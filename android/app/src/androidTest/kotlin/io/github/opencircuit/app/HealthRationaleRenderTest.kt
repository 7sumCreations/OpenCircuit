package io.github.opencircuit.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.health.HealthPermissionsRationaleActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The privacy screen Health Connect opens through the manifest alias, launched as the real activity
 * on a device. Its title is a screen title like every other in the app, so it must say so in its
 * semantics rather than leave TalkBack to guess from the font size: the heading property, the one
 * OnboardingScreen, the Ring data card, Connection details and the Measure cards all carry.
 *
 * The activity is exported=false, which only keeps other apps out; instrumentation running in the
 * app's own package launches it normally.
 */
@RunWith(AndroidJUnit4::class)
class HealthRationaleRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<HealthPermissionsRationaleActivity>()

    @Test
    fun theTitleIsDrawnAndNamedAHeading() {
        val title = compose.onNodeWithText(TITLE)
        title.assertIsDisplayed()

        title.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun theScreenSaysWriteOnlyAndNothingLeavesThePhone() {
        compose.onNodeWithText("It never asks to read", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("no internet permission", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("not a medical device", substring = true).performScrollTo().assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "How OpenCircuit uses Health Connect"
    }
}
