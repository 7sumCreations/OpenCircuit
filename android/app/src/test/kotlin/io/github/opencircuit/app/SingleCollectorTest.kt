package io.github.opencircuit.app

import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The session controller is the one collector of the link's `frames` and `teardowns`: every
 * feature reaches frames through its dispatcher, never the flow. The fake link fails a second
 * collection while one runs, and a failure in a background coroutine fails the test, so a
 * controller that collected twice at once turns these tests red.
 */
class SingleCollectorTest {

    private val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "Test ring")
    private val logLines = mutableListOf<String>()

    @Test
    fun theControllerHoldsTheOnlyCollectionOfBothFlows() = runTest {
        val link = FakeRingLink(ring)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })

        controller.start()
        runCurrent()

        // Bounded on virtual time: if the controller did not hold the collection, this one would
        // wait forever instead of failing.
        assertFailsWith<IllegalStateException> { withTimeout(1_000) { link.frames.collect() } }
        assertFailsWith<IllegalStateException> { withTimeout(1_000) { link.teardowns.collect() } }
    }

    @Test
    fun startingAndConnectingAgainDoesNotCollectAgain() = runTest {
        val link = FakeRingLink(ring)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })

        controller.start()
        controller.connect()
        controller.connect()
        runCurrent()
        link.emitFrame(TestFrames.unknown)
        runCurrent()

        assertEquals(2, link.connectCalls)
        assertEquals(mapOf(0xee to 1), controller.dispatcher.counts.value.unhandled, "each frame routed once")
    }

    @Test
    fun framesQueuedBeforeTheStartAreRoutedInOrderAfterIt() = runTest {
        val link = FakeRingLink(ring)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        link.emitFrame(TestFrames.wornDescriptor)
        link.emitFrame(TestFrames.chargingDescriptor)

        controller.start()
        runCurrent()

        assertEquals(71, controller.deviceStatus.state.value.batteryPercent, "the later descriptor wins")
    }

    @Test
    fun eachTeardownIsCollectedAndKept() = runTest {
        val link = FakeRingLink(ring)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.start()
        runCurrent()
        assertNull(controller.teardowns.value.last)

        link.emitTeardown(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 0))
        link.emitTeardown(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 2))
        runCurrent()

        assertEquals(2, controller.teardowns.value.count)
        assertEquals(LinkTeardown(TeardownReason.USER_DISCONNECTED, 2), controller.teardowns.value.last)
    }

    @Test
    fun aThrowingHandlerDoesNotEndTheCollection() = runTest {
        val link = FakeRingLink(ring)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.dispatcher.register(0xee) { throw IllegalStateException("a handler bug") }
        controller.start()
        runCurrent()

        link.emitFrame(TestFrames.unknown)
        link.emitFrame(TestFrames.wornDescriptor)
        runCurrent()

        assertEquals(66, controller.deviceStatus.state.value.batteryPercent, "the frame after the failure arrived")
        assertEquals(mapOf(0xee to 1), controller.dispatcher.counts.value.handlerFailures)
    }
}
