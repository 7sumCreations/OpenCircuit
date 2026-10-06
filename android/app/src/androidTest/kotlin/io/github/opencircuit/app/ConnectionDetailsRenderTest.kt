package io.github.opencircuit.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.opencircuit.app.details.ConnectionDetailsCard
import io.github.opencircuit.app.details.ConnectionDetailsPresenter
import io.github.opencircuit.app.details.ConnectionDetailsUi
import io.github.opencircuit.app.details.DIAGNOSTICS_HINT
import io.github.opencircuit.app.details.DetailsInput
import io.github.opencircuit.app.session.ConnectionTimings
import io.github.opencircuit.app.session.DispatchCounts
import io.github.opencircuit.app.session.SessionTeardowns
import io.github.opencircuit.app.ui.OpenCircuitTheme
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.LinkDiagnostic
import io.github.opencircuit.ble.LinkInfo
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.ScanDiagnostic
import io.github.opencircuit.ringkit.FirmwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Connection details card drawn on a device from a hand-built state through the real
 * presenter: collapsed, the address and the ring's name are masked and the diagnostics are
 * hidden; "Show all" shows the full values, the decoded advertisement and every diagnostics line
 * (the last one, a bond change, included); Copy is reported once per tap. Every value is
 * synthetic; the address is the project's placeholder.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionDetailsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private var copies = 0

    private val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "RingConn Gen2-EEFF")

    private val advertisement: ByteArray = run {
        val name = "RingConn Gen2-EEFF".toByteArray(Charsets.UTF_8)
        val bytes = intArrayOf(0x02, 0x01, 0x06, 0x11, 0x07, 0x37, 0x04, 0x1c, 0x97, 0xd7, 0x6d, 0xce, 0xa8, 0x22, 0x4a, 0x87, 0x2d, 0x99, 0xad, 0x27, 0x83)
        ByteArray(bytes.size) { bytes[it].toByte() } + byteArrayOf((name.size + 1).toByte(), 0x09) + name
    }

    /** 62 bring-up steps, then a Bluetooth change and a bond change: 64 lines, as many as the link keeps. */
    private val diagnostics = List(62) { LinkDiagnostic(it * 10L, "step $it", "") } +
        LinkDiagnostic(1_000, "adapter state", "OFF → ON") +
        LinkDiagnostic(2_000, "bond state", "BONDING → BONDED, after createBond")

    private fun details(input: DetailsInput) = ConnectionDetailsPresenter.present(input)

    private val full = DetailsInput(
        ring = ring,
        info = LinkInfo(firmware = FirmwareInfo(version = "FR02.018"), attMtu = 247, historySafe = true, bonded = true),
        timings = ConnectionTimings(connectToAuthenticatedMillis = 5_200, pairingNeededSeen = true, pairingNeededMillis = 4_200),
        counts = DispatchCounts(unhandled = mapOf(0x47 to 1)),
        teardowns = SessionTeardowns(),
        diagnostics = diagnostics,
        scanKept = true,
        scan = ScanDiagnostic(AddressType.RANDOM, advertisement, rssi = -61),
    )

    private fun show(details: ConnectionDetailsUi) {
        compose.setContent {
            OpenCircuitTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionDetailsCard(details, onCopy = { copies++ })
                }
            }
        }
    }

    @Test
    fun collapsedTheAddressAndTheNameAreMaskedAndTheDiagnosticsHidden() {
        show(details(full))

        compose.onNodeWithText("Connection details").assertIsDisplayed()
        compose.onNodeWithText("RingConn Gen2-••••").assertIsDisplayed()
        compose.onNodeWithText("AA:••:••:••:••:••").assertIsDisplayed()
        compose.onNodeWithText("247").assertIsDisplayed()
        compose.onNodeWithText("AA:BB:CC:DD:EE:FF").assertDoesNotExist()
        compose.onNodeWithText("RingConn Gen2-EEFF").assertDoesNotExist()
        compose.onNodeWithText("Link diagnostics").assertDoesNotExist()
    }

    @Test
    fun showAllGivesFullValuesTheAdvertisementAndEveryDiagnosticsLine() {
        show(details(full))

        compose.onNodeWithText("Show all").performClick()

        compose.onNodeWithText("AA:BB:CC:DD:EE:FF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("5.2 s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("8327ad99-2d87-4a22-a8ce-6dd7971c0437").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(DIAGNOSTICS_HINT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+0 ms · step 0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+1000 ms · adapter state · OFF → ON").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+2000 ms · bond state · BONDING → BONDED, after createBond").performScrollTo().assertIsDisplayed()
        assertEquals(64, compose.onAllNodesWithText("+", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun copyIsReportedOncePerTap() {
        show(details(full))

        compose.onNodeWithText("Copy").performScrollTo().performClick()

        assertEquals(1, copies)
    }

    @Test
    fun withNoRingTheCardSaysNotAvailable() {
        show(details(DetailsInput()))

        assertTrue(compose.onAllNodesWithText("not available").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Show all").performClick()
        compose.onNodeWithText("Link diagnostics").performScrollTo().assertIsDisplayed()
    }
}
