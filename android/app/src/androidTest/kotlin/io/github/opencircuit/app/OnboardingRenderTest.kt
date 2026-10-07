package io.github.opencircuit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.onboarding.OnboardingScreen
import io.github.opencircuit.app.ui.OpenCircuitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The onboarding pages drawn on a device: four pages in order with the Android copy, Continue
 * walking them, Skip on the first three, the disclaimer and Get started on the last, and both
 * ways out calling back once. The screen takes nothing that could ask for a permission.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var done = 0

    private fun show() {
        compose.setContent { OpenCircuitTheme { OnboardingScreen(onDone = { done++ }) } }
    }

    private fun next() {
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
    }

    @Test
    fun continueWalksTheFourPagesAndGetStartedFinishes() {
        show()

        compose.onNodeWithText("Welcome to OpenCircuit").assertIsDisplayed()
        compose.onNodeWithText("Health Connect", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 1 of 4").assertIsDisplayed()
        next()
        compose.onNodeWithText("Getting started").assertIsDisplayed()
        compose.onNodeWithText("force-stop it", substring = true).assertIsDisplayed()
        next()
        compose.onNodeWithText("Permissions").assertIsDisplayed()
        compose.onNodeWithText("Nearby devices (Bluetooth)", substring = true).assertIsDisplayed()
        next()
        compose.onNodeWithText("Good to know").assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 4 of 4").assertIsDisplayed()
        compose.onNodeWithText("not affiliated with, authorized, or endorsed by RingConn or JZ_Tech", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("not a medical device", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithText("Continue").assertDoesNotExist()
        assertEquals(0, done)

        compose.onNodeWithText("Get started").performClick()

        assertEquals(1, done)
    }

    @Test
    fun skipOnTheFirstPageFinishesAtOnce() {
        show()

        compose.onNodeWithText("Skip").performClick()

        assertEquals(1, done)
    }

    @Test
    fun skipOnTheThirdPageFinishesToo() {
        show()
        next()
        next()

        compose.onNodeWithText("Skip").performClick()

        assertEquals(1, done)
    }

    @Test
    fun noPageMentionsApplesHealthApp() {
        show()
        repeat(3) {
            compose.onNodeWithText("Apple Health", substring = true).assertDoesNotExist()
            next()
        }
        compose.onNodeWithText("Apple Health", substring = true).assertDoesNotExist()
    }
}
