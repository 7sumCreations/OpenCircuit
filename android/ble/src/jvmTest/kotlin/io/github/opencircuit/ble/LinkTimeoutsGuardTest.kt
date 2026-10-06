package io.github.opencircuit.ble

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every tuned value of the link pinned to a literal typed here, never read back from the Kotlin
 * constant: the per-operation timeouts (PORTING.md D-185) and the reconnect thresholds
 * (PORTING.md D-186; the 6 s stable period is upstream's `connectStablePeriod`,
 * `ios/OpenCircuit/BLE/RingScanner.swift:228` @ b1c2fdd).
 */
class LinkTimeoutsGuardTest {

    private fun s(seconds: Long): Duration = Duration.ofSeconds(seconds)

    @Test
    fun everyTimeoutIsPinnedToItsChosenValue() {
        assertEquals(s(35), LinkTimeouts.DIRECT_CONNECT, "a backstop over the stack's own 30 s direct-connect timeout")
        assertNull(LinkTimeouts.STANDING_CONNECT, "a standing connection waits for the ring with no timeout")
        assertEquals(s(10), LinkTimeouts.DISCOVER)
        assertEquals(s(40), LinkTimeouts.BOND, "the 30 s pairing timeout plus time for the user to answer the prompt")
        assertEquals(s(5), LinkTimeouts.MTU)
        assertEquals(s(5), LinkTimeouts.DESCRIPTOR_WRITE)
        assertEquals(s(5), LinkTimeouts.READ)
        assertEquals(s(5), LinkTimeouts.WRITE)
    }

    @Test
    fun eachOperationWaitsForItsOwnTimeout() {
        assertEquals(s(35), GattOp.Connect(autoConnect = false).timeout)
        assertNull(GattOp.Connect(autoConnect = true).timeout)
        assertEquals(s(10), GattOp.DiscoverServices.timeout)
        assertEquals(s(5), GattOp.RequestMtu.timeout)
        assertEquals(s(5), GattOp.EnableNotifications.timeout)
        assertEquals(s(5), GattOp.Read(GattPort.SYSTEM_ID).timeout)
        assertEquals(s(5), GattOp.Write(byteArrayOf(0x01, 0x00, 0x00), GattOp.Write.Purpose.AUTH_START).timeout)
    }

    @Test
    fun theReconnectThresholdsArePinned() {
        assertEquals(s(20), ReconnectPolicy.LATE_FAILURE_AFTER, "a 133 this long after the connect started means the ring is out of reach")
        assertEquals(s(6), ReconnectPolicy.STABLE_AFTER, "upstream connectStablePeriod")
        assertEquals(133, ReconnectPolicy.GATT_ERROR)
        assertEquals(147, ReconnectPolicy.GATT_CONNECTION_TIMEOUT)
    }
}
