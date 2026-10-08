package io.github.opencircuit.app.ring

import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.app.sync.SyncState
import java.time.Duration
import java.time.Instant
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
    /** The "Disconnect after syncing" switch. */
    val disconnectAfterSync: Boolean = true,
    /** The help line under the switch. */
    val help: String = STEPS_HELP,
)

/** The button's resting label. */
const val SYNC_NOW = "Sync now"

/** Steps and skin temperature are not in the ring's history (PROTOCOL.md §5.4). */
const val STEPS_HELP = "Steps and skin temperature are only recorded while the ring is connected."

/**
 * Builds the Ring data card from the session's sync state (null when there is no ring), the
 * switch and [now]; pure, so every state is tested on the JVM.
 */
object RingDataPresenter {

    fun present(sync: SyncState?, disconnectAfterSync: Boolean, now: Instant): RingDataUi {
        if (sync == null) return RingDataUi(headline = NOT_SYNCED, disconnectAfterSync = disconnectAfterSync)
        if (sync.syncing) {
            return RingDataUi(
                headline = SYNCING,
                syncing = true,
                lastSync = null,
                syncEnabled = false,
                syncLabel = SYNCING,
                disconnectAfterSync = disconnectAfterSync,
                help = if (disconnectAfterSync) KEEP_OPEN_THEN_DISCONNECT else KEEP_OPEN,
            )
        }
        val last = sync.last
        return RingDataUi(
            headline = if (last == null) NOT_SYNCED else "Last synced ${ago(last.finishedAt, now)}",
            lastSync = last?.commit?.let { "${count(it.records)} records · ${outcomeWords(last.outcome)}" },
            problem = last?.let { problemOf(it.outcome) },
            syncEnabled = true,
            disconnectAfterSync = disconnectAfterSync,
        )
    }

    private fun outcomeWords(outcome: SyncOutcome): String = when (outcome) {
        SyncOutcome.COMPLETE -> "complete"
        else -> "partial — data kept, will retry"
    }

    private fun problemOf(outcome: SyncOutcome): String? = when (outcome) {
        SyncOutcome.COMPLETE, SyncOutcome.PARTIAL -> null
        SyncOutcome.SAVE_FAILED, SyncOutcome.COMMIT_FAILED -> "Couldn't save — will retry"
        SyncOutcome.NOT_CONNECTED -> "Couldn't reach the ring — try again"
        SyncOutcome.OPEN_FAILED -> "The ring link refused the sync — try again"
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

    private const val NOT_SYNCED = "Not synced yet"
    private const val SYNCING = "Syncing…"
    private const val KEEP_OPEN = "Keep the app open until the sync finishes."
    private const val KEEP_OPEN_THEN_DISCONNECT = "Keep the app open until the sync finishes. It disconnects when your data is saved."
}
