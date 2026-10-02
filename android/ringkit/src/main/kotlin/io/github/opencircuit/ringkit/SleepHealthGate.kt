package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepHealthGate.swift (@ b1c2fdd): the gate
// for mirroring a night's sleep to the health store.
//
// With periodic overnight draining the staged night GROWS as epochs arrive, and the health-store sleep
// watermark keys off the latest segment end — so writing a still-in-progress night on each drain would
// lay down OVERLAPPING sleep samples. The write waits until the night is "settled": its latest segment
// ended far enough in the past that it won't advance again. The watermark then blocks any re-write of
// that same settled night, so it lands exactly once.
//
// "Ended at least the margin ago" is judged as the gap from the segment end to now, so no instant
// arithmetic can leave `Instant`'s range (a Swift Date never overflows; `Instant.minus` would throw).

import java.time.Duration
import java.time.Instant

object SleepHealthGate {

    /**
     * How long after the last staged epoch a night is considered done growing. One epoch is 150 s and a
     * drain can lag a few minutes, so 20 min clears "the block might still extend" without holding a
     * finished night back into the next day.
     */
    val SETTLE_MARGIN: Duration = Duration.ofMinutes(20)

    /** Whether the night ending at [latestSegmentEnd] is settled enough to mirror. Null (no segments) is never settled. */
    fun isSettled(latestSegmentEnd: Instant?, now: Instant, margin: Duration = SETTLE_MARGIN): Boolean {
        if (latestSegmentEnd == null) return false
        // upstream: end <= now - margin
        return Duration.between(latestSegmentEnd, now) >= margin
    }

    /**
     * Whether a night is safe to mirror now. Ordinary drains still require the quiet margin; an
     * authoritative finalization signal (a sleep focus ending, the wearer saving an edit) may write
     * immediately. A finalization signal never fabricates sleep: real segments are still required.
     */
    fun isReadyToWrite(latestSegmentEnd: Instant?, now: Instant, finalized: Boolean, margin: Duration = SETTLE_MARGIN): Boolean {
        if (latestSegmentEnd == null) return false
        return finalized || isSettled(latestSegmentEnd, now, margin)
    }
}
