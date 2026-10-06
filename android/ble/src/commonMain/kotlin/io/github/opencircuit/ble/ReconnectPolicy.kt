package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.ReconnectBackoff
import java.time.Duration

/**
 * When and how the link tries again after a connection failed or dropped (PORTING.md D-186).
 * Pure: the link owns the attempt count and feeds it in.
 *
 * Always by the ring's address, never by scanning. The count is upstream's `reconnectAttempts`
 * (`ios/OpenCircuit/BLE/RingScanner.swift:206-262`, `:1035-1099` @ b1c2fdd): every failure
 * schedules attempt `count + 1` after `ReconnectBackoff.delay` (1 s, 5 s, then 30 s), and only a
 * connection that stayed up [STABLE_AFTER] and delivered a frame resets it to 0.
 *
 * Upstream re-issues one CoreBluetooth connect, which never expires. Android has two kinds: a
 * direct connect (fast, but it scans hard and gives up after 30 s) and a standing `autoConnect`
 * connection (slow and cheap, no timeout). The link connects directly first, and arms the
 * standing connection once three reconnects have failed or a failure shows the ring is out of
 * reach; a standing connection that fails before connecting goes back to direct.
 */
internal object ReconnectPolicy {
    /** Status 133 (`GATT_ERROR`): Android reports it for most connections that could not be opened. */
    const val GATT_ERROR: Int = 133

    /** Status 147 (`GATT_CONNECTION_TIMEOUT`): the stack's own 30 s direct-connect timeout. */
    const val GATT_CONNECTION_TIMEOUT: Int = 147

    /** A 133 this long or longer after the connect started is a timeout (ring absent), not a refusal. */
    val LATE_FAILURE_AFTER: Duration = Duration.ofSeconds(20)

    /** How long a connection must stay up, having delivered a frame, before the count resets (upstream `:228`). */
    val STABLE_AFTER: Duration = Duration.ofSeconds(6)

    /** How a connection ended. */
    enum class Failure {
        /** A failed or dropped connection. */
        FAILED,

        /** A connection that could not be opened because the ring is out of reach. */
        UNREACHABLE,

        /** A standing connection that failed before it connected. */
        STANDING_FAILED,
    }

    /** The next attempt: its number, how long to wait for it, and whether it is the standing connection. */
    data class Plan(val attempt: Int, val delay: Duration, val standing: Boolean)

    /** Classifies a connection that failed to open with [status]; [late] when it started [LATE_FAILURE_AFTER] ago or more. */
    fun classifyOpenFailure(status: Int, late: Boolean): Failure =
        if (status == GATT_CONNECTION_TIMEOUT || (status == GATT_ERROR && late)) Failure.UNREACHABLE else Failure.FAILED

    /** The attempt after [failure], when [attempts] attempts were counted before it. */
    fun next(attempts: Int, failure: Failure): Plan {
        val attempt = if (failure == Failure.STANDING_FAILED) maxOf(attempts, 1) else attempts + 1
        val standing = when (failure) {
            Failure.UNREACHABLE -> true
            // `attempt - 1` reconnects have been made and failed.
            Failure.FAILED -> ReconnectBackoff.shouldSurfaceCalmState(attempt - 1)
            Failure.STANDING_FAILED -> false
        }
        // The link runs in the foreground until background work exists, so never the background cap.
        return Plan(attempt, ReconnectBackoff.delay(attempt, inBackground = false), standing)
    }
}
