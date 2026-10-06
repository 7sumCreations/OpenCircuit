package io.github.opencircuit.app

import io.github.opencircuit.app.connect.CompanionPairing
import io.github.opencircuit.app.connect.ConnectFlowController
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.PairingFailure
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.ScanUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opening the app with a remembered ring: the link is built from the saved address and connected
 * with no scan (Android reconnects by address; upstream reconnected its remembered peripheral,
 * `ios/OpenCircuit/BLE/RingScanner.swift:96-164` @ b1c2fdd). One link per ring, kept: the
 * Android link registers its receivers when it is built, so building another for the same ring
 * would leak them. Reopening the screen, Try again and pairing the same ring again reuse it.
 */
class LaunchReconnectTest {

    private val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "RingConn Gen2 TEST")
    private val otherRing = RememberedRing("AA:BB:CC:DD:EE:00", AddressType.RANDOM, "RingConn Gen2 OTHR")

    /** The scanner the Scan & connect flow would use; it records every scan started. */
    private val scanner = ScriptedScanner { emit(ScanUpdate.NoRingFound) }

    private fun TestScope.openScreen(under: SessionsUnderTest): RingViewModel {
        val flow = ConnectFlowController(
            prefs = PrefsAppPrefs(under.values),
            rings = PrefsRememberedRingStore(under.values),
            scanners = { scanner },
            connector = under.sessions,
            // Bluetooth on: a scan, if the launch path asked for one, would really start.
            adapterState = MutableStateFlow(AdapterState.ON),
            scope = backgroundScope,
            log = {},
            pairing = CompanionPairing(FakeCompanionPort()) {},
            monotonicMillis = { testScheduler.currentTime },
        )
        val viewModel = RingViewModel(under.sessions, title = "Ring", scope = backgroundScope, connectFlow = flow)
        runCurrent()
        return viewModel
    }

    private fun remembered(ring: RememberedRing): InMemoryKeyValues =
        InMemoryKeyValues().also { check(PrefsRememberedRingStore(it).save(ring)) }

    @Test
    fun aRememberedRingIsConnectedOnLaunchWithoutAnyScan() = runTest {
        val under = sessionsUnderTest(remembered(ring))

        val viewModel = openScreen(under)

        assertEquals(1, under.factory.built.size)
        assertEquals(ring, under.factory.built.single().ring)
        assertEquals(1, under.factory.built.single().fake.connectCalls)
        assertEquals(0, scanner.scans, "a remembered ring reconnects by its address; nothing scans")
        assertEquals("RingConn Gen2 TEST", viewModel.uiState.value.card.ringName)
    }

    @Test
    fun theReconnectingRingShowsItsOwnStateNotTheReadyCard() = runTest {
        val under = sessionsUnderTest(remembered(ring))
        val viewModel = openScreen(under)

        under.factory.built.single().fake.setState(LinkState.Connecting)
        runCurrent()

        assertEquals("Connecting to RingConn Gen2 TEST…", viewModel.uiState.value.card.link.headline)
    }

    @Test
    fun withNoRememberedRingNothingIsBuiltOrScannedAndTheCardIsReady() = runTest {
        val under = sessionsUnderTest()

        val viewModel = openScreen(under)

        assertTrue(under.factory.built.isEmpty())
        assertEquals(0, scanner.scans)
        assertEquals("Ready", viewModel.uiState.value.card.link.headline)
        assertEquals(LinkAction.SCAN_AND_CONNECT, viewModel.uiState.value.card.link.action)
        assertNull(viewModel.uiState.value.card.ringName)
    }

    @Test
    fun aDamagedSavedRingReadsAsNoneAndNothingConnects() = runTest {
        val values = InMemoryKeyValues().also { it.putRaw("ring.remembered.v1", "AA:BB:CC:DD:EE|RANDOM|-") }
        val under = sessionsUnderTest(values)

        openScreen(under)

        assertTrue(under.factory.built.isEmpty(), "a damaged file never becomes a connection")
    }

    @Test
    fun aRelaunchedAppOverTheSamePreferencesReconnectsTheSameRingWithoutAScan() = runTest {
        val values = remembered(ring)
        openScreen(sessionsUnderTest(values))

        // A new process: new sessions, new screen, the same preferences file.
        val relaunched = sessionsUnderTest(values)
        openScreen(relaunched)

        assertEquals(ring, relaunched.factory.built.single().ring)
        assertEquals(1, relaunched.factory.built.single().fake.connectCalls)
        assertEquals(0, scanner.scans)
    }

    @Test
    fun reopeningTheScreenAndTryAgainReuseTheOneLink() = runTest {
        val under = sessionsUnderTest(remembered(ring))
        openScreen(under)
        val reopened = openScreen(under)
        val link = under.factory.built.single()
        link.fake.setState(LinkState.PairingFailed(PairingFailure.BOND_TIMED_OUT))
        runCurrent()

        reopened.onAction(RingAction.Link(LinkAction.TRY_AGAIN))

        assertEquals(1, under.factory.built.size, "one link per ring")
        assertEquals(3, link.fake.connectCalls, "launch, reopen, Try again")
    }

    @Test
    fun pairingTheRememberedRingAgainReusesItsLink() = runTest {
        val under = sessionsUnderTest(remembered(ring))
        openScreen(under)

        under.sessions.connect(ring.copy(address = ring.address.lowercase(java.util.Locale.ROOT)))

        assertEquals(1, under.factory.built.size)
        assertEquals(2, under.factory.built.single().fake.connectCalls)
    }

    @Test
    fun forgettingThenPairingAgainClosesTheOldLinkAndBuildsExactlyOneNewOne() = runTest {
        val under = sessionsUnderTest(remembered(ring))
        openScreen(under)

        under.sessions.stopReconnecting()
        under.sessions.connect(ring)

        assertEquals(2, under.factory.built.size)
        assertTrue(under.factory.built[0].closed, "the old link is retired")
        assertEquals(
            listOf("build", "connect", "disconnect", "close", "disassociate", "build", "connect"),
            under.events,
        )
        assertEquals(ring, under.store.load(), "the paired ring is remembered again")
    }

    @Test
    fun pairingADifferentRingReleasesTheOldLinkBeforeBuildingTheNewOne() = runTest {
        val under = sessionsUnderTest(remembered(ring))
        openScreen(under)

        under.sessions.connect(otherRing)

        assertEquals(listOf("build", "connect", "disconnect", "close", "build", "connect"), under.events)
        assertEquals(otherRing, under.store.load())
        assertEquals(otherRing, under.sessions.current.value?.link?.ring)
    }
}
