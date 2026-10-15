package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.instant
import io.github.opencircuit.store.codec.json
import io.github.opencircuit.store.codec.jsonObjectOf
import io.github.opencircuit.store.codec.lowerHexDigit
import io.github.opencircuit.store.codec.obj
import io.github.opencircuit.store.codec.readStored
import io.github.opencircuit.store.codec.required
import io.github.opencircuit.store.codec.string
import io.github.opencircuit.store.codec.unreadable
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * History pages this version cannot decode yet, kept on the phone byte for byte so a later
 * version can decode them once their layout is known: older Gen 2 firmware (FR02.013) sends its
 * night as `0x40` pages whose record layout was never captured (../docs/PROTOCOL.md §5.3a). The
 * app stores each page in the [HistoryJournal] and only then acknowledges it, so the ring drops
 * it; the commit moves it here in the same transaction that deletes its journal row, so it is
 * never lost and never blocks the journal (PORTING.md D-274). Nothing is decoded or ingested from
 * these pages.
 *
 * One `store_kv` row per page, keyed `history.raw40/<ring id>/<seq>`, `<seq>` being the page's
 * journal sequence number written as 16 digits — so one ring's pages are one key range in arrival
 * order, and a journal number is never reused. The value holds the page as lower-case hex and when
 * it arrived. At most [kept] pages are kept per ring ([KEPT] in the app): the oldest beyond it are
 * deleted, and their count is added to `history.raw40.dropped/<ring id>`. No schema change. A
 * [ringId] must be non-empty and contain no `/`.
 */
class RawHistoryPages internal constructor(private val db: StoreDatabase, private val kv: KvDao, private val kept: Int) {
    constructor(db: StoreDatabase, kept: Int = KEPT) : this(db, db.kvDao(), kept)

    init {
        require(kept >= 1) { "at least one page is kept: $kept" }
    }

    /** One kept page. [page] is a copy on every read. */
    class Page(
        /** The page's journal sequence number: later pages have larger numbers. */
        val seq: Long,
        page: ByteArray,
        /** When the page arrived. */
        val receivedAt: Instant,
    ) {
        private val bytes = page.copyOf()

        /** The page as the ring sent it, opcode included. */
        val page: ByteArray get() = bytes.copyOf()
    }

    /** What one [keep] did: pages written, and the oldest pages deleted to stay within the cap. */
    data class Kept(val kept: Int, val dropped: Int)

    /**
     * Writes [pages] (journal entries), then deletes the ring's oldest pages beyond the cap and
     * counts them. One write transaction, joining the caller's when one is open: the commit calls
     * it inside the chunk that deletes the same pages from the journal.
     */
    suspend fun keep(ringId: String, pages: List<HistoryJournal.Entry>, now: Instant): Kept {
        val prefix = prefixOf(ringId)
        if (pages.isEmpty()) return Kept(0, 0)
        return db.withWriteTransaction {
            for (entry in pages) {
                val value = jsonObjectOf(
                    "page" to JsonPrimitive(entry.page.toLowerHex()),
                    "receivedAt" to entry.receivedAt.json(),
                ).toString()
                kv.upsert(StoreKvEntity(keyOf(prefix, entry.seq), value, now))
            }
            val rows = kv.range(prefix + FIRST_KEY, prefix + END)
            val over = rows.dropLast(kept)
            over.forEach { kv.delete(it.key) }
            if (over.isNotEmpty()) {
                val droppedKey = DROPPED + ringId
                val before = kv.get(droppedKey)?.value?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                kv.upsert(StoreKvEntity(droppedKey, (before + over.size).toString(), now))
            }
            Kept(pages.size, over.size)
        }
    }

    /** The ring's kept pages, oldest first; a row that cannot be read as a page is skipped. */
    suspend fun read(ringId: String): List<Page> {
        val prefix = prefixOf(ringId)
        return kv.range(prefix + FIRST_KEY, prefix + END).mapNotNull { row ->
            val seq = seqOf(prefix, row.key) ?: return@mapNotNull null
            val decoded = readStored(row.value) { root ->
                val o = root.obj()
                Page(seq, o.required("page").string().fromLowerHex(), o.required("receivedAt").instant())
            }
            (decoded as? Decoded.Readable)?.value
        }
    }

    /** How many of the ring's pages were deleted to stay within the cap, over every commit. */
    suspend fun droppedSoFar(ringId: String): Long {
        prefixOf(ringId)
        return kv.get(DROPPED + ringId)?.value?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    }

    companion object {
        /**
         * Pages kept per ring: 5,000 — about 12 nights at the ~400 pages a night the official app
         * drained from an FR02.013 ring, and at most about 3 MB (a page is one ATT value of at most
         * 244 bytes, stored as hex).
         */
        const val KEPT = 5_000

        private const val RAW = "history.raw40/"
        private const val DROPPED = "history.raw40.dropped/"
        private const val DIGITS = 16
        private val FIRST_KEY = "0".repeat(DIGITS)

        /** Sorts after every 16-digit suffix (`:` follows `9`). */
        private const val END = ":"
        private const val HEX = "0123456789abcdef"

        private fun prefixOf(ringId: String): String {
            require(ringId.isNotEmpty() && '/' !in ringId) { "a ring id must be non-empty and contain no '/': \"$ringId\"" }
            return "$RAW$ringId/"
        }

        private fun keyOf(prefix: String, seq: Long): String {
            require(seq >= 0) { "a journal sequence number is never negative: $seq" }
            return prefix + seq.toString().padStart(DIGITS, '0')
        }

        private fun seqOf(prefix: String, key: String): Long? {
            val digits = key.removePrefix(prefix)
            if (digits.length != DIGITS || digits.any { it !in '0'..'9' }) return null
            return digits.toLong()
        }

        private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
            for (b in this@toLowerHex) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }

        private fun String.fromLowerHex(): ByteArray {
            if (length % 2 != 0) unreadable("odd hex length")
            return ByteArray(length / 2) { i -> ((lowerHexDigit(this[2 * i]) shl 4) or lowerHexDigit(this[2 * i + 1])).toByte() }
        }
    }
}
