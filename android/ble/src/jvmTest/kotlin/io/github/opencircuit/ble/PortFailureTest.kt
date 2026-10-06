package io.github.opencircuit.ble

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A GATT call that throws something the Android adapter does not catch (for example, a
 * framework error after Bluetooth restarted) fails that operation and closes the connection like
 * any other failure; the link keeps running and reconnects (PORTING.md D-187). Without this, the
 * exception would end the link's event loop, and in the app's scope crash the process.
 */
class PortFailureTest {

    /** The fake ring, except that each armed call throws once. */
    private class ThrowingOnce(private val inner: FakeGatt, vararg calls: String) : GattPort by inner {
        private val armed = calls.toMutableSet()

        fun arm(call: String) {
            armed += call
        }

        private fun maybeThrow(call: String) {
            if (armed.remove(call)) throw IllegalStateException("$call failed inside the Bluetooth stack")
        }

        override fun connect(session: SessionToken, ring: RememberedRing, autoConnect: Boolean, events: GattPort.EventSink): Boolean {
            maybeThrow("connect")
            return inner.connect(session, ring, autoConnect, events)
        }

        override fun discoverServices(): Boolean {
            maybeThrow("discoverServices")
            return inner.discoverServices()
        }

        override fun write(characteristic: GattPort.Characteristic, value: ByteArray): Boolean {
            maybeThrow("write")
            return inner.write(characteristic, value)
        }

        override fun close() {
            inner.close()
            maybeThrow("close")
        }
    }

    @Test
    fun aThrowingDiscoveryFailsTheOperationAndTheLinkReconnects() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ThrowingOnce(ring, "discoverServices"), backgroundScope)

        link.connect()
        runCurrent()
        assertEquals(listOf("connect autoConnect=false", "close"), ring.log)
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)

        advance(1_000)
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun aThrowingConnectFailsTheAttemptAndTheLinkReconnects() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ThrowingOnce(ring, "connect"), backgroundScope)

        link.connect()
        runCurrent()
        assertEquals(listOf("close"), ring.log)
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)

        advance(1_000)
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun aThrowingFeatureWriteAnswersItsSenderAndTheLinkReconnects() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val port = ThrowingOnce(ring)
        val link = RingLink(Fixtures.ring, port, backgroundScope)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)

        port.arm("write")
        assertEquals(SendResult.Failed(SendFailure.GATT_ERROR), link.send(Fixtures.hex("950000")))
        assertEquals("close", ring.log.last())

        advance(1_000)
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(SendResult.Sent, link.send(Fixtures.hex("950000")))
    }

    @Test
    fun aThrowingCloseStillLetsTheLinkReconnect() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ThrowingOnce(ring, "close"), backgroundScope)
        link.connect()
        runCurrent()

        ring.dropConnection(8)
        runCurrent()
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)

        advance(1_000)
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(2, ring.connects.size)
    }
}
