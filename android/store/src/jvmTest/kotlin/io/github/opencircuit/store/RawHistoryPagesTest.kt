package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The permanent raw-page store: history pages this version cannot decode yet (older Gen 2
 * firmware's `0x40` pages, ../docs/PROTOCOL.md §5.3a) are kept byte for byte so a later version
 * can decode them once their layout is known. One `store_kv` row per page, keyed
 * `history.raw40/<ring id>/<journal seq, 16 digits>`; at most a fixed number of pages per ring,
 * the oldest dropped first and counted. The `0x40` bodies here are arbitrary bytes: the layout was
 * never captured.
 */
class RawHistoryPagesTest {

    private val at = Instant.parse("2026-10-15T03:00:00.250Z")

    private fun page(n: Int) = byteArrayOf(0x40, 0x00, n.toByte(), 0x11, 0x22, (n * 3).toByte())

    private fun entry(seq: Long, n: Int = seq.toInt()) = HistoryJournal.Entry(seq, page(n), at.plusSeconds(seq), drainId = 9)

    @Test
    fun keptPagesReadBackInArrivalOrderByteForByteWithTheirTime() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val raw = RawHistoryPages(db)

            val kept = raw.keep("ring-A", listOf(entry(3), entry(11)), now = at)
            val read = raw.read("ring-A")

            assertEquals(RawHistoryPages.Kept(kept = 2, dropped = 0), kept)
            assertEquals(listOf(3L, 11L), read.map { it.seq })
            assertContentEquals(page(3), read[0].page)
            assertContentEquals(page(11), read[1].page)
            assertEquals(listOf(at.plusSeconds(3), at.plusSeconds(11)), read.map { it.receivedAt })
            assertEquals(0L, raw.droppedSoFar("ring-A"))
            assertEquals(emptyList(), raw.read("ring-B"), "one ring's pages are not another's")
        }
    }

    @Test
    fun overTheCapTheOldestPagesAreDroppedFirstAndTheDropsAreCounted() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val raw = RawHistoryPages(db, kept = 3)

            val first = raw.keep("ring-A", (1L..5L).map { entry(it) }, now = at)
            assertEquals(RawHistoryPages.Kept(kept = 5, dropped = 2), first)
            assertEquals(listOf(3L, 4L, 5L), raw.read("ring-A").map { it.seq })

            val second = raw.keep("ring-A", listOf(entry(6), entry(7)), now = at)
            assertEquals(RawHistoryPages.Kept(kept = 2, dropped = 2), second)
            assertEquals(listOf(5L, 6L, 7L), raw.read("ring-A").map { it.seq })
            assertEquals(4L, raw.droppedSoFar("ring-A"), "the drops add up across commits")
        }
    }

    @Test
    fun theCapHoldsSeveralNightsYetCannotFillThePhone() {
        // A night on older firmware is about 400 pages (the official app's drain, PROTOCOL.md §5.3a);
        // a page is at most one 244-byte ATT value, about 0.5 KB stored as hex.
        assertTrue(RawHistoryPages.KEPT >= 5 * 400, "fewer than five nights: ${RawHistoryPages.KEPT}")
        assertTrue(RawHistoryPages.KEPT * 600L <= 10_000_000L, "more than about 10 MB: ${RawHistoryPages.KEPT}")
    }

    @Test
    fun keepJoinsTheCallersTransactionSoAFailedCommitKeepsNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val raw = RawHistoryPages(db)

            assertFailsWith<IllegalStateException> {
                db.withWriteTransaction {
                    raw.keep("ring-A", listOf(entry(1)), now = at)
                    error("the chunk failed")
                }
            }

            assertEquals(emptyList(), raw.read("ring-A"))
        }
    }

    @Test
    fun theSamePageKeptTwiceIsOneRow() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val raw = RawHistoryPages(db)

            raw.keep("ring-A", listOf(entry(4)), now = at)
            raw.keep("ring-A", listOf(entry(4)), now = at)

            assertEquals(listOf(4L), raw.read("ring-A").map { it.seq })
        }
    }

    @Test
    fun aRingIdWithASlashIsRefused() = runBlocking<Unit> {
        withInMemoryStore { db ->
            assertFailsWith<IllegalArgumentException> { RawHistoryPages(db).read("ring/A") }
        }
    }
}
