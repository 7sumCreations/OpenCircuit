package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.LocalStore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A connection torn down while a page is being stored: the page reaches the journal, but its
 * acknowledgement never goes out, so the ring keeps it and offers it again on the next sync. The
 * link's teardown counts it in `pagesUnacknowledged`; the sync's report carries that count as a
 * trace — an expected re-offer, not a fault. The re-offered page is journaled a second time and
 * the commit keeps each record and each sample once.
 */
class TeardownReofferDedupTest {

    @Test
    fun aPageCutByATeardownIsTracedOfferedAgainAndStoredOnce() = runTest {
        val pages = HistoryTestPages.backlog(3)
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to pages, Command.SYNC_CHANNEL_ALL_DAY to emptyList()))
        }
        try {
            var dropped = false
            w.store.duringAppend = { page ->
                if (!dropped && page.contentEquals(pages[1])) {
                    dropped = true
                    w.ring.dropLink() // the link goes while the second page is being stored
                }
            }
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(20_000)

            val first = w.session.sync.state.value.last!!
            assertEquals(SyncOutcome.PARTIAL, first.outcome)
            assertEquals(1, first.pagesUnacknowledged, "the page cut mid-store is traced")
            assertEquals(emptySet(), first.faults, "a re-offer is not a fault")
            assertNull(w.viewModel.uiState.value.ringData.problem)
            assertEquals(listOf(pages[0], pages[1]).map { it.toPlainHex() }, w.journal.read(TEST_RING_ID).entries.map { it.page.toPlainHex() })
            assertEquals(listOf(pages[0].toPlainHex()), w.ring.acknowledgedPages.map { it.toPlainHex() })

            w.viewModel.onAction(RingAction.SyncNow) // the ring offers the second page again
            runCurrent()
            advanceTo(120_000)

            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() }, "each page acknowledged once")
            val counters = (0 until 3).flatMap { HistoryTestPages.counters(it) }
            assertEquals(counters, w.blobs.loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            val heartRates = LocalStore(w.db).samples(MetricKind.HEART_RATE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
            assertEquals(counters.map { Instant.ofEpochSecond(it + Command.SYNC_EPOCH) }, heartRates.map { it.start }, "each sample once")
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)
        } finally {
            w.db.close()
        }
    }
}
