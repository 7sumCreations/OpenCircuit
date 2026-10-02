package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the daily energy estimate and its step windows: what the
 * upstream vectors never feed in — bucket widths that are tiny, sub-second, zero, negative, NaN,
 * infinite or astronomically wide, step windows that end before they start, carry a negative delta
 * or fall outside the day, step counts below zero or past 32 bits once summed, a step split exactly
 * in half, duplicated and unsorted heart-rate samples, an impossible age, a NaN body weight and a
 * day start at the end of `Instant`'s range. Kept out of the upstream-port classes so their counts
 * stay exact.
 *
 * Every expected value that matches upstream was measured on the pinned Swift build (Swift 6.3.2);
 * where Kotlin deliberately differs the test says so, and `PORTING.md` records why.
 */
class DailyEnergyHazardTest {

    private val profile = UserProfile(age = 35, weightKg = 72.0, heightCm = 178.0, sex = BiologicalSex.MALE)
    private val day: Instant = Instant.ofEpochSecond(1_753_660_800)
    private fun at(hours: Double): Instant = day.plusMillis(Math.round(hours * 3_600_000))
    private val bout = (0 until 8).map { HRSample(118, at(8.0).plusSeconds(it * 150L)) }
    private val walk = listOf(StepWindow(at(8.0), at(8.5), 2000))

    private fun estimate(bucketSeconds: Double) =
        Calories.dailyEstimate(bout, steps = 2000, profile = profile, stepWindows = walk, dayStart = day, bucketSeconds = bucketSeconds)

    /** The legacy answer for this day: the larger of the HR and step channels over the whole day. */
    private val legacyKcal = 194.65774378585087

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun aTinyBucketWidthIsBoundedInsteadOfSpreadingWithoutEnd() {
        // Upstream walks every bucket of the span: 1e-6 s ran past 20 s and 1e-300 s traps on the
        // Int conversion (both measured). Below one second the port declines to attribute and returns
        // the legacy estimate, so this finishes at once; without the bound it would not.
        for (w in listOf(1e-9, 1e-300, Double.MIN_VALUE, 0.5)) {
            val e = estimate(w)
            assertEquals(legacyKcal, e.activeKcal, 1e-9, "width $w")
            assertTrue(e.buckets.isEmpty(), "width $w")
        }
        // One second is the narrowest grid: a whole-day window still finishes, one bucket per second.
        val allDay = Calories.dailyEstimate(emptyList(), steps = 5000, profile = profile,
            stepWindows = listOf(StepWindow(day, day.plusSeconds(26 * 3600L), 5000)), dayStart = day, bucketSeconds = 1.0)
        assertEquals(26 * 3600, allDay.buckets.size)
        assertEquals(Calories.activeKcalFromSteps(5000, profile), allDay.activeKcal, 1e-9)
        assertEquals(200.60974378585072, estimate(1.0).activeKcal, 1e-9)
        assertEquals(1800, estimate(1.0).buckets.size)
    }

    @Test
    fun bucketWidthsThatAreNotPositiveOrTooWideFallBackToTheLegacyEstimate() {
        assertEquals(200.60974378585087, estimate(900.0).activeKcal)
        assertEquals(2, estimate(900.0).buckets.size)
        // Not positive or NaN: the legacy estimate, as upstream.
        for (w in listOf(0.0, -1.0, Double.NaN)) {
            assertEquals(legacyKcal, estimate(w).activeKcal, "width $w")
            assertTrue(estimate(w).buckets.isEmpty())
        }
        // Infinite gives upstream a bucket starting at NaN; 1e300 a bucket ending past any date
        // `Instant` holds. Past a billion seconds the port returns the legacy estimate.
        for (w in listOf(Double.POSITIVE_INFINITY, 1e300, 2e9)) {
            assertEquals(legacyKcal, estimate(w).activeKcal, "width $w")
            assertTrue(estimate(w).buckets.isEmpty(), "width $w")
        }
        val wide = estimate(1e9).buckets.single()
        assertEquals(day to day.plusSeconds(1_000_000_000), wide.start to wide.end)
        assertEquals(200.6097437858509, estimate(1e9).activeKcal, 1e-9)
        assertEquals(1, estimate(200_000.0).buckets.size)
    }

    @Test
    fun stepWindowsThatAreReversedNegativeOrOutsideTheDay() {
        // Ends before it starts: a point snapshot credited whole at its start (measured).
        val reversed = Calories.dailyEstimate(emptyList(), 500, profile, stepWindows = listOf(StepWindow(at(10.0), at(9.0), 500)), dayStart = day)
        assertEquals(4.464, reversed.activeKcal, 1e-12)
        assertEquals(listOf(at(10.0)), reversed.buckets.map { it.start })
        // A negative delta is skipped; the steps it cannot place leave no activity to credit them
        // to, so the day degrades to the legacy estimate (no buckets).
        val negative = Calories.dailyEstimate(emptyList(), 500, profile, stepWindows = listOf(StepWindow(at(10.0), at(11.0), -500)), dayStart = day)
        assertEquals(4.464, negative.activeKcal, 1e-12)
        assertTrue(negative.buckets.isEmpty())
        val outside = Calories.dailyEstimate(emptyList(), 10, profile, stepWindows = listOf(StepWindow(at(30.0), at(31.0), 100)), dayStart = day)
        assertEquals(0.08928, outside.activeKcal, 1e-12)
        assertTrue(outside.buckets.isEmpty())
        for (withDay in listOf(day, null)) {
            val belowZero = Calories.dailyEstimate(emptyList(), -500, profile, dayStart = withDay)
            assertEquals(0.0, belowZero.activeKcal)
            assertTrue(belowZero.buckets.isEmpty())
        }
    }

    @Test
    fun creditedStepsAreCountedInSixtyFourBitsAndRoundedHalfAwayFromZero() {
        val big = (0 until 3).map { StepWindow(at(it + 1.0), at(it + 1.5), Int.MAX_VALUE) }
        // Three windows of Int.MAX_VALUE steps credit 6 442 450 941: a 32-bit sum would wrap and pay
        // a phantom residual. Upstream's 64-bit Int does not (measured).
        val e = Calories.dailyEstimate(emptyList(), Int.MAX_VALUE, profile, stepWindows = big, dayStart = day)
        assertEquals(57_518_202.001247995, e.activeKcal, 1e-6)
        assertEquals(6, e.buckets.size)
        val plusPoint = Calories.dailyEstimate(emptyList(), Int.MAX_VALUE, profile,
            stepWindows = big + StepWindow(at(20.0), at(20.0), 7), dayStart = day)
        assertEquals(57_518_202.06374399, plusPoint.activeKcal, 1e-6)
        assertEquals(7, plusPoint.buckets.size)
        // Half of one step falls inside the day: Swift's rounded() credits it (0.5 → 1), so no
        // residual is added; ties-to-even would credit 0 and pay the step again.
        val half = Calories.dailyEstimate(emptyList(), 1, profile, stepWindows = listOf(StepWindow(at(-1.0), at(1.0), 1)), dayStart = day)
        assertEquals(0.004464, half.activeKcal, 1e-15)
        assertEquals(4, half.buckets.size)
        val oneAndHalf = Calories.dailyEstimate(emptyList(), 0, profile, stepWindows = listOf(StepWindow(at(-1.0), at(1.0), 3)), dayStart = day)
        assertEquals(0.013392, oneAndHalf.activeKcal, 1e-15)
    }

    @Test
    fun duplicatedUnsortedAndEmptyHeartRate() {
        val once = Calories.dailyEstimate(bout, 0, profile, dayStart = day)
        assertEquals(legacyKcal, once.activeKcal, 1e-9)
        assertEquals(once, Calories.dailyEstimate(bout + bout, 0, profile, dayStart = day), "duplicated samples overlap and count once")
        assertEquals(once, Calories.dailyEstimate(bout.reversed(), 0, profile, dayStart = day))
        assertEquals(listOf(at(8.0), at(8.25)), once.buckets.map { it.start })
        val empty = Calories.dailyEstimate(emptyList(), 0, profile, dayStart = day)
        assertEquals(0.0, empty.activeKcal)
        assertTrue(empty.buckets.isEmpty())
    }

    @Test
    fun impossibleProfilesAndADayStartAtTheEndOfTime() {
        // Age Int.MIN_VALUE: upstream's max HR is 2 147 483 868 (64-bit); the port's saturates at
        // Int.MAX_VALUE. Either threshold is above every real heart rate, so only Int.MAX_VALUE
        // readings qualify — and the answer is upstream's (measured).
        val old = UserProfile(age = Int.MIN_VALUE, weightKg = 72.0, heightCm = 178.0, sex = BiologicalSex.MALE)
        assertEquals(0.0, Calories.dailyEstimate(bout, 0, old).activeKcal)
        val extreme = bout + HRSample(Int.MAX_VALUE, at(9.0)) + HRSample(Int.MAX_VALUE, at(9.02))
        val e = Calories.dailyEstimate(extreme, 0, old, dayStart = day)
        assertEquals(815_078_819.1054925, e.activeKcal, 1e-6)
        assertEquals(3.7, e.elevatedMinutes, 1e-12)
        // A NaN body weight: the step energy is NaN and shows in the total (the writer range-checks).
        val nanBody = profile.copy(weightKg = Double.NaN)
        val n = Calories.dailyEstimate(bout, 2000, nanBody, stepWindows = walk, dayStart = day)
        assertTrue(n.activeKcal.isNaN())
        assertEquals(listOf(at(8.25)), n.buckets.map { it.start })
        assertEquals(5.0, n.buckets.single().elevatedMinutes, 1e-12)
        // A day whose attribution span would leave `Instant`'s range: the legacy estimate, no throw.
        val lastDay = Instant.MAX.minusSeconds(3600)
        val late = Calories.dailyEstimate(listOf(HRSample(118, lastDay), HRSample(118, lastDay.plusSeconds(150))), 0, profile, dayStart = lastDay)
        assertTrue(late.buckets.isEmpty())
        assertEquals(5.0, late.elevatedMinutes, 1e-12)
    }

    @Test
    fun stepWindowIsAPlainValueThatAcceptsWhatUpstreamAccepts() {
        val w = StepWindow(at(10.0), at(9.0), -5)
        assertEquals(StepWindow(at(10.0), at(9.0), -5), w)
        assertNotEquals(w, w.copy(delta = 5))
        assertEquals(at(9.0), w.end)
    }
}
