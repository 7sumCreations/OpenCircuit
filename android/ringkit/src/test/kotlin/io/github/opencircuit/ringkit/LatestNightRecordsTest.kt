package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of upstream LatestNightRecordsTests.swift (@ b1c2fdd): staging a multi-night archive union
 * must describe LAST night, not the earliest block. Upstream reads the device calendar; here every
 * call passes an explicit zone (the fixtures are built on the same zone's wall clock).
 */
class LatestNightRecordsTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    /** A still (motion baseline `1`) worn epoch at [date]. */
    private fun stillRecord(date: Instant): BulkRecord {
        val counter = date.epochSecond - Command.SYNC_EPOCH
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        for (k in 10 until 15) b[k] = 1 // [10:15] still baseline
        return BulkRecord.of(b)!!
    }

    /** ~3 h of still epochs at 150 s spacing from [start]. */
    private fun night(start: Instant, epochs: Int = 72): List<BulkRecord> = (0 until epochs).map { stillRecord(start.plusSeconds(it * 150L)) }

    /** 02:00 local on the day containing [base], then +[dayOffset] days. */
    private fun twoAM(base: Instant, dayOffset: Int): Instant =
        base.atZone(zone).toLocalDate().atStartOfDay(zone).plusHours(2).plusDays(dayOffset.toLong()).toInstant()

    private fun latest(records: List<BulkRecord>) = BulkSleep.latestNightRecords(records, zone = zone)

    @Test
    fun testTwoNightUnionScopesToLatestNight() {
        val base = Instant.ofEpochSecond(1_780_000_000) // ~2026, comfortably after the sync epoch
        val n1Start = twoAM(base, 0)
        val n2Start = twoAM(base, 1)
        // Union with the prior night LAST in the array — proves selection isn't array-order based.
        val union = night(n2Start) + night(n1Start)

        val scoped = latest(union)

        assertFalse(scoped.isEmpty())
        // Everything kept is from night 2 (no prior-night records leak through the 30-min margin).
        val cutoff = n2Start.minusSeconds(31 * 60)
        assertTrue(scoped.all { !it.date().isBefore(cutoff) }, "prior night's records must be excluded")
        // And night 2 is fully retained (its ~72 epochs, give or take the margin).
        assertTrue(scoped.size >= 70)
    }

    @Test
    fun testSingleNightReturnedWhole() {
        val base = Instant.ofEpochSecond(1_780_000_000)
        val only = night(twoAM(base, 0))
        assertEquals(only.size, latest(only).size)
    }

    /**
     * A night handed off in TWO fragments (a data gap from a dropped buffer / missed drain) must be
     * returned WHOLE — both fragments — not clipped to the latest block.
     */
    @Test
    fun testFragmentedNightReturnsBothFragments() {
        val base = Instant.ofEpochSecond(1_780_000_000)
        val anchor = twoAM(base, 0) // 02:00 local
        val p1Start = anchor.minusSeconds(3 * 3600) // ~23:00 prev — 3 h fragment
        val p2Start = anchor.plusSeconds(40 * 60) // 02:40 — 3 h fragment, ~43 min gap
        val p1 = night(p1Start)
        val p2 = night(p2Start)
        // Prior night, 1 day earlier, must NOT be absorbed into the cluster.
        val prior = night(twoAM(base, -1))
        val union = p2 + prior + p1 // shuffled order

        val scoped = latest(union)

        // Both of last night's fragments are retained …
        assertEquals(p1.size + p2.size, scoped.size, "both fragments of last night are kept (stitched), prior night excluded")
        // … and none of the prior night leaks in (cluster gap > MAX_INTRA_NIGHT_GAP).
        val priorCutoff = p1Start.minusSeconds(31 * 60)
        assertTrue(scoped.all { !it.date().isBefore(priorCutoff) }, "prior night excluded")
        // The earliest kept record is from fragment 1, not fragment 2.
        assertTrue(scoped.minOf { it.date() }.isBefore(p2Start), "fragment 1 is retained, not clipped")
    }

    @Test
    fun testNoOvernightBlockReturnsInputUnchanged() {
        // A still block at ~14:00 local (daytime nap) — not overnight → input returned unchanged so
        // the caller stages exactly as before.
        val base = Instant.ofEpochSecond(1_780_000_000)
        val twoPM = base.atZone(zone).toLocalDate().atStartOfDay(zone).plusHours(14).toInstant()
        val nap = night(twoPM)
        assertEquals(nap.size, latest(nap).size)
    }

    /**
     * The invariant the 2026-08-29 wake-edge bug violated, pinned as a property: the night-scoping
     * pre-filter must never be stricter about "same night" than `ActivityPeriod.mainSleepBlock`, the
     * function it feeds, or a pause the app itself calls "same night" deletes every record after it.
     */
    @Test
    fun testForwardAbsorbIsNeverStricterThanTheBridgeItFeeds() {
        assertTrue(
            BulkSleep.MORNING_CONTINUATION_MAX_GAP >= ActivityPeriod.MAX_SLEEP_PAUSE,
            "The night-scoping pre-filter would drop records that `mainSleepBlock` would have bridged " +
                "into the same night. That is the 2026-08-29 wake-edge truncation: a pause the app itself " +
                "calls \"same night\" deletes every record after it.",
        )
    }
}
