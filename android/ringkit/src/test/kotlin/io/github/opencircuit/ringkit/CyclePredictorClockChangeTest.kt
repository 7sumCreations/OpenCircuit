package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.CyclePredictor.PeriodEntry
import io.github.opencircuit.ringkit.FoundationDate.unix
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Kotlin-only: cycle prediction, day classification and the period mirror across both 2026 New York
 * clock changes (8 March, 02:00 → 03:00; 1 November, 02:00 → 01:00). Every expected value was measured
 * on the pinned Swift build (Swift 6.3.2) with a New York calendar. The zone is New York so that the
 * answers depend on it: a 28-day gap across a change is 27.958… or 28.041… days, a prediction made in
 * seconds lands at 23:30 or 01:00 local rather than at midnight, and the late-evening instants asked about
 * below fall on the next day in UTC.
 */
class CyclePredictorClockChangeTest {

    private val ny: ZoneId = ZoneId.of("America/New_York")

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant = ZonedDateTime.of(y, m, d, h, min, 0, 0, ny).toInstant()

    private fun bits(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)

    /** Local midnights 8 Feb, 8 Mar and 5 Apr 2026: the second gap spans the spring change. */
    private val spring = listOf(PeriodEntry(at(2026, 2, 8)), PeriodEntry(at(2026, 3, 8)), PeriodEntry(at(2026, 4, 5)))

    /** Local midnights 4 Oct, 1 Nov and 29 Nov 2026: the second gap spans the fall change. */
    private val fall = listOf(PeriodEntry(at(2026, 10, 4)), PeriodEntry(at(2026, 11, 1)), PeriodEntry(at(2026, 11, 29)))

    private fun instants(p: CyclePredictor.CyclePrediction) =
        listOf(p.nextPeriodStart, p.nextPeriodEnd, p.fertileWindowStart, p.ovulationEstimate)

    @Test
    fun cycleLengthsAndPredictionsAcrossBothChangesAreFoundationsSeconds() {
        assertEquals(listOf(1_770_526_800L, 1_772_946_000L, 1_775_361_600L), spring.map { it.start.epochSecond })
        assertEquals(listOf(1_791_086_400L, 1_793_505_600L, 1_795_928_400L), fall.map { it.start.epochSecond })

        // Measured: 28 days, then 28 days less the skipped hour → 27.979166666666664; plus the repeated hour
        // → 28.020833333333336 (each two samples).
        val springStats = assertNotNull(CyclePredictor.cycleStats(spring))
        assertEquals(0x403bfaaaaaaaaaaaL, bits(springStats.avgCycleLengthDays))
        assertEquals(2, springStats.sampleCount)
        val fallStats = assertNotNull(CyclePredictor.cycleStats(fall))
        assertEquals(0x403c055555555556L, bits(fallStats.avgCycleLengthDays))
        assertEquals(2, fallStats.sampleCount)

        // next start, next end, fertile-window start, ovulation (seconds since 1970, measured)
        val cases = listOf(
            // now = the last start: 2 May 23:30 EDT, not 3 May 00:00
            Triple(spring, at(2026, 4, 5), listOf(1_777_779_000L, 1_778_211_000L, 1_776_137_400L, 1_776_569_400L)),
            Triple(fall, at(2026, 11, 29), listOf(1_798_349_400L, 1_798_781_400L, 1_796_707_800L, 1_797_139_800L)),
            // rolled forward across the fall change from 1 October
            Triple(spring, at(2026, 10, 1), listOf(1_792_283_400L, 1_792_715_400L, 1_790_641_800L, 1_791_073_800L)),
            // a plain 28-day history whose next start crosses the spring change: 14 Mar 01:00 EDT, not 00:00
            Triple(
                listOf(PeriodEntry(at(2025, 12, 20)), PeriodEntry(at(2026, 1, 17)), PeriodEntry(at(2026, 2, 14))),
                at(2026, 2, 14, 9),
                listOf(1_773_464_400L, 1_773_896_400L, 1_771_822_800L, 1_772_254_800L),
            ),
        )
        for ((history, now, want) in cases) {
            val p = assertNotNull(CyclePredictor.predict(history, now = now))
            assertEquals(want.map { unix(it.toDouble()) }, instants(p), "now $now")
        }
    }

    @Test
    fun loggedDaysAreTheZonesCalendarDaysOnTheClockChangeDays() {
        // A period logged 7 → 9 March: measured at each instant.
        val springLog = listOf(PeriodEntry(at(2026, 3, 7), at(2026, 3, 9)))
        val springAsked = listOf(
            at(2026, 3, 6, 23, 59) to false,
            at(2026, 3, 7) to true,
            at(2026, 3, 8, 1, 59) to true,
            at(2026, 3, 8, 3) to true, // the first minute after the skipped hour
            at(2026, 3, 9, 23, 59) to true, // 10 March in UTC
            at(2026, 3, 10) to false,
        )
        for ((t, logged) in springAsked) assertEquals(logged, CyclePredictor.isLoggedPeriodDay(t, springLog, ny), "$t")

        // A period logged 31 October → 1 November noon; 1 November has two 01:30s.
        val fallLog = listOf(PeriodEntry(at(2026, 10, 31), at(2026, 11, 1, 12)))
        val firstOneThirty = Instant.ofEpochSecond(1_793_511_000L) // 01:30 EDT
        assertEquals(1, firstOneThirty.atZone(ny).hour)
        val fallAsked = listOf(
            firstOneThirty to true,
            firstOneThirty.plusSeconds(3_600) to true, // 01:30 EST, the repeated hour
            at(2026, 11, 1, 23, 30) to true, // 2 November in UTC
            at(2026, 11, 2) to false,
            at(2026, 10, 30, 23, 59) to false,
        )
        for ((t, logged) in fallAsked) assertEquals(logged, CyclePredictor.isLoggedPeriodDay(t, fallLog, ny), "$t")
    }

    @Test
    fun predictedFertileAndOvulationDaysAreReadInTheZone() {
        // The spring prediction's instants all fall at 23:30 local (03:30 UTC the next day). Asked at each
        // one's local midnight, 23:30, the next midnight and an hour before midnight — measured on the
        // pinned build as [in predicted period, in fertile window, ovulation day]:
        val p = assertNotNull(CyclePredictor.predict(spring, now = at(2026, 4, 5)))
        val expected = mapOf(
            p.ovulationEstimate to listOf(listOf(false, true, true), listOf(false, true, true), listOf(false, false, false), listOf(false, true, false)),
            p.fertileWindowStart to listOf(listOf(false, true, false), listOf(false, true, false), listOf(false, true, false), listOf(false, false, false)),
            p.nextPeriodStart to listOf(listOf(true, false, false), listOf(true, false, false), listOf(true, false, false), listOf(false, false, false)),
            p.nextPeriodEnd to listOf(listOf(true, false, false), listOf(true, false, false), listOf(false, false, false), listOf(true, false, false)),
        )
        for ((base, rows) in expected) {
            val midnight = base.atZone(ny).toLocalDate().atStartOfDay(ny).toInstant()
            val asked = listOf(midnight, midnight.plusSeconds(23 * 3_600L + 30 * 60), midnight.plusSeconds(24 * 3_600L), midnight.minusSeconds(3_600L))
            assertEquals(base, asked[1], "the instant itself is 23:30 local")
            for ((t, want) in asked.zip(rows)) {
                val got = listOf(
                    CyclePredictor.isInPredictedPeriod(t, p, ny),
                    CyclePredictor.isInFertileWindow(t, p, ny),
                    CyclePredictor.isOvulationDay(t, p, ny),
                )
                assertEquals(want, got, "$t")
            }
        }
    }

    @Test
    fun theMirrorCountsDaysAcrossBothChanges() {
        // start, today (both at local noon unless stated), end → measured last day (seconds since 1970),
        // day count, cap reached, up to date with 4 samples written
        data class Row(val start: Instant, val today: Instant, val end: Instant?, val last: Long, val count: Long, val capped: Boolean, val upToDate4: Boolean)
        val rows = listOf(
            Row(at(2026, 3, 5, 12), at(2026, 3, 8, 12), null, 1_772_946_000L, 4, false, true),
            Row(at(2026, 3, 5, 12), at(2026, 3, 11, 12), null, 1_773_201_600L, 7, false, false),
            Row(at(2026, 3, 5, 12), at(2026, 3, 12, 12), null, 1_773_288_000L, 8, true, false),
            Row(at(2026, 3, 5, 12), at(2026, 3, 20, 12), null, 1_773_288_000L, 8, true, false),
            Row(at(2026, 10, 29, 12), at(2026, 11, 2, 12), null, 1_793_595_600L, 5, false, false),
            Row(at(2026, 10, 29, 12), at(2026, 11, 10, 12), at(2026, 11, 1, 23, 30), 1_793_505_600L, 4, true, true),
            Row(at(2026, 10, 29, 12), at(2026, 11, 5, 12), null, 1_793_854_800L, 8, true, false),
        )
        for (r in rows) {
            assertEquals(Instant.ofEpochSecond(r.last), CyclePredictor.periodMirrorLastDay(start = r.start, end = r.end, today = r.today, zone = ny), "${r.today}")
            assertEquals(r.count, CyclePredictor.periodMirrorDayCount(start = r.start, end = r.end, today = r.today, zone = ny), "${r.today}")
            assertEquals(r.capped, CyclePredictor.openPeriodHasReachedAutoExtendCap(start = r.start, today = r.today, zone = ny), "${r.today}")
            assertEquals(r.upToDate4, CyclePredictor.periodMirrorIsUpToDate(writtenSampleCount = 4, start = r.start, end = r.end, today = r.today, zone = ny), "${r.today}")
        }
    }
}
