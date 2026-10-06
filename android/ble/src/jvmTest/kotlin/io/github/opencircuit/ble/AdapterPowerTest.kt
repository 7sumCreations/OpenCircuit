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
 * Bluetooth turned off cancels everything and closes the connection; the link then waits in
 * `BluetoothOff` without trying, and turning Bluetooth on re-arms the connection the user asked
 * for (upstream `reconnectWhenPoweredOn`, `ios/OpenCircuit/BLE/RingScanner.swift:546-551` and
 * `:803-859` @ b1c2fdd). GrapheneOS can turn Bluetooth off on its own when idle, so this is an
 * ordinary state, not an error.
 */
class AdapterPowerTest {

    private fun TestScope.authenticated(): Triple<FakeGatt, LinkCore, List<LinkTeardown>> {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = LinkCore(Fixtures.ring, ring, backgroundScope)
        val teardowns = recordTeardowns(link)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
        return Triple(ring, link, teardowns)
    }

    @Test
    fun bluetoothOffMidWriteFailsTheWriteClosesAndWaitsWithoutTrying() = runTest {
        val (ring, link, teardowns) = authenticated()
        ring.hold(Operation.WRITE)
        val write = backgroundScope.async { link.send(hex("950000")) }
        runCurrent()

        link.onAdapterState(AdapterState.OFF)
        runCurrent()

        assertTrue(write.isCompleted)
        assertEquals(SendResult.Failed(SendFailure.LINK_LOST), write.getCompleted())
        assertEquals("close", ring.log.last())
        assertEquals(LinkState.BluetoothOff, link.state.value)
        // The frame that authenticated the connection was never collected.
        assertEquals(listOf(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 1)), teardowns)

        advance(10 * 60_000)
        assertEquals(1, ring.connects.size, "no attempt while Bluetooth is off")
        assertEquals(LinkState.BluetoothOff, link.state.value)
    }

    @Test
    fun bluetoothOnReconnectsDirectAtOnce() = runTest {
        val (ring, link, _) = authenticated()
        link.onAdapterState(AdapterState.OFF)
        runCurrent()
        advance(60_000)

        link.onAdapterState(AdapterState.ON)
        runCurrent()

        assertEquals(listOf(false, false), ring.connects.map { it.autoConnect })
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(1, ring.maxOpenConnections)
    }

    @Test
    fun turningOffCountsAsOffAndTurningOnWaitsForOn() = runTest {
        val (ring, link, _) = authenticated()

        link.onAdapterState(AdapterState.TURNING_OFF)
        runCurrent()
        assertEquals(LinkState.BluetoothOff, link.state.value)
        assertEquals("close", ring.log.last())

        link.onAdapterState(AdapterState.OFF)
        link.onAdapterState(AdapterState.TURNING_ON)
        runCurrent()
        assertEquals(LinkState.BluetoothOff, link.state.value)
        assertEquals(1, ring.connects.size, "not before the adapter is on")
        assertEquals(1, ring.log.count { it == "close" }, "closed once")

        link.onAdapterState(AdapterState.ON)
        runCurrent()
        assertEquals(2, ring.connects.size)
    }

    @Test
    fun bluetoothOffCancelsAWaitingReconnect() = runTest {
        val (ring, link, _) = authenticated()
        ring.dropConnection(8)
        runCurrent()
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)

        link.onAdapterState(AdapterState.OFF)
        runCurrent()
        advance(10 * 60_000)

        assertEquals(LinkState.BluetoothOff, link.state.value)
        assertEquals(1, ring.connects.size)

        link.onAdapterState(AdapterState.ON)
        runCurrent()
        assertEquals(2, ring.connects.size)
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun aConnectAskedForWhileBluetoothIsOffWaitsForItToComeOn() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = LinkCore(Fixtures.ring, ring, backgroundScope)
        link.onAdapterState(AdapterState.OFF)
        runCurrent()
        assertEquals(LinkState.BluetoothOff, link.state.value)

        link.connect()
        runCurrent()
        assertEquals(emptyList(), ring.log)
        assertEquals(LinkState.BluetoothOff, link.state.value)

        link.onAdapterState(AdapterState.ON)
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun disconnectWhileBluetoothIsOffIsIdleAndBluetoothComingBackDoesNotReconnect() = runTest {
        val (ring, link, _) = authenticated()
        link.onAdapterState(AdapterState.OFF)
        runCurrent()

        link.disconnect()
        runCurrent()
        assertEquals(LinkState.Idle, link.state.value)

        link.onAdapterState(AdapterState.ON)
        runCurrent()
        assertEquals(LinkState.Idle, link.state.value)
        assertEquals(1, ring.connects.size)
    }

    @Test
    fun bluetoothComingBackWithNoConnectionWantedLeavesTheLinkIdle() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = LinkCore(Fixtures.ring, ring, backgroundScope)

        link.onAdapterState(AdapterState.OFF)
        runCurrent()
        assertEquals(LinkState.BluetoothOff, link.state.value)
        link.onAdapterState(AdapterState.ON)
        runCurrent()

        assertEquals(LinkState.Idle, link.state.value)
        assertEquals(emptyList(), ring.log)
    }
}
