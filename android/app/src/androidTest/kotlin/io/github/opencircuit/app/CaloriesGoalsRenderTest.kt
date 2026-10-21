package io.github.opencircuit.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.today.TODAY_CALORIES_TAG
import io.github.opencircuit.app.today.TODAY_EDIT_ORDER_TAG
import io.github.opencircuit.app.today.TODAY_GOALS_TAG
import io.github.opencircuit.app.today.TODAY_LIST_TAG
import io.github.opencircuit.app.today.TodayAction
import io.github.opencircuit.app.today.TodayInput
import io.github.opencircuit.app.today.TodayPresenter
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodaySession
import io.github.opencircuit.app.today.TodayStored
import io.github.opencircuit.app.today.TodayTile
import io.github.opencircuit.app.today.TodayTrends
import io.github.opencircuit.app.today.TodayUiState
import io.github.opencircuit.app.today.todayGoalTag
import io.github.opencircuit.app.today.todayOrderRowTag
import io.github.opencircuit.app.today.todayTileTag
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.GoalHistory
import io.github.opencircuit.ringkit.HRSample
import io.github.opencircuit.store.DayActivityInputs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Calories card, the four goal rings and Edit order, drawn from a state the presenter built and
 * read back by their semantics: the card and each ring are one node saying their whole sentence; a
 * day with no step total says so; Edit order offers Move up / Move down on every section and tile
 * (48 dp, named, the ends disabled) while outside it each tile stays one node with its click.
 */
@RunWith(AndroidJUnit4::class)
class CaloriesGoalsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-09-30T18:00:00Z") // a Wednesday
    private val today = LocalDate.parse("2026-09-30")

    private fun state(steps: Long?, editing: Boolean = false): TodayUiState {
        val walk = (0 until 16).map { HRSample(130, today.atStartOfDay(zone).plusSeconds(9 * 3_600L + it * 150L).toInstant()) }
        val stored = TodayStored(trends = TodayTrends(), day = today, activity = DayActivityInputs(today, walk, steps, emptyList(), null))
        return TodayPresenter.present(TodayInput(stored, TodaySession(link = LinkState.Authenticated), now, zone)).copy(editingOrder = editing)
    }

    private fun description(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    @Test
    fun theCaloriesCardAndEachRingAreOneNodeSayingTheirSentence() {
        val s = state(steps = 6_240)
        compose.setContent { OpenCircuitTheme { TodayScreen(s, onAction = {}) } }

        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(TODAY_CALORIES_TAG))
        compose.onNodeWithTag(TODAY_CALORIES_TAG).assertIsDisplayed().assert(description(s.calories!!.description))
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(TODAY_GOALS_TAG))
        compose.onNodeWithTag(todayGoalTag(GoalHistory.Ring.STEPS)).assertIsDisplayed().assert(description("Steps 6,240 of 8,000, 78 %"))
        for (ring in s.goals!!.rings) compose.onNodeWithTag(todayGoalTag(ring.ring)).assert(description(ring.description))
        compose.onNodeWithText("Steps: connected time only").assertIsDisplayed()
    }

    @Test
    fun aDayWithNoStepTotalSaysStepsNotRecorded() {
        val s = state(steps = null)
        compose.setContent { OpenCircuitTheme { TodayScreen(s, onAction = {}) } }

        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(TODAY_GOALS_TAG))
        compose.onNodeWithTag(todayGoalTag(GoalHistory.Ring.STEPS)).assert(description("Steps not recorded"))
    }

    @Test
    fun editOrderOffersNamedMoveButtonsWithTheEndsDisabled() {
        val actions = mutableListOf<TodayAction>()
        val s = state(steps = 6_240, editing = true)
        compose.setContent { OpenCircuitTheme { TodayScreen(s, onAction = { actions += it }) } }

        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(todayOrderRowTag("readiness")))
        compose.onNodeWithContentDescription("Move Readiness up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Readiness down").assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasContentDescription("Move HRV up"))
        compose.onNodeWithContentDescription("Move HRV up").assertIsEnabled().performClick()
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasContentDescription("Move Goals down"))
        compose.onNodeWithContentDescription("Move Goals down").assertIsNotEnabled()

        assertEquals(
            listOf<TodayAction>(TodayAction.MoveSection(io.github.opencircuit.app.today.DashboardSection.READINESS, +1), TodayAction.MoveTile(TodayTile.Metric.HRV, -1)),
            actions,
        )
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(TODAY_EDIT_ORDER_TAG))
        compose.onNodeWithTag(TODAY_EDIT_ORDER_TAG).assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun outsideEditOrderEachTileIsStillOneNodeWithItsClick() {
        val actions = mutableListOf<TodayAction>()
        val s = state(steps = 6_240)
        compose.setContent { OpenCircuitTheme { TodayScreen(s, onAction = { actions += it }) } }

        compose.onNodeWithTag(TODAY_EDIT_ORDER_TAG).performClick()
        compose.onNodeWithTag(TODAY_LIST_TAG).performScrollToNode(hasTestTag(todayTileTag(TodayTile.Metric.HRV)))
        compose.onNodeWithTag(todayTileTag(TodayTile.Metric.HRV)).assertHasClickAction().performClick()

        assertEquals(listOf(TodayAction.EditOrder(true), TodayAction.OpenDetail(TodayTile.Metric.HRV)), actions)
    }
}
