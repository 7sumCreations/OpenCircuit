package io.github.opencircuit.app

import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The debug build's demo link: a ring the emulator can "connect" to. It keeps the real link's
 * contract where the app relies on it (a second collection fails, refusals as values) so the app
 * code above it runs unchanged.
 */
class DemoRingLinkTest {

    private val logLines = mutableListOf<String>()

    @Test
    fun connectingAuthenticatesAndSendsOneDescriptorThroughTheSession() = runTest {
        val link = DemoRingLink()
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        assertEquals(LinkState.Idle, link.state.value)
        assertEquals("Demo ring", link.ring.name)

        controller.connect()
        runCurrent()

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(72, controller.deviceStatus.state.value.batteryPercent)
        assertEquals(emptyList(), logLines, "the demo descriptor is a well-formed 0x10 frame")
    }

    @Test
    fun connectingAgainWhileConnectedSendsNoMoreFrames() = runTest {
        val link = DemoRingLink()
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { link.frames.collect { received += it } }

        link.connect()
        link.connect()
        runCurrent()

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(1, received.size)
        assertEquals(0x10, received[0][0].toInt() and 0xFF)
    }

    @Test
    fun sendIsRefusedBeforeConnectingAndForTheReservedAuthCommands() = runTest {
        val link = DemoRingLink()

        assertEquals(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED), link.send(hex("d00000")))
        link.connect()
        assertEquals(SendResult.Sent, link.send(hex("d00000")))
        assertEquals(SendResult.Refused(RefusalReason.AUTH_COMMAND_RESERVED), link.send(hex("010000")))
        assertEquals(SendResult.Refused(RefusalReason.AUTH_COMMAND_RESERVED), link.send(hex("0101aabbcc")))
    }

    @Test
    fun disconnectingTearsDownTheConnectionOnceAndGoesIdle() = runTest {
        val link = DemoRingLink()
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.connect()
        runCurrent()

        link.disconnect()
        link.disconnect()
        runCurrent()

        assertEquals(LinkState.Idle, link.state.value)
        assertEquals(1, controller.teardowns.value.count)
        assertEquals(LinkTeardown(TeardownReason.USER_DISCONNECTED, 0), controller.teardowns.value.last)
        assertEquals(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED), link.send(hex("d00000")))
    }

    @Test
    fun eachFlowTakesOneCollection() = runTest {
        val link = DemoRingLink()
        RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it }).start()
        runCurrent()

        assertFailsWith<IllegalStateException> { withTimeout(1_000) { link.frames.collect() } }
        assertFailsWith<IllegalStateException> { withTimeout(1_000) { link.teardowns.collect() } }
    }
}
