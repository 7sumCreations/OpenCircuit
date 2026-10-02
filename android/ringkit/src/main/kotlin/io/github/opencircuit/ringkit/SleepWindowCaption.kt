package io.github.opencircuit.ringkit

// THE "Asleep 1:24 AM–12:27 PM" LINE — and the one night it was lying on. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepWindowCaption.swift (@ b1c2fdd).
//
// A Gen 2 Air tester's card read "4h 0m asleep / 4h 21m in bed / Asleep 1:24 AM–12:27 PM · 2m to fall
// asleep". The totals were right; the CAPTION was wrong: 1:24 AM–12:27 PM is an 11 h 3 m span, and an
// en-dash between two clock times asserts a CONTINUOUS block, over a night with a multi-hour hole. The
// card already suppressed its own in-bed range on exactly this night; this caption was the third site
// of the same rule and the only one that never got it. Hence `isContiguous`: ONE predicate the callers
// share, so they cannot drift apart again.
//
// The copy follows the confidence copy's rules: name the measurement, never a cause ("nothing was
// recorded"), and state the gap, never an inferred total — the gapped line quotes the SPAN (certain:
// two timestamps) and says part of it holds no records; it does NOT quote a gap duration, because
// `span - measuredInBed` is a bound, not a value. "between X and Y" instead of "X–Y" is the whole fix.
//
// The strings are upstream's, character for character (U+2013 en dash in the range, U+00B7 middle dot
// as the separator). Every number is a whole integer rendered by Kotlin's locale-free `toString`, as
// Swift's interpolation renders it. The CLOCK format is the caller's, injected.

import java.time.Instant

/**
 * The sleep-card caption under the stage legend: when the wearer was asleep, and — when the window
 * cannot be presented as one continuous block — that part of it holds no records.
 */
object SleepWindowCaption {

    /**
     * How far a wall-clock span may exceed the summed in-bed time before the night is treated as
     * STITCHED rather than continuous. 15 % is slack for epoch-boundary rounding (150 s cadence) and the
     * awake tail a staging pass trims, NOT a gap allowance; deliberately loose in the safe direction.
     */
    const val CONTIGUOUS_TOLERANCE: Double = 1.15

    /**
     * Can [span] (seconds) be presented as one continuous recorded interval? [measuredInBed] is the
     * GAP-EXCLUDED in-bed total in seconds (`SleepStaging.Summary.inBed`). Answers `true` when
     * [measuredInBed] is zero, negative or NaN: with no basis to test against there is no positive
     * evidence of a gap, and every caller's default is the plain rendering.
     */
    fun isContiguous(span: Double, measuredInBed: Double): Boolean {
        if (!(measuredInBed > 0)) return true
        return span <= measuredInBed * CONTIGUOUS_TOLERANCE
    }

    /** Least sleep latency (seconds) worth printing. */
    internal const val MINIMUM_LATENCY: Double = 60.0

    /** Most latency (seconds) that can be a measurement rather than an artifact of a late-starting archive. */
    internal const val MAXIMUM_LATENCY: Double = 4.0 * 3600

    /**
     * The caption, or null when there is no real asleep window to describe.
     *
     * [onset]: first asleep epoch; null (a legacy stored row) yields null. [wake]: last asleep epoch;
     * the caption needs `wake > onset`. [inBedStart]: start of the in-bed window, for the sleep-latency
     * clause. [measuredInBed]: gap-excluded in-bed seconds. [clock]: renders an instant as a short local
     * time, injected so the caption and the times printed elsewhere on the card match.
     */
    fun line(
        onset: Instant?,
        wake: Instant?,
        inBedStart: Instant?,
        measuredInBed: Double,
        clock: (Instant) -> String,
    ): String? {
        if (onset == null || wake == null || !wake.isAfter(onset)) return null
        val span = secondsBetween(onset, wake)

        if (!isContiguous(span, measuredInBed)) {
            // No latency clause here on purpose: beside "part of this was never recorded", "2m to fall
            // asleep" reads as a precision the night does not have.
            return "Asleep between ${clock(onset)} and ${clock(wake)} · nothing was recorded across " +
                "part of that ${SleepConfidence.approximateDuration(span)} window"
        }

        val parts = mutableListOf("Asleep ${clock(onset)}–${clock(wake)}")
        if (inBedStart != null) {
            val latency = secondsBetween(inBedStart, onset)
            if (latency >= MINIMUM_LATENCY && latency < MAXIMUM_LATENCY) {
                // Swift `Int((latency / 60).rounded())`; the latency is bounded, so this never traps.
                parts += "${roundHalfAwayFromZero(latency / 60).toLong()}m to fall asleep"
            }
        }
        return parts.joinToString(separator = " · ")
    }
}
