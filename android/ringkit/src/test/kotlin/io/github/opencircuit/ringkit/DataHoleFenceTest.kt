package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Port of upstream DataHoleFenceTests.swift (@ b1c2fdd) — the leading edge must not be carried
// across an UNOBSERVED data hole. Two coupled behaviours, both gated by the single
// `MOTION_GAP_SUB_SAMPLE_CORRECTION` switch:
//   1. the detector's gap-break budget is measured in SAMPLE space, but `BulkSleep.motionTimeline`
//      expands each 150 s epoch into five samples at `start + k*30 s`, so the distance between the
//      last sample of one epoch and the first of the next is `hole - 120 s`;
//   2. the merge pass may not hand a short run's boundary to a run on the far side of a hole.
// Grounded upstream on a real charge-case hole of 1281 s between records.
//
// Fixture discipline (the flat-motion trap): a CONSTANT motion value de-floors to STILL, so the
// awake stretches here are a HIGH value punctuated by periodic dips, and every fixture asserts its
// own premise before the behaviour under test.

class DataHoleFenceTest {

    private val epoch: Long = 150
    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)

    private class Epoch(val start: Instant, val values: List<Float>)

    /** A motion timeline built exactly as `BulkSleep.motionTimeline` builds one: five samples per 150 s epoch at `start + k*30 s`. */
    private fun timeline(epochs: List<Epoch>): List<MotionSample> =
        epochs.flatMap { e -> (0 until 5).map { k -> MotionSample(e.start.plusSeconds(k * 30L), e.values[k]) } }

    /** A moving epoch: high, with one dip, so the rolling p10 floor stays low and the de-floored magnitude is genuinely large. */
    private fun moving(i: Int): List<Float> = if (i % 3 == 0) listOf(1f, 60f, 55f, 70f, 65f) else listOf(58f, 62f, 54f, 71f, 66f)

    /** A still epoch: the Gen-2 idle floor. */
    private val still: List<Float> = listOf(1f, 1f, 1f, 1f, 1f)

    private class ChargeNight(val timeline: List<MotionSample>, val stubStart: Instant, val postHoleStart: Instant)

    /** Active head → short still stub → HOLE → long still night. [holeSeconds] is record start to record start across the hole. */
    private fun chargeShapedNight(holeSeconds: Long, stubEpochs: Int = 4, headEpochs: Int = 12, tailEpochs: Int = 200): ChargeNight {
        val epochs = mutableListOf<Epoch>()
        var t = t0
        for (i in 0 until headEpochs) { epochs += Epoch(t, moving(i)); t = t.plusSeconds(epoch) }
        val stubStart = t
        repeat(stubEpochs) { epochs += Epoch(t, still); t = t.plusSeconds(epoch) }
        // `t` points one epoch past the last stub record; step back to it, then forward by the hole.
        val postHoleStart = t.minusSeconds(epoch).plusSeconds(holeSeconds)
        t = postHoleStart
        repeat(tailEpochs) { epochs += Epoch(t, still); t = t.plusSeconds(epoch) }
        return ChargeNight(timeline(epochs), stubStart, postHoleStart)
    }

    // The switch and its arithmetic

    @Test
    fun testCorrectionEqualsTheMotionTimelineSubSampleSpan() {
        // `motionTimeline` emits k = 0…4 at 30 s, so the last sub-sample sits 4 × 30 = 120 s past
        // the epoch start. The correction MUST equal that span or the budget is arbitrary.
        assertEquals(Duration.ofSeconds(4 * 30), ActivityPeriod.MOTION_GAP_SUB_SAMPLE_CORRECTION)
    }

    @Test
    fun testTheMotionTimelineReallyPutsItsLastSampleOneCorrectionPastTheEpochStart() {
        // Pins the premise the correction is derived from, against the real builder.
        val recs = TestRecordBuilder.records(count = 2, start = t0, motion = listOf(1f, 1f, 1f, 1f, 1f))
        val tl = BulkSleep.motionTimeline(recs)
        val firstEpochSamples = tl.filter { it.time.isBefore(t0.plusSeconds(epoch)) }
        assertEquals(5, firstEpochSamples.size)
        assertEquals(ActivityPeriod.MOTION_GAP_SUB_SAMPLE_CORRECTION, Duration.between(t0, firstEpochSamples.last().time))
    }

    // 1. the gap-break budget

    @Test
    fun testAHoleJustOverTheRecordBudgetBreaksTheRun() {
        // 1281 s — the measured charge hole. In sample space that is 1161 s, UNDER the raw 1200 s
        // budget: only the correction makes the detector break here.
        val n = chargeShapedNight(1281)
        val periods = ActivityPeriod.detectFromMotion(n.timeline)
        assertFalse(
            periods.any { it.start.isBefore(n.stubStart.plusSeconds(epoch)) && it.end.isAfter(n.postHoleStart) },
            "no period may span the hole",
        )
    }

    @Test
    fun testAHoleUnderTheRecordBudgetStillBridges() {
        // 1000 s is a real hole but under the budget at BOTH ends of the arithmetic — guards against
        // the correction over-breaking ordinary drain jitter.
        val n = chargeShapedNight(1000)
        val block = ActivityPeriod.mainSleepBlock(ActivityPeriod.detectFromMotion(n.timeline))
        assertNotNull(block)
        assertTrue(block.start.isBefore(n.postHoleStart), "a sub-budget hole must still bridge, exactly as before the fence")
    }

    @Test
    fun testTheBudgetBoundaryIsTheRecordGapNotTheSampleGap() {
        // 1201 s of records = 1081 s of samples: over the corrected budget (1080), under the raw one.
        val over = chargeShapedNight(1201)
        assertEquals(over.postHoleStart, ActivityPeriod.mainSleepBlock(ActivityPeriod.detectFromMotion(over.timeline))?.start)
        // 1200 s of records = 1080 s of samples: NOT over the corrected budget (strict `>`).
        val under = chargeShapedNight(1200)
        val underStart = ActivityPeriod.mainSleepBlock(ActivityPeriod.detectFromMotion(under.timeline))?.start
        assertNotNull(underStart)
        assertTrue(underStart.isBefore(under.postHoleStart))
    }

    // 2. the merge fence

    @Test
    fun testShortStillStubDoesNotDonateItsStartAcrossTheHole() {
        // The stub is 4 epochs = 10 min, under the 15 min activity-change threshold, so the merge
        // pass wants to hand its START to the run after it — on the far side of the charge hole.
        val n = chargeShapedNight(1281)
        val block = ActivityPeriod.mainSleepBlock(ActivityPeriod.detectFromMotion(n.timeline))
        assertNotNull(block, "the long still tail must still be detected as the night")
        assertEquals(n.postHoleStart, block.start, "the night must open on the first OBSERVED epoch after the hole, not on the pre-hole stub")
        assertTrue(block.start.isAfter(n.stubStart))
    }

    @Test
    fun testTheStubIsFoldedIntoThePrecedingRunRatherThanDropped() {
        // The fence must not silently delete measured time: the stub joins the run BEFORE the hole.
        val n = chargeShapedNight(1281)
        val periods = ActivityPeriod.detectFromMotion(n.timeline)
        val covering = periods.firstOrNull { !it.start.isAfter(n.stubStart) && !it.end.isBefore(n.stubStart) }
        assertNotNull(covering, "the stub's time must still be covered by some period")
        assertTrue(covering.start.isBefore(n.stubStart), "…by the run that precedes the hole")
    }

    @Test
    fun testAHoleFreeTimelineIsUnaffectedByTheFence() {
        // With no hole the merge pass runs its original branch order: a 10-min still stub inside a
        // moving stretch still merges.
        val epochs = mutableListOf<Epoch>()
        var t = t0
        for (i in 0 until 12) { epochs += Epoch(t, moving(i)); t = t.plusSeconds(epoch) }
        repeat(4) { epochs += Epoch(t, still); t = t.plusSeconds(epoch) }
        for (i in 0 until 12) { epochs += Epoch(t, moving(i)); t = t.plusSeconds(epoch) }
        val periods = ActivityPeriod.detectFromMotion(timeline(epochs))
        assertFalse(periods.any { it.activity == Activity.SLEEP }, "a sub-threshold still stub between two moving runs is absorbed, not promoted")
    }

    // The fence is symmetric: a stub AFTER the hole may not reach back either

    @Test
    fun testTailStubAfterAHoleDoesNotExtendTheNightBackAcrossIt() {
        val epochs = mutableListOf<Epoch>()
        var t = t0
        repeat(200) { epochs += Epoch(t, still); t = t.plusSeconds(epoch) }
        val lastPreHole = t.minusSeconds(epoch)
        t = lastPreHole.plusSeconds(1281)
        val postHoleStart = t
        repeat(4) { epochs += Epoch(t, still); t = t.plusSeconds(epoch) }

        val block = ActivityPeriod.mainSleepBlock(ActivityPeriod.detectFromMotion(timeline(epochs)))
        assertNotNull(block)
        assertTrue(block.end.isBefore(postHoleStart), "the night must end on the last OBSERVED epoch before the hole")
    }

    @Test
    fun testShortStubFencedByAHoleIsNotUsedToJoinTwoMovingRuns() {
        // The merge pass swallows a short run when its NEIGHBOURS agree — here both are active, so
        // without the fence the two moving runs would be welded into one period spanning the hole.
        val epochs = mutableListOf<Epoch>()
        var t = t0
        for (i in 0 until 12) { epochs += Epoch(t, moving(i)); t = t.plusSeconds(epoch) }
        val lastPreHole = t.minusSeconds(epoch)
        t = lastPreHole.plusSeconds(1281)
        val postHoleStart = t
        repeat(4) { epochs += Epoch(t, still); t = t.plusSeconds(epoch) }
        for (i in 0 until 12) { epochs += Epoch(t, moving(i)); t = t.plusSeconds(epoch) }

        val periods = ActivityPeriod.detectFromMotion(timeline(epochs))
        assertFalse(
            periods.any { !it.start.isAfter(lastPreHole) && !it.end.isBefore(postHoleStart) },
            "no period may be welded across the hole — got ${periods.map { "${it.activity} ${Duration.between(t0, it.start).seconds}→${Duration.between(t0, it.end).seconds}" }} " +
                "hole ${Duration.between(t0, lastPreHole).seconds}→${Duration.between(t0, postHoleStart).seconds}",
        )
    }

    // The whole pipeline, through BulkSleep

    @Test
    fun testMainSleepDoesNotOpenBeforeAChargeShapedHole() {
        val recs = mutableListOf<BulkRecord>()
        var t = t0
        for (i in 0 until 12) {
            recs += TestRecordBuilder.records(count = 1, start = t, motion = moving(i), heartRate = 70)
            t = t.plusSeconds(epoch)
        }
        val stubStart = t
        repeat(4) {
            recs += TestRecordBuilder.records(count = 1, start = t, motion = still, heartRate = 62)
            t = t.plusSeconds(epoch)
        }
        val postHoleStart = t.minusSeconds(epoch).plusSeconds(1281)
        t = postHoleStart
        repeat(200) {
            recs += TestRecordBuilder.records(count = 1, start = t, motion = still, heartRate = 50)
            t = t.plusSeconds(epoch)
        }
        val block = BulkSleep.mainSleep(recs)
        assertNotNull(block)
        assertEquals(postHoleStart, block.start)
        assertTrue(block.start.isAfter(stubStart))
    }
}

/**
 * Minimal 23-byte `0x4c` record builder for these fixtures (upstream's `TestRecordBuilder`):
 * `[0:4]` epoch counter (big-endian), `[4]` HR, `[8]` the `0x12` "no SpO2 here" activity tag,
 * `[10:15]` motion. Values are clamped into a byte, as Swift's `UInt8(clamping:)` does.
 */
private object TestRecordBuilder {
    fun records(count: Int, start: Instant, motion: List<Float>, heartRate: Int = 60): List<BulkRecord> =
        (0 until count).mapNotNull { i ->
            val counter = start.plusSeconds(i * 150L).epochSecond - Command.SYNC_EPOCH
            val b = ByteArray(23)
            b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
            b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
            b[4] = heartRate.coerceIn(0, 255).toByte()
            b[8] = 0x12 // "no SpO2 here" sentinel → activity layout
            for (k in 0 until 5) b[10 + k] = motion[k].toInt().coerceIn(0, 255).toByte()
            BulkRecord.of(b)
        }
}
