package io.github.opencircuit.app

import io.github.opencircuit.app.live.LivePoint
import io.github.opencircuit.app.live.liveChartDescription
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a screen reader hears for the live chart. The chart's line colour is its only drawn trend
 * cue, so the description carries the newest reading and the trend in words as well.
 */
class LiveChartDescriptionTest {

    @Test
    fun withNoPointsItSaysItIsWaiting() {
        assertEquals("Live chart, waiting for a reading", liveChartDescription(emptyList()))
    }

    @Test
    fun oneReadingHasNoTrend() {
        assertEquals("Live chart, 1 reading, latest 61", liveChartDescription(listOf(LivePoint(0, 61))))
    }

    @Test
    fun theTrendFollowsTheLastTwoPointsLikeTheLineColour() {
        val falling = listOf(LivePoint(0, 58), LivePoint(2_000, 66), LivePoint(4_000, 63))
        assertEquals("Live chart, 3 readings, latest 63, falling", liveChartDescription(falling))
        assertEquals("Live chart, 2 readings, latest 66, rising", liveChartDescription(falling.take(2)))
        assertEquals(
            "Live chart, 2 readings, latest 66, steady",
            liveChartDescription(listOf(LivePoint(0, 66), LivePoint(2_000, 66))),
        )
    }
}
