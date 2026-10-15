package io.github.opencircuit.app

import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.HistoryJournal
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Each commit chunk is ONE transaction across the archive merge, the ingest and the journal rows
 * it consumes (PORTING.md D-267): a failure at the end of a chunk's transaction — after all three
 * writes — leaves nothing of that chunk in the store and every one of its journal rows in place,
 * while the chunks before it stay committed; the next commit finishes the rest, once each.
 * 834 pages = 5,004 records: the first chunk consumes pages 1–833, the second page 834.
 */
class ChunkAtomicityTest {

    private val now = Instant.parse("2026-10-08T09:00:00Z")
    private val pages = (0 until 834).map { HistoryTestPages.sleepPage(it, 834) }

    private suspend fun heartRateCount(db: StoreDatabase) =
        LocalStore(db).samples(MetricKind.HEART_RATE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")).size

    private fun withPages(failIn: Int, block: suspend (StoreDatabase, StoreHistory) -> Unit) = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            var failing = true
            val store = StoreHistory({ db }, TEST_RING_ID, { ZoneOffset.UTC }, insideChunk = { index ->
                if (failing && index == failIn) {
                    failing = false
                    throw IOException("disk full")
                }
            })
            for (page in pages) store.append(page, now, drainId = 1)
            block(db, store)
        } finally {
            db.close()
        }
    }

    @Test
    fun aFailureInTheSecondChunkKeepsTheFirstAndLeavesTheSecondsRowsJournaled() = withPages(failIn = 1) { db, store ->
        assertFailsWith<IOException> { store.commit(now, CommitPlanner.Drained.Everything) }

        assertEquals(5_000, heartRateCount(db), "the first chunk is committed, nothing of the second")
        assertEquals(listOf(pages.last().toPlainHex()), HistoryJournal(db).read(TEST_RING_ID).entries.map { it.page.toPlainHex() })

        val retry = store.commit(now, CommitPlanner.Drained.Everything)
        assertEquals(1, retry.chunks)
        assertEquals(5_004, heartRateCount(db))
        assertEquals(emptyList(), HistoryJournal(db).read(TEST_RING_ID).entries)
    }

    @Test
    fun aFailureInTheFirstChunkStoresNothingAndKeepsEveryRow() = withPages(failIn = 0) { db, store ->
        assertFailsWith<IOException> { store.commit(now, CommitPlanner.Drained.Everything) }

        assertEquals(0, heartRateCount(db))
        assertEquals(0, BlobStore(db).loadEpochArchive(TEST_RING_ID).records.size, "no archive merge either")
        assertEquals(834, HistoryJournal(db).read(TEST_RING_ID).entries.size)
    }
}
