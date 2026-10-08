package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The history journal: every history page the ring sends is written here, raw, BEFORE the app
 * acknowledges it to the ring, and stays until a commit has put its records in the store. One
 * `store_kv` row per page, keyed `history.journal/<ring id>/<seq, 16 digits>`, so a key range is
 * one ring's pages in arrival order. Kotlin-only (upstream acknowledged first and kept pages in
 * memory). Damaged rows are planted on the raw path (SQL), never through an append.
 */
class HistoryJournalTest {

    private val at = Instant.parse("2026-10-08T07:30:00.125Z")

    // A real 0x4c page header and its first record, then a truncated 0x47 page (raw bytes).
    private val page4c = hex("4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300")
    private val page47 = hex("4700000c65863a029f00303c")

    private suspend fun StoreDatabase.plant(key: String, value: String) =
        execRaw("INSERT OR REPLACE INTO store_kv(`key`, value, updated_at) VALUES ('$key', '${value.replace("'", "''")}', 1)")

    @Test
    fun appendedPagesReadBackInArrivalOrderWithTheirTimeAndDrain() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val journal = HistoryJournal(db)

            val first = journal.append("ring-A", page4c, receivedAt = at, drainId = 7)
            val second = journal.append("ring-A", page47, receivedAt = at.plusMillis(1_300), drainId = null)
            val read = journal.read("ring-A")

            assertEquals(listOf(first, second), read.entries.map { it.seq })
            assertContentEquals(page4c, read.entries[0].page)
            assertContentEquals(page47, read.entries[1].page)
            assertEquals(listOf(at, at.plusMillis(1_300)), read.entries.map { it.receivedAt })
            assertEquals(listOf(7L, null), read.entries.map { it.drainId })
            assertEquals(0, read.unreadable)
        }
    }

    @Test
    fun anEmptyJournalReadsEmpty() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val read = HistoryJournal(db).read("ring-A")
            assertEquals(emptyList(), read.entries)
            assertEquals(0, read.unreadable)
        }
    }

    @Test
    fun keysKeepArrivalOrderPastSevenDigitSequenceNumbers() = runBlocking<Unit> {
        withInMemoryStore { db ->
            // The next sequence number planted just below 10 000 000: an unpadded key would sort
            // 10000000 before 9999999.
            db.plant("history.journal.next/ring-A", "9999999")
            val journal = HistoryJournal(db)

            journal.append("ring-A", page4c, at, drainId = 1)
            journal.append("ring-A", page47, at, drainId = 1)

            assertEquals(listOf(9_999_999L, 10_000_000L), journal.read("ring-A").entries.map { it.seq })
            assertEquals(
                listOf("history.journal/ring-A/0000000009999999", "history.journal/ring-A/0000000010000000"),
                db.queryRaw("SELECT `key` FROM store_kv WHERE `key` LIKE 'history.journal/%' ORDER BY `key`"),
            )
        }
    }

    @Test
    fun twoRingsKeepSeparateJournalsEvenWhenOneIdPrefixesTheOther() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val journal = HistoryJournal(db)
            journal.append("ring", page4c, at, drainId = null)
            journal.append("ring2", page47, at, drainId = null)

            assertContentEquals(page4c, journal.read("ring").entries.single().page)
            assertContentEquals(page47, journal.read("ring2").entries.single().page)

            journal.deleteThrough("ring", journal.read("ring").entries.single().seq)
            assertEquals(emptyList(), journal.read("ring").entries)
            assertEquals(1, journal.read("ring2").entries.size, "the other ring's page is kept")
        }
    }

    @Test
    fun deleteThroughRemovesExactlyThePagesUpToItAndSequenceNumbersAreNeverReused() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val journal = HistoryJournal(db)
            val seqs = (1..4).map { journal.append("ring-A", page4c, at.plusSeconds(it.toLong()), drainId = 3) }

            val deleted = journal.deleteThrough("ring-A", seqs[1])
            val next = journal.append("ring-A", page47, at, drainId = null)

            assertEquals(2, deleted)
            assertEquals(listOf(seqs[2], seqs[3], next), journal.read("ring-A").entries.map { it.seq })
            assertEquals(seqs[3] + 1, next, "a sequence number is never handed out twice")
        }
    }

    @Test
    fun aCounterBehindTheRowsNeverOverwritesAStoredPage() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val journal = HistoryJournal(db)
            journal.append("ring-A", page4c, at, drainId = 1)
            journal.append("ring-A", page4c, at, drainId = 1)
            db.plant("history.journal.next/ring-A", "1") // damaged or restored behind the rows

            val seq = journal.append("ring-A", page47, at, drainId = 2)

            assertEquals(3L, seq)
            assertEquals(listOf(1L, 1L, 2L), journal.read("ring-A").entries.map { it.drainId })
        }
    }

    @Test
    fun aDamagedRowIsCountedNeverReadAsAPage() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val journal = HistoryJournal(db)
            journal.append("ring-A", page4c, at, drainId = null)
            db.plant("history.journal/ring-A/0000000000000002", "{\"page\":\"zz\",\"receivedAt\":1}")
            db.plant("history.journal/ring-A/0000000000000003", "not json")
            db.plant("history.journal.next/ring-A", "4")
            journal.append("ring-A", page47, at, drainId = null)

            val read = journal.read("ring-A")

            assertEquals(listOf(1L, 4L), read.entries.map { it.seq })
            assertEquals(2, read.unreadable)
            assertEquals(4L, read.lastSeq, "the damaged rows are inside the range a commit consumes")
        }
    }

    @Test
    fun anAppendedPageIsACopyAndAReadPageIsACopy() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val journal = HistoryJournal(db)
            val page = page4c.copyOf()
            journal.append("ring-A", page, at, drainId = null)
            page[1] = 0x7F

            val entry = journal.read("ring-A").entries.single()
            entry.page[1] = 0x55

            assertContentEquals(page4c, journal.read("ring-A").entries.single().page)
        }
    }

    @Test
    fun aRingIdThatWouldBreakTheKeyIsRefused() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val journal = HistoryJournal(db)
            assertFailsWith<IllegalArgumentException> { journal.append("ring/A", page4c, at, drainId = null) }
            assertFailsWith<IllegalArgumentException> { journal.append("", page4c, at, drainId = null) }
            assertNull(db.queryRaw("SELECT `key` FROM store_kv").firstOrNull())
        }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
