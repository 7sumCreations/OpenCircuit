package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the vitals and energy basics (HRV, stress, strain, energy,
 * distance): what the upstream vectors never feed in — empty and single inputs, window sizes of
 * zero, below zero and far past the input, RR and heart-rate values at the 32-bit extremes, NaN,
 * infinite and negative-zero doubles, reversed, duplicated and far-future sample times, and
 * profiles with a zero, negative or NaN body. Kept out of the upstream-port classes so their counts
 * stay exact.
 *
 * Every expected value that matches upstream was measured on the pinned Swift build (Swift 6.3.2);
 * where Kotlin deliberately differs the test says so, and `PORTING.md` records why. Nothing here
 * may throw, loop or allocate past the size of its input.
 */
class VitalsHazardTest {

    private val male = UserProfile(age = 30, weightKg = 80.0, heightCm = 180.0, sex = BiologicalSex.MALE)
    private val t0: Instant = Instant.ofEpochSecond(1_750_000_000)

    // HRV

    @Test
    fun rmssdOfShortWindowsIsAbsentAndExtremeRrIsExactIn64Bits() {
        assertNull(HRV.rmssd(emptyList()))
        assertNull(HRV.rmssd(listOf(800)))
        // The successive difference spans 2^32 - 1 here: a 32-bit subtraction would wrap and a
        // 32-bit result would saturate. Upstream (64-bit Int) answers exactly, as does Kotlin.
        assertEquals(4_294_967_295L, HRV.rmssd(listOf(Int.MIN_VALUE, Int.MAX_VALUE)))
        assertEquals(2_147_483_647L, HRV.rmssd(listOf(0, Int.MAX_VALUE, 0)))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun rollingRmssdWindowSizesOutsideTheInputYieldNothingAndAllocateNothing() {
        val rr = List(30_000) { 800 + (it % 7) * 10 }
        for (ws in listOf(0, -5, 1, Int.MIN_VALUE, rr.size + 1, Int.MAX_VALUE)) {
            assertEquals(emptyList(), HRV.rollingRMSSD(rr, windowSize = ws), "windowSize $ws")
        }
        assertEquals(listOf(1L), HRV.rollingRMSSD(listOf(1, 2, 4), windowSize = 3), "windowSize == count is one window")
        // The work is the input size times the window (upstream's algorithm): half the night as the
        // window is the worst case, and still finishes well inside the time limit.
        assertEquals(rr.size - 15_000 + 1, HRV.rollingRMSSD(rr, windowSize = 15_000).size)
    }

    @Test
    fun cleanRrDropsNonPositiveValuesAndEmptyGroups() {
        assertEquals(listOf(7), HRV.cleanRR(listOf(listOf(-5, 0, 7), emptyList(), listOf(Int.MIN_VALUE))))
        assertEquals(emptyList(), HRV.cleanRR(emptyList()))
    }

    @Test
    fun summaryOfNothingIsAbsentAndItsMeanIsSummedIn64Bits() {
        assertNull(HRV.summary(emptyList()))
        assertNull(HRV.summary(listOf(800, 810), windowSize = 3))
        // Negative RR values are not filtered here (cleanRR is the caller's step) — as upstream.
        assertEquals(HRV.Summary(min = 900, max = 1000, avg = 933), HRV.summary(listOf(800, -100, 900, 0), windowSize = 2))
        // Three windows of 2^32 - 1: their sum leaves 32 bits; upstream's 64-bit mean is exact.
        val big = HRV.summary(listOf(Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE), windowSize = 2)
        assertEquals(HRV.Summary(min = 4_294_967_295L, max = 4_294_967_295L, avg = 4_294_967_295L), big)
    }

    // Stress

    @Test
    fun stressOfDegenerateRrIsTheMaximumAsUpstream() {
        // No variability to measure reads as the maximum, 10.0 — upstream's answer, kept.
        assertEquals(10.0, Stress.index(emptyList()))
        assertEquals(10.0, Stress.index(listOf(700)))
        assertEquals(10.0, Stress.index(listOf(0, 0, 0, 50)))
        // Negative intervals bin by truncation toward zero, as Swift's integer division does.
        assertEquals(10.0, Stress.index(listOf(-120, -60, -10, 40, 90)))
        assertEquals(10.0, Stress.index(listOf(-25, -24, 10, 30)))
    }

    @Test
    fun stressAtThe32BitExtremesDoesNotOverflowAndTiesPickTheHigherBin() {
        // max - min is 2^32 - 1: a 32-bit subtraction would wrap to -1 and read as constant RR.
        assertEquals(0.0, Stress.index(listOf(Int.MIN_VALUE, Int.MAX_VALUE)))
        // Bins 12 and 14 both hold two values: the higher one is the mode (725 ms), as upstream.
        assertEquals(3.13, Stress.index(listOf(600, 610, 700, 710)))
    }

    // Strain

    @Test
    fun strainSampleIntervalOutsideTheNormalRangeBehavesAsUpstream() {
        val s = Strain(maxHR = 190, restingHR = 60)
        val inZone = List(600) { 170 }
        val mixed = List(300) { 170 } + List(300) { 65 }
        // Zero, negative and -Inf intervals count each sample as one second.
        for (ss in listOf(0.0, -0.0, -1.0, Double.NEGATIVE_INFINITY)) assertEquals(8.78, s.calculate(inZone, sampleSeconds = ss), "sampleSeconds $ss")
        // NaN poisons the TRIMP sum; a NaN TRIMP is not > 0, so the strain is 0.0.
        assertTrue(Strain.edwardsTRIMP(inZone, maxHR = 190, restingHR = 60, sampleSeconds = Double.NaN)!!.isNaN())
        assertEquals(0.0, s.calculate(inZone, sampleSeconds = Double.NaN))
        // +Inf: every sample in a zone gives an infinite strain; one zone-0 sample (0 × Inf = NaN) gives 0.0.
        assertEquals(Double.POSITIVE_INFINITY, s.calculate(inZone, sampleSeconds = Double.POSITIVE_INFINITY))
        assertTrue(Strain.edwardsTRIMP(mixed, maxHR = 190, restingHR = 60, sampleSeconds = Double.POSITIVE_INFINITY)!!.isNaN())
        assertEquals(0.0, s.calculate(mixed, sampleSeconds = Double.POSITIVE_INFINITY))
        // The 0…21 scale is not a cap: an enormous interval scores past 21, as upstream.
        assertEquals(1641.83, s.calculate(inZone, sampleSeconds = 1e300))
    }

    @Test
    fun strainParametersAtTheirBoundsAndExtremes() {
        val at = { n: Int -> List(n) { 170 } }
        assertNull(Strain(maxHR = 60, restingHR = 60).calculate(at(600)), "maxHR == restingHR")
        assertNull(Strain(maxHR = 59, restingHR = 60).calculate(at(600)), "maxHR < restingHR")
        assertNull(Strain(maxHR = 190, restingHR = 60).calculate(at(Strain.MIN_READINGS - 1)), "one reading short")
        assertEquals(8.78, Strain(maxHR = 190, restingHR = 60).calculate(at(Strain.MIN_READINGS)), "exactly the minimum")
        assertNull(Strain(maxHR = 190, restingHR = 60).calculate(emptyList()))
        // Extreme readings and parameters are computed in doubles: no wrap, upstream's values.
        val extreme = List(300) { Int.MAX_VALUE } + List(300) { Int.MIN_VALUE }
        assertEquals(7.7, Strain(maxHR = 190, restingHR = 60).calculate(extreme))
        assertEquals(5.67, Strain(maxHR = Int.MAX_VALUE, restingHR = Int.MIN_VALUE).calculate(at(600)))
    }

    @Test
    fun trimpToStrainOnNonPositiveNonFiniteAndHugeTrimp() {
        for (t in listOf(Double.NaN, -0.0, 0.0, -1.0, Double.MIN_VALUE, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, Strain.trimpToStrain(t), "trimp $t")
        }
        assertEquals(Double.POSITIVE_INFINITY, Strain.trimpToStrain(Double.POSITIVE_INFINITY))
        assertEquals(1676.65, Strain.trimpToStrain(1e308))
    }

    @Test
    fun trimpOverTimestampedSamplesWithReversedFarFutureAndHugeSpans() {
        // end before start: each sample counts one second, as upstream.
        val reversed = List(600) { HRSample(bpm = 150, start = t0.plusSeconds(it + 1L), end = t0.plusSeconds(it.toLong())) }
        assertEquals(30.00000000000029, Strain.edwardsTRIMP(reversed, maxHR = 180, restingHR = 60))
        // Instants near the end of the representable range: exact here, the same energy.
        val far = List(600) { HRSample(150, Instant.ofEpochSecond(30_000_000_000_000_000L + it), Instant.ofEpochSecond(30_000_000_000_000_001L + it)) }
        assertEquals(150.00000000000145, Calories.activeKcal(far, maxHR = 180))
        // One sample spanning ±3e16 s: its duration is taken without overflow (upstream's value).
        val span = listOf(HRSample(150, Instant.ofEpochSecond(-30_000_000_000_000_000L), Instant.ofEpochSecond(30_000_000_000_000_000L))) +
            (1 until 600).map { HRSample(150, t0.plusSeconds(it.toLong())) }
        assertEquals(1.5e16, Calories.activeKcal(span, maxHR = 180))
    }

    @Test
    fun activeKcalCountsEveryCopyOfADuplicatedSampleAndNeedsMaxAboveResting() {
        // 300 seconds, each sample delivered twice: upstream counts both copies (and reaches the
        // 600-reading minimum because of them). Kept: duplicates are removed by counter where the
        // records are merged, before samples are built.
        val dup = (0 until 300).flatMap { o -> HRSample(150, t0.plusSeconds(o.toLong()), t0.plusSeconds(o + 1L)).let { listOf(it, it) } }
        assertEquals(150.00000000000145, Calories.activeKcal(dup, maxHR = 180))
        assertEquals(0.0, Calories.activeKcal(dup, maxHR = Calories.DEFAULT_RESTING_HR))
        assertEquals(0.0, Calories.activeKcal(dup, maxHR = -5))
        assertEquals(0.0, Calories.activeKcal(emptyList(), maxHR = 180))
    }

    // Energy from distance, steps and the profile

    @Test
    fun distanceAndStepEnergyOnHostileInputs() {
        assertEquals(0.0, Calories.activeKcalFromDistance(Double.NaN, male))
        assertEquals(0.0, Calories.activeKcalFromDistance(-0.0, male))
        assertEquals(Double.POSITIVE_INFINITY, Calories.activeKcalFromDistance(Double.POSITIVE_INFINITY, male))
        // An impossible body passes through as upstream's arithmetic gives it; the profile entry and
        // the health writer range-check (PORTING.md).
        assertEquals(-70.0, Calories.activeKcalFromDistance(2000.0, male.copy(weightKg = -70.0)))
        assertTrue(Calories.activeKcalFromDistance(2000.0, male.copy(weightKg = Double.NaN)).isNaN())
        assertEquals(21_303_037.77824, Calories.activeKcalFromSteps(Int.MAX_VALUE, male))
        assertEquals(0.0, Calories.activeKcalFromSteps(Int.MIN_VALUE, male))
    }

    @Test
    fun bmrOfImpossibleProfilesIsUpstreamArithmeticWithoutOverflow() {
        assertEquals(-161.0, Calories.bmrKcalPerDay(UserProfile(age = 0, weightKg = 0.0, heightCm = 0.0, sex = BiologicalSex.FEMALE)))
        assertEquals(280.0, Calories.bmrKcalPerDay(male.copy(weightKg = -70.0)))
        assertTrue(Calories.bmrKcalPerDay(male.copy(weightKg = Double.NaN)).isNaN())
        // The age is converted to a double before it is multiplied: no 32-bit wrap.
        assertEquals(-10_737_416_471.0, Calories.bmrKcalPerDay(UserProfile(Int.MAX_VALUE, 80.0, 180.0, BiologicalSex.FEMALE)))
        assertEquals(-10_737_416_471.0 / 24.0, Calories.bmrKcalPerHour(UserProfile(Int.MAX_VALUE, 80.0, 180.0, BiologicalSex.FEMALE)))
    }

    @Test
    fun restingBaselineOnNonFiniteSignedZeroAndDegenerateDayCounts() {
        // NaN is ordered as Swift's sort orders it, so whether it is trimmed off depends on where it
        // started, exactly as upstream (measured).
        assertTrue(Calories.restingBaselineBpm(listOf(60.0, Double.NaN, 62.0))!!.isNaN())
        assertTrue(Calories.restingBaselineBpm(listOf(60.0, 61.0, Double.NaN, 59.0, 60.0, 62.0, 58.0, 61.0, 60.0, 63.0))!!.isNaN())
        assertEquals(60.125, Calories.restingBaselineBpm(listOf(Double.NaN, 61.0, 60.0, 59.0, 60.0, 62.0, 58.0, 61.0, 60.0, 63.0)))
        assertEquals(60.75, Calories.restingBaselineBpm(listOf(Double.POSITIVE_INFINITY, 61.0, 60.0, 59.0, 60.0, 62.0, 58.0, 61.0, 60.0, 63.0)))
        assertTrue(Calories.restingBaselineBpm(listOf(Double.POSITIVE_INFINITY, 61.0, Double.NEGATIVE_INFINITY))!!.isNaN())
        assertEquals(0.0, Calories.restingBaselineBpm(listOf(-0.0, 0.0, -0.0)))
        assertNull(Calories.restingBaselineBpm(listOf(60.0, 61.0, 62.0), minDays = 0))
        assertNull(Calories.restingBaselineBpm(listOf(60.0, 61.0, 62.0), minDays = -1))
        assertNull(Calories.restingBaselineBpm(emptyList(), minDays = 1))
        assertEquals(60.0, Calories.restingBaselineBpm(listOf(60.0), minDays = 1))
        assertEquals(9.5, Calories.restingBaselineBpm((0 until 20).map { it.toDouble() }), "20 days trim two from each end")
    }

    @Test
    fun restingEnergyScaleTreatsANonFiniteReadingAsMissing() {
        // Deliberate difference: upstream turns an unreadable (NaN or infinite) resting HR or
        // baseline into the -20 % or +20 % clamp (measured: NaN HR 0.8, +Inf HR 1.2, -Inf HR 0.8,
        // +Inf baseline 0.8). Its own contract says a missing input means no change, 1.0.
        val nonFinite = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (x in nonFinite) {
            assertEquals(1.0, Calories.restingEnergyScale(restingHR = x, baselineRestingHR = 60.0), "resting HR $x")
            assertEquals(1.0, Calories.restingEnergyScale(restingHR = 70.0, baselineRestingHR = x), "baseline $x")
            for (y in nonFinite) assertEquals(1.0, Calories.restingEnergyScale(restingHR = x, baselineRestingHR = y), "both $x $y")
        }
        // Every finite pair keeps upstream's answer: the clamped linear scale, or 1.0 for a
        // baseline that is not above zero. Swept over the whole finite range once.
        val rhrs = listOf(-Double.MAX_VALUE, -500.0, -1.0, -0.0, 0.0, Double.MIN_VALUE, 30.0, 59.99, 60.0, 60.01, 79.0, 80.0, 81.0, 200.0, Double.MAX_VALUE)
        val bases = listOf(-Double.MAX_VALUE, -60.0, -0.0, 0.0, Double.MIN_VALUE, 1e-300, 0.5, 30.0, 60.0, 100.0, Double.MAX_VALUE)
        for (r in rhrs) for (b in bases) {
            val scale = Calories.restingEnergyScale(restingHR = r, baselineRestingHR = b)
            val expected = if (b > 0) swiftMin(1.2, swiftMax(0.8, 1.0 + 0.01 * (r - b))) else 1.0
            assertEquals(expected, scale, "resting HR $r baseline $b")
            assertTrue(scale.isFinite() && scale in 0.8..1.2, "finite and within ±20 % at $r / $b")
        }
        assertEquals(1.2, Calories.restingEnergyScale(restingHR = 70.0, baselineRestingHR = Double.MIN_VALUE), "a tiny positive baseline is trusted, as upstream")
    }

    @Test
    fun basalEnergyFallsBackToTheStaticRateOnAnUnreadableRestingHr() {
        // Upstream: 59.333… kcal/h (the -20 % clamp). Here: the static per-hour BMR.
        assertEquals(Calories.bmrKcalPerHour(male), Calories.basalKcalPerHour(male, restingHR = Double.NaN, baselineRestingHR = 60.0))
        assertEquals(Calories.bmrKcalPerHour(male), Calories.basalKcalPerHour(male, restingHR = 70.0, baselineRestingHR = Double.POSITIVE_INFINITY))
    }

    @Test
    fun workoutEnergyOnNonFiniteDurationsAndImpossibleBodies() {
        assertEquals(0.0, Calories.workoutActiveKcal(avgHR = 120, durationSeconds = Double.NaN, profile = male))
        assertEquals(Double.POSITIVE_INFINITY, Calories.workoutActiveKcal(avgHR = 120, durationSeconds = Double.POSITIVE_INFINITY, profile = male))
        // A clamped zero rate times an infinite duration: 0 × Inf = NaN, as upstream.
        assertTrue(Calories.workoutActiveKcal(avgHR = 40, durationSeconds = Double.POSITIVE_INFINITY, profile = male).isNaN())
        // Swift's max(0, NaN) is 0, where Kotlin's maxOf(0.0, NaN) is NaN: a NaN body weight gives
        // a zero rate upstream, and here.
        assertEquals(0.0, Calories.workoutActiveKcal(avgHR = 120, durationSeconds = 60.0, profile = male.copy(weightKg = Double.NaN)))
        assertEquals(323_816_300.1315488, Calories.workoutActiveKcal(avgHR = Int.MAX_VALUE, durationSeconds = 60.0, profile = male))
        val female = UserProfile(age = 30, weightKg = -500.0, heightCm = 160.0, sex = BiologicalSex.FEMALE)
        assertEquals(23.57356596558317, Calories.workoutActiveKcal(avgHR = 120, durationSeconds = 60.0, profile = female))
    }

    @Test
    fun distanceAtTheIntegerExtremes() {
        assertEquals(0.0, DistanceEstimate.meters(Int.MIN_VALUE))
        assertEquals(532_575_944.456, DistanceEstimate.meters(Int.MAX_VALUE))
    }
}
