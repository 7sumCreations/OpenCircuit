package io.github.opencircuit.ble

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An unbounded, ordered buffer read through [flow] by ONE collector at a time (PORTING.md D-189).
 *
 * Items wait in arrival order while nobody collects. A collector takes them one at a time; an
 * item leaves the buffer only in the same step that hands it to the collector, so a collector
 * that stops (`first()`, `take(n)`) or is cancelled loses nothing: whatever it had not taken
 * waits for the next collector. A second collection while one is running throws
 * [IllegalStateException] at once.
 *
 * Measured against the stock alternatives (`FrameDeliveryHazardTest`): `Channel.receiveAsFlow()`
 * lets a second collector in and splits the items between the two; `Channel.consumeAsFlow()`
 * cancels the channel when its collector stops, after which every new item is refused without a
 * word; and a channel receiver cancelled just after an item was handed to it loses that item.
 *
 * [add] and [clear] may be called from any thread.
 */
internal class SingleCollectorBuffer<T : Any>(
    /** Names the stream in the second-collector error, e.g. "frames". */
    private val name: String,
) {
    private val lock = Any()
    private val items = ArrayDeque<T>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val collecting = AtomicBoolean(false)

    /** Appends [item] for the collector. */
    fun add(item: T) {
        synchronized(lock) { items.addLast(item) }
        wake.trySend(Unit)
    }

    /** Drops every waiting item; returns how many there were. */
    fun clear(): Int = synchronized(lock) {
        val count = items.size
        items.clear()
        count
    }

    /** The items, in order, for exactly one collector at a time. */
    val flow: Flow<T> = object : Flow<T> {
        override suspend fun collect(collector: FlowCollector<T>) {
            check(collecting.compareAndSet(false, true)) {
                "$name already has a collector: exactly one may collect at a time"
            }
            try {
                while (true) {
                    // Taken and handed over with no suspension in between: a cancellation can
                    // only strike while waiting below, when no item is in hand.
                    val next = synchronized(lock) { items.removeFirstOrNull() }
                    if (next == null) wake.receive() else collector.emit(next)
                }
            } finally {
                collecting.set(false)
            }
        }
    }
}
