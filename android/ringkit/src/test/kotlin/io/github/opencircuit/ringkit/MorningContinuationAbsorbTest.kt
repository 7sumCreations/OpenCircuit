package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of upstream MorningContinuationAbsorbTests.swift (@ b1c2fdd) — the morning-continuation
 * absorb in `BulkSleep.latestNightRecords`. Device case upstream (Gen 2 Air): asleep 03:44→10:30
 * with a ~1-min detector split at 09:04; the later piece's midpoint fails the overnight test, so it
 * drained away as an 86-min "nap" and the night lost its real wake. The fix chains FORWARD over
 * later sleep blocks within `MORNING_CONTINUATION_MAX_GAP`, under strict rules (small gap, overnight
 * envelope preserved, `MAX_NIGHT_SPAN` respected, anchor untouched). Fixtures are built on an
 * explicit zone's wall clock, the zone the selector is given.
 */
class MorningContinuationAbsorbTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val base: Instant = Instant.ofEpochSecond(1_780_000_000) // ~2026, after the sync epoch

    private fun record(date: Instant, still: Boolean, seed: Int = 0): BulkRecord {
        val counter = date.epochSecond - Command.SYNC_EPOCH
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        if (still) {
            for (k in 10 until 15) b[k] = 1 // still baseline
        } else {
            // Elevated AND varied within/between epochs — a constant plateau would de-floor to
            // still (the flat-motion fixture trap) and never split the block.
            val jitter = seed % 5
            b[10] = (8 + jitter).toByte(); b[11] = 12; b[12] = (20 + jitter).toByte(); b[13] = 6; b[14] = 10
        }
        return BulkRecord.of(b)!!
    }

    /** Still epochs at 150 s spacing covering [start, end). */
    private fun still(start: Instant, end: Instant): List<BulkRecord> =
        (0 until Duration.between(start, end).seconds step 150).map { record(start.plusSeconds(it), still = true) }

    private fun moving(start: Instant, end: Instant): List<BulkRecord> =
        (0 until Duration.between(start, end).seconds step 150).mapIndexed { i, s -> record(start.plusSeconds(s), still = false, seed = i) }

    /** Local `hour:min` on the day containing [base] (+ [dayOffset]). */
    private fun at(hour: Int, min: Int = 0, dayOffset: Int = 0): Instant =
        base.atZone(zone).toLocalDate().atStartOfDay(zone).plusDays(dayOffset.toLong()).toInstant().plusSeconds((hour * 60L + min) * 60)

    private fun maxKeptDate(scoped: List<BulkRecord>): Instant? = scoped.maxOfOrNull { it.date() }

    private fun latest(records: List<BulkRecord>, gap: Duration = BulkSleep.MORNING_CONTINUATION_MAX_GAP) =
        BulkSleep.latestNightRecords(records, zone = zone, morningContinuationGap = gap)

    /** THE DEVICE CASE, shaped: night 02:00–08:30, a 15-min arousal, then a continuation 08:45–10:30. */
    @Test
    fun testBriefArousalContinuationIsAbsorbed() {
        val union = still(at(2, 0), at(8, 30)) + moving(at(8, 30), at(8, 45)) + still(at(8, 45), at(10, 30))

        val scoped = latest(union)

        val last = assertNotNull(maxKeptDate(scoped))
        assertFalse(last.isBefore(at(10, 25)), "the 08:45–10:30 continuation is part of the night")
        // The head of the night survives absorption untouched.
        assertFalse(scoped.minOf { it.date() }.isAfter(at(2, 0)))
    }

    /** Kill switch: a zero gap is byte-identical to the pre-fix scoping. */
    @Test
    fun testZeroGapIsByteIdenticalToPreFixScoping() {
        val union = still(at(2, 0), at(8, 30)) + moving(at(8, 30), at(8, 45)) + still(at(8, 45), at(10, 30))

        val off = latest(union, gap = Duration.ZERO)

        // Pre-fix behaviour: the night ends at the arousal; only the 30-min margin follows it.
        assertFalse(maxKeptDate(off)!!.isAfter(at(9, 1)), "with the absorb disabled the continuation stays out")
        assertEquals(off.map { it.counter }.sorted(), off.map { it.counter }, "scoped set stays ordered")
    }

    /** A genuine late-morning nap keeps its hours-wide gap and stays OUT of the night. */
    @Test
    fun testRealNapHoursLaterIsNotAbsorbed() {
        val union = still(at(2, 0), at(8, 30)) + moving(at(8, 30), at(8, 45)) + still(at(11, 45), at(13, 0)) // 3-h gap → a nap

        assertFalse(maxKeptDate(latest(union))!!.isAfter(at(9, 1)), "a 3-h gap must not be bridged — that block is a nap")
    }

    /**
     * Absorption must never push the whole-night envelope past the overnight gate: a late-onset
     * night whose extension would move the envelope midpoint past 09:00 keeps its tail split off.
     */
    @Test
    fun testAbsorbStopsBeforeBreakingTheOvernightEnvelope() {
        val union = still(at(5, 0), at(8, 50)) + moving(at(8, 50), at(9, 0)) + still(at(9, 0), at(13, 50)) // extension → midpoint 09:25

        val scoped = latest(union)

        assertFalse(scoped.isEmpty(), "the 05:00–08:50 night itself must survive")
        assertFalse(maxKeptDate(scoped)!!.isAfter(at(9, 30)), "the tail is refused rather than sinking the whole night")
        // And the surviving envelope still describes an overnight block.
        assertFalse(scoped.minOf { it.date() }.isAfter(at(5, 0)))
    }

    /**
     * A continuation bout can be too short to START a night yet real: candidates use the nap floor
     * (15 min), not the 60-min minimum sleep duration. A sub-nap-floor blip stays out.
     */
    @Test
    fun testShortContinuationBoutAbsorbsButBlipDoesNot() {
        val bout = still(at(2, 0), at(8, 30)) + moving(at(8, 30), at(8, 45)) + still(at(8, 45), at(9, 10)) // 25 min — over the nap floor
        assertFalse(maxKeptDate(latest(bout))!!.isBefore(at(9, 5)), "a 25-min post-arousal bout is part of the night")

        val blip = still(at(2, 0), at(8, 30)) + moving(at(8, 30), at(8, 45)) + still(at(8, 45), at(8, 55)) // 10 min — under the nap floor
        assertFalse(maxKeptDate(latest(blip))!!.isAfter(at(9, 1)), "a sub-nap-floor blip must not extend the night")
    }

    /** Chaining: two continuation blocks separated by two brief arousals all fold into the night. */
    @Test
    fun testChainedContinuationsAllAbsorb() {
        val union = still(at(1, 0), at(7, 0)) +
            moving(at(7, 0), at(7, 10)) +
            still(at(7, 10), at(8, 30)) +
            moving(at(8, 30), at(8, 40)) +
            still(at(8, 40), at(10, 0))

        assertTrue(!maxKeptDate(latest(union))!!.isBefore(at(9, 55)), "both same-morning bouts belong to the night")
    }
}
