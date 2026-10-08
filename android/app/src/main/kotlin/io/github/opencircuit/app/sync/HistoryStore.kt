package io.github.opencircuit.app.sync

import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.HistoryCommitGate
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
     * each chunk all or nothing. Throws when a chunk fails; the chunks before it are kept. Before
     * each chunk after the first it asks [keepGoing]; on false it stops there and the remaining
     * pages stay stored (a paused sync's commit is bounded in time, PORTING.md D-268).
     *
     * Then, unless told to stop, it stages nights (PORTING.md D-269): [evidence] — what the sync's
     * own channels delivered — goes through `HistoryCommitGate.decide` (D-43); on STAGE or
     * RESTAGE_FROM_ARCHIVE every complete night of the stored archive not yet staged is staged and
     * saved, oldest first, until the first one the store defers.
     */
    suspend fun commit(
        now: Instant,
        drained: CommitPlanner.Drained,
        keepGoing: () -> Boolean = { true },
        evidence: SyncEvidence = SyncEvidence.NONE,
    ): CommitResult

    companion object {
        /**
         * No store: every page is refused, so none is acknowledged and the ring keeps them all.
         * For a session that is given no store (the E8 tests); never loses a page.
         */
        val NONE: HistoryStore = object : HistoryStore {
            override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long =
                throw IllegalStateException("this session keeps no history")

            override suspend fun commit(now: Instant, drained: CommitPlanner.Drained, keepGoing: () -> Boolean, evidence: SyncEvidence): CommitResult =
                CommitResult()
        }
    }
}

/**
 * What a sync's own channels say about the night, for the commit's staging gate
 * (`HistoryCommitGate.decide`): the sleep channel's verdict (null when it never ran), the new
 * `0x4c` records it delivered, and the sleep-vitals records another channel delivered (a ring can
 * hand its night to the all-day channel). Records stored outside any drain are counted by the store
 * itself, from its journal.
 */
data class SyncEvidence(
    val sleepOutcome: HistoryChannelOutcome? = null,
    val sleepRecordsAdded: Int = 0,
    val nightRecordsOnOtherChannels: Int = 0,
) {
    companion object {
        /** Nothing drained: no channel ran. */
        val NONE = SyncEvidence()
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
    /** Transactions it did not start because it was told to stop; their pages stay stored. */
    val chunksLeft: Int = 0,
    /** The staging gate's decision; null when the commit stopped before staging. */
    val staging: HistoryCommitGate.Decision? = null,
    /** Complete nights staged and handed to the store (whatever the store kept). */
    val nightsStaged: Int = 0,
    /**
     * Complete nights left for a later commit: the store holds every save until its one-time move of
     * stored nights is done, or a save failed. Never dropped — the archive keeps them.
     */
    val nightsWaiting: Int = 0,
    /**
     * What went wrong while staging, by kind (a failed night save, re-derivation or nap save);
     * null when nothing did. The records are stored either way; the nights are retried later.
     */
    val stagingFault: String? = null,
)
