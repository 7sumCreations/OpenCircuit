package io.github.opencircuit.app.today

import io.github.opencircuit.app.ui.charts.TrendChartGutters
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trend chart's label gutters (the tick values at the right, the day labels underneath) grow
 * with the font, as the labels drawn in them do, so a large font does not push them off the chart.
 */
class TrendChartGuttersTest {

    @Test
    fun atTheDefaultFontTheGuttersAreTheirDesignedSize() {
        assertEquals(40f, TrendChartGutters.rightDp(1f))
        assertEquals(22f, TrendChartGutters.bottomDp(1f))
    }

    @Test
    fun atTwiceTheFontTheGuttersAreTwiceAsLarge() {
        assertEquals(80f, TrendChartGutters.rightDp(2f))
        assertEquals(44f, TrendChartGutters.bottomDp(2f))
    }

    @Test
    fun aFontSmallerThanTheDefaultKeepsTheDesignedSize() {
        assertEquals(40f, TrendChartGutters.rightDp(0.85f))
        assertEquals(22f, TrendChartGutters.bottomDp(0.85f))
    }
}
