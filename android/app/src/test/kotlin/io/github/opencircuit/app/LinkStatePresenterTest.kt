package io.github.opencircuit.app

import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.LinkStatePresenter
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.PairingFailure
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Every state the link can be in has its own words and one thing the user can do about it
 * (upstream's `statusText`, `ios/OpenCircuit/ContentView.swift:2227-2247` @ b1c2fdd, and its
 * not-streaming hint, :1411-1424). The copy never comes from a `:ble` type's `toString`.
 */
class LinkStatePresenterTest {

    private val name = "RingConn Gen2-1A2B"

    /** One of each state. [covers] fails to compile when the link gains a state this list lacks. */
    private val allStates: List<LinkState> = listOf(
        LinkState.Idle,
        LinkState.Connecting,
        LinkState.Discovering,
        LinkState.PairingNeeded,
        LinkState.PairingFailed(PairingFailure.BOND_NOT_COMPLETED),
        LinkState.Preparing,
        LinkState.Authenticating,
        LinkState.Authenticated,
        LinkState.NotStreaming,
        LinkState.Reconnecting(attempt = 2, delay = Duration.ofSeconds(5)),
        LinkState.WaitingForRing,
        LinkState.BondLostSuspected,
        LinkState.BluetoothOff,
    )

    @Suppress("unused")
    private fun covers(state: LinkState): Unit = when (state) {
        LinkState.Idle, LinkState.Connecting, LinkState.Discovering, LinkState.PairingNeeded, is LinkState.PairingFailed,
        LinkState.Preparing, LinkState.Authenticating, LinkState.Authenticated, LinkState.NotStreaming,
        is LinkState.Reconnecting, LinkState.WaitingForRing, LinkState.BondLostSuspected, LinkState.BluetoothOff,
        -> Unit
    }

    @Test
    fun all13StatesHaveTheirOwnWordsAndAnAction() {
        assertEquals(13, allStates.map { it::class }.toSet().size, "one of each of the 13 states")
        allStates.forEach { state ->
            val ui = LinkStatePresenter.present(state, name)
            assertTrue(ui.headline.isNotBlank(), "headline for ${state::class.simpleName}")
            assertTrue(ui.action.label.isNotBlank(), "action for ${state::class.simpleName}")
            assertFalse("LinkState" in ui.headline || "(" in ui.headline, "no type name in the copy: ${ui.headline}")
        }
    }

    @Test
    fun onlyAuthenticatedIsConnected() {
        allStates.forEach { state ->
            assertEquals(state == LinkState.Authenticated, LinkStatePresenter.present(state, name).connected, "${state::class.simpleName}")
        }
        assertEquals("Connected", LinkStatePresenter.present(LinkState.Authenticated, name).headline)
    }

    @Test
    fun idleOffersScanAndConnect() {
        val ui = LinkStatePresenter.present(LinkState.Idle, name)
        assertEquals("Ready", ui.headline)
        assertEquals(LinkAction.SCAN_AND_CONNECT, ui.action)
        assertEquals("Scan & connect", ui.action.label)
    }

    @Test
    fun connectingStepsNameTheRingAndCanBeCancelled() {
        listOf(LinkState.Connecting, LinkState.Discovering, LinkState.Preparing, LinkState.Authenticating).forEach { state ->
            val ui = LinkStatePresenter.present(state, name)
            assertEquals("Connecting to $name…", ui.headline)
            assertEquals(LinkAction.CANCEL, ui.action)
        }
    }

    @Test
    fun pairingNeededAsksToConfirmTheRequestWhichMayBeANotification() {
        val ui = LinkStatePresenter.present(LinkState.PairingNeeded, name)
        assertEquals("Pairing with $name…", ui.headline)
        assertEquals("Confirm the pairing request — it may appear as a notification.", ui.detail)
        assertEquals(LinkAction.CANCEL, ui.action)
    }

    @Test
    fun pairingFailedOffersTryAgainWithItsReasonInWords() {
        val details = PairingFailure.entries.map { reason ->
            val ui = LinkStatePresenter.present(LinkState.PairingFailed(reason), name)
            assertEquals("Pairing didn't finish", ui.headline)
            assertEquals(LinkAction.TRY_AGAIN, ui.action)
            assertEquals("Try again", ui.action.label)
            val detail = ui.detail.orEmpty()
            assertFalse(reason.name in detail, "no enum name in the copy: $detail")
            assertTrue("try again" in detail, detail)
            detail
        }
        assertEquals(PairingFailure.entries.size, details.toSet().size, "each reason has its own words")
    }

    @Test
    fun notStreamingSaysTheLinkIsOpenButNoDataCameYet() {
        val ui = LinkStatePresenter.present(LinkState.NotStreaming, name)
        assertEquals("Ring isn't streaming", ui.headline)
        assertTrue(ui.detail.orEmpty().startsWith("Connected, but the ring hasn't sent data yet."), ui.detail)
        assertTrue("official RingConn app" in ui.detail.orEmpty())
        assertEquals(LinkAction.DISCONNECT, ui.action)
    }

    @Test
    fun reconnectingAndWaitingOfferStopReconnecting() {
        listOf(LinkState.Reconnecting(attempt = 1, delay = Duration.ofSeconds(1)), LinkState.WaitingForRing).forEach { state ->
            val ui = LinkStatePresenter.present(state, name)
            assertEquals("Ring unreachable — reconnecting automatically", ui.headline)
            assertEquals(LinkAction.STOP_RECONNECTING, ui.action)
            assertEquals("Stop reconnecting", ui.action.label)
        }
    }

    @Test
    fun bondLostSaysTheLinkKeepsTryingAndOffersBluetoothSettings() {
        val ui = LinkStatePresenter.present(LinkState.BondLostSuspected, name)
        assertEquals("Ring forgot this phone", ui.headline)
        assertTrue("Bluetooth settings" in ui.detail.orEmpty(), ui.detail)
        assertTrue("keeps trying to reconnect" in ui.detail.orEmpty(), ui.detail)
        assertEquals(LinkAction.BLUETOOTH_SETTINGS, ui.action)
        assertTrue(ui.action.opensSystemSettings)
    }

    @Test
    fun bluetoothOffOffersToTurnItOn() {
        val ui = LinkStatePresenter.present(LinkState.BluetoothOff, name)
        assertEquals("Bluetooth off", ui.headline)
        assertEquals(LinkAction.TURN_ON_BLUETOOTH, ui.action)
        assertTrue(ui.action.opensSystemSettings)
    }

    @Test
    fun theStatesTheUserMustTellApartHaveDifferentWords() {
        val distinct = listOf(
            LinkState.PairingNeeded, LinkState.PairingFailed(PairingFailure.BOND_TIMED_OUT), LinkState.BondLostSuspected,
            LinkState.NotStreaming, LinkState.WaitingForRing, LinkState.BluetoothOff, LinkState.Authenticated, LinkState.Idle,
        ).map { LinkStatePresenter.present(it, name).headline }
        assertEquals(distinct.size, distinct.toSet().size, "$distinct")
        assertNotEquals(
            LinkStatePresenter.present(LinkState.Reconnecting(1, Duration.ofSeconds(1)), name).detail,
            LinkStatePresenter.present(LinkState.WaitingForRing, name).detail,
        )
    }
}
