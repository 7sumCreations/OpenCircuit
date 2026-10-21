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
)

/** Everything [TodayPresenter.present] needs; [stored] is null until the first load finishes. */
data class TodayInput(
    val stored: TodayStored?,
    val session: TodaySession,
    val now: Instant,
    val zone: ZoneId,
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

    fun present(input: TodayInput): TodayUiState {
        val session = input.session
        val stored = input.stored
        val neverSynced = session.log.isEmpty()
        val live = session.live
        val storedHr = reading(HEART_RATE, BPM, stored?.heartRate, stored != null, neverSynced, input) { it.roundToInt().toString() }
        val storedSpo2 = reading(SPO2, PERCENT, stored?.spo2, stored != null, neverSynced, input) { (it * 100).roundToInt().toString() }
        return TodayUiState(
            loading = stored == null,
            heartRate = heartRate(storedHr, live, session.syncing),
            heartRateLive = live.mode == LiveMode.HEART_RATE,
            spo2 = spo2(storedSpo2, live),
            spo2Measure = spo2Button(session),
            lastSynced = if (neverSynced) NOT_SYNCED else "Last synced ${RingDataPresenter.ago(session.log.last().finishedAt, input.now)}",
            ring = session.link?.let { LinkStatePresenter.present(it, session.ringName ?: RING).headline } ?: NO_RING,
            strip = SyncStripPresenter.present(session.syncing, session.channels, firstSync = neverSynced),
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
        live.mode == LiveMode.HEART_RATE -> stored.copy(status = WARMING_UP)
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
        if (session.log.isEmpty() && !session.syncing) return TodayOverdueUi(NEVER_SYNCED, TodayAlertLevel.NEVER_SYNCED, canSync = true)
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
