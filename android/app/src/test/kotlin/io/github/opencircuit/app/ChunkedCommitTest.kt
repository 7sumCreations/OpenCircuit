package io.github.opencircuit.app

import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.HistoryJournal
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The commit on the real store goes oldest first in transactions of at most 5,000 records
 * (PORTING.md D-267): 4,998 records are one transaction, 5,004 are two — the oldest 5,000, then
 * the last 4. Pages arrive newest first here, so "oldest first" is the commit's own order, not
 * the journal's. Asserted on the store's rows as each transaction ends.
 */
class ChunkedCommitTest {

    private val now = Instant.parse("2026-10-08T09:00:00Z")

    private suspend fun heartRates(db: StoreDatabase) =
        LocalStore(db).samples(MetricKind.HEART_RATE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")).map { it.start }

    private fun commitPages(pageCount: Int, check: suspend (StoreDatabase, List<Pair<Int, List<Instant>>>) -> Unit) = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val seen = mutableListOf<Pair<Int, List<Instant>>>()
            val store = StoreHistory({ db }, TEST_RING_ID, { ZoneOffset.UTC }, insideChunk = { index -> seen += index to heartRates(db) })
            // Newest page first: the ring's order across two channels can be anything.
            for (i in (0 until pageCount).reversed()) store.append(HistoryTestPages.sleepPage(i, pageCount), now, drainId = 1)
            store.commit(now, CommitPlanner.Drained.Everything)
            check(db, seen)
        } finally {
            db.close()
        }
    }

    private fun date(recordIndex: Int): Instant {
        val counter = HistoryTestPages.counters(recordIndex / 6)[recordIndex % 6]
        return Instant.ofEpochSecond(counter + Command.SYNC_EPOCH)
    }

    @Test
    fun fourThousandNineHundredNinetyEightRecordsAreOneTransaction() = commitPages(833) { db, seen ->
        assertEquals(listOf(0), seen.map { it.first })
        assertEquals(4_998, heartRates(db).size)
        assertEquals(emptyList(), HistoryJournal(db).read(TEST_RING_ID).entries)
    }

    @Test
    fun fiveThousandAndFourRecordsAreTheOldestFiveThousandThenTheLastFour() = commitPages(834) { db, seen ->
        assertEquals(listOf(0, 1), seen.map { it.first })
        assertEquals(5_000, seen[0].second.size, "the first transaction stored 5,000 records' samples")
        assertEquals(date(0), seen[0].second.first())
        assertEquals(date(4_999), seen[0].second.last(), "the oldest 5,000")
        assertEquals(5_004, seen[1].second.size)
        assertEquals(date(5_003), seen[1].second.last())
        assertEquals(emptyList(), HistoryJournal(db).read(TEST_RING_ID).entries)
    }
}
