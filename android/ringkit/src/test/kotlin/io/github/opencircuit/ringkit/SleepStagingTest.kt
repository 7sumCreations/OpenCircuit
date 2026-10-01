package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream SleepStagingTests.swift (@ b1c2fdd), all 31 tests. SYNTHETIC-ONLY tests for the
 * sleep-stage classifier: there is no per-epoch ground truth (the ring sends no hypnogram), so these
 * build controlled epoch sequences with a known intended stage and assert the classifier recovers
 * it, plus one constructed night checking the stage totals partition the night the way the RingConn
 * app's night totals do (light ≫ rem > deep, modest awake).
 *
 * Every literal — record bytes, counters, thresholds, tolerances and the calibrated tuning values —
 * is typed from the upstream test, never read from the Kotlin `Tuning`. Upstream's
 * `latestNightRecords(from:)` and `isOvernightBlock` read the device calendar; here each call names
 * its zone: America/New_York, both for the counter-10 000 nights (see [deviceZone] — upstream's
 * vectors there depend on the machine's zone) and for the wall-clock night of the last test.
 */
class SleepStagingTest {

    @Test
    fun testQuietWakeOnsetCalibration() {
        // Locks the user-ground-truthed quiet-wake onset calibration against an unnoticed rollback.
        assertEquals(0.60, SleepStaging.Tuning.DEFAULT.onsetSettleFraction)
    }

    // Record builders

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
    }

    /** A sleep-vitals epoch (sub 0x62) with explicit HR/HRV and a uniform motion byte. */
    private fun vrec(counter: Long, hr: Int, hrv: Int = 0, motion: Int = 1): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    /** A sleep-vitals epoch that also sets the respiratory-rate byte `[7] = rr*8`. `rr` must be ≤ 31. */
    private fun vrecRR(counter: Long, hr: Int, hrv: Int = 0, motion: Int = 1, rr: Double): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[7] = Math.round(rr * 8).toByte(); b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    /** An active/awake epoch (sub 0x12, high motion, no vitals). */
    private fun arec(counter: Long, motion: Int = 0x14): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[8] = 0x12
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    private val step = 150L
    /**
     * The device zone for upstream's `latestNightRecords(from:)` calls. The counter-10 000 nights run
     * 14:46–22:26 UTC, and selection judges "overnight" in this zone: measured on the pinned build,
     * upstream's own vectors here fail in GMT and western Europe (selection keeps only the 51-record
     * restless tail) and the mid-night check fails in Los Angeles, Chicago and Denver. New York is a
     * zone where all of them pass upstream.
     */
    private val deviceZone: ZoneId = ZoneId.of("America/New_York")

    private fun secs(d: Duration): Double = d.seconds + d.nano / 1e9

    private fun date(counter: Long): Instant = Instant.ofEpochSecond(counter + Command.SYNC_EPOCH)

    /** Fraction of asleep (non-inBed, non-awake) time spent in [stage]. */
    private fun fraction(segs: List<SleepSegment>, stage: SleepStage): Double {
        val totals = SleepStaging.stageTotals(segs)
        val asleep = secs(totals[SleepStage.ASLEEP_CORE] ?: Duration.ZERO) +
            secs(totals[SleepStage.ASLEEP_DEEP] ?: Duration.ZERO) + secs(totals[SleepStage.ASLEEP_REM] ?: Duration.ZERO)
        if (!(asleep > 0)) return 0.0
        return secs(totals[stage] ?: Duration.ZERO) / asleep
    }

    // Sleep-vitals rescue reaches the STAGED (summary/Health) path

    /**
     * A still 6 h night followed by a ~100-min restless-but-asleep morning: HR stays at the sleeping
     * level and the ring keeps emitting sleep-vitals, but motion spikes so the motion detector alone
     * scores the morning "active".
     */
    private fun stillThenRestlessMorning(morningHR: Int, morningVitals: Boolean): List<BulkRecord> {
        val recs = mutableListOf<BulkRecord>()
        var c = 10_000L
        repeat(144) { recs += vrec(c, hr = 55, hrv = 60, motion = 1); c += step } // 6h still sleep
        for (i in 0 until 40) { // ~100-min morning
            recs += if (i % 2 == 0) vrec(c, hr = morningHR, hrv = if (morningVitals) 60 else 0, motion = 2) else arec(c, motion = 60)
            c += step
        }
        return recs
    }

    private fun asleepMinutes(segs: List<SleepSegment>): Double {
        val t = SleepStaging.stageTotals(segs)
        return (secs(t[SleepStage.ASLEEP_CORE] ?: Duration.ZERO) + secs(t[SleepStage.ASLEEP_DEEP] ?: Duration.ZERO) +
            secs(t[SleepStage.ASLEEP_REM] ?: Duration.ZERO)) / 60
    }

    @Test
    fun testStagedClassifierHonorsMovingButAsleepMorning() {
        val segs = SleepStaging.classify(BulkSleep.latestNightRecords(stillThenRestlessMorning(morningHR = 55, morningVitals = true), deviceZone))
        assertTrue(asleepMinutes(segs) > 400, "staged summary must count the moving-but-asleep morning, not trim it off")
    }

    @Test
    fun testStagedClassifierDoesNotCountElevatedHRMorning() {
        val asleepWake = asleepMinutes(
            SleepStaging.classify(BulkSleep.latestNightRecords(stillThenRestlessMorning(morningHR = 95, morningVitals = true), deviceZone)),
        )
        assertTrue(asleepWake < 380, "an elevated-HR (awake) morning must not be counted as sleep")
    }

    @Test
    fun testStagedClassifierRequiresSleepVitalsToCountRestlessMorning() {
        val asleepNoVitals = asleepMinutes(
            SleepStaging.classify(BulkSleep.latestNightRecords(stillThenRestlessMorning(morningHR = 55, morningVitals = false), deviceZone)),
        )
        assertTrue(asleepNoVitals < 380, "without sleep-vitals coverage the restless morning stays awake")
    }

    /** A 6 h still night with a ~40-min restless-but-low-HR episode in the MIDDLE (sustained sleep on both sides). */
    private fun stillWithMidNightRestless(episodeHR: Int, episodeVitals: Boolean): List<BulkRecord> {
        val recs = mutableListOf<BulkRecord>()
        var c = 10_000L
        repeat(72) { recs += vrec(c, hr = 55, hrv = 60, motion = 1); c += step } // 3h still sleep
        for (i in 0 until 16) { // ~40-min episode
            recs += if (i % 2 == 0) vrec(c, hr = episodeHR, hrv = if (episodeVitals) 60 else 0, motion = 2) else arec(c, motion = 60)
            c += step
        }
        repeat(72) { recs += vrec(c, hr = 55, hrv = 60, motion = 1); c += step } // 3h still sleep
        return recs
    }

    @Test
    fun testMidNightWASOIsImmuneToTheRescueButTheMorningTailIsNot() {
        fun asleep(recs: List<BulkRecord>, halfWindow: Int): Double = asleepMinutes(
            SleepStaging.classify(
                BulkSleep.latestNightRecords(recs, deviceZone),
                tuning = SleepStaging.Tuning(motionAwakeVitalsHalfWindow = halfWindow),
            ),
        )
        val mid = stillWithMidNightRestless(episodeHR = 55, episodeVitals = true)
        assertEquals(
            asleep(mid, halfWindow = 3), asleep(mid, halfWindow = 0), 5.0,
            "a mid-night WASO is interior, so the trailing-tail rescue must not change it",
        )
        val morning = stillThenRestlessMorning(morningHR = 55, morningVitals = true)
        assertTrue(
            asleep(morning, halfWindow = 3) > asleep(morning, halfWindow = 0) + 20,
            "the morning tail must still be rescued when the softening is on",
        )
    }

    // Single-stage recovery

    @Test
    fun testStillFlatLowHRIsMostlyDeep() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step }
        repeat(120) { recs += vrec(c, hr = 50); c += step } // flat, still, low
        repeat(12) { recs += arec(c); c += step }

        val segs = SleepStaging.classify(recs)
        assertFalse(segs.isEmpty())
        assertTrue(fraction(segs, SleepStage.ASLEEP_DEEP) > 0.8, "calm flat low HR -> mostly Deep")
        assertEquals(0.0, fraction(segs, SleepStage.ASLEEP_REM), "no variability/elevation -> no REM")
    }

    @Test
    fun testElevatedFlatHRLowMotionIsREM() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step }
        repeat(100) { recs += vrec(c, hr = 50); c += step } // Deep baseline
        val remStart = c
        repeat(40) { recs += vrec(c, hr = 66); c += step } // elevated, still
        val remEnd = c
        repeat(12) { recs += arec(c); c += step }

        val segs = SleepStaging.classify(recs)
        assertTrue(fraction(segs, SleepStage.ASLEEP_REM) > 0.2, "elevated still block -> REM present")
        // The bulk of REM should sit in the elevated window (±2 epochs of transition bleed tolerated).
        val lo = date(remStart).minusSeconds(2 * step)
        val hi = date(remEnd).plusSeconds(2 * step)
        val remSegs = segs.filter { it.stage == SleepStage.ASLEEP_REM }
        val remTotal = remSegs.sumOf { secs(it.duration) }
        val remInWindow = remSegs.filter { !it.start.isBefore(lo) && !it.end.isAfter(hi) }.sumOf { secs(it.duration) }
        assertTrue(remTotal > 0)
        assertTrue(remInWindow / remTotal >= 0.8, "REM concentrates in the elevated window")
    }

    @Test
    fun testVariabilitySeparatesREMFromLightAtSameMeanHR() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step }
        val flatStart = c
        repeat(100) { recs += vrec(c, hr = 55); c += step } // flat
        val varStart = c
        // 2-epoch steps so runs survive smoothing; mean 55, swings ±10.
        for (k in 0 until 40) { recs += vrec(c, hr = if ((k / 2) % 2 == 0) 45 else 65); c += step }
        repeat(12) { recs += arec(c); c += step }

        val segs = SleepStaging.classify(recs)
        val varLo = date(varStart)
        val flatLo = date(flatStart)
        val remInVar = segs.filter { it.stage == SleepStage.ASLEEP_REM && !it.start.isBefore(varLo) }.sumOf { secs(it.duration) }
        val remInFlat = segs.filter { it.stage == SleepStage.ASLEEP_REM && !it.start.isBefore(flatLo) && it.start.isBefore(varLo) }
            .sumOf { secs(it.duration) }
        assertTrue(remInVar > 0, "variable block reads as REM")
        assertTrue(remInVar > remInFlat, "REM concentrates in the variable block, not the flat one")
    }

    @Test
    fun testHighMotionMidSleepIsAwake() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step }
        repeat(60) { recs += vrec(c, hr = 52); c += step }
        val wakeStart = c
        repeat(3) { recs += arec(c, motion = 0x16); c += step } // mid-sleep movement
        repeat(60) { recs += vrec(c, hr = 52); c += step }
        repeat(12) { recs += arec(c); c += step }

        val segs = SleepStaging.classify(recs)
        val awake = segs.filter { it.stage == SleepStage.AWAKE }
        assertFalse(awake.isEmpty(), "mid-sleep motion -> Awake segment")
        val wt = date(wakeStart)
        assertTrue(awake.any { Math.abs(secs(Duration.between(wt, it.start))) < step * 4.0 }, "awake segment aligns with the motion burst")
        // Awake stays inside the inBed window.
        val inBed = segs.first { it.stage == SleepStage.IN_BED }
        for (a in awake) {
            assertTrue(!a.start.isBefore(inBed.start))
            assertTrue(!a.end.isAfter(inBed.end))
        }
    }

    // SpO2 + respiratory-rate fusion (additive; default-inert)

    @Test
    fun testRrVarWeightZeroIsNoOp() {
        fun night(rr: Boolean): List<BulkRecord> {
            val recs = mutableListOf<BulkRecord>()
            var c = 0x0c220000L
            repeat(12) { recs += arec(c); c += step }
            for (k in 0 until 140) {
                val hr = if (k % 3 == 0) 52 else 58
                recs += if (rr) vrecRR(c, hr = hr, hrv = 55, rr = if (k % 2 == 0) 13.0 else 18.0) else vrec(c, hr = hr, hrv = 55)
                c += step
            }
            repeat(12) { recs += arec(c); c += step }
            return recs
        }
        val withoutRR = SleepStaging.classify(night(rr = false))
        val withRR = SleepStaging.classify(night(rr = true))
        assertFalse(withRR.isEmpty())
        assertEquals(withoutRR, withRR, "RR carriage is inert at the default rrVarWeight (0): output is byte-identical with vs without RR")
        // And an explicit Tuning(rrVarWeight: 0) matches the default.
        assertEquals(withRR, SleepStaging.classify(night(rr = true), tuning = SleepStaging.Tuning(rrVarWeight = 0.0)))
    }

    @Test
    fun testRrVariabilityAddsREMCue() {
        // deepHR (p42) < target HR (54) < remHR (p86): only the target's RR variability can make it REM.
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step } // onset (awake)
        repeat(120) { recs += vrecRR(c, hr = 50, hrv = 50, rr = 15.0); c += step } // low, flat (Deep)
        val tStart = c
        for (k in 0 until 16) { recs += vrecRR(c, hr = 54, hrv = 50, rr = if (k % 2 == 0) 10.0 else 22.0); c += step } // FLAT HR/HRV, OSC RR
        val tEnd = c
        repeat(80) { recs += vrecRR(c, hr = 58, hrv = 50, rr = 15.0); c += step } // high, flat
        repeat(12) { recs += arec(c); c += step } // offset (awake)

        val lo = date(tStart)
        val hi = date(tEnd)
        fun remInTarget(w: Double): Double =
            SleepStaging.classify(recs, tuning = SleepStaging.Tuning(rrVarWeight = w))
                .filter { it.stage == SleepStage.ASLEEP_REM }
                .fold(0.0) { acc, s -> acc + maxOf(0.0, secs(Duration.between(maxOf(s.start, lo), minOf(s.end, hi)))) }

        val remOff = remInTarget(0.0)
        val remOn = remInTarget(1.0)
        assertEquals(0.0, remOff, 0.001, "with rrVarWeight:0 the flat-HR/flat-HRV block carries no REM")
        assertTrue(remOn > 0, "raising rrVarWeight turns the RR-variable block into REM")
        assertTrue(remOn > remOff, "RR variability adds a REM cue the HR-only model misses")
    }

    // HR-aware onset/offset ("still but awake")

    @Test
    fun testStillButElevatedHRPreSleepCountedAsAwakeInBedNotTrimmed() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        val preStart = c
        val preEpochs = 40
        repeat(preEpochs) { recs += vrec(c, hr = 78); c += step } // still but AWAKE (high HR)
        val onset = c
        repeat(120) { recs += vrec(c, hr = 54); c += step } // real sleep core
        repeat(8) { recs += arec(c); c += step } // morning (motion-active)

        val segs = SleepStaging.classify(recs)
        assertFalse(segs.isEmpty())
        val inBed = segs.first { it.stage == SleepStage.IN_BED }
        val preStartDate = date(preStart)
        val onsetDate = date(onset)
        // (a) in-bed spans the FULL bedtime window, pre-sleep included.
        assertEquals(0.0, secs(Duration.between(preStartDate, inBed.start)), step * 2.0, "in-bed spans the full bedtime window, pre-sleep included")
        // (b) asleep ≈ the core.
        val s = SleepStaging.summary(segs)
        val coreMin = (120 * step).toDouble() / 60
        assertEquals(coreMin, s.minutes.asleep.toDouble(), 30.0, "asleep reflects the real core, not the pre-sleep wake")
        // (c) the pre-sleep span surfaces as AWAKE-IN-BED.
        val awakeSegs = segs.filter { it.stage == SleepStage.AWAKE }
        assertTrue(
            awakeSegs.any {
                Math.abs(secs(Duration.between(preStartDate, it.start))) < step * 2.0 && !it.end.isAfter(onsetDate.plusSeconds(step * 2))
            },
            "pre-sleep wake-in-bed is an awake segment, not trimmed away",
        )
        // (d) efficiency < 1.
        assertTrue(s.efficiency < 1.0, "in-bed wake pulls efficiency below 100%")
        // Partition holds: in-bed == asleep + awake.
        assertEquals(secs(s.inBed), secs(s.totalAsleep) + secs(s.awake), step.toDouble())
    }

    @Test
    fun testStillAwakePrePostSleepGivesPlausibleEfficiency() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(8) { recs += arec(c); c += step } // up, before bed (outside block)
        val bedStart = c
        repeat(30) { recs += vrec(c, hr = 78); c += step } // in bed, awake, still
        repeat(120) { recs += vrec(c, hr = 54); c += step } // asleep core
        repeat(30) { recs += vrec(c, hr = 78); c += step } // awake in bed, still
        val bedEnd = c
        repeat(8) { recs += arec(c); c += step } // got up (outside block)

        val segs = SleepStaging.classify(recs)
        assertFalse(segs.isEmpty())
        val s = SleepStaging.summary(segs)

        val coreMin = (120 * step).toDouble() / 60
        assertEquals(coreMin, s.minutes.asleep.toDouble(), 30.0, "only the core counts as asleep, not the still-but-awake tails")
        assertTrue(s.efficiency > 0.6, "efficiency includes in-bed wake → < 1")
        assertTrue(s.efficiency < 0.8, "two awake tails pull efficiency down to ~0.67")
        assertEquals(secs(s.inBed), secs(s.totalAsleep) + secs(s.awake), step.toDouble())
        val inBed = segs.first { it.stage == SleepStage.IN_BED }
        assertEquals(0.0, secs(Duration.between(date(bedStart), inBed.start)), step * 2.0, "in-bed starts at bedtime, not onset")
        assertEquals(0.0, secs(Duration.between(date(bedEnd), inBed.end)), step * 2.0, "in-bed ends at final get-up, not last-asleep")
        assertTrue(segs.count { it.stage == SleepStage.AWAKE } >= 2, "pre- and post-sleep wake-in-bed both present")
    }

    @Test
    fun testInteriorSustainedHRWakeWithoutMotionIsAwake() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step }
        repeat(60) { recs += vrec(c, hr = 54); c += step }
        val wakeStart = c
        repeat(10) { recs += vrec(c, hr = 80); c += step } // still but AWAKE, 25 min
        repeat(60) { recs += vrec(c, hr = 54); c += step }
        repeat(12) { recs += arec(c); c += step }

        val segs = SleepStaging.classify(recs)
        val awake = segs.filter { it.stage == SleepStage.AWAKE }
        assertFalse(awake.isEmpty(), "sustained mid-sleep HR elevation -> Awake, even with no motion")
        val wt = date(wakeStart)
        assertTrue(awake.any { Math.abs(secs(Duration.between(wt, it.start))) < step * 4.0 }, "awake segment aligns with the HR elevation")
        val inBed = segs.first { it.stage == SleepStage.IN_BED }
        for (a in awake) assertTrue(a.end.isBefore(inBed.end))
    }

    // Descent-relative onset trim (the "mild wind-down" fix)

    @Test
    fun testMildWindDownBelowFixedMarginIsTrimmedAsAwakeInBed() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(8) { recs += arec(c); c += step } // before bed (outside block)
        repeat(12) { recs += vrec(c, hr = 62); c += step } // wind-down, still, < floor+18
        repeat(4) { recs += vrec(c, hr = 56); c += step } // settling
        val onset = c
        repeat(120) { recs += vrec(c, hr = 50); c += step } // asleep core
        repeat(8) { recs += arec(c); c += step } // morning (outside block)

        val segs = SleepStaging.classify(recs)
        val s = SleepStaging.summary(segs)
        val coreMin = (120 * step).toDouble() / 60
        assertEquals(coreMin, s.minutes.asleep.toDouble(), 30.0, "mild wind-down is not counted as asleep")
        assertTrue(s.efficiency < 0.95, "wind-down pulls efficiency below 100%")
        val onsetDate = date(onset)
        assertTrue(
            segs.any { it.stage == SleepStage.AWAKE && !it.end.isAfter(onsetDate.plusSeconds(step * 2)) },
            "the wind-down surfaces as awake-in-bed, ending at onset",
        )
        // Control: with the descent gate disabled, the SAME wind-down reads as asleep (eff ~1).
        val disabled = SleepStaging.classify(recs, tuning = SleepStaging.Tuning(onsetMinDescentBPM = 999.0))
        val sd = SleepStaging.summary(disabled)
        assertTrue(sd.minutes.asleep > s.minutes.asleep, "disabling the trim counts the wind-down as asleep (the old behavior)")
        assertTrue(sd.efficiency > s.efficiency)
    }

    @Test
    fun testFlatNightIsByteIdenticalWithTrimOnVsOff() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(8) { recs += arec(c); c += step }
        for (k in 0 until 140) { recs += vrec(c, hr = if (k % 3 == 0) 52 else 50, hrv = 55); c += step } // flat, calm
        repeat(8) { recs += arec(c); c += step }
        val on = SleepStaging.classify(recs)
        val off = SleepStaging.classify(recs, tuning = SleepStaging.Tuning(onsetMinDescentBPM = 999.0))
        assertEquals(on, off, "no wind-down (descent < gate) → trim is inert, output identical")
    }

    @Test
    fun testLateSettleBeyondSearchWindowIsNotTrimmed() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(8) { recs += arec(c); c += step }
        repeat(60) { recs += vrec(c, hr = 60); c += step } // 2.5 h elevated — beyond the 48-epoch search
        repeat(80) { recs += vrec(c, hr = 50); c += step } // settles only here
        repeat(8) { recs += arec(c); c += step }
        val on = SleepStaging.classify(recs)
        val off = SleepStaging.classify(recs, tuning = SleepStaging.Tuning(onsetMinDescentBPM = 999.0))
        assertEquals(
            SleepStaging.summary(on).minutes.awake, SleepStaging.summary(off).minutes.awake,
            "a late settle past the search window is not trimmed (bounded, no runaway)",
        )
    }

    // Lead-in wake onset (the "lay still but awake for hours" fix)

    @Test
    fun testFragmentedPreSleepAnchorsOnsetAfterLastWakeBlock() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(8) { recs += arec(c); c += step } // before bed (outside block)
        val firstDip = c
        repeat(10) { recs += vrec(c, hr = 64); c += step } // brief still dip (pre-sleep, NOT real sleep)
        repeat(16) { recs += vrec(c, hr = 90); c += step } // clearly AWAKE (~90 bpm), still
        val afterWake = c
        repeat(40) { recs += vrec(c, hr = 60); c += step } // early light sleep, above the descent band
        repeat(100) { recs += vrec(c, hr = 52); c += step } // deep consolidated sleep
        repeat(8) { recs += arec(c); c += step } // morning

        val segs = SleepStaging.classify(recs)
        assertFalse(segs.isEmpty())
        val win = assertNotNull(SleepStaging.sleepWindow(segs), "no sleep window")
        val firstDipDate = date(firstDip)
        val afterWakeDate = date(afterWake)
        assertTrue(
            !win.onset.isBefore(afterWakeDate.minusSeconds(step * 2)),
            "onset anchors after the last pre-sleep wake block, not the first still dip",
        )
        assertTrue(
            secs(Duration.between(firstDipDate, win.onset)) > step * 20.0,
            "the fragmented pre-sleep (dip + wake block) is well before onset",
        )
        // Control: with the lead-in rule off (consolidation guard 0 ⇒ never fires), onset regresses.
        val off = SleepStaging.classify(recs, tuning = SleepStaging.Tuning(minConsolidatedSleepEpochs = 0))
        val winOff = assertNotNull(SleepStaging.sleepWindow(off), "no window (off)")
        assertTrue(winOff.onset.isBefore(win.onset), "without the lead-in rule, onset anchors earlier (the bug)")
    }

    @Test
    fun testConsolidatedSleepBeforeEarlyWakeKeepsOnsetEarly() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(8) { recs += arec(c); c += step } // before bed (outside block)
        val onset = c
        repeat(30) { recs += vrec(c, hr = 52); c += step } // real sleep (75 min) BEFORE the stir
        repeat(8) { recs += vrec(c, hr = 90); c += step } // early awakening
        repeat(100) { recs += vrec(c, hr = 52); c += step } // back to sleep
        repeat(8) { recs += arec(c); c += step }

        val segs = SleepStaging.classify(recs)
        val win = assertNotNull(SleepStaging.sleepWindow(segs), "no sleep window")
        assertEquals(
            0.0, secs(Duration.between(date(onset), win.onset)), step * 3.0,
            "consolidated sleep before the stir keeps onset early — the stir is interior wake",
        )
        assertFalse(segs.none { it.stage == SleepStage.AWAKE }, "the early awakening still surfaces as an interior awake segment")
    }

    // Constructed-night partition (sanity vs. RingConn night totals)

    @Test
    fun testConstructedNightPartitionsLikeATracker() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(8) { recs += arec(c); c += step } // sleep onset (awake)
        for (cycle in 0 until 5) {
            for (k in 0 until 10) { recs += vrec(c, hr = if (k % 2 == 0) 54 else 62, hrv = 60); c += step } // Light (jittery mid)
            repeat(8) { recs += vrec(c, hr = 50, hrv = 70); c += step } // Deep (flat low)
            for (k in 0 until 8) { recs += vrec(c, hr = if (k % 2 == 0) 54 else 62, hrv = 60); c += step } // Light (jittery mid)
            for (k in 0 until 10) { recs += vrec(c, hr = if (k % 2 == 0) 64 else 78, hrv = 45); c += step } // REM (elevated, jittery)
            if (cycle < 4) repeat(2) { recs += arec(c, motion = 0x15); c += step } // brief wake
        }
        repeat(8) { recs += arec(c); c += step } // morning (awake)

        val segs = SleepStaging.classify(recs)
        assertFalse(segs.isEmpty())
        val s = SleepStaging.summary(segs)

        assertTrue(s.deep > Duration.ZERO); assertTrue(s.rem > Duration.ZERO)
        assertTrue(s.light > Duration.ZERO); assertTrue(s.awake > Duration.ZERO)
        assertTrue(s.light > s.rem, "Light is the largest asleep stage")
        assertTrue(s.rem > s.deep, "REM exceeds Deep")
        assertEquals(secs(s.inBed), secs(s.totalAsleep) + secs(s.awake), step.toDouble())
        assertTrue(s.efficiency > 0.6)
        assertTrue(s.efficiency <= 1.0)
        val totals = SleepStaging.stageTotals(segs)
        assertEquals(totals[SleepStage.ASLEEP_DEEP], s.deep)
        assertEquals(secs(SleepStaging.totalAsleep(segs)), secs(s.totalAsleep), 0.001)
    }

    // Stitching a night handed off in MULTIPLE fragments (the shrink fix)

    @Test
    fun testFragmentedNightIsStitchedAcrossGap() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        // Fragment 1: a ~5 h sleep core, bracketed by onset/EOF motion.
        repeat(8) { recs += arec(c); c += step }
        repeat(120) { recs += vrec(c, hr = 52); c += step }
        repeat(8) { recs += arec(c); c += step }
        // Data gap well past the detector's break threshold (no records for 2 h).
        c += 2 * 3600
        // Fragment 2: a second ~4 h sleep core.
        repeat(8) { recs += arec(c); c += step }
        repeat(100) { recs += vrec(c, hr = 52); c += step }
        repeat(8) { recs += arec(c); c += step }

        assertEquals(2, BulkSleep.contiguousFragments(recs).size, "gap splits into two fragments")

        val segs = SleepStaging.classify(recs)
        assertFalse(segs.isEmpty())
        val s = SleepStaging.summary(segs)
        val bothCoresMin = ((120 + 100) * step).toDouble() / 60
        assertEquals(bothCoresMin, s.minutes.asleep.toDouble(), 40.0, "stitched asleep covers both fragments, not just the latest")
        val inBeds = segs.filter { it.stage == SleepStage.IN_BED }
        assertEquals(2, inBeds.size, "one in-bed segment per fragment")
        val wallSpan = secs(Duration.between(segs.minOf { it.start }, segs.maxOf { it.end }))
        assertTrue(wallSpan - secs(s.inBed) > 1.5 * 3600, "the inter-fragment gap is excluded from in-bed")
        assertEquals(secs(s.inBed), secs(s.totalAsleep) + secs(s.awake), step * 2.0)
    }

    @Test
    fun testSingleFragmentNightUnchangedByStitch() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(8) { recs += arec(c); c += step }
        repeat(120) { recs += vrec(c, hr = 52); c += step }
        repeat(8) { recs += arec(c); c += step }
        assertEquals(1, BulkSleep.contiguousFragments(recs).size)
        val segs = SleepStaging.classify(recs)
        assertEquals(1, segs.count { it.stage == SleepStage.IN_BED })
    }

    @Test
    fun testNoSleepBlockYieldsNoSegments() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(80) { recs += arec(c, motion = 0x18); c += step }
        assertTrue(SleepStaging.classify(recs).isEmpty(), "all-active -> no staging")
        assertTrue(SleepStaging.stageTotals(emptyList()).isEmpty())
        assertEquals(Duration.ZERO, SleepStaging.totalAsleep(emptyList()))
    }

    @Test
    fun testBulkSleepStagedSegmentsDelegatesToClassifier() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step }
        repeat(120) { recs += vrec(c, hr = 50); c += step }
        repeat(12) { recs += arec(c); c += step }
        assertEquals(
            SleepStaging.classify(recs), BulkSleep.stagedSegments(recs),
            "BulkSleep.stagedSegments is a thin wrapper over SleepStaging.classify",
        )
    }

    // All health-store sleep-analysis stage values

    @Test
    fun testStagedSegmentsProduceAllFiveHealthKitStages() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(8) { recs += arec(c); c += step } // sleep onset (awake)
        repeat(5) {
            repeat(10) { recs += vrec(c, hr = 57, hrv = 55); c += step } // Light/core
            repeat(8) { recs += vrec(c, hr = 50, hrv = 65); c += step } // Deep
            repeat(8) { recs += vrec(c, hr = 57, hrv = 55); c += step } // Light/core
            repeat(10) { recs += vrec(c, hr = 65, hrv = 40); c += step } // REM (elevated)
            repeat(2) { recs += arec(c, motion = 0x15); c += step } // brief Awake
        }
        repeat(8) { recs += arec(c); c += step } // wake-up (awake)

        val segs = BulkSleep.stagedSegments(recs)
        assertFalse(segs.isEmpty(), "staged segments must be produced")

        val present = segs.map { it.stage }.toSet()
        assertTrue(SleepStage.IN_BED in present, "inBed span required")
        assertTrue(SleepStage.ASLEEP_CORE in present, "asleepCore (light) required")
        assertTrue(SleepStage.ASLEEP_DEEP in present, "asleepDeep required")
        assertTrue(SleepStage.ASLEEP_REM in present, "asleepREM required")
        assertTrue(SleepStage.AWAKE in present, "awake required")
    }

    @Test
    fun testSleepStageEnumCoversAllHealthKitValues() {
        val required = setOf(SleepStage.IN_BED, SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM, SleepStage.AWAKE)
        assertEquals(required, SleepStage.entries.toSet(), "SleepStage must cover exactly the 5 sleep-analysis cases")
    }

    // Dedup: the sleep cursor gates re-writing the same night

    @Test
    fun testSleepCursorDedupsByMaxEndDate() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step }
        repeat(120) { recs += vrec(c, hr = 55); c += step }
        repeat(12) { recs += arec(c); c += step }

        val segs = BulkSleep.stagedSegments(recs)
        assertFalse(segs.isEmpty(), "need staged segments for this test")
        val maxEnd = assertNotNull(segs.maxOfOrNull { it.end }, "no end dates")

        val cursor = SyncCursor()
        assertTrue(cursor.isNew(MetricKind.SLEEP, maxEnd), "fresh cursor: staged night is new (must be written)")
        cursor.advance(MetricKind.SLEEP, maxEnd)
        assertFalse(cursor.isNew(MetricKind.SLEEP, maxEnd), "after write: same night must not be written again (dedup)")
        val nextNight = maxEnd.plusSeconds(1)
        assertTrue(cursor.isNew(MetricKind.SLEEP, nextNight), "next night is newer than cursor: must be written")
    }

    // Personal (multi-night) baseline — Deep band anchoring

    @Test
    fun testPersonalBaselineFactory() {
        assertNull(SleepStaging.PersonalBaseline.fromRecentDeepHR(listOf(51, 52), minNights = 3), "too few nights → no baseline (stay single-night)")
        assertNull(
            SleepStaging.PersonalBaseline.fromRecentDeepHR(listOf(0, 0, 51), minNights = 3),
            "zeros (no-Deep nights) are filtered → too few valid → nil",
        )
        // Odd count: sorted valid [51,51,52,75,102] → median 52.
        assertEquals(52.0, SleepStaging.PersonalBaseline.fromRecentDeepHR(listOf(51, 75, 52, 51, 102))?.deepSleepHR)
        // Even count → TRUE median: sorted [48,49,70,72] → (49+70)/2 = 59.5.
        assertEquals(59.5, SleepStaging.PersonalBaseline.fromRecentDeepHR(listOf(72, 48, 70, 49))?.deepSleepHR)
    }

    @Test
    fun testBaselineSuppressesDeepOnGloballyElevatedNight() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step }
        repeat(120) { recs += vrec(c, hr = 70, hrv = 55); c += step } // flat, still, ELEVATED
        repeat(12) { recs += arec(c); c += step }

        val noBaseline = SleepStaging.classify(recs)
        val withBaseline = SleepStaging.classify(recs, baseline = SleepStaging.PersonalBaseline(deepSleepHR = 50.0))
        assertTrue(fraction(noBaseline, SleepStage.ASLEEP_DEEP) > 0.5, "single-night: the flat block reads as Deep relative to its own distribution")
        assertTrue(
            fraction(withBaseline, SleepStage.ASLEEP_DEEP) < 0.05,
            "with a personal baseline, a 70-bpm night is not deep for a person whose deep HR is ~50",
        )
        assertTrue(fraction(withBaseline, SleepStage.ASLEEP_CORE) > 0.8, "baseline-suppressed Deep relabels to Light")
        assertTrue(fraction(withBaseline, SleepStage.ASLEEP_REM) < 0.2, "a flat elevated night does not spuriously read as all-REM")
    }

    @Test
    fun testBaselineIsInertWhenItDoesNotBind() {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        repeat(12) { recs += arec(c); c += step }
        repeat(120) { recs += vrec(c, hr = 50, hrv = 55); c += step } // normal calm low-HR night
        repeat(12) { recs += arec(c); c += step }

        val single = SleepStaging.classify(recs)
        assertEquals(
            single, SleepStaging.classify(recs, baseline = SleepStaging.PersonalBaseline(deepSleepHR = 50.0)),
            "a baseline matching the night's deep HR changes nothing",
        )
        assertEquals(
            single, SleepStaging.classify(recs, baseline = SleepStaging.PersonalBaseline(deepSleepHR = 80.0)),
            "a non-binding baseline is byte-identical to the single-night classifier",
        )
    }

    // Night-scoping cap (the "no sleep recorded" regression)

    @Test
    fun testLatestNightRecordsCapsAllDayBridgedBlockToOneNight() {
        // Upstream builds this on Calendar.current and today's date; here a fixed date in a named zone.
        val zone = ZoneId.of("America/New_York")
        val wake = LocalDate.of(2026, 6, 15).atTime(LocalTime.of(8, 0)).atZone(zone).toInstant()
        // 20 h of continuous still, low-HR epochs ending at wake — a sedentary day bridged into the night.
        val start = wake.minus(Duration.ofHours(20))
        assertTrue(Duration.between(start, wake) > BulkSleep.MAX_NIGHT_SPAN, "precondition: the bridged block exceeds maxNightSpan")
        var c = start.epochSecond - Command.SYNC_EPOCH
        val recs = mutableListOf<BulkRecord>()
        repeat((Duration.between(start, wake).seconds / 150).toInt()) { recs += vrec(c, hr = 52, hrv = 55); c += step }

        val scoped = BulkSleep.latestNightRecords(recs, zone)
        val lo = assertNotNull(scoped.minOfOrNull { it.date() }, "scoping returned nothing")
        val hi = scoped.maxOf { it.date() }
        assertTrue(
            Duration.between(lo, hi) <= BulkSleep.MAX_NIGHT_SPAN.plusHours(1),
            "a >maxNightSpan bridged block must be capped to one night, not returned whole",
        )
        assertEquals(0.0, secs(Duration.between(wake, hi)), 3600.0, "the scoped window ends at the latest night's wake")
        val inBeds = SleepStaging.classify(scoped).filter { it.stage == SleepStage.IN_BED }
        assertEquals(1, inBeds.size, "one capped night → a single in-bed window, not a stitched >24 h span")
        assertTrue(
            SleepWindow.isOvernightBlock(inBeds[0].start, inBeds[0].end, zone),
            "the capped night is overnight, so overnightStagedSegments persists it",
        )
    }
}
