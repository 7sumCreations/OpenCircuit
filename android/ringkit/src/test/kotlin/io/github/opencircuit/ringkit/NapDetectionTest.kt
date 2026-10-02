package io.github.opencircuit.ringkit

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Automatic nap detection: a day of `0x4c` motion epochs (active / still / active) anchored at a
 * chosen wall-clock hour; a daytime stillness block of at least 15 min is a nap, while an overnight
 * block, an overlap with the main night, a sub-15-min block and an activity-tagged still block are
 * all rejected. Synthetic records only.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/NapDetectionTests.swift
 * (@ b1c2fdd) — all 6 tests. Upstream anchors at "today" on the device calendar and judges
 * "overnight" on the same calendar; here the day is fixed (2026-06-15, no DST change) and the zone
 * is named (America/New_York) and passed to `naps`, so the anchor hour means the same local time
 * on both sides.
 */
class NapDetectionTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val day: LocalDate = LocalDate.of(2026, 6, 15)
    private val step = 150L

    // :14-21 — uniform motion byte (1 = still; higher = movement); `tag` is byte [8].
    private fun rec(counter: Long, motion: Int, tag: Int = 0): BulkRecord {
        val b = ByteArray(23)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        for (k in 0 until 5) b[10 + k] = motion.toByte()
        b[8] = tag.toByte()
        return assertNotNull(BulkRecord.of(b))
    }

    /** :26-36 — active → still → active, with `epoch` chosen so counter 0 lands at [anchorHour] local. */
    private fun dayRecords(anchorHour: Int, activeEpochs: Int, stillEpochs: Int, stillTag: Int = 0): Pair<List<BulkRecord>, Long> {
        val anchor = day.atStartOfDay(zone).plusHours(anchorHour.toLong()).toInstant()
        val recs = mutableListOf<BulkRecord>()
        var c = 0L
        repeat(activeEpochs) { recs += rec(c, motion = 20); c += step }
        repeat(stillEpochs) { recs += rec(c, motion = 1, tag = stillTag); c += step }
        repeat(activeEpochs) { recs += rec(c, motion = 20); c += step }
        return recs to anchor.epochSecond
    }

    @Test
    fun detectsDaytimeNap() { // :38-48
        // 14:00 anchor; 20-min active, 60-min still nap, 20-min active.
        val (recs, epoch) = dayRecords(anchorHour = 14, activeEpochs = 8, stillEpochs = 24)
        val naps = NapDetection.naps(recs, mainSleep = null, zone = zone, epoch = epoch)
        assertEquals(1, naps.size, "one daytime nap")
        val nap = naps[0]
        assertTrue(nap.duration >= NapDetection.MIN_NAP_DURATION)
        assertTrue(nap.asleep > java.time.Duration.ZERO)
        assertFalse(nap.isLongNap, "60-min nap is not a long nap")
        assertFalse(nap.segments.isEmpty())
    }

    @Test
    fun rejectsAwakeSedentaryStillBlock() { // :50-57
        // A 60-min daytime still block the ring tagged ACTIVITY (0x12) — sedentary-but-awake.
        val (recs, epoch) = dayRecords(anchorHour = 14, activeEpochs = 8, stillEpochs = 24, stillTag = 0x12)
        val naps = NapDetection.naps(recs, mainSleep = null, zone = zone, epoch = epoch)
        assertTrue(naps.isEmpty(), "awake sedentary stillness (activity-tagged) is not a nap")
    }

    @Test
    fun detectsRingMeasuredSleepNap() { // :59-64
        // The same block, but ring-measured as sleep (sleep-vitals layout, [8] = SpO2 0x62) → a real nap.
        val (recs, epoch) = dayRecords(anchorHour = 14, activeEpochs = 8, stillEpochs = 24, stillTag = 0x62)
        val naps = NapDetection.naps(recs, mainSleep = null, zone = zone, epoch = epoch)
        assertEquals(1, naps.size, "ring-measured daytime sleep is a nap")
    }

    @Test
    fun rejectsShortStillBlock() { // :66-71
        // 10-min still block (< 15 min) → not a nap.
        val (recs, epoch) = dayRecords(anchorHour = 14, activeEpochs = 8, stillEpochs = 4)
        assertTrue(NapDetection.naps(recs, mainSleep = null, zone = zone, epoch = epoch).isEmpty())
    }

    @Test
    fun rejectsOvernightBlock() { // :73-78
        // Same shape but at 02:00 — an overnight midpoint is night sleep, not a nap.
        val (recs, epoch) = dayRecords(anchorHour = 2, activeEpochs = 8, stillEpochs = 24)
        val naps = NapDetection.naps(recs, mainSleep = null, zone = zone, epoch = epoch)
        assertTrue(naps.isEmpty(), "overnight stillness is excluded from naps")
    }

    @Test
    fun excludesOverlapWithMainSleep() { // :80-88
        val (recs, epoch) = dayRecords(anchorHour = 14, activeEpochs = 8, stillEpochs = 24)
        // A main-sleep block spanning the whole captured day window → the nap is suppressed.
        val main = ActivityPeriod(Activity.SLEEP, recs.first().date(epoch), recs.last().date(epoch))
        val naps = NapDetection.naps(recs, mainSleep = main, zone = zone, epoch = epoch)
        assertTrue(naps.isEmpty(), "a block overlapping the main night is not double-counted as a nap")
    }
}
