package io.github.opencircuit.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.demo.DemoRingLink
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Syncing on a device, end to end through the real app: activity, container (the on-device
 * database), session, triggers, view model and screen, with the debug build's demo ring — which
 * answers a sync with three pages released one acknowledgement at a time, then its end report.
 *
 * With no complete sync recorded for the ring, the link coming up at launch syncs by itself: the
 * card goes to "Last synced just now" with the 18 records stored, and the ring is disconnected
 * afterwards (the switch is on). Sync now then syncs again whatever the throttle says: it
 * reconnects, drains (nothing new) and disconnects.
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
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Last synced just now").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("18 records · complete").performScrollTo().assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithText("Sync now").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Last synced just now").performScrollTo().assertIsDisplayed()
    }
}
