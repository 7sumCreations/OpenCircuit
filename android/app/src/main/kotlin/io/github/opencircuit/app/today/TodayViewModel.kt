package io.github.opencircuit.app.today

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.github.opencircuit.app.ring.everyMinute
import io.github.opencircuit.app.session.SessionHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel(scope) {

    /** The Today screen's state. */
    val uiState: StateFlow<TodayUiState>

    /** Dates marked because data landed on them while another day was open; a visit clears its mark. */
    private val marks = MutableStateFlow<Set<LocalDate>>(emptySet())

    /** Today's own actions (pull to refresh, the hero's Measure / Stop, the overdue chip's Sync now, the day browser); tabs are the host's. */
    fun onAction(action: TodayAction) {
        when (action) {
            TodayAction.Refresh -> commands.refresh()
            TodayAction.MeasureSpO2 -> commands.measureSpO2()
            TodayAction.StopMeasure -> commands.stopMeasure()
            TodayAction.OpenRing -> Unit
            is TodayAction.SelectDay -> select(action.day)
            TodayAction.ShowToday -> select(today())
        }
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
        var previous: TodayStored? = null
        // One load per revision and per day shown; a new request cancels the one it replaces.
        val stored: Flow<TodayStored?> = combine(source?.revisions() ?: flowOf(0L), shown) { _, day -> day }
            .transformLatest<LocalDate, TodayStored?> { day ->
                if (source == null) {
                    emit(TodayStored())
                } else {
                    try {
                        val z = zone()
                        val loaded = source.load(day, z)
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
        val dashboard = combine(loads, facts, ticks) { s, f, _ -> TodayPresenter.dashboard(s, f, wallClock(), zone()) }
        val browsing = combine(picked, marks) { p, m -> p to m }
        uiState = combine(loads, parts, dashboard, ticks, browsing) { s, parts, built, _, (p, m) ->
            TodayPresenter.present(TodayInput(s, parts, wallClock(), zone(), built, picked = p, marked = m))
        }.stateIn(scope, SharingStarted.Eagerly, TodayUiState())
    }

    private companion object {
        /** The picked past day's epoch day in the saved state; absent on Today. */
        const val PICKED_DAY = "today.pickedDay"
    }
}

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
