package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A tile's trend screen, as upstream's `MetricDetailView` (ios/OpenCircuit/Dashboard/
 * MetricDetailView.swift @ 2d07e550) builds it: the tile rebuilt over a 14- or 30-day window
 * ending on the day shown, the usual band and its mean, the day shown as the emphasised last
 * point, MIN / AVG / MAX over the window's days, "N of M days", what the number is, and the same
 * rows as a table and as the chart's read-out. Measure only on heart rate and SpO₂.
 */
class TileDetailPresenterTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.of(2026, 10, 21)
    private val now: Instant = today.atStartOfDay(zone).plusHours(18).toInstant()

    private fun at(day: LocalDate, hour: Int): Instant = day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant()

    /** Daytime heart rate on 20 of the last 30 days (none on days 3–12 back): 60 + (back % 5) bpm. */
    private fun trends(): TodayTrends {
        val hr = (0 until 30).filter { it !in 3..12 }.flatMap { back ->
            val d = today.minusDays(back.toLong())
            (9..17).map { h -> QuantitySample(kind = MetricKind.HEART_RATE, start = at(d, h), value = 60.0 + back % 5) }
        }
        return TodayTrends.of(TodayWindowInputs(hr = hr), zone)
    }

    @Test
    fun fourteenDaysEndingTodayWithBandMeanStatsAndCount() {
        val ui = TileDetailPresenter.present(trends(), TodayTile.Metric.HEART_RATE, 14, today, now, zone)
        assertEquals("Heart rate", ui.title)
        assertEquals("Ending today", ui.subtitle)
        assertEquals(14, ui.rows.size)
        assertEquals(today.minusDays(13), ui.rows.first().day)
        assertEquals(today, ui.rows.last().day)
        // Days 0–2 and 13 back have readings: 60, 61, 62 and 63.
        assertEquals("4 of 14 days", ui.count)
        assertEquals(listOf("MIN" to "60", "AVG" to "62", "MAX" to "63"), ui.stats.map { it.label to it.value })
        assertEquals("60", ui.value)
        assertEquals("Wed 21 Oct: 60 bpm", ui.rows.last().readout)
        assertEquals("Fri 16 Oct: no reading", ui.rows[8].readout)
        assertNotNull(ui.chart.newest, "the day shown is the emphasised last point")
        assertEquals(DetailMeasure.HEART_RATE, ui.measure)
        assertEquals(TodayTiles.what(TodayTile.Metric.HEART_RATE), ui.about)
        // Every third day back from the last: the 9th, 12th, 15th, 18th and today.
        assertEquals(listOf("9", "12", "15", "18", "Today"), ui.xLabels.map { it.text })
        assertEquals(1f, ui.xLabels.last().x)
    }

    @Test
    fun thirtyDaysWidenTheWindowAndItsCounts() {
        val ui = TileDetailPresenter.present(trends(), TodayTile.Metric.HEART_RATE, 30, today, now, zone)
        assertEquals(30, ui.rows.size)
        assertEquals("20 of 30 days", ui.count)
        val band = assertNotNull(ui.chart.band, "20 days of baseline: a usual range")
        val mean = assertNotNull(ui.chart.mean, "the dashed mean rule")
        assertTrue(mean in band.top..band.bottom, "the mean sits inside the band: $mean in $band")
        assertTrue(ui.usual.startsWith("Usual "), ui.usual)
        // Every seventh day back from the last.
        assertEquals(listOf("23", "30", "7", "14", "Today"), ui.xLabels.map { it.text })
    }

    @Test
    fun aPastDayEndsTheWindowOnItAndAMetricWithoutDataSaysSo() {
        val shown = today.minusDays(14)
        val ui = TileDetailPresenter.present(trends(), TodayTile.Metric.HEART_RATE, 14, shown, now, zone)
        assertEquals("Ending Wed 7 Oct", ui.subtitle)
        assertEquals(shown, ui.rows.last().day)
        assertEquals("64", ui.value, "that day's own value: 60 + 14 % 5")

        val spo2 = TileDetailPresenter.present(trends(), TodayTile.Metric.SPO2, 14, today, now, zone)
        assertNull(spo2.value)
        assertEquals("0 of 14 days", spo2.count)
        assertEquals(emptyList(), spo2.stats, "no MIN / AVG / MAX over nothing")
        assertEquals(DetailMeasure.SPO2, spo2.measure)
        assertTrue(spo2.description.startsWith("SpO₂ chart, no data in the last 14 days"), spo2.description)
        assertNull(TileDetailPresenter.present(trends(), TodayTile.Metric.HRV, 14, today, now, zone).measure)
    }

    @Test
    fun theChartIsDescribedInWordsWithItsLastDay() {
        val ui = TileDetailPresenter.present(trends(), TodayTile.Metric.HEART_RATE, 14, today, now, zone)
        assertTrue(ui.description.startsWith("Heart rate over the last 14 days, 4 days with data, ranging from 60 to 63 beats per minute"), ui.description)
        assertTrue(ui.description.endsWith("Wednesday 21 October: 60 beats per minute."), ui.description)
    }
}
