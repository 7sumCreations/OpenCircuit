package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.DailySteps
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A past day on Today: its tiles' two weeks end on THAT day and each tile shows that day's value
 * only (never an older day's in its place); steps compare the whole day with usual and claim no
 * goal; readiness is "that day" and there is no synthesis line; the live hero gives way to the
 * day's heart-rate range. Synthetic, ring-shaped readings built on the raw path (the window rollup).
 */
class PastDayTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.of(2026, 10, 21)
    private val now: Instant = today.atStartOfDay(zone).plusHours(18).toInstant()
    private val shown: LocalDate = today.minusDays(2)

    private fun at(day: LocalDate, hour: Int): Instant = day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant()

    /** Daytime heart rate on days −9…0 (low 50 + d, high 110 + d), steps on −9…0, SpO₂ only up to −3. */
    private fun trends(): TodayTrends {
        val days = (0..9).map { today.minusDays(it.toLong()) }
        val hr = days.flatMap { d ->
            val k = (today.toEpochDay() - d.toEpochDay()).toDouble()
            listOf(QuantitySample(kind = MetricKind.HEART_RATE, start = at(d, 3), value = 50.0 + k)) +
                (9..17).map { h -> QuantitySample(kind = MetricKind.HEART_RATE, start = at(d, h), value = 70.0) } +
                QuantitySample(kind = MetricKind.HEART_RATE, start = at(d, 18), value = 110.0 + k)
        }
        val spo2 = days.filter { it <= today.minusDays(3) }.map { QuantitySample(kind = MetricKind.SPO2, start = at(it, 3), value = 0.96) }
        val steps = days.map { d -> DailySteps(d.atStartOfDay(zone).toInstant(), if (d == shown) 12_000 else 6_000 + (d.dayOfMonth % 3) * 100L, now) }
        return TodayTrends.of(TodayWindowInputs(hr = hr, spo2 = spo2, dailies = steps), zone)
    }

    private fun present(day: LocalDate) = TodayDashboard.present(trends(), day, now, zone, lastSyncAt = now.minusSeconds(600))

    @Test
    fun aPastDaysTilesEndOnThatDayAndShowOnlyItsOwnValue() {
        val ui = present(shown)
        val hr = ui.tiles.single { it.metric == TodayTile.Metric.HEART_RATE }
        // The day's average: (50+2 + 9×70 + 110+2) / 11.
        assertEquals(((52.0 + 9 * 70 + 112) / 11).let { Math.round(it).toString() }, hr.value)
        assertNull(hr.freshness, "the heading already names the day")
        assertFalse(hr.stale)
        assertEquals("Range 52–112 bpm", hr.extra)
        assertNotNull(hr.chart.newest, "the shown day is the chart's last point")

        val spo2 = ui.tiles.single { it.metric == TodayTile.Metric.SPO2 }
        assertNull(spo2.value, "no SpO₂ that day: the day before's is not shown in its place")
        assertEquals("No reading that day", spo2.usual)
        assertTrue(spo2.description.contains("no reading that day"), spo2.description)
        assertNull(spo2.chart.newest, "nothing that day, so no accent dot")
    }

    @Test
    fun stepsOnAPastDayCompareTheWholeDayWithUsualAndClaimNoGoal() {
        val steps = present(shown).tiles.single { it.metric == TodayTile.Metric.STEPS }
        assertEquals("12,000", steps.value)
        assertEquals("Connected time only", steps.extra, "the goal belongs to today's running total")
        val delta = assertNotNull(steps.delta, "a complete day is judged")
        assertTrue(delta.startsWith("+") && delta.endsWith("vs usual") && !delta.startsWith("Yesterday"), delta)
        assertTrue(steps.description.startsWith("Steps. 12,000 steps, that day."), steps.description)

        // On today the same rules as before: today's running total, judged on yesterday.
        val todaySteps = present(today).tiles.single { it.metric == TodayTile.Metric.STEPS }
        assertTrue(todaySteps.description.startsWith("Steps. "), todaySteps.description)
        assertTrue(todaySteps.extra!!.startsWith("Goal "), todaySteps.extra)
    }

    @Test
    fun aPastDayHasReadinessThatDayNoSynthesisAndItsHeartRateRangeInPlaceOfTheLiveHero() {
        val ui = present(shown)
        assertNull(ui.synthesis)
        assertEquals("Readiness that day", ui.readiness?.heading)
        val hero = assertNotNull(ui.pastDay)
        assertEquals("Heart rate on Mon 19 Oct", hero.title)
        assertEquals("52–112", hero.range)
        // (52 + 9 × 70 + 112) / 11 = 72.2.
        assertEquals("average 72", hero.detail?.substringBefore(" ·"))
        assertEquals((52.0 - 40) / 120, hero.low.toDouble(), 1e-6)
        assertEquals((112.0 - 40) / 120, hero.high.toDouble(), 1e-6)
        assertTrue(hero.description.startsWith("Heart rate on Monday 19 October: 52 to 112 beats per minute, average 72"), hero.description)

        assertNull(present(today).pastDay, "today keeps the live hero")
    }

    @Test
    fun aPastDayWithNoHeartRateSaysSoInsteadOfARange() {
        val empty = TodayDashboard.present(TodayTrends(), shown, now, zone, lastSyncAt = null)
        val hero = assertNotNull(empty.pastDay)
        assertNull(hero.range)
        assertEquals("No heart rate was stored for this day.", hero.empty)
        assertEquals("Heart rate on Monday 19 October. No heart rate was stored for this day.", hero.description)
    }
}
