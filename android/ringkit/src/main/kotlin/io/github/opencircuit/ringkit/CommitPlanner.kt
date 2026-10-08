package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId

// Which journaled history records one commit may put in the store, and in which transactions.
// Kotlin-only (PORTING.md D-267): upstream commits every record of a drain in one batch
// (ios/OpenCircuit/BLE/RingSession.swift:3656-3665 @ b1c2fdd).
//
// WHY A BOUND. The store's ingest keeps only samples newer than each kind's cursor, and the cursor
// only moves forward. Both history channels carry samples of the same kinds, so committing a
// channel drained to today while the other channel still holds older records would move the
// cursor past those records: the next sync's records of that channel would be dropped as old. A
// record is therefore released only when no planned channel can still hold an older one.
//
// WHY CHUNKS. Records go oldest first by counter across both channels, so every chunk's samples
// are newer than the previous chunk's, and each chunk is one transaction of bounded size and
// memory (5,000 records: about 32 ms and 55 MB per chunk on a 210k-record backlog, measured on the
// JVM store; one batch peaked at 261 MB).

/** Pure planning for the history commit: the hold-back bound, the chunks, and the nights to stage. */
object CommitPlanner {

    /** The most records one commit transaction takes. */
    const val CHUNK_RECORDS = 5_000

    /** How far one channel (or the whole sync) is drained: which records the ring can no longer hold an older one than. */
    sealed interface Drained {
        /** Nothing is left on the ring for this channel: every record may be released. */
        data object Everything : Drained

        /** The channel is drained through the record with [counter]: only records up to it may be released. */
        data class Through(val counter: Long) : Drained

        /** The sync learned nothing about this channel: no record may be released. */
        data object Nothing : Drained
    }

    /** One journal row: its sequence number and the `0x4c` records it holds (none for any other page). */
    class Page(val seq: Long, records: List<BulkRecord>) {
        val records: List<BulkRecord> = records.toList()
    }

    /** One transaction: [records] oldest first, and the journal rows it consumes. */
    data class Chunk(val records: List<BulkRecord>, val consumed: List<Long>)

    /**
     * The commit: [chunks] in order; [kept] the journal rows no chunk consumes (a record of theirs is
     * held back); [heldBackRecords] how many distinct records are held back.
     */
    data class Plan(val chunks: List<Chunk>, val kept: List<Long>, val heldBackRecords: Int)

    /**
     * How far a channel is drained, from its verdict over the sync and the highest counter of the
     * `0x4c` records it delivered ([lastCounter], null when none): COMPLETE or EMPTY → everything
     * (the ring said it had nothing more); otherwise through [lastCounter]; with no record, nothing
     * (never opened, unanswered, or cut before its first page — the ring may still hold any age).
     */
    fun drained(verdict: HistoryChannelOutcome?, lastCounter: Long?): Drained = when {
        verdict == HistoryChannelOutcome.COMPLETE || verdict == HistoryChannelOutcome.EMPTY -> Drained.Everything
        lastCounter != null -> Drained.Through(lastCounter)
        else -> Drained.Nothing
    }

    /**
     * The nights a commit stages, oldest first (PORTING.md D-269): every complete night of the
     * stored [archive] ([BulkSleep.completeNights]; [drainedThrough] is the commit's time when the
     * sync drained every channel, else null) whose last record is after [stagedThrough] — the end of
     * the newest night already staged in sequence (null: none yet). The archive holds released
     * records only, never a held-back journal row (D-267), so a night still partly on the ring is
     * never complete here.
     */
    fun nightsToStage(
        archive: List<BulkRecord>,
        zone: ZoneId,
        drainedThrough: Instant?,
        stagedThrough: Instant?,
        temperatures: List<TemperatureSample> = emptyList(),
    ): List<List<BulkRecord>> =
        BulkSleep.completeNights(archive, zone, drainedThrough, temperatures)
            .filter { night -> stagedThrough == null || night.last().date().isAfter(stagedThrough) }

    /** The least drained of [channels]: the sync may release only what every planned channel is drained through. */
    fun leastDrained(channels: List<Drained>): Drained {
        if (channels.any { it == Drained.Nothing }) return Drained.Nothing
        val through = channels.filterIsInstance<Drained.Through>().minOfOrNull { it.counter } ?: return Drained.Everything
        return Drained.Through(through)
    }

    /**
     * Plans the commit of [pages] (the journal rows that could be read) under [bound]: the records
     * it releases, distinct by counter (a record in two pages is taken from the later one), oldest
     * first, in chunks of at most [chunkRecords]. A page is consumed by the chunk that holds its
     * newest record; a page that holds no record (another opcode), and every row of
     * [unreadableSeqs], by the first chunk; a page with a record held back is kept whole. An empty
     * journal plans no chunk.
     */
    fun plan(pages: List<Page>, bound: Drained, unreadableSeqs: List<Long> = emptyList(), chunkRecords: Int = CHUNK_RECORDS): Plan {
        require(chunkRecords >= 1) { "a chunk holds at least one record: $chunkRecords" }
        fun released(counter: Long) = when (bound) {
            Drained.Everything -> true
            is Drained.Through -> counter <= bound.counter
            Drained.Nothing -> false
        }

        val inOrder = pages.sortedBy { it.seq }
        val byCounter = LinkedHashMap<Long, BulkRecord>()
        for (page in inOrder) for (record in page.records) byCounter[record.counter] = record
        val (out, held) = byCounter.values.partition { released(it.counter) }
        val chunked = out.sortedBy { it.counter }.chunked(chunkRecords)
        val chunkNewest = chunked.map { it.last().counter }

        val consumed = List(maxOf(chunked.size, 1)) { mutableListOf<Long>() }
        val kept = mutableListOf<Long>()
        for (page in inOrder) {
            when {
                page.records.isEmpty() -> consumed[0] += page.seq
                page.records.all { released(it.counter) } -> {
                    val newest = page.records.maxOf { it.counter }
                    consumed[chunkNewest.indexOfFirst { it >= newest }] += page.seq
                }
                else -> kept += page.seq
            }
        }
        consumed[0] += unreadableSeqs
        consumed[0].sort()

        val chunks = if (chunked.isEmpty()) {
            if (consumed[0].isEmpty()) emptyList() else listOf(Chunk(emptyList(), consumed[0].toList()))
        } else {
            chunked.mapIndexed { i, records -> Chunk(records, consumed[i].toList()) }
        }
        return Plan(chunks, kept, held.size)
    }
}
