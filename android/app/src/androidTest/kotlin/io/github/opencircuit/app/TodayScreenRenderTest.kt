package io.github.opencircuit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.today.MeasureButtonUi
import io.github.opencircuit.app.today.ReadingUi
import io.github.opencircuit.app.today.SyncStripUi
import io.github.opencircuit.app.today.TODAY_OVERDUE_TAG
import io.github.opencircuit.app.today.TODAY_STRIP_TAG
import io.github.opencircuit.app.today.TodayAction
import io.github.opencircuit.app.today.TodayAlertLevel
import io.github.opencircuit.app.today.TodayOverdueUi
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodayUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Today screen drawn from hand-built states, read back by its text: stored readings with their
 * age and "Last synced"; a fresh install that has nothing yet; a heart rate with no oxygen reading;
 * and the way to the Ring tab when no ring is paired.
 */
@RunWith(AndroidJUnit4::class)
class TodayScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val stored = TodayUiState(
        loading = false,
        heartRate = ReadingUi("Heart rate", "62", "bpm", "as of 13:02 · 3 h ago"),
        spo2 = ReadingUi("SpO₂", "97", "%", "as of 15:47 · 15 min ago"),
        lastSynced = "Last synced 2 h ago",
        ring = "Connected",
    )

    @Test
    fun storedReadingsShowWithTheirAgeAndTheLastSync() {
        compose.setContent { TodayScreen(stored, onAction = {}) }
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithText("as of 13:02 · 3 h ago").assertIsDisplayed()
        compose.onNodeWithText("97").assertIsDisplayed()
        compose.onNodeWithText("as of 15:47 · 15 min ago").assertIsDisplayed()
        compose.onNodeWithText("Last synced 2 h ago").assertIsDisplayed()
        compose.onNodeWithText("Connected").assertIsDisplayed()
    }

    @Test
    fun aFreshInstallSaysWhatIsMissingAndOffersTheRingTab() {
        val actions = mutableListOf<TodayAction>()
        val fresh = TodayUiState(
            loading = false,
            heartRate = ReadingUi("Heart rate", unit = "bpm", note = "Waiting for the first sync"),
            spo2 = ReadingUi("SpO₂", unit = "%", note = "Waiting for the first sync"),
        )
        compose.setContent { TodayScreen(fresh, onAction = { actions += it }) }
        compose.onNodeWithText("Not synced yet").assertIsDisplayed()
        compose.onNodeWithText("No ring yet — pair one on the Ring tab").assertIsDisplayed()
        compose.onNodeWithText("62").assertDoesNotExist()
        compose.onNodeWithText("Open Ring").performClick()
        assertEquals(listOf<TodayAction>(TodayAction.OpenRing), actions)
    }

    @Test
    fun aHeartRateWithoutAnOxygenReadingSaysSo() {
        val hrOnly = stored.copy(spo2 = ReadingUi("SpO₂", unit = "%", note = "No reading yet"))
        compose.setContent { TodayScreen(hrOnly, onAction = {}) }
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithText("No reading yet").assertIsDisplayed()
        compose.onNodeWithText("Open Ring").assertDoesNotExist()
    }

    @Test
    fun whileSyncingTheStripShowsProgressBesideLastSyncedAndMeasureWaits() {
        val syncing = stored.copy(
            heartRate = stored.heartRate.copy(status = "Live heart rate after sync"),
            strip = SyncStripUi("Syncing · All-day channel 120 of 723 records", 120f / 723f),
            spo2Measure = MeasureButtonUi(reason = "Available after sync"),
        )
        compose.setContent { TodayScreen(syncing, onAction = {}) }
        compose.onNodeWithText("Syncing · All-day channel 120 of 723 records").assertIsDisplayed()
        compose.onNodeWithTag(TODAY_STRIP_TAG).assertIsDisplayed()
        compose.onNodeWithText("Last synced 2 h ago").assertIsDisplayed()
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithText("Live heart rate after sync").assertIsDisplayed()
        compose.onNodeWithText("Measure").assertIsNotEnabled()
        compose.onNodeWithText("Available after sync").assertIsDisplayed()
    }

    @Test
    fun theOverdueChipShowsTheRingCardsWordsAndSyncsNow() {
        val actions = mutableListOf<TodayAction>()
        val overdue = stored.copy(
            lastSynced = "Last synced 5 days ago",
            overdue = TodayOverdueUi("Your ring keeps about 7 days of data. Sync now so nothing is lost.", TodayAlertLevel.RED, canSync = true),
        )
        compose.setContent { TodayScreen(overdue, onAction = { actions += it }) }
        compose.onNodeWithTag(TODAY_OVERDUE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Your ring keeps about 7 days of data. Sync now so nothing is lost.").assertIsDisplayed()
        compose.onNodeWithText("Last synced 5 days ago").assertIsDisplayed()
        compose.onNodeWithText("Sync now").performClick()
        assertEquals(listOf<TodayAction>(TodayAction.Refresh), actions)
    }

    @Test
    fun theHerosMeasureStartsSpO2AndStopEndsIt() {
        val actions = mutableListOf<TodayAction>()
        compose.setContent { TodayScreen(stored.copy(spo2Measure = MeasureButtonUi(enabled = true)), onAction = { actions += it }) }
        compose.onNodeWithText("Measure").assertIsEnabled().performClick()
        assertEquals(listOf<TodayAction>(TodayAction.MeasureSpO2), actions)
    }

    @Test
    fun whileSpO2IsMeasuredTheHeroShowsStop() {
        val actions = mutableListOf<TodayAction>()
        val measuring = stored.copy(
            spo2 = stored.spo2.copy(value = "96", status = "Measuring · keep your hand still"),
            spo2Measure = MeasureButtonUi(label = "Stop", enabled = true, stops = true),
        )
        compose.setContent { TodayScreen(measuring, onAction = { actions += it }) }
        compose.onNodeWithText("Measuring · keep your hand still").assertIsDisplayed()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(listOf<TodayAction>(TodayAction.StopMeasure), actions)
    }
}
