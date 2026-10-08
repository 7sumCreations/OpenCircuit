package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.instant
import io.github.opencircuit.store.codec.json
import io.github.opencircuit.store.codec.jsonObjectOf
import io.github.opencircuit.store.codec.long
import io.github.opencircuit.store.codec.lowerHexDigit
import io.github.opencircuit.store.codec.obj
import io.github.opencircuit.store.codec.optional
import io.github.opencircuit.store.codec.readStored
import io.github.opencircuit.store.codec.required
import io.github.opencircuit.store.codec.string
import io.github.opencircuit.store.codec.unreadable
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * The history journal: each history page the ring sends, stored raw the moment it arrives and
 * BEFORE the app acknowledges it to the ring, kept until a commit has put its records in the store
 * and deleted it. A page that is not here was never acknowledged, so the ring still has it
 * (PORTING.md D-261).
 *
 * One `store_kv` row per page, keyed `history.journal/<ring id>/<seq>` with the sequence number
 * written as 16 digits, so one ring's pages are one key range in arrival order. The value holds
 * the page as lower-case hex, when it arrived and the drain it arrived in (absent outside a
 * drain). The next sequence number is kept under `history.journal.next/<ring id>` and only ever
 * grows: a number is never handed out twice, even after its row is deleted. No schema change:
 * the rows live in the existing key-value table.
 *
 * Kotlin-only: upstream acknowledges first and keeps pages in memory (`DrainBankCadence`,
 * `UnattributedPageBuffer`, a stranded-counter ledger — ios/OpenCircuit/BLE/RingSession.swift:476-515
 * @ b1c2fdd), which this replaces. A [ringId] must be non-empty and contain no `/`.
 */
class HistoryJournal internal constructor(private val db: StoreDatabase, private val kv: KvDao) {
    constructor(db: StoreDatabase) : this(db, db.kvDao())

    /** One journaled page. [page] is a copy on every read. */
    class Entry(
        /** The page's place in its ring's journal: later pages have larger numbers. */
        val seq: Long,
        page: ByteArray,
        /** When the page arrived. */
        val receivedAt: Instant,
        /** The drain the page arrived in, or null for a page that arrived outside one. */
        val drainId: Long?,
    ) {
        private val bytes = page.copyOf()

        /** The page as the ring sent it, opcode and trailer included. */
        val page: ByteArray get() = bytes.copyOf()
    }

    /** One ring's journal as read. */
    data class Read(
        /** The pages that could be read, oldest first. */
        val entries: List<Entry>,
        /** Rows in the range that could not be read as a page: counted, never passed on as one. */
        val unreadable: Int,
        /** The largest sequence number in the range, readable or not; null when the journal is empty. */
        val lastSeq: Long?,
    )

    /**
     * Stores [page], received at [receivedAt] in drain [drainId] (null outside a drain), under the
     * ring's next sequence number, which it returns. One write transaction (joining the caller's
     * when one is open): when this returns the page is durable, and when it throws nothing was
     * written.
     */
    suspend fun append(ringId: String, page: ByteArray, receivedAt: Instant, drainId: Long?): Long {
        val prefix = prefixOf(ringId)
        val value = jsonObjectOf(
            "page" to JsonPrimitive(page.toLowerHex()),
            "receivedAt" to receivedAt.json(),
            "drainId" to drainId?.let(::JsonPrimitive),
        ).toString()
        return db.withWriteTransaction {
            val counterKey = NEXT + ringId
            val counted = kv.get(counterKey)?.value?.toLongOrNull()?.takeIf { it >= FIRST_SEQ } ?: FIRST_SEQ
            // A counter behind the rows (restored, damaged or planted) must never overwrite a page.
            val ahead = kv.range(keyOf(prefix, counted), prefix + END).lastOrNull()?.let { seqOf(prefix, it.key) }
            val seq = if (ahead != null) ahead + 1 else counted
            require(seq <= MAX_SEQ) { "the journal's sequence numbers are used up" }
            kv.upsert(StoreKvEntity(keyOf(prefix, seq), value, receivedAt))
            kv.upsert(StoreKvEntity(counterKey, (seq + 1).toString(), receivedAt))
            seq
        }
    }

    /** The ring's journal, oldest page first. */
    suspend fun read(ringId: String): Read {
        val prefix = prefixOf(ringId)
        val rows = kv.range(prefix + FIRST_KEY, prefix + END)
        val entries = ArrayList<Entry>(rows.size)
        var unreadable = 0
        var lastSeq: Long? = null
        for (row in rows) {
            val seq = seqOf(prefix, row.key)
            if (seq != null) lastSeq = maxOf(lastSeq ?: seq, seq)
            val entry = seq?.let { decode(it, row.value) }
            if (entry == null) unreadable++ else entries += entry
        }
        return Read(entries, unreadable, lastSeq)
    }

    /**
     * Deletes every row of the ring's journal up to and including sequence number [seq], readable
     * or not; returns how many. Joins the caller's transaction when one is open, so a commit
     * deletes the pages it consumed in the same transaction that stored their records.
     */
    suspend fun deleteThrough(ringId: String, seq: Long): Int {
        val prefix = prefixOf(ringId)
        if (seq < FIRST_SEQ) return 0
        val until = if (seq >= MAX_SEQ) prefix + END else keyOf(prefix, seq + 1)
        return db.withWriteTransaction { kv.deleteRange(prefix + FIRST_KEY, until) }
    }

    private fun decode(seq: Long, text: String): Entry? {
        val decoded = readStored(text) { root ->
            val o = root.obj()
            Entry(
                seq = seq,
                page = o.required("page").string().fromLowerHex(),
                receivedAt = o.required("receivedAt").instant(),
                drainId = o.optional("drainId")?.long(),
            )
        }
        return (decoded as? Decoded.Readable)?.value
    }

    private companion object {
        const val JOURNAL = "history.journal/"
        const val NEXT = "history.journal.next/"
        const val DIGITS = 16
        const val FIRST_SEQ = 1L
        const val MAX_SEQ = 9_999_999_999_999_999L
        val FIRST_KEY = "0".repeat(DIGITS)

        /** Sorts after every 16-digit suffix (`:` follows `9`). */
        const val END = ":"

        fun prefixOf(ringId: String): String {
            require(ringId.isNotEmpty() && '/' !in ringId) { "a ring id must be non-empty and contain no '/': \"$ringId\"" }
            return "$JOURNAL$ringId/"
        }

        fun keyOf(prefix: String, seq: Long): String = prefix + seq.toString().padStart(DIGITS, '0')

        /** The sequence number a key of this journal names, or null for a key that is not 16 digits. */
        fun seqOf(prefix: String, key: String): Long? {
            val digits = key.removePrefix(prefix)
            if (digits.length != DIGITS || digits.any { it !in '0'..'9' }) return null
            return digits.toLong()
        }

        fun ByteArray.toLowerHex(): String = buildString(size * 2) {
            for (b in this@toLowerHex) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }

        fun String.fromLowerHex(): ByteArray {
            if (length % 2 != 0) unreadable("odd hex length")
            return ByteArray(length / 2) { i -> ((lowerHexDigit(this[2 * i]) shl 4) or lowerHexDigit(this[2 * i + 1])).toByte() }
        }

        const val HEX = "0123456789abcdef"
    }
}
