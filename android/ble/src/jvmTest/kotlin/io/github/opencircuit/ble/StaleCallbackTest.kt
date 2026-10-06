package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * Callbacks of a closed connection change nothing, even for the same ring: Android drops the
 * callbacks of other addresses but not those of an older client for the same one. The link
 * tells them apart by the connection's session token (upstream's identity guard,
 * `ios/OpenCircuit/BLE/RingScanner.swift:996`, and its delegate check,
 * `ios/OpenCircuit/BLE/RingSession.swift:1027` @ b1c2fdd). One link instance is reused across
 * every connection, and each connection starts from scratch.
 */
class StaleCallbackTest {

    private val coldBringUpLog = listOf(
        "connect autoConnect=false",
        "discoverServices",
        "requestMtu 517",
        "setNotifications 8327ad97 on",
        "writeDescriptor 8327ad97/00002902 01 00",
        "read 00002a23",
        "read 00002a26",
        "read 00002a29",
        "read 00002a27",
        "write 8327ad98 01 00 00",
        "write 8327ad98 01 01 31 82 67 00",
    )

    /** Every kind of callback, as the closed connection [old] would have produced it. */
    private fun staleCallbacks(old: SessionToken): List<GattEvent> = listOf(
        GattEvent.ConnectionChanged(old, status = 0, connected = true),
        GattEvent.ServicesDiscovered(old, status = 0, setOf(GattPort.NOTIFY, GattPort.WRITE)),
        GattEvent.MtuChanged(old, mtu = 185, status = 0),
        GattEvent.DescriptorWritten(old, GattPort.NOTIFY, GattPort.CCCD, status = 0),
        GattEvent.CharacteristicRead(old, GattPort.SYSTEM_ID, Fixtures.hex("0102030405060708"), status = 0),
        GattEvent.CharacteristicWritten(old, GattPort.WRITE, status = 0),
        GattEvent.Notification(old, GattPort.NOTIFY, Fixtures.challengeFrame),
        GattEvent.Notification(old, GattPort.NOTIFY, Fixtures.firstDataFrame),
        GattEvent.ConnectionChanged(old, status = 8, connected = false),
    )

    /** A link authenticated on its first connection, then dropped by the ring. */
    private fun TestScope.droppedAfterBringUp(): Triple<FakeGatt, RingLink, List<String>> {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val frames = recordFrames(link)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
        ring.dropConnection(8)
        runCurrent()
        return Triple(ring, link, frames)
    }

    /** The calls made on the newest connection (everything after the last close). */
    private fun FakeGatt.newestConnectionLog(): List<String> = log.subList(log.lastIndexOf("close") + 1, log.size)

    @Test
    fun oneLinkConnectsDropsReconnectsAndBringsUpAgainFromScratch() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val frames = recordFrames(link)
        val teardowns = recordTeardowns(link)
        link.connect()
        runCurrent()

        ring.dropConnection(8)
        runCurrent()
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        advance(1_000)

        assertEquals(coldBringUpLog + "close" + coldBringUpLog, ring.log, "the whole bring-up again, auth included")
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(listOf("15 00 08 0a b0 a7", "15 00 08 0a b0 a7"), frames)
        assertEquals(listOf(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 0)), teardowns)
        assertEquals(2, ring.sessions.size)
        assertNotSame(ring.sessions[0], ring.sessions[1], "a new session token for the new connection")
        assertEquals(1, ring.maxOpenConnections)
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun callbacksOfTheClosedConnectionChangeNothingOnTheNewOne() = runTest {
        val (ring, link, frames) = droppedAfterBringUp()
        ring.hold(Operation.DISCOVER_SERVICES)
        advance(1_000) // reconnected; discovery is in flight on the new connection
        val old = ring.sessions.first()
        val state = link.state.value
        val info = link.info.value
        val log = ring.log
        val delivered = frames.toList()
        assertEquals(LinkState.Discovering, state)

        staleCallbacks(old).forEach(ring::deliver)
        runCurrent()

        assertEquals(state, link.state.value)
        assertEquals(info, link.info.value)
        assertEquals(log, ring.log, "no operation answered, no write, no close")
        assertEquals(delivered, frames, "no frame of the closed connection delivered")

        // The new connection's own discovery is still the operation in flight.
        ring.release(Operation.DISCOVER_SERVICES)
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(coldBringUpLog, ring.newestConnectionLog())
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun callbacksOfTheClosedConnectionChangeNothingWhileTheReconnectWaits() = runTest {
        val (ring, link, frames) = droppedAfterBringUp()
        val old = ring.sessions.first()
        val log = ring.log
        val delivered = frames.toList()

        staleCallbacks(old).forEach(ring::deliver)
        runCurrent()

        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        assertEquals(log, ring.log)
        assertEquals(delivered, frames)
        advance(999)
        assertEquals(1, ring.connects.size, "the stale callbacks started nothing early")
        advance(1)
        assertEquals(2, ring.connects.size)
    }

    @Test
    fun aDataFrameOfTheClosedConnectionDoesNotAuthenticateTheNewOne() = runTest {
        val (ring, link, frames) = droppedAfterBringUp()
        ring.hold(Operation.WRITE) // the new connection's 01 00 00 is never answered
        advance(1_000)
        assertEquals(LinkState.Authenticating, link.state.value)
        val delivered = frames.toList()

        ring.deliver(GattEvent.Notification(ring.sessions.first(), GattPort.NOTIFY, Fixtures.firstDataFrame))
        runCurrent()

        assertEquals(LinkState.Authenticating, link.state.value)
        assertEquals(delivered, frames)
    }
}
