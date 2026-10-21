package io.github.opencircuit.app

import io.github.opencircuit.app.onboarding.OnboardingPages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Onboarding's Health Connect lines. The writer ships, so nothing promises it "in a later version";
 * and sending to Health Connect is a switch, OFF until the user turns it on in Settings, so nothing
 * says the data is written there as a matter of course, and the way in is the switch then the
 * card's Connect — no longer a card on the Ring screen.
 */
class OnboardingHealthConnectCopyTest {

    private val lines = OnboardingPages.all.flatMap { it.paragraphs }

    @Test
    fun noOnboardingLineSaysLaterVersion() {
        assertFalse(lines.any { it.contains("later version") }, "stale copy: $lines")
    }

    @Test
    fun noLinePromisesThatDataIsWrittenToHealthConnectByItself() {
        assertFalse(lines.any { it.contains("written only to Health Connect") }, "stale copy: $lines")
        val welcome = OnboardingPages.all.first().paragraphs.joinToString(" ")
        assertTrue("unless you turn" in welcome && "Settings" in welcome, "page 1 says it is opt-in: $welcome")
    }

    @Test
    fun permissionsPageNamesTheSwitchInSettingsThenTheConnectButton() {
        val page = OnboardingPages.all.first { it.title == "Permissions" }
        val health = page.paragraphs.filter { "Health Connect" in it }
        assertEquals(1, health.size, "one Health Connect line: $health")
        val line = health.single()
        assertTrue("Send to Health Connect" in line && "Settings" in line, line)
        assertTrue(line.indexOf("Send to Health Connect") < line.indexOf("Connect Health Connect"), "the switch first, then the card's button: $line")
        assertFalse("Ring screen" in line, "the card is no longer on the Ring screen: $line")
    }
}
