package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.onboarding.Destination
import io.github.opencircuit.app.onboarding.LaunchFlow
import io.github.opencircuit.app.onboarding.OnboardingPages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Onboarding is shown once: the first screen is decided at launch from `onboarding.completed.v1`
 * alone, and finishing (Get started) or skipping sets it, so the next launch (a new instance
 * over the same file) opens on the Ring screen. A damaged flag shows onboarding again; a failed
 * write still lets the user in and says so in the log.
 */
class OnboardingFlagTest {

    private val values = InMemoryKeyValues()
    private val logLines = mutableListOf<String>()

    private fun launch() = LaunchFlow(PrefsAppPrefs(values), log = { logLines += it })

    @Test
    fun aFreshInstallOpensOnOnboarding() {
        assertEquals(Destination.Onboarding, launch().start)
    }

    @Test
    fun finishingOnboardingOpensTheRingScreenAndTheNextLaunchSkipsIt() {
        val first = launch()

        assertEquals(Destination.Ring, first.finishOnboarding())

        assertEquals(true, values.raw("onboarding.completed.v1"))
        assertEquals(Destination.Ring, launch().start, "relaunch")
        assertEquals(Destination.Ring, launch().start, "and the one after")
    }

    @Test
    fun aDamagedFlagShowsOnboardingAgain() {
        values.putRaw("onboarding.completed.v1", "yes")

        assertEquals(Destination.Onboarding, launch().start)
    }

    @Test
    fun aFailedWriteStillLetsTheUserInAndIsLogged() {
        values.failWrites = true

        assertEquals(Destination.Ring, launch().finishOnboarding())

        assertEquals(1, logLines.size, logLines.toString())
        assertTrue("onboarding" in logLines.single(), logLines.single())
        assertEquals(Destination.Onboarding, launch().start, "nothing was saved, so it shows again")
    }

    @Test
    fun thereAreFourPagesInUpstreamsOrderWithAndroidCopy() {
        assertEquals(listOf("Welcome to OpenCircuit", "Getting started", "Permissions", "Good to know"), OnboardingPages.all.map { it.title })

        val text = OnboardingPages.all.flatMap { it.paragraphs }.joinToString("\n")
        assertTrue("Health Connect" in text)
        assertTrue("Nearby devices" in text)
        assertTrue("Gen 2, Gen 2 Air, Gen 3" in text)
        assertFalse("Apple Health" in text)
        assertFalse("iPhone" in text)
    }

    @Test
    fun theLastPageCarriesTheDisclaimer() {
        val last = OnboardingPages.all.last().paragraphs.joinToString(" ")

        assertTrue("not affiliated with, authorized, or endorsed by RingConn or JZ_Tech" in last, last)
        assertTrue("not a medical device" in last, last)
    }

    @Test
    fun skipIsOfferedOnTheFirstThreePagesAndGetStartedOnlyOnTheLast() {
        val pages = OnboardingPages.all

        assertEquals(listOf(true, true, true, false), pages.indices.map { OnboardingPages.showsSkip(it) })
        assertEquals(listOf("Continue", "Continue", "Continue", "Get started"), pages.indices.map { OnboardingPages.primaryLabel(it) })
    }
}
