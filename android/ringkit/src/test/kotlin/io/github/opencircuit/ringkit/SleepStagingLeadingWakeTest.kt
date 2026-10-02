package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port of upstream SleepStagingLeadingWakeTests.swift (@ b1c2fdd), all 10 tests: sleep ONSET
 * manufactured by eroding the awake run that OPENS the block.
 *
 * Upstream grounded this on a real Gen 3 night whose motion channel was the `1,1,1,1,1` placeholder
 * across the head of the night: the HR gate correctly marked the first four in-block epochs awake,
 * `erodeShortHRWake` then wiped them (an HR-only run shorter than `minHRWakeRunEpochs`), and onset
 * landed on the very first epoch at an HR 26 bpm above the night's floor. The fix is a SCOPING
 * correction: erosion repairs a hole punched IN sleep, and the head run has no sleep before it.
 *
 * Fixture discipline (the flat-motion trap): a CONSTANT motion byte de-floors to STILL, so the head
 * here must be awake on HR ALONE; every `classify` case asserts its premise (the kill-switch run)
 * before asserting the fix. Upstream's `inout [Bool]` is a caller-owned `BooleanArray` here. Every
 * literal is typed from upstream.
 */
class SleepStagingLeadingWakeTest {

    private val step = 150L
    private val base = 0x0c220000L

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
    }

    /** A still sleep-vitals epoch carrying HR + HRV. */
    private fun vrec(counter: Long, hr: Int, hrv: Int = 55, motion: Int = 1): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    /** A moving activity epoch — the getting-up that closes the night. */
    private fun arec(counter: Long, motion: Int = 0x14): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[8] = 0x12
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        return BulkRecord.of(b)!!
    }

    private fun date(counter: Long): Instant = Instant.ofEpochSecond(counter + Command.SYNC_EPOCH)

    private class Night(val records: List<BulkRecord>, val blockStart: Instant, val firstSleep: Instant)

    /**
     * [headEpochs] still-but-ELEVATED epochs opening the block, then a long flat sleep at [sleepHR],
     * then a moving offset. Motion is uniformly still through head and sleep, so the head is awake on
     * HR alone.
     */
    private fun elevatedHeadNight(headEpochs: Int = 4, headHR: Int = 74, sleepHR: Int = 50, sleepEpochs: Int = 120): Night {
        val recs = mutableListOf<BulkRecord>()
        var c = base
        val blockStart = date(c)
        repeat(headEpochs) { recs += vrec(c, hr = headHR); c += step }
        val firstSleep = date(c)
        repeat(sleepEpochs) { recs += vrec(c, hr = sleepHR); c += step }
        repeat(8) { recs += arec(c); c += step }
        return Night(recs, blockStart, firstSleep)
    }

    private fun onset(segs: List<SleepSegment>): Instant? = SleepStaging.sleepWindow(segs)?.onset

    private fun mask(vararg head: Boolean, falses: Int): BooleanArray = (head.toList() + List(falses) { false }).toBooleanArray()

    // The pass itself

    /** The defect, stated at the level it happens: a short HR-only run at index 0 is erased. */
    @Test
    fun testUnguardedErosionErasesTheRunThatOpensTheBlock() {
        val awake = mask(true, true, true, true, falses = 40)
        SleepStaging.erodeShortHRWake(awake, List(44) { false }, minRun = 5, protectsLeading = false)
        assertFalse(awake.contains(true), "pre-#202 behaviour: the head run is eroded away")
    }

    @Test
    fun testHeadRunIsExemptWhenProtected() {
        val awake = mask(true, true, true, true, falses = 40)
        SleepStaging.erodeShortHRWake(awake, List(44) { false }, minRun = 5, protectsLeading = true)
        assertEquals(listOf(true, true, true, true), awake.take(4))
        assertFalse(awake.drop(4).contains(true))
    }

    /** The exemption is for the HEAD ONLY — the interior rule it was written for is untouched. */
    @Test
    fun testInteriorShortRunIsStillEroded() {
        val awake = BooleanArray(40)
        for (i in 20 until 23) awake[i] = true
        SleepStaging.erodeShortHRWake(awake, List(40) { false }, minRun = 5, protectsLeading = true)
        assertFalse(awake.contains(true), "a REM-ish bump inside sleep must still erode")
    }

    /** A run at index 0 that is already long enough is unaffected either way. */
    @Test
    fun testLongHeadRunIsUnchangedByTheExemption() {
        val start = (List(8) { true } + List(30) { false }).toBooleanArray()
        val on = start.copyOf()
        val off = start.copyOf()
        val motionless = List(38) { false }
        SleepStaging.erodeShortHRWake(on, motionless, minRun = 5, protectsLeading = true)
        SleepStaging.erodeShortHRWake(off, motionless, minRun = 5, protectsLeading = false)
        assertEquals(start.toList(), on.toList())
        assertEquals(start.toList(), off.toList())
    }

    /** A head run containing MOTION was already exempt, so the two settings must agree there too. */
    @Test
    fun testMotionBearingHeadRunAgreesOnBothSettings() {
        val start = mask(true, true, falses = 30)
        val motion = MutableList(32) { false }
        motion[1] = true
        val on = start.copyOf()
        val off = start.copyOf()
        SleepStaging.erodeShortHRWake(on, motion, minRun = 5, protectsLeading = true)
        SleepStaging.erodeShortHRWake(off, motion, minRun = 5, protectsLeading = false)
        assertEquals(start.toList(), on.toList())
        assertEquals(start.toList(), off.toList())
    }

    /** The exemption may only ADD leading awake — onset can move later, never earlier. */
    @Test
    fun testExemptionOnlyEverAddsAwake() {
        for (seed in 0 until 200) {
            val awake = BooleanArray(40) { (it * 7 + seed) % 5 < 2 }
            val motion = List(40) { (it * 11 + seed) % 9 == 0 }
            val off = awake.copyOf()
            SleepStaging.erodeShortHRWake(awake, motion, minRun = 5, protectsLeading = true)
            SleepStaging.erodeShortHRWake(off, motion, minRun = 5, protectsLeading = false)
            for (i in awake.indices) {
                if (off[i]) assertTrue(awake[i], "seed $seed index $i: protection turned an awake epoch asleep")
            }
        }
    }

    // Wiring (fails if the call site loses the flag)

    @Test
    fun testElevatedHeadNoLongerAnchorsOnset() {
        val night = elevatedHeadNight()
        val unguarded = SleepStaging.classify(night.records, tuning = SleepStaging.Tuning(protectsLeadingHRWake = false))
        // Premise: without the exemption the night really does open ON the elevated head.
        assertEquals(night.blockStart, onset(unguarded), "premise failed — the fixture is not reproducing the #202 shape")

        val guarded = SleepStaging.classify(night.records)
        assertEquals(night.firstSleep, onset(guarded), "onset must move off the elevated head to the first genuinely-settled epoch")
    }

    /** The in-bed envelope is set by the motion block: the night must not shrink, only be re-labelled. */
    @Test
    fun testInBedWindowIsUnchangedAndTheRecoveredHeadBecomesAwake() {
        val night = elevatedHeadNight()
        val off = SleepStaging.summary(
            SleepStaging.classify(night.records, tuning = SleepStaging.Tuning(protectsLeadingHRWake = false)),
        ).minutes
        val on = SleepStaging.summary(SleepStaging.classify(night.records)).minutes
        assertEquals(off.inBed, on.inBed, "time in bed is the motion block's, and must not move")
        assertTrue(on.awake > off.awake)
        assertEquals(on.awake - off.awake, off.asleep - on.asleep, "the head is re-labelled, not discarded")
    }

    /** A night that genuinely falls asleep at once must be byte-identical under both settings. */
    @Test
    fun testFastOnsetNightIsByteIdentical() {
        val recs = mutableListOf<BulkRecord>()
        var c = base
        repeat(120) { recs += vrec(c, hr = 50); c += step }
        repeat(8) { recs += arec(c); c += step }
        val off = SleepStaging.classify(recs, tuning = SleepStaging.Tuning(protectsLeadingHRWake = false))
        val on = SleepStaging.classify(recs)
        assertEquals(off, on)
    }

    /** The kill switch is real: `false` restores the pre-fix output exactly. */
    @Test
    fun testKillSwitchRestoresPreFixStaging() {
        val night = elevatedHeadNight()
        val off = SleepStaging.classify(night.records, tuning = SleepStaging.Tuning(protectsLeadingHRWake = false))
        assertEquals(night.blockStart, onset(off))
        assertNotEquals(onset(SleepStaging.classify(night.records)), onset(off))
    }
}
