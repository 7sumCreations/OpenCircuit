package io.github.opencircuit.ringkit

// Auto-reconnect backoff policy. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/ReconnectBackoff.swift:14-51 (@ b1c2fdd).
//
// The connection layer re-issues a connect on every disconnect; left unbounded, a ring on the
// charger (it accepts a connect then immediately drops it) keeps the radio armed in a tight
// reconnect loop for hours. This grows the delay between consecutive failed reconnects and tells the
// UI when to stop saying "Connecting…" and surface a calm "ring unreachable / charging" note instead.
// Pure: the connection layer owns the attempt counter and resets it on a real, frame-delivering
// connection.
//
// NOTE: the calm state is derived from elapsed ATTEMPTS, NOT a decoded charging-flag byte — that
// descriptor bit is not decoded, so we never claim to know the ring is charging.

import java.time.Duration
import java.util.Collections

object ReconnectBackoff {

    /**
     * Delay before the next reconnect, indexed by consecutive failed attempts: 1 s → 5 s → 30 s,
     * then capped at 30 s. Reset the attempt counter on a successful, frame-delivering connect.
     * Read-only: a cast back to a mutable list cannot change the schedule.
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:18`).
     */
    val DELAYS: List<Duration> =
        Collections.unmodifiableList(listOf(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)))

    /**
     * Backoff delay before reconnect attempt [attempt] (1-based). Attempt 0 (or less) means "no
     * failures yet" → reconnect immediately. Past the table it stays at the cap.
     */
    fun delay(attempt: Int): Duration {
        if (attempt <= 0) return Duration.ZERO
        return DELAYS[minOf(attempt - 1, DELAYS.size - 1)]
    }

    /**
     * Cap on the backoff delay while the app is BACKGROUNDED. The full 30 s cap assumes the process
     * stays alive to finish the wait — upstream measured that iOS suspends the app ~10 s after the
     * disconnect event, and a suspension mid-backoff leaves no standing pending connect, so the ring
     * coming back in range wakes nothing and the rest of the night is lost. 8 s fits inside
     * upstream's background-task allowance with margin.
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:33`).
     */
    val BACKGROUND_DELAY_CAP: Duration = Duration.ofSeconds(8)

    /**
     * Backoff delay for attempt [attempt], capped at [BACKGROUND_DELAY_CAP] when [inBackground] —
     * the charger-flap damping is a foreground luxury; in the background, re-arming the wake path
     * before suspension always wins.
     */
    fun delay(attempt: Int, inBackground: Boolean): Duration {
        val d = delay(attempt)
        return if (inBackground) minOf(d, BACKGROUND_DELAY_CAP) else d
    }

    /**
     * After this many consecutive failed reconnect attempts, the UI should swap the permanent
     * "Connecting…" for a calm "ring unreachable / charging — will reconnect automatically".
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:45`).
     */
    const val CALM_STATE_ATTEMPT_THRESHOLD: Int = 3

    /** Whether enough reconnects have failed to surface the calm state (vs. "Connecting…"). */
    fun shouldSurfaceCalmState(attempts: Int): Boolean = attempts >= CALM_STATE_ATTEMPT_THRESHOLD
}
