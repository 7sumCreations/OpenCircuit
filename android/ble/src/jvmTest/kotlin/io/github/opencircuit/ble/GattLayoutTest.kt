package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A device whose discovered services lack the ring's notify or write characteristic is not
 * the ring's GATT layout: the link closes that connection before any other operation (no bond,
 * no MTU, no notifications, no write) and treats it as an ordinary failure, reconnecting after
 * the usual delay. The fake ring always reports both, so these tests take one away on the way
 * from the fake to the link.
 */
class GattLayoutTest {

    @Test
    fun aDeviceWithoutTheNotifyCharacteristicIsClosedAndRetried() = runTest {
        assertDroppedAfterDiscovery(missing = GattPort.NOTIFY)
    }

    @Test
    fun aDeviceWithoutTheWriteCharacteristicIsClosedAndRetried() = runTest {
        assertDroppedAfterDiscovery(missing = GattPort.WRITE)
    }

    private fun TestScope.assertDroppedAfterDiscovery(missing: GattPort.Characteristic) {
        val fake = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val gatt = object : GattPort by fake {
            override fun connect(
                session: SessionToken,
                ring: RememberedRing,
                autoConnect: Boolean,
                events: GattPort.EventSink,
            ): Boolean = fake.connect(session, ring, autoConnect) { event ->
                events.deliver(
                    if (event is GattEvent.ServicesDiscovered) {
                        GattEvent.ServicesDiscovered(event.session, event.status, event.characteristics - missing)
                    } else {
                        event
                    },
                )
            }
        }
        val link = LinkCore(Fixtures.ring, gatt, backgroundScope)
        val teardowns = recordTeardowns(link)

        link.connect()
        runCurrent()

        assertEquals(listOf("connect autoConnect=false", "discoverServices", "close"), fake.log)
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        assertEquals(listOf(LinkTeardown(TeardownReason.LINK_DROPPED, 0)), teardowns)
    }
}
