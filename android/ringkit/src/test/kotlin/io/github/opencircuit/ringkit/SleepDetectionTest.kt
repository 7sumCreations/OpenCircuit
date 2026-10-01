package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepDetectionTests.swift
 * (@ b1c2fdd): all 20 tests — parity with openwhoop-algos `activity.rs`, the wear / charging gate,
 * the HR gate (awake-but-still rejection) and the sleep-vitals rescue (moving-but-asleep morning).
 *
 * Swift's `SIMD3<Float>` gravity is [Gravity]; `findSleep(&events)` takes a `MutableList` and
 * removes from it, as the Swift `inout` array did.
 */
class SleepDetectionTest {

    private val base: Instant = Instant.ofEpochSecond(1_700_000_000)

    private fun reading(minutes: Int, g: Gravity?): GravitySample = GravitySample(base.plusSeconds(minutes * 60L), g)

    private fun minutesAfterBase(t: Instant): Double = (t.epochSecond - base.epochSecond) / 60.0

    @Test
    fun emptyAndSingle() { // :11-14
        assertTrue(ActivityPeriod.detectFromGravity(emptyList()).isEmpty())
        assertTrue(ActivityPeriod.detectFromGravity(listOf(reading(0, Gravity(0f, 0f, 1f)))).isEmpty())
    }

    @Test
    fun allStillIsSleep() { // :16-21
        val h = (0 until 120).map { reading(it, Gravity(0f, 0f, 1f)) }
        val p = ActivityPeriod.detectFromGravity(h)
        assertFalse(p.isEmpty())
        assertEquals(Activity.SLEEP, p.firstOrNull()?.activity)
    }

    @Test
    fun allMovingIsActive() { // :23-26
        val h = (0 until 120).map { reading(it, Gravity(if (it % 2 == 0) 1f else -1f, 0f, 0f)) }
        assertEquals(Activity.ACTIVE, ActivityPeriod.detectFromGravity(h).firstOrNull()?.activity)
    }

    @Test
    fun noGravityIsActive() { // :28-31
        val h = (0 until 120).map { reading(it, null) }
        assertEquals(Activity.ACTIVE, ActivityPeriod.detectFromGravity(h).firstOrNull()?.activity)
    }

    @Test
    fun gapBreaksRun() { // :33-37
        val h = (0 until 60).map { reading(it, Gravity(0f, 0f, 1f)) } + (120 until 180).map { reading(it, Gravity(0f, 0f, 1f)) }
        assertTrue(ActivityPeriod.detectFromGravity(h).size >= 2)
    }

    @Test
    fun findSleepReturnsLong() { // :39-45
        val events = mutableListOf(
            ActivityPeriod(Activity.ACTIVE, base, base.plusSeconds(30 * 60)),
            ActivityPeriod(Activity.SLEEP, base.plusSeconds(30 * 60), base.plusSeconds(300 * 60)),
        )
        assertEquals(Activity.SLEEP, ActivityPeriod.findSleep(events)?.activity)
    }

    @Test
    fun findSleepIgnoresShort() { // :47-50
        val events = mutableListOf(ActivityPeriod(Activity.SLEEP, base, base.plusSeconds(30 * 60)))
        assertNull(ActivityPeriod.findSleep(events))
    }

    @Test
    fun findSleepEmpty() { // :52-55
        val events = mutableListOf<ActivityPeriod>()
        assertNull(ActivityPeriod.findSleep(events))
    }

    @Test
    fun isActive() { // :57-60
        assertTrue(ActivityPeriod(Activity.ACTIVE, base, base.plusSeconds(3600)).isActive)
        assertFalse(ActivityPeriod(Activity.SLEEP, base, base.plusSeconds(3600)).isActive)
    }

    // #41 — wear / charging gate

    /** A 4 h still motion block (would detect as sleep) sampled every 5 min. */
    private fun stillNight(): List<MotionSample> = (0 until 48).map { MotionSample(base.plusSeconds(it * 5L * 60), 1f) }

    private fun temps(celsius: Double): List<TemperatureSample> =
        (0 until 48).map { TemperatureSample(base.plusSeconds(it * 5L * 60), celsius) }

    @Test
    fun wearGateReclassifiesColdStillBlockAsActive() { // :72-80
        val motion = stillNight()
        assertEquals(Activity.SLEEP, ActivityPeriod.detectFromMotion(motion).firstOrNull()?.activity, "motion-only: a still block reads as sleep")
        // Same motion, but skin temp ~22 °C (off-wrist / charging) -> no sleep survives.
        val gated = ActivityPeriod.detectFromMotion(motion, temperatureSamples = temps(22.0))
        assertFalse(gated.any { it.activity == Activity.SLEEP }, "cold (unworn) still block must not count as sleep")
    }

    @Test
    fun wearGateKeepsWarmStillBlockAsSleep() { // :82-85
        val gated = ActivityPeriod.detectFromMotion(stillNight(), temperatureSamples = temps(32.0))
        assertEquals(Activity.SLEEP, gated.firstOrNull()?.activity, "worn (32 °C) still block stays sleep")
    }

    @Test
    fun wearGateNoTemperatureLeavesDetectionUnchanged() { // :87-92
        val motion = stillNight()
        assertEquals(
            ActivityPeriod.detectFromMotion(motion),
            ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList()),
            "no temperature coverage -> identical to motion-only (absence ≠ unworn)",
        )
    }

    // HR gate — awake-but-still rejection (the "sleep while I was out" bug, 2026-06-23)

    /** Still motion across `[startMin, endMin)` at 5-min cadence (reads as sleep, motion-only). */
    private fun stillMotion(startMin: Int, endMin: Int): List<MotionSample> =
        (startMin until endMin step 5).map { MotionSample(base.plusSeconds(it * 60L), 1f) }

    private fun hrSeries(startMin: Int, endMin: Int, bpm: Int): List<HeartRateSample> =
        (startMin until endMin step 5).map { HeartRateSample(base.plusSeconds(it * 60L), bpm) }

    /** Is `minute` inside a detected `.sleep` period? */
    private fun sleepCovers(minute: Int, periods: List<ActivityPeriod>): Boolean {
        val t = base.plusSeconds(minute * 60L)
        return periods.any { it.activity == Activity.SLEEP && !it.start.isAfter(t) && !it.end.isBefore(t) }
    }

    /**
     * The reported failure: a still-but-AWAKE evening (sitting out late, HR ~108) staged as sleep,
     * then real low-HR sleep after a buffer gap. Motion-only stages the evening; the HR gate removes
     * it and keeps the real block.
     */
    @Test
    fun heartRateGateRejectsAwakeStillEveningBlock() { // :117-129
        // 60-min data gap (120→180) > gravityMaxGap → detect() breaks the run.
        val motion = stillMotion(0, 120) + stillMotion(180, 480)
        val hr = hrSeries(0, 120, bpm = 108) + hrSeries(180, 480, bpm = 64)

        val motionOnly = ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList())
        assertTrue(sleepCovers(60, motionOnly), "motion-only stages the still evening as sleep")

        val gated = ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList(), heartRateSamples = hr)
        assertFalse(sleepCovers(60, gated), "awake-but-still evening (HR 108 ≫ floor) must not be sleep")
        assertTrue(sleepCovers(300, gated), "real low-HR (64 bpm) sleep block survives the gate")
    }

    /** No regression: a genuinely still, low-HR night stays sleep (median near the floor). */
    @Test
    fun heartRateGateKeepsRealLowHRSleep() { // :132-136
        val gated = ActivityPeriod.detectFromMotion(stillMotion(0, 300), temperatureSamples = emptyList(), heartRateSamples = hrSeries(0, 300, bpm = 60))
        assertEquals(Activity.SLEEP, gated.firstOrNull()?.activity, "uniformly low-HR still night stays sleep")
    }

    /** No HR coverage → identical to motion-only (absence of HR is not evidence of wakefulness). */
    @Test
    fun heartRateGateNoHRLeavesDetectionUnchanged() { // :139-144
        val motion = stillMotion(0, 300)
        assertEquals(
            ActivityPeriod.detectFromMotion(motion),
            ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList(), heartRateSamples = emptyList()),
            "no HR coverage → identical to motion-only",
        )
    }

    /** Too few HR readings → the gate stays out rather than acting on noise. */
    @Test
    fun heartRateGateIgnoresSparseHR() { // :147-153
        val motion = stillMotion(0, 300)
        val hr = listOf(HeartRateSample(base, 120), HeartRateSample(base.plusSeconds(60 * 60), 120)) // < minHRSamplesForGate
        assertEquals(
            Activity.SLEEP,
            ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList(), heartRateSamples = hr).firstOrNull()?.activity,
            "too few HR readings → gate stays out, block remains sleep",
        )
    }

    // Sleep-vitals rescue (moving-but-asleep morning) — the SYMMETRIC counterpart to the HR gate.

    /** Alternate near-still and a big spike every 5 min so the stretch classifies `.active` motion-only. */
    private fun spikyMotion(startMin: Int, endMin: Int): List<MotionSample> =
        (startMin until endMin step 5).mapIndexed { i, m -> MotionSample(base.plusSeconds(m * 60L), if (i % 2 == 0) 2f else 260f) }

    private fun hrvTimes(startMin: Int, endMin: Int): List<Instant> = (startMin until endMin step 5).map { base.plusSeconds(it * 60L) }

    private fun lastSleepEnd(periods: List<ActivityPeriod>): Instant? =
        periods.filter { it.activity == Activity.SLEEP }.map { it.end }.maxOrNull()

    /** A still low-HR night followed by a restless-but-asleep morning: the tail extends through the morning. */
    @Test
    fun sleepVitalsRescueExtendsMovingButAsleepMorning() { // :176-191
        val motion = stillMotion(0, 360) + spikyMotion(360, 480)
        val hr = hrSeries(0, 480, bpm = 55) // low all night incl. the restless morning
        val hrv = hrvTimes(0, 470) // ring still measuring sleep vitals to ~470

        val motionOnly = ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList(), heartRateSamples = hr)
        val cutEnd = assertNotNull(lastSleepEnd(motionOnly))
        assertTrue(minutesAfterBase(cutEnd) < 380, "motion-only cuts the night at the first morning stir (~360)")

        val rescued = ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList(), heartRateSamples = hr, sleepVitalTimes = hrv)
        val rescuedEnd = assertNotNull(lastSleepEnd(rescued))
        assertTrue(minutesAfterBase(rescuedEnd) > 450, "sleep-vitals rescue extends the night's tail through the restless morning")
    }

    /** A genuine morning WAKE (HR climbs above the sleeping floor + margin) is NOT rescued. */
    @Test
    fun sleepVitalsRescueDoesNotRescueElevatedHRWake() { // :195-205
        val motion = stillMotion(0, 360) + spikyMotion(360, 480)
        val hr = hrSeries(0, 360, bpm = 55) + hrSeries(360, 480, bpm = 95) // awake HR after 360
        val hrv = hrvTimes(0, 470)

        val rescued = ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList(), heartRateSamples = hr, sleepVitalTimes = hrv)
        val end = assertNotNull(lastSleepEnd(rescued))
        assertTrue(minutesAfterBase(end) < 380, "elevated-HR morning is real wake, not rescued sleep")
    }

    /** No sleep-vitals coverage in the morning (ring stopped emitting HRV = awake) → no rescue. */
    @Test
    fun sleepVitalsRescueRequiresSleepVitalsCoverage() { // :209-219
        val motion = stillMotion(0, 360) + spikyMotion(360, 480)
        val hr = hrSeries(0, 480, bpm = 55)
        val hrv = hrvTimes(0, 360) // sleep vitals STOP at the stir — nothing to extend through

        val rescued = ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList(), heartRateSamples = hr, sleepVitalTimes = hrv)
        val end = assertNotNull(lastSleepEnd(rescued))
        assertTrue(minutesAfterBase(end) < 380, "without sleep-vitals coverage the morning stays active (no HR-only over-count)")
    }

    /** No regression: with no sleep-vitals times passed, detection is identical to before the rescue. */
    @Test
    fun sleepVitalsRescueNoOpWithoutCoverage() { // :222-229
        val motion = stillMotion(0, 300)
        val hr = hrSeries(0, 300, bpm = 60)
        assertEquals(
            ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList(), heartRateSamples = hr),
            ActivityPeriod.detectFromMotion(motion, temperatureSamples = emptyList(), heartRateSamples = hr, sleepVitalTimes = emptyList()),
            "no sleep-vitals coverage → identical to the pre-rescue result",
        )
    }
}
