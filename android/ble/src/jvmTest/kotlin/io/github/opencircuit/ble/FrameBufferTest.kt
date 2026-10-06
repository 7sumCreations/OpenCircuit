package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Every frame the ring sends, except the `81 00` challenge the link answers itself, waits in one
 * ordered buffer per connection until the one collector of `frames` takes it. The link has
 * already acknowledged most of them to the ring, which will never send them again, so none may
 * be lost: frames wait while nobody collects, a collector that stops (or is cancelled) leaves the
 * rest for the next one, and a teardown counts what it had to drop (PORTING.md D-189).
 */
class FrameBufferTest {

    private val firstDataFrame = "15 00 08 0a b0 a7"

    /** A burst of [pages] pages, alternating a real `0x4c` page and a truncated `0x47` page, with a heartbeat after every 100. */
    private fun burst(pages: Int): List<ByteArray> = buildList {
        for (i in 0 until pages) {
            add(if (i % 2 == 0) Fixtures.sleepPage4c else Fixtures.ppgPage47Truncated)
            if (i % 100 == 99) add(Fixtures.heartbeat(1 + (i / 100) % 9))
        }
    }

    @Test
    fun framesThatArriveWithNoCollectorWaitAndALaterCollectorGetsEveryOneInOrder() = runTest {
        val (ring, link) = authenticatedLink()
        val frames = burst(10)

        ring.notifyAll(frames)
        runCurrent()
        val got = recordFrames(link)

        assertEquals(listOf(firstDataFrame) + frames.map { it.hexString() }, got)
    }

    @Test
    fun aCollectorThatStopsAfterOneFrameLeavesEveryLaterFrameForTheNextCollector() = runTest {
        val (ring, link) = authenticatedLink()
        ring.notify(Fixtures.sleepPage4c)
        runCurrent()

        val first = link.frames.first() // collects one frame, then stops collecting
        ring.notify(Fixtures.heartbeat(1))
        runCurrent()
        val rest = recordFrames(link)

        assertEquals(firstDataFrame, first.hexString())
        assertEquals(listOf(Fixtures.sleepPage4c.hexString(), Fixtures.heartbeat(1).hexString()), rest)
    }

    @Test
    fun aCollectorCancelledMidBurstLosesNoFrameAndTheNextCollectorGetsTheRestInOrder() = runTest {
        val (ring, link) = authenticatedLink()
        val firstHalf = burst(5)
        val secondHalf = burst(6).drop(1)
        val seenByFirst = mutableListOf<String>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            link.frames.collect { seenByFirst += it.hexString() }
        }

        ring.notifyAll(firstHalf)
        runCurrent()
        collector.cancel()
        ring.notifyAll(secondHalf)
        runCurrent()
        val seenBySecond = recordFrames(link)

        assertEquals(listOf(firstDataFrame) + firstHalf.map { it.hexString() }, seenByFirst)
        assertEquals(secondHalf.map { it.hexString() }, seenBySecond)
    }

    /**
     * The narrowest window: the collector is waiting, a frame arrives and wakes it, and the
     * collector is cancelled before it runs again. A channel hands the frame over at the wake-up
     * and loses it here (`FrameDeliveryHazardTest`); the link's buffer must keep it.
     */
    @Test
    fun aCollectorCancelledBetweenAFramesArrivalAndItsOwnWakeUpLeavesThatFrameForTheNextCollector() = runTest {
        val (ring, link) = authenticatedLink()
        val seenByFirst = mutableListOf<String>()
        val collector = backgroundScope.launch { link.frames.collect { seenByFirst += it.hexString() } }
        runCurrent() // took the first data frame; waiting for the next

        ring.notify(Fixtures.descriptor10)
        backgroundScope.launch { collector.cancel() } // runs after the link took the frame, before the collector wakes
        runCurrent()
        val seenBySecond = recordFrames(link)

        assertEquals(listOf(firstDataFrame), seenByFirst)
        assertEquals(listOf(Fixtures.descriptor10.hexString()), seenBySecond)
    }

    @Test
    fun aSecondCollectorOfFramesWhileTheFirstStillCollectsFailsLoudly() = runTest {
        val (_, link) = authenticatedLink()
        recordFrames(link)

        assertFailsWith<IllegalStateException> { link.frames.first() }
    }

    @Test
    fun aSecondCollectorOfTeardownsWhileTheFirstStillCollectsFailsLoudly() = runTest {
        val (_, link) = authenticatedLink()
        recordTeardowns(link)

        assertFailsWith<IllegalStateException> { link.teardowns.first() }
    }

    @Test
    fun aTeardownsCollectorThatStopsLeavesLaterTeardownsForTheNextCollector() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        ring.dropConnection(8)
        runCurrent()

        val first = link.teardowns.first()
        advance(1_000) // the reconnect
        link.disconnect()
        runCurrent()
        val rest = recordTeardowns(link)

        assertEquals(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 0), first)
        assertEquals(listOf(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 0)), rest)
    }

    @Test
    fun aTeardownMidBurstCountsEveryFrameStillWaitingAsUndelivered() = runTest {
        val (ring, link) = authenticatedLink()
        val teardowns = recordTeardowns(link)

        ring.notifyAll(burst(5))
        runCurrent()
        ring.dropConnection(8)
        runCurrent()

        // The first data frame and the 5 pages were never collected.
        assertEquals(listOf(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 6)), teardowns)
    }

    @Test
    fun aSlowCollectorCutOffByATeardownHasEveryFrameEitherDeliveredOrCounted() = runTest {
        val (ring, link) = authenticatedLink()
        val teardowns = recordTeardowns(link)
        val delivered = mutableListOf<String>()
        backgroundScope.launch {
            link.frames.collect {
                delivered += it.hexString()
                delay(100) // a slow consumer
            }
        }
        val frames = burst(10)

        ring.notifyAll(frames)
        runCurrent()
        advance(250) // the collector has taken 3 frames
        ring.dropConnection(8)
        runCurrent()
        advance(900) // the collector is idle for the rest of the reconnect delay (1 s)

        val all = listOf(firstDataFrame) + frames.map { it.hexString() }
        assertEquals(all.take(3), delivered.take(3))
        assertEquals(3, delivered.size, "nothing of the torn-down connection is delivered after its teardown")
        assertEquals(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = all.size - 3), teardowns.first())
    }

    @Test
    fun theNextConnectionStartsWithAnEmptyBuffer() = runTest {
        val (ring, link) = authenticatedLink()
        ring.notifyAll(burst(3))
        runCurrent()

        ring.dropConnection(8)
        advance(1_000) // reconnect and authenticate again
        val got = recordFrames(link)

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(listOf(firstDataFrame), got, "only the new connection's frame")
    }

    /** The perf flag on the frame buffer: a 2 000-page burst with nobody collecting. */
    @Test
    fun aBurstOf2000PagesWithNoCollectorIsBufferedWholeAndEveryPageIsAckedOnce() = runTest {
        val (ring, link) = authenticatedLink()
        val before = ring.log.size
        val frames = burst(2_000)

        ring.notifyAll(frames)
        runCurrent()
        val writes = ring.log.drop(before)
        val got = recordFrames(link)

        assertEquals(2_020, frames.size)
        assertEquals(1_000, writes.count { it == "write 8327ad98 cc 00 00" })
        assertEquals(1_000, writes.count { it == "write 8327ad98 c7 00 00" })
        assertEquals(20, writes.count { it == "write 8327ad98 91 00 00" })
        assertEquals(2_020, writes.size, "nothing but one ACK per frame")
        assertEquals(listOf(firstDataFrame) + frames.map { it.hexString() }, got)
        assertEquals(emptyList(), ring.violations)
    }

    /** The perf flag on the frame buffer: a 2 000-page burst taken by a collector slower than the ring. */
    @Test
    fun aBurstOf2000PagesTakenByASlowCollectorReachesItWholeAndInOrder() = runTest {
        val (ring, link) = authenticatedLink()
        val delivered = mutableListOf<String>()
        backgroundScope.launch {
            link.frames.collect {
                delivered += it.hexString()
                delay(5)
            }
        }
        val frames = burst(2_000)

        ring.notifyAll(frames)
        runCurrent()
        advance(20_000)

        assertEquals(listOf(firstDataFrame) + frames.map { it.hexString() }, delivered)
    }
}
