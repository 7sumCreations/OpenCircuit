package io.github.opencircuit.app

import io.github.opencircuit.app.sync.CommitResult
import io.github.opencircuit.app.sync.HistoryPages
import io.github.opencircuit.app.sync.HistoryStore
import io.github.opencircuit.app.sync.SyncEvidence
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.CommitPlanner
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "The ACK lane is idle" is the app's to know: the link's `acknowledge` returns once the
 * acknowledgement is written, so the lane is idle when no history page is queued, being stored or
 * being acknowledged in [HistoryPages]. A disconnect waits for that, at most 2 s
 * ([HistoryPages.awaitIdle]); a page still in flight then is not acknowledged, and the ring offers
 * it again (a disconnect racing the last acknowledgement, GB b1c6c721).
 */
class AckLaneIdleTest {

    private val page = HistoryTestPages.sleepPage(0, 1)

    private class SlowStore(private val appendMillis: Long) : HistoryStore {
        override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long {
            delay(appendMillis)
            return 1
        }

        override suspend fun commit(now: Instant, drained: CommitPlanner.Drained, keepGoing: () -> Boolean, evidence: SyncEvidence) = CommitResult()
    }

    @Test
    fun theLaneIsIdleOnceThePageInFlightIsStoredAndAcknowledged() = runTest {
        val acks = mutableListOf<Long>()
        val pages = HistoryPages(SlowStore(1_500), { acks += testScheduler.currentTime; SendResult.Sent }, backgroundScope, { Instant.EPOCH }, {})

        assertTrue(pages.awaitIdle(2_000), "nothing in flight: idle at once")
        assertEquals(0L, testScheduler.currentTime)

        pages.onFrame(page)
        val idle = async { pages.awaitIdle(2_000) }
        runCurrent()
        advanceTo(1_499)
        assertFalse(idle.isCompleted, "the page is still being stored at 1 499 ms")
        advanceTo(1_500)
        assertTrue(idle.await())
        assertEquals(listOf(1_500L), acks)
    }

    @Test
    fun theWaitGivesUpAfterTwoSecondsWithThePageNotYetAcknowledged() = runTest {
        val acks = mutableListOf<Long>()
        val pages = HistoryPages(SlowStore(5_000), { acks += testScheduler.currentTime; SendResult.Sent }, backgroundScope, { Instant.EPOCH }, {})

        pages.onFrame(page)
        val idle = async { pages.awaitIdle(2_000) }
        runCurrent()
        advanceTo(1_999)
        assertFalse(idle.isCompleted)
        advanceTo(2_000)
        assertFalse(idle.await(), "not idle within 2 s")
        assertEquals(emptyList(), acks, "the page in flight is not acknowledged yet")
    }
}
