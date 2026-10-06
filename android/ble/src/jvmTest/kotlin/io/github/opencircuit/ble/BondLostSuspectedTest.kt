package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.GattPort.BondState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When the ring forgets the phone (factory reset, paired elsewhere) Android 16 keeps reporting
 * the bond and drops every connection at once, after showing its own dialog. The link cannot
 * see the cause; it sees a bonded ring whose connections keep dropping before discovery is done
 * or within 2 s of connecting. Three such connections in a row show `BondLostSuspected` (two
 * are not enough). A connection that got through discovery and stayed up 2 s starts the count
 * again. The link keeps reconnecting meanwhile, and never removes the bond: that is the user's
 * call, in system Settings. CoreBluetooth reports no such case, so upstream has none.
 *
 * Every test runs its connections through ONE link, as the app does.
 */
class BondLostSuspectedTest {

    /** HCI 0x05, "authentication failure": how a link whose encryption fails is dropped. */
    private val authenticationFailure = 5

    /** The current connection drops [afterMillis] from now. */
    private fun TestScope.dropAfter(ring: FakeGatt, afterMillis: Long) {
        advance(afterMillis)
        ring.dropConnection(authenticationFailure)
        runCurrent()
    }

    /** Waits out the reconnect delay the link shows (30 s once it shows `BondLostSuspected`); the next connection is then up. */
    private fun TestScope.reconnect(link: RingLink) {
        val delay = when (val state = link.state.value) {
            is LinkState.Reconnecting -> state.delay.toMillis()
            LinkState.BondLostSuspected -> LONGEST_RECONNECT_DELAY // shown once three attempts were made
            else -> throw AssertionError("no reconnect is waiting: $state")
        }
        advance(delay)
    }

    /** A bonded ring that never finishes discovery, connected through one link that has seen [drops] early drops. */
    private fun TestScope.droppingInDiscovery(drops: Int): Pair<FakeGatt, RingLink> {
        val (ring, link) = linkTo()
        ring.hold(Operation.DISCOVER_SERVICES)
        link.connect()
        runCurrent()
        repeat(drops) {
            if (it > 0) reconnect(link)
            assertEquals(LinkState.Discovering, link.state.value)
            dropAfter(ring, 0)
        }
        return ring to link
    }

    @Test
    fun threeConnectionsInARowDroppingBeforeDiscoveryMeanTheBondWasProbablyLost() = runTest {
        val (ring, link) = droppingInDiscovery(2)
        assertEquals(reconnecting(attempt = 2, seconds = 5), link.state.value, "two are not enough")

        reconnect(link)
        dropAfter(ring, 0)

        assertEquals(LinkState.BondLostSuspected, link.state.value)
    }

    @Test
    fun threeConnectionsDroppingWithin2SecondsAfterDiscoveryMeanTheBondWasProbablyLost() = runTest {
        val (ring, link) = linkTo()
        link.connect()
        runCurrent()

        dropAfter(ring, 1_999)
        reconnect(link)
        dropAfter(ring, 1_999)
        reconnect(link)
        assertEquals(LinkState.Authenticated, link.state.value, "discovery and the bring-up are done")
        dropAfter(ring, 1_999)

        assertEquals(LinkState.BondLostSuspected, link.state.value)
    }

    @Test
    fun aDropAtExactly2SecondsAfterDiscoveryDoesNotCount() = runTest {
        val (ring, link) = linkTo()
        link.connect()
        runCurrent()

        dropAfter(ring, 1_999)
        reconnect(link)
        dropAfter(ring, 1_999)
        reconnect(link)
        dropAfter(ring, 2_000)

        assertEquals(reconnecting(attempt = 3, seconds = 30), link.state.value)
    }

    @Test
    fun aConnectionThroughDiscoveryAndUp2100MillisecondsStartsTheCountAgain() = runTest {
        val (ring, link) = droppingInDiscovery(2)
        reconnect(link)
        ring.release(Operation.DISCOVER_SERVICES) // this connection gets through discovery

        dropAfter(ring, 2_100)
        assertEquals(reconnecting(attempt = 3, seconds = 30), link.state.value)
        ring.hold(Operation.DISCOVER_SERVICES)
        reconnect(link)
        dropAfter(ring, 0)
        reconnect(link)
        dropAfter(ring, 0)
        assertEquals(reconnecting(attempt = 5, seconds = 30), link.state.value, "two since the healthy one")
        reconnect(link)
        dropAfter(ring, 0)

        assertEquals(LinkState.BondLostSuspected, link.state.value)
    }

    @Test
    fun aDiscoveryThatFinishesAfter2SecondsStillMakesAHealthyConnection() = runTest {
        val (ring, link) = droppingInDiscovery(2)
        reconnect(link)
        advance(3_000)
        ring.release(Operation.DISCOVER_SERVICES) // discovery done 3 s after connecting
        runCurrent()
        ring.hold(Operation.DISCOVER_SERVICES)

        dropAfter(ring, 500)
        assertEquals(reconnecting(attempt = 3, seconds = 30), link.state.value)
        reconnect(link)
        dropAfter(ring, 0)

        assertEquals(reconnecting(attempt = 4, seconds = 30), link.state.value, "one early drop since the healthy one")
    }

    @Test
    fun aDropStillInDiscoveryAfter2SecondsCounts() = runTest {
        val (ring, link) = linkTo()
        ring.hold(Operation.DISCOVER_SERVICES)
        link.connect()
        runCurrent()

        dropAfter(ring, 9_000)
        reconnect(link)
        dropAfter(ring, 9_000)
        reconnect(link)
        dropAfter(ring, 9_000)

        assertEquals(LinkState.BondLostSuspected, link.state.value)
    }

    /**
     * Only a disconnect Android reports is the symptom. A discovery the ring never answers is the
     * link's own timeout: a slow or wedged ring, not a lost bond, and telling the user to remove
     * a working bond would do harm.
     */
    @Test
    fun discoveryTimeoutsAndErrorsAreNotDropsAndNeverSuggestALostBond() = runTest {
        val (ring, link) = linkTo()
        ring.hold(Operation.DISCOVER_SERVICES)
        link.connect()
        runCurrent()

        advance(10_000) // discovery times out
        reconnect(link)
        advance(10_000) // and again
        ring.release(Operation.DISCOVER_SERVICES)
        ring.failWith(Operation.DISCOVER_SERVICES, 133)
        reconnect(link) // the third connection's discovery fails with an error status

        assertEquals(3, ring.connects.size)
        assertEquals(reconnecting(attempt = 3, seconds = 30), link.state.value)
    }

    @Test
    fun earlyDropsOfARingWithNoBondNeverSuggestALostBond() = runTest {
        val (ring, link) = linkTo(unbondedRing())
        ring.hold(Operation.DISCOVER_SERVICES)
        link.connect()
        runCurrent()

        dropAfter(ring, 0)
        reconnect(link)
        dropAfter(ring, 0)
        reconnect(link)
        dropAfter(ring, 0)

        assertEquals(reconnecting(attempt = 3, seconds = 30), link.state.value)
    }

    @Test
    fun theLinkKeepsReconnectingShowsItAgainAndKeepsTheBond() = runTest {
        val (ring, link) = droppingInDiscovery(3)
        assertEquals(LinkState.BondLostSuspected, link.state.value)

        reconnect(link)
        assertEquals(LinkState.Discovering, link.state.value, "the next attempt is made")
        assertEquals(4, ring.connects.size)
        dropAfter(ring, 0)
        assertEquals(LinkState.BondLostSuspected, link.state.value)

        assertEquals(BondState.BONDED, ring.bondState())
        assertEquals(0, ring.createBondCalls)
        assertTrue(ring.log.all { it.startsWith("connect ") || it == "discoverServices" || it == "close" }, "${ring.log}")
    }

    @Test
    fun aHealthyConnectionAfterTheSuspicionStartsTheCountAgain() = runTest {
        val (ring, link) = droppingInDiscovery(3)
        ring.release(Operation.DISCOVER_SERVICES)
        reconnect(link)
        assertEquals(LinkState.Authenticated, link.state.value)
        advance(60_000)
        ring.hold(Operation.DISCOVER_SERVICES)

        ring.dropConnection(8) // a late drop: neither counted nor suspected
        runCurrent()
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        reconnect(link)
        dropAfter(ring, 0)
        assertEquals(reconnecting(attempt = 2, seconds = 5), link.state.value, "one early drop since the healthy one")
        reconnect(link)
        dropAfter(ring, 0)

        assertEquals(reconnecting(attempt = 3, seconds = 30), link.state.value, "two early drops since the healthy one")
    }

    private companion object {
        /** The reconnect delay once three attempts were made (`ReconnectBackoff`: 1 s, 5 s, then 30 s). */
        const val LONGEST_RECONNECT_DELAY = 30_000L
    }
}
