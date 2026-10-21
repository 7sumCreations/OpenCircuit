package io.github.opencircuit.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.today.TODAY_DETAIL_CHART_TAG
import io.github.opencircuit.app.today.TODAY_DETAIL_STATS_TAG
import io.github.opencircuit.app.today.TODAY_DETAIL_TABLE_TAG
import io.github.opencircuit.app.today.TODAY_DETAIL_TAG
import io.github.opencircuit.app.today.TileDetailPresenter
import io.github.opencircuit.app.today.TodayAction
import io.github.opencircuit.app.today.TodayInput
import io.github.opencircuit.app.today.TodayPresenter
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodaySession
import io.github.opencircuit.app.today.TodayStored
import io.github.opencircuit.app.today.TodayTile
import io.github.opencircuit.app.today.TodayTrends
import io.github.opencircuit.app.today.TodayUiState
import io.github.opencircuit.app.today.TodayWindowInputs
import io.github.opencircuit.app.today.todayTileTag
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A tile's trend screen drawn from a state the presenter built over synthetic, ring-shaped heart
 * rate, read back by text and semantics: a tile tap asks for its trend; the trend shows the 14 / 30
 * toggle (the open range selected), the chart described in words, MIN / AVG / MAX as one node each,
 * "N of M days", the table of the same rows on request, and back asks to close it.
 */
@RunWith(AndroidJUnit4::class)
class TileDetailRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-21")
    private val now = today.atStartOfDay(zone).plusHours(18).toInstant()

    /** Daytime heart rate on 20 of the last 30 days (none 3–12 days back): 60 + (days back % 5). */
    private fun trends(): TodayTrends {
        val hr = (0 until 30).filter { it !in 3..12 }.flatMap { back ->
            val d = today.minusDays(back.toLong())
            (9..17).map { h -> QuantitySample(kind = MetricKind.HEART_RATE, start = d.atStartOfDay(zone).plusHours(h.toLong()).toInstant(), value = 60.0 + back % 5) }
        }
        return TodayTrends.of(TodayWindowInputs(hr = hr), zone)
    }

    private fun state(days: Int?): TodayUiState {
        val base = TodayPresenter.present(TodayInput(TodayStored(trends = trends()), TodaySession(link = LinkState.Authenticated), now, zone))
        return base.copy(detail = days?.let { TileDetailPresenter.present(trends(), TodayTile.Metric.HEART_RATE, it, today, now, zone) })
    }

    private fun show(state: TodayUiState): MutableList<TodayAction> {
        val actions = mutableListOf<TodayAction>()
        compose.setContent { OpenCircuitTheme { TodayScreen(state, onAction = { actions += it }) } }
        return actions
    }

    @Test
    fun aTileTapAsksForItsTrend() {
        val actions = show(state(null))
        compose.onNodeWithTag(todayTileTag(TodayTile.Metric.HRV)).performScrollTo().performClick()
        assertEquals(listOf<TodayAction>(TodayAction.OpenDetail(TodayTile.Metric.HRV)), actions)
    }

    @Test
    fun fourteenDaysShowTheChartInWordsTheFiguresAndTheTableOnRequest() {
        val s = state(14)
        val detail = s.detail!!
        val actions = show(s)
        compose.onNodeWithTag(TODAY_DETAIL_TAG).assertIsDisplayed()
        compose.onNodeWithText("14 days").assertIsSelected()
        compose.onNodeWithTag(TODAY_DETAIL_CHART_TAG).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(detail.description)))
        compose.onNodeWithTag(TODAY_DETAIL_STATS_TAG).assertIsDisplayed()
        compose.onNode(hasContentDescription("Lowest 60 beats per minute")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Average 62 beats per minute")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Highest 63 beats per minute")).assertIsDisplayed()
        compose.onNodeWithText("4 of 14 days").assertIsDisplayed()

        compose.onNodeWithText("30 days").performClick()
        compose.onNodeWithText("Show values as a table").performScrollTo().performClick()
        compose.onNodeWithTag(TODAY_DETAIL_TABLE_TAG).assertExists()
        compose.onNodeWithText("Fri 16 Oct").assertExists()
        compose.onNodeWithContentDescription("Back to Today").performClick()
        assertEquals(listOf(TodayAction.DetailRange(30), TodayAction.CloseDetail), actions)
    }

    @Test
    fun thirtyDaysCountTheWiderWindow() {
        show(state(30))
        compose.onNodeWithText("30 days").assertIsSelected()
        compose.onNodeWithText("20 of 30 days").assertIsDisplayed()
        compose.onNodeWithText("Heart rate (bpm), last 30 days").assertIsDisplayed()
    }
}
