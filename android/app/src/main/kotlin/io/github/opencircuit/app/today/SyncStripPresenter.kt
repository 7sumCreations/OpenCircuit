package io.github.opencircuit.app.today

import io.github.opencircuit.app.ring.RingDataPresenter
import io.github.opencircuit.app.sync.ChannelProgress

/** Today's sync strip while a sync runs: its line and how far the current channel is (null: not known yet). */
data class SyncStripUi(val text: String, val fraction: Float?)

/**
 * The sync strip's words and bar from the running sync's channels. Pure. The line is the Ring data
 * card's own progress line for the channel being drained ([RingDataPresenter.progressLine]); the bar
 * is determinate once that channel's first page has said how many records it holds, and
 * indeterminate before, between channels and at the commit. No strip when no sync runs: Today shows
 * "Last synced …" then.
 */
object SyncStripPresenter {
    fun present(syncing: Boolean, channels: List<ChannelProgress>, firstSync: Boolean): SyncStripUi? {
        if (!syncing) return null
        val head = if (firstSync) FIRST_SYNC else SYNCING
        val current = channels.lastOrNull() ?: return SyncStripUi("$head…", null)
        val expected = current.expected
        val fraction = if (!current.done && expected != null && expected > 0) (current.records.toFloat() / expected).coerceIn(0f, 1f) else null
        return SyncStripUi("$head · ${RingDataPresenter.progressLine(current)}", fraction)
    }

    private const val SYNCING = "Syncing"
    private const val FIRST_SYNC = "First sync"
}
