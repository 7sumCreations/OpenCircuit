package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port of upstream SleepContinuationTests.swift (@ b1c2fdd), all 9 tests: "sleep never continues
 * after a mid-night wake". The ring recorded the whole night, but one night-wide sleeping floor set
 * by the deep-rich first bout made every epoch of a legitimately lighter second bout read awake at an
 * objectively low HR. The fix is `rescueSecondBoutHRWake`, an ADD-only pass whose
 * `hrWakeRescueCeilingBPM == 0` kill switch is byte-identical to the pre-rescue staging.
 *
 * SYNTHETIC-ONLY (the ring sends no hypnogram): these build a controlled bimodal night and assert the
 * classifier recovers the second bout with the rescue on and loses it with the rescue off. Every
 * literal is typed from upstream.
 */
class SleepContinuationTest {

    private val step = 150L
    private val base = 0x0c220000L
    private val asleep = setOf(SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM)

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
    }

    /** A sleep-vitals epoch carrying HR + HRV and a uniform still motion byte (de-floors to 0). */
    private fun vrec(counter: Long, hr: Int, hrv: Int = 55, motion: Int = 1): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    /** A motion/activity epoch (sub 0x12, high motion, no vitals): the bathroom trip / onset / offset. */
    private fun arec(counter: Long, motion: Int = 0x14): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[8] = 0x12
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    private fun date(counter: Long): Instant = Instant.ofEpochSecond(counter + Command.SYNC_EPOCH)

    private fun secs(d: Duration): Double = d.seconds + d.nano / 1e9

    private fun later(a: Instant, b: Instant): Instant = if (b.isAfter(a)) b else a
    private fun earlier(a: Instant, b: Instant): Instant = if (b.isBefore(a)) b else a

    /** Minutes of ASLEEP (Core/Deep/REM) time whose segments overlap `[lo, hi)`. */
    private fun asleepMinutes(segs: List<SleepSegment>, from: Instant, to: Instant): Double =
        segs.filter { it.stage in asleep }.fold(0.0) { acc, s ->
            acc + maxOf(0.0, secs(Duration.between(later(s.start, from), earlier(s.end, to)))) / 60
        }

    private class Night(val records: List<BulkRecord>, val secondStart: Instant, val secondEnd: Instant)

    /**
     * An awake onset, a FIRST bout at a low resting HR, a ~10-min bathroom trip (motion), then a SECOND
     * bout at [secondHR] (still, sleep-vitals present), then an awake offset. Both bouts are FLAT so
     * the p12 sleeping floor is deterministically [firstHR].
     */
    private fun midNightWakeNight(firstHR: Int, secondHR: Int, firstEpochs: Int = 96, tripEpochs: Int = 4, secondEpochs: Int = 96): Night {
        val recs = mutableListOf<BulkRecord>()
        var c = base
        repeat(12) { recs += arec(c); c += step } // awake onset
        repeat(firstEpochs) { recs += vrec(c, hr = firstHR); c += step }
        repeat(tripEpochs) { recs += arec(c); c += step } // bathroom trip (motion)
        val secondStart = date(c)
        repeat(secondEpochs) { recs += vrec(c, hr = secondHR); c += step }
        val secondEnd = date(c)
        repeat(12) { recs += arec(c); c += step } // awake offset
        return Night(recs, secondStart, secondEnd)
    }

    // The bug, and the fix

    /**
     * Swept across the rescue band: with the rescue ON (default) the second bout is recovered, with it
     * OFF (kill switch) it is lost. Floor 50 → wake threshold 68, rescue ceiling 75.
     */
    @Test
    fun testSecondBoutRecoveredAcrossRescueBand() {
        val floor = 50
        val off = SleepStaging.Tuning(hrWakeRescueCeilingBPM = 0.0) // kill switch = pre-fix behaviour
        for (hr2 in listOf(60, 64, 68, 71, 74, 76, 80)) {
            val night = midNightWakeNight(firstHR = floor, secondHR = hr2)
            val onSegs = SleepStaging.classify(night.records) // rescue ON
            val offSegs = SleepStaging.classify(night.records, tuning = off) // rescue OFF
            val on = asleepMinutes(onSegs, night.secondStart, night.secondEnd)
            val offM = asleepMinutes(offSegs, night.secondStart, night.secondEnd)
            val full = secs(Duration.between(night.secondStart, night.secondEnd)) / 60 // ~240 min

            if (hr2 < 68) {
                // Below the wake threshold the second bout was never awake — rescue is a no-op.
                assertEquals(offM, on, 1.0, "hr2=$hr2: below threshold, rescue must not change anything")
                assertTrue(on > full * 0.9, "hr2=$hr2: sub-threshold bout stays asleep")
            } else if (hr2 < 75) {
                // In-band: OFF loses (almost) the whole bout (the bug); ON recovers (almost) all of it.
                assertTrue(offM < full * 0.2, "hr2=$hr2: BUG — with the rescue off the second bout is lost as awake")
                assertTrue(on > full * 0.8, "hr2=$hr2: FIX — the rescue recovers the second bout as sleep")
                assertTrue(on > offM + full * 0.5, "hr2=$hr2: the rescue must add back most of the lost bout")
            } else {
                // At/above the ceiling the elevated bout is genuine wake — the rescue must NOT fire.
                assertEquals(offM, on, 1.0, "hr2=$hr2: above the ceiling, genuine wake must stay awake with the rescue on")
                assertTrue(on < full * 0.2, "hr2=$hr2: genuine wake is not rescued")
            }
        }
    }

    /** The exact tester scenario: ~4 h asleep before a 3 a.m. trip and ~4 h after it. */
    @Test
    fun testTesterNightContinuesAfterBathroomTrip() {
        val night = midNightWakeNight(firstHR = 50, secondHR = 71) // 71 = floor+21, squarely in-band
        val fixed = SleepStaging.classify(night.records)
        val buggy = SleepStaging.classify(night.records, tuning = SleepStaging.Tuning(hrWakeRescueCeilingBPM = 0.0))

        val lostBefore = asleepMinutes(buggy, night.secondStart, night.secondEnd)
        val keptAfter = asleepMinutes(fixed, night.secondStart, night.secondEnd)
        assertTrue(lostBefore < 40, "pre-fix: the ~4 h second bout is dropped")
        assertTrue(keptAfter > 200, "post-fix: the ~4 h second bout is logged as sleep")

        // Whole-night total roughly doubles.
        val totalFixed = secs(SleepStaging.totalAsleep(fixed))
        val totalBuggy = secs(SleepStaging.totalAsleep(buggy))
        assertTrue(totalFixed > totalBuggy + 200 * 60, "the fix restores several hours to the night's total asleep time")
    }

    /**
     * With the morning-tail softening OFF the trip (motion) and the second bout (HR-only) are ONE awake
     * run; the rescue must relabel only the motion-free tail and leave the getting-up awake.
     */
    @Test
    fun testBathroomTripStaysAwakeWithSofteningOff() {
        val night = midNightWakeNight(firstHR = 50, secondHR = 71)
        val tuning = SleepStaging.Tuning(motionAwakeVitalsHalfWindow = 0) // softening off; rescue on
        val segs = SleepStaging.classify(night.records, tuning = tuning)

        // The second bout is still recovered…
        assertTrue(
            asleepMinutes(segs, night.secondStart, night.secondEnd) > 200,
            "with softening off the motion-free second bout is still rescued",
        )
        // …while the trip immediately before it stays awake.
        val tripStart = night.secondStart.minusSeconds(4 * step) // 4 trip epochs
        val tripAwake = segs.any { it.stage == SleepStage.AWAKE && it.start.isBefore(night.secondStart) && it.end.isAfter(tripStart) }
        assertTrue(tripAwake, "the getting-up itself stays scored as a brief awakening")
    }

    // Safety: the rescue only ever ADDS sleep, and never on the wrong night

    /** Lying STILL and AWAKE for hours at the START, then falling asleep: no sleep behind the wake block. */
    @Test
    fun testLieAwakeFirstNightIsNotRescued() {
        val recs = mutableListOf<BulkRecord>()
        var c = base
        repeat(6) { recs += arec(c); c += step } // brief settle
        val awakeStart = date(c)
        // 60 epochs (~2.5 h) STILL but AWAKE at floor+20, sleep-vitals present.
        for (k in 0 until 60) { recs += vrec(c, hr = if (k % 2 == 0) 70 else 69); c += step }
        val awakeEnd = date(c)
        for (k in 0 until 120) { recs += vrec(c, hr = if (k % 2 == 0) 50 else 49); c += step } // real sleep
        repeat(12) { recs += arec(c); c += step }

        val fixed = SleepStaging.classify(recs)
        val off = SleepStaging.classify(recs, tuning = SleepStaging.Tuning(hrWakeRescueCeilingBPM = 0.0))
        // The rescue must not convert the pre-sleep wakefulness to sleep: output is unchanged vs OFF.
        assertEquals(off, fixed, "a lie-awake-FIRST night has no sleep behind the wake block — the rescue must be a no-op")
        // And that leading stretch is (mostly) awake, not logged as sleep.
        val asleepInLeadIn = asleepMinutes(fixed, awakeStart, awakeEnd)
        assertTrue(asleepInLeadIn < 40, "the pre-sleep wake is not logged as sleep")
    }

    /** A still second bout well ABOVE the rescue ceiling is a genuine awakening — the rescue leaves it awake. */
    @Test
    fun testElevatedSecondBoutIsNotRescued() {
        val night = midNightWakeNight(firstHR = 50, secondHR = 90)
        val fixed = SleepStaging.classify(night.records)
        val off = SleepStaging.classify(night.records, tuning = SleepStaging.Tuning(hrWakeRescueCeilingBPM = 0.0))
        assertEquals(off, fixed, "a clearly-elevated second bout is above the ceiling — the rescue is a no-op")
        assertTrue(asleepMinutes(fixed, night.secondStart, night.secondEnd) < 40, "genuine elevated wake stays awake")
    }

    /** A brief above-ceiling HR AROUSAL inside a still second bout stays awake; the calm stretches are rescued. */
    @Test
    fun testAboveCeilingArousalWithinBoutStaysAwake() {
        val recs = mutableListOf<BulkRecord>()
        var c = base
        repeat(12) { recs += arec(c); c += step }
        repeat(96) { recs += vrec(c, hr = 50); c += step } // first bout (floor 50)
        repeat(4) { recs += arec(c); c += step } // get up briefly
        repeat(20) { recs += vrec(c, hr = 70); c += step } // calm, in-band
        val spikeStart = date(c)
        repeat(8) { recs += vrec(c, hr = 92); c += step } // STILL but HR arousal (>ceiling)
        val spikeEnd = date(c)
        repeat(20) { recs += vrec(c, hr = 70); c += step } // calm, in-band again
        repeat(12) { recs += arec(c); c += step }

        val segs = SleepStaging.classify(recs)
        // The arousal stays awake…
        val arousalAwake = segs.any { it.stage == SleepStage.AWAKE && it.start.isBefore(spikeEnd) && it.end.isAfter(spikeStart) }
        assertTrue(arousalAwake, "an above-ceiling arousal inside the bout is preserved as awake")
        // …but the still, in-band stretches around it are rescued (net asleep well over the spike alone).
        assertTrue(asleepMinutes(segs, spikeEnd, date(c)) > 30, "the calm in-band stretch after the arousal is still rescued")
    }

    /** Two successive mid-night wakes: BOTH the second and third bouts must be recovered. */
    @Test
    fun testTwoSuccessiveMidNightWakesBothRecovered() {
        val recs = mutableListOf<BulkRecord>()
        var c = base
        repeat(12) { recs += arec(c); c += step }
        repeat(96) { recs += vrec(c, hr = 50); c += step } // first bout
        repeat(4) { recs += arec(c); c += step } // wake 1
        val secondStart = date(c)
        repeat(40) { recs += vrec(c, hr = 71); c += step } // second bout (in-band)
        val secondEnd = date(c)
        repeat(4) { recs += arec(c); c += step } // wake 2
        val thirdStart = date(c)
        repeat(40) { recs += vrec(c, hr = 71); c += step } // third bout (in-band)
        val thirdEnd = date(c)
        repeat(12) { recs += arec(c); c += step }

        val segs = SleepStaging.classify(recs)
        assertTrue(asleepMinutes(segs, secondStart, secondEnd) > 80, "second bout recovered")
        assertTrue(
            asleepMinutes(segs, thirdStart, thirdEnd) > 80,
            "third bout recovered (backed by the genuine first bout, not only the rescued second)",
        )
    }

    // Kill switch is byte-identical

    /** Ceiling 0 is byte-identical to an explicit disabled tuning, and differs from the rescued default. */
    @Test
    fun testKillSwitchByteIdentical() {
        val night = midNightWakeNight(firstHR = 50, secondHR = 71)
        val a = SleepStaging.classify(night.records, tuning = SleepStaging.Tuning(hrWakeRescueCeilingBPM = 0.0))
        val b = SleepStaging.classify(night.records, tuning = SleepStaging.Tuning(hrWakeRescueCeilingBPM = 0.0))
        assertEquals(b, a)
        // And it genuinely differs from the rescued default (so the knob is load-bearing).
        val def = SleepStaging.classify(night.records)
        assertNotEquals(def, a, "ceiling 0 must actually disable the rescue")
    }

    /** The default tuning ships the rescue ON at 25 bpm and 0.5 vitals fraction (locks the calibration). */
    @Test
    fun testDefaultRescueCalibration() {
        assertEquals(25.0, SleepStaging.Tuning.DEFAULT.hrWakeRescueCeilingBPM)
        assertEquals(0.5, SleepStaging.Tuning.DEFAULT.hrWakeRescueVitalsFraction)
    }
}
