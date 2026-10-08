package io.github.opencircuit.app.sync

import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.BulkSleep
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
 * The commit is one write transaction: it reads the journal, merges every `0x4c` record into the
 * epoch archive bounded by `now` plus one day (PORTING.md D-44; the same day the store's ingest
 * allows into the future), stores the samples of those records in ONE ingest, and deletes exactly
 * the journal rows it read. If any step throws, nothing of it is stored and the pages stay in the
 * journal for the next commit. A page that arrives while the commit runs gets a later sequence
 * number and stays for the next one.
 *
 * [database] opens the store on first use (the app opens it once for the process); [ringId] names
 * the ring's journal and archive; [zone] is where a local day starts for the step totals.
 */
class StoreHistory(
    private val database: suspend () -> StoreDatabase,
    private val ringId: String,
    private val zone: () -> ZoneId,
) : HistoryStore {

    override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long =
        HistoryJournal(database()).append(ringId, page, receivedAt, drainId)

    override suspend fun commit(now: Instant): CommitResult {
        val db = database()
        val journal = HistoryJournal(db)
        val blobs = BlobStore(db)
        val samples = LocalStore(db)
        return db.withWriteTransaction {
            val read = journal.read(ringId)
            val lastSeq = read.lastSeq ?: return@withWriteTransaction CommitResult()
            val sleepPages = read.entries.filter { it.page.firstOrNull()?.toInt()?.and(0xFF) == PAGE_4C }
            val records = sleepPages.flatMap { BulkSleep.recordsFromPage(it.page) }
            val merge = blobs.mergeEpochArchive(ringId, records, notAfter = now.plus(NOT_AFTER_ALLOWANCE), now = now)
            // The HRV gate is calibrated on the whole archive, never on one page (BulkSleep.samples).
            val stored = samples.ingest(BulkSleep.samples(records, calibratedBy = merge.records), now, zone())
            journal.deleteThrough(ringId, lastSeq)
            CommitResult(
                pages = read.entries.size,
                records = records.size,
                samplesStored = stored.size,
                droppedAfterBound = merge.droppedAfterBound,
                unreadablePages = read.unreadable,
                pagesNotKept = read.entries.size - sleepPages.size,
            )
        }
    }

    private companion object {
        const val PAGE_4C = 0x4C

        /** How far past `now` a record may be dated and still be kept (the store's ingest allows the same). */
        val NOT_AFTER_ALLOWANCE: Duration = Duration.ofDays(1)
    }
}
