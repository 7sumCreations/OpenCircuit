package io.github.opencircuit.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.connect.ConnectFlowPresenter
import io.github.opencircuit.app.connect.ConnectFlowState
import io.github.opencircuit.app.connect.ScanPhase
import io.github.opencircuit.app.ring.DeviceStatusState
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingScreen
import io.github.opencircuit.app.ring.RingUiState
import io.github.opencircuit.app.ring.connectionCardUi
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.BluetoothPermission
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.RememberedRing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Ring screen drawn on a device for each first-run state, from hand-built states through the
 * real presenter: Nearby devices not allowed (once, and for good), blocked by policy, Bluetooth
 * off, scanning, the picker (saved ring first, a tap picks), no ring found (with and without the
 * saved-ring hint), a failed scan, and one ring selected and connecting. Each has its own words
 * and its own button. Addresses are synthetic.
 */
@RunWith(AndroidJUnit4::class)
class RingStatesRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<RingAction>()
    private var state by mutableStateOf(idle(ConnectFlowState()))

    private val alpha = RememberedRing("AA:BB:CC:DD:EE:00", AddressType.RANDOM, "RingConn Alpha")
    private val zulu = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "RingConn Zulu")

    private fun idle(flow: ConnectFlowState, adapter: AdapterState? = AdapterState.ON, link: LinkState = LinkState.Idle, ringName: String? = null) =
        RingUiState(
            title = "Ring",
            card = connectionCardUi(
                link,
                ringName,
                DeviceStatusState(),
                measuring = false,
                keepaliveProblem = null,
                flowCard = ConnectFlowPresenter.scan(flow) ?: if (link == LinkState.Idle) ConnectFlowPresenter.availability(flow, adapter) else null,
            ),
        )

    private fun show() {
        compose.setContent { OpenCircuitTheme { RingScreen(state = state, onAction = { actions += it }, pulse = false) } }
    }

    @Test
    fun nearbyDevicesRefusedOnceOffersToAllowIt() {
        state = idle(ConnectFlowState(permission = BluetoothPermission.DENIED, canAskAgain = true))
        show()

        compose.onNodeWithText("Bluetooth not allowed").assertIsDisplayed()
        compose.onNodeWithText("It never uses it for location", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Allow Nearby devices").performClick()
        assertEquals(listOf<RingAction>(RingAction.Link(LinkAction.ALLOW_NEARBY)), actions)
    }

    @Test
    fun nearbyDevicesRefusedForGoodOffersSettingsAndAskAgain() {
        state = idle(ConnectFlowState(permission = BluetoothPermission.DENIED, canAskAgain = false))
        show()

        compose.onNodeWithText("Bluetooth not allowed").assertIsDisplayed()
        compose.onNodeWithText("Settings › Apps › OpenCircuit › Permissions", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Ask again").performClick()
        compose.onNodeWithText("Open settings").performClick()
        assertEquals(listOf<RingAction>(RingAction.Link(LinkAction.ASK_AGAIN), RingAction.Link(LinkAction.OPEN_APP_SETTINGS)), actions)
    }

    @Test
    fun aDevicePolicyShowsWhyAndOffersNoButton() {
        state = idle(ConnectFlowState(permission = BluetoothPermission.RESTRICTED, canAskAgain = false))
        show()

        compose.onNodeWithText("Bluetooth is blocked by a device policy").assertIsDisplayed()
        compose.onNodeWithText("Scan & connect").assertDoesNotExist()
        compose.onNodeWithText("Open settings").assertDoesNotExist()
    }

    @Test
    fun bluetoothOffOffersToTurnItOn() {
        state = idle(ConnectFlowState(permission = BluetoothPermission.GRANTED, canAskAgain = false), adapter = AdapterState.OFF)
        show()

        compose.onNodeWithText("Bluetooth off").assertIsDisplayed()
        compose.onNodeWithText("Turn on Bluetooth").performClick()
        assertEquals(listOf<RingAction>(RingAction.Link(LinkAction.TURN_ON_BLUETOOTH)), actions)
    }

    @Test
    fun scanningSaysSoWithAProgressIndicatorAndCancel() {
        state = idle(ConnectFlowState(permission = BluetoothPermission.GRANTED, phase = ScanPhase.Scanning(listOf(alpha))))
        show()

        compose.onNodeWithText("Searching for ring…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Searching").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf<RingAction>(RingAction.Link(LinkAction.CANCEL_SCAN)), actions)
    }

    @Test
    fun severalRingsShowThePickerWithTheSavedRingMarkedAndATapPicksIt() {
        state = idle(ConnectFlowState(permission = BluetoothPermission.GRANTED, phase = ScanPhase.Choosing(listOf(alpha, zulu)), savedRing = zulu))
        show()

        compose.onNodeWithText("Multiple rings found — pick one").assertIsDisplayed()
        compose.onNodeWithText("Last used").assertIsDisplayed()
        compose.onNodeWithText("RingConn Alpha").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect to RingConn Zulu").performClick()
        assertEquals(listOf<RingAction>(RingAction.Pick(zulu)), actions)
    }

    @Test
    fun noRingFoundGivesTheHintsAndSearchAgain() {
        state = idle(ConnectFlowState(permission = BluetoothPermission.GRANTED, phase = ScanPhase.NoRingFound))
        show()

        compose.onNodeWithText("No ring found").assertIsDisplayed()
        compose.onNodeWithText("Take the ring out of its charging case", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Force-stop the official RingConn app", substring = true).assertIsDisplayed()
        compose.onNodeWithText("within a few feet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("paired before", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Search again").performClick()
        assertEquals(listOf<RingAction>(RingAction.Link(LinkAction.SEARCH_AGAIN)), actions)
    }

    @Test
    fun noRingFoundWithASavedRingAddsThatHint() {
        state = idle(ConnectFlowState(permission = BluetoothPermission.GRANTED, phase = ScanPhase.NoRingFound, savedRing = alpha))
        show()

        compose.onNodeWithText("reconnects automatically once it's back in range", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFailedScanSaysSoAndOffersSearchAgain() {
        state = idle(ConnectFlowState(permission = BluetoothPermission.GRANTED, phase = ScanPhase.Failed(-1)))
        show()

        compose.onNodeWithText("Couldn't search for rings").assertIsDisplayed()
        compose.onNodeWithText("Android didn't start the scan", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Search again").assertIsDisplayed()
    }

    @Test
    fun oneRingSelectedShowsTheLinkConnectingToIt() {
        // The scan ended with one ring; the card now follows that ring's link.
        state = idle(ConnectFlowState(permission = BluetoothPermission.GRANTED), link = LinkState.Connecting, ringName = alpha.name)
        show()

        compose.onNodeWithText("Connecting to RingConn Alpha…").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
