package io.github.opencircuit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.onboarding.Destination
import io.github.opencircuit.app.ui.tabTag
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.open
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The tracer on a device, end to end through the real app — activity, container, the on-device
 * database, ring sessions — with the debug build's demo ring: after onboarding the app opens on
 * Today; the heart rate already stored shows at once, before the launch's sync can finish; the
 * remembered demo ring connects with no visit to the Ring tab; and back from the Ring tab returns
 * to Today.
 *
 * The stored reading is written before launch through the store's own API (a heart rate a minute
 * old, newer than anything the demo ring's history holds), so Today must read it from the store.
 */
@RunWith(AndroidJUnit4::class)
class TodayTracerOnDemoRingTest {

    private val app: OpenCircuitApp
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp

    @get:Rule(order = 0)
    val storedAndRemembered = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
            check(app.container.appPrefs.setOnboardingCompleted()) { "could not record that onboarding was done" }
            check(app.container.rememberedRings.save(DemoRingLink.RING)) { "could not remember the demo ring" }
            // The link stays up after the launch's sync, so "Connected" is not a race with the disconnect.
            disconnectAfterSyncWas = app.container.appPrefs.disconnectAfterSync
            check(app.container.appPrefs.setDisconnectAfterSync(false)) { "could not turn the switch off" }
            runBlocking {
                val db = StoreFactory.open(app)
                try {
                    val now = Instant.now()
                    LocalStore(db).insertLiveSamples(listOf(QuantitySample(MetricKind.HEART_RATE, now.minusSeconds(60), value = STORED_BPM)), now)
                } finally {
                    db.close()
                }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private var disconnectAfterSyncWas = true

    @After
    fun stopTheSession() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
        check(app.container.appPrefs.setDisconnectAfterSync(disconnectAfterSyncWas)) { "could not restore the switch" }
    }

    @Test
    fun todayOpensWithTheStoredHeartRateAndTheDemoRingConnectsFromIt() {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(STORED_BPM.toInt().toString()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("shown before any sync of this launch finished", null, app.container.ringSessions.current.value?.sync?.state?.value?.last)
        compose.onNodeWithTag(tabTag(Destination.Today)).assertIsSelected()
        compose.onNodeWithText("min ago", substring = true).assertIsDisplayed()

        // The remembered ring connects while Today is the only screen ever shown.
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(linkConnected()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(LinkState.Authenticated, app.container.ringSessions.current.value?.state?.value)

        compose.openTab(Destination.Ring)
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Ring · Demo ring").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(STORED_BPM.toInt().toString()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ring · Demo ring").assertDoesNotExist()
    }

    private companion object {
        /** A heart rate no demo history page carries as its newest reading. */
        const val STORED_BPM = 77.0
    }
}
