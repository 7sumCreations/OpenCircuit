package io.github.opencircuit.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.health.HEALTH_CONNECT_CARD_TAG
import io.github.opencircuit.app.health.HealthCardAction
import io.github.opencircuit.app.health.HealthCardUi
import io.github.opencircuit.app.health.HealthConnectCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Ring screen's Health Connect card, drawn from a [HealthCardUi] exactly as the presenter hands
 * one over. The presenter's own states are asserted JVM-side (`HealthCardStateTest`,
 * `HealthCardAmberTest`, `HealthCardCompleteTest`); nothing here re-derives them — what these tests
 * pin is that the card DRAWS what it is given: every label/value row of the connected state, the
 * amber warning only when there is one, the notes, the button's enabled state, and the card's own
 * title named as a heading.
 *
 * The gap this closes (handed from the Phase 5.6 UI review): every instrumented render test left
 * `RingUiState.health` null, so the composable was never drawn at all — the connected-state rows
 * had no render proof, and the heading invariant the 5.6 fix established app-wide was asserted at
 * every screen title except this card's, because no test rendered it. The card takes `ui` as a
 * value, so no Health Connect fixture, grant, or store is needed to draw it.
 */
@RunWith(AndroidJUnit4::class)
class HealthCardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun draw(ui: HealthCardUi, onAction: (HealthCardAction) -> Unit = {}) {
        compose.setContent { HealthConnectCard(ui, onAction) }
    }

    /** The card's own title: a heading like every other screen title, and the only one in the card. */
    @Test
    fun theCardNamesItsTitleAHeadingAndNothingElse() {
        draw(HealthCardUi(headline = "Connected", rows = listOf("Last written" to "just now")))

        compose.onNodeWithText("Health Connect").assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertEquals(
            "one heading and only one: a card that makes every line a heading is as hostile to TalkBack as one whose title says nothing",
            1,
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().size,
        )
    }

    /** The connected state's label/value rows: each one drawn, both halves of it, in the card. */
    @Test
    fun theConnectedStateDrawsEveryLabelAndValueRow() {
        draw(
            HealthCardUi(
                headline = "Connected",
                rows = listOf("Last written" to "just now", "Written this sync" to "4,312 records"),
                button = "Write to Health Connect now",
                action = HealthCardAction.WRITE_NOW,
                buttonEnabled = true,
            ),
        )

        compose.onNodeWithTag(HEALTH_CONNECT_CARD_TAG).assertIsDisplayed()
        compose.onNodeWithText("Last written").assertIsDisplayed()
        compose.onNodeWithText("just now").assertIsDisplayed()
        compose.onNodeWithText("Written this sync").assertIsDisplayed()
        compose.onNodeWithText("4,312 records").assertIsDisplayed()
        // The headline is what changes when a write lands, so it is announced when it does.
        compose.onNodeWithText("Connected").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    /** A row is a row: nothing in the card is drawn from a literal the presenter did not hand over. */
    @Test
    fun theRowsAreDrawnFromWhatThePresenterGivesNotFromWhatTheCardKnows() {
        draw(HealthCardUi(headline = "Connected", rows = listOf("Last written" to "Not yet")))

        compose.onNodeWithText("Last written").assertIsDisplayed()
        compose.onNodeWithText("Not yet").assertIsDisplayed()
        compose.onNodeWithText("4,312 records").assertDoesNotExist()
    }

    /** With a warning, the amber line is drawn and it is a second thing read out on change. */
    @Test
    fun theAmberWarningIsDrawnWhenThereIsOne() {
        draw(
            HealthCardUi(
                headline = "Connected",
                warning = "Not allowed: Respiratory rate, Skin temperature. The rest is being written.",
                secondButton = "Change in Health Connect",
                secondAction = HealthCardAction.MANAGE,
            ),
        )

        compose.onNodeWithText("Not allowed: Respiratory rate, Skin temperature. The rest is being written.")
            .assertIsDisplayed()
        compose.onNodeWithText("Change in Health Connect").assertIsDisplayed()
        // Two things are announced when they change: the headline, and the amber line. The warning's
        // live region is on the surface that holds it, so it is counted, not aimed at the text node.
        assertEquals(
            "the headline and the amber warning are each read out when they change",
            2,
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes().size,
        )
    }

    /** Without a warning the card says nothing amber and announces only its headline. */
    @Test
    fun noWarningDrawsNoAmberLine() {
        draw(HealthCardUi(headline = "Connected"))

        compose.onNodeWithText("Not allowed:").assertDoesNotExist()
        assertEquals(
            "no warning, no second line announced",
            1,
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes().size,
        )
    }

    /** "Writing…" while a flush runs: drawn, and not tappable. */
    @Test
    fun aButtonThatCannotBeUsedIsDrawnDisabled() {
        draw(HealthCardUi(headline = "Connected", button = "Writing…", action = HealthCardAction.WRITE_NOW, buttonEnabled = false))

        compose.onNodeWithText("Writing…").assertIsDisplayed().assertIsNotEnabled()
    }

    /** The plain lines under the rows ("Catching up…", the live-only line). */
    @Test
    fun theNotesAreDrawnUnderTheRows() {
        draw(HealthCardUi(headline = "Connected", rows = listOf("Last written" to "3 h ago"), notes = listOf("Catching up…", "Steps and skin temperature keep coming while the ring is connected.")))

        compose.onNodeWithText("Catching up…").assertIsDisplayed()
        compose.onNodeWithText("Steps and skin temperature keep coming while the ring is connected.").assertIsDisplayed()
    }

    /** A card with no button draws no button. */
    @Test
    fun aStateWithNoButtonDrawsNone() {
        draw(HealthCardUi(headline = "Health Connect is not available", body = "This phone has no Health Connect."))

        compose.onNodeWithText("Health Connect is not available").assertIsDisplayed()
        compose.onNodeWithText("This phone has no Health Connect.").assertIsDisplayed()
        compose.onNodeWithText("Write to Health Connect now").assertDoesNotExist()
    }
}
