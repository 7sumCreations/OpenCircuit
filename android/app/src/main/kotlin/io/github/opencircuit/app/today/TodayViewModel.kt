package io.github.opencircuit.app.today

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.github.opencircuit.app.data.AppPrefs
import io.github.opencircuit.app.health.HealthUpgradeNotice
import io.github.opencircuit.app.ring.everyMinute
import io.github.opencircuit.app.session.SessionHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Hosts the Today screen's state: what is stored (reloaded on each [TodaySource.revisions]), what
 * the ring session reports ([session]) and the clock, through [TodayPresenter]. [scope] becomes the
 * view model's scope (the app passes a main-thread scope; tests pass a virtual-time one).
 *
 * The state starts loading and leaves it after the first load; from then on a reload never blanks
 * the screen: while one runs, or when one fails, the last values stay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val source: TodaySource?,
    session: Flow<TodaySession> = flowOf(TodaySession()),
    scope: CoroutineScope,
    /** The phone's wall clock, for the readings' ages and "Last synced". */
    private val wallClock: () -> Instant = Instant::now,
    /** The phone's time zone, for "today" and the times shown. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Re-reads [wallClock]: ages move on while nothing else changes. */
    clockTicks: Flow<Unit> = everyMinute(),
    private val log: (String) -> Unit = {},
    /** What Today's own actions do to the ring session. */
    private val commands: TodayCommands = TodayCommands.NONE,
    /** Where the picked day lives, so it survives the activity being recreated and the process being killed. */
    private val saved: SavedStateHandle,
    /** Where Today's section and tile orders are saved; null keeps the default order, unsaved. */
    private val prefs: AppPrefs? = null,
    /** The one-time "Health Connect export is now off" notice; null: none (tests that are not about it). */
    private val healthNotice: HealthUpgradeNotice? = null,
) : ViewModel(scope) {

    /** The Today screen's state. */
    val uiState: StateFlow<TodayUiState>

    /** Dates marked because data landed on them while another day was open; a visit clears its mark. */
    private val marks = MutableStateFlow<Set<LocalDate>>(emptySet())

    /** The saved order of sections and tiles, read here, synchronously, so the first state already has it. */
    private val order = MutableStateFlow(
        TodayOrder(DashboardSectionOrder.decode(prefs?.todaySectionOrder.orEmpty()), TileOrder.decode(prefs?.todayTileOrder)),
    )

    /** Edit order is on. */
    private val editing = MutableStateFlow(false)

    /** Today's own actions (pull to refresh, the hero's Measure / Stop, the overdue chip's Sync now, the day browser); tabs are the host's. */
    fun onAction(action: TodayAction) {
        when (action) {
            TodayAction.Refresh -> commands.refresh()
            TodayAction.MeasureSpO2 -> commands.measureSpO2()
            TodayAction.StopMeasure -> commands.stopMeasure()
            TodayAction.OpenRing -> Unit
            is TodayAction.SelectDay -> select(action.day)
            TodayAction.ShowToday -> select(today())
            is TodayAction.OpenDetail -> saved[DETAIL] = action.metric.name
            TodayAction.CloseDetail -> saved[DETAIL] = null
            is TodayAction.DetailRange -> if (action.days in TileDetailPresenter.RANGES) saved[DETAIL_DAYS] = action.days
            is TodayAction.EditOrder -> editing.value = action.on
            is TodayAction.MoveSection -> reorder { it.copy(sections = moved(it.sections, action.section, action.by)) }
            is TodayAction.MoveTile -> reorder { it.copy(tiles = TileOrder.moved(it.tiles, action.metric, action.by)) }
            TodayAction.ResetOrder -> reorder { TodayOrder(DashboardSectionOrder.decode(""), TileOrder.decode(null)) }
            // Open Settings from the notice dismisses it too; the host opens the tab.
            TodayAction.DismissHealthNotice, TodayAction.OpenSettings -> healthNotice?.dismiss()
        }
    }

    /** [section] moved one place ([by] −1 up, +1 down) through upstream's move rule; past either end, unchanged. */
    private fun moved(sections: List<DashboardSection>, section: DashboardSection, by: Int): List<DashboardSection> {
        val from = sections.indexOf(section)
        if (from < 0 || from + by !in sections.indices) return sections
        // Upstream's destination counts positions before the move: one down is two past the start.
        return DashboardSectionOrder.reordered(sections, sections, from, if (by < 0) from + by else from + by + 1)
    }

    /**
     * Applies [change] and saves the order it changed — only that one, so a move is one write and a
     * move past an end none. A failed save keeps the new order for this run and says so in the log.
     */
    private fun reorder(change: (TodayOrder) -> TodayOrder) {
        val before = order.value
        val next = order.updateAndGet(change)
        val p = prefs ?: return
        var saved = true
        if (next.sections != before.sections) saved = p.setTodaySectionOrder(DashboardSectionOrder.encode(next.sections))
        if (next.tiles != before.tiles) saved = p.setTodayTileOrder(TileOrder.encode(next.tiles)) && saved
        if (!saved) log("Today could not save its order")
    }

    private fun today(): LocalDate = LocalDate.ofInstant(wallClock(), zone())

    /**
     * A past day is kept as its date, so only the user moves it; today (or later) is kept as no
     * date at all, so Today follows the clock into the next day.
     */
    private fun select(day: LocalDate) {
        // Set to null, never removed: a removed key stops updating the flow already handed out.
        saved[PICKED_DAY] = if (day.isBefore(today())) day.toEpochDay() else null
        marks.update { it - day }
    }

    init {
        val ticks = clockTicks.shareIn(scope, SharingStarted.Eagerly, replay = 1)
        val picked: Flow<LocalDate?> = saved.getStateFlow<Long?>(PICKED_DAY, null).map { it?.let(LocalDate::ofEpochDay) }
        // The day on screen: the picked one, or today — re-read on every tick, so Today turns over at local midnight.
        val shown = combine(picked, ticks) { p, _ -> p ?: today() }.distinctUntilChanged()
        // The open detail (a metric name) and its range; an unknown name (an older build's) is no detail.
        val detail: Flow<Pair<TodayTile.Metric, Int>?> = combine(
            saved.getStateFlow<String?>(DETAIL, null),
            saved.getStateFlow(DETAIL_DAYS, TodayTiles.WINDOW_DAYS),
        ) { name, days -> TodayTile.Metric.entries.firstOrNull { it.name == name }?.let { it to days } }.distinctUntilChanged()
        // The tiles read two weeks; an open 30-day trend widens the one load to thirty days.
        val window = detail.map { maxOf(TodayTiles.WINDOW_DAYS, it?.second ?: 0) }.distinctUntilChanged()
        var previous: TodayStored? = null
        // One load per revision, day shown and window; a new request cancels the one it replaces.
        val stored: Flow<TodayStored?> = combine(source?.revisions() ?: flowOf(0L), shown, window) { _, day, w -> day to w }
            .transformLatest<Pair<LocalDate, Int>, TodayStored?> { (day, w) ->
                if (source == null) {
                    emit(TodayStored())
                } else {
                    try {
                        val z = zone()
                        val loaded = source.load(day, z, w)
                        marks.update { (it + DayMarks.landed(previous, loaded, z)) - day }
                        previous = loaded
                        emit(loaded)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The last values stay on screen; the next revision tries again.
                        log("Today could not read the store: ${e::class.java.simpleName}")
                    }
                }
            }
            .catch { e -> log("Today stopped watching the store: ${e::class.java.simpleName}") }
            .onStart { emit(null) }
        // The dashboard follows the store, the two sync-log facts it reads and the clock — never a
        // live frame or a sync's progress, so those do not rebuild it or redraw a tile.
        // Each read twice below, each run once: one load per revision, one session collection.
        val loads = stored.shareIn(scope, SharingStarted.Eagerly, replay = 1)
        val parts = session.shareIn(scope, SharingStarted.Eagerly, replay = 1)
        val facts = parts.map(DashboardFacts::of).distinctUntilChanged()
        val built = combine(loads, facts, ticks) { s, f, _ -> TodayPresenter.dashboard(s, f, wallClock(), zone()) }
        // The tiles are put in the saved order once per rebuild or move: a live frame never re-sorts them, and a move never rebuilds them.
        val dashboard = combine(built, order.map { it.tiles }.distinctUntilChanged()) { d, tileOrder ->
            d.copy(tiles = d.tiles.sortedBy { tileOrder.indexOf(it.metric) })
        }
        // The detail is built from a load for its day and at least its window; until one lands it waits, never shows another day's.
        val detailUi = combine(loads, detail, shown, ticks) { s, open, day, _ ->
            open?.let { (metric, days) ->
                val ready = s != null && s.day == day && s.windowDays >= days
                TileDetailPresenter.present(if (ready) s!!.trends else TodayTrends(), metric, days, day, wallClock(), zone())
                    .copy(loading = !ready)
            }
        }
        val notice: Flow<Boolean> = healthNotice?.visible ?: flowOf(false)
        val arrangement = combine(order, editing, notice) { o, e, n -> Arrangement(o, e, n) }
        val browsing = combine(picked, marks, detailUi, arrangement) { p, m, d, a -> Browsing(p, m, d, a) }
        uiState = combine(loads, parts, dashboard, ticks, browsing) { s, parts, built, _, b ->
            ordered(TodayPresenter.present(TodayInput(s, parts, wallClock(), zone(), built, picked = b.picked, marked = b.marked)).copy(detail = b.detail), b.arrangement)
        }.stateIn(scope, SharingStarted.Eagerly, ordered(TodayUiState(), Arrangement(order.value, editing = false, notice = healthNotice?.visible?.value == true)))
    }

    /** What the user chose on Today besides the stored data: the picked day, the marks, the open detail, and the page's arrangement. */
    private data class Browsing(val picked: LocalDate?, val marked: Set<LocalDate>, val detail: TileDetailUi?, val arrangement: Arrangement)

    /** The saved order, Edit order, and whether the one-time Health Connect notice shows. */
    private data class Arrangement(val order: TodayOrder, val editing: Boolean, val notice: Boolean)

    /** [state] with the saved order, Edit order and the notice (its tiles are already in that order: the dashboard sorted them). */
    private fun ordered(state: TodayUiState, arrangement: Arrangement): TodayUiState = state.copy(
        sections = arrangement.order.sections,
        tileOrder = arrangement.order.tiles,
        editingOrder = arrangement.editing,
        healthNotice = arrangement.notice,
    )

    private companion object {
        /** The picked past day's epoch day in the saved state; null on Today. */
        const val PICKED_DAY = "today.pickedDay"

        /** The open tile detail's metric name; null with none open. */
        const val DETAIL = "today.detail"

        /** The open detail's range in days (14 or 30). */
        const val DETAIL_DAYS = "today.detailDays"
    }
}

/** Today's order: its sections and its tiles, each a full list. */
data class TodayOrder(val sections: List<DashboardSection>, val tiles: List<TodayTile.Metric>)

/** What Today shows of the ring session: the current session's link, name, sync log and running sync. */
@OptIn(ExperimentalCoroutinesApi::class)
fun todaySession(sessions: SessionHost?): Flow<TodaySession> =
    (sessions?.current ?: flowOf(null)).flatMapLatest { session ->
        if (session == null) {
            flowOf(TodaySession())
        } else {
            val log = combine(session.syncRecords.entries, session.syncRecords.logRead) { entries, read -> entries to read }
            combine(session.state, log, session.sync.state, session.liveMeasure.state, session.link.info) { link, (entries, read), sync, live, info ->
                TodaySession(
                    link = link,
                    ringName = session.link.ring.name,
                    log = entries,
                    // An entry recorded this session is a sync too, read or not.
                    logLoaded = read || entries.isNotEmpty(),
                    syncing = sync.syncing,
                    channels = sync.channels,
                    liveFirmware = info.firmware.version.takeIf(String::isNotEmpty),
                    live = live,
                )
            }
        }
    }
