package io.github.opencircuit.ringkit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNTHETIC-ONLY tests for the robust (median / MAD) personal baseline behind the overnight signals
 * index (#183, Phase 2). Every vector is hand-constructed with a known median, MAD and expected z —
 * never a real health value, and never a value copied from a capture.
 *
 * These prove CORRECTNESS, not skill: they show the arithmetic does what `RobustBaseline` claims,
 * and nothing more. No assertion here says the index predicts anything.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RobustBaselineTests.swift
 * (@ b1c2fdd), all 10 tests. `testArtifactDayShiftsBaselineLessThanOneNoiseFloor` reads its noise
 * floor from the ported `HeadacheSignals.Feature.SKIN_TEMP_DEVIATION.noiseFloor`, as upstream reads
 * `skinTempDeviation.noiseFloor` — one home for the constant. Its expected z of exactly 2 (0.6 over
 * upstream's 0.3) still pins the value.
 */
class RobustBaselineTest {

    private fun assertNotEqual(unexpected: Double, actual: Double, accuracy: Double, message: String) =
        assertTrue(abs(unexpected - actual) > accuracy, "$message (got $actual)")

    // Median

    @Test
    fun medianOddAndEvenCounts() { // :14-21
        assertNull(RobustBaseline.median(emptyList()))
        assertEquals(5.0, assertNotNull(RobustBaseline.median(listOf(5.0))), 1e-9)
        // Odd count, deliberately unsorted input → the middle value after sorting.
        assertEquals(5.0, assertNotNull(RobustBaseline.median(listOf(9.0, 1.0, 5.0, 3.0, 7.0))), 1e-9)
        // Even count → the mean of the two central values: (5 + 7) / 2.
        assertEquals(6.0, assertNotNull(RobustBaseline.median(listOf(9.0, 1.0, 5.0, 3.0, 7.0, 11.0))), 1e-9)
    }

    @Test
    fun madOnHandComputedVectors() { // :23-36
        // Odd: sorted [50,50,50,60,70,70,70] → median 60; |dev| = [10,10,10,0,10,10,10] → MAD 10.
        val odd = assertNotNull(RobustBaseline.stats(listOf(50.0, 50.0, 50.0, 60.0, 70.0, 70.0, 70.0)))
        assertEquals(60.0, odd.median, 1e-9)
        assertEquals(10.0, odd.mad, 1e-9)
        assertEquals(7, odd.n)

        // Even: sorted [10,20,30,40,52,60,70,80] → median (40+52)/2 = 46;
        // |dev| sorted = [6,6,14,16,24,26,34,36] → MAD (16+24)/2 = 20.
        val even = assertNotNull(RobustBaseline.stats(listOf(10.0, 20.0, 30.0, 40.0, 52.0, 60.0, 70.0, 80.0)))
        assertEquals(46.0, even.median, 1e-9)
        assertEquals(20.0, even.mad, 1e-9)
        assertEquals(8, even.n)
    }

    // The consistency constant

    /**
     * The 1.4826 Gaussian consistency constant must actually reach the divisor. Without it every
     * threshold copied from the vitals engine (which is on an SD scale) would silently mean something
     * else — see the file header's provenance note.
     */
    @Test
    fun consistencyConstantIsAppliedInZ() { // :43-56
        val stats = assertNotNull(RobustBaseline.stats(listOf(50.0, 50.0, 50.0, 60.0, 70.0, 70.0, 70.0))) // median 60, MAD 10
        val scale = RobustBaseline.MAD_CONSISTENCY * stats.mad // 14.826, well above the 5 floor

        // One scaled MAD-unit above the median is exactly z = 1.
        assertEquals(1.0, RobustBaseline.z(today = 60 + scale, stats = stats, noiseFloor = 5.0), 1e-9)
        assertEquals(2.0, RobustBaseline.z(today = 60 + 2 * scale, stats = stats, noiseFloor = 5.0), 1e-9)
        // Falsifier: had the constant been dropped, the same input would read 1.4826.
        assertNotEqual((60 + scale - 60) / stats.mad, RobustBaseline.z(today = 60 + scale, stats = stats, noiseFloor = 5.0), 1e-6, "the constant reaches the divisor")
        assertEquals(1.4826, RobustBaseline.MAD_CONSISTENCY, 1e-12)
    }

    // Noise-floor clamping

    /**
     * A perfectly regular person has MAD == 0. The floor is applied to the SCALE, so the result is a
     * small finite number rather than an infinity or a NaN.
     */
    @Test
    fun zeroMADDoesNotExplodeToInfinity() { // :62-76
        val stats = assertNotNull(RobustBaseline.stats(List(7) { 60.0 }))
        assertEquals(0.0, stats.mad, 1e-9)

        val small = RobustBaseline.z(today = 62.0, stats = stats, noiseFloor = 5.0)
        assertTrue(small.isFinite())
        assertEquals(0.4, small, 1e-9) // 2 bpm against a 5 bpm floor

        // A genuinely large change still saturates at the clamp rather than running away.
        val big = RobustBaseline.z(today = 100.0, stats = stats, noiseFloor = 5.0)
        assertTrue(big.isFinite())
        assertEquals(RobustBaseline.Z_CLAMP, big, 1e-9)
        assertEquals(-RobustBaseline.Z_CLAMP, RobustBaseline.z(today = 20.0, stats = stats, noiseFloor = 5.0), 1e-9)
    }

    /** A baseline tighter than the noise floor must not turn a 1-bpm wobble into a big z. */
    @Test
    fun subFloorWobbleYieldsSmallZ() { // :79-90
        // MAD 0.1 → 1.4826 · 0.1 = 0.148, far below the 5 bpm floor, so the floor is the scale.
        val stats = assertNotNull(RobustBaseline.stats(listOf(59.9, 60.0, 60.1, 60.0, 59.9, 60.1, 60.0)))
        assertEquals(60.0, stats.median, 1e-9)
        assertEquals(0.1, stats.mad, 1e-9)

        val z = RobustBaseline.z(today = 61.0, stats = stats, noiseFloor = 5.0)
        assertEquals(0.2, z, 1e-9)
        // Unfloored this would be 1 / 0.14826 ≈ 6.7 and would clamp at 4 — a 1 bpm wobble presented
        // as a maximal deviation.
        assertTrue(z < 1, "must stay below HeadacheSignals.Tuning().onsetZ, i.e. contribute 0")
    }

    // Window selection

    @Test
    fun nilBelowMinBaselineDays() { // :94-102
        assertNull(RobustBaseline.stats(List(6) { 60.0 }))
        val s = assertNotNull(RobustBaseline.stats(List(7) { 60.0 }))
        assertEquals(7, s.n)
        assertEquals(60.0, s.median, 1e-9)
        // Degenerate arguments are refused rather than producing a nonsense estimate.
        assertNull(RobustBaseline.stats(List(30) { 60.0 }, minDays = 0))
        assertNull(RobustBaseline.stats(List(30) { 60.0 }, minDays = 5, maxDays = 3))
    }

    /**
     * `prior` is oldest → newest, so an over-long series must keep the NEWEST window. Keeping the
     * oldest would score today against a baseline the person has already moved away from.
     */
    @Test
    fun trailingWindowKeepsNewestValues() { // :106-113
        val prior = (1..80).map { it.toDouble() } // oldest 1 … newest 80
        val s = assertNotNull(RobustBaseline.stats(prior))
        assertEquals(RobustBaseline.MAX_BASELINE_DAYS, s.n)
        assertEquals(50.5, s.median, 1e-9, "median of 21…80, the newest 60")
        assertNotEqual(40.5, s.median, 1e-9, "40.5 = whole series, i.e. no window at all")
        assertNotEqual(30.5, s.median, 1e-9, "30.5 = the OLDEST 60, i.e. prefix not suffix")
    }

    // Circular clock arithmetic

    /**
     * Bedtime wraps. The plain median of 23:50 and 00:10 is midday, which would make a perfectly
     * regular sleeper look maximally irregular.
     */
    @Test
    fun circularMedianAcrossMidnight() { // :119-136
        val tenToMidnight = 23 * 60 + 50 // 1430
        val tenPast = 10

        assertEquals(0, RobustBaseline.circularMedianMinutes(listOf(tenToMidnight, tenPast, 0)))
        // The even-count case is where the plain median is catastrophically wrong: (1430+10)/2 = 720.
        assertEquals(0, RobustBaseline.circularMedianMinutes(listOf(tenToMidnight, tenPast)))

        // A non-wrapping cluster still behaves like an ordinary median.
        assertEquals(1400, RobustBaseline.circularMedianMinutes(listOf(1380, 1400, 1420)))

        assertEquals(500, RobustBaseline.circularMedianMinutes(listOf(500)))
        assertNull(RobustBaseline.circularMedianMinutes(emptyList()))

        // Out-of-range minutes are normalised into 0…1439 rather than rejected.
        assertEquals(30, RobustBaseline.circularMedianMinutes(listOf(1440 + 30)))
        assertEquals(1430, RobustBaseline.circularMedianMinutes(listOf(-10)))
    }

    @Test
    fun circularDeltaWraps() { // :138-144
        assertEquals(20, RobustBaseline.circularDeltaMinutes(23 * 60 + 50, 10))
        assertEquals(20, RobustBaseline.circularDeltaMinutes(10, 23 * 60 + 50), "symmetric")
        assertEquals(0, RobustBaseline.circularDeltaMinutes(0, 0))
        assertEquals(640, RobustBaseline.circularDeltaMinutes(0, 800), "the short way round")
        assertEquals(720, RobustBaseline.circularDeltaMinutes(0, 720), "the antipode is the maximum")
    }

    // Why median/MAD and not mean/SD — made falsifiable

    /**
     * The documented artifact night — a cold object held while asleep, read as 86 °F ≈ 30 °C
     * (`SkinTempBaseline`, and `SkinTempBaselineTest.artifactNightAlertsButRecoveryNightStaysQuiet`) —
     * dropped into an otherwise steady series.
     *
     * The claim is that median/MAD survives it and mean/SD does not. This test computes BOTH
     * baselines on the same two vectors so the claim can fail: the robust baseline must move by less
     * than one skin-temp noise floor (0.3 °C), and the shipped mean/SD engine must move by more.
     */
    @Test
    fun artifactDayShiftsBaselineLessThanOneNoiseFloor() { // :156-187
        val floor = HeadacheSignals.Feature.SKIN_TEMP_DEVIATION.noiseFloor // upstream: skinTempDeviation.noiseFloor (0.3)
        val steady = listOf(34.3, 34.4, 34.5, 34.4, 34.3, 34.5, 34.4, 34.4, 34.5)
        val artifactC = 30.0 // ≈ 86 °F
        val contaminated = listOf(artifactC) + steady

        val clean = assertNotNull(RobustBaseline.stats(steady))
        val dirty = assertNotNull(RobustBaseline.stats(contaminated))
        val medianShift = abs(dirty.median - clean.median)
        val madShift = abs(dirty.mad - clean.mad)

        assertTrue(medianShift < floor, "one artifact night must not move the robust centre")
        assertTrue(madShift < floor, "…nor the robust scale")

        // The alternative, computed with the SHIPPED mean/SD engine on the same two vectors.
        val cleanSD = assertNotNull(VitalsBaseline.stats(steady))
        val dirtySD = assertNotNull(VitalsBaseline.stats(contaminated))
        val meanShift = abs(dirtySD.mean - cleanSD.mean)

        assertTrue(meanShift > medianShift, "the mean must move MORE — this is the whole argument")
        assertTrue(meanShift > floor, "and it moves by more than a whole noise floor")
        assertTrue(dirtySD.sd > 10 * cleanSD.sd, "one outlier inflates the SD by an order of magnitude")

        // The consequence that matters: after the artifact, the mean/SD scale has swollen so far that a
        // genuinely deviant night is MASKED, while the robust scale still sees it.
        val deviantNight = clean.median + 0.6
        val robustZ = RobustBaseline.z(today = deviantNight, stats = dirty, noiseFloor = floor)
        val sdZ = (deviantNight - dirtySD.mean) / dirtySD.sd
        assertEquals(2.0, robustZ, 1e-9)
        assertTrue(sdZ < 1.0)
        assertTrue(robustZ > 2 * sdZ)
    }
}
