package io.github.opencircuit.app.today

import io.github.opencircuit.app.ui.charts.TrendGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trend chart in words and in its read-out: what a screen reader hears for the whole chart
 * (upstream's chart summary plus the emphasised last day), and which day a touch at a given x
 * lands on — the nearest day's slot, clamped to the chart, so a drag past either end still reads
 * the end day.
 */
class TrendChartDescriptionTest {

    private val values = listOf(44.0, null, 50.0, 52.0, null, 48.0)
    private fun whole(v: Double) = Math.round(v).toString()

    @Test
    fun theDescriptionNamesTheSpanTheUsualRangeAndTheLastDay() {
        val text = TrendGeometry.describeTrend("HRV", values, "milliseconds", 46.0..51.0, ::whole, "Monday 19 October", 48.0)
        assertEquals(
            "HRV over the last 6 days, 4 days with data, ranging from 44 to 52 milliseconds. Usual range 46 to 51. " +
                "Monday 19 October: 48 milliseconds.",
            text,
        )
    }

    @Test
    fun aLastDayWithoutAReadingAndAnEmptyChartSaySo() {
        assertTrue(
            TrendGeometry.describeTrend("HRV", values, "milliseconds", null, ::whole, "Monday 19 October", null)
                .endsWith("Monday 19 October: no reading."),
        )
        assertEquals(
            "HRV chart, no data in the last 3 days. Monday 19 October: no reading.",
            TrendGeometry.describeTrend("HRV", listOf(null, null, null), "milliseconds", null, ::whole, "Monday 19 October", null),
        )
    }

    @Test
    fun aTouchReadsTheNearestDayAndADragPastAnEndReadsThatEnd() {
        val n = values.size
        assertEquals(0, TrendGeometry.slotAt(0f, n))
        assertEquals(0, TrendGeometry.slotAt(0.09f, n))
        assertEquals(1, TrendGeometry.slotAt(0.11f, n), "past halfway to the second day")
        assertEquals(5, TrendGeometry.slotAt(1f, n))
        assertEquals(0, TrendGeometry.slotAt(-0.4f, n))
        assertEquals(5, TrendGeometry.slotAt(1.7f, n))
        assertEquals(0, TrendGeometry.slotAt(0.8f, 1), "a one-day chart has one slot")
        assertNull(TrendGeometry.slotAt(0.5f, 0))
    }

    @Test
    fun theMeanRuleIsPlacedInsideTheDomain() {
        val g = TrendGeometry.of(values, 46.0..51.0, mean = 48.5)
        val mean = assertNotNull(g.mean)
        val band = assertNotNull(g.band)
        assertTrue(mean > band.top && mean < band.bottom, "$mean inside $band")
        assertNull(TrendGeometry.of(values, null).mean, "no mean without a usual range")
    }
}
