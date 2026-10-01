package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Port of upstream BedtimeWidenTests.swift (@ b1c2fdd), all 10 tests: the bedtime widen
 * (`preOnsetBedtimeReachEpochs`) re-opens the in-bed envelope over a MEASURED awake-in-bed lead-in the
 * motion still-block missed — fixing the "time in bed == time asleep / 100 % efficiency" fast-onset
 * defect without re-timing sleep and without fabricating a latency.
 *
 * Fixtures mirror upstream's device-confirmed shape: a moving-but-HR-elevated lead-in (reading in
 * bed), a short still settle at sleep level, then a DATA GAP, then a still low-HR sleep block. The gap
 * is load-bearing: it splits the lead-in into a fragment that stages to nothing, so the sleep block
 * collapses to in-bed start == onset; the widen runs over the FULL record set and reaches the lead-in
 * across the gap. Every literal is typed from upstream.
 */
class BedtimeWidenTest {

    private val step = 150L
    private val asleep = setOf(SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM)

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
    }

    /** A still sleep-vitals epoch (motion `1` → joins the motion block). */
    private fun srec(counter: Long, hr: Int): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = 60; b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = 1
        return BulkRecord.of(b)!!
    }

    /**
     * A MOVING epoch that still carries HR (non-uniform motion → scored active, so it is EXCLUDED from
     * the still block). Layout `0x62` keeps the heart rate decodable.
     */
    private fun mrec(counter: Long, hr: Int): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = 60; b[8] = 0x62
        val m = intArrayOf(30, 8, 31, 9, 30)
        for (k in 0 until 5) b[10 + k] = m[k].toByte()
        return BulkRecord.of(b)!!
    }

    /**
     * [leadN moving epochs @ leadHR] [3 still @ 53 (sleep-level, so the gap bridges)] [gapN missing]
     * [120 still @ 52 = 5 h sleep]. The default lead is elevated (awake); `leadHR = 52` is a flat-floor
     * lead-in that carries no awake evidence.
     */
    private fun night(leadHR: Int = 72, leadN: Int = 12, gapN: Int = 12): List<BulkRecord> {
        val recs = mutableListOf<BulkRecord>()
        var c = 100_000L
        repeat(leadN) { recs += mrec(c, hr = leadHR); c += step }
        repeat(3) { recs += srec(c, hr = 53); c += step }
        c += step * gapN
        repeat(120) { recs += srec(c, hr = 52); c += step }
        return recs
    }

    private fun onset(s: List<SleepSegment>): Instant? = s.filter { it.stage in asleep }.minOfOrNull { it.start }
    private fun wake(s: List<SleepSegment>): Instant? = s.filter { it.stage in asleep }.maxOfOrNull { it.end }
    private fun inBedStart(s: List<SleepSegment>): Instant? = s.filter { it.stage == SleepStage.IN_BED }.minOfOrNull { it.start }
    private fun tuning(reach: Int): SleepStaging.Tuning = SleepStaging.Tuning(preOnsetBedtimeReachEpochs = reach)
    private fun secs(d: Duration): Double = d.seconds + d.nano / 1e9

    // Core behaviour

    @Test
    fun testReachZeroReproducesTheFastOnsetCollapse() {
        val off = SleepStaging.classify(night(), tuning = tuning(reach = 0))
        assertEquals(onset(off), inBedStart(off), "reach=0: in-bed collapses onto onset (the defect)")
        assertEquals(1.0, SleepStaging.summary(off).efficiency, 0.001)
    }

    @Test
    fun testFastOnsetWidensInBedButNotSleep() {
        val recs = night()
        val off = SleepStaging.classify(recs, tuning = tuning(reach = 0))
        val on = SleepStaging.classify(recs, tuning = tuning(reach = 40))

        val bedOn = inBedStart(on)
        val onsetOn = onset(on)
        if (bedOn == null || onsetOn == null) fail("no segments")
        assertTrue(bedOn.isBefore(onsetOn), "widen pushes in-bed start before onset")
        assertTrue(SleepStaging.summary(on).efficiency < 1.0, "efficiency must drop below 100 %")
        assertTrue(SleepStaging.summary(on).awake > Duration.ZERO, "a pre-onset awake segment must exist")

        // The anchors and the sleep clock must NOT move — the widen adds only pre-onset in-bed awake.
        assertEquals(onset(off), onset(on), "onset unchanged")
        assertEquals(wake(off), wake(on), "wake unchanged")
        assertEquals(
            secs(SleepStaging.summary(off).totalAsleep), secs(SleepStaging.summary(on).totalAsleep), 0.5, "time asleep unchanged",
        )
    }

    @Test
    fun testEnvelopeTilesWithoutGapOrOverlap() {
        val on = SleepStaging.classify(night(), tuning = tuning(reach = 40))
        val env = on.firstOrNull { it.stage == SleepStage.IN_BED } ?: fail("no inBed")
        val inner = on.filter { it.stage != SleepStage.IN_BED }.sortedBy { it.start }
        assertEquals(env.start, inner.firstOrNull()?.start, "first child starts at the envelope start")
        assertEquals(env.end, inner.lastOrNull()?.end, "last child ends at the envelope end")
        for ((a, b) in inner.zipWithNext()) {
            assertEquals(b.start, a.end, "children tile contiguously (no gap/overlap)")
        }
    }

    // Honesty guards (never fabricate)

    @Test
    fun testNoPreOnsetRecordsIsNoOp() {
        // Sleep block with nothing before it → no measured lead-in → no widen.
        val recs = mutableListOf<BulkRecord>()
        var c = 100_000L
        repeat(120) { recs += srec(c, hr = 52); c += step }
        val on = SleepStaging.classify(recs, tuning = tuning(reach = 40))
        assertEquals(onset(on), inBedStart(on), "no pre-onset epochs → never fabricate a latency")
    }

    @Test
    fun testFlatFloorLeadInIsNoOp() {
        // Pre-onset epochs exist but HR is already at the sleeping floor (no awake evidence).
        val on = SleepStaging.classify(night(leadHR = 52), tuning = tuning(reach = 40))
        assertEquals(onset(on), inBedStart(on), "flat-floor pre-onset HR → no awake evidence → no widen")
    }

    @Test
    fun testReachBoundsHowFarBack() {
        // A reach smaller than the gap can't reach the lead-in; one that clears the gap widens.
        val recs = night(leadHR = 72, leadN = 20, gapN = 12) // gap = 12 epochs
        val tooShort = SleepStaging.classify(recs, tuning = tuning(reach = 8)) // < gap
        val long = SleepStaging.classify(recs, tuning = tuning(reach = 48)) // clears gap
        assertEquals(onset(tooShort), inBedStart(tooShort), "reach inside the gap can't reach the lead-in")
        val bLong = inBedStart(long)
        val oLong = onset(long)
        if (bLong == null || oLong == null) fail("no segments")
        assertTrue(bLong.isBefore(oLong), "a reach that clears the gap widens back to the lead-in")
    }

    @Test
    fun testStillButAwakeLeadInIsUntouched() {
        // A still awake lead-in joins the motion block and the HR gate already marks it pre-onset
        // awake; the widen's fast-onset guard must skip it — identical output at reach 0 and 40.
        val recs = mutableListOf<BulkRecord>()
        var c = 100_000L
        repeat(8) { recs += srec(c, hr = 72); c += step } // still-but-awake, in-block
        repeat(120) { recs += srec(c, hr = 52); c += step }
        val off = SleepStaging.classify(recs, tuning = tuning(reach = 0))
        val on = SleepStaging.classify(recs, tuning = tuning(reach = 40))
        assertEquals(inBedStart(off), inBedStart(on), "existing lead-in night: in-bed start unchanged")
        assertEquals(onset(off), onset(on), "onset unchanged")
        assertEquals(
            secs(SleepStaging.summary(off).awake), secs(SleepStaging.summary(on).awake), 0.5,
            "awake unchanged — the widen does not double-count an already-detected lead-in",
        )
        val b = inBedStart(off)
        val o = onset(off)
        if (b != null && o != null) {
            assertTrue(b.isBefore(o), "sanity: the existing HR gate already put in-bed before onset")
        }
    }

    @Test
    fun testMultiFragmentInBedSegmentsNeverOverlap() {
        // Two real sleep blocks split by a gap: the widen targets only the onset-containing envelope.
        val recs = mutableListOf<BulkRecord>()
        var c = 100_000L
        repeat(30) { recs += srec(c, hr = 52); c += step } // block 1 (75 min)
        c += step * 12 // gap
        repeat(120) { recs += srec(c, hr = 52); c += step } // block 2 (5 h)
        val on = SleepStaging.classify(recs, tuning = tuning(reach = 40))
        val beds = on.filter { it.stage == SleepStage.IN_BED }.sortedBy { it.start }
        for ((a, b) in beds.zipWithNext()) {
            assertTrue(!a.end.isAfter(b.start), "in-bed envelopes must not overlap after the widen")
        }
    }

    @Test
    fun testUnbridgeableAwakeGapIsNotCrossed() {
        // A gap with ELEVATED HR on the near side (awake, not asleep) must NOT be bridged.
        val recs = mutableListOf<BulkRecord>()
        var c = 100_000L
        repeat(12) { recs += mrec(c, hr = 72); c += step } // lead-in
        repeat(2) { recs += mrec(c, hr = 75); c += step } // still AWAKE right before the gap
        c += step * 12 // gap
        repeat(120) { recs += srec(c, hr = 52); c += step } // sleep block
        val on = SleepStaging.classify(recs, tuning = tuning(reach = 48))
        assertEquals(onset(on), inBedStart(on), "an awake-bordered gap is not bridged (no widen)")
    }

    @Test
    fun testShortAwakeBorderedDropoutStillWidens() {
        // A measured elevated-HR lead-in ended about 10 minutes before the flat sleep block: a routine
        // dropout, not enough ambiguity to throw away the whole measured bedtime lead-in.
        val recs = mutableListOf<BulkRecord>()
        var c = 100_000L
        repeat(12) { recs += mrec(c, hr = 78); c += step }
        c += step * 3 // 10 min separation
        repeat(120) { recs += srec(c, hr = 52); c += step }

        val on = SleepStaging.classify(recs, tuning = tuning(reach = 48))
        val bed = inBedStart(on)
        val sleep = onset(on)
        if (bed == null || sleep == null) fail("no segments")
        assertTrue(bed.isBefore(sleep), "a short dropout must not erase measured awake-in-bed lead-in")
        assertTrue(SleepStaging.summary(on).awake > Duration.ZERO)
        assertTrue(SleepStaging.summary(on).efficiency < 1.0)
    }
}
