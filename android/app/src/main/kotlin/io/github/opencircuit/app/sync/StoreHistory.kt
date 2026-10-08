package io.github.opencircuit.app.sync

import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.BulkSleep
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.HistoryJournal
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * [HistoryStore] over the on-device database: pages go into the history journal, and a commit
 * moves them into the epoch archive and the sample tables.
 *
 * A commit reads the journal and plans it ([CommitPlanner], PORTING.md D-267): only records the
 * sync is drained through are released; they go oldest first by counter, across both channels, in
 * chunks of at most [chunkRecords]. Each chunk is ONE write transaction: it merges the chunk's
 * records into the epoch archive bounded by `now` plus one day (PORTING.md D-44; the same day the
 * store's ingest allows into the future), stores their samples in one ingest, and deletes exactly
 * the journal rows the chunk consumes. If a chunk throws, nothing of it is stored and its rows stay
 * in the journal; the chunks before it are kept. Pages holding a record held back stay for a later
 * commit, as does a page that arrives while the commit runs.
 *
 * [database] opens the store on first use (the app opens it once for the process); [ringId] names
 * the ring's journal and archive; [zone] is where a local day starts for the step totals.
 * [insideChunk] runs at the end of each chunk's transaction, with the chunk's index: a seam for
 * the tests that fail a chunk midway (it does nothing in the app).
 */
class StoreHistory(
    private val database: suspend () -> StoreDatabase,
    private val ringId: String,
    private val zone: () -> ZoneId,
    private val chunkRecords: Int = CommitPlanner.CHUNK_RECORDS,
    private val insideChunk: suspend (index: Int) -> Unit = {},
) : HistoryStore {

    override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long =
        HistoryJournal(database()).append(ringId, page, receivedAt, drainId)

    override suspend fun commit(now: Instant, drained: CommitPlanner.Drained, keepGoing: () -> Boolean): CommitResult {
        val db = database()
        val journal = HistoryJournal(db)
        val blobs = BlobStore(db)
        val samples = LocalStore(db)
        val read = journal.read(ringId)
        val opcodes = read.entries.associate { it.seq to (it.page.firstOrNull()?.toInt()?.and(0xFF)) }
        val pages = read.entries.map { CommitPlanner.Page(it.seq, if (opcodes[it.seq] == PAGE_4C) BulkSleep.recordsFromPage(it.page) else emptyList()) }
        val plan = CommitPlanner.plan(pages, drained, read.unreadableSeqs, chunkRecords)

        var result = CommitResult(heldBack = plan.heldBackRecords, pagesKept = plan.kept.size)
        for ((index, chunk) in plan.chunks.withIndex()) {
            if (index > 0 && !keepGoing()) {
                // Stopped between transactions: what is left stays in the journal for the next commit.
                return result.copy(chunksLeft = plan.chunks.size - index)
            }
            val stored = db.withWriteTransaction {
                val merge = blobs.mergeEpochArchive(ringId, chunk.records, notAfter = now.plus(NOT_AFTER_ALLOWANCE), now = now)
                // The HRV gate is calibrated on the whole archive, never on one chunk (BulkSleep.samples).
                val ingested = samples.ingest(BulkSleep.samples(chunk.records, calibratedBy = merge.records), now, zone())
                journal.delete(ringId, chunk.consumed)
                insideChunk(index)
                Pair(merge.droppedAfterBound, ingested.size)
            }
            val consumedPages = chunk.consumed.filter { it in opcodes }
            result = result.copy(
                pages = result.pages + consumedPages.size,
                records = result.records + chunk.records.size,
                samplesStored = result.samplesStored + stored.second,
                droppedAfterBound = result.droppedAfterBound + stored.first,
                unreadablePages = result.unreadablePages + (chunk.consumed.size - consumedPages.size),
                pagesNotKept = result.pagesNotKept + consumedPages.count { opcodes[it] != PAGE_4C },
                chunks = result.chunks + 1,
            )
        }
        return result
    }

    private companion object {
        const val PAGE_4C = 0x4C

        /** How far past `now` a record may be dated and still be kept (the store's ingest allows the same). */
        val NOT_AFTER_ALLOWANCE: Duration = Duration.ofDays(1)
    }
}
