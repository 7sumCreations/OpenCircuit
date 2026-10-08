package io.github.opencircuit.app.ring

import io.github.opencircuit.app.sync.ChannelProgress
import io.github.opencircuit.app.sync.StoredData
import io.github.opencircuit.app.sync.StoredLastNight
import io.github.opencircuit.app.sync.SyncLogEntries
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.app.sync.SyncState
import io.github.opencircuit.ringkit.SyncMeasurement
import io.github.opencircuit.store.SyncLogEntry
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Ring data card shows. */
data class RingDataUi(
    /** "Syncing…", "Last synced 2 days ago" or "Not synced yet". */
    val headline: String,
    /** A sync is running: the button is off and the help says to keep the app open. */
    val syncing: Boolean = false,
    /** The last sync's row: "4,312 records · complete"; null before the first. */
    val lastSync: String? = null,
    /** What went wrong with the last sync, in the user's words; null when nothing did. */
    val problem: String? = null,
    /** Whether Sync now can be tapped. */
    val syncEnabled: Boolean = false,
    /** The button's label. */
    val syncLabel: String = SYNC_NOW,
    /** Why Sync now cannot be tapped right now (a live measure runs); null when nothing blocks it. */
    val syncBlockedBy: String? = null,
    /** While a sync runs, one line per channel so far ("Sleep channel done · 18 records"); empty otherwise. */
    val progress: List<String> = emptyList(),
    /** The "Disconnect after syncing" switch. */
    val disconnectAfterSync: Boolean = true,
    /** The help line under the switch. */
    val help: String = STEPS_HELP,
    /** "12 Sep – 8 Oct": the stored samples' first and last day; null when nothing is stored. */
    val storedRange: String? = null,
    /** How many nights are stored; null when nothing is stored. */
    val nights: String? = null,
    /** "23:41 → 07:02 · 7 h 21 m": the latest stored night; null when none is. */
    val lastNight: String? = null,
    /** What the last sync left for later: held-back records and the channel that held them, nights waiting. */
    val notices: List<String> = emptyList(),
    /** The overdue-sync warning; null when the ring is not overdue. */
    val overdue: String? = null,
    /** How urgent [overdue] is. */
    val overdueLevel: SyncMeasurement.OverdueLevel? = null,
)

/** What the Ring data card is built from. */
data class RingDataInput(
    /** The session's sync state (the running sync's progress); null when there is no ring. */
    val sync: SyncState? = null,
    /** The sync log, oldest first (kept across launches). */
    val log: List<SyncLogEntry> = emptyList(),
    /** What the store holds; null when nothing is. */
    val stored: StoredData? = null,
    /** The firmware version the ring reports now; null when not read on this connection. */
    val liveFirmware: String? = null,
    /** The "Disconnect after syncing" switch. */
    val disconnectAfterSync: Boolean = true,
    val now: Instant,
    /** The phone's zone: the stored days and the night's times are local. */
    val zone: ZoneId = ZoneOffset.UTC,
    /** A live measure runs: Sync now is disabled and says why. */
    val measuring: Boolean = false,
)

/** The button's resting label. */
const val SYNC_NOW = "Sync now"

/** Steps and skin temperature are not in the ring's history (PROTOCOL.md §5.4). */
const val STEPS_HELP = "Steps and skin temperature are only recorded while the ring is connected."

/**
 * Builds the Ring data card from a [RingDataInput]: the running sync, the sync log, what is stored,
 * the ring's firmware, the switch, the time and the zone; pure, so every state is tested on the JVM.
 */
object RingDataPresenter {

    /**
     * The card from [input]. While a sync runs: its progress and the keep-the-app-open help. Else,
     * from the sync log's newest entry (so the same after a relaunch): when, the result, what went
     * wrong, what was left for later (held-back records and the channel that held them, nights
     * waiting); what is stored; and the overdue warning, measured from the least-drained channel
     * (PORTING.md D-273). Sync now is enabled with a ring and no live measure.
     */
    fun present(input: RingDataInput): RingDataUi {
        val sync = input.sync
        val disconnectAfterSync = input.disconnectAfterSync
        if (sync?.syncing == true) {
            return RingDataUi(
                headline = SYNCING,
                syncing = true,
                lastSync = null,
                syncEnabled = false,
                syncLabel = SYNCING,
                disconnectAfterSync = disconnectAfterSync,
                help = if (disconnectAfterSync) KEEP_OPEN_THEN_DISCONNECT else KEEP_OPEN,
                progress = sync.channels.map(::progressLine),
            )
        }
        val last = input.log.lastOrNull()
        val outcome = last?.let { entry -> SyncOutcome.entries.firstOrNull { it.name == entry.outcome } }
        val overdue = SyncMeasurement.overdue(SyncLogEntries.generation(input.liveFirmware, input.log), SyncLogEntries.oldestOnRing(input.log), input.now)
        val stored = input.stored
        return RingDataUi(
            headline = when {
                last == null -> NOT_SYNCED
                last.paused -> PAUSED
                else -> "Last synced ${ago(last.finishedAt, input.now)}"
            },
            lastSync = last?.let { lastSyncRow(it, outcome) },
            problem = last?.let { problemOf(outcome) ?: lostFrames(it.undeliveredFrames) },
            notices = last?.let(::notices).orEmpty(),
            syncEnabled = sync != null && !input.measuring,
            syncBlockedBy = if (sync != null && input.measuring) STOP_MEASURING_TO_SYNC else null,
            disconnectAfterSync = disconnectAfterSync,
            storedRange = stored?.let { range(it.oldest, it.newest, input.zone) },
            nights = stored?.let { count(it.nights) },
            lastNight = stored?.lastNight?.let { lastNight(it, input.zone) },
            overdue = overdue?.let { "Your ring keeps about ${it.keepsDays} days of data. Sync ${if (it.level == SyncMeasurement.OverdueLevel.RED) "now" else "soon"} so nothing is lost." },
            overdueLevel = overdue?.level,
        )
    }

    /** "4,312 records · complete", "Up to date", "12 records · partial — data kept, will retry" or "No data received". */
    private fun lastSyncRow(entry: SyncLogEntry, outcome: SyncOutcome?): String {
        val stored = entry.recordsStored
        return when (outcome) {
            SyncOutcome.COMPLETE -> if (stored == 0) UP_TO_DATE else "${records(stored)} · complete"
            // Pages came and are kept on the phone, whether or not the commit stored them.
            SyncOutcome.SAVE_FAILED, SyncOutcome.COMMIT_FAILED -> "${records(stored)} · $PARTIAL"
            else -> if (stored == 0 && entry.heldBack == 0) NO_DATA else "${records(stored)} · $PARTIAL"
        }
    }

    /** What the sync left for later: the records held back and the channels that held them, then nights waiting. */
    private fun notices(entry: SyncLogEntry): List<String> {
        val notices = mutableListOf<String>()
        val holding = SyncLogEntries.heldBackBy(entry)
        if (holding.isNotEmpty()) {
            val why = holding.joinToString(" and ") { "the ${channelName(it.label)} ${reasonWords(it.reason)}" }
            notices += "${records(entry.heldBack)} waiting — $why"
        }
        if (entry.nightsWaiting > 0) {
            notices += "${count(entry.nightsWaiting)} ${if (entry.nightsWaiting == 1) "night" else "nights"} waiting — saved by a later sync"
        }
        return notices
    }

    private fun channelName(label: String): String = when (label) {
        "sleep" -> "sleep channel"
        "all-day" -> "all-day channel"
        else -> "channel $label"
    }

    private fun reasonWords(reason: SyncLogEntries.HoldReason): String = when (reason) {
        SyncLogEntries.HoldReason.NOT_REACHED -> "wasn't reached"
        SyncLogEntries.HoldReason.NO_ANSWER -> "didn't answer"
        SyncLogEntries.HoldReason.NOT_FINISHED -> "didn't finish"
    }

    private fun lostFrames(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "1 frame from the ring wasn't read — sync again"
        else -> "${count(n)} frames from the ring weren't read — sync again"
    }

    /** "12 Sep – 8 Oct"; both years when they differ; one day once. Days are local to [zone]. */
    private fun range(oldest: Instant?, newest: Instant?, zone: ZoneId): String? {
        if (oldest == null || newest == null) return null
        val from = oldest.atZone(zone).toLocalDate()
        val to = newest.atZone(zone).toLocalDate()
        if (from == to) return DAY.format(from)
        return if (from.year == to.year) "${DAY.format(from)} – ${DAY.format(to)}" else "${DAY_YEAR.format(from)} – ${DAY_YEAR.format(to)}"
    }

    /** "23:41 → 07:02 · 7 h 21 m": asleep and awake in [zone], and the time asleep. */
    private fun lastNight(night: StoredLastNight, zone: ZoneId): String {
        val asleep = night.asleepMinutes.coerceAtLeast(0)
        return "${TIME.format(night.onset.atZone(zone))} → ${TIME.format(night.wake.atZone(zone))} · ${asleep / 60} h ${asleep % 60} m"
    }

    private fun records(n: Int): String = "${count(n)} ${if (n == 1) "record" else "records"}"

    /**
     * One channel's line while a sync runs: "Sleep channel done · 18 records", "All-day channel
     * 120 of 723 records · about 6 min left", or "… N records so far" before a countdown is known.
     */
    internal fun progressLine(p: ChannelProgress): String {
        val name = when (p.label) {
            "sleep" -> "Sleep channel"
            "all-day" -> "All-day channel"
            else -> "Channel ${p.label}"
        }
        if (p.done) return "$name done · ${count(p.records)} records"
        val expected = p.expected ?: return "$name ${count(p.records)} records so far"
        val left = when (val eta = p.etaSeconds) {
            null -> ""
            in 0 until 60 -> " · less than a minute left"
            else -> " · about ${(eta + 59) / 60} min left"
        }
        return "$name ${count(p.records)} of ${count(expected)} records$left"
    }

    private fun problemOf(outcome: SyncOutcome?): String? = when (outcome) {
        null, SyncOutcome.COMPLETE, SyncOutcome.PARTIAL -> null
        SyncOutcome.SAVE_FAILED, SyncOutcome.COMMIT_FAILED -> "Couldn't save — will retry"
        SyncOutcome.NOT_CONNECTED -> "Couldn't reach the ring — try again"
        SyncOutcome.OPEN_FAILED -> "The ring link refused the sync — try again"
        SyncOutcome.NO_ACK -> "The ring didn't answer the sync request"
    }

    /** "just now", "5 min ago", "3 h ago", "1 day ago", "2 days ago". A time ahead of [now] reads "just now". */
    internal fun ago(at: Instant, now: Instant): String {
        val seconds = Duration.between(at, now).seconds
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3_600} h ago"
            seconds < 2 * 86_400 -> "1 day ago"
            else -> "${seconds / 86_400} days ago"
        }
    }

    /** ASCII digits with a comma every three, whatever the phone's locale ("4,312"). */
    private fun count(n: Int): String = String.format(Locale.ROOT, "%,d", n)

    private const val PAUSED = "Sync paused — open OpenCircuit to finish"
    private const val UP_TO_DATE = "Up to date"
    private const val NO_DATA = "No data received"
    private const val PARTIAL = "partial — data kept, will retry"

    /** Day and month in English (the app's one language), whatever the phone's locale. */
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private const val STOP_MEASURING_TO_SYNC = "Stop measuring to sync"
    private const val NOT_SYNCED = "Not synced yet"
    private const val SYNCING = "Syncing…"
    private const val KEEP_OPEN = "Keep the app open until the sync finishes."
    private const val KEEP_OPEN_THEN_DISCONNECT = "Keep the app open until the sync finishes. It disconnects when your data is saved."
}
