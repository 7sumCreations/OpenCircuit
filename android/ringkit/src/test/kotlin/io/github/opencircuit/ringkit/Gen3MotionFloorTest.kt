package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream Gen3MotionFloorTests.swift (@ b1c2fdd), all 5 tests: the Gen 3 sleep-detection
 * regression. The `0x4c` motion channel idles at a device-dependent — and intra-night DRIFTING —
 * level: a still Gen 2 reads ~1, a still Gen 3 reads ~15–16 and steps to ~24, ~39 as sleeping posture
 * changes. An ABSOLUTE still threshold calibrated to Gen 2 classified every Gen 3 epoch as movement;
 * the fix measures stillness above a LOCAL rolling idle floor and bridges drift-step gaps.
 *
 * All data is SYNTHETIC — it reproduces the failure shape, not a person's night. Every literal is
 * typed from upstream.
 */
class Gen3MotionFloorTest {

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
    }

    /** A worn sleep-vitals epoch: HR/HRV/SpO2/RR present, all five motion bytes = [motion]. */
    private fun sleepEpoch(counter: Long, hr: Int, motion: Int, hrv: Int = 40, spo2: Int = 0x5e, rr: Int = 120): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[7] = rr.toByte(); b[8] = spo2.toByte() // sleep-vitals layout ([8] is an SpO2 %)
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    /** An awake/active epoch with VARYING motion (a moving wrist; not a constant reading). */
    private fun activeEpoch(counter: Long, i: Int): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = 90; b[8] = 0x12 // activity tag, awake HR
        val m = intArrayOf(0x0a, 0x30, 0x58)[i % 3]
        for (k in 0 until 5) b[10 + k] = m.toByte()
        return BulkRecord.of(b)!!
    }

    private val step = BulkRecord.EPOCH_SECONDS.toLong() // 150 s

    /**
     * [onset] active epochs, then still sleep plateaus at each [floors] level for [plateauEpochs]
     * epochs each (the posture drift), then [wake] active epochs.
     */
    private fun driftingNight(floors: List<Int>, plateauEpochs: Int, onset: Int = 8, wake: Int = 8): List<BulkRecord> {
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        for (i in 0 until onset) { recs += activeEpoch(c, i); c += step }
        var hr = 76
        for (floor in floors) {
            repeat(plateauEpochs) { recs += sleepEpoch(c, hr = hr, motion = floor); c += step }
            hr = if (hr > 2) hr - 2 else hr // HR drifts down deeper into the night
        }
        for (i in 0 until wake) { recs += activeEpoch(c, i); c += step }
        return recs
    }

    private fun secs(d: Duration): Double = d.seconds + d.nano / 1e9

    // The regression itself

    @Test
    fun testGen3ElevatedConstantFloorDetectsSleep() {
        // A flat Gen-3 night at a CONSTANT elevated floor (16) — what previously detected as zero sleep.
        val recs = driftingNight(floors = listOf(16), plateauEpochs = 120, onset = 6, wake = 6) // ~5 h still
        val block = assertNotNull(
            BulkSleep.mainSleep(recs),
            "an elevated but still Gen-3 floor must detect as sleep (was nil → blank cards)",
        )
        assertTrue(secs(block.duration) > 4.0 * 3600, "≈5 h still block recovered")
    }

    @Test
    fun testGen3DriftingFloorStaysOneNight() {
        // Floor steps 16 → 24 → 39 across the night (posture drift). Must read as ONE block, not three.
        val recs = driftingNight(floors = listOf(16, 24, 39), plateauEpochs = 40) // 3 × ~1.7 h = ~5 h
        val block = assertNotNull(BulkSleep.mainSleep(recs), "the drifting Gen-3 night must detect as sleep")
        assertTrue(
            secs(block.duration) > 4.0 * 3600,
            "drift-stepped plateaus bridge into one ~5 h night, not three short fragments",
        )
        // And the staged night is populated.
        val segs = BulkSleep.sleepSegments(recs)
        val asleep = segs.filter { it.stage == SleepStage.ASLEEP_CORE }.fold(0.0) { acc, s -> acc + secs(Duration.between(s.start, s.end)) }
        assertTrue(asleep > 3.0 * 3600, "most of the drifting night is staged as asleep")
        // The motion-floor STEPS must not masquerade as awakenings.
        val summary = SleepStaging.summary(SleepStaging.classify(recs)).minutes
        assertTrue(
            summary.asleep.toDouble() / maxOf(summary.inBed, 1L).toDouble() > 0.85,
            "drift steps stay asleep — most of the in-bed window is sleep, not false wake",
        )
        // Vitals samples flow regardless of staging (the health-store path).
        assertFalse(BulkSleep.samples(recs).filter { it.kind == MetricKind.HRV_SDNN }.isEmpty(), "HRV samples present")
    }

    @Test
    fun testGen2ConstantFloorUnchanged() {
        // Parity: Gen 2's flat `1` floor still detects sleep.
        val recs = driftingNight(floors = listOf(1), plateauEpochs = 120, onset = 6, wake = 6)
        assertNotNull(BulkSleep.mainSleep(recs), "Gen-2 still floor detection preserved")
    }

    @Test
    fun testVaryingActivityIsNotSleep() {
        // Safety invariant: genuinely VARYING motion is never staged as sleep, at any device's idle level.
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c220000L
        for (i in 0 until 160) { recs += activeEpoch(c, i); c += step }
        assertNull(BulkSleep.mainSleep(recs))
        assertTrue(BulkSleep.sleepSegments(recs).isEmpty())
    }

    // The de-flooring primitive

    @Test
    fun testRollingFloorTracksDriftToZero() {
        // A drifting-but-flat signal de-floors to ~0 in each plateau's interior once the rolling window
        // sits fully inside it; a varying signal keeps its excursions (active).
        val base = Instant.ofEpochSecond(1_700_000_000L)
        val plateau = 120
        val levels: List<Float> = List(plateau) { 16f } + List(plateau) { 24f } + List(plateau) { 39f }
        val times = levels.indices.map { base.plusSeconds(it * 30L) }
        val floored = ActivityPeriod.motionAboveLocalFloor(times.zip(levels).map { MotionSample(it.first, it.second) })
        // Deep interior of each plateau (well clear of the step boundaries) reads still.
        for (mid in listOf(plateau / 2, plateau + plateau / 2, 2 * plateau + plateau / 2)) {
            assertTrue(
                floored[mid] <= ActivityPeriod.MOTION_STILL_THRESHOLD,
                "flat plateau at level ${levels[mid]} de-floors to still",
            )
        }

        // A genuinely varying signal keeps excursions above the floor (active).
        val varying: List<Float> = (0 until 120).map { floatArrayOf(10f, 48f, 88f)[it % 3] }
        val vTimes = varying.indices.map { base.plusSeconds(it * 30L) }
        val vFloored = ActivityPeriod.motionAboveLocalFloor(vTimes.zip(varying).map { MotionSample(it.first, it.second) })
        assertTrue(
            (vFloored.maxOrNull() ?: 0f) > ActivityPeriod.MOTION_STILL_THRESHOLD,
            "varying motion keeps active excursions above the local floor",
        )
    }
}
