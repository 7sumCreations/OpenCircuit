package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/CaloriesAttributionTests.swift
// (@ b1c2fdd), all 19 tests, as upstream's two classes in one file: `CaloriesAttributionTests` (15)
// and `ElevatedPiecesTests` (4).

/**
 * Time-attributed active energy (`Calories.dailyEstimate` with `stepWindows` + `dayStart`).
 *
 * Regression origin: a tester's Apple Health showed active energy stop dead at 2pm and never resume,
 * while HR and steps kept arriving all afternoon. The legacy estimate is `max(hrKcal, stepKcal)`
 * over two WHOLE-DAY snapshots — once the last elevated-HR bout ends `hrKcal` is exactly constant,
 * and the step channel needs ~27-40k steps to overtake it, so the day total froze.
 */
class CaloriesAttributionTest {

    private val profile = UserProfile(age = 35, weightKg = 72.0, heightCm = 178.0, sex = BiologicalSex.MALE)
    private val day: Instant = Instant.ofEpochSecond(1_753_660_800) // a local midnight

    private fun at(hours: Double): Instant = day.plusMillis(Math.round(hours * 3_600_000))

    /** A run of back-to-back 150 s epochs at one bpm — how the ring actually delivers a bout. */
    private fun bout(fromHour: Double, minutes: Double, bpm: Int): List<HRSample> {
        val epochs = roundHalfAwayFromZero(minutes * 60 / 150).toInt()
        return (0 until epochs).map { i -> HRSample(bpm, at(fromHour).plusSeconds(i * 150L)) }
    }

    private fun steps(delta: Int, fromHour: Double, minutes: Double): StepWindow =
        StepWindow(at(fromHour), at(fromHour).plusMillis(Math.round(minutes * 60_000)), delta)

    // The reported bug

    /** THE regression test. A morning bout freezes `hrKcal`; the afternoon is walking with heart rate below the 92 bpm gate. */
    @Test
    fun afternoonWalkingAccruesAfterTheLastElevatedBout() { // :36-56
        val hr = bout(fromHour = 8.0, minutes = 20.0, bpm = 118)
        val windows = listOf(
            steps(2_000, fromHour = 8.0, minutes = 20.0), // during the bout
            steps(2_100, fromHour = 15.0, minutes = 25.0), // the walk home, HR ~88
        )

        val legacy = Calories.dailyEstimate(hr, steps = 4_100, profile = profile)
        val attributed = Calories.dailyEstimate(hr, steps = 4_100, profile = profile, stepWindows = windows, dayStart = day)

        // Legacy: the afternoon is worth zero, so the day total is just the morning bout.
        assertEquals(Calories.workoutActiveKcal(avgHR = 118, durationSeconds = 20.0 * 60, profile = profile), legacy.activeKcal, 0.5)

        val afternoon = attributed.buckets.filter { it.start >= at(14.0) }
        assertFalse(afternoon.isEmpty(), "the afternoon walk must produce buckets")
        assertTrue(afternoon.fold(0.0) { acc, b -> acc + b.activeKcal } > 15, "2,100 steps of walking must be worth real kcal")
        assertTrue(attributed.activeKcal > legacy.activeKcal)
    }

    /** Isolated elevated spot reads dilute a morning bout under the legacy day-average pricing; per-piece pricing cannot be moved by them. */
    @Test
    fun isolatedAfternoonSpotReadsDoNotLowerTheDayTotal() { // :61-78
        val morning = bout(fromHour = 8.0, minutes = 30.0, bpm = 135)
        val spots = listOf(11.0, 13.0, 15.0).map { HRSample(95, at(it)) }
        val windows = listOf(steps(3_000, fromHour = 8.0, minutes = 30.0))

        val before = Calories.dailyEstimate(morning, steps = 3_000, profile = profile, stepWindows = windows, dayStart = day)
        val after = Calories.dailyEstimate(morning + spots, steps = 3_000, profile = profile, stepWindows = windows, dayStart = day)
        assertTrue(after.activeKcal >= before.activeKcal - 0.000_001)

        // …and the legacy path is the thing that regresses, which is why this fix exists.
        val legacyBefore = Calories.dailyEstimate(morning, steps = 3_000, profile = profile)
        val legacyAfter = Calories.dailyEstimate(morning + spots, steps = 3_000, profile = profile)
        assertTrue(legacyAfter.activeKcal < legacyBefore.activeKcal)
    }

    // Invariants

    @Test
    fun bucketsSumToTheDayTotal() { // :82-90
        val hr = bout(fromHour = 7.0, minutes = 25.0, bpm = 128) + bout(fromHour = 18.0, minutes = 15.0, bpm = 104)
        val windows = listOf(
            steps(1_800, fromHour = 7.0, minutes = 25.0),
            steps(4_200, fromHour = 12.0, minutes = 90.0),
            steps(1_500, fromHour = 18.0, minutes = 15.0),
        )
        val e = Calories.dailyEstimate(hr, steps = 7_500, profile = profile, stepWindows = windows, dayStart = day)
        assertEquals(e.activeKcal, e.buckets.fold(0.0) { acc, b -> acc + b.activeKcal }, 1e-9)
    }

    /** The bucket grid is PLACEMENT metadata. If the width moved the total, every user's Move ring would depend on a constant we picked. */
    @Test
    fun dayTotalIsInvariantToBucketWidth() { // :94-108
        val hr = bout(fromHour = 9.0, minutes = 12.0, bpm = 130) + bout(fromHour = 17.0, minutes = 8.0, bpm = 96)
        val windows = listOf(
            steps(900, fromHour = 9.0, minutes = 12.0),
            steps(5_000, fromHour = 10.0, minutes = 300.0),
            steps(700, fromHour = 17.0, minutes = 8.0),
        )
        val widths = listOf(5.0 * 60, 10.0 * 60, 15.0 * 60, 30.0 * 60, 60.0 * 60)
        val totals = widths.map { w ->
            Calories.dailyEstimate(hr, steps = 6_600, profile = profile, stepWindows = windows, dayStart = day, bucketSeconds = w).activeKcal
        }
        for (total in totals.drop(1)) assertEquals(totals[0], total, 1e-6)
    }

    @Test
    fun bucketsAreChronologicalAndNonOverlapping() { // :110-119
        val hr = bout(fromHour = 6.0, minutes = 10.0, bpm = 120)
        val windows = listOf(steps(3_000, fromHour = 6.0, minutes = 200.0))
        val e = Calories.dailyEstimate(hr, steps = 3_000, profile = profile, stepWindows = windows, dayStart = day)
        assertFalse(e.buckets.isEmpty())
        for ((a, b) in e.buckets.zipWithNext()) assertTrue(a.end <= b.start)
    }

    // Overlap netting

    /** A walk that raised heart rate must be paid ONCE — by whichever channel valued it higher, not by both. */
    @Test
    fun walkInsideABoutIsPaidOnce() { // :125-137
        val hr = bout(fromHour = 10.0, minutes = 20.0, bpm = 125)
        val windows = listOf(steps(2_200, fromHour = 10.0, minutes = 20.0)) // entirely inside the bout
        val e = Calories.dailyEstimate(hr, steps = 2_200, profile = profile, stepWindows = windows, dayStart = day)

        val hrOnly = Calories.workoutActiveKcal(avgHR = 125, durationSeconds = 20.0 * 60, profile = profile)
        val stepOnly = Calories.activeKcalFromSteps(steps = 2_200, profile = profile)
        assertTrue(hrOnly > stepOnly, "precondition: HR is the richer channel here")
        assertEquals(hrOnly, e.activeKcal, 0.001)
        assertEquals(0.0, e.buckets.fold(0.0) { acc, b -> acc + b.stepKcal }, 0.001)
    }

    /** …and where the step channel values a slice higher, the excess IS credited. */
    @Test
    fun stepExcessOverElevatedTimeIsCredited() { // :140-150
        val hr = bout(fromHour = 10.0, minutes = 5.0, bpm = 93) // barely over the gate
        val windows = listOf(steps(4_000, fromHour = 10.0, minutes = 5.0)) // a lot of walking, 5 min
        val e = Calories.dailyEstimate(hr, steps = 4_000, profile = profile, stepWindows = windows, dayStart = day)

        val hrOnly = Calories.workoutActiveKcal(avgHR = 93, durationSeconds = 5.0 * 60, profile = profile)
        val stepOnly = Calories.activeKcalFromSteps(steps = 4_000, profile = profile)
        assertTrue(stepOnly > hrOnly, "precondition: steps are the richer channel here")
        assertEquals(stepOnly, e.activeKcal, 0.001)
    }

    // Byte-identical degrade

    @Test
    fun degradesToLegacyWithoutDayStart() { // :154-162
        val hr = bout(fromHour = 8.0, minutes = 20.0, bpm = 118)
        val windows = listOf(steps(2_000, fromHour = 8.0, minutes = 20.0))
        val e = Calories.dailyEstimate(hr, steps = 2_000, profile = profile, stepWindows = windows)
        val legacy = Calories.legacyDailyEstimate(hr, steps = 2_000, profile = profile)
        assertTrue(e.buckets.isEmpty())
        assertEquals(legacy.activeKcal, e.activeKcal, 1e-9)
    }

    /** A day with steps but no per-snapshot history must NOT be attributed — otherwise it would silently under-report. */
    @Test
    fun degradesToLegacyWhenStepsExistWithoutWindows() { // :166-173
        val hr = bout(fromHour = 8.0, minutes = 20.0, bpm = 118)
        val e = Calories.dailyEstimate(hr, steps = 9_000, profile = profile, stepWindows = emptyList(), dayStart = day)
        val legacy = Calories.legacyDailyEstimate(hr, steps = 9_000, profile = profile)
        assertTrue(e.buckets.isEmpty())
        assertEquals(legacy.activeKcal, e.activeKcal, 1e-9)
    }

    /** Steps-only days keep their exact pre-attribution value. */
    @Test
    fun stepsOnlyDayMatchesLegacyExactly() { // :177-186
        val low = listOf(HRSample(70, at(9.0)))
        val windows = listOf(steps(5_000, fromHour = 9.0, minutes = 240.0))
        val e = Calories.dailyEstimate(low, steps = 5_000, profile = profile, stepWindows = windows, dayStart = day)
        assertEquals(0.0, e.elevatedMinutes)
        assertEquals(Calories.activeKcalFromSteps(steps = 5_000, profile = profile), e.activeKcal, 1e-6)
    }

    // Accounting details

    /** Steps are prorated on METRES. Splitting the Int step count at a bucket edge would truncate and quietly lose steps. */
    @Test
    fun stepWindowSpanningManyBucketsLosesNoEnergy() { // :192-201
        // 999 steps (odd, to expose Int truncation) over 2 hours = 8 fifteen-minute buckets.
        val windows = listOf(steps(999, fromHour = 9.0, minutes = 120.0))
        val e = Calories.dailyEstimate(emptyList(), steps = 999, profile = profile, stepWindows = windows, dayStart = day)
        assertEquals(Calories.activeKcalFromSteps(steps = 999, profile = profile), e.activeKcal, 1e-9)
        assertTrue(e.buckets.size >= 8)
    }

    /** Steps the daily counter reports but no snapshot placed in time still get credited — and NOT at midnight. */
    @Test
    fun unplacedStepsAreCreditedAtTheEarliestActivityNotMidnight() { // :205-213
        val windows = listOf(steps(1_000, fromHour = 16.0, minutes = 30.0))
        val e = Calories.dailyEstimate(emptyList(), steps = 3_000, profile = profile, stepWindows = windows, dayStart = day)
        assertEquals(Calories.activeKcalFromSteps(steps = 3_000, profile = profile), e.activeKcal, 1e-9)
        assertEquals(at(16.0), e.buckets.firstOrNull()?.start)
    }

    /** A snapshot whose window opened before midnight holds steps from BOTH days; only the in-day share is placed. */
    @Test
    fun windowStraddlingMidnightPlacesOnlyItsInDayShare() { // :218-226
        val straddling = StepWindow(at(-1.0), at(1.0), 600) // half before midnight
        val e = Calories.dailyEstimate(emptyList(), steps = 300, profile = profile, stepWindows = listOf(straddling), dayStart = day)
        assertEquals(Calories.activeKcalFromSteps(steps = 300, profile = profile), e.activeKcal, 0.01)
        assertTrue((e.buckets.firstOrNull()?.start ?: Instant.MIN) >= day)
    }

    /** The daily step counter is the source of truth for HOW MANY; the snapshots only say WHEN. */
    @Test
    fun dailyStepScalarRemainsAuthoritativeOverTheSnapshots() { // :230-237
        val straddling = StepWindow(at(-1.0), at(1.0), 600)
        val e = Calories.dailyEstimate(emptyList(), steps = 600, profile = profile, stepWindows = listOf(straddling), dayStart = day)
        assertEquals(Calories.activeKcalFromSteps(steps = 600, profile = profile), e.activeKcal, 0.01)
    }

    @Test
    fun sleepWindowHRIsStillExcluded() { // :239-246
        val asleep = bout(fromHour = 2.0, minutes = 20.0, bpm = 120)
        val sleep = DateInterval(at(0.0), at(6.0))
        val e = Calories.dailyEstimate(asleep, steps = 0, profile = profile, sleepWindow = sleep, dayStart = day)
        assertEquals(0.0, e.elevatedMinutes)
        assertEquals(0.0, e.activeKcal, 1e-9)
    }
}

/**
 * `ExerciseMinutes.elevatedPieces` — the per-slice decomposition `estimate` is defined on. The Apple
 * Exercise ring reads `estimate`, so the anti-drift invariant here is load-bearing.
 */
class ElevatedPiecesTest {

    private val day: Instant = Instant.ofEpochSecond(1_753_660_800)
    private fun at(seconds: Double): Instant = day.plusMillis(Math.round(seconds * 1000))

    /** The invariant: total piece duration IS the exercise-minutes scalar. */
    @Test
    fun pieceDurationAlwaysEqualsTheMinutesScalar() { // :258-277
        val cases: List<List<HRSample>> = listOf(
            emptyList(),
            listOf(HRSample(120, at(0.0))), // isolated point
            (0 until 6).map { HRSample(120, at(it * 150.0)) }, // a run
            listOf(
                HRSample(130, at(0.0), at(600.0)), // spans + points
                HRSample(125, at(300.0)),
                HRSample(118, at(450.0)),
                HRSample(99, at(5_000.0)),
            ),
            listOf(HRSample(140, at(0.0), at(300.0)), HRSample(100, at(60.0), at(120.0))), // nested
            listOf(HRSample(95, at(900.0), at(1_200.0)), HRSample(145, at(0.0), at(600.0))), // out of order
        )
        for (samples in cases) {
            val pieces = ExerciseMinutes.elevatedPieces(samples, maxHR = 185)
            val scalar = ExerciseMinutes.estimate(samples, maxHR = 185)
            assertEquals(scalar, pieces.fold(0.0) { acc, p -> acc + p.seconds } / 60.0, 1e-9)
        }
    }

    @Test
    fun piecesAreDisjointAndOrdered() { // :279-290
        val samples = listOf(
            HRSample(140, at(0.0), at(600.0)),
            HRSample(100, at(300.0), at(900.0)),
            HRSample(130, at(1_800.0), at(2_000.0)),
        )
        val pieces = ExerciseMinutes.elevatedPieces(samples, maxHR = 185)
        for ((a, b) in pieces.zipWithNext()) assertTrue(a.end <= b.start)
        // The overlap goes to the EARLIER sample, so nothing is double-counted.
        assertEquals(140, pieces.firstOrNull()?.bpm)
        assertEquals(at(600.0), pieces[1].start)
    }

    @Test
    fun isolatedPointSampleStillEarnsNoTime() { // :292-296
        val pieces = ExerciseMinutes.elevatedPieces(listOf(HRSample(150, at(0.0))), maxHR = 185)
        assertEquals(0.0, pieces.fold(0.0) { acc, p -> acc + p.seconds })
    }

    @Test
    fun consecutivePointSamplesEachEarnAnEpoch() { // :298-303
        val samples = (0 until 3).map { HRSample(120, at(it * 150.0)) }
        val pieces = ExerciseMinutes.elevatedPieces(samples, maxHR = 185)
        assertEquals(450.0, pieces.fold(0.0) { acc, p -> acc + p.seconds }, 1e-9)
        assertEquals(listOf(120, 120, 120), pieces.map { it.bpm })
    }
}
