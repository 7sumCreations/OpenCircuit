package io.github.opencircuit.app.sync

import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.BulkSleep
import io.github.opencircuit.ringkit.EpochRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** What a running drain hears from the history routes, in the order the ring sent it. */
sealed interface HistorySignal {
    /**
     * A page is stored and acknowledged; [countdown] is the records the ring says are still queued
     * after it (16 bits, `0x47` / `0x4c` only), and [counters] the counters of the `0x4c` records it
     * held (empty for any other page), so a drain counts unique records, not pages.
     */
    data class PageStored(val opcode: Int, val countdown: Int?, val counters: List<Long> = emptyList()) : HistorySignal

    /** The ring's `0x82` answer to the sync open (its bytes are kept for the trace). */
    class SyncAck(frame: ByteArray) : HistorySignal {
        val frame: ByteArray = frame.copyOf()
    }

    /** A `0x50` frame: the end of the history, once this channel's `0x82` has come. */
    class EndOfHistory(frame: ByteArray) : HistorySignal {
        val frame: ByteArray = frame.copyOf()
    }

    /** A page could not be stored, so it was NOT acknowledged: the ring keeps it. */
    data object SaveFailed : HistorySignal

    /** A stored page's acknowledgement was refused or failed: the ring keeps it and offers it again. */
    data class AckFailed(val result: SendResult) : HistorySignal

    /** The link left `Authenticated` while the drain ran (raised by the drain itself, never by the routes). */
    data object LinkDown : HistorySignal
}

/** The history routes' counts for this session. Counts only, never a page. */
data class HistoryPageCounts(
    /** Pages stored and acknowledged. */
    val acknowledged: Int = 0,
    /** Of those, pages that arrived with no drain running. */
    val outsideDrain: Int = 0,
    /** Pages that could not be stored, and so were never acknowledged. */
    val saveFailures: Int = 0,
    /** Pages stored whose acknowledgement was refused or failed. */
    val ackFailures: Int = 0,
)

/**
 * The frame dispatcher's handler for the history frames: `0x47`, `0x4c` and `0x4d` pages, the
 * `0x82` sync answer and the `0x50` end-of-history report (registered at the session's
 * construction, before any frame is dispatched).
 *
 * Every page is first stored ([HistoryStore.append]) and only then acknowledged to the ring
 * ([acknowledge]): the ring drops a page once it has the acknowledgement, so a page that could not
 * be stored is never acknowledged — counted, reported to a running drain as
 * [HistorySignal.SaveFailed], and the ring keeps it (PORTING.md D-261; never a silent fail-open).
 * Pages that arrive with no drain running are stored and
 * acknowledged the same way, with no drain id; the next commit takes them (0.1.1 acknowledged and
 * dropped them).
 *
 * The dispatcher's handler only queues the frame; one coroutine in [scope] handles the queue in
 * arrival order, so an `0x50` that follows a page is seen after that page is stored and
 * acknowledged.
 */
class HistoryPages(
    private val store: HistoryStore,
    private val acknowledge: suspend (ByteArray) -> SendResult,
    scope: CoroutineScope,
    private val wallClock: () -> Instant,
    private val log: (String) -> Unit,
) {
    private class Arrival(val frame: ByteArray, val receivedAt: Instant, val drainId: Long?)

    private class Attached(val drainId: Long, val sink: (HistorySignal) -> Unit)

    private val queue = Channel<Arrival>(Channel.UNLIMITED)
    private val attached = AtomicReference<Attached?>(null)
    private val countsFlow = MutableStateFlow(HistoryPageCounts())

    /** This session's counts. */
    val counts: StateFlow<HistoryPageCounts> = countsFlow.asStateFlow()

    init {
        scope.launch { for (arrival in queue) handle(arrival) }
    }

    /** One history frame from the dispatcher (any thread). Queued; never blocks the dispatcher. */
    fun onFrame(frame: ByteArray) {
        queue.trySend(Arrival(frame.copyOf(), wallClock(), attached.get()?.drainId))
    }

    /** Drain [drainId] starts: pages arriving from now on carry its id, and their signals go to [sink]. */
    fun attach(drainId: Long, sink: (HistorySignal) -> Unit) {
        attached.set(Attached(drainId, sink))
    }

    /** Drain [drainId] is over: no more signals to it (a newer drain's attachment is left alone). */
    fun detach(drainId: Long) {
        attached.getAndUpdate { if (it?.drainId == drainId) null else it }
    }

    private suspend fun handle(arrival: Arrival) {
        val frame = arrival.frame
        when (frame.firstOrNull()?.toInt()?.and(0xFF)) {
            SYNC_ACK -> signal(arrival, HistorySignal.SyncAck(frame))
            END_OF_HISTORY -> signal(arrival, HistorySignal.EndOfHistory(frame))
            else -> storeThenAcknowledge(arrival)
        }
    }

    private suspend fun storeThenAcknowledge(arrival: Arrival) {
        val page = arrival.frame
        val opcode = page[0].toInt() and 0xFF
        try {
            store.append(page, arrival.receivedAt, arrival.drainId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not stored, so not acknowledged: the ring keeps the page and offers it again.
            val failures = countsFlow.updateAndGet { it.copy(saveFailures = it.saveFailures + 1) }.saveFailures
            log("A history page could not be saved and was not acknowledged: ${e::class.java.simpleName} (failures $failures)")
            signal(arrival, HistorySignal.SaveFailed)
            return
        }
        when (val result = acknowledge(page)) {
            SendResult.Sent -> {
                countsFlow.update {
                    it.copy(acknowledged = it.acknowledged + 1, outsideDrain = it.outsideDrain + if (arrival.drainId == null) 1 else 0)
                }
                val counters = if (opcode == PAGE_4C) BulkSleep.recordsFromPage(page).map { it.counter } else emptyList()
                signal(arrival, HistorySignal.PageStored(opcode, EpochRecord.remainingRecordCountdown(page), counters))
            }
            else -> {
                countsFlow.update { it.copy(ackFailures = it.ackFailures + 1) }
                log("A stored history page's acknowledgement did not go out: $result")
                signal(arrival, HistorySignal.AckFailed(result))
            }
        }
    }

    /** Tells the drain the frame arrived in, if that drain is still attached. */
    private fun signal(arrival: Arrival, signal: HistorySignal) {
        val drain = attached.get() ?: return
        if (arrival.drainId == drain.drainId) drain.sink(signal)
    }

    companion object {
        /** The frames this handler is registered for. */
        val OPCODES: List<Int> = listOf(0x47, 0x4C, 0x4D, 0x82, 0x50)

        private const val PAGE_4C = 0x4C
        private const val SYNC_ACK = 0x82
        private const val END_OF_HISTORY = 0x50
    }
}
