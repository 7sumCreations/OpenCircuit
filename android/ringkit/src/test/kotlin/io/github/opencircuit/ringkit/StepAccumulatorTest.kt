package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step accumulation. The descriptor's `[4:6]` field is a QUARTER-HOUR BUCKET — steps since the last
 * :00/:15/:30/:45, cleared at each boundary (🟢 measured upstream over 10,327 descriptor frames from
 * two rings: 268 drops, every one at a wall-clock quarter boundary). These cases pin the fold that
 * turns that into a daily total, and pin the two "obvious" fixes upstream's corpus says are WRONG
 * (crediting the raw value in full at every boundary: +4.9 % over; the same with a lag margin: +8.2 %
 * over). Steps have no ring-side backlog, so an over-count is as permanent as an under-count.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/StepAccumulatorTests.swift
 * (@ b1c2fdd): all 24 tests. Upstream's `DateFormatter`/`Calendar` in a named zone (`:159-170`) are
 * an explicit `ZoneId` here, and the synthetic `+05:40` zone (`:194-195`) is a `ZoneOffset`. Every
 * call passes its zone; nothing reads the system default.
 */
class StepAccumulatorTest {

    // MARK: - The fold

    // :20 — while the bucket climbs, only the newly-taken steps are new.
    @Test
    fun climbWithinABucketCountsOnlyTheIncrement() {
        val u = StepAccumulator.update(previousRaw = 100, newRaw = 150, dayChanged = false)
        assertEquals(50, u.deltaToAdd)
        assertFalse(u.isReset)
    }

    // :28 — the same raw counter twice in a row must add 0, or every keepalive poll re-credits the bucket.
    @Test
    fun flatBucketAddsNothing() {
        val u = StepAccumulator.update(previousRaw = 4321, newRaw = 4321, dayChanged = false)
        assertEquals(0, u.deltaToAdd)
        assertFalse(u.isReset)
    }

    // :36 — real shape, 2026-08-08 18:28:30 → 18:32:48: the 18:15 bucket closed at 83 and the 18:30
    // bucket was already at 58. 58 is a NEW quarter's steps, credited whole.
    @Test
    fun bucketRollCreditsTheNewBucketWhole() {
        val u = StepAccumulator.update(previousRaw = 83, newRaw = 58, dayChanged = false)
        assertEquals(58, u.deltaToAdd)
        assertTrue(u.isReset)
    }

    // :46 — the most common roll: the next bucket has not started counting yet. 17:14:52 (98) → 17:16:41 (0).
    @Test
    fun rollToZeroCreditsNothing() {
        val u = StepAccumulator.update(previousRaw = 98, newRaw = 0, dayChanged = false)
        assertEquals(0, u.deltaToAdd)
        assertTrue(u.isReset)
    }

    // :54 — no baseline (first run / fresh pairing / reinstall): credit the bucket we connected in.
    @Test
    fun firstReadingHasNoBaselineCreditsTheBucketSoFar() {
        val u = StepAccumulator.update(previousRaw = null, newRaw = 800, dayChanged = false)
        assertEquals(800, u.deltaToAdd)
        assertFalse(u.isReset)
    }

    // :62 — across midnight the readings are certainly in different buckets: credit whole.
    @Test
    fun dayRolloverCreditsTheBucketWholeEvenWhenTheRawClimbed() {
        val u = StepAccumulator.update(previousRaw = 200, newRaw = 300, dayChanged = true)
        assertEquals(300, u.deltaToAdd)
        assertFalse(u.isReset)
    }

    // :70
    @Test
    fun dayRolloverWithADropIsStillJustARoll() {
        val u = StepAccumulator.update(previousRaw = 900, newRaw = 50, dayChanged = true)
        assertEquals(50, u.deltaToAdd)
        assertTrue(u.isReset)
    }

    // :76 — the counter is 16-bit; a wrap, a reboot and a bucket roll all present as a drop.
    @Test
    fun wraparoundIsIndistinguishableFromABucketRoll() {
        val u = StepAccumulator.update(previousRaw = 65500, newRaw = 30, dayChanged = false)
        assertEquals(30, u.deltaToAdd)
        assertTrue(u.isReset)
    }

    // :84
    @Test
    fun resetFlagIsNeverSetOnAClimb() {
        assertFalse(StepAccumulator.update(previousRaw = 10, newRaw = 20, dayChanged = false).isReset)
        assertFalse(StepAccumulator.update(previousRaw = 10, newRaw = 20, dayChanged = true).isReset)
    }

    // :89
    @Test
    fun deltaIsNeverNegative() {
        val values = listOf(0, 1, 97, 746, 65535)
        for (previous in values) {
            for (new in values) {
                for (dayChanged in listOf(false, true)) {
                    val u = StepAccumulator.update(previousRaw = previous, newRaw = new, dayChanged = dayChanged)
                    assertTrue(u.deltaToAdd >= 0, "$previous→$new day=$dayChanged")
                    assertTrue(u.deltaToAdd <= maxOf(new, new - previous))
                }
            }
        }
    }

    // MARK: - The fold over a REAL captured bucket series

    /**
     * Verbatim `[4:6]` values from upstream's 2026-08-09 diagnostics export (Gen 2, FR02.018),
     * 2026-08-08 16:54:09 → 17:29:53 local. Three buckets: 16:45 climbs, 17:00 (crossed without a
     * visible drop) and 17:15. Typed from `:106-110`.
     */
    private val realSeries0808 = listOf(
        11, 18, 18, 18, 37, 58,                                      // 16:45 bucket
        63, 70, 70, 70, 70, 70, 78, 78, 78, 98, 98, 98, 98, 98, 98,  // 17:00 bucket
        0, 0, 0, 0, 0, 9, 9, 9, 9, 9, 9, 9,                          // 17:15 bucket
    )

    // :112
    @Test
    fun foldOverARealCapturedSeriesSumsTheObservedBuckets() {
        var previous: Int? = null
        var total = 0
        for (raw in realSeries0808) {
            total += StepAccumulator.update(previousRaw = previous, newRaw = raw, dayChanged = false).deltaToAdd
            previous = raw
        }
        // 98 (the merged 16:45+17:00 run — the measured 1.3 % under-count) + 9.
        assertEquals(107, total)
        // The two rejected "fixes" would credit the 58 twice on this exact shape.
        assertTrue(total < 107 + 58)
    }

    // :127 — over a monotone-then-rolled series, the credit never exceeds the bucket-end values.
    @Test
    fun aRolledBucketIsNeverCreditedTwice() {
        val series = listOf(0, 40, 90, 0, 25, 25, 12) // roll at index 3, and a late roll at index 6
        var previous: Int? = null
        var total = 0
        for (raw in series) {
            total += StepAccumulator.update(previousRaw = previous, newRaw = raw, dayChanged = false).deltaToAdd
            previous = raw
        }
        assertEquals(90 + 25 + 12, total)
    }

    // :141
    @Test
    fun summingDeltasReconstructsASessionOfBuckets() {
        val readings: List<Pair<Int?, Int>> = listOf(
            null to 0,     // baseline, empty bucket
            0 to 400,      // +400
            400 to 1200,   // +800 → this bucket closes at 1200
            1200 to 0,     // roll
            0 to 300,      // +300
            300 to 800,    // +500 → closes at 800
        )
        var total = 0
        for ((prev, raw) in readings) {
            total += StepAccumulator.update(previousRaw = prev, newRaw = raw, dayChanged = false).deltaToAdd
        }
        assertEquals(2000, total)
    }

    // MARK: - Bucket boundaries

    // :159-170 — a wall-clock reading on 2026-08-08 in a named zone.
    private fun date(hhmmss: String, tz: String = "America/New_York"): Instant =
        LocalDateTime.parse("2026-08-08T$hhmmss").atZone(ZoneId.of(tz)).toInstant()

    private val newYork: ZoneId = ZoneId.of("America/New_York")

    // :172
    @Test
    fun bucketStartFloorsToTheQuarterHour() {
        assertEquals(date("17:00:00"), StepAccumulator.bucketStart(date("17:14:52"), newYork))
        assertEquals(date("17:15:00"), StepAccumulator.bucketStart(date("17:16:41"), newYork))
        assertEquals(date("00:00:00"), StepAccumulator.bucketStart(date("00:00:00"), newYork))
        assertEquals(date("23:45:00"), StepAccumulator.bucketStart(date("23:59:59"), newYork))
    }

    // :180 — Asia/Kolkata is UTC+05:30; the ring's quarter is a LOCAL wall-clock quarter.
    @Test
    fun bucketStartIsCorrectInAHalfHourOffsetZone() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val t = date("17:22:03", tz = "Asia/Kolkata")
        assertEquals(date("17:15:00", tz = "Asia/Kolkata"), StepAccumulator.bucketStart(t, kolkata))
    }

    // :188 — pin the wall-clock reading with a synthetic +05:40 offset, where an epoch floor lands at :10.
    @Test
    fun bucketStartIsAWallClockFloorNotAnEpochFloor() {
        val zone = ZoneOffset.ofHoursMinutes(5, 40)
        val sample = LocalDateTime.parse("2026-08-08T17:22:03").atZone(zone).toInstant()
        assertEquals(
            LocalDateTime.parse("2026-08-08T17:15:00").atZone(zone).toInstant(),
            StepAccumulator.bucketStart(sample, zone),
        )
    }

    // :204
    @Test
    fun bucketLengthIsFifteenMinutes() {
        assertEquals(Duration.ofSeconds(900), StepAccumulator.BUCKET)
    }

    // MARK: - The Health window

    private fun windowStart(sample: Instant, previous: Instant?): Instant =
        StepAccumulator.windowStart(sampleDate = sample, previousSampleAt = previous, dayStart = date("00:00:00"), zone = newYork)

    // :210 — the steady case: descriptors ~1.8 min apart inside one bucket. Nothing to clamp.
    @Test
    fun windowStartUsesThePreviousReadingWhenItIsInsideTheBucket() {
        assertEquals(date("17:11:41"), windowStart(date("17:12:22"), date("17:11:41")))
    }

    // :220 — a reconnect after a 3-hour gap used to stamp the window at the LAST reading.
    @Test
    fun windowStartFloorsAStalePreviousReadingToTheBucket() {
        assertEquals(date("17:30:00"), windowStart(date("17:40:00"), date("14:31:00")))
    }

    // :231 — upstream measured a 21.7-hour Health sample for a quarter-hour of walking.
    @Test
    fun windowStartOnTheDaysFirstReadingIsTheBucketNotMidnight() {
        assertEquals(date("21:30:00"), windowStart(date("21:43:32"), null))
    }

    // :242 — a frame 41 s after a boundary can still carry the PREVIOUS bucket.
    @Test
    fun windowStartAllowsForTheRingsClearLag() {
        assertEquals(date("17:00:00"), windowStart(date("17:15:41"), null))
        // Well clear of the lag window, it does NOT reach back.
        assertEquals(date("17:15:00"), windowStart(date("17:20:00"), null))
    }

    // :259 — just after midnight the lag allowance would reach into yesterday; the day clamp holds.
    @Test
    fun windowStartNeverCrossesMidnight() {
        assertEquals(date("00:00:00"), windowStart(date("00:01:00"), null))
    }

    // :270 — a stale/cross-session timestamp must never produce an inverted window.
    @Test
    fun windowStartRejectsAPreviousReadingFromTheFuture() {
        val start = windowStart(date("17:20:00"), date("18:00:00"))
        assertEquals(date("17:15:00"), start)
        assertTrue(start <= date("17:20:00"))
    }

    // :281
    @Test
    fun windowStartIsAlwaysWithinTheDayAndNotAfterTheSample() {
        val sample = date("13:07:09")
        for (previous in listOf(null, date("00:00:00"), date("12:00:00"), date("13:07:09"), date("23:00:00"))) {
            val start = windowStart(sample, previous)
            assertTrue(start >= date("00:00:00"))
            assertTrue(start <= sample)
        }
    }
}
