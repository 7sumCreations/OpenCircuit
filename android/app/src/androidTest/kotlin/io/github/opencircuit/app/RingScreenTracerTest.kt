package io.github.opencircuit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end on a device: the real app (activity, container, session controller, view model,
 * screen) with the debug build's demo ring, which connects and reports a 72 % battery. The app
 * opens on the Ring screen only once onboarding is done, so that is recorded first, through the
 * app's own preferences, before the activity starts.
 */
@RunWith(AndroidJUnit4::class)
class RingScreenTracerTest {

    @get:Rule(order = 0)
    val onboardingDone = object : ExternalResource() {
        override fun before() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp
            check(app.container.appPrefs.setOnboardingCompleted()) { "could not record that onboarding was done" }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theDemoRingShowsConnectedWithItsBattery() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Ring · Demo ring").assertIsDisplayed()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("72%", substring = true).assertIsDisplayed()
    }
}
