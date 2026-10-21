package io.github.opencircuit.app

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.ble.LinkState
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.onboarding.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Syncing on a device, end to end through the real app: activity, container (the on-device
 * database), session, triggers, view model and screen, with the debug build's demo ring — which
 * answers each channel's open with its pages released one acknowledgement at a time, then its end
 * report: three synthetic nights on the sleep channel, the days between and since on the all-day
 * channel.
 *
 * With no complete sync recorded for the ring, the link coming up at launch syncs by itself: the
 * card goes to "Last synced just now" with the records stored, three nights and last night, and
 * the link is held afterwards: the switch is on, but it acts only once the user leaves the app.
 * Sync now then syncs again whatever the throttle says (nothing new), and leaving the app closes
 * the link.
 * While a sync runs, both Measure buttons are off and say to wait for it. The app opens on Today
 * and the launch's sync starts from there; the test reads the Ring tab.
 */
@RunWith(AndroidJUnit4::class)
class SyncOnDemoRingTest {

    @get:Rule(order = 0)
    val rememberedDemoRing = object : ExternalResource() {
        override fun before() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp
            // A fresh demo link (its three pages not yet drained by another test in this process).
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
            check(app.container.appPrefs.setOnboardingCompleted()) { "could not record that onboarding was done" }
            check(app.container.rememberedRings.save(DemoRingLink.RING)) { "could not remember the demo ring" }
            check(app.container.appPrefs.setDisconnectAfterSync(true)) { "could not turn the switch on" }
            // No complete sync recorded for the demo ring: the launch's link-up syncs.
            val prefs = SharedPreferencesKeyValues(app.getSharedPreferences("opencircuit", Context.MODE_PRIVATE))
            check(prefs.remove("sync.lastComplete.v1/${RingAddress.normalized(DemoRingLink.RING.address)}")) { "could not clear the last sync" }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theLaunchSyncsTheDemoRingByItselfThenSyncNowSyncsAgain() {
        compose.openTab(Destination.Ring)
        compose.waitUntil(timeoutMillis = 90_000) {
            compose.onAllNodesWithText("Last synced just now").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(" records · complete", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stored on this phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nights").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Last night").performScrollTo().assertIsDisplayed()
        // The switch is on, but the app is in front: the link is held (it closes once the user leaves).
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(linkConnected()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Sync now").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Syncing…").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithText("Syncing…").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Last synced just now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Up to date").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(linkConnected()).assertCountEquals(1)

        // Leaving the app closes the held link.
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val until = System.currentTimeMillis() + 10_000
        while (app.container.ringSessions.current.value?.state?.value == LinkState.Authenticated) {
            check(System.currentTimeMillis() < until) { "the link stayed up after the app was left" }
            Thread.sleep(100)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test
    fun measureWaitsWhileTheLaunchSyncRuns() {
        compose.openTab(Destination.Ring)
        // The launch's sync drains three nights page by page: the link is up and "Syncing…" shows.
        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithText("Syncing…").fetchSemanticsNodes().isNotEmpty() }

        // Both Measure buttons are off, each card saying why; the sync is not interrupted.
        compose.onNodeWithContentDescription("Measure heart rate").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Measure SpO₂").assertIsNotEnabled()
        compose.onAllNodesWithText("Wait for the sync to finish").assertCountEquals(2)

        compose.waitUntil(timeoutMillis = 90_000) {
            compose.onAllNodesWithText("Last synced just now").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Wait for the sync to finish").assertCountEquals(0)
    }
}
