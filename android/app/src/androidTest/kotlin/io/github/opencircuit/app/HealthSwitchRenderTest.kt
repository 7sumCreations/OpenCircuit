package io.github.opencircuit.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.health.HEALTH_CONNECT_CARD_TAG
import io.github.opencircuit.app.health.HealthAccessState
import io.github.opencircuit.app.health.HealthCardAction
import io.github.opencircuit.app.health.HealthFlushState
import java.time.Instant
import io.github.opencircuit.app.health.HealthCardUi
import io.github.opencircuit.app.settings.SETTINGS_HEALTH_SWITCH_TAG
import io.github.opencircuit.app.settings.SettingsAction
import io.github.opencircuit.app.settings.SettingsPresenter
import io.github.opencircuit.app.settings.SettingsScreen
import io.github.opencircuit.app.settings.SettingsUiState
import io.github.opencircuit.app.today.HEALTH_NOTICE_BODY
import io.github.opencircuit.app.today.HEALTH_NOTICE_TITLE
import io.github.opencircuit.app.today.ReadingUi
import io.github.opencircuit.app.today.TODAY_HEALTH_NOTICE_TAG
import io.github.opencircuit.app.today.TodayAction
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodayUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Health Connect switch and the upgrade notice, drawn from hand-built states and read back by
 * their text and semantics: Settings with the switch OFF draws the switch and its line and nothing
 * of the card (no Connect); a tap on the row asks to turn it on; ON draws the Health Connect card and routes
 * its buttons. Today draws the one-time notice only when asked to, with Dismiss and Open Settings.
 */
@RunWith(AndroidJUnit4::class)
class HealthSwitchRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offDrawsTheSwitchAndItsLineAndNothingOfTheCard() {
        val actions = mutableListOf<SettingsAction>()
        compose.setContent { SettingsScreen(SettingsUiState(), onAction = { actions += it }) }

        compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).assertIsDisplayed().assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNodeWithText("Send to Health Connect").assertIsDisplayed()
        compose.onNodeWithText(SettingsPresenter.OFF_LINE).assertIsDisplayed()
        compose.onNodeWithText("What was already written stays in Health Connect", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(HEALTH_CONNECT_CARD_TAG).assertDoesNotExist()
        compose.onNodeWithText("Connect Health Connect").assertDoesNotExist()

        compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).performClick()
        assertEquals(listOf<SettingsAction>(SettingsAction.SetHealthWrite(true)), actions)
    }

    @Test
    fun onDrawsTheCardAndRoutesItsButtons() {
        val actions = mutableListOf<SettingsAction>()
        val notConnected = HealthCardUi(headline = "Not connected", button = "Connect Health Connect", action = HealthCardAction.CONNECT, buttonEnabled = true)
        compose.setContent {
            SettingsScreen(SettingsUiState(healthOn = true, healthLine = SettingsPresenter.ON_LINE, healthCard = notConnected), onAction = { actions += it })
        }

        compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).assertIsOn()
        compose.onNodeWithText(SettingsPresenter.ON_LINE).assertIsDisplayed()
        compose.onNodeWithTag(HEALTH_CONNECT_CARD_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect Health Connect").performScrollTo().performClick()
        compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).performClick()
        assertEquals(listOf(SettingsAction.ConnectHealth, SettingsAction.SetHealthWrite(false)), actions)
    }

    @Test
    fun aChangeThatCouldNotBeSavedSaysSo() {
        compose.setContent { SettingsScreen(SettingsUiState(saveError = SettingsPresenter.ON_NOT_SAVED), onAction = {}) }
        compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).assertIsOff()
        compose.onNodeWithText("Could not save the setting, so it stays off. Try again.").assertIsDisplayed()
    }

    /** Turned ON before Health Connect has been read: "Checking…", and no button to tap. */
    @Test
    fun checkingDrawsNoButton() {
        compose.setContent {
            SettingsScreen(SettingsPresenter.present(true, HealthAccessState(), HealthFlushState(), Instant.EPOCH, saveError = null), onAction = {})
        }
        compose.onNodeWithText("Checking Health Connect…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect Health Connect").assertDoesNotExist()
        compose.onNodeWithText("Write to Health Connect now").assertDoesNotExist()
    }

    private val today = TodayUiState(
        loading = false,
        heartRate = ReadingUi("Heart rate", "62", "bpm", "as of 13:02 · 3 h ago"),
        spo2 = ReadingUi("SpO₂", "97", "%", "as of 15:47 · 15 min ago"),
        lastSynced = "Last synced 2 h ago",
    )

    @Test
    fun todayDrawsTheNoticeWithItsTwoButtons() {
        val actions = mutableListOf<TodayAction>()
        compose.setContent { TodayScreen(today.copy(healthNotice = true), onAction = { actions += it }) }

        compose.onNodeWithTag(TODAY_HEALTH_NOTICE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(HEALTH_NOTICE_TITLE).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(HEALTH_NOTICE_BODY).assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Open Settings").performClick()
        assertEquals(listOf(TodayAction.DismissHealthNotice, TodayAction.OpenSettings), actions)
    }

    @Test
    fun todayWithoutTheNoticeDrawsNone() {
        compose.setContent { TodayScreen(today, onAction = {}) }
        compose.onNodeWithText("62").assertIsDisplayed()
        compose.onNodeWithTag(TODAY_HEALTH_NOTICE_TAG).assertDoesNotExist()
        compose.onNodeWithText(HEALTH_NOTICE_TITLE).assertDoesNotExist()
    }
}
