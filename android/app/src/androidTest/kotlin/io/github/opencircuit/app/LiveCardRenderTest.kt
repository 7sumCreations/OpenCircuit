package io.github.opencircuit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.live.LiveCardUi
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.LivePoint
import io.github.opencircuit.app.live.MeasureCardUi
import io.github.opencircuit.app.live.MeasureUi
import io.github.opencircuit.app.ring.DeviceStatusState
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.connectionCardUi
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.app.ring.RingScreen
import io.github.opencircuit.app.ring.RingUiState
import io.github.opencircuit.app.ui.OpenCircuitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Ring screen's Measure section drawn from hand-built states on a device: the Live card's
 * large readout, low–high so far, progress line, chart and Stop; the Measure cards with SpO₂'s
 * "est." mark and a failure line; and that the buttons send the right actions.
 */
@RunWith(AndroidJUnit4::class)
class LiveCardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<RingAction>()

    private fun card(mode: LiveMode, caption: String, measuring: Boolean = false, failure: String? = null) = MeasureCardUi(
        mode = mode,
        title = if (mode == LiveMode.HEART_RATE) "Heart rate" else "SpO₂",
        estimate = mode == LiveMode.SPO2,
        caption = caption,
        failure = failure,
        measuring = measuring,
        actionLabel = (if (measuring) "Stop measuring " else "Measure ") + if (mode == LiveMode.HEART_RATE) "heart rate" else "SpO₂",
    )

    private val heartRateLive = LiveCardUi(
        title = "Live heart rate",
        estimate = false,
        readout = "63",
        unit = "bpm",
        range = "58–66 so far",
        progress = "Measuring heart rate…",
        points = listOf(LivePoint(0, 58), LivePoint(2_000, 66), LivePoint(4_000, 63)),
        windowMillis = 90_000,
        windowLabel = "last 90 s",
    )

    private fun show(measure: MeasureUi, pulse: Boolean = false) {
        compose.setContent {
            OpenCircuitTheme {
                RingScreen(
                    state = RingUiState(
                        title = "Ring",
                        card = connectionCardUi(LinkState.Authenticated, "Demo ring", DeviceStatusState(batteryPercent = 72), measuring = true, keepaliveProblem = null),
                        measure = measure,
                    ),
                    onAction = { actions += it },
                    pulse = pulse,
                )
            }
        }
    }

    @Test
    fun aRunningHeartRateMeasureShowsTheReadoutRangeProgressChartAndStop() {
        show(
            MeasureUi(
                heartRate = card(LiveMode.HEART_RATE, "62 bpm (settled) · measuring…", measuring = true),
                spo2 = card(LiveMode.SPO2, "No reading yet"),
                live = heartRateLive,
            ),
        )

        compose.onNodeWithText("Live heart rate").assertIsDisplayed()
        compose.onNodeWithText("63").assertIsDisplayed()
        compose.onNodeWithText("58–66 so far").assertIsDisplayed()
        compose.onNodeWithText("Measuring heart rate…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Live chart, 3 readings").assertIsDisplayed()
        compose.onNodeWithText("last 90 s").assertIsDisplayed()
        compose.onNodeWithText("62 bpm (settled) · measuring…").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("Stop measuring heart rate").performScrollTo().performClick()
        assertEquals(listOf<RingAction>(RingAction.StopMeasure), actions)
    }

    @Test
    fun beforeTheFirstReadingTheReadoutIsADashAndAsksToHoldStill() {
        show(
            MeasureUi(
                heartRate = card(LiveMode.HEART_RATE, "preparing…", measuring = true),
                spo2 = card(LiveMode.SPO2, "No reading yet"),
                live = heartRateLive.copy(readout = "—", range = null, progress = "Hold still — getting a reading", points = emptyList()),
            ),
        )

        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithText("Hold still — getting a reading").assertIsDisplayed()
        compose.onNodeWithContentDescription("Live chart, waiting for a reading").assertIsDisplayed()
    }

    @Test
    fun theMeasureButtonsSendTheirModeAndSpo2IsMarkedAsAnEstimateWithItsFailureLine() {
        val failure = "Couldn't get a reading — make sure the ring is worn snugly and not on the charger, then hold still."
        show(
            MeasureUi(
                heartRate = card(LiveMode.HEART_RATE, "Last: 62 bpm"),
                spo2 = card(LiveMode.SPO2, "No reading yet", failure = failure),
                live = null,
            ),
        )

        compose.onNodeWithText("Last: 62 bpm").assertIsDisplayed()
        compose.onNodeWithText("est.").assertIsDisplayed()
        compose.onNodeWithText(failure).assertIsDisplayed()
        compose.onNodeWithContentDescription("Measure SpO₂").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Measure heart rate").performClick()
        assertEquals(listOf(RingAction.Measure(LiveMode.SPO2), RingAction.Measure(LiveMode.HEART_RATE)), actions)
    }

    @Test
    fun withThePulseOnTheScreenStillSettlesForTheTest() {
        // Measures whether the never-ending endpoint animation keeps the test from going idle.
        show(
            MeasureUi(
                heartRate = card(LiveMode.HEART_RATE, "measuring…", measuring = true),
                spo2 = card(LiveMode.SPO2, "No reading yet"),
                live = heartRateLive,
            ),
            pulse = true,
        )

        compose.waitForIdle()
        compose.onNodeWithText("63").assertIsDisplayed()
        compose.onNodeWithContentDescription("Live chart, 3 readings").assertIsDisplayed()
    }
}
