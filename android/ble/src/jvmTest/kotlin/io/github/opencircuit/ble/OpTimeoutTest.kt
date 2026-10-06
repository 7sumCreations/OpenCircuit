package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.ConnectCall
import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every GATT operation has its own timeout (PORTING.md D-185): when the ring never answers, the
 * operation is still waiting 1 ms before its timeout and has failed at it; the connection is then
 * closed and the next attempt is made to the same address, never by scanning.
 */
class OpTimeoutTest {

    /** A link to a ring that never answers [operation]; the clock starts when it is submitted. */
    private fun TestScope.linkWithRingHolding(operation: Operation): Pair<FakeGatt, RingLink> {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(operation)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()
        return ring to link
    }

    /** Asserts the withheld operation [line] is still pending at [millis] − 1 and has failed at [millis]. */
    private fun TestScope.assertTimesOutAfter(ring: FakeGatt, link: RingLink, millis: Long, line: String, pendingState: LinkState) {
        advance(millis - 1)
        assertEquals(line, ring.log.last(), "still waiting 1 ms before the timeout")
        assertEquals(pendingState, link.state.value)

        advance(1)
        assertEquals(listOf(line, "close"), ring.log.takeLast(2), "failed and closed at the timeout")
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun aDirectConnectTimesOutAfter35Seconds() = runTest {
        val (ring, link) = linkWithRingHolding(Operation.CONNECT)
        assertTimesOutAfter(ring, link, 35_000, "connect autoConnect=false", LinkState.Connecting)
    }

    @Test
    fun serviceDiscoveryTimesOutAfter10Seconds() = runTest {
        val (ring, link) = linkWithRingHolding(Operation.DISCOVER_SERVICES)
        assertTimesOutAfter(ring, link, 10_000, "discoverServices", LinkState.Discovering)
    }

    @Test
    fun theMtuExchangeTimesOutAfter5Seconds() = runTest {
        val (ring, link) = linkWithRingHolding(Operation.REQUEST_MTU)
        assertTimesOutAfter(ring, link, 5_000, "requestMtu 517", LinkState.Preparing)
    }

    @Test
    fun theNotificationDescriptorWriteTimesOutAfter5Seconds() = runTest {
        val (ring, link) = linkWithRingHolding(Operation.WRITE_DESCRIPTOR)
        assertTimesOutAfter(ring, link, 5_000, "writeDescriptor 8327ad97/00002902 01 00", LinkState.Preparing)
    }

    @Test
    fun aDeviceInformationReadTimesOutAfter5Seconds() = runTest {
        val (ring, link) = linkWithRingHolding(Operation.READ)
        assertTimesOutAfter(ring, link, 5_000, "read 00002a23", LinkState.Preparing)
    }

    @Test
    fun theAuthStartWriteTimesOutAfter5Seconds() = runTest {
        val (ring, link) = linkWithRingHolding(Operation.WRITE)
        assertTimesOutAfter(ring, link, 5_000, "write 8327ad98 01 00 00", LinkState.Authenticating)
    }

    @Test
    fun aFeatureWriteTimesOutAfter5SecondsAndItsSenderLearnsIt() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()
        ring.hold(Operation.WRITE)

        val result = backgroundScope.async { link.send(hex("950000")) }
        runCurrent()
        advance(4_999)
        assertFalse(result.isCompleted, "still waiting 1 ms before the timeout")
        assertEquals("write 8327ad98 95 00 00", ring.log.last())

        advance(1)
        assertTrue(result.isCompleted)
        assertEquals(SendResult.Failed(SendFailure.TIMED_OUT), result.getCompleted())
        assertEquals("close", ring.log.last())
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
    }

    @Test
    fun aStandingConnectionNeverTimesOut() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.failWith(Operation.CONNECT, 147) // out of reach: the next attempt is the standing connection
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()
        ring.clearFailure(Operation.CONNECT)
        ring.hold(Operation.CONNECT)
        advance(1_000)
        assertEquals("connect autoConnect=true", ring.log.last())
        val log = ring.log
        val states = recordStates(link)

        advance(10 * 60_000)

        assertEquals(log, ring.log, "no close and no other attempt: the standing connection still waits")
        assertEquals(listOf<LinkState>(LinkState.WaitingForRing), states)
    }

    @Test
    fun timedOutConnectionsReconnectToTheSameAddressAfterOneFiveAndThirtySecondsWithoutScanning() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(Operation.DISCOVER_SERVICES) // every connection connects, then never finishes discovery
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()

        var expectedConnects = 1
        for ((attempt, delaySeconds) in listOf(1 to 1L, 2 to 5L, 3 to 30L)) {
            advance(10_000) // discovery times out
            assertEquals(reconnecting(attempt, delaySeconds), link.state.value)
            advance(delaySeconds * 1_000 - 1)
            assertEquals(expectedConnects, ring.connects.size, "no attempt before the delay is over")
            advance(1)
            expectedConnects++
            assertEquals(expectedConnects, ring.connects.size, "attempt $attempt starts when its delay is over")
        }

        val direct = ConnectCall(Fixtures.RING_ADDRESS, autoConnect = false)
        assertEquals(List(4) { direct }, ring.connects)
        assertEquals(3, ring.log.count { it == "close" })
        val verbs = ring.log.map { it.substringBefore(' ') }.toSet()
        assertEquals(setOf("connect", "discoverServices", "close"), verbs, "nothing but connect, discovery and close: no scan")
        assertEquals(1, ring.maxOpenConnections)
    }
}
