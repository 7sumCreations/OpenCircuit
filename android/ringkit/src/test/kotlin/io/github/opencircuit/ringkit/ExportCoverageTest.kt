package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage is a MEASUREMENT of what we hold. These tests exist mostly to stop it from overstating: a
 * fraction above 1.0, a gap that isn't there, or a duplicate row counted twice would all turn "here
 * is what we have" into a claim about what the ring recorded.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ExportCoverageTests.swift (@ b1c2fdd)
 * — all 17 tests.
 */
class ExportCoverageTest {

    private val cadence = BulkRecord.EPOCH_SECONDS.toDouble() // 150 s
    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(epochs: Int): Instant = t0.plusSeconds(epochs.toLong() * 150)
    private fun plus(seconds: Double): Instant = t0.plusMillis((seconds * 1000).toLong())

    // MARK: perfect coverage

    @Test
    fun perfectCoverage() {
        val times = (0 until 24).map { at(it) }
        val a = ExportCoverage.assess(times, at(0), at(24))
        assertEquals(24L, a.expectedSamples)
        assertEquals(24, a.observedSamples)
        assertEquals(1.0, a.coverageFraction, 1e-9)
        assertEquals(emptyList(), a.gaps)
        assertEquals(0.0, a.longestGapSeconds)
    }

    @Test
    fun coverageFractionNeverExceedsOne() {
        // Denser-than-cadence sampling (e.g. a live measurement burst) must not read as >100 %.
        val times = (0 until 120).map { t0.plusSeconds(it * 30L) }
        val a = ExportCoverage.assess(times, t0, t0.plusSeconds(3600))
        assertTrue(a.observedSamples > a.expectedSamples)
        assertEquals(1.0, a.coverageFraction, 1e-9)
    }

    // MARK: gaps

    @Test
    fun singleInteriorGap() {
        // Epochs 0…3 then 10…19: a 7-epoch hole in the middle.
        val times = (0..3).map { at(it) } + (10 until 20).map { at(it) }
        val a = ExportCoverage.assess(times, at(0), at(19))
        assertEquals(1, a.gaps.size)
        assertEquals(at(3), a.gaps.first().start)
        assertEquals(at(10), a.gaps.first().end)
        assertEquals(7 * cadence, a.gaps.first().seconds)
        assertEquals(7 * cadence, a.longestGapSeconds)
        assertEquals(14, a.observedSamples)
        assertEquals(19L, a.expectedSamples)
    }

    @Test
    fun leadingAndTrailingGapsAreReported() {
        // Window spans epochs 0…20 but we only hold 8…12.
        val times = (8..12).map { at(it) }
        val a = ExportCoverage.assess(times, at(0), at(20))
        assertEquals(2, a.gaps.size, "leading and trailing holes are both real gaps")
        assertEquals(at(0), a.gaps[0].start)
        assertEquals(at(8), a.gaps[0].end)
        assertEquals(at(12), a.gaps[1].start)
        assertEquals(at(20), a.gaps[1].end)
        assertEquals(8 * cadence, a.longestGapSeconds)
    }

    @Test
    fun longestGapMatchesWidestReportedGap() {
        val times = listOf(at(0), at(5), at(6), at(20), at(21))
        val a = ExportCoverage.assess(times, at(0), at(21))
        assertFalse(a.gaps.isEmpty())
        assertEquals(a.gaps.maxOf { it.seconds }, a.longestGapSeconds)
        assertEquals(14 * cadence, a.longestGapSeconds)
    }

    @Test
    fun singleMissedEpochIsNotReportedAsAGap() {
        // minGap is two epochs: ordinary jitter must not bury the real holes.
        val times = listOf(at(0), at(1), at(3), at(4))
        val a = ExportCoverage.assess(times, at(0), at(4))
        assertEquals(emptyList(), a.gaps, "a 2-epoch step is not STRICTLY longer than minGap")
    }

    @Test
    fun gapsAreAscending() {
        val times = listOf(at(10), at(11), at(30))
        val a = ExportCoverage.assess(times, at(0), at(40))
        assertEquals(3, a.gaps.size)
        for ((lhs, rhs) in a.gaps.zipWithNext()) {
            assertTrue(lhs.start <= rhs.start)
        }
    }

    // MARK: degenerate + hostile input

    @Test
    fun emptyInputMakesTheWholeWindowOneGap() {
        val a = ExportCoverage.assess(emptyList(), at(0), at(24))
        assertEquals(0, a.observedSamples)
        assertEquals(24L, a.expectedSamples)
        assertEquals(0.0, a.coverageFraction)
        assertEquals(listOf(ExportCoverage.Gap(at(0), at(24))), a.gaps)
        assertEquals(24 * cadence, a.longestGapSeconds)
    }

    @Test
    fun invertedWindowIsDegenerateAndInventsNoGap() {
        val a = ExportCoverage.assess(listOf(at(1)), at(10), at(0))
        assertEquals(0L, a.expectedSamples)
        assertEquals(0, a.observedSamples)
        assertEquals(0.0, a.coverageFraction)
        assertEquals(emptyList(), a.gaps, "an inverted window has no hole to report")
        assertEquals(0.0, a.longestGapSeconds)
    }

    @Test
    fun zeroLengthWindowIsDegenerate() {
        val a = ExportCoverage.assess(listOf(at(0)), at(0), at(0))
        assertEquals(0L, a.expectedSamples)
        assertEquals(0.0, a.coverageFraction)
        assertEquals(emptyList(), a.gaps)
    }

    @Test
    fun unsortedAndDuplicateTimestamps() {
        val times = listOf(at(3), at(0), at(1), at(1), at(2), at(3), at(0))
        val a = ExportCoverage.assess(times, at(0), at(4))
        assertEquals(4, a.observedSamples, "duplicates cover the same epoch and count once")
        assertEquals(4L, a.expectedSamples)
        assertEquals(1.0, a.coverageFraction, 1e-9)
        assertEquals(emptyList(), a.gaps)
    }

    @Test
    fun samplesOutsideTheWindowAreIgnored() {
        val inside = (10..14).map { at(it) }
        val outside = listOf(at(-50), at(-1), at(100), at(500))
        val a = ExportCoverage.assess(outside + inside, at(10), at(14))
        assertEquals(5, a.observedSamples)
        assertEquals(4L, a.expectedSamples)
        assertEquals(1.0, a.coverageFraction, 1e-9)
        assertEquals(emptyList(), a.gaps, "out-of-window samples must not create in-window gaps either")
    }

    @Test
    fun windowBoundsAreEchoedBack() {
        val a = ExportCoverage.assess(emptyList(), at(2), at(9))
        assertEquals(at(2), a.windowStart)
        assertEquals(at(9), a.windowEnd)
    }

    @Test
    fun expectedSamplesFloorsPartialEpochs() {
        // 5.5 epochs of window → 5 whole epochs expected, never 6.
        val a = ExportCoverage.assess(emptyList(), t0, plus(5.5 * 150))
        assertEquals(5L, a.expectedSamples)
    }

    @Test
    fun customCadenceAndMinGap() {
        val times = listOf(t0, t0.plusSeconds(60))
        val a = ExportCoverage.assess(times, t0, t0.plusSeconds(600), cadence = 60.0, minGap = 120.0)
        assertEquals(10L, a.expectedSamples)
        assertEquals(2, a.observedSamples)
        assertEquals(0.2, a.coverageFraction, 1e-9)
        assertEquals(listOf(ExportCoverage.Gap(t0.plusSeconds(60), t0.plusSeconds(600))), a.gaps)
    }

    @Test
    fun nonPositiveCadenceIsDegenerateRatherThanDividingByZero() {
        val a = ExportCoverage.assess(listOf(t0), t0, t0.plusSeconds(600), cadence = 0.0)
        assertEquals(0L, a.expectedSamples)
        assertEquals(0.0, a.coverageFraction)
    }

    @Test
    fun gapSecondsIsEndMinusStart() {
        val gap = ExportCoverage.Gap(t0, t0.plusSeconds(450))
        assertEquals(450.0, gap.seconds)
    }
}
