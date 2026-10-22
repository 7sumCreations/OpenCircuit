package io.github.opencircuit.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.debug.TestWallClock
import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.today.TODAY_STRIP_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Live readings on Today, end to end through the real app with the debug build's demo ring (which
 * answers each mode write with `86 00 86` and each `95 00 00` poll with one `0x15` frame). The
 * phone's wall clock is fixed for each test, in the device's own zone, so the result never depends
 * on the hour the suite happens to run: the demo's nights land at 08:27 local, and the night
 * window (the automatic syncs' own) is quiet from about an hour before the usual onset until six
 * hours after the usual wake.
 *
 * At 18:00 the launch's sync shows on the strip with Measure waiting for it; within 15 s of the
 * drain's end the hero is in live mode, and the number appears on lock; a tap on Measure reads
 * SpO₂, and Stop ends it. At 03:00, inside the night, no automatic measure starts.
 */
@RunWith(AndroidJUnit4::class)
class TodayLiveOnDemoRingTest {

    /** Fixes the wall clock at the hour named by the test's [AtLocalHour] before the app starts, and frees it after. */
    class PinnedWallClock : TestWatcher() {
        override fun starting(description: Description) {
            val hour = checkNotNull(description.getAnnotation(AtLocalHour::class.java)) { "the test names no hour" }.hour
            // Yesterday, so nothing this test stores is dated after the real now: other tests read the
            // newest stored reading, and a later-than-now one would shadow theirs.
            val at = LocalDate.now(ZoneId.systemDefault()).minusDays(1).atTime(hour, 0).atZone(ZoneId.systemDefault())
            TestWallClock.pinTo(at.toInstant().toEpochMilli())
        }

        override fun finished(description: Description) {
            TestWallClock.reset()
        }
    }

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    annotation class AtLocalHour(val hour: Int)

    private val app: OpenCircuitApp
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp

    private var disconnectAfterSyncWas = true

    @get:Rule(order = 0)
    val pinnedClock = PinnedWallClock()

    @get:Rule(order = 1)
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

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun stopTheSession() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
        check(app.container.appPrefs.setDisconnectAfterSync(disconnectAfterSyncWas)) { "could not restore the switch" }
    }

    private fun session() = checkNotNull(app.container.ringSessions.current.value) { "no ring session" }

    /** The launch's sync, on the strip with Measure waiting for it; returns when its drain has ended. */
    private fun syncShowsThenEnds(): Long {
        // Seen together in one frame: the strip's bar and Measure's reason.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Available after sync").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TODAY_STRIP_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 60_000) { session().sync.state.value.last?.commit != null }
        val drainEnded = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Last synced", substring = true).fetchSemanticsNodes().isNotEmpty() }
        return drainEnded
    }

    @Test
    @AtLocalHour(18)
    fun byDayTheHeroGoesLiveWithin15sOfTheSyncAndSpO2MeasuresOnATap() {
        val drainEnded = syncShowsThenEnds()
        assertFalse("18:00 is outside the night window", runBlocking { session().triggers?.nightQuietNow() == true })

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Live · warming up").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Live now").fetchSemanticsNodes().isNotEmpty()
        }
        val live = SystemClock.elapsedRealtime() - drainEnded
        Log.i("TodayLiveOnDemoRingTest", "the hero went live $live ms after the drain's end")
        assertTrue("the hero went live $live ms after the drain's end", live <= 15_000)
        assertEquals(LiveMode.HEART_RATE, session().liveMeasure.state.value.mode)
        // The number on lock (two warm-up polls, then a resting value).
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithText("Live now").fetchSemanticsNodes().isNotEmpty() }

        // SpO₂ by tap: it takes the automatic heart rate over, and Stop ends it.
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Measure").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Measure").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Measuring · keep your hand still").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(LiveMode.SPO2, session().liveMeasure.state.value.mode)
        compose.waitUntil(timeoutMillis = 20_000) { session().liveMeasure.state.value.newest != null }
        compose.onNodeWithText("Stop").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session().liveMeasure.state.value.mode != LiveMode.SPO2 }
    }

    @Test
    @AtLocalHour(3)
    fun inTheNightNoAutomaticMeasureStarts() {
        syncShowsThenEnds()
        assertTrue("03:00 is inside the night window", runBlocking { session().triggers?.nightQuietNow() == true })

        // Longer than the day's 15 s: nothing may start, however long it is given.
        SystemClock.sleep(20_000)
        assertNull("inside the night no automatic measure starts", session().liveMeasure.state.value.mode)
    }
}
