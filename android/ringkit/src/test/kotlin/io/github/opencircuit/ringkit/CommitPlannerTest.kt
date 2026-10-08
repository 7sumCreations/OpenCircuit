package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.CommitPlanner.Drained
import io.github.opencircuit.ringkit.CommitPlanner.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The commit's pure planning (PORTING.md D-267): which journaled records a commit may put in the
 * store, and in which transactions.
 *
 * - The store's ingest keeps only samples newer than each kind's cursor, so a record may be
 *   released only once no channel can still hold an older one: a channel that ended COMPLETE or
 *   EMPTY is drained through everything; one that delivered records is drained through its
 *   highest counter; one the sync learned nothing about holds everything back.
 * - Released records go oldest first by counter, across both channels, in chunks of at most
 *   5,000; a journal page is consumed by the chunk holding its newest record, and a page with any
 *   record held back is not consumed at all.
 *
 * Records are built on the raw path: 23 bytes with the counter written big-endian in `[0:4]`.
 */
class CommitPlannerTest {

    private fun record(counter: Long): BulkRecord {
        val bytes = ByteArray(BulkRecord.LENGTH)
        bytes[0] = (counter ushr 24).toByte()
        bytes[1] = (counter ushr 16).toByte()
        bytes[2] = (counter ushr 8).toByte()
        bytes[3] = counter.toByte()
        bytes[4] = 60
        return BulkRecord.of(bytes)!!
    }

    private fun page(seq: Long, vararg counters: Long) = Page(seq, counters.map(::record))

    private fun counters(records: List<BulkRecord>) = records.map { it.counter }

    // MARK: chunk size — a literal typed from the approved number, never read back from the code

    @Test
    fun aChunkHoldsAtMostFiveThousandRecords() {
        assertEquals(5_000, CommitPlanner.CHUNK_RECORDS)
    }

    // MARK: drained-through per channel

    @Test
    fun aCompleteOrEmptyChannelIsDrainedThroughEverything() {
        assertEquals(Drained.Everything, CommitPlanner.drained(HistoryChannelOutcome.COMPLETE, lastCounter = 900))
        assertEquals(Drained.Everything, CommitPlanner.drained(HistoryChannelOutcome.EMPTY, lastCounter = null))
    }

    @Test
    fun aChannelCutShortIsDrainedThroughItsNewestRecordOrHoldsEverythingBackWithoutOne() {
        assertEquals(Drained.Through(900), CommitPlanner.drained(HistoryChannelOutcome.PARTIAL, lastCounter = 900))
        assertEquals(Drained.Nothing, CommitPlanner.drained(HistoryChannelOutcome.PARTIAL, lastCounter = null))
        assertEquals(Drained.Nothing, CommitPlanner.drained(HistoryChannelOutcome.NO_ACK, lastCounter = null))
        assertEquals(Drained.Nothing, CommitPlanner.drained(HistoryChannelOutcome.LINK_DOWN, lastCounter = null))
        assertEquals(Drained.Through(450), CommitPlanner.drained(HistoryChannelOutcome.LINK_DOWN, lastCounter = 450))
        // A channel the sync never opened.
        assertEquals(Drained.Nothing, CommitPlanner.drained(null, lastCounter = null))
    }

    @Test
    fun theSyncIsDrainedThroughItsLeastDrainedChannel() {
        assertEquals(Drained.Everything, CommitPlanner.leastDrained(listOf(Drained.Everything, Drained.Everything)))
        assertEquals(Drained.Through(500), CommitPlanner.leastDrained(listOf(Drained.Everything, Drained.Through(500))))
        assertEquals(Drained.Through(500), CommitPlanner.leastDrained(listOf(Drained.Through(900), Drained.Through(500))))
        assertEquals(Drained.Nothing, CommitPlanner.leastDrained(listOf(Drained.Through(900), Drained.Nothing)))
        assertEquals(Drained.Nothing, CommitPlanner.leastDrained(listOf(Drained.Nothing, Drained.Everything)))
        // No channel planned: nothing can still be waiting on the ring.
        assertEquals(Drained.Everything, CommitPlanner.leastDrained(emptyList()))
    }

    // MARK: hold-back

    @Test
    fun recordsNewerThanTheBoundStayJournaledAndAPageStraddlingItIsKeptWhole() {
        // The sleep channel's pages first (newer records), then the all-day's (older), as they arrive.
        val pages = listOf(page(1, 600, 750), page(2, 900, 1_050), page(3, 150, 300), page(4, 450, 750))
        val plan = CommitPlanner.plan(pages, Drained.Through(750))

        assertEquals(1, plan.chunks.size)
        // Released oldest first across both channels; 750 is in two pages, released once.
        assertEquals(listOf(150L, 300, 450, 600, 750), counters(plan.chunks.single().records))
        assertEquals(listOf(1L, 3, 4), plan.chunks.single().consumed)
        assertEquals(listOf(2L), plan.kept)
        assertEquals(2, plan.heldBackRecords)
    }

    @Test
    fun aPageStraddlingTheBoundIsKeptAndItsOlderRecordsAreStillReleased() {
        val plan = CommitPlanner.plan(listOf(page(1, 150, 300, 450)), Drained.Through(300))
        assertEquals(listOf(150L, 300), counters(plan.chunks.single().records))
        assertEquals(emptyList(), plan.chunks.single().consumed)
        assertEquals(listOf(1L), plan.kept)
        assertEquals(1, plan.heldBackRecords)
    }

    @Test
    fun nothingIsReleasedWhileAChannelHoldsEverythingBackButPagesWithNoRecordsAreConsumed() {
        val pages = listOf(page(1, 150, 300), page(2), page(3, 450))
        val plan = CommitPlanner.plan(pages, Drained.Nothing, unreadableSeqs = listOf(4))

        assertEquals(1, plan.chunks.size)
        assertEquals(emptyList(), plan.chunks.single().records)
        assertEquals(listOf(2L, 4), plan.chunks.single().consumed)
        assertEquals(listOf(1L, 3), plan.kept)
        assertEquals(3, plan.heldBackRecords)
    }

    @Test
    fun everythingIsReleasedWhenEveryChannelIsDrained() {
        val plan = CommitPlanner.plan(listOf(page(1, 300, 150), page(2, 450)), Drained.Everything)
        assertEquals(listOf(150L, 300, 450), counters(plan.chunks.single().records))
        assertEquals(listOf(1L, 2), plan.chunks.single().consumed)
        assertEquals(emptyList(), plan.kept)
        assertEquals(0, plan.heldBackRecords)
    }

    @Test
    fun anEmptyJournalPlansNoChunk() {
        val plan = CommitPlanner.plan(emptyList(), Drained.Everything)
        assertEquals(emptyList(), plan.chunks)
        assertEquals(emptyList(), plan.kept)
    }

    @Test
    fun theSameCounterInTwoPagesIsReleasedOnceTakenFromTheLaterPage() {
        val first = Page(1, listOf(record(150)))
        val laterBytes = record(150).raw.also { it[4] = 61 }
        val later = Page(2, listOf(BulkRecord.of(laterBytes)!!))
        val plan = CommitPlanner.plan(listOf(first, later), Drained.Everything)
        assertEquals(listOf(61), plan.chunks.single().records.map { it.raw[4].toInt() })
    }

    // MARK: chunks

    @Test
    fun fiveThousandRecordsAreOneChunkAndFiveThousandAndOneAreTwo() {
        fun pagesOf(n: Int) = (1..n).map { page(it.toLong(), it * 150L) }

        val exact = CommitPlanner.plan(pagesOf(5_000), Drained.Everything)
        assertEquals(listOf(5_000), exact.chunks.map { it.records.size })

        val over = CommitPlanner.plan(pagesOf(5_001), Drained.Everything)
        assertEquals(listOf(5_000, 1), over.chunks.map { it.records.size })
        assertEquals(5_001L * 150, over.chunks[1].records.single().counter)
        assertEquals(listOf(5_001L), over.chunks[1].consumed)
    }

    @Test
    fun aPageIsConsumedByTheChunkHoldingItsNewestRecordAndPagesWithoutRecordsByTheFirst() {
        // Chunks of 2: [150, 300] [450, 600]. Page 1 spans both chunks, so the second consumes it.
        val pages = listOf(page(1, 150, 450), page(2, 300), page(3, 600), page(4))
        val plan = CommitPlanner.plan(pages, Drained.Everything, chunkRecords = 2)

        assertEquals(listOf(listOf(150L, 300), listOf(450L, 600)), plan.chunks.map { counters(it.records) })
        assertEquals(listOf(listOf(2L, 4), listOf(1L, 3)), plan.chunks.map { it.consumed })
    }

    @Test
    fun aChunkSizeBelowOneIsRefused() {
        assertFailsWith<IllegalArgumentException> { CommitPlanner.plan(emptyList(), Drained.Everything, chunkRecords = 0) }
    }
}
