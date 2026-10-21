package io.github.opencircuit.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.today.TODAY_LIST_TAG
import io.github.opencircuit.app.today.TODAY_PLACEHOLDER_TAG
import io.github.opencircuit.app.today.TODAY_READINESS_TAG
import io.github.opencircuit.app.today.TODAY_SYNTHESIS_TAG
import io.github.opencircuit.app.today.TodayInput
import io.github.opencircuit.app.today.TodayNight
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
import io.github.opencircuit.store.SyncLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Today's dashboard drawn from a state the presenter built over a synthetic, ring-shaped week, read
 * back by its semantics: every tile is one node saying its whole sentence; the readiness ring says
 * its score, tier and factors; the synthesis line shows; placeholders hold the grid before the first
 * load; at 200 % font the tiles stack one to a row and keep their whole height; dark mode draws.
 */
@RunWith(AndroidJUnit4::class)
class TodayDashboardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private fun day(o: Int) = LocalDate.parse("2026-09-30").plusDays(o.toLong()).atStartOfDay(zone).toInstant()

    private fun state(): TodayUiState {
        val nights = (-5..0).map { o ->
            val wake = day(o).plusSeconds(6 * 3_600L)
            TodayNight(day(o), 420, if (o == 0) 82 else 70, if (o == 0) 34 else 40, 33.5, wake.minusSeconds(8 * 3_600L), wake)
        }
        val hrv = (-5..0).map { o -> QuantitySample(kind = MetricKind.HRV_SDNN, start = day(o).plusSeconds(7_200), value = if (o == 0) 58.0 else 50.0) }
        val hr = (-5..0).flatMap { o -> (8..20).map { h -> QuantitySample(kind = MetricKind.HEART_RATE, start = day(o).plusSeconds(h * 3_600L), value = 60.0 + h) } }
            .filter { it.start <= now }
        val trends = TodayTrends.of(TodayWindowInputs(nights = nights, hrv = hrv, hr = hr), zone)
        val log = listOf(SyncLogEntry(startedAt = now.minusSeconds(660), finishedAt = now.minusSeconds(600), outcome = "COMPLETE"))
        return TodayPresenter.present(TodayInput(TodayStored(trends = trends), TodaySession(link = LinkState.Authenticated, log = log), now, zone))
    }

    private fun description(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    @Test
    fun everyTileIsOneNodeThatSaysItsWholeSentence() {
        val s = state()
        compose.setContent { OpenCircuitTheme { TodayScreen(s, onAction = {}) } }
        assertEquals(8, s.tiles.size)
        for (tile in s.tiles) {
            compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(todayTileTag(tile.metric)))
            compose.onNodeWithTag(todayTileTag(tile.metric)).assertIsDisplayed().assert(description(tile.description))
        }
        val hrv = s.tiles.single { it.metric == TodayTile.Metric.HRV }.description
        assertTrue(hrv, hrv.startsWith("HRV. 58 milliseconds, overnight avg."))
    }

    @Test
    fun theReadinessRingAndTheSynthesisLineShow() {
        val s = state()
        compose.setContent { OpenCircuitTheme { TodayScreen(s, onAction = {}) } }
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(TODAY_READINESS_TAG))
        compose.onNodeWithTag(TODAY_READINESS_TAG).assertIsDisplayed().assert(description(s.readiness!!.description))
        assertTrue(s.readiness!!.description, s.readiness!!.description.startsWith("Readiness, estimate, 79 out of 100, Good."))
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(TODAY_SYNTHESIS_TAG))
        compose.onNodeWithText(s.synthesis!!).assertIsDisplayed()
        compose.onNodeWithText("Wednesday 30 September").assertIsDisplayed()
    }

    @Test
    fun beforeTheFirstLoadPlaceholdersHoldTheGrid() {
        compose.setContent { OpenCircuitTheme { TodayScreen(TodayUiState(), onAction = {}) } }
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(TODAY_PLACEHOLDER_TAG))
        assertTrue(compose.onAllNodesWithTag(TODAY_PLACEHOLDER_TAG).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun atTwiceTheFontTheTilesStackOneToARowAtTheirWholeHeight() {
        val s = state()
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) { OpenCircuitTheme { TodayScreen(s, onAction = {}) } }
        }
        val first = todayTileTag(TodayTile.Metric.HEART_RATE)
        val second = todayTileTag(TodayTile.Metric.HRV)
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(second))
        val a = compose.onNodeWithTag(first).getBoundsInRoot()
        val b = compose.onNodeWithTag(second).getBoundsInRoot()
        assertEquals("one tile to a row: the second sits under the first", a.left, b.left)
        assertTrue("the second starts below the first", b.top >= a.bottom)
        assertEquals("the tile grew with the font", 368f, (b.bottom - b.top).value, 1f)
    }

    @Test
    fun darkModeDraws() {
        val s = state()
        compose.setContent { OpenCircuitTheme(dark = true) { TodayScreen(s, onAction = {}) } }
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(todayTileTag(TodayTile.Metric.STEPS)))
        compose.onNodeWithTag(todayTileTag(TodayTile.Metric.STEPS)).assertIsDisplayed()
    }
}
