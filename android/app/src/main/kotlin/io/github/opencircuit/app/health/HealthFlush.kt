package io.github.opencircuit.app.health

import io.github.opencircuit.health.FlushResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** What the Health Connect card reads of the writes. */
data class HealthFlushState(
    /** A write is running. */
    val running: Boolean = false,
    /** The last write's result; null before the first. */
    val last: FlushResult? = null,
    /** Why the last write stopped before it could report, as the failure's kind (never a value); null when it reported. */
    val error: String? = null,
)

/**
 * Starts Health Connect writes: after each committed sync ([io.github.opencircuit.app.sync.HistoryDrainController]'s
 * after-commit hook) and on the user's "Write to Health Connect now". [request] returns at once;
 * the write runs in [scope] — the app's process scope, never a session's or a screen's, so a sync's
 * pause, the end of a session or a closed screen never cuts it (a write cut by the process ending
 * resumes next time: the store's marks move only past what Health Connect accepted).
 *
 * One write at a time: a request while one runs is kept, and one more write runs after it (any
 * number of such requests make one), so samples stored meanwhile are never left waiting for the
 * next sync. Upstream drops such a request (`flushToHealth`'s `isFlushing` guard).
 */
class HealthFlush(
    private val flush: suspend () -> FlushResult,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    /** Settings' "Send to Health Connect" switch ([HealthSwitch.isOn]); while it is false nothing is written. */
    private val enabled: () -> Boolean,
) {
    private val requested = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val stateFlow = MutableStateFlow(HealthFlushState())

    /** Running, the last result, the last error. */
    val state: StateFlow<HealthFlushState> = stateFlow.asStateFlow()

    /**
     * Asks for a write; returns at once. With the switch OFF nothing happens and nothing is kept for
     * later: every caller (the post-commit seam, Write now, the re-queue below) is gated here.
     */
    fun request() {
        if (!enabled()) return
        requested.set(true)
        if (!running.compareAndSet(false, true)) return
        stateFlow.update { it.copy(running = true) }
        scope.launch {
            try {
                // Re-checked before each pass: a write queued behind the running one does not start once turned OFF.
                while (enabled() && requested.getAndSet(false)) runOnce()
            } finally {
                // Released on every exit: a write that threw or was cancelled never blocks the next request.
                stateFlow.update { it.copy(running = false) }
                running.set(false)
            }
            // A request that came between the last check and the release.
            if (requested.get()) request()
        }
    }

    private suspend fun runOnce() {
        try {
            val result = flush()
            stateFlow.update { it.copy(last = result, error = null) }
            log(
                "Health Connect: ${result.recordsWritten} records written" +
                    (if (result.rejected.isNotEmpty()) ", ${result.rejected.values.sum()} dropped by the platform's rules" else "") +
                    (if (result.notGranted.isNotEmpty()) ", not allowed: ${result.notGranted.joinToString()}" else "") +
                    (if (result.failures.isNotEmpty()) ", failed: ${result.failures.keys.joinToString()}" else "") +
                    (if (result.catchingUp) ", catching up" else "") +
                    (if (!result.available) ", Health Connect not available" else ""),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val kind = e::class.java.simpleName
            stateFlow.update { it.copy(error = kind) }
            log("Health Connect write stopped: $kind; what was not written stays pending")
        }
    }
}
