package io.github.opencircuit.app

import io.github.opencircuit.app.sync.CommitResult
import io.github.opencircuit.app.sync.HistoryPages
import io.github.opencircuit.app.sync.HistoryStore
import io.github.opencircuit.app.sync.SyncEvidence
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.CommitPlanner
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The history routes have ONE coroutine that stores and acknowledges every page. An unexpected
 * error while one page is handled (here its acknowledgement throws) must not end that coroutine:
 * the page is not acknowledged (the ring keeps it), and the next page is still stored and
 * acknowledged, so the session's ACK lane does not die until the process restarts.
 */
class HistoryPagesSurvivesFaultTest {

    private class Journal : HistoryStore {
        val appended = mutableListOf<ByteArray>()

        override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long {
            appended += page
            return appended.size.toLong()
        }

        override suspend fun commit(now: Instant, drained: CommitPlanner.Drained, keepGoing: () -> Boolean, evidence: SyncEvidence) = CommitResult()
    }

    @Test
    fun aPageWhoseHandlingThrowsDoesNotStopTheNextPage() = runTest {
        val store = Journal()
        val acknowledged = mutableListOf<ByteArray>()
        var calls = 0
        val pages = HistoryPages(
            store = store,
            acknowledge = { page ->
                if (calls++ == 0) throw IllegalStateException("defect")
                acknowledged += page
                SendResult.Sent
            },
            scope = backgroundScope,
            wallClock = { Instant.EPOCH },
            log = {},
        )
        val first = HistoryTestPages.sleepPage(0, 2)
        val second = HistoryTestPages.sleepPage(1, 2)

        pages.onFrame(first)
        pages.onFrame(second)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        assertEquals(2, store.appended.size, "both pages stored")
        assertEquals(listOf(second.toPlainHex()), acknowledged.map { it.toPlainHex() }, "the second page was acknowledged")
        assertEquals(1, pages.counts.value.ackFailures, "the first page's failed acknowledgement is counted")
        assertTrue(pages.awaitIdle(1_000), "the ACK lane is idle again")
    }
}
