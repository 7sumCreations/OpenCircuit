package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin-only: the ring's step bucket is a LOCAL wall-clock quarter-hour, so [StepAccumulator]'s
 * bucket and window arithmetic must hold in every UTC offset that is not a whole hour — India
 * (+05:30), Nepal (+05:45), Newfoundland (-03:30 in winter), central Australia (+09:30). Upstream
 * tests one of them (Kolkata); these cover the rest, plus a daylight-saving fall-back where the same
 * local quarter occurs twice.
 *
 * Every expected value is a UTC instant typed by hand from the offset arithmetic, not computed
 * through `java.time`, so a zone-handling mistake in the code cannot be mirrored in the test.
 */
class StepAccumulatorOffsetTest {

    private fun utc(s: String): Instant = Instant.parse(s)

    private val plus0530 = ZoneOffset.ofHoursMinutes(5, 30)
    private val plus0545 = ZoneOffset.ofHoursMinutes(5, 45)
    private val minus0330 = ZoneOffset.ofHoursMinutes(-3, -30)
    private val plus0930 = ZoneOffset.ofHoursMinutes(9, 30)

    /** (zone, sample, expected bucket start) — local 17:22:03 → 17:15, 17:44:59 → 17:30, 17:45:00 → itself. */
    private val bucketCases: List<Triple<ZoneId, String, String>> = listOf(
        Triple(plus0530, "2026-08-08T11:52:03Z", "2026-08-08T11:45:00Z"),
        Triple(plus0530, "2026-08-08T12:14:59Z", "2026-08-08T12:00:00Z"),
        Triple(plus0530, "2026-08-08T12:15:00Z", "2026-08-08T12:15:00Z"),
        Triple(plus0545, "2026-08-08T11:37:03Z", "2026-08-08T11:30:00Z"),
        Triple(plus0545, "2026-08-08T11:59:59Z", "2026-08-08T11:45:00Z"),
        Triple(plus0545, "2026-08-08T12:00:00Z", "2026-08-08T12:00:00Z"),
        Triple(minus0330, "2026-08-08T20:52:03Z", "2026-08-08T20:45:00Z"),
        Triple(minus0330, "2026-08-08T21:14:59Z", "2026-08-08T21:00:00Z"),
        Triple(minus0330, "2026-08-08T21:15:00Z", "2026-08-08T21:15:00Z"),
        Triple(plus0930, "2026-08-08T07:52:03Z", "2026-08-08T07:45:00Z"),
        Triple(plus0930, "2026-08-08T08:14:59Z", "2026-08-08T08:00:00Z"),
        Triple(plus0930, "2026-08-08T08:15:00Z", "2026-08-08T08:15:00Z"),
    )

    @Test
    fun bucketStartFloorsToTheLocalQuarterInEveryNonWholeHourOffset() {
        for ((zone, sample, expected) in bucketCases) {
            assertEquals(utc(expected), StepAccumulator.bucketStart(utc(sample), zone), "$zone $sample")
        }
    }

    @Test
    fun namedZonesAgreeWithTheirFixedOffsets() {
        // Kathmandu and Darwin keep no daylight saving, so they must bucket exactly as their offsets.
        assertEquals(utc("2026-08-08T11:30:00Z"), StepAccumulator.bucketStart(utc("2026-08-08T11:37:03Z"), ZoneId.of("Asia/Kathmandu")))
        assertEquals(utc("2026-08-08T07:45:00Z"), StepAccumulator.bucketStart(utc("2026-08-08T07:52:03Z"), ZoneId.of("Australia/Darwin")))
    }

    @Test
    fun aRepeatedLocalQuarterAtTheFallBackBucketsEachOccurrenceOnItsOwnInstant() {
        // St. John's leaves daylight time (-02:30) for standard time (-03:30) on 2026-11-01, so local
        // 01:00–02:00 happens twice. Each 01:22:03 must floor to ITS OWN 01:15, one hour apart.
        val stJohns = ZoneId.of("America/St_Johns")
        val first = utc("2026-11-01T03:52:03Z")  // 01:22:03 at -02:30
        val second = utc("2026-11-01T04:52:03Z") // 01:22:03 at -03:30
        assertEquals(ZoneOffset.ofHoursMinutes(-2, -30), stJohns.rules.getOffset(first), "fixture: first pass is daylight time")
        assertEquals(ZoneOffset.ofHoursMinutes(-3, -30), stJohns.rules.getOffset(second), "fixture: second pass is standard time")
        assertEquals(utc("2026-11-01T03:45:00Z"), StepAccumulator.bucketStart(first, stJohns))
        assertEquals(utc("2026-11-01T04:45:00Z"), StepAccumulator.bucketStart(second, stJohns))
    }

    @Test
    fun windowStartNeverCrossesLocalMidnightInAnyOffset() {
        // Sample at local 00:01:00 on 2026-08-08; the day starts at local midnight, not UTC midnight.
        val cases = listOf(
            Triple(plus0530, "2026-08-07T18:31:00Z", "2026-08-07T18:30:00Z"),
            Triple(plus0545, "2026-08-07T18:16:00Z", "2026-08-07T18:15:00Z"),
            Triple(minus0330, "2026-08-08T03:31:00Z", "2026-08-08T03:30:00Z"),
            Triple(plus0930, "2026-08-07T14:31:00Z", "2026-08-07T14:30:00Z"),
        )
        for ((zone, sample, dayStart) in cases) {
            assertEquals(
                utc(dayStart),
                StepAccumulator.windowStart(sampleDate = utc(sample), previousSampleAt = null, dayStart = utc(dayStart), zone = zone),
                "$zone",
            )
        }
    }

    @Test
    fun windowStartReachesBackOneLocalBucketInsideTheClearLagInAnyOffset() {
        // Sample at local 17:16:30: the 120 s lag lands at 17:14:30, so the window opens at local 17:00.
        val cases = listOf(
            Triple(plus0530, "2026-08-08T11:46:30Z", "2026-08-08T11:30:00Z"),
            Triple(plus0545, "2026-08-08T11:31:30Z", "2026-08-08T11:15:00Z"),
            Triple(minus0330, "2026-08-08T20:46:30Z", "2026-08-08T20:30:00Z"),
            Triple(plus0930, "2026-08-08T07:46:30Z", "2026-08-08T07:30:00Z"),
        )
        for ((zone, sample, expected) in cases) {
            val dayStart = utc(sample).minusSeconds(12 * 3600) // well before; the day clamp is not under test here
            assertEquals(
                utc(expected),
                StepAccumulator.windowStart(sampleDate = utc(sample), previousSampleAt = null, dayStart = dayStart, zone = zone),
                "$zone",
            )
        }
    }
}
