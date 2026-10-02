package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ExerciseMinutesTests.swift
 * (@ b1c2fdd), all 27 tests.
 */
class ExerciseMinutesTest {

    private val t0: Instant = Instant.ofEpochSecond(0)

    private fun at(seconds: Double): Instant = t0.plusMillis(Math.round(seconds * 1000))
    private fun point(bpm: Int, seconds: Double) = HRSample(bpm, at(seconds), at(seconds))

    // Threshold

    @Test
    fun thresholdHalf() { // :10-13
        // maxHR 180 → threshold = 90 bpm
        assertEquals(90, ExerciseMinutes.threshold(maxHR = 180))
    }

    @Test
    fun thresholdMinimumClamp() { // :15-18
        // maxHR 60 → 50% = 30 < 60 → clamped to 60
        assertEquals(60, ExerciseMinutes.threshold(maxHR = 60))
    }

    @Test
    fun thresholdAtAge35() { // :20-23
        // maxHR = 220 - 35 = 185 → 50% = 92
        assertEquals(92, ExerciseMinutes.threshold(maxHR = 185))
    }

    // Heart-rate-reserve threshold

    /**
     * The reported defect, as arithmetic. At age 35 the old model gives 92 bpm to everyone. Under
     * HRR the person who rests at 78 gets 120 and the person who rests at 45 gets 101 — each 40 %
     * of the way up their OWN range. (78 + 0.4·107 = 120.8, truncated to 120 by the Int conversion.)
     */
    @Test
    fun thresholdIsRelativeToRestingHR() { // :31-37
        assertEquals(78 + (0.4 * 107).toInt(), ExerciseMinutes.threshold(maxHR = 185, restingHR = 78.0))
        assertEquals(45 + (0.4 * 140).toInt(), ExerciseMinutes.threshold(maxHR = 185, restingHR = 45.0))
        assertTrue(
            ExerciseMinutes.threshold(maxHR = 185, restingHR = 78.0) > ExerciseMinutes.threshold(maxHR = 185, restingHR = 45.0),
            "a faster resting pulse must demand a faster elevated pulse",
        )
    }

    /** null resting HR is the kill-switch: byte-identical to the pre-HRR model. */
    @Test
    fun thresholdWithoutRestingHRIsTheOriginalModel() { // :40-45
        for (maxHR in listOf(60, 120, 180, 185, 200)) {
            assertEquals(maxOf((maxHR.toDouble() * 0.5).toInt(), 60), ExerciseMinutes.threshold(maxHR = maxHR, restingHR = null))
        }
    }

    /** An implausible or impossible resting HR must degrade to the %-of-max model rather than produce a threshold nobody can reach. */
    @Test
    fun thresholdRejectsImplausibleRestingHR() { // :49-56
        val fallback = ExerciseMinutes.threshold(maxHR = 185)
        assertEquals(fallback, ExerciseMinutes.threshold(maxHR = 185, restingHR = 20.0))
        assertEquals(fallback, ExerciseMinutes.threshold(maxHR = 185, restingHR = 140.0))
        assertEquals(
            if (fallback == 60) 60 else ExerciseMinutes.threshold(maxHR = 100),
            ExerciseMinutes.threshold(maxHR = 100, restingHR = 120.0),
            "restingHR above maxHR is nonsense → fall back",
        )
    }

    @Test
    fun thresholdKeepsTheAbsoluteFloor() { // :58-61
        // A very low max HR with a low resting HR still cannot drop the bar under 60 bpm.
        assertEquals(60, ExerciseMinutes.threshold(maxHR = 60, restingHR = 40.0))
    }

    // Derived resting baseline

    /** A day's worth of readings with a genuine quiet stretch yields that stretch as the baseline. */
    @Test
    fun restingBaselineFindsTheQuietStretch() { // :66-73
        val samples = (0 until 120).map { i -> // 5 h of 2.5-min epochs
            HRSample(if (i < 60) 52 else 95, at(i * 150.0)) // quiet first, active after
        }
        assertEquals(52.0, assertNotNull(ExerciseMinutes.restingBaseline(samples)), 1e-9)
    }

    /**
     * The failure that a fixture caught when this landed: three readings taken during exertion have
     * no rest in them, so `lowestSustained` would report ~100 as a "resting" HR and push the
     * threshold to 134. Too few readings over too short a span ⇒ no baseline.
     */
    @Test
    fun restingBaselineRefusesAThinOrShortSampleSet() { // :78-87
        val workout = listOf(HRSample(140, t0), HRSample(100, at(300.0)), HRSample(130, at(1_800.0)))
        assertNull(ExerciseMinutes.restingBaseline(workout), "3 readings is not a day")

        // Enough readings, but packed into 30 minutes — still no evidence of rest.
        val dense = (0 until 20).map { HRSample(120, at(it * 90.0)) }
        assertNull(ExerciseMinutes.restingBaseline(dense), "30 min span is not a day")
    }

    /** A long day that genuinely never rests must also decline rather than invent a high baseline. */
    @Test
    fun restingBaselineRefusesAnImplausiblyHighQuietStretch() { // :90-95
        val busy = (0 until 200).map { HRSample(110, at(it * 150.0)) } // 8 h, never quiet
        assertNull(ExerciseMinutes.restingBaseline(busy))
    }

    /**
     * A SPOT-READ-ONLY day must not produce a baseline. `RestingHR.lowestSustained` falls back to the
     * single lowest reading when no 5-min window held two readings, and the production auto-measure
     * cadence (600 s) is longer than that window — so without an explicit sustained-window
     * requirement one poor-contact 44 bpm read becomes the whole day's "resting HR".
     */
    @Test
    fun restingBaselineRefusesASpotReadOnlyDay() { // :103-111
        val samples = (0 until 30).map { HRSample(68, at(it * 600.0)) }.toMutableList() // 10-min spacing
        samples[7] = HRSample(44, at(7 * 600.0)) // one bad read
        assertEquals(30, samples.size)
        assertNull(
            ExerciseMinutes.restingBaseline(samples),
            "no 5-min window holds two readings — there is no sustained rest to measure",
        )
    }

    /** …and the same day WITH a genuinely sustained quiet stretch does produce one, so the guard is a discriminator rather than an off switch. */
    @Test
    fun restingBaselineAcceptsWhenASustainedWindowExists() { // :115-124
        var samples = (0 until 30).map { HRSample(68, at(it * 600.0)) }
        // A dense quiet block: 6 readings 60 s apart at 55 bpm.
        samples = samples + (0 until 6).map { HRSample(55, at(3 * 3600 + it * 60.0)) }
        assertEquals(55.0, assertNotNull(ExerciseMinutes.restingBaseline(samples)), 1e-9)
    }

    /**
     * KNOWN RESIDUAL, pinned deliberately so the boundary is visible rather than surprising. The
     * derived threshold is always ≥ the %-of-max one for a realistic age, so the instant
     * `restingBaseline` becomes derivable the day's elevated minutes STEP DOWN. This test FAILING
     * means someone fixed that; update it.
     */
    @Test
    fun estimateIsNonMonotonicAcrossTheBaselineBoundary() { // :134-157
        val ringOn = t0
        // 1 h at rest, then a 40-min walk — span 1 h 40 m, under the 2 h guard.
        var early = (0 until 24).map { HRSample(62, ringOn.plusSeconds(it * 150L)) }
        early = early + (1..16).map { HRSample(95, ringOn.plusSeconds(3600 + it * 150L)) }
        assertNull(ExerciseMinutes.restingBaseline(early), "under the span guard")
        assertEquals(40.0, ExerciseMinutes.estimate(early, maxHR = 185, deriveRestingHR = true), 1e-9)

        // The same walk, once enough quiet time has accrued to read the resting pulse.
        val late = early + (0 until 24).map { HRSample(62, ringOn.plusMillis(Math.round((2.5 * 3600 + it * 150) * 1000))) }
        assertEquals(62.0, assertNotNull(ExerciseMinutes.restingBaseline(late)), 1e-9)
        assertEquals(
            0.0, ExerciseMinutes.estimate(late, maxHR = 185, deriveRestingHR = true), 1e-9,
            "95 bpm is not 40% of the way from 62 to 185 — correct, but it is a STEP DOWN",
        )
        // With the shipped default there is no boundary and no step at all.
        assertEquals(40.0, ExerciseMinutes.estimate(late, maxHR = 185), 1e-9)
    }

    // The reported symptom — the ring filling from ordinary morning activity

    /**
     * A high-resting-HR wearer's morning: 6 h of night at 72 bpm, then 40 min of ordinary ambulation
     * at 96 bpm. Under the old absolute 92-bpm bar every one of those epochs counted; under HRR
     * (72 → 117 bpm) none of it does.
     */
    @Test
    fun ordinaryMorningActivityNoLongerFillsTheRingForAHighRestingHRWearer() { // :164-191
        val sleepEnd = t0.plusSeconds(6 * 3600)
        val samples = mutableListOf<HRSample>()
        for (i in 0 until 144) samples += HRSample(72, t0.plusSeconds(i * 150L)) // 6 h asleep
        for (i in 1..16) samples += HRSample(96, sleepEnd.plusSeconds(i * 150L)) // 40 min pottering about
        val window = DateInterval(t0, sleepEnd)

        val old = ExerciseMinutes.estimate(samples, maxHR = 185, sleepWindow = window, deriveRestingHR = false)
        assertEquals(40.0, old, 1e-9, "the old absolute 92-bpm bar filled a 30-min goal from this alone")

        val new = ExerciseMinutes.estimate(samples, maxHR = 185, sleepWindow = window, deriveRestingHR = true)
        assertEquals(0.0, new, 1e-9, "96 bpm is 22 bpm above a 72-bpm rest — not moderate exertion")

        // AND THAT IS WHY THE MODEL IS SHIPPED OFF. With the shipped default the day still reads 40 minutes.
        assertEquals(
            old, ExerciseMinutes.estimate(samples, maxHR = 185, sleepWindow = window), 1e-9,
            "shipped default must reproduce the pre-HRR model exactly",
        )
    }

    /** …and real exertion by the same wearer still counts, so the gate is not simply "off". */
    @Test
    fun realExertionStillCountsForAHighRestingHRWearer() { // :194-207
        val sleepEnd = t0.plusSeconds(6 * 3600)
        val samples = mutableListOf<HRSample>()
        for (i in 0 until 144) samples += HRSample(72, t0.plusSeconds(i * 150L))
        for (i in 1..12) samples += HRSample(135, sleepEnd.plusSeconds(i * 150L)) // 30 min of genuine effort
        val minutes = ExerciseMinutes.estimate(samples, maxHR = 185, sleepWindow = DateInterval(t0, sleepEnd))
        assertEquals(30.0, minutes, 1e-9)
    }

    /**
     * The invariant the Goals footnote promises the user: whatever periods the minutes ring counts
     * are exactly the periods the calorie estimate prices. `elevatedPieces` must derive the same
     * baseline `Calories` re-derives from the same samples.
     */
    @Test
    fun minutesAndCaloriesShareOneQualifyingSet() { // :212-231
        val sleepEnd = t0.plusSeconds(6 * 3600)
        val samples = mutableListOf<HRSample>()
        for (i in 0 until 144) samples += HRSample(70, t0.plusSeconds(i * 150L))
        for (i in 0 until 24) samples += HRSample(130, sleepEnd.plusSeconds(i * 150L))
        val derived = ExerciseMinutes.restingBaseline(samples)
        assertNotNull(derived)
        val thresh = ExerciseMinutes.threshold(maxHR = 185, restingHR = derived)
        val pieces = ExerciseMinutes.elevatedPieces(samples, maxHR = 185, sleepWindow = DateInterval(t0, sleepEnd))
        assertFalse(pieces.isEmpty())
        for (p in pieces) {
            assertTrue(p.bpm >= thresh, "a piece priced by calories must clear the same bar")
        }
    }

    // Empty / below threshold

    @Test
    fun noSamplesReturnsZero() { // :235-237
        assertEquals(0.0, ExerciseMinutes.estimate(emptyList(), maxHR = 180))
    }

    @Test
    fun allBelowThresholdReturnsZero() { // :239-244
        val samples = listOf(60, 70, 80).map { bpm -> HRSample(bpm, t0, t0) }
        assertEquals(0.0, ExerciseMinutes.estimate(samples, maxHR = 180))
    }

    // Basic elevated estimate

    @Test
    fun singleIsolatedPointSampleGivesNoFullEpoch() { // :248-255
        // One ISOLATED elevated point read (e.g. a single live-HR spot read) must NOT be credited a
        // full 2.5-min epoch — a lone spot read isn't evidence of exercise (#82 fix).
        val s = HRSample(100, t0, t0)
        assertEquals(0.0, ExerciseMinutes.estimate(listOf(s), maxHR = 180, epochSeconds = 150.0), 0.01)
    }

    @Test
    fun isolatedPointSampleHonorsCustomWidth() { // :257-263
        // An isolated point read gets the caller-supplied small width, not a full epoch.
        val s = HRSample(100, t0, t0)
        val minutes = ExerciseMinutes.estimate(listOf(s), maxHR = 180, epochSeconds = 150.0, pointSampleWidth = 30.0)
        assertEquals(0.5, minutes, 0.01) // 30 s
    }

    @Test
    fun twoConsecutivePointsCountAsSustained() { // :265-274
        // Two point reads within one epoch ⇒ a sustained run ⇒ each gets a full epoch.
        val samples = listOf(0, 150).map { offset -> point(100, offset.toDouble()) }
        val minutes = ExerciseMinutes.estimate(samples, maxHR = 180, epochSeconds = 150.0)
        // [0,150) + [150,300) → merged [0,300] = 5 min
        assertEquals(5.0, minutes, 0.01)
    }

    @Test
    fun threeConsecutiveEpochs() { // :276-286
        // Three point samples at t=0, 150, 300 → consecutive run → merges to [0, 450s] = 7.5 min.
        val samples = listOf(0, 150, 300).map { offset -> point(100, offset.toDouble()) }
        val minutes = ExerciseMinutes.estimate(samples, maxHR = 180, epochSeconds = 150.0)
        // intervals: [0,150), [150,300), [300,450) → merged: [0, 450] = 7.5 min
        assertEquals(7.5, minutes, 0.01)
    }

    @Test
    fun gapBetweenElevatedRuns() { // :288-299
        // Two separate sustained runs (each ≥2 consecutive points) split by a 10-min gap stay as two intervals.
        fun run(base: Double) = listOf(base, base + 150).map { point(100, it) }
        val samples = run(0.0) + run(1200.0)
        val minutes = ExerciseMinutes.estimate(samples, maxHR = 180, epochSeconds = 150.0)
        // Two separate [x, x+300] runs = 5 min + 5 min = 10 min
        assertEquals(10.0, minutes, 0.01)
    }

    @Test
    fun sampleWithRealDurationIsUsed() { // :301-307
        // A sample spanning 5 minutes — its real duration should be used, not epochSeconds
        val s = HRSample(100, t0, t0.plusSeconds(5 * 60))
        assertEquals(5.0, ExerciseMinutes.estimate(listOf(s), maxHR = 180, epochSeconds = 150.0), 0.01)
    }

    // Sleep-window exclusion

    @Test
    fun sleepWindowExcludesElevatedHR() { // :311-324
        // Elevated HR samples during sleep → excluded; a sustained awake run → counted.
        val sleep = DateInterval(at(-3600.0), at(3600.0))
        val sleeping = listOf(0.0, 150.0).map { point(100, it) } // inside the sleep window — excluded
        val awake = listOf(7200.0, 7350.0).map { point(100, it) } // outside — a sustained run of two
        val minutes = ExerciseMinutes.estimate(sleeping + awake, maxHR = 180, sleepWindow = sleep, epochSeconds = 150.0)
        // Only the awake run counted: [7200,7500] = 5 min
        assertEquals(5.0, minutes, 0.01)
    }

    @Test
    fun noExclusionWhenNoSleepWindow() { // :326-332
        val samples = listOf(0.0, 150.0).map { point(100, it) }
        val minutes = ExerciseMinutes.estimate(samples, maxHR = 180, sleepWindow = null, epochSeconds = 150.0)
        assertEquals(5.0, minutes, 0.01)
    }

    // Interval merging

    @Test
    fun overlappingIntervalsAreMerged() { // :336-347
        // Two overlapping real-duration samples → merged to one interval
        val s1 = HRSample(100, t0, t0.plusSeconds(300)) // 5 min
        val s2 = HRSample(110, t0.plusSeconds(200), t0.plusSeconds(600)) // overlaps, extends to 10 min
        val minutes = ExerciseMinutes.estimate(listOf(s1, s2), maxHR = 180, epochSeconds = 150.0)
        // Merged: [0, 600s] = 10 min
        assertEquals(10.0, minutes, 0.01)
    }
}
