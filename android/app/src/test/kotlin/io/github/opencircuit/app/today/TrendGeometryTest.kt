package io.github.opencircuit.app.today

import io.github.opencircuit.app.ui.charts.ChartPoint
import io.github.opencircuit.app.ui.charts.TrendGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trend chart's geometry, as points and rectangles in a unit box (x 0 → 1 left to right, y 0
 * at the top): the draw call only scales it. A missing day breaks the line; a day alone between
 * gaps is a dot; the newest day is the larger dot; the usual-range band is a rectangle, none while
 * the baseline is learning; the y domain is the data and the band padded by 30 % of their span.
 */
class TrendGeometryTest {

    private val padding = 0.30

    private fun close(expected: Double, actual: Float, what: String) = assertEquals(expected, actual.toDouble(), 1e-6, what)

    @Test
    fun aMissingDayBreaksTheLineAndALoneDayIsADot() {
        val g = TrendGeometry.of(listOf(50.0, 52.0, null, 51.0, null, 49.0, 50.0), band = null)
        assertEquals(listOf(listOf(0, 1), listOf(5, 6)), g.segments.map { seg -> seg.map { Math.round(it.x * 6) } })
        assertEquals(1, g.dots.size, "day 3 stands alone")
        close(3.0 / 6, g.dots.single().x, "the dot sits at its day")
        val newest = g.newest!!
        close(1.0, newest.x, "the newest day is the last slot")
        assertTrue(g.segments.last().last() == newest, "the newest point also ends its segment")
    }

    @Test
    fun theYDomainIsTheDataAndBandPaddedByThirtyPercentOfTheSpan() {
        val g = TrendGeometry.of(listOf(40.0, 60.0), band = 45.0..65.0)
        val span = 65.0 - 40.0
        assertEquals(40.0 - span * padding / 2, g.yMin, 1e-9)
        assertEquals(65.0 + span * padding / 2, g.yMax, 1e-9)
        // y is inverted: the top of the box is the domain's top.
        close((g.yMax - 60.0) / (g.yMax - g.yMin), g.newest!!.y, "60 maps under the top padding")
        val band = g.band!!
        close((g.yMax - 65.0) / (g.yMax - g.yMin), band.top, "band top at 65")
        close((g.yMax - 45.0) / (g.yMax - g.yMin), band.bottom, "band bottom at 45")
        assertTrue(band.top < band.bottom)
    }

    @Test
    fun noBandWhileTheBaselineIsLearning() {
        assertNull(TrendGeometry.of(listOf(50.0, 52.0, 58.0), band = null).band)
    }

    @Test
    fun aFlatSeriesStillHasHeightAndSitsMidBox() {
        val g = TrendGeometry.of(listOf(50.0, 50.0, 50.0), band = null)
        assertTrue(g.yMax > g.yMin)
        close(0.5, g.newest!!.y, "a flat line is drawn through the middle")
    }

    @Test
    fun anEmptySeriesDrawsNothing() {
        val g = TrendGeometry.of(listOf(null, null, null), band = null)
        assertEquals(emptyList(), g.segments)
        assertEquals(emptyList(), g.dots)
        assertNull(g.newest)
        assertNull(g.band)
    }

    @Test
    fun noNewestDotWhenTheLastDayHasNoValue() {
        // Steps: today's partial total is never plotted, so yesterday ends the line and no accent dot is drawn.
        val g = TrendGeometry.of(listOf(8_000.0, 9_000.0, 12_000.0, null), band = null)
        assertNull(g.newest)
        assertEquals(1, g.segments.size)
    }

    @Test
    fun aSingleDayWindowStillPlacesItsPoint() {
        val g = TrendGeometry.of(listOf(42.0), band = null)
        assertEquals(ChartPoint(0.5f, 0.5f), g.newest)
    }

    @Test
    fun ticksAreThreeEvenValuesInsideTheDomainWithTheirHeights() {
        val g = TrendGeometry.of(listOf(40.0, 60.0), band = null)
        assertEquals(3, g.ticks.size)
        assertTrue(g.ticks.all { it.value in g.yMin..g.yMax })
        for (t in g.ticks) close((g.yMax - t.value) / (g.yMax - g.yMin), t.y, "tick ${t.value}")
    }

    @Test
    fun theDescriptionSaysTheRangeAndTheUsualBandInWords() {
        val text = TrendGeometry.describe("HRV", listOf(50.0, null, 58.0), "milliseconds", 47.0..53.0) { it.toInt().toString() }
        assertEquals("HRV over the last 3 days, 2 days with data, ranging from 50 to 58 milliseconds. Usual range 47 to 53", text)
        assertEquals("HRV chart, no data in the last 3 days", TrendGeometry.describe("HRV", listOf(null, null, null), "milliseconds", null) { it.toString() })
    }
}
