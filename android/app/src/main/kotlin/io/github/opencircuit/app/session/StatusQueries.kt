package io.github.opencircuit.app.session

import io.github.opencircuit.ringkit.AndroidDrainTiming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a status query (`d0 00 00`) is still waiting for its answer. The ring answers one with a
 * `0x10` / `0x87` descriptor or with a `0x50` frame (PROTOCOL.md §4), and a `0x50` says nothing
 * that tells it from the end of the history: one landing after a history open would be read by
 * order alone. So before its first open the drain waits ([awaitQuiet]) for any outstanding query's
 * answer, or [AndroidDrainTiming.STATUS_ANSWER_WAIT] after the query, whichever comes first.
 *
 * A query is outstanding from [onQuery] — each keepalive `d0`, and each time the link turns
 * `Authenticated` (the link writes its own `d0` right after its auth reply, PORTING.md D-257) —
 * until [onAnswer] (any `0x10`, `0x87` or `0x50` frame). Any thread.
 */
class StatusQueries(private val monotonicMillis: () -> Long) {
    /** When the outstanding query was written, or null when none is outstanding. */
    private val outstandingSince = MutableStateFlow<Long?>(null)

    /** A status query was just written (or the link just authenticated and wrote its own). */
    fun onQuery() {
        outstandingSince.value = monotonicMillis()
    }

    /** A frame that answers a status query arrived. */
    fun onAnswer() {
        outstandingSince.value = null
    }

    /** Returns at once with no query outstanding; otherwise at its answer, or 2 s after it was written. */
    suspend fun awaitQuiet() {
        val since = outstandingSince.value ?: return
        val left = since + AndroidDrainTiming.STATUS_ANSWER_WAIT.toMillis() - monotonicMillis()
        if (left <= 0) return
        withTimeoutOrNull(left) { outstandingSince.first { it == null } }
    }
}
