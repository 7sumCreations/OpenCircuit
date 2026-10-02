package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin-only hostile-input checks for the export coverage assessment: non-finite, negative and
 * zero cadences and minimum gaps, samples on the window's bounds, a window spanning most of time, and
 * a cadence so small that the expected count leaves 64 bits. Kept out of the upstream-port class so
 * its count stays exact.
 *
 * Every expected value was measured on upstream's pinned Swift build, except where a Kotlin bound
 * replaces an upstream trap; that case says so.
 */
class ExportCoverageHazardTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(s: Long): Instant = t0.plusSeconds(s)
    private val five = (0L until 5L).map { at(it * 150) }

    private fun gaps(a: ExportCoverage.Assessment): List<Pair<Long, Long>> =
        a.gaps.map { (it.start.epochSecond - t0.epochSecond) to (it.end.epochSecond - t0.epochSecond) }

    @Test
    fun unusableCadencesAreDegenerateOrExpectNothing() {
        for (cadence in listOf(Double.NaN, -150.0)) {
            val a = ExportCoverage.assess(five, t0, at(750), cadence = cadence)
            assertEquals(0L, a.expectedSamples, "cadence $cadence")
            assertEquals(0, a.observedSamples)
            assertEquals(0.0, a.coverageFraction)
            assertEquals(emptyList(), a.gaps)
        }
        // An infinite cadence expects no sample but still counts what it holds.
        val inf = ExportCoverage.assess(five, t0, at(750), cadence = Double.POSITIVE_INFINITY)
        assertEquals(0L, inf.expectedSamples)
        assertEquals(5, inf.observedSamples)
        assertEquals(0.0, inf.coverageFraction)
        assertEquals(emptyList(), inf.gaps)
    }

    @Test
    fun minimumGapEdgesAsUpstream() {
        val nan = ExportCoverage.assess(five + at(5000), t0, at(6000), minGap = Double.NaN)
        assertEquals(40L, nan.expectedSamples)
        assertEquals(6, nan.observedSamples)
        assertEquals(0.15, nan.coverageFraction)
        assertEquals(emptyList(), nan.gaps, "a NaN minimum never reports a gap")

        val neg = ExportCoverage.assess(five, t0, at(750), minGap = -1.0)
        assertEquals(listOf(0L to 0L, 0L to 150L, 150L to 300L, 300L to 450L, 450L to 600L, 600L to 750L), gaps(neg), "a negative minimum reports even a zero-length step")
        assertEquals(150.0, neg.longestGapSeconds)

        val zero = ExportCoverage.assess(five, t0, at(750), minGap = 0.0)
        assertEquals(listOf(0L to 150L, 150L to 300L, 300L to 450L, 450L to 600L, 600L to 750L), gaps(zero))

        val inf = ExportCoverage.assess(five, t0, at(6000), minGap = Double.POSITIVE_INFINITY)
        assertEquals(0.125, inf.coverageFraction)
        assertEquals(emptyList(), inf.gaps)

        val exact = ExportCoverage.assess(listOf(t0, at(300), at(601)), t0, at(601))
        assertEquals(listOf(300L to 601L), gaps(exact), "a step of exactly the minimum is not a gap")
        assertEquals(301.0, exact.longestGapSeconds)
    }

    @Test
    fun samplesOnTheBoundsCountAndAHugeWindowStaysFinite() {
        val bounds = ExportCoverage.assess(listOf(t0, at(750)), t0, at(750))
        assertEquals(5L, bounds.expectedSamples)
        assertEquals(2, bounds.observedSamples)
        assertEquals(0.4, bounds.coverageFraction)
        assertEquals(listOf(0L to 750L), gaps(bounds))

        val far = ExportCoverage.assess(listOf(Instant.EPOCH), Instant.ofEpochSecond(-10_000_000_000_000_000), Instant.ofEpochSecond(10_000_000_000_000_000))
        assertEquals(133_333_333_333_333L, far.expectedSamples)
        assertEquals(1, far.observedSamples)
        assertEquals(7.500000000000019e-15, far.coverageFraction)
        assertEquals(2, far.gaps.size)
        assertEquals(1e16, far.longestGapSeconds)
    }

    @Test
    fun anExpectedCountBeyondSixtyFourBitsSaturatesInsteadOfTrapping() {
        // Upstream TRAPS here ("Double value cannot be converted to Int … greater than Int.max",
        // measured for a 1e-300 s cadence over an hour and a 1 ms cadence over 2e16 s).
        val tiny = ExportCoverage.assess(listOf(Instant.EPOCH), Instant.EPOCH, Instant.ofEpochSecond(3600), cadence = 1e-300)
        assertEquals(Long.MAX_VALUE, tiny.expectedSamples)
        assertEquals(1, tiny.observedSamples)
        assertEquals(1.0 / Long.MAX_VALUE.toDouble(), tiny.coverageFraction)
        val wide = ExportCoverage.assess(listOf(Instant.EPOCH), Instant.ofEpochSecond(-10_000_000_000_000_000), Instant.ofEpochSecond(10_000_000_000_000_000), cadence = 1e-3)
        assertEquals(Long.MAX_VALUE, wide.expectedSamples)
    }
}
