package io.github.opencircuit.app.session

import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendFailure
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.KeepaliveCadence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Why the last keepalive write did not go out. Shown on the connection card until one does. */
sealed interface KeepaliveProblem {
    /** The link refused the write. */
    data class Refused(val reason: RefusalReason) : KeepaliveProblem

    /** The write failed. */
    data class Failed(val reason: SendFailure) : KeepaliveProblem
}

/**
 * Keeps an idle link warm and the ring's status fresh with `d0 00 00` — the status query, which
 * the ring answers with its `0x10` descriptor (PROTOCOL.md §5.4) — and nothing else.
 *
 * - While the link is [LinkState.Authenticated] and no live measure runs: one `d0 00 00` as soon
 *   as that holds, then one every [KeepaliveCadence.interval] by day (180 s). Upstream primes the
 *   keepalive with `01 00 00` and writes `07 00 00` outside its sleep window
 *   (`ios/OpenCircuit/BLE/RingSession.swift:1220-1261` @ b1c2fdd); here the link runs its own
 *   auth and there is no history drain to bank the pages a `07` could move (PORTING.md D-232, D-233).
 * - While a measure runs: nothing (the measure's entry writes its own `d0`).
 * - When a measure stops: the status refresh — one `d0 00 00` at once and, if no descriptor with
 *   a battery answers within 750 ms, one more at the next tick, the 30 s cadence kept around a
 *   live read; then the 180 s cadence. Upstream's refresh ladder writes `07 00 00` and
 *   `01 00 00` (RingSession.swift:4666-4722); this one does not (PORTING.md D-240).
 *
 * A refused or failed write is logged and kept in [problem] until a write goes through or the
 * link leaves `Authenticated`; the cadence carries on.
 */
class KeepaliveTicker(
    private val send: suspend (ByteArray) -> SendResult,
    private val scope: CoroutineScope,
    private val linkState: StateFlow<LinkState>,
    private val isMeasuring: StateFlow<Boolean>,
    /** How many descriptors with a battery have arrived; a change means the ring answered. */
    private val batteryReadings: () -> Int,
    private val log: (String) -> Unit,
) {
    private val problemFlow = MutableStateFlow<KeepaliveProblem?>(null)
    private val started = AtomicBoolean(false)

    /** Why the last keepalive write did not go out, or null when it did. */
    val problem: StateFlow<KeepaliveProblem?> = problemFlow.asStateFlow()

    /** Starts following the link and the measure. Calling it again does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Both are touched only by this one collector, one value at a time.
            var wasMeasuring = false
            var refreshPending = false
            combine(linkState, isMeasuring) { state, measuring -> (state == LinkState.Authenticated) to measuring }
                .distinctUntilChanged()
                .collectLatest { (authenticated, measuring) ->
                    if (wasMeasuring && !measuring) refreshPending = true
                    wasMeasuring = measuring
                    // Off the authenticated link the card shows the link's own words; a status
                    // query refused as the link went down is not kept beside them.
                    if (!authenticated) problemFlow.value = null
                    if (!authenticated || measuring) return@collectLatest
                    val refresh = refreshPending
                    refreshPending = false
                    keepWarm(refresh)
                }
        }
    }

    /** Runs until the link leaves `Authenticated` or a measure starts (the collector cancels it). */
    private suspend fun keepWarm(refresh: Boolean) {
        val readingsBefore = batteryReadings()
        statusQuery()
        if (refresh) {
            delay(REFRESH_WAIT_MILLIS)
            val answered = batteryReadings() != readingsBefore
            delay((if (answered) IDLE_MILLIS else RETRY_MILLIS) - REFRESH_WAIT_MILLIS)
            statusQuery()
        }
        while (true) {
            delay(IDLE_MILLIS)
            statusQuery()
        }
    }

    private suspend fun statusQuery() {
        problemFlow.value = when (val result = send(Command.statusQuery)) {
            SendResult.Sent -> null
            is SendResult.Refused -> {
                log("keepalive: status query refused (${result.reason.name})")
                KeepaliveProblem.Refused(result.reason)
            }
            is SendResult.Failed -> {
                log("keepalive: status query failed (${result.reason.name})")
                KeepaliveProblem.Failed(result.reason)
            }
        }
    }

    private companion object {
        /** The idle cadence: the day value, as E8 has no night window or battery saver (180 s). */
        val IDLE_MILLIS = KeepaliveCadence.interval(isNight = false, activeMeasurement = false, batterySaver = false).toMillis()

        /** The tick kept around a live read: where an unanswered refresh is asked again (30 s). */
        val RETRY_MILLIS = KeepaliveCadence.interval(isNight = false, activeMeasurement = true, batterySaver = false).toMillis()

        /** How long a status request waits for its descriptor (RingSession.swift:4693). */
        const val REFRESH_WAIT_MILLIS = 750L
    }
}
