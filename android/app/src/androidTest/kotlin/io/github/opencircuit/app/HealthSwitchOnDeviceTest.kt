package io.github.opencircuit.app

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.health.HEALTH_CONNECT_CARD_TAG
import io.github.opencircuit.app.health.HealthUpgradeNotice
import io.github.opencircuit.app.onboarding.Destination
import io.github.opencircuit.app.settings.SETTINGS_HEALTH_SWITCH_TAG
import io.github.opencircuit.app.today.HEALTH_NOTICE_TITLE
import io.github.opencircuit.app.today.TODAY_HEALTH_NOTICE_TAG
import io.github.opencircuit.app.today.TodayScreen
import io.github.opencircuit.app.today.TodayViewModel
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * The Health Connect switch through the real app on a device: with the switch OFF (a fresh
 * install's default) the Settings tab shows it off and nothing of the Health Connect card — no
 * Connect anywhere; turning it on draws the card and the app stays in front (no permission screen
 * opens by itself); turning it off again hides the card and is saved.
 */
@RunWith(AndroidJUnit4::class)
class HealthSwitchOnDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: OpenCircuitApp
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp

    /** The app's notice flag before this test: using the switch dismisses the notice, and that must not outlive the test. */
    private var noticeDismissed: Boolean = false

    @Before
    fun offAsOnAFreshInstall() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
        check(app.container.appPrefs.setOnboardingCompleted()) { "could not record that onboarding was done" }
        noticeDismissed = app.container.appPrefs.healthUpgradeNoticeDismissed
        app.container.healthSwitch.set(false)
    }

    @After
    fun backToOff() {
        app.container.healthSwitch.set(false)
        restoreNoticeFlag(app, noticeDismissed)
    }

    @Test
    fun settingsShowsTheSwitchOffWithNoCardAndTurningItOnDrawsTheCardWithoutAPrompt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("Connect Health Connect").assertDoesNotExist()
            compose.openTab(Destination.Settings)
            compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).assertIsOff()
            compose.onNodeWithTag(HEALTH_CONNECT_CARD_TAG).assertDoesNotExist()
            compose.onNodeWithText("Connect Health Connect").assertDoesNotExist()

            compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag(HEALTH_CONNECT_CARD_TAG).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).assertIsOn()
            assertEquals("ON is saved", true, app.container.appPrefs.healthWriteEnabled)
            compose.waitForIdle()
            assertEquals("no permission screen opened by itself", Lifecycle.State.RESUMED, scenario.state)

            compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag(HEALTH_CONNECT_CARD_TAG).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag(SETTINGS_HEALTH_SWITCH_TAG).assertIsOff()
            assertFalse("OFF is saved", app.container.appPrefs.healthWriteEnabled)
        }
    }
}

/**
 * Puts the app's own "notice dismissed" flag back as it was before a test that used the switch
 * (using it dismisses the notice), so a later check of the notice on this device is not silently
 * skipped by an earlier test.
 */
internal fun restoreNoticeFlag(app: OpenCircuitApp, dismissedBefore: Boolean) {
    if (dismissedBefore) return
    // The app's preferences file ("opencircuit") and the notice's key, typed here rather than read from the code.
    check(app.getSharedPreferences("opencircuit", Context.MODE_PRIVATE).edit().remove("health.upgradeNotice.dismissed.v1").commit()) {
        "could not restore the notice flag"
    }
}

/**
 * The one-time notice across a restart, on a device: an install that used Health Connect before
 * (its store flag stands in here) sees it on Today; Dismiss hides it; a new notice and view model
 * over a new preferences object on the same file — nothing carried in memory — never show it, not
 * even on the first frame. The test's own preferences file is cleared before and after.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeNoticeSurvivesRestartTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scopes = mutableListOf<CoroutineScope>()

    private fun prefs() = PrefsAppPrefs(SharedPreferencesKeyValues(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

    private fun today(): TodayViewModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        val notice = HealthUpgradeNotice(prefs(), usedBefore = { true }, scope, log = {})
        return TodayViewModel(source = null, scope = scope, zone = { ZoneId.of("UTC") }, clockTicks = flowOf(Unit), healthNotice = notice)
    }

    @Before
    fun clean() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        clean()
    }

    @Test
    fun dismissedOnceItNeverShowsAgain() {
        lateinit var vm: TodayViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = today() }
        compose.setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            TodayScreen(state, onAction = vm::onAction)
        }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag(TODAY_HEALTH_NOTICE_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dismiss").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag(TODAY_HEALTH_NOTICE_TAG).fetchSemanticsNodes().isEmpty() }

        // The restart: everything let go, new objects over the same file.
        scopes.forEach { it.cancel() }
        lateinit var again: TodayViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { again = today() }
        assertFalse("not on the first frame", again.uiState.value.healthNotice)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse("nor after", again.uiState.value.healthNotice)
        compose.onNodeWithText(HEALTH_NOTICE_TITLE).assertDoesNotExist()
    }

    private companion object {
        const val PREFS = "upgrade-notice-test"
    }
}
