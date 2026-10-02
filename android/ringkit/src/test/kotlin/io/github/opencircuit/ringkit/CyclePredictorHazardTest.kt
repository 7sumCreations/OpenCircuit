package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.CyclePredictor.PeriodEntry
import io.github.opencircuit.ringkit.CyclePredictor.SkinTempNight
import io.github.opencircuit.ringkit.FoundationDate.referenceSeconds
import io.github.opencircuit.ringkit.FoundationDate.unix
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for cycle prediction and the period mirror: what the upstream vectors
 * never feed in. Period dates are entered by the user and stored; skin-temperature nights come from the
 * ring; `now`, `today` and the mirror's covered-day count come from the phone and the store. So here the
 * history arrives unsorted, duplicated, overlapping and ending before it starts, the clock arrives at
 * Foundation's distant dates and at the ends of `Instant`, a period starts on a day whose midnight the
 * zone skips, and the covered-day count arrives at the ends of `Int`. Kept out of the upstream-port
 * classes so their counts stay exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Upstream
 * keeps every instant as a `Double` of seconds since 2001 and adds and subtracts in those doubles; the
 * port does the same and converts to an `Instant` once, so the instants here are stated as the doubles
 * upstream printed. Where the port deliberately differs the test says so, and `PORTING.md` records why.
 */
class CyclePredictorHazardTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val day = 86_400.0

    /** 2025-07-11 00:00:00 UTC, the anchor upstream's mirror tests use. */
    private val a = 1_752_192_000.0

    private fun entry(start: Double, end: Double? = null) = PeriodEntry(unix(start), end?.let(::unix))

    private fun bits(t: Instant): Long = java.lang.Double.doubleToRawLongBits(referenceSeconds(t))

    private fun bits(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)

    // MARK: the roll-forward loop

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun theRollForwardWalksToFoundationsDistantDatesAsUpstreamAndIsBoundedBeyond() {
        val base = listOf(entry(a), entry(a + 28 * day))
        // Measured: Foundation's distant future as `now` (64 092 211 200 s, about 35 000 additions), 1e11
        // and 1e12 (412 637 additions, 4 ms upstream) — each the first 28-day step at or after `now`.
        for ((now, next) in listOf(64_092_211_200.0 to 64_092_556_800.0, 1e11 to 100_000_742_400.0, 1e12 to 1_000_001_203_200.0)) {
            assertEquals(unix(next), assertNotNull(CyclePredictor.predict(base, now = unix(now))).nextPeriodStart, "now $now")
        }
        // Foundation's distant past as a logged start (21-day cycle): measured 1 787 356 800.
        val distantPast = -62_135_769_600.0
        val fromThePast = CyclePredictor.predict(listOf(entry(distantPast), entry(distantPast + 21 * day)), now = unix(1_786_400_000.0))
        assertEquals(unix(1_787_356_800.0), assertNotNull(fromThePast).nextPeriodStart)

        // Beyond Foundation's dates, at `Instant`'s ends, upstream would walk about 1.3e10 additions one at
        // a time (412 637 took 4 ms, so minutes). The port walks a bounded number and then jumps the rest
        // in one step: the answer is still the first step at or after `now`, and nothing throws.
        val atTheEnd = assertNotNull(CyclePredictor.predict(base, now = Instant.MAX))
        assertEquals(Instant.MAX, atTheEnd.nextPeriodStart, "a step past the last instant saturates")
        assertEquals(Instant.MAX, atTheEnd.nextPeriodEnd)
        assertTrue(atTheEnd.ovulationEstimate.isBefore(Instant.MAX))
        val now = unix(1_786_400_000.0)
        val cycle = 28 * day
        for (start in listOf(Instant.MIN, Instant.MIN.plusSeconds(12_345))) {
            val p = assertNotNull(CyclePredictor.predict(listOf(PeriodEntry(start), PeriodEntry(start.plusSeconds(28 * 86_400L))), now = now))
            assertEquals(28.0, p.avgCycleLengthDays)
            assertFalse(p.nextPeriodStart.isBefore(now), "rolled to or past now")
            assertTrue(referenceSeconds(p.nextPeriodStart) - cycle < referenceSeconds(now), "the FIRST step at or after now")
        }
    }

    // MARK: cycle statistics on hostile histories

    @Test
    fun hostileHistoriesFollowUpstream() {
        fun stats(vararg e: PeriodEntry) = CyclePredictor.cycleStats(e.toList())
        // Measured on the pinned build (average, sample count, average duration):
        // unsorted → sorted first: 28.0, 2, none
        val unsorted = assertNotNull(stats(entry(a + 56 * day), entry(a), entry(a + 28 * day)))
        assertEquals(listOf<Any?>(28.0, 2, null), listOf(unsorted.avgCycleLengthDays, unsorted.sampleCount, unsorted.avgPeriodDurationDays))
        // duplicated start → a zero-day interval, excluded: 28.0, 1
        val dup = assertNotNull(stats(entry(a), entry(a), entry(a + 28 * day)))
        assertEquals(listOf<Any?>(28.0, 1, null), listOf(dup.avgCycleLengthDays, dup.sampleCount, dup.avgPeriodDurationDays))
        // an end before its start → a negative duration, outside 1…10 days, ignored
        val backwards = assertNotNull(stats(entry(a, a - 5 * day), entry(a + 28 * day)))
        assertEquals(listOf<Any?>(28.0, 1, null), listOf(backwards.avgCycleLengthDays, backwards.sampleCount, backwards.avgPeriodDurationDays))
        // overlapping entries: the 40-day "period" is ignored, the 5-day one kept
        val overlap = assertNotNull(stats(entry(a, a + 40 * day), entry(a + 28 * day, a + 33 * day)))
        assertEquals(listOf<Any?>(28.0, 1, 5.0), listOf(overlap.avgCycleLengthDays, overlap.sampleCount, overlap.avgPeriodDurationDays))
        // durations at the sanity edge: exactly 10 days kept, 10.0001 dropped
        val edge = assertNotNull(stats(entry(a, a + 10 * day), entry(a + 28 * day, a + 28 * day + 10.0001 * day)))
        assertEquals(10.0, edge.avgPeriodDurationDays)
        // intervals at the length edges: exactly 21 and 45 days kept, 20.99999 dropped → 33.0, 2
        val lengths = assertNotNull(stats(entry(a), entry(a + 21 * day), entry(a + 66 * day), entry(a + 66 * day + 20.99999 * day)))
        assertEquals(listOf<Any?>(33.0, 2), listOf(lengths.avgCycleLengthDays, lengths.sampleCount))
        // Entries tied on their start keep their given order (Swift's sort is stable), and the durations are
        // summed in that order: 3.1 + 7.7 + 1.3 + 2.9 → 3.7500000000000004, 1.3 + 7.7 + 3.1 + 2.9 → 3.75.
        val tied = assertNotNull(stats(entry(a, a + 3.1 * day), entry(a, a + 7.7 * day), entry(a, a + 1.3 * day), entry(a + 30 * day, a + 30 * day + 2.9 * day)))
        assertEquals(0x400e000000000001L, bits(assertNotNull(tied.avgPeriodDurationDays)))
        assertEquals(listOf<Any?>(30.0, 1), listOf(tied.avgCycleLengthDays, tied.sampleCount))
        val reversed = assertNotNull(stats(entry(a + 30 * day, a + 30 * day + 2.9 * day), entry(a, a + 1.3 * day), entry(a, a + 7.7 * day), entry(a, a + 3.1 * day)))
        assertEquals(0x400e000000000000L, bits(assertNotNull(reversed.avgPeriodDurationDays)))
        // Too little history, or no interval in range, predicts nothing.
        assertNull(stats())
        assertNull(stats(entry(a)))
        assertNull(stats(entry(a), entry(a + 15 * day)))
        assertNull(CyclePredictor.predict(listOf(entry(a), entry(a + 50 * day)), now = unix(a)))
    }

    // MARK: skin-temperature corroboration

    @Test
    fun skinTemperatureNightsFollowUpstream() {
        val base = listOf(entry(a), entry(a + 28 * day))
        val now = unix(a + 28 * day)
        val ov = assertNotNull(CyclePredictor.predict(base, now = now)).ovulationEstimate
        fun corroborated(vararg nights: SkinTempNight) = assertNotNull(CyclePredictor.predict(base, nights.toList(), now)).tempCorroborated
        fun at(t: Instant, seconds: Double) = assertNotNull(addingSeconds(t, seconds))
        // Measured on the pinned build:
        // the same night given twice counts twice (kept as upstream: the caller passes one value a night)
        assertTrue(corroborated(SkinTempNight(ov, 0.3), SkinTempNight(ov, 0.3)))
        // an unreadable offset never counts; an infinite one does (it is ≥ 0.2)
        assertFalse(corroborated(SkinTempNight(ov, Double.NaN), SkinTempNight(ov, Double.NaN)))
        assertTrue(corroborated(SkinTempNight(ov, Double.POSITIVE_INFINITY), SkinTempNight(at(ov, 3 * day), 0.2)))
        assertFalse(corroborated(SkinTempNight(ov, Double.NEGATIVE_INFINITY), SkinTempNight(ov, 0.5)))
        // the window is closed at both ends, ±3 days; 1 µs past its end is outside
        assertTrue(corroborated(SkinTempNight(at(ov, 3 * day), 0.2), SkinTempNight(at(ov, -3 * day), 0.2)))
        assertFalse(corroborated(SkinTempNight(at(ov, 3 * day + 1e-6), 0.2), SkinTempNight(at(ov, -3 * day), 0.2)))
        // nights at the ends of `Instant` are simply outside the window, and nothing throws
        assertFalse(corroborated(SkinTempNight(Instant.MIN, 1.0), SkinTempNight(Instant.MAX, 1.0), SkinTempNight(ov, 1.0)))
    }

    // MARK: the period mirror

    /** A start at noon local on [date] in [zone], and the days counted on k days later. */
    private class MirrorRow(val counts: List<Long>, val capped: List<Boolean>, val covered10: List<Long>, val ended: List<Long>)

    private val ks = listOf(0, 6, 7, 8, 12)

    private fun mirrorRow(zone: ZoneId, start: Instant): MirrorRow {
        val todays = ks.map { start.plusSeconds(86_400L * it) }
        val end = start.plusSeconds(4 * 86_400L)
        return MirrorRow(
            counts = todays.map { CyclePredictor.periodMirrorDayCount(start = start, end = null, today = it, zone = zone) },
            capped = todays.map { CyclePredictor.openPeriodHasReachedAutoExtendCap(start = start, today = it, zone = zone) },
            covered10 = todays.map { CyclePredictor.periodMirrorDayCount(start = start, end = null, today = it, alreadyCoveredDays = 10, zone = zone) },
            ended = todays.map { CyclePredictor.periodMirrorDayCount(start = start, end = end, today = it, zone = zone) },
        )
    }

    @Test
    fun theMirrorCountsCalendarDaysWhenAPeriodStartsOnADayWhoseMidnightIsSkipped() {
        // Three zones whose 2026 spring change skips midnight itself, so the start day begins at 01:00:
        // Santiago 6 September, Havana 8 March, Beirut 29 March. Upstream adds days to that 01:00 and
        // counts whole days from it, so on such a day it counts one day short (measured, days 0/6/7/8/12):
        // open period 1, 6, 7, 8, 8 (cap reached a day late); 10 days already covered 1, 6, 7, 8, 10; a
        // 5-day logged period mirrors 4 days for good. The port counts calendar days: one day more on each,
        // the cap on the eighth day, all five logged days. Every other start agrees with upstream (below).
        for ((zoneId, date) in listOf("America/Santiago" to Triple(2026, 9, 6), "America/Havana" to Triple(2026, 3, 8), "Asia/Beirut" to Triple(2026, 3, 29))) {
            val zone = ZoneId.of(zoneId)
            val noon = ZonedDateTime.of(date.first, date.second, date.third, 12, 0, 0, 0, zone).toInstant()
            val firstDay = ZonedDateTime.of(date.first, date.second, date.third, 0, 0, 0, 0, zone)
            assertEquals(1, firstDay.hour, "$zoneId: midnight is skipped on the start day")
            val row = mirrorRow(zone, noon)
            assertEquals(listOf(1L, 7L, 8L, 8L, 8L), row.counts, zoneId)
            assertEquals(listOf(false, false, true, true, true), row.capped, zoneId)
            assertEquals(listOf(1L, 7L, 8L, 9L, 10L), row.covered10, zoneId)
            assertEquals(listOf(1L, 5L, 5L, 5L, 5L), row.ended, zoneId)
            // The last day is the start of the eighth calendar day (upstream: 01:00 on it).
            val eighth = firstDay.toLocalDate().plusDays(7).atStartOfDay(zone).toInstant()
            assertEquals(eighth, CyclePredictor.openPeriodAutoExtendLastDay(start = noon, today = noon.plusSeconds(12 * 86_400L), zone = zone))
            // Starts 3 and 7 days earlier span the skipped midnight without starting on it: as upstream.
            for (back in listOf(3L, 7L)) {
                val earlier = mirrorRow(zone, noon.minusSeconds(back * 86_400L))
                assertEquals(listOf(1L, 7L, 8L, 8L, 8L), earlier.counts, "$zoneId back $back")
                assertEquals(listOf(false, false, true, true, true), earlier.capped, "$zoneId back $back")
                assertEquals(listOf(1L, 7L, 8L, 9L, 10L), earlier.covered10, "$zoneId back $back")
                assertEquals(listOf(1L, 5L, 5L, 5L, 5L), earlier.ended, "$zoneId back $back")
            }
        }
    }

    @Test
    fun hostileMirrorInputsFollowUpstream() {
        val start = unix(a)
        val today = unix(a + 19 * day)
        // Measured (UTC, today = day 19): a covered-day count at or past the elapsed days reaches today and
        // no further (Foundation never answers nil here: it returns a far date, and at `Int.max` the start
        // itself, so the floor is clamped to today either way); 0, −1 and the `Int` minimum leave the cap.
        for (covered in listOf(Int.MAX_VALUE, Int.MAX_VALUE - 1, 1_000_000_000, 10_000_000, 3_000_000, 1_000_000, 20)) {
            assertEquals(unix(1_753_833_600.0), CyclePredictor.openPeriodAutoExtendLastDay(start = start, today = today, alreadyCoveredDays = covered, zone = utc), "covered $covered")
            assertEquals(20L, CyclePredictor.periodMirrorDayCount(start = start, end = null, today = today, alreadyCoveredDays = covered, zone = utc), "covered $covered")
            assertEquals(covered == 20, CyclePredictor.periodMirrorIsUpToDate(writtenSampleCount = covered, start = start, end = null, today = today, zone = utc), "covered $covered")
        }
        for (covered in listOf(1, 0, -1, Int.MIN_VALUE)) {
            assertEquals(unix(1_752_796_800.0), CyclePredictor.openPeriodAutoExtendLastDay(start = start, today = today, alreadyCoveredDays = covered, zone = utc), "covered $covered")
            assertEquals(8L, CyclePredictor.periodMirrorDayCount(start = start, end = null, today = today, alreadyCoveredDays = covered, zone = utc), "covered $covered")
            assertFalse(CyclePredictor.periodMirrorIsUpToDate(writtenSampleCount = covered, start = start, end = null, today = today, zone = utc), "covered $covered")
        }
        // today before the start: nothing to mirror, the cap not reached, the last day is today's
        assertEquals(0L, CyclePredictor.periodMirrorDayCount(start = start, end = null, today = unix(a - day), zone = utc))
        assertFalse(CyclePredictor.openPeriodHasReachedAutoExtendCap(start = start, today = unix(a - 30 * day), zone = utc))
        assertEquals(unix(1_752_105_600.0), CyclePredictor.openPeriodAutoExtendLastDay(start = start, today = unix(a - day), zone = utc))
        // an end before the start: nothing to mirror, the last day is the end's
        assertEquals(0L, CyclePredictor.periodMirrorDayCount(start = start, end = unix(a - 3 * day), today = unix(a + 9 * day), zone = utc))
        assertEquals(unix(1_751_932_800.0), CyclePredictor.periodMirrorLastDay(start = start, end = unix(a - 3 * day), today = unix(a + 9 * day), zone = utc))
        assertFalse(CyclePredictor.isLoggedPeriodDay(unix(a - day), listOf(PeriodEntry(start, unix(a - 3 * day))), utc))
        assertFalse(CyclePredictor.isLoggedPeriodDay(start, listOf(PeriodEntry(start, unix(a - 3 * day))), utc))
        // a negative written count is never up to date
        assertFalse(CyclePredictor.periodMirrorIsUpToDate(writtenSampleCount = -5, start = start, end = null, today = unix(a + 2 * day), zone = utc))
    }

    @Test
    fun theEndsOfTimeNeverThrowAndAnUnplaceableDayIsNoDay() {
        // Foundation's own distant dates: measured, the cap reached, 1 460 973 days between them (whole days
        // since its distant past, which java.time places on 30 December of year 0), and the far day logged.
        val distantPast = unix(-62_135_769_600.0)
        val distantFuture = unix(64_092_211_200.0)
        assertTrue(CyclePredictor.openPeriodHasReachedAutoExtendCap(start = distantPast, today = distantFuture, zone = utc))
        assertEquals(1_460_973L, CyclePredictor.periodMirrorDayCount(start = distantPast, end = distantFuture, today = distantFuture, zone = utc))
        assertTrue(CyclePredictor.isLoggedPeriodDay(distantFuture, listOf(PeriodEntry(distantPast, distantFuture)), utc))

        // `Instant`'s own ends cannot be placed in any zone (their local date-time is past java.time's
        // range), where Foundation still reads a day. Kept difference: such an instant is no day — never
        // logged, never predicted, no mirror (no last day, nothing to count, never up to date, no cap) —
        // and nothing throws.
        val start = unix(a)
        val today = unix(a + 3 * day)
        val prediction = assertNotNull(CyclePredictor.predict(listOf(entry(a - 28 * day), entry(a)), now = start))
        for (end in listOf(Instant.MIN, Instant.MAX)) {
            for (zone in listOf(utc, ZoneId.of("Pacific/Kiritimati"), ZoneId.of("Pacific/Pago_Pago"))) {
                assertFalse(CyclePredictor.isLoggedPeriodDay(end, listOf(PeriodEntry(start, today)), zone))
                assertFalse(CyclePredictor.isLoggedPeriodDay(start, listOf(PeriodEntry(end, null)), zone))
                assertFalse(CyclePredictor.isLoggedPeriodDay(start, listOf(PeriodEntry(start, end)), zone))
                assertFalse(CyclePredictor.isInPredictedPeriod(end, prediction, zone))
                assertFalse(CyclePredictor.isInFertileWindow(end, prediction, zone))
                assertFalse(CyclePredictor.isOvulationDay(end, prediction, zone))
                assertNull(CyclePredictor.openPeriodAutoExtendLastDay(start = end, today = today, zone = zone))
                assertNull(CyclePredictor.openPeriodAutoExtendLastDay(start = start, today = end, zone = zone))
                assertNull(CyclePredictor.periodMirrorLastDay(start = start, end = end, today = today, zone = zone))
                assertEquals(0L, CyclePredictor.periodMirrorDayCount(start = end, end = null, today = today, zone = zone))
                assertEquals(0L, CyclePredictor.periodMirrorDayCount(start = start, end = null, today = end, zone = zone))
                assertFalse(CyclePredictor.periodMirrorIsUpToDate(writtenSampleCount = 4, start = start, end = end, today = today, zone = zone))
                assertFalse(CyclePredictor.openPeriodHasReachedAutoExtendCap(start = end, today = today, zone = zone))
                assertFalse(CyclePredictor.openPeriodHasReachedAutoExtendCap(start = start, today = end, zone = zone))
            }
            // A prediction from a history at the ends of time computes (saturating), and nothing throws.
            assertNotNull(CyclePredictor.predict(listOf(PeriodEntry(end), entry(a), entry(a + 28 * day)), now = end))
        }
    }

    // MARK: the date arithmetic (upstream's doubles, converted once)

    @Test
    fun theDateArithmeticIsFoundationsDoubleSecondsToTheLastBit() {
        // A history with fractional-second starts, rolled forward from 2020 to 2026 and to 2100. Upstream
        // adds the average cycle in `Double` seconds since 2001, rounding at every step; the port does the
        // same and converts each result to an `Instant` once. Measured (the doubles upstream printed, as
        // IEEE-754 bits): average 0x403d00004c9ab3c5 (29.00000456597242 days), duration 0x400ffffe3f0dd7cc.
        val t0 = 1_600_000_000.123
        val history = listOf(
            entry(t0),
            entry(t0 + 29 * 86_400 + 0.456),
            entry(t0 + 58 * 86_400 + 0.789, t0 + 62 * 86_400 + 0.5),
        )
        val stats = assertNotNull(CyclePredictor.cycleStats(history))
        assertEquals(0x403d00004c9ab3c5L, bits(stats.avgCycleLengthDays))
        assertEquals(0x400ffffe3f0dd7ccL, bits(assertNotNull(stats.avgPeriodDurationDays)))
        // next start, next end, fertile-window start, ovulation — seconds since 2001 as upstream printed them
        val expected = mapOf(
            1_605_000_000.0 to listOf(0x41c2c07d00a73b64L, 0x41c2c32000823d70L, 0x41c2b3f6c0a73b64L, 0x41c2b74280a73b64L),
            1_786_400_000.0 to listOf(0x41c820db0edaf1b4L, 0x41c8237e0eb5f3c0L, 0x41c81454cedaf1b4L, 0x41c817a08edaf1b4L),
            4_102_444_800.0 to listOf(0x41e7480f31474f40L, 0x41e748b7f13e0fc3L, 0x41e744eda1474f40L, 0x41e745c091474f40L),
        )
        for ((now, want) in expected) {
            val p = assertNotNull(CyclePredictor.predict(history, now = unix(now)))
            assertEquals(want, listOf(p.nextPeriodStart, p.nextPeriodEnd, p.fertileWindowStart, p.ovulationEstimate).map(::bits), "now $now")
            assertEquals(p.ovulationEstimate, p.fertileWindowEnd)
        }
    }
}
