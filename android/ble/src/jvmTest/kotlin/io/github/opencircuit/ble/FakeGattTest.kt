package io.github.opencircuit.ble

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The scripted fake ring must be able to fail a test: Android allows one outstanding GATT
 * operation per connection, and a second one submitted before the first's callback is refused
 * on a phone. The fake turns that into a test failure, so the link's queue is proven, not assumed.
 */
class FakeGattTest {

    private val received = mutableListOf<GattEvent>()

    @Test
    fun aSecondOperationWhileOneIsOutstandingFailsTheTest() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.connect(SessionToken(1), Fixtures.ring, autoConnect = false) { received += it }

        assertFailsWith<AssertionError> { ring.discoverServices() }
        assertEquals(listOf("discoverServices submitted while CONNECT is outstanding"), ring.violations)
    }

    @Test
    fun anOperationAfterThePreviousCallbackIsAllowed() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.connect(SessionToken(1), Fixtures.ring, autoConnect = false) { received += it }
        runCurrent()

        ring.discoverServices()
        runCurrent()

        assertEquals(listOf("connect autoConnect=false", "discoverServices"), ring.log)
        assertEquals(emptyList(), ring.violations)
        assertEquals(2, received.size)
    }

    @Test
    fun aHeldCallbackKeepsItsOperationOutstandingUntilReleased() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(FakeGatt.Operation.CONNECT)
        ring.connect(SessionToken(1), Fixtures.ring, autoConnect = false) { received += it }
        runCurrent()

        assertEquals(emptyList(), received)
        assertFailsWith<AssertionError> { ring.discoverServices() }

        ring.release(FakeGatt.Operation.CONNECT)
        runCurrent()
        assertEquals(1, received.size)
    }

    @Test
    fun noCallbackFollowsAClose() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.connect(SessionToken(1), Fixtures.ring, autoConnect = false) { received += it }
        ring.close()
        runCurrent()

        assertEquals(emptyList(), received)
        assertEquals(listOf("connect autoConnect=false", "close"), ring.log)
    }

    @Test
    fun aDroppedConnectionReportsTheDisconnectAndNeverAnswersItsOutstandingOperation() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val session = SessionToken(1)
        ring.connect(session, Fixtures.ring, autoConnect = false) { received += it }
        runCurrent()
        ring.hold(FakeGatt.Operation.DISCOVER_SERVICES)
        ring.discoverServices()

        ring.dropConnection(8)
        ring.release(FakeGatt.Operation.DISCOVER_SERVICES)
        runCurrent()

        val last = received.last() as GattEvent.ConnectionChanged
        assertEquals(8, last.status)
        assertEquals(false, last.connected)
        assertTrue(received.none { it is GattEvent.ServicesDiscovered }, "the dropped connection's discovery never answers")
    }

    @Test
    fun connectsAreRecordedWithTheirAddressAndOpenConnectionsAreCountedUntilClosed() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val first = SessionToken(1)
        val second = SessionToken(2)
        ring.connect(first, Fixtures.ring, autoConnect = false) { received += it }
        runCurrent()
        ring.dropConnection(8)
        ring.connect(second, Fixtures.ring, autoConnect = true) { received += it }
        runCurrent()

        assertEquals(
            listOf(FakeGatt.ConnectCall(Fixtures.RING_ADDRESS, false), FakeGatt.ConnectCall(Fixtures.RING_ADDRESS, true)),
            ring.connects,
        )
        assertEquals(listOf(first, second), ring.sessions)
        assertEquals(2, ring.maxOpenConnections, "the dropped connection was never closed")
    }

    @Test
    fun aClearedFailureAnswersWithSuccessAgain() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.failWith(FakeGatt.Operation.CONNECT, 133)
        ring.connect(SessionToken(1), Fixtures.ring, autoConnect = false) { received += it }
        runCurrent()
        ring.close()
        ring.clearFailure(FakeGatt.Operation.CONNECT)
        ring.connect(SessionToken(2), Fixtures.ring, autoConnect = false) { received += it }
        runCurrent()

        assertEquals(listOf(133, 0), received.map { (it as GattEvent.ConnectionChanged).status })
        assertEquals(1, ring.maxOpenConnections)
    }

    @Test
    fun everyEventCarriesTheSessionOfItsConnection() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val session = SessionToken(7)
        ring.connect(session, Fixtures.ring, autoConnect = false) { received += it }
        runCurrent()
        ring.notify(Fixtures.challengeFrame)

        assertEquals(2, received.size)
        assertTrue(received.all { it.session === session })
    }
}
