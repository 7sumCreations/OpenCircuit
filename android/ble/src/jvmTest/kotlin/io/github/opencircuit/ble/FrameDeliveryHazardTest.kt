package io.github.opencircuit.ble

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Why `frames` and `teardowns` sit on [SingleCollectorBuffer] and not on a stock channel flow
 * (PORTING.md D-189). The frames are already acknowledged to the ring, so the buffer must never
 * lose one without a trace. Each stock option is measured here against kotlinx-coroutines 1.11.0;
 * each test pins what the library does, so a library change that alters it fails here first.
 */
class FrameDeliveryHazardTest {

    /** `receiveAsFlow()` does not refuse a second collector: it splits the items between the two. */
    @Test
    fun receiveAsFlowLetsASecondCollectorInAndSplitsTheItems() = runTest {
        val channel = Channel<Int>(Channel.UNLIMITED)
        val flow = channel.receiveAsFlow()
        val a = mutableListOf<Int>()
        val b = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { a += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { b += it } }

        (1..6).forEach { channel.trySend(it) }
        runCurrent()

        assertEquals((1..6).toList(), (a + b).sorted())
        assertTrue(a.isNotEmpty() && b.isNotEmpty(), "both collectors got items: a=$a b=$b")
    }

    /**
     * `consumeAsFlow()` refuses a second collector, but once its one collector stops it cancels the
     * channel: every later item is refused (a `trySend` failure nobody reads) and a new collector throws.
     */
    @Test
    fun consumeAsFlowRefusesEveryItemSentAfterItsCollectorStopped() = runTest {
        val channel = Channel<Int>(Channel.UNLIMITED)
        val flow = channel.consumeAsFlow()
        channel.trySend(1)
        channel.trySend(2)

        assertEquals(1, flow.first())
        val later = channel.trySend(3)

        assertTrue(later.isFailure, "the item sent after the collector stopped is refused")
        assertFailsWith<IllegalStateException> { flow.first() }
    }

    /**
     * A channel receiver that is waiting when an item arrives is handed the item at once; if it is
     * cancelled before it runs, the item is gone from the channel and never reaches anyone.
     */
    @Test
    fun aWaitingChannelReceiverCancelledAfterAnItemWasHandedToItLosesTheItem() = runTest {
        val lost = mutableListOf<Int>()
        val channel = Channel<Int>(Channel.UNLIMITED) { lost += it }
        val receiver = backgroundScope.launch { channel.receive() }
        runCurrent() // waiting in receive()

        channel.trySend(7)
        receiver.cancel() // before the receiver ran again
        runCurrent()

        assertEquals(listOf(7), lost)
        assertTrue(channel.tryReceive().isFailure, "the item is no longer in the channel")
    }

    /** The same moment on the chosen buffer: the item stays for the next collector. */
    @Test
    fun aWaitingBufferCollectorCancelledAfterAnItemArrivedLeavesItForTheNextCollector() = runTest {
        val buffer = SingleCollectorBuffer<Int>("items")
        val first = mutableListOf<Int>()
        val collector = backgroundScope.launch { buffer.flow.collect { first += it } }
        runCurrent() // waiting for an item

        buffer.add(7)
        collector.cancel() // before the collector ran again
        runCurrent()

        assertEquals(emptyList(), first)
        assertEquals(7, buffer.flow.first())
    }

    /** The chosen buffer refuses a second collector while the first runs, and admits one after it stopped. */
    @Test
    fun theBufferAdmitsOneCollectorAtATime() = runTest {
        val buffer = SingleCollectorBuffer<Int>("items")
        buffer.add(1)
        buffer.add(2)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { buffer.flow.collect {} }

        val second = assertFailsWith<IllegalStateException> { buffer.flow.first() }
        collector.cancel()
        buffer.add(3)

        assertEquals("items already has a collector: exactly one may collect at a time", second.message)
        assertEquals(3, buffer.flow.first())
    }

    @Test
    fun clearingTheBufferCountsWhatWasWaiting() = runTest {
        val buffer = SingleCollectorBuffer<Int>("items")
        (1..4).forEach(buffer::add)

        assertEquals(1, buffer.flow.first())
        assertEquals(3, buffer.clear())
        assertEquals(0, buffer.clear())
    }
}
