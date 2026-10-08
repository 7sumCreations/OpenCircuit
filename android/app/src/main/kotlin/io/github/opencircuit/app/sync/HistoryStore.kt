package io.github.opencircuit.app.sync

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

    /** Puts every stored page's records into the store and forgets the pages, all or nothing. */
    suspend fun commit(now: Instant): CommitResult

    companion object {
        /**
         * No store: every page is refused, so none is acknowledged and the ring keeps them all.
         * For a session that is given no store (the E8 tests); never loses a page.
         */
        val NONE: HistoryStore = object : HistoryStore {
            override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long =
                throw IllegalStateException("this session keeps no history")

            override suspend fun commit(now: Instant): CommitResult = CommitResult()
        }
    }
}

/** What one commit stored. Counts only; never a value or a page. */
data class CommitResult(
    /** Stored pages the commit read and consumed. */
    val pages: Int = 0,
    /** `0x4c` records those pages held, now in the epoch archive. */
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
)
