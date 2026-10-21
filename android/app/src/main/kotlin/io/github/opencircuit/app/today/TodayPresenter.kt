package io.github.opencircuit.app.today

import io.github.opencircuit.app.ring.LinkStatePresenter
import io.github.opencircuit.app.ring.RingDataPresenter
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.QuantitySample
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
    /** "as of 14:02 · 3 h ago", "No reading yet", "Waiting for the first sync", or empty before the first load. */
    val note: String = "",
)

/** The Today screen's state. */
data class TodayUiState(
    /** True only before the first load finishes; never again after it. */
    val loading: Boolean = true,
    val heartRate: ReadingUi = ReadingUi(HEART_RATE, unit = BPM),
    val spo2: ReadingUi = ReadingUi(SPO2, unit = PERCENT),
    /** "Last synced 2 h ago", "Syncing…" or "Not synced yet". */
    val lastSynced: String = NOT_SYNCED,
    /** The ring's link in a word ("Connected"), or where to pair one. */
    val ring: String = NO_RING,
)

/** What the ring session contributes to Today: its link, its name, its sync log and whether a sync runs. */
data class TodaySession(
    val link: LinkState? = null,
    val ringName: String? = null,
    val log: List<SyncLogEntry> = emptyList(),
    val syncing: Boolean = false,
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

/**
 * Today's words from what is stored and what the session reports. Pure: the clock and the zone are
 * inputs, and every number and date prints with an explicit locale.
 */
object TodayPresenter {

    fun present(input: TodayInput): TodayUiState {
        val session = input.session
        val stored = input.stored
        val neverSynced = session.log.isEmpty()
        return TodayUiState(
            loading = stored == null,
            heartRate = reading(HEART_RATE, BPM, stored?.heartRate, stored != null, neverSynced, input) { it.roundToInt().toString() },
            spo2 = reading(SPO2, PERCENT, stored?.spo2, stored != null, neverSynced, input) { (it * 100).roundToInt().toString() },
            lastSynced = when {
                session.syncing -> SYNCING
                neverSynced -> NOT_SYNCED
                else -> "Last synced ${RingDataPresenter.ago(session.log.last().finishedAt, input.now)}"
            },
            ring = session.link?.let { LinkStatePresenter.present(it, session.ringName ?: RING).headline } ?: NO_RING,
        )
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
    private const val SYNCING = "Syncing…"
    private const val WAITING = "Waiting for the first sync"
    private const val NO_READING = "No reading yet"
    private const val RING = "the ring"
}
