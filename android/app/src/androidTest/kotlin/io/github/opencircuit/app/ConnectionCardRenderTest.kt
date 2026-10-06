package io.github.opencircuit.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.live.LiveMeasureState
import io.github.opencircuit.app.live.measureUi
import io.github.opencircuit.app.ring.DeviceStatusState
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.LinkStatePresenter
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingScreen
import io.github.opencircuit.app.ring.RingUiState
import io.github.opencircuit.app.ring.connectionCardUi
import io.github.opencircuit.app.session.KeepaliveProblem
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.PairingFailure
import io.github.opencircuit.ble.SendFailure
import io.github.opencircuit.ringkit.DeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * The connection card drawn on a device from hand-built states: the words and the button of
 * every link state, the battery with its charging and time lines, the case battery, the charger
 * hint with Measure disabled, "as of …", and a status-request problem.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionCardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<RingAction>()
    private var state by mutableStateOf(screen(LinkState.Idle))

    private fun screen(
        link: LinkState,
        status: DeviceStatusState = DeviceStatusState(),
        measuring: Boolean = false,
        problem: KeepaliveProblem? = null,
        live: LiveMeasureState = LiveMeasureState(),
    ) = RingUiState(
        title = "Ring",
        card = connectionCardUi(link, RING, status, measuring, problem),
        measure = if (link == LinkState.Authenticated) measureUi(live, onCharger = status.onCharger) else null,
    )

    private fun show() {
        compose.setContent {
            OpenCircuitTheme { RingScreen(state = state, onAction = { actions += it }, pulse = false) }
        }
    }

    @Test
    fun everyLinkStateShowsItsWordsAndItsButton() {
        show()
        val states = listOf(
            LinkState.Idle, LinkState.Connecting, LinkState.Discovering, LinkState.PairingNeeded,
            LinkState.PairingFailed(PairingFailure.BOND_TIMED_OUT), LinkState.Preparing, LinkState.Authenticating,
            LinkState.Authenticated, LinkState.NotStreaming, LinkState.Reconnecting(attempt = 2, delay = Duration.ofSeconds(5)),
            LinkState.WaitingForRing, LinkState.BondLostSuspected, LinkState.BluetoothOff,
        )
        for (link in states) {
            state = screen(link)
            compose.waitForIdle()
            val words = LinkStatePresenter.present(link, RING)
            compose.onNodeWithText(words.headline).assertIsDisplayed()
            words.detail?.let { compose.onNodeWithText(it).assertIsDisplayed() }
            compose.onNodeWithText(words.action!!.label).assertIsDisplayed()
        }
        compose.onNodeWithText("Pairing with $RING…").assertDoesNotExist()
    }

    @Test
    fun theButtonSendsItsAction() {
        state = screen(LinkState.PairingFailed(PairingFailure.BOND_NOT_COMPLETED))
        show()

        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf<RingAction>(RingAction.Link(LinkAction.TRY_AGAIN)), actions)
    }

    @Test
    fun onTheChargerTheCardShowsTimeToFullTheCaseAndTheHintAndMeasureIsDisabled() {
        state = screen(
            LinkState.Authenticated,
            DeviceStatusState(
                batteryPercent = 64, onCharger = true, timeToFullSeconds = 3.0 * 3_600,
                caseBattery = DeviceStatus.CaseBattery(percent = 70, isCharging = false), batteryReadings = 3,
            ),
        )
        show()

        compose.onNodeWithText(RING).assertIsDisplayed()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("64%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ring battery, charging").assertIsDisplayed()
        compose.onNodeWithText("~3 h to full").assertIsDisplayed()
        compose.onNodeWithText("Case 70%").assertIsDisplayed()
        compose.onNodeWithText("Ring is on the charger — Measure unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("Measure heart rate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Measure SpO₂").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun anInferredChargeIsLabelledAndLeavesMeasureEnabled() {
        state = screen(LinkState.Authenticated, DeviceStatusState(batteryPercent = 67, chargingInferred = true, batteryReadings = 2))
        show()

        compose.onNodeWithText("charging (inferred)").assertIsDisplayed()
        compose.onNodeWithText("estimating time to full…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ring battery").assertIsDisplayed()
        compose.onNodeWithText("Ring is on the charger — Measure unavailable").assertDoesNotExist()
        compose.onNodeWithContentDescription("Measure heart rate").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Measure SpO₂").performScrollTo().assertIsEnabled()
    }

    @Test
    fun offTheChargerTheTimeLeftShowsTheCaseIsHiddenAndMeasureIsEnabled() {
        state = screen(LinkState.Authenticated, DeviceStatusState(batteryPercent = 72, timeToEmptySeconds = 78.0 * 3_600, batteryReadings = 3))
        show()

        compose.onNodeWithText("72%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ring battery").assertIsDisplayed()
        compose.onNodeWithText("~3 d 6 h left").assertIsDisplayed()
        compose.onNodeWithText("Case", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Ring is on the charger — Measure unavailable").assertDoesNotExist()
        compose.onNodeWithContentDescription("Measure heart rate").performScrollTo().assertIsEnabled()
    }

    @Test
    fun anOutOfDateBatterySaysAsOfAndDropsTheTimeLeft() {
        state = screen(
            LinkState.Reconnecting(attempt = 1, delay = Duration.ofSeconds(1)),
            DeviceStatusState(batteryPercent = 70, timeToEmptySeconds = 10.0 * 3_600, batteryAgeMillis = 240_000, batteryReadings = 1),
        )
        show()

        compose.onNodeWithText("70%").assertIsDisplayed()
        compose.onNodeWithText("as of 4 min ago").assertIsDisplayed()
        compose.onNodeWithText("~10 h left").assertDoesNotExist()
        compose.onNodeWithText("Stop reconnecting").assertIsDisplayed()
    }

    @Test
    fun aStatusRequestProblemIsShownInWords() {
        state = screen(LinkState.Authenticated, problem = KeepaliveProblem.Failed(SendFailure.TIMED_OUT))
        show()

        compose.onNodeWithText("Couldn't ask the ring for its status — the ring stopped answering.").assertIsDisplayed()
    }

    private companion object {
        const val RING = "RingConn Gen2-1A2B"
    }
}
