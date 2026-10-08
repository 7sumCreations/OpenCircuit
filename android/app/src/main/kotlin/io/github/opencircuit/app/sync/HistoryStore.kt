package io.github.opencircuit.app.sync

import io.github.opencircuit.ringkit.CommitPlanner
import java.time.Instant

/**
 * Where a ring session keeps the history the ring sends: the session's one way to the store.
 * [append] stores a page before the ring is told it may drop it; [commit] turns the stored pages
 * into the store's records and samples.
 */
interface HistoryStore {
    /**
     * Stores [page] durably, received at [receivedAt] in drain [drainId] (null outside a drain).
     * Returns only once the page is durable; throws when it could not be stored, and then the page
     * must not be acknowledged.
     */
    suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long

    /**
     * Puts the stored pages' records into the store and forgets those pages — only records the
     * sync is [drained] through (a channel that may still hold older records holds the newer ones
     * back, PORTING.md D-267); the rest stay stored for a later commit. Oldest first, in chunks:
     * each chunk all or nothing. Throws when a chunk fails; the chunks before it are kept.
     */
    suspend fun commit(now: Instant, drained: CommitPlanner.Drained): CommitResult

    companion object {
        /**
         * No store: every page is refused, so none is acknowledged and the ring keeps them all.
         * For a session that is given no store (the E8 tests); never loses a page.
         */
        val NONE: HistoryStore = object : HistoryStore {
            override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long =
                throw IllegalStateException("this session keeps no history")

            override suspend fun commit(now: Instant, drained: CommitPlanner.Drained): CommitResult = CommitResult()
        }
    }
}

/** What one commit stored. Counts only; never a value or a page. */
data class CommitResult(
    /** Stored pages the commit read and consumed. */
    val pages: Int = 0,
    /** Distinct `0x4c` records those pages held, now in the epoch archive. */
    val records: Int = 0,
    /** Samples the store kept from those records (older or implausible ones are not stored again). */
    val samplesStored: Int = 0,
    /** Records dated after the merge's bound (now plus one day): never stored, counted. */
    val droppedAfterBound: Int = 0,
    /** Stored rows that could not be read back as a page: consumed and counted, never passed on. */
    val unreadablePages: Int = 0,
    /**
     * Pages consumed without records: `0x47` (raw sensor pages, not decoded into samples) and
     * `0x4d` (sport history, which this version does not keep). Counted, so the loss is shown.
     */
    val pagesNotKept: Int = 0,
    /** Distinct records held back for a later commit, because a channel may still hold older ones. */
    val heldBack: Int = 0,
    /** Stored pages kept for a later commit (one of their records is held back). */
    val pagesKept: Int = 0,
    /** Transactions the commit ran. */
    val chunks: Int = 0,
)
