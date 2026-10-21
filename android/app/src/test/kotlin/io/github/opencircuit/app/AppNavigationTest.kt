package io.github.opencircuit.app

import io.github.opencircuit.app.onboarding.AppNavigation
import io.github.opencircuit.app.onboarding.Destination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * After onboarding the app has three tabs, Today first, each labelled; back from Ring or Settings
 * returns to Today, back on Today leaves the app (Android's own back), and the ring session runs
 * whichever tab is open — only onboarding comes before it.
 */
class AppNavigationTest {

    @Test
    fun theBarHoldsTodayRingAndSettingsInThatOrderWithTheirLabels() {
        assertEquals(listOf(Destination.Today, Destination.Ring, Destination.Settings), AppNavigation.tabs)
        assertEquals(listOf("Today", "Ring", "Settings"), AppNavigation.tabs.map(AppNavigation::label))
    }

    @Test
    fun backFromRingOrSettingsReturnsToTodayAndBackOnTodayLeavesTheApp() {
        assertEquals(Destination.Today, AppNavigation.back(Destination.Ring))
        assertEquals(Destination.Today, AppNavigation.back(Destination.Settings))
        assertNull(AppNavigation.back(Destination.Today), "Android's own back closes the app")
        assertNull(AppNavigation.back(Destination.Onboarding), "onboarding keeps Android's back")
    }

    @Test
    fun theRingSessionRunsOnEveryScreenButOnboarding() {
        assertFalse(AppNavigation.runsSession(Destination.Onboarding))
        assertTrue(Destination.entries.filter { it != Destination.Onboarding }.all(AppNavigation::runsSession))
    }
}
