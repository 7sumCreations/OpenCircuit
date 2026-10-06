package io.github.opencircuit.app

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.demo.DemoRingScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First run end to end on a device, with the real app (activity, container, preferences, ring
 * sessions) and the debug build's demo ring: a fresh install shows onboarding; skipping it lands
 * on the Ring screen; a relaunch skips onboarding; and once a ring is remembered, the relaunched
 * app reconnects it by its address with no scan and shows it connected.
 *
 * The ring is remembered the way a finished pairing leaves it (the saved-ring preference): the
 * pairing sheet itself is Android's and cannot be driven here.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunEndToEndTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: OpenCircuitApp
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp

    private val scanner: DemoRingScanner get() = app.container.ringScanner as DemoRingScanner

    /** As on a fresh install: no preferences (other tests leave onboarding done and a ring saved) and no ring session. */
    @Before
    fun freshInstall() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
        check(app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()) { "could not clear the preferences" }
    }

    @Test
    fun onboardingOnceThenARelaunchReconnectsTheRememberedRingWithoutAScan() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Welcome to OpenCircuit").assertExists()
            compose.onNodeWithText("Skip").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Scan & connect").fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue("Skip records onboarding as done", app.container.appPrefs.onboardingCompleted)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Welcome to OpenCircuit").assertDoesNotExist()
            compose.onNodeWithText("Scan & connect").assertExists()
        }

        // A ring paired earlier; the next launch must reconnect it, not scan for it.
        check(app.container.rememberedRings.save(DemoRingLink.RING)) { "could not remember the demo ring" }
        val scansBefore = scanner.scansStarted

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Welcome to OpenCircuit").assertDoesNotExist()
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Demo ring").assertExists()
        }
        assertEquals("no scan on the way to the remembered ring", scansBefore, scanner.scansStarted)
        assertEquals(DemoRingLink.RING, app.container.ringSessions.current.value?.link?.ring)
    }

    private companion object {
        /** The app's preferences file, typed here rather than read from the code. */
        const val PREFS_FILE = "opencircuit"
    }
}
