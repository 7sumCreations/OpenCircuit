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
