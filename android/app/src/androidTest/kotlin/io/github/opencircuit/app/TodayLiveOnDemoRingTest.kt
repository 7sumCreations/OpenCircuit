package io.github.opencircuit.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.today.TODAY_STRIP_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Live readings on Today, end to end through the real app with the debug build's demo ring (which
 * answers each mode write with `86 00 86` and each `95 00 00` poll with one `0x15` frame): the
 * launch's sync shows on the strip with Measure waiting for it; within 15 s of the drain's end the
 * hero is in live mode, and the number appears on lock; a tap on Measure reads SpO₂, and Stop ends
 * it. Outside the night only: inside the night window (the automatic syncs' own) no automatic
 * measure starts, and that is asserted instead — the test reads which applies from the session.
 */
@RunWith(AndroidJUnit4::class)
class TodayLiveOnDemoRingTest {

    private val app: OpenCircuitApp
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp

    private var disconnectAfterSyncWas = true

    @get:Rule(order = 0)
    val rememberedDemoRing = object : ExternalResource() {
        override fun before() {
            // A fresh demo link (its pages not yet drained by another test in this process).
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
            check(app.container.appPrefs.setOnboardingCompleted()) { "could not record that onboarding was done" }
            check(app.container.rememberedRings.save(DemoRingLink.RING)) { "could not remember the demo ring" }
            disconnectAfterSyncWas = app.container.appPrefs.disconnectAfterSync
            // ON, its default: the link is held while the app is in front.
            check(app.container.appPrefs.setDisconnectAfterSync(true)) { "could not turn the switch on" }
            // No complete sync recorded for the demo ring: the launch's link-up syncs.
            val prefs = SharedPreferencesKeyValues(app.getSharedPreferences("opencircuit", Context.MODE_PRIVATE))
            check(prefs.remove("sync.lastComplete.v1/${RingAddress.normalized(DemoRingLink.RING.address)}")) { "could not clear the last sync" }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun stopTheSession() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
        check(app.container.appPrefs.setDisconnectAfterSync(disconnectAfterSyncWas)) { "could not restore the switch" }
    }

    private fun session() = checkNotNull(app.container.ringSessions.current.value) { "no ring session" }

    @Test
    fun theStripShowsTheSyncThenTheHeroGoesLiveAndSpO2MeasuresOnATap() {
        // The launch's sync, on the strip, with Measure waiting for it.
        // Seen together in one frame: the strip's bar and Measure's reason.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Available after sync").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TODAY_STRIP_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 60_000) { session().sync.state.value.last?.commit != null }
        val drainEnded = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Last synced", substring = true).fetchSemanticsNodes().isNotEmpty() }

        val night = runBlocking { session().triggers?.nightQuietNow() == true }
        if (!night) {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText("Live · warming up").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Live now").fetchSemanticsNodes().isNotEmpty()
            }
            val live = SystemClock.elapsedRealtime() - drainEnded
            assertTrue("the hero went live ${live} ms after the drain's end", live <= 15_000)
            assertEquals(LiveMode.HEART_RATE, session().liveMeasure.state.value.mode)
            // The number on lock (two warm-up polls, then a resting value).
            compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithText("Live now").fetchSemanticsNodes().isNotEmpty() }
        } else {
            SystemClock.sleep(5_000)
            assertNull("inside the night no automatic measure starts", session().liveMeasure.state.value.mode)
        }

        // SpO₂ by tap: it takes the automatic heart rate over, and Stop ends it.
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Measure").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Measure").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Measuring · keep your hand still").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(LiveMode.SPO2, session().liveMeasure.state.value.mode)
        compose.waitUntil(timeoutMillis = 20_000) { session().liveMeasure.state.value.newest != null }
        compose.onNodeWithText("Stop").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session().liveMeasure.state.value.mode != LiveMode.SPO2 }
    }
}
