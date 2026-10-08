package io.github.opencircuit.app

import io.github.opencircuit.app.sync.CommitResult
import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.HistoryCommitGate
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.HistoryJournal
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The commit on the real store: what it cannot keep is counted, never silent. Pages that are not
 * `0x4c` (a `0x47` sensor page, a `0x4d` sport page) are consumed and counted as not kept; a
 * record dated after the merge's bound (now + 1 day) never reaches the archive and is counted;
 * the journal is emptied either way, and an empty journal commits nothing.
 */
class StoreHistoryCommitTest {

    private val now = Instant.parse("2026-10-08T09:00:00Z")

    @Test
    fun pagesItCannotKeepAndRecordsDatedPastTheBoundAreCounted() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val store = StoreHistory({ db }, TEST_RING_ID, { ZoneOffset.UTC })
            // A 0x4c page whose last record is dated 2 days after `now` (counter written on the raw path).
            val future = HistoryTestPages.sleepPage(0, 1).copyOf()
            val farCounter = now.plusSeconds(2 * 86_400).epochSecond - Command.SYNC_EPOCH
            val lastRecord = 3 + 5 * 23
            future[lastRecord] = (farCounter ushr 24).toByte()
            future[lastRecord + 1] = (farCounter ushr 16).toByte()
            future[lastRecord + 2] = (farCounter ushr 8).toByte()
            future[lastRecord + 3] = farCounter.toByte()
            future[future.size - 1] = future.dropLast(1).fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }.toByte()
            store.append(future, now, drainId = 1)
            store.append(hex("4700000c65863a029f00303c"), now, drainId = 1) // a truncated 0x47 page
            store.append(hex("4d004d"), now, drainId = null) // a 0x4d sport page

            val result = store.commit(now, CommitPlanner.Drained.Everything)

            // No channel's evidence was given, so the staging gate skips (HistoryCommitGate, D-43).
            val skip = HistoryCommitGate.Decision.SKIP
            assertEquals(CommitResult(pages = 3, records = 6, samplesStored = result.samplesStored, droppedAfterBound = 1, unreadablePages = 0, pagesNotKept = 2, chunks = 1, staging = skip), result)
            assertEquals(HistoryTestPages.counters(0).dropLast(1), BlobStore(db).loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            assertEquals(emptyList(), HistoryJournal(db).read(TEST_RING_ID).entries)
            assertEquals(CommitResult(staging = skip), store.commit(now, CommitPlanner.Drained.Everything), "an empty journal commits nothing")
        } finally {
            db.close()
        }
    }
    @Test
    fun aRecordPageWhoseChecksumFailsIsCountedAsNotKept() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val store = StoreHistory({ db }, TEST_RING_ID, { ZoneOffset.UTC })
            // A 0x4c page already acknowledged (the ring dropped it) whose XOR trailer does not match:
            // none of its records can be read, so its loss must be counted, never silent.
            val corrupt = HistoryTestPages.sleepPage(0, 1).copyOf()
            corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toInt() xor 0x01).toByte()
            store.append(corrupt, now, drainId = 1)

            val result = store.commit(now, CommitPlanner.Drained.Everything)

            assertEquals(1, result.pages)
            assertEquals(0, result.records)
            assertEquals(1, result.pagesNotKept, "a 0x4c page with no readable record is a page not kept")
            assertEquals(emptyList(), HistoryJournal(db).read(TEST_RING_ID).entries)
        } finally {
            db.close()
        }
    }
}
