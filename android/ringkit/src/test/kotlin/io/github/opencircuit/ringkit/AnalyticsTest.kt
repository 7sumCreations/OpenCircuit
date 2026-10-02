package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity tests for the analytics ported from openwhoop-algos. The RR vectors and assertions mirror
 * openwhoop's own Rust unit tests, so the port is provably equivalent.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/AnalyticsTests.swift (@ b1c2fdd),
 * all 28 tests — HRV, stress, strain, the Mifflin-St Jeor and resting-HR–adjusted basal energy,
 * TRIMP energy and the step / distance energy estimate (:11-171), the daily energy estimate
 * (:173-210), the trimmed-mean baseline (:214-242), the daily resting HR into the basal energy
 * (:246-332) and `testSleepScore` (:334-342). HRV results are 64-bit (`Long`), as upstream's `Int`.
 *
 * The two daily resting-HR tests read the device calendar and clock upstream (`Calendar.current`,
 * `Date()`); here each names a zone and a fixed instant, chosen so the test bites (stated at each).
 */
class AnalyticsTest {

    // HRV / RMSSD (sleep.rs)

    @Test
    fun rmssd() { // :11-14
        assertEquals(100L, HRV.rmssd(listOf(800, 900, 1000))) // diffs 100,100 -> 100
        assertNull(HRV.rmssd(listOf(800)))
    }

    @Test
    fun cleanRR() { // :16-19
        assertEquals(listOf(800, 900, 1000), HRV.cleanRR(listOf(listOf(800, 900), listOf(1000), emptyList())))
        assertEquals(listOf(900), HRV.cleanRR(listOf(listOf(0, 900), listOf(0))))
    }

    // Stress — Baevsky index (stress.rs)

    @Test
    fun stressConstantRRReturnsMax() { // :23-25
        assertEquals(10.0, Stress.index(List(120) { 750 }))
    }

    @Test
    fun stressModerateVariability() { // :27-39
        val rr = listOf(
            667, 674, 682, 690, 682, 652, 638, 632, 625, 619, 612, 619, 606, 594, 583,
            577, 566, 561, 561, 556, 556, 550, 556, 556, 556, 556, 550, 550, 545, 541,
            531, 531, 531, 531, 531, 536, 541, 545, 550, 556, 561, 566, 571, 577, 577,
            583, 583, 583, 588, 594, 594, 600, 600, 600, 600, 594, 600, 612, 619, 625,
            632, 632, 632, 625, 625, 619, 619, 619, 612, 606, 594, 600, 600, 600, 600,
            606, 606, 606, 606, 600, 606, 612, 612, 612, 612, 612, 612, 612, 612, 619,
            612, 612, 612, 619, 619, 625, 625, 625, 632, 638, 645, 645, 638, 638, 632,
            625, 625, 625, 625, 632, 638, 632, 632, 625, 625, 625, 625, 625, 619, 612,
        )
        val score = Stress.index(rr)
        assertTrue(score > 0.0)
        assertTrue(score <= 10.0)
    }

    @Test
    fun stressLowVariability() { // :41-52
        val rr = listOf(
            1000, 984, 1017, 1017, 1017, 1017, 1017, 1000, 1000, 1000, 1000, 1000, 984,
            984, 984, 984, 984, 984, 984, 984, 952, 952, 952, 952, 938, 952, 952, 952,
            968, 968, 968, 968, 984, 984, 984, 984, 968, 968, 968, 968, 968, 968, 968,
            968, 968, 968, 968, 968, 968, 968, 968, 968, 968, 952, 952, 952, 952, 952,
            952, 952, 938, 938, 938, 938, 938, 923, 923, 938, 938, 938, 938, 938, 938,
            938, 938, 938, 938, 938, 938, 923, 923, 923, 938, 938, 952, 952, 952, 952,
            968, 968, 968, 984, 984, 984, 984, 968, 968, 968, 984, 984, 984, 984, 968,
            968, 968, 968, 968, 952, 952, 952, 952, 938, 952, 952, 952, 968, 968, 952,
            952, 952,
        )
        assertTrue(Stress.index(rr) > 0.0)
    }

    // Strain — Edwards TRIMP (strain.rs)

    @Test
    fun strainTooFewReadingsIsNil() { // :56-58
        assertNull(Strain(maxHR = 200, restingHR = 60).calculate(List(500) { 80 }))
    }

    @Test
    fun strainInvalidHRParamsIsNil() { // :60-63
        assertNull(Strain(maxHR = 60, restingHR = 60).calculate(List(600) { 80 }))
        assertNull(Strain(maxHR = 50, restingHR = 60).calculate(List(600) { 80 }))
    }

    @Test
    fun restingHRProducesZeroStrain() { // :65-67
        assertEquals(0.0, Strain(maxHR = 190, restingHR = 60).calculate(List(600) { 65 }))
    }

    @Test
    fun highHRProducesHighStrain() { // :69-72
        val s = Strain(maxHR = 190, restingHR = 60).calculate(List(1800) { 170 })
        assertTrue((s ?: 0.0) > 10.0)
    }

    @Test
    fun strainCappedAt21() { // :74-76
        assertEquals(21.0, Strain(maxHR = 190, restingHR = 60).calculate(List(86_400) { 190 }))
    }

    // Calories — Mifflin-St Jeor + TRIMP

    @Test
    fun maleBMRMifflinStJeor() { // :80-84
        val profile = UserProfile(age = 30, weightKg = 80.0, heightCm = 180.0, sex = BiologicalSex.MALE)
        assertEquals(1780.0, Calories.bmrKcalPerDay(profile), 0.001)
        assertEquals(74.166_666, Calories.bmrKcalPerHour(profile), 0.001)
    }

    @Test
    fun femaleBMRMifflinStJeor() { // :86-89
        val profile = UserProfile(age = 40, weightKg = 65.0, heightCm = 165.0, sex = BiologicalSex.FEMALE)
        assertEquals(1320.25, Calories.bmrKcalPerDay(profile), 0.001)
    }

    // Resting-HR–adjusted basal energy (#dynamic-resting-calories)

    private val male30 = UserProfile(age = 30, weightKg = 80.0, heightCm = 180.0, sex = BiologicalSex.MALE) // :93

    @Test
    fun restingBaselineNilBelowMinDays() { // :95-100
        // Two prior days is below the 3-day minimum → no trusted baseline.
        assertNull(Calories.restingBaselineBpm(listOf(60.0, 62.0)))
        // Three days → mean.
        assertEquals(60.0, assertNotNull(Calories.restingBaselineBpm(listOf(58.0, 60.0, 62.0))), 1e-9)
    }

    @Test
    fun restingScaleNoChangeWithoutInputs() { // :102-109
        // Missing RHR or baseline ⇒ neutral 1.0 (caller degrades to static BMR).
        assertEquals(1.0, Calories.restingEnergyScale(restingHR = null, baselineRestingHR = 60.0))
        assertEquals(1.0, Calories.restingEnergyScale(restingHR = 70.0, baselineRestingHR = null))
        assertEquals(1.0, Calories.restingEnergyScale(restingHR = 70.0, baselineRestingHR = 0.0))
        // On-baseline RHR ⇒ no change.
        assertEquals(1.0, Calories.restingEnergyScale(restingHR = 60.0, baselineRestingHR = 60.0), 1e-9)
    }

    @Test
    fun restingScaleMovesWithDeviation() { // :111-115
        // +8 bpm over baseline ⇒ +8% at 1%/bpm; −5 ⇒ −5%.
        assertEquals(1.08, Calories.restingEnergyScale(restingHR = 68.0, baselineRestingHR = 60.0), 1e-9)
        assertEquals(0.95, Calories.restingEnergyScale(restingHR = 55.0, baselineRestingHR = 60.0), 1e-9)
    }

    @Test
    fun restingScaleClampedBothWays() { // :117-121
        // A garbage/extreme RHR can't push basal energy past ±20%.
        assertEquals(1.20, Calories.restingEnergyScale(restingHR = 200.0, baselineRestingHR = 60.0), 1e-9)
        assertEquals(0.80, Calories.restingEnergyScale(restingHR = 10.0, baselineRestingHR = 60.0), 1e-9)
    }

    @Test
    fun basalKcalPerHourFallsBackToStatic() { // :123-127
        // No RHR/baseline ⇒ exactly the static per-hour BMR (no regression, never zero).
        assertEquals(Calories.bmrKcalPerHour(male30), Calories.basalKcalPerHour(male30), 1e-9)
    }

    @Test
    fun basalKcalPerHourVariesWithMeasuredRHR() { // :129-137
        val base = Calories.bmrKcalPerHour(male30) // 74.1666…
        val elevated = Calories.basalKcalPerHour(male30, restingHR = 70.0, baselineRestingHR = 60.0)
        val lowered = Calories.basalKcalPerHour(male30, restingHR = 55.0, baselineRestingHR = 60.0)
        assertEquals(base * 1.10, elevated, 1e-9) // +10 bpm ⇒ +10%
        assertEquals(base * 0.95, lowered, 1e-9) // −5 bpm ⇒ −5%
        assertTrue(elevated > base)
        assertTrue(lowered < base)
    }

    @Test
    fun activeCaloriesFromEdwardsTRIMP() { // :139-151
        val start = Instant.ofEpochSecond(0)
        val samples = (0 until 600).map { offset ->
            HRSample(bpm = 150, start = start.plusSeconds(offset.toLong()), end = start.plusSeconds(offset + 1L))
        }
        // maxHR 180, restingHR 60, bpm 150 => 75% HRR => zone weight 3.
        // 600 one-second samples = 10 min, TRIMP = 30, kcal = 150.
        assertEquals(150.0, Calories.activeKcal(samples, maxHR = 180), 0.001)
    }

    // Step/distance-derived active-energy ESTIMATE (the "0 active calories" fix): a day with
    // walking, or a workout whose HR never locked, still reports honest active calories instead
    // of 0 — derived (not a sensor reading), labeled an estimate at every write/display site.

    @Test
    fun activeKcalFromDistance() { // :156-162
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 180.0, sex = BiologicalSex.MALE)
        // 2 km × 70 kg × 0.5 kcal·kg⁻¹·km⁻¹ = 70 kcal.
        assertEquals(70.0, Calories.activeKcalFromDistance(meters = 2000.0, profile = profile), 0.001)
        assertEquals(0.0, Calories.activeKcalFromDistance(meters = 0.0, profile = profile), 0.001)
        assertEquals(0.0, Calories.activeKcalFromDistance(meters = -5.0, profile = profile), 0.001)
    }

    @Test
    fun activeKcalFromStepsNonZeroForWalk() { // :164-171
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 180.0, sex = BiologicalSex.MALE)
        val kcal = Calories.activeKcalFromSteps(steps = 5_000, profile = profile)
        val expectedKm = DistanceEstimate.meters(steps = 5_000) / 1000.0
        assertEquals(expectedKm * 70 * 0.5, kcal, 0.001)
        assertTrue(kcal > 0, "a 5000-step walk must yield nonzero active calories")
        assertEquals(0.0, Calories.activeKcalFromSteps(steps = 0, profile = profile), 0.001)
    }

    @Test
    fun dailyEstimateUsesSameModerateHRRunForMinutesAndCalories() { // :173-199
        val profile = UserProfile(age = 33, weightKg = 72.5, heightCm = 170.0, sex = BiologicalSex.MALE)
        val start = Instant.ofEpochSecond(0)
        // 100 bpm is above the elevated-HR threshold for age 33 (93 bpm), but below the old
        // 50%-of-HR-reserve calorie floor (~124 bpm). The old dashboard therefore showed
        // exercise time with zero HR calories. Four consecutive 2.5-min epochs represent 10 min.
        val samples = listOf(0L, 150L, 300L, 450L).map { offset ->
            HRSample(bpm = 100, start = start.plusSeconds(offset), end = start.plusSeconds(offset))
        }

        val result = Calories.dailyEstimate(hrSamples = samples, steps = 0, profile = profile)
        val expectedKcal = Calories.workoutActiveKcal(avgHR = 100, durationSeconds = 10.0 * 60, profile = profile)

        assertEquals(10.0, result.elevatedMinutes, 0.001)
        assertEquals(expectedKcal, result.activeKcal, 0.001)
        assertTrue(result.activeKcal > 0)
    }

    @Test
    fun dailyEstimateFallsBackToStepsWithoutQualifyingHR() { // :201-210
        val profile = UserProfile(age = 33, weightKg = 72.5, heightCm = 170.0, sex = BiologicalSex.MALE)
        val low = HRSample(bpm = 70, start = Instant.ofEpochSecond(0))
        val result = Calories.dailyEstimate(hrSamples = listOf(low), steps = 5_000, profile = profile)

        assertEquals(0.0, result.elevatedMinutes)
        assertEquals(Calories.activeKcalFromSteps(steps = 5_000, profile = profile), result.activeKcal, 0.001)
    }

    // Trimmed-mean baseline robustness (#172 review, fix #4)

    @Test
    fun restingBaselineTrimmedMeanResistsOutlier() { // :214-221
        // 10 days at ~60 bpm with one outlier at 100. Plain mean would be ~64; trimmed mean
        // drops the outlier and the lowest, yielding ~60.
        val prior = listOf(59.0, 60.0, 60.0, 61.0, 60.0, 59.0, 61.0, 60.0, 60.0, 100.0)
        val baseline = assertNotNull(Calories.restingBaselineBpm(prior))
        assertEquals(60.0, baseline, 1.0, "trimmed mean resists a single outlier day")
    }

    @Test
    fun restingBaselineTrimmedMeanSmallWindow() { // :223-242
        // Below minTrimmedBaselineDays (5) we do NOT trim: trimming a thin window collapses the
        // baseline toward a single median day (n=3 → the middle value alone), so we take the plain
        // mean of every prior day instead. These assertions pin the ACTUAL small-window behavior.

        // n=3, skewed: plain mean (72.33…), NOT the median (59). Proves it isn't collapsing to
        // sorted[1] the way the pre-fix 1-in/1-out trim did.
        assertEquals((58 + 59 + 100) / 3.0, assertNotNull(Calories.restingBaselineBpm(listOf(58.0, 59.0, 100.0))), 1e-9)
        // n=3, symmetric: mean == median here, both 60.
        assertEquals(60.0, assertNotNull(Calories.restingBaselineBpm(listOf(58.0, 60.0, 62.0))), 1e-9)
        // n=4, skewed: plain mean of all four (65), NOT a trimmed-to-middle-two 60.
        assertEquals((50 + 60 + 60 + 90) / 4.0, assertNotNull(Calories.restingBaselineBpm(listOf(50.0, 60.0, 60.0, 90.0))), 1e-9)
        // n=5: trimming kicks in — drop one high + one low, mean the middle three.
        // [50, 59, 60, 61, 100] → drop 50 & 100 → mean(59, 60, 61) = 60, NOT the plain mean (66).
        assertEquals(60.0, assertNotNull(Calories.restingBaselineBpm(listOf(50.0, 59.0, 60.0, 61.0, 100.0))), 1e-9)
    }

    // Integration — daily RHR → energy inputs → dynamic basal kcal (#172 review, fix #3)

    /** Athens: each day's readings (00:00–05:00 local) cross UTC midnight, so grouping in any other zone splits days. */
    private val athens: ZoneId = ZoneId.of("Europe/Athens")

    @Test
    fun dailyRHRToBasalEnergyEndToEnd() { // :246-292
        // Upstream reads the device calendar and clock. Here: Europe/Athens on 31 March 2026 (09:00 UTC),
        // so the five days (27–31 March) cross the 29 March spring-forward (a 23-hour day), and each
        // day's readings straddle UTC midnight — a zone-blind grouping finds six days, not five.
        val now = Instant.parse("2026-03-31T09:00:00Z")
        val today = CalendarDay.startOfDay(now, athens)!!

        // Synthesize 5 days of HR data: ~300 readings per day, each day at a different sustained
        // resting level. Day 0–3 baseline around 60 bpm; day 4 (today) elevated at 70 bpm.
        val allHR = mutableListOf<HRSample>()
        for (dayOffset in 0 until 5) {
            val dayStart = today.atZone(athens).plusDays(dayOffset - 4L).toInstant()
            val bpm = if (dayOffset < 4) 60 else 70
            for (minute in 0 until 300 step 5) {
                val t = dayStart.plusSeconds(minute * 60L)
                allHR += HRSample(bpm = bpm, start = t, end = t.plusSeconds(60))
            }
        }

        // Derive daily RHR WITHOUT sleep segments (the lowestSustained path for all days —
        // matches the fix for derivation parity, #172 review fix #1).
        val daily = RestingHR.dailyValues(hr = allHR, sleep = emptyList(), zone = athens)
        assertEquals(5, daily.size, "5 days of HR data → 5 daily RHR values")

        // All baseline days should be ~60 bpm (lowestSustained of constant 60).
        for (d in daily.take(4)) {
            assertEquals(60.0, d.bpm, 1.0, "baseline day should derive ~60 bpm via lowestSustained")
        }
        // Today should be ~70 bpm.
        assertEquals(70.0, daily.lastOrNull()?.bpm ?: 0.0, 1.0, "today should derive ~70 bpm via lowestSustained")

        // Verify the baseline uses the trimmed mean of prior days (all ~60 → trimmed mean ~60).
        val prior = daily.filter { it.day < today }.map { it.bpm }
        val baseline = assertNotNull(Calories.restingBaselineBpm(prior))
        assertEquals(60.0, baseline, 1.0)

        // The scale factor for today: +10 bpm over 60 → +10%.
        val scale = Calories.restingEnergyScale(restingHR = 70.0, baselineRestingHR = baseline)
        assertEquals(1.10, scale, 0.02)

        // Dynamic basal energy should exceed static.
        val dynamicKcal = Calories.basalKcalPerHour(male30, restingHR = 70.0, baselineRestingHR = baseline)
        val staticKcal = Calories.bmrKcalPerHour(male30)
        assertTrue(dynamicKcal > staticKcal, "elevated RHR day should produce higher basal energy than static BMR")
    }

    @Test
    fun derivationParityWithoutSleep() { // :294-332
        // Verify that omitting sleep segments gives ALL days the same derivation method
        // (lowestSustained), so the comparison today-vs-baseline is fair. Upstream reads the device
        // calendar and clock. Here: Europe/Athens on 26 October 2026 (09:00 UTC), so "yesterday" is
        // the 25-hour fall-back day whose readings run through the repeated hour, and both days'
        // readings straddle UTC midnight — a zone-blind grouping finds three days, not two.
        val now = Instant.parse("2026-10-26T09:00:00Z")
        val today = CalendarDay.startOfDay(now, athens)!!
        val yesterday = today.atZone(athens).plusDays(-1).toInstant()

        // Both days have the same HR pattern: readings at 60 bpm with a dip to 55.
        val hr = mutableListOf<HRSample>()
        for (dayStart in listOf(yesterday, today)) {
            for (minute in 0 until 300 step 5) {
                val t = dayStart.plusSeconds(minute * 60L)
                val bpm = if (minute < 30) 55 else 60
                hr += HRSample(bpm = bpm, start = t, end = t.plusSeconds(60))
            }
        }

        // Sleep segments covering ONLY today's night.
        val sleepStart = today.plusSeconds(1 * 3600)
        val sleepEnd = today.plusSeconds(4 * 3600)
        val sleep = listOf(SleepSegment(sleepStart, sleepEnd, SleepStage.ASLEEP_CORE))

        val withSleep = RestingHR.dailyValues(hr = hr, sleep = sleep, zone = athens)
        val withoutSleep = RestingHR.dailyValues(hr = hr, sleep = emptyList(), zone = athens)

        // Without sleep: both days should produce the same RHR (same HR pattern, same method).
        assertEquals(2, withoutSleep.size)
        assertEquals(
            withoutSleep[0].bpm, withoutSleep[1].bpm, 1e-9,
            "without sleep, identical HR patterns yield identical daily RHR — no method offset",
        )

        // With sleep: today may differ from yesterday (sleep-mean vs lowestSustained).
        // This is the bias the fix eliminates.
        if (withSleep.size == 2) {
            val diff = abs(withSleep[0].bpm - withSleep[1].bpm)
            val noDiff = abs(withoutSleep[0].bpm - withoutSleep[1].bpm)
            assertTrue(noDiff <= diff, "dropping sleep should not increase inter-day offset")
        }
    }

    // Sleep score (openwhoop sleep.rs)

    @Test
    fun sleepScore() { // :334-342
        // #28: graded (floating-point ratio), not a 0-or-100 step function.
        assertEquals(100.0, SleepScore.score(durationSeconds = 8 * 3600))
        assertEquals(75.0, SleepScore.score(durationSeconds = 6 * 3600))
        assertEquals(50.0, SleepScore.score(durationSeconds = 4 * 3600))
        assertEquals(0.0, SleepScore.score(durationSeconds = 0))
        assertEquals(100.0, SleepScore.score(durationSeconds = 24 * 3600)) // clamped at the ideal
    }
}
