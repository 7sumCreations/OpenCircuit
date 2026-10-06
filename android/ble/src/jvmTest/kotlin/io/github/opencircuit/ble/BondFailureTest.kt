package io.github.opencircuit.ble

import io.github.opencircuit.ble.GattPort.BondState
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Every way the wait for the bond can end badly. Android would not start the bond, the bond
 * started and fell back to "not bonded" (the user declined or ignored the prompt), or no bond
 * came within 40 s: each ends in `PairingFailed` with its own reason, closes the connection and
 * is NOT retried, since a retry would put the same pairing prompt in front of the user again.
 * The link waits there until `connect()` is called. A dropped connection while waiting is an
 * ordinary drop: the next attempt is made after the usual delay.
 */
class BondFailureTest {

    /** A link to a ring with no bond, connected and waiting for the bond it asked for. */
    private fun TestScope.waitingForTheBond(): Pair<FakeGatt, LinkCore> {
        val (ring, link) = linkTo(unbondedRing())
        link.connect()
        runCurrent()
        assertEquals(LinkState.PairingNeeded, link.state.value)
        return ring to link
    }

    /** Nothing is tried again on its own for ten minutes. */
    private fun TestScope.assertNotRetried(ring: FakeGatt, link: RingLink, failed: LinkState) {
        val log = ring.log
        advance(10 * 60_000)
        assertEquals(log, ring.log, "nothing tried after the pairing failed")
        assertEquals(1, ring.connects.size)
        assertEquals(failed, link.state.value)
    }

    @Test
    fun aBondRequestAndroidWillNotStartFailsThePairingAndIsNotRetried() = runTest {
        val (ring, link) = linkTo(unbondedRing())
        ring.refuseBondRequests()
        val teardowns = recordTeardowns(link)

        link.connect()
        runCurrent()

        val failed = LinkState.PairingFailed(PairingFailure.BOND_REQUEST_REJECTED)
        assertEquals(failed, link.state.value)
        assertEquals(listOf("connect autoConnect=false", "discoverServices", "createBond", "close"), ring.log)
        assertEquals(listOf(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 0)), teardowns)
        assertNotRetried(ring, link, failed)
    }

    @Test
    fun aBondRequestThatThrowsFailsThePairingLikeARefusal() = runTest {
        val gatt = object : GattPort by FakeGatt(backgroundScope, unbondedRing()) {
            override fun createBond(): Boolean = throw IllegalStateException("no adapter")
        }
        val link = LinkCore(Fixtures.ring, gatt, backgroundScope)

        link.connect()
        runCurrent()

        assertEquals(LinkState.PairingFailed(PairingFailure.BOND_REQUEST_REJECTED), link.state.value)
    }

    /** A failed read is no answer, so it is neither "no bond" (a needless prompt) nor a pairing failure. */
    @Test
    fun aBondStateThatCannotBeReadClosesAndReconnectsWithoutAskingToBond() = runTest {
        val fake = FakeGatt(backgroundScope, unbondedRing())
        val gatt = object : GattPort by fake {
            override fun bondState(): GattPort.BondState = throw IllegalStateException("no adapter")
        }
        val link = LinkCore(Fixtures.ring, gatt, backgroundScope)

        link.connect()
        runCurrent()

        assertEquals(listOf("connect autoConnect=false", "discoverServices", "close"), fake.log)
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
    }

    @Test
    fun aBondThatStartsAndFallsBackToNoneFailsThePairingAndIsNotRetried() = runTest {
        val (ring, link) = waitingForTheBond()

        ring.changeBondState(BondState.BONDING)
        ring.changeBondState(BondState.NONE) // declined, or the prompt ignored until it timed out
        runCurrent()

        val failed = LinkState.PairingFailed(PairingFailure.BOND_NOT_COMPLETED)
        assertEquals(failed, link.state.value)
        assertEquals("close", ring.log.last())
        assertFalse(link.info.value.bonded)
        assertNotRetried(ring, link, failed)
        assertEquals(1, ring.createBondCalls, "no second request")
    }

    @Test
    fun aRingBondingOnItsOwnThatFallsBackToNoneFailsThePairing() = runTest {
        val (ring, link) = linkTo(Fixtures.acceptingRing().copy(bondState = BondState.BONDING))
        link.connect()
        runCurrent()

        ring.changeBondState(BondState.NONE)
        runCurrent()

        assertEquals(LinkState.PairingFailed(PairingFailure.BOND_NOT_COMPLETED), link.state.value)
        assertEquals(0, ring.createBondCalls)
    }

    /**
     * Android announces "bonding" when a requested bond starts; a "not bonded" before that is a
     * broadcast left over from earlier, not the end of this bond, which then still completes.
     */
    @Test
    fun aNotBondedBroadcastBeforeTheBondStartedDoesNotFailThePairing() = runTest {
        val (ring, link) = waitingForTheBond()

        ring.changeBondState(BondState.NONE)
        runCurrent()
        assertEquals(LinkState.PairingNeeded, link.state.value)
        ring.changeBondState(BondState.BONDING)
        ring.changeBondState(BondState.BONDED)
        runCurrent()

        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun noBondWithin40SecondsFailsThePairingAndIsNotRetried() = runTest {
        val (ring, link) = waitingForTheBond()
        ring.changeBondState(BondState.BONDING)
        runCurrent()

        advance(39_999)
        assertEquals(LinkState.PairingNeeded, link.state.value, "still waiting 1 ms before the bond timeout")
        assertEquals("createBond", ring.log.last())

        advance(1)
        val failed = LinkState.PairingFailed(PairingFailure.BOND_TIMED_OUT)
        assertEquals(failed, link.state.value)
        assertEquals("close", ring.log.last())
        assertNotRetried(ring, link, failed)
    }

    @Test
    fun aDropWhileWaitingForTheBondReconnectsAfterTheUsualDelayAndItsBondTimeoutNeverFires() = runTest {
        val (ring, link) = waitingForTheBond()
        advance(30_000)

        ring.dropConnection(8)
        runCurrent()
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        advance(1_000)
        assertEquals(LinkState.PairingNeeded, link.state.value, "the next connection asks again")
        assertEquals(2, ring.createBondCalls)

        advance(10_000) // 41 s after the first wait began: only its own timeout could fire here
        assertEquals(LinkState.PairingNeeded, link.state.value)
        advance(29_999)
        assertEquals(LinkState.PairingNeeded, link.state.value)
        advance(1)
        assertEquals(LinkState.PairingFailed(PairingFailure.BOND_TIMED_OUT), link.state.value)
    }

    @Test
    fun aBondThatArrivesAfterThePairingFailedDoesNotReopenTheLink() = runTest {
        val (ring, link) = waitingForTheBond()
        advance(40_000)
        val log = ring.log

        ring.changeBondState(BondState.BONDED)
        runCurrent()

        assertEquals(LinkState.PairingFailed(PairingFailure.BOND_TIMED_OUT), link.state.value)
        assertEquals(log, ring.log)
    }

    @Test
    fun connectAfterAFailedPairingAsksForTheBondAgain() = runTest {
        val (ring, link) = waitingForTheBond()
        ring.changeBondState(BondState.BONDING)
        ring.changeBondState(BondState.NONE)
        runCurrent()

        link.connect()
        runCurrent()
        ring.changeBondState(BondState.BONDING)
        ring.changeBondState(BondState.BONDED)
        runCurrent()

        assertEquals(2, ring.createBondCalls)
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun disconnectWhileWaitingForTheBondEndsTheWaitWithoutAPairingFailure() = runTest {
        val (ring, link) = waitingForTheBond()

        link.disconnect()
        runCurrent()
        advance(60_000)

        assertEquals(LinkState.Idle, link.state.value)
        assertEquals("close", ring.log.last())
    }
}
