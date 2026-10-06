package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * An exception nobody expected, thrown while the link handles one event, ends neither the link
 * nor the app: it counts as a failure of the connection open at the time (the write in flight
 * fails with `GATT_ERROR`, the waiting ones with `LINK_LOST`, the connection is closed, its
 * teardown published and the next attempt scheduled), and the link goes on handling the events
 * that follow. Without this, the event loop would end: under the Android factory's scope the
 * exception would crash the app, and on any scope the link would stop answering for good.
 *
 * The fault is injected through the diagnostics clock, which every handler that notes a
 * diagnostic reads; a bond-state broadcast notes one first thing.
 */
class LoopFaultTest {

    /** The test scheduler's clock, except that its next [faults] readings throw. */
    private class FaultyClock(private val inner: TimeSource) : TimeSource {
        var faults = 0

        override fun markNow(): TimeMark {
            val mark = inner.markNow()
            return object : TimeMark {
                override fun elapsedNow(): Duration {
                    if (faults > 0) {
                        faults--
                        throw IllegalStateException("the clock failed")
                    }
                    return mark.elapsedNow()
                }
            }
        }
    }

    private class Rig(val ring: FakeGatt, val link: LinkCore, val clock: FaultyClock)

    private fun TestScope.rig(): Rig {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val clock = FaultyClock(testTimeSource)
        val link = LinkCore(Fixtures.ring, ring, backgroundScope, clock)
        ring.onBondStateChanged(link::onBondState)
        return Rig(ring, link, clock)
    }

    @Test
    fun anUnexpectedErrorFailsTheConnectionsWritesAndTheLinkReconnects() = runTest {
        val (ring, link, clock) = rig().let { Triple(it.ring, it.link, it.clock) }
        val teardowns = recordTeardowns(link)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
        ring.hold(Operation.WRITE)
        val inFlight = backgroundScope.async { link.send(hex("950000")) }
        runCurrent()
        val queued = backgroundScope.async { link.send(hex("960000")) }
        runCurrent()

        clock.faults = 1
        ring.changeBondState(GattPort.BondState.BONDED)
        runCurrent()

        assertEquals(SendResult.Failed(SendFailure.GATT_ERROR), inFlight.getCompleted())
        assertEquals(SendResult.Failed(SendFailure.LINK_LOST), queued.getCompleted())
        assertEquals("close", ring.log.last())
        // The frame that authenticated the connection was never collected.
        assertEquals(listOf(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 1)), teardowns)
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        assertTrue(
            link.diagnostics.value.any { it.event == "unexpected error" && it.detail == "IllegalStateException" },
            "the fault is noted by its class only: ${link.diagnostics.value}",
        )

        ring.release(Operation.WRITE)
        advance(1_000)
        assertEquals(LinkState.Authenticated, link.state.value, "the same link connects again")
        assertEquals(SendResult.Sent, link.send(hex("950000")))
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun anUnexpectedErrorWithNoConnectionOpenLeavesTheLinkWorking() = runTest {
        val rig = rig()

        rig.clock.faults = 1
        rig.ring.changeBondState(GattPort.BondState.BONDED)
        runCurrent()
        assertEquals(LinkState.Idle, rig.link.state.value)

        rig.link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, rig.link.state.value)
    }

    @Test
    fun anErrorWhileRecoveringStillClosesTheConnectionAndAnswersEveryCaller() = runTest {
        val (ring, link, clock) = rig().let { Triple(it.ring, it.link, it.clock) }
        val teardowns = recordTeardowns(link)
        link.connect()
        runCurrent()
        ring.hold(Operation.WRITE)
        val inFlight = backgroundScope.async { link.send(hex("950000")) }
        runCurrent()
        val queued = backgroundScope.async { link.send(hex("960000")) }
        runCurrent()

        clock.faults = 2 // the handler throws, and so does the first step of the recovery
        ring.changeBondState(GattPort.BondState.BONDED)
        runCurrent()

        assertEquals(SendResult.Failed(SendFailure.GATT_ERROR), inFlight.getCompleted())
        assertEquals(SendResult.Failed(SendFailure.LINK_LOST), queued.getCompleted())
        assertEquals(1, ring.log.count { it == "close" })
        assertEquals(listOf(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 1)), teardowns)
        assertEquals(LinkState.Idle, link.state.value, "stopped until connect() is asked again")
        advance(10 * 60_000)
        assertEquals(1, ring.connects.size, "no reconnect of its own")

        ring.release(Operation.WRITE)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(SendResult.Sent, link.send(hex("950000")))
    }
}
