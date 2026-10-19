package io.github.opencircuit.app

import io.github.opencircuit.app.onboarding.OnboardingPages
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Health Connect writer ships, so onboarding must not promise it "in a later version". */
class OnboardingHealthConnectCopyTest {

    @Test
    fun noOnboardingLineSaysLaterVersion() {
        val lines = OnboardingPages.all.flatMap { it.paragraphs }
        assertFalse(lines.any { it.contains("later version") }, "stale copy: $lines")
    }

    @Test
    fun permissionsPageNamesTheConnectButton() {
        val page = OnboardingPages.all.first { it.title == "Permissions" }
        assertTrue(page.paragraphs.any { it.contains("Connect Health Connect") })
    }
}
