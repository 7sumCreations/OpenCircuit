package io.github.opencircuit.ringkit

// Sampling-coverage measurement for the rich export. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/ExportCoverage.swift (@ b1c2fdd), whole.
//
// WHAT THIS IS: a statement about the data WE HOLD over a window — "we have 312 of the 336 epochs this
// window could contain, and the biggest hole is 47 minutes". It is NOT an estimate of what the ring
// recorded: a missing stretch can equally mean the ring wasn't worn, wasn't drained yet, or the epochs
// were lost. Reporting it as a MEASUREMENT of our own holdings is the only honest framing, which is why
// the coverage fraction is clamped and can never exceed 1.0.
//
// The default cadence is the ring's own 150 s epoch step (`BulkRecord.EPOCH_SECONDS`), so "expected"
// counts epochs, not wall-clock guesses.
//
// Shape notes: cadence, minimum gap and gap lengths are `Double` seconds (Swift `TimeInterval`), so a
// NaN or infinite parameter behaves as upstream's comparisons do. The expected count is 64-bit and
// saturates where upstream traps (a cadence so small that `window / cadence` leaves 64 bits). Every
// span and gap is `timeIntervalSince` as Swift computes it — the difference of the two dates' doubles
// (`dateSecondsBetween`) — because the export prints these seconds with 17 significant digits.

import java.time.Instant
import java.util.Collections
import kotlin.math.floor

object ExportCoverage {

    /** A stretch of the window with no stored sample. */
    data class Gap(val start: Instant, val end: Instant) {
        val seconds: Double get() = dateSecondsBetween(start, end)
    }

    /** What we hold over `[windowStart, windowEnd]`. Its gap list is copied in and read-only out. */
    class Assessment(
        val windowStart: Instant,
        val windowEnd: Instant,
        /** `floor(window / cadence)`, never negative. 0 for a degenerate window. */
        val expectedSamples: Long,
        /** Distinct in-window sample instants: two rows at one instant cover the same epoch and count once. */
        val observedSamples: Int,
        /** `observed / expected`, clamped to 0…1; 0 when [expectedSamples] is 0. */
        val coverageFraction: Double,
        gaps: List<Gap>,
        /** The widest gap's seconds, 0 when there is none. */
        val longestGapSeconds: Double,
    ) {
        /** Ascending, only holes STRICTLY longer than the minimum gap. */
        val gaps: List<Gap> = Collections.unmodifiableList(ArrayList(gaps))

        override fun equals(other: Any?): Boolean =
            other is Assessment && windowStart == other.windowStart && windowEnd == other.windowEnd &&
                expectedSamples == other.expectedSamples && observedSamples == other.observedSamples &&
                coverageFraction == other.coverageFraction && gaps == other.gaps && longestGapSeconds == other.longestGapSeconds

        override fun hashCode(): Int =
            listOf(windowStart, windowEnd, expectedSamples, observedSamples, ieeeHash(coverageFraction), gaps, ieeeHash(longestGapSeconds)).hashCode()

        override fun toString(): String =
            "Assessment(windowStart=$windowStart, windowEnd=$windowEnd, expectedSamples=$expectedSamples, " +
                "observedSamples=$observedSamples, coverageFraction=$coverageFraction, gaps=$gaps, longestGapSeconds=$longestGapSeconds)"
    }

    /**
     * Assess how much of `[from, to]` (inclusive) [sampleTimes] covers. [cadence] is the expected
     * spacing in seconds; [minGap] the shortest silence (seconds) worth reporting — a step must be
     * STRICTLY longer. Two epochs by default, so one missed epoch is jitter, not a hole. A non-positive
     * window or cadence is degenerate: nothing expected, nothing observed, no gaps.
     */
    fun assess(
        sampleTimes: List<Instant>,
        from: Instant,
        to: Instant,
        cadence: Double = BulkRecord.EPOCH_SECONDS.toDouble(),
        minGap: Double = 2.0 * BulkRecord.EPOCH_SECONDS,
    ): Assessment {
        val span = dateSecondsBetween(from, to)
        if (!(span > 0 && cadence > 0)) {
            return Assessment(from, to, expectedSamples = 0, observedSamples = 0, coverageFraction = 0.0, gaps = emptyList(), longestGapSeconds = 0.0)
        }
        // Swift `max(0, Int((span / cadence).rounded(.down)))`; `toLong` saturates where Swift traps.
        val expected = maxOf(0L, floor(span / cadence).toLong())

        val inWindow = ArrayList<Instant>()
        for (t in sampleTimes.sorted()) {
            if (t < from || t > to) continue
            if (inWindow.lastOrNull() != t) inWindow += t // sorted input → dedupe adjacent
        }

        val gaps = ArrayList<Gap>()
        var cursor = from
        for (t in inWindow) {
            if (dateSecondsBetween(cursor, t) > minGap) gaps += Gap(cursor, t)
            cursor = t
        }
        if (dateSecondsBetween(cursor, to) > minGap) gaps += Gap(cursor, to)

        val fraction = if (expected > 0) swiftMin(1.0, swiftMax(0.0, inWindow.size.toDouble() / expected.toDouble())) else 0.0
        return Assessment(
            windowStart = from,
            windowEnd = to,
            expectedSamples = expected,
            observedSamples = inWindow.size,
            coverageFraction = fraction,
            gaps = gaps,
            longestGapSeconds = gaps.maxOfOrNull { it.seconds } ?: 0.0,
        )
    }
}
