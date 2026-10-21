package io.github.opencircuit.app.today

import io.github.opencircuit.app.live.LiveMeasureState
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.MeasureFailure
import io.github.opencircuit.app.ring.LinkStatePresenter
import io.github.opencircuit.app.ring.RingDataPresenter
import io.github.opencircuit.app.sync.ChannelProgress
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SyncMeasurement
import io.github.opencircuit.store.SyncLogEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One reading in the hero: its name, its number (null when none is stored), its unit and when it was taken. */
data class ReadingUi(
    val label: String,
    val value: String? = null,
    val unit: String,
    /** "as of 14:02 · 3 h ago", "Live now", "No reading yet", "Waiting for the first sync", or empty before the first load. */
    val note: String = "",
    /** A second line about the live state: "Live heart rate after sync", "Live · warming up", "Measuring · …"; null when none. */
    val status: String? = null,
)

/** The hero's SpO₂ button: Measure (off with [reason] when it cannot run) or Stop while it measures. */
data class MeasureButtonUi(
    val label: String = MEASURE,
    val enabled: Boolean = false,
    /** Why it is off; null when it is on. */
    val reason: String? = null,
    /** The button stops the running SpO₂ measure. */
    val stops: Boolean = false,
)

/** How Today's sync alert reads: the ring's overdue warning (amber, red) or a ring never synced. */
enum class TodayAlertLevel { AMBER, RED, NEVER_SYNCED }

/** Today's overdue chip: the Ring data card's own warning words, or the never-synced line; [canSync] offers Sync now. */
data class TodayOverdueUi(val text: String, val level: TodayAlertLevel, val canSync: Boolean)

/** The Today screen's state. */
data class TodayUiState(
    /** True only before the first load finishes; never again after it. */
    val loading: Boolean = true,
    val heartRate: ReadingUi = ReadingUi(HEART_RATE, unit = BPM),
    val spo2: ReadingUi = ReadingUi(SPO2, unit = PERCENT),
    /** "Last synced 2 h ago" or "Not synced yet" — in every state, a running sync too. */
    val lastSynced: String = NOT_SYNCED,
    /** The ring's link in a word ("Connected"), or where to pair one. */
    val ring: String = NO_RING,
    /** The sync strip while a sync runs; null (collapsed to [lastSynced]) otherwise. */
    val strip: SyncStripUi? = null,
    /** The overdue chip; null when the ring is not overdue (or there is no ring). */
    val overdue: TodayOverdueUi? = null,
    /** The hero's heart rate is live: a heart-rate measure runs. */
    val heartRateLive: Boolean = false,
    /** The hero's SpO₂ button. */
    val spo2Measure: MeasureButtonUi = MeasureButtonUi(),
    /** The metric tiles, in the dashboard's order; empty before the first load (the screen shows same-size placeholders). */
    val tiles: List<TileUi> = emptyList(),
    /** The readiness ring; null before the first load. */
    val readiness: ReadinessUi? = null,
    /** The one-sentence read of today; null before the first load (and on any day but today). */
    val synthesis: String? = null,
    /** Today's date in words ("Wednesday 30 September"). */
    val dateLine: String = "",
    /**
     * The day the tiles, readiness and hero range on screen are for; null before the first load.
     * It can trail [DayBrowserUi.selected] while a newly picked day loads: the screen shows that
     * day's page as placeholders until the two agree, never the day before's values as its own.
     */
    val day: LocalDate? = null,
    /** The day browser: the days there are, the one picked, and the marked dates. */
    val browser: DayBrowserUi = DayBrowserUi(),
    /** On a past day, its heart-rate range in place of the live hero; null on today. */
    val pastDay: PastDayHeroUi? = null,
    /** The open tile's trend screen; null with none open. */
    val detail: TileDetailUi? = null,
    /** [day]'s Calories card; null before the first load. */
    val calories: CaloriesUi? = null,
    /** [day]'s four goal rings; null before the first load. */
    val goals: GoalsUi? = null,
    /** The order of Today's sections, as saved (read before the first frame). */
    val sections: List<DashboardSection> = DashboardSection.entries.toList(),
    /** The order of the tiles, as saved; [tiles] are in it. */
    val tileOrder: List<TodayTile.Metric> = TodayTile.Metric.entries.toList(),
    /** Edit order is on: the page lists sections and tiles with move buttons instead of the cards. */
    val editingOrder: Boolean = false,
    /** The one-time "Health Connect export is now off" notice shows (on today's page only). */
    val healthNotice: Boolean = false,
)

/** What the ring session contributes to Today: its link, name, sync log, the running sync, the ring's firmware and the live measure. */
data class TodaySession(
    val link: LinkState? = null,
    val ringName: String? = null,
    val log: List<SyncLogEntry> = emptyList(),
    val syncing: Boolean = false,
    /** The running sync's channels so far; empty when none runs. */
    val channels: List<ChannelProgress> = emptyList(),
    /** The firmware version the ring reports on this connection; null when not read. */
    val liveFirmware: String? = null,
    /** The live measure (the automatic heart rate, or the user's SpO₂). */
    val live: LiveMeasureState = LiveMeasureState(),
    /** The kept sync log has been read ([log] is what it holds); before that, and when the read failed, nothing is claimed from it. */
    val logLoaded: Boolean = true,
)

/** What the dashboard reads from the session: the last logged sync's end, and whether the log has been read. */
data class DashboardFacts(val lastSyncAt: Instant?, val syncKnown: Boolean) {
    companion object {
        fun of(session: TodaySession) = DashboardFacts(session.log.lastOrNull()?.finishedAt, session.logLoaded)
    }
}

/** Everything [TodayPresenter.present] needs; [stored] is null until the first load finishes. */
data class TodayInput(
    val stored: TodayStored?,
    val session: TodaySession,
    val now: Instant,
    val zone: ZoneId,
    /**
     * The dashboard already built from [stored] ([TodayPresenter.dashboard]), so a live frame or a
     * sync's progress does not rebuild it; null to build it here.
     */
    val dashboard: DashboardUi? = null,
    /** The day the user picked; null for Today (which follows the clock). */
    val picked: LocalDate? = null,
    /** Dates marked because data landed on them while another day was open. */
    val marked: Set<LocalDate> = emptySet(),
)

internal const val HEART_RATE = "Heart rate"
internal const val SPO2 = "SpO₂"
internal const val BPM = "bpm"
internal const val PERCENT = "%"
internal const val NOT_SYNCED = "Not synced yet"
internal const val NO_RING = "No ring yet — pair one on the Ring tab"
internal const val MEASURE = "Measure"

/**
 * Today's words from what is stored and what the session reports. Pure: the clock and the zone are
 * inputs, and every number and date prints with an explicit locale.
 */
object TodayPresenter {

    /**
     * The dashboard (tiles, readiness, sentence) for the day the load is for (today when it names
     * none) from what is stored and the two facts of the sync log it reads; placeholders' state (no
     * tiles) before the first load.
     */
    fun dashboard(stored: TodayStored?, facts: DashboardFacts, now: Instant, zone: ZoneId): DashboardUi {
        val today = LocalDate.ofInstant(now, zone)
        return stored?.let { TodayDashboard.present(it.trends, it.day ?: today, now, zone, facts.lastSyncAt, facts.syncKnown, it.activity, it.naps) }
            ?: DashboardUi(dateLine = TodayDashboard.dateLine(today))
    }

    /** The browser: from the first stored day (or the picked day, if earlier) to today; a picked day not before today is today. */
    private fun browser(input: TodayInput): DayBrowserUi {
        val today = LocalDate.ofInstant(input.now, input.zone)
        val selected = input.picked?.takeIf { it.isBefore(today) } ?: today
        val stored = BrowsableDays.of(input.stored?.earliest, input.now, input.zone)
        val days = if (selected.isBefore(stored.first)) BrowsableDays(selected, stored.last) else stored
        return DayBrowser.present(days, selected, today, input.marked - selected)
    }

    fun present(input: TodayInput): TodayUiState {
        val session = input.session
        val stored = input.stored
        val neverSynced = session.log.isEmpty()
        val live = session.live
        val storedHr = reading(HEART_RATE, BPM, stored?.heartRate, stored != null, neverSynced, input) { it.roundToInt().toString() }
        val storedSpo2 = reading(SPO2, PERCENT, stored?.spo2, stored != null, neverSynced, input) { (it * 100).roundToInt().toString() }
        val dashboard = input.dashboard ?: dashboard(stored, DashboardFacts.of(session), input.now, input.zone)
        return TodayUiState(
            tiles = dashboard.tiles,
            readiness = dashboard.readiness,
            synthesis = dashboard.synthesis,
            dateLine = dashboard.dateLine,
            // From the dashboard, not the load: the two can be one emission apart, and the day must name the values shown.
            day = dashboard.day,
            pastDay = dashboard.pastDay,
            calories = dashboard.calories,
            goals = dashboard.goals,
            browser = browser(input),
            loading = stored == null,
            heartRate = heartRate(storedHr, live, session.syncing),
            heartRateLive = live.mode == LiveMode.HEART_RATE,
            spo2 = spo2(storedSpo2, live),
            spo2Measure = spo2Button(session),
            lastSynced = if (neverSynced) NOT_SYNCED else "Last synced ${RingDataPresenter.ago(session.log.last().finishedAt, input.now)}",
            ring = session.link?.let { LinkStatePresenter.present(it, session.ringName ?: RING).headline } ?: NO_RING,
            strip = SyncStripPresenter.present(session.syncing, session.channels, firstSync = neverSynced && session.logLoaded),
            overdue = overdue(input),
        )
    }

    /**
     * The hero's heart rate: live once a heart-rate measure runs (the stored number stays while it
     * warms up; the live one replaces it on lock), else the stored one — with "Live heart rate after
     * sync" while a sync runs (no live measure during a sync, ever).
     */
    private fun heartRate(stored: ReadingUi, live: LiveMeasureState, syncing: Boolean): ReadingUi = when {
        live.mode == LiveMode.HEART_RATE && live.newest != null ->
            stored.copy(value = live.newest.toString(), note = LIVE_NOW, status = if (live.stale) NO_SIGNAL else null)
        // A new budget warms up again: the reading this session just settled stays, never the older stored one.
        live.mode == LiveMode.HEART_RATE -> live.heartRate.lastValue?.let { stored.copy(value = it.toString(), note = LIVE_NOW, status = WARMING_UP) }
            ?: stored.copy(status = WARMING_UP)
        syncing -> stored.copy(status = LIVE_AFTER_SYNC)
        else -> stored
    }

    /** The hero's SpO₂: the value being measured while the user's measure runs, else the stored one (and why the last measure gave none). */
    private fun spo2(stored: ReadingUi, live: LiveMeasureState): ReadingUi = when {
        live.mode == LiveMode.SPO2 -> stored.copy(value = live.newest?.toString() ?: stored.value, status = MEASURING)
        else -> when (live.spo2.failure) {
            null -> stored
            is MeasureFailure.NoReading -> stored.copy(status = NO_SPO2_READING)
            else -> stored.copy(status = SPO2_FAILED)
        }
    }

    /** Measure (SpO₂ by tap only), off with the reason while a sync runs or the ring is not connected; Stop while it measures. */
    private fun spo2Button(session: TodaySession): MeasureButtonUi = when {
        session.live.mode == LiveMode.SPO2 -> MeasureButtonUi(label = STOP, enabled = true, stops = true)
        session.link != LinkState.Authenticated -> MeasureButtonUi(reason = CONNECT_TO_MEASURE)
        session.syncing -> MeasureButtonUi(reason = AFTER_SYNC)
        else -> MeasureButtonUi(enabled = true)
    }

    /**
     * The overdue chip: the Ring data card's own warning ([RingDataPresenter.overdueWarning], the
     * per-model thresholds measured from the least-drained channel) — or, for a ring with no sync
     * yet, the never-synced line. Nothing without a ring, and no never-synced line while the first
     * sync runs (the strip says so).
     */
    private fun overdue(input: TodayInput): TodayOverdueUi? {
        val session = input.session
        if (session.link == null) return null
        val warning = RingDataPresenter.overdueWarning(session.liveFirmware, session.log, input.now, session.syncing)
        if (warning != null) {
            val level = if (warning.level == SyncMeasurement.OverdueLevel.RED) TodayAlertLevel.RED else TodayAlertLevel.AMBER
            return TodayOverdueUi(warning.text, level, canSync = !session.syncing)
        }
        // Only once the kept log has been read: before (or after a failed read) an empty log proves nothing.
        if (session.logLoaded && session.log.isEmpty() && !session.syncing) return TodayOverdueUi(NEVER_SYNCED, TodayAlertLevel.NEVER_SYNCED, canSync = true)
        return null
    }

    private fun reading(
        label: String,
        unit: String,
        sample: QuantitySample?,
        loaded: Boolean,
        neverSynced: Boolean,
        input: TodayInput,
        number: (Double) -> String,
    ): ReadingUi = when {
        !loaded -> ReadingUi(label, unit = unit)
        sample == null -> ReadingUi(label, unit = unit, note = if (neverSynced) WAITING else NO_READING)
        else -> ReadingUi(label, number(sample.value), unit, "as of ${whenTaken(sample.start, input)} · ${RingDataPresenter.ago(sample.start, input.now)}")
    }

    /** "14:02" today, "Mon 19 Oct" on an earlier (or later) day, in [TodayInput.zone]. */
    private fun whenTaken(at: Instant, input: TodayInput): String {
        val local = at.atZone(input.zone)
        return if (local.toLocalDate() == input.now.atZone(input.zone).toLocalDate()) TIME.format(local) else DAY.format(local)
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private const val LIVE_NOW = "Live now"
    private const val WARMING_UP = "Live · warming up"
    private const val NO_SIGNAL = "Live · no signal from the ring"
    private const val LIVE_AFTER_SYNC = "Live heart rate after sync"
    private const val MEASURING = "Measuring · keep your hand still"
    private const val NO_SPO2_READING = "No reading — keep the ring snug and try again"
    private const val SPO2_FAILED = "Couldn't measure — try again"
    private const val STOP = "Stop"
    private const val CONNECT_TO_MEASURE = "Connect the ring to measure"
    private const val AFTER_SYNC = "Available after sync"
    private const val NEVER_SYNCED = "This ring hasn't synced yet. Sync to bring its history to your phone."
    private const val WAITING = "Waiting for the first sync"
    private const val NO_READING = "No reading yet"
    private const val RING = "the ring"
}
