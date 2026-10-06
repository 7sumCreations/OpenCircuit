package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A connection that drops while an operation is in flight fails that operation and tears down
 * cleanly: the waiting caller hears why, the connection is closed exactly once, one teardown is
 * published, the operation's timer never fires later, and the next connection is opened only
 * after the old one was closed, so two are never open at once.
 */
class DisconnectMidOpTest {

    private val dropped = LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 0)

    /**
     * The teardown of an authenticated connection nobody collected `frames` from: the frame that
     * authenticated it (`15 00 08 0a b0 a7`) was never taken, so it is counted as undelivered.
     */
    private val droppedAfterAuth = LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 1)

    private fun TestScope.authenticated(): Triple<FakeGatt, RingLink, List<LinkTeardown>> {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val teardowns = recordTeardowns(link)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
        return Triple(ring, link, teardowns)
    }

    @Test
    fun aDropDuringAFeatureWriteFailsItAndEveryWriteQueuedBehindIt() = runTest {
        val (ring, link, teardowns) = authenticated()
        ring.hold(Operation.WRITE)
        val inFlight = backgroundScope.async { link.send(hex("950000")) }
        runCurrent()
        val queued = backgroundScope.async { link.send(hex("960000")) }
        runCurrent()
        assertEquals("write 8327ad98 95 00 00", ring.log.last())

        ring.dropConnection(8)
        runCurrent()

        assertTrue(inFlight.isCompleted, "the write in flight is not left waiting")
        assertEquals(SendResult.Failed(SendFailure.LINK_LOST), inFlight.getCompleted())
        assertTrue(queued.isCompleted)
        assertEquals(SendResult.Failed(SendFailure.LINK_LOST), queued.getCompleted())
        assertEquals("close", ring.log.last())
        assertEquals(1, ring.log.count { it == "close" })
        assertEquals(listOf(droppedAfterAuth), teardowns)
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
    }

    @Test
    fun aDropDuringAReadLeavesNoOperationBehindAndTheNextConnectionBringsUpCleanly() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(Operation.READ)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val teardowns = recordTeardowns(link)
        link.connect()
        runCurrent()
        assertEquals("read 00002a23", ring.log.last())

        ring.dropConnection(8)
        runCurrent()
        ring.release(Operation.READ)
        advance(1_000)

        assertEquals(LinkState.Authenticated, link.state.value, "nothing of the dropped connection blocks the new one")
        assertEquals(listOf(dropped), teardowns)
        assertEquals(1, ring.log.count { it == "close" })
        assertEquals(1, ring.maxOpenConnections)
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun aDropDuringTheMtuExchangeClosesOnceAndNeverOpensASecondConnection() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(Operation.REQUEST_MTU)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()

        ring.dropConnection(19)
        runCurrent()
        assertEquals(listOf("requestMtu 517", "close"), ring.log.takeLast(2))
        ring.release(Operation.REQUEST_MTU)
        advance(1_000)

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(2, ring.connects.size)
        assertEquals(1, ring.maxOpenConnections)
    }

    @Test
    fun theTimerOfTheOperationCutOffByTheDropNeverFiresLater() = runTest {
        val (ring, link, teardowns) = authenticated()
        ring.hold(Operation.WRITE)
        backgroundScope.async { link.send(hex("950000")) } // its timeout would fire 5 s from now
        runCurrent()
        advance(1_000)
        ring.dropConnection(8)
        runCurrent()
        ring.release(Operation.WRITE)
        advance(1_000) // reconnected and authenticated again, 2 s in
        assertEquals(LinkState.Authenticated, link.state.value)
        val log = ring.log

        advance(10_000)

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(log, ring.log, "no close of the new connection")
        assertEquals(listOf(droppedAfterAuth), teardowns)
    }

    @Test
    fun disconnectTearsDownAsTheUsersChoiceAndNeverReconnects() = runTest {
        val (ring, link, teardowns) = authenticated()

        link.disconnect()
        runCurrent()
        advance(10 * 60_000)

        assertEquals(LinkState.Idle, link.state.value)
        // The frame that authenticated the connection was never collected.
        assertEquals(listOf(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 1)), teardowns)
        assertEquals(1, ring.connects.size)
        assertEquals("close", ring.log.last())
    }

    @Test
    fun disconnectWhileAReconnectWaitsCancelsIt() = runTest {
        val (ring, link, teardowns) = authenticated()
        ring.dropConnection(8)
        runCurrent()
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)

        link.disconnect()
        runCurrent()
        advance(10 * 60_000)

        assertEquals(LinkState.Idle, link.state.value)
        assertEquals(1, ring.connects.size)
        assertEquals(listOf(droppedAfterAuth), teardowns, "nothing was connected to tear down a second time")
    }

    @Test
    fun aConnectionThatNeverConnectedPublishesNoTeardown() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.failWith(Operation.CONNECT, 133)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val teardowns = recordTeardowns(link)

        link.connect()
        runCurrent()

        assertEquals(listOf("connect autoConnect=false", "close"), ring.log)
        assertEquals(emptyList(), teardowns)
    }
}
