package io.github.opencircuit.app

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.ring.LinkStatePresenter
import io.github.opencircuit.app.today.BrowsableDays
import io.github.opencircuit.app.today.DayBrowser
import io.github.opencircuit.app.today.MeasureButtonUi
import io.github.opencircuit.app.today.PastDayHeroUi
import io.github.opencircuit.app.today.ReadingUi
import io.github.opencircuit.app.today.TODAY_CHIP_TAG
import io.github.opencircuit.app.today.TODAY_HERO_TAG
import io.github.opencircuit.app.today.TODAY_OVERDUE_TAG
import io.github.opencircuit.app.today.TODAY_PAST_DAY_TAG
import io.github.opencircuit.app.today.TODAY_STRIP_TAG
import io.github.opencircuit.app.today.TodayAction
import io.github.opencircuit.app.today.TodayAlertLevel
import io.github.opencircuit.app.today.TodayOverdueUi
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodayUiState
import io.github.opencircuit.app.today.todayDayTag
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ble.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The Today scenarios the design mock-up names that no other render test draws, each from a
 * hand-built state and read back by its text: the live hero, the amber overdue chip, a ring that is
 * not connected, and a past day in the day browser. (Syncing, a fresh install and red overdue are
 * in [TodayScreenRenderTest]; the upgrade notice in [HealthSwitchRenderTest]; a tile's trend in
 * [TileDetailRenderTest]; Edit order in [CaloriesGoalsRenderTest].)
 */
@RunWith(AndroidJUnit4::class)
class TodayScenariosRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val stored = TodayUiState(
        loading = false,
        heartRate = ReadingUi("Heart rate", "62", "bpm", "as of 13:02 · 3 h ago"),
        spo2 = ReadingUi("SpO₂", "97", "%", "as of 07:10 · 9 h ago"),
        lastSynced = "Last synced 2 min ago",
        ring = "Connected",
        spo2Measure = MeasureButtonUi(enabled = true),
    )

    private fun show(state: TodayUiState): MutableList<TodayAction> {
        val actions = mutableListOf<TodayAction>()
        compose.setContent { OpenCircuitTheme { TodayScreen(state, onAction = { actions += it }) } }
        return actions
    }

    @Test
    fun liveTheHeroShowsTheLiveNumberAsOneNodeAScreenReaderHearsPolitely() {
        val live = stored.copy(heartRate = stored.heartRate.copy(value = "71", note = "Live now"), heartRateLive = true)
        show(live)
        compose.onNodeWithText("71").assertIsDisplayed()
        compose.onNodeWithText("Live now").assertIsDisplayed()
        compose.onNodeWithText("Last synced 2 min ago").assertIsDisplayed()
        compose.onNodeWithText("Live heart rate after sync").assertDoesNotExist()
        compose.onNodeWithText("Measure").assertIsEnabled()
        // The label, the number, the unit and "Live now" are one node, and its changes are announced politely.
        compose.onNode(hasText("Heart rate") and hasText("71") and hasText("Live now"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun overdueAmberShowsTheWarningBesideLastSyncedAndSyncsNow() {
        val amber = stored.copy(
            lastSynced = "Last synced 4 days ago",
            ring = LinkStatePresenter.present(LinkState.WaitingForRing, "RingConn").headline,
            spo2Measure = MeasureButtonUi(reason = "Connect the ring to measure"),
            overdue = TodayOverdueUi("Your ring keeps about 7 days of data. Sync soon so nothing is lost.", TodayAlertLevel.AMBER, canSync = true),
        )
        val actions = show(amber)
        compose.onNodeWithTag(TODAY_OVERDUE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Your ring keeps about 7 days of data. Sync soon so nothing is lost.").assertIsDisplayed()
        compose.onNodeWithText("Last synced 4 days ago").assertIsDisplayed()
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithText("Sync now").performClick()
        assertEquals(listOf<TodayAction>(TodayAction.Refresh), actions)
    }

    @Test
    fun disconnectedKeepsTheStoredReadingsAndSaysWhyMeasureIsOff() {
        val disconnected = stored.copy(
            lastSynced = "Last synced 3 h ago",
            ring = LinkStatePresenter.present(LinkState.WaitingForRing, "RingConn").headline,
            spo2Measure = MeasureButtonUi(reason = "Connect the ring to measure"),
        )
        show(disconnected)
        compose.onNodeWithText("Ring unreachable — reconnecting automatically").assertIsDisplayed()
        compose.onNodeWithText("Last synced 3 h ago").assertIsDisplayed()
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithText("97").assertIsDisplayed()
        compose.onNodeWithText("Measure").assertIsNotEnabled()
        compose.onNodeWithText("Connect the ring to measure").assertIsDisplayed()
        compose.onNodeWithTag(TODAY_STRIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TODAY_OVERDUE_TAG).assertDoesNotExist()
        compose.onNodeWithText("Open Ring").assertDoesNotExist()
    }

    @Test
    fun aPastDayShowsItsHeartRateRangeInPlaceOfTheHeroAndTheTodayChipReturns() {
        val today = LocalDate.parse("2026-10-21")
        val past = today.minusDays(2)
        val marked = today.minusDays(5)
        val browser = DayBrowser.present(BrowsableDays(today.minusDays(9), today), past, today, setOf(marked))
        val range = PastDayHeroUi(
            title = "Heart rate on Mon 19 Oct",
            range = "52–112",
            detail = "average 72 · resting 55",
            low = 0.1f,
            average = 0.27f,
            high = 0.6f,
            description = "Heart rate on Monday 19 October: 52 to 112 beats per minute, average 72, resting 55.",
        )
        val actions = show(stored.copy(browser = browser, day = past, pastDay = range))

        compose.onNode(isHeading() and hasText("Monday")).assertIsDisplayed()
        compose.onNodeWithTag(TODAY_HERO_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TODAY_PAST_DAY_TAG).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(range.description)))
        compose.onNodeWithTag(todayDayTag(past)).assertIsSelected()
        val spoken = compose.onNodeWithTag(todayDayTag(marked)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(spoken, spoken.endsWith(", new data just arrived"))
        compose.onNodeWithTag(TODAY_CHIP_TAG).assertIsDisplayed().performClick()
        assertEquals(listOf<TodayAction>(TodayAction.ShowToday), actions)
    }
}
