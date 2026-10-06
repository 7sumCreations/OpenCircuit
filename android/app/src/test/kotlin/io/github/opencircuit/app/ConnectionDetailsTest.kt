package io.github.opencircuit.app

import io.github.opencircuit.app.connect.FallbackReason
import io.github.opencircuit.app.connect.PairingOutcome
import io.github.opencircuit.app.details.ConnectionDetailsUi
import io.github.opencircuit.app.details.DetailRow
import io.github.opencircuit.app.details.DetailsSources
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.LinkDiagnostic
import io.github.opencircuit.ble.LinkInfo
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.ScanDiagnostic
import io.github.opencircuit.ringkit.FirmwareInfo
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Connection details card: what the phone and the ring did, readable on the phone itself (it
 * is never on a cable). It shows the link's facts, the app's own timings, the pairing sheet's
 * outcome, frame and teardown counters, the link's diagnostics list exactly as the link wrote it,
 * and the last advertisement decoded. A surface the link or scanner does not offer reads "not
 * available".
 *
 * Privacy: the address and the ring's advertised name (whose suffix is the end of the address)
 * are masked in the collapsed card and in the Copy text; the full values show on screen only when
 * the card is expanded. Every value is synthetic; the addresses are the project's placeholders.
 */
class ConnectionDetailsTest {

    private val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "RingConn Gen2-EEFF")

    /** A ring-shaped advertisement: flags, the data service, the name, tx power −12 dBm, manufacturer data. */
    private val advertisement: ByteArray = run {
        val name = "RingConn Gen2-EEFF".toByteArray(Charsets.UTF_8)
        bytes(0x02, 0x01, 0x06) +
            bytes(0x11, 0x07, 0x37, 0x04, 0x1c, 0x97, 0xd7, 0x6d, 0xce, 0xa8, 0x22, 0x4a, 0x87, 0x2d, 0x99, 0xad, 0x27, 0x83) +
            byteArrayOf((name.size + 1).toByte(), 0x09) + name +
            bytes(0x02, 0x0a, 0xf4) +
            bytes(0x06, 0xff, 0xff, 0xff, 0x01, 0x02, 0x03)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private class Screen(
        val link: FakeRingLink?,
        val viewModel: RingViewModel,
        val pairing: MutableStateFlow<PairingOutcome?>,
        val scan: MutableStateFlow<ScanDiagnostic?>,
    ) {
        val details: ConnectionDetailsUi get() = viewModel.uiState.value.details

        fun value(rows: List<DetailRow>, label: String): String =
            rows.firstOrNull { it.label == label }?.value ?: error("no row '$label' in ${rows.map { it.label }}")
    }

    private fun TestScope.screen(link: RingLink? = FakeRingLink(ring)): Screen {
        val pairing = MutableStateFlow<PairingOutcome?>(null)
        val scan = MutableStateFlow<ScanDiagnostic?>(null)
        val sessions = link?.let {
            sessionsOf(RingSessionController(it, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = {}))
        }
        val viewModel = RingViewModel(
            sessions,
            title = "Ring",
            scope = backgroundScope,
            details = DetailsSources(pairingOutcome = pairing, scanMatch = scan),
        )
        runCurrent()
        return Screen(link as? FakeRingLink, viewModel, pairing, scan)
    }

    @Test
    fun withNoRingEverySurfaceSaysNotAvailable() = runTest {
        val screen = screen(link = null)

        val details = screen.details
        assertEquals("not available", screen.value(details.rows, "Ring"))
        assertEquals("not available", screen.value(details.rows, "ATT MTU"))
        assertEquals(listOf("not available"), details.diagnostics)
        assertEquals("none seen since the app started", screen.value(details.advertisement, "Advertisement"))
    }

    @Test
    fun aScannerThatKeepsNoMatchSaysNotAvailable() = runTest {
        val viewModel = RingViewModel(sessions = null, title = "Ring", scope = backgroundScope, details = DetailsSources())
        runCurrent()

        val advertisement = viewModel.uiState.value.details.advertisement
        assertEquals(listOf(DetailRow("Advertisement", "not available")), advertisement)
    }

    @Test
    fun theLinkFactsShowFromTheLinkInfo() = runTest {
        val screen = screen()
        screen.link!!.setInfo(
            LinkInfo(
                firmware = FirmwareInfo(version = "FR02.018", modelName = "RingConn Gen2-EEFF", manufacturer = "JZ_Tech"),
                attMtu = 247,
                historySafe = true, // 247 ≥ 246
                bonded = true,
                macMismatch = false,
            ),
        )
        runCurrent()

        val rows = screen.details.rows
        assertTrue(screen.value(rows, "Firmware").startsWith("FR02.018"), screen.value(rows, "Firmware"))
        assertEquals("247", screen.value(rows, "ATT MTU"))
        assertEquals("yes", screen.value(rows, "History-safe"))
        assertEquals("yes", screen.value(rows, "Bonded"))
        assertEquals("no", screen.value(rows, "MAC mismatch"))
        assertEquals("random", screen.value(rows, "Address type"))
    }

    @Test
    fun theDiagnosticsShowExactlyAsTheLinkWroteThemAllSixtyFour() = runTest {
        val screen = screen()
        repeat(70) { screen.link!!.emitDiagnostic(LinkDiagnostic(it * 10L, "step $it", if (it % 2 == 0) "" else "detail $it")) }
        screen.link!!.emitDiagnostic(LinkDiagnostic(900, "bond state", "BONDING → BONDED, after createBond"))
        runCurrent()

        val lines = screen.details.diagnostics
        assertEquals(64, lines.size, "the link keeps 64; the card drops none of them")
        assertEquals("+900 ms · bond state · BONDING → BONDED, after createBond", lines.last())
        assertEquals("+70 ms · step 7 · detail 7", lines.first(), "71 written, the last 64 kept: steps 7 to 69 and the bond line")
    }

    @Test
    fun aLinkWithoutDiagnosticsSaysNotAvailable() = runTest {
        val screen = screen(link = timedLink()) // the timed wrapper offers only RingLink

        assertEquals(listOf("not available"), screen.details.diagnostics)
    }

    @Test
    fun theAppsOwnTimingsAreMeasured() = runTest {
        val screen = screen() // opening the screen connects at t = 0
        val link = screen.link!!
        link.setState(LinkState.Connecting)
        advanceTo(1_000)
        link.setState(LinkState.PairingNeeded)
        runCurrent()
        assertEquals("showing now", screen.value(screen.details.rows, "Pairing prompt"))
        advanceTo(5_200)
        link.setState(LinkState.Authenticated)
        runCurrent()
        advanceTo(10_000)
        link.setState(LinkState.Reconnecting(attempt = 1, delay = java.time.Duration.ofSeconds(1)))
        advanceTo(13_000)
        link.setState(LinkState.Authenticated)
        runCurrent()

        val rows = screen.details.rows
        assertEquals("5.2 s", screen.value(rows, "Connect → authenticated"))
        assertEquals("seen for 4.2 s", screen.value(rows, "Pairing prompt"))
        assertEquals("3.0 s", screen.value(rows, "Reconnect wait (last)"))
    }

    @Test
    fun framesNotHandledAndTornDownConnectionsAreCounted() = runTest {
        val screen = screen()
        screen.link!!.emitFrame(TestFrames.unknown)
        screen.link.emitFrame(TestFrames.unknown)
        screen.link.emitFrame(TestFrames.historyPage47)
        screen.link.emitTeardown(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 3))
        screen.link.emitTeardown(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 2))
        runCurrent()

        val rows = screen.details.rows
        assertEquals("0x47 × 1 · 0xee × 2", screen.value(rows, "Frames not handled"))
        assertEquals("none", screen.value(rows, "Handler failures"))
        assertEquals("2 · last: link dropped · 5 frames undelivered in all", screen.value(rows, "Connections torn down"))
    }

    @Test
    fun thePairingSheetsOutcomeIsShownInWords() = runTest {
        val screen = screen()
        assertEquals("not used since the app started", screen.value(screen.details.rows, "Pairing sheet"))

        screen.pairing.value = PairingOutcome.Fallback(FallbackReason.DISCOVERY_TIMEOUT, resultCode = 2)
        runCurrent()
        assertEquals(
            "connected without it: the sheet didn't find the ring in time (result 2)",
            screen.value(screen.details.rows, "Pairing sheet"),
        )

        screen.pairing.value = PairingOutcome.Declined(1)
        runCurrent()
        assertEquals("refused (result 1)", screen.value(screen.details.rows, "Pairing sheet"))

        screen.pairing.value = PairingOutcome.Fallback(FallbackReason.USER_CHOSE_PLAIN_BOND, resultCode = 1)
        runCurrent()
        assertEquals(
            "connected without it: you chose to pair without the system sheet (result 1)",
            screen.value(screen.details.rows, "Pairing sheet"),
        )
    }

    @Test
    fun theAdvertisementIsDecodedWithFullValuesOnlyWhenExpanded() = runTest {
        val screen = screen()
        screen.scan.value = ScanDiagnostic(AddressType.RANDOM, advertisement, rssi = -61)
        runCurrent()

        val full = screen.details.advertisement
        assertEquals("-61 dBm", screen.value(full, "Signal"))
        assertEquals("random", screen.value(full, "Address type (scan)"))
        assertEquals("0x06", screen.value(full, "Flags"))
        assertEquals("8327ad99-2d87-4a22-a8ce-6dd7971c0437", screen.value(full, "Services"))
        assertEquals("-12 dBm", screen.value(full, "Tx power"))
        assertEquals("0xffff: 010203", screen.value(full, "Manufacturer data"))
        assertEquals("RingConn Gen2-EEFF", screen.value(full, "Name (advertised)"))
        assertFalse("EEFF" in screen.details.copyText.uppercase(Locale.ROOT))
    }

    @Test
    fun theCollapsedCardMasksTheAddressAndTheNameSuffix() = runTest {
        val screen = screen()

        val summary = screen.details.summary
        assertEquals("RingConn Gen2-••••", screen.value(summary, "Ring"))
        assertEquals("AA:••:••:••:••:••", screen.value(summary, "Address"))
        assertEquals("RingConn Gen2-EEFF", screen.value(screen.details.rows, "Ring"), "expanded: the full name")
        assertEquals("AA:BB:CC:DD:EE:FF", screen.value(screen.details.rows, "Address"), "expanded: the full address")
    }

    @Test
    fun theCopiedTextCarriesNeitherTheAddressNorTheNameSuffix() = runTest {
        val screen = screen()
        val link = screen.link!!
        link.setInfo(LinkInfo(firmware = FirmwareInfo(version = "FR02.018", modelName = "RingConn Gen2-EEFF"), mac = ring.address))
        // A diagnostic that (against the link's promise) carries the address must not leak it either.
        link.emitDiagnostic(LinkDiagnostic(5, "MAC source", "aa:bb:cc:dd:ee:ff from System ID"))
        screen.scan.value = ScanDiagnostic(AddressType.RANDOM, advertisement, rssi = -61)
        runCurrent()

        val copied = screen.details.copyText.uppercase(Locale.ROOT)
        assertFalse("AA:BB:CC:DD:EE:FF" in copied, copied)
        assertFalse("AABBCCDDEEFF" in copied, copied)
        assertFalse("EEFF" in copied, copied)
        assertTrue("RINGCONN GEN2-••••" in copied, copied)
        assertTrue("AA:••:••:••:••:••" in copied, copied)
        assertTrue("+5 MS · MAC SOURCE" in copied, "the diagnostics are in the copy, masked")
        for (typeName in listOf("REMEMBEREDRING(", "LINKINFO(", "SCANDIAGNOSTIC(", "FIRMWAREINFO(")) {
            assertFalse(typeName in copied, "no :ble value's toString in the copy")
        }
    }

    @Test
    fun theCopiedTextNamesTheAppAndCarriesTheMaskedRows() = runTest {
        val screen = screen()
        screen.link!!.setInfo(LinkInfo(attMtu = 185, historySafe = false))
        runCurrent()

        val copied = screen.details.copyText
        assertTrue(copied.startsWith("OpenCircuit connection details"), copied)
        assertTrue("ATT MTU: 185" in copied, copied)
        assertTrue("History-safe: no" in copied, copied)
    }
}
