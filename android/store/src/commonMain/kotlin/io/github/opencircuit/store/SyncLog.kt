package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import io.github.opencircuit.store.codec.SyncLogCodec
import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.SyncMeasurement
import java.time.Instant

/**
 * One channel of one logged sync. Counts, times and the ring's `0x82` status bytes only — never a
 * page, a value or an address.
 */
data class SyncLogChannel(
    /** `sleep` or `all-day`. */
    val label: String,
    /** The wire selector byte. */
    val channel: Int,
    /** The channel's verdict over its rounds; null when it never ran a round. */
    val verdict: HistoryChannelOutcome? = null,
    /** Every `0x82` answer, in order, as plain hex. */
    val syncAcks: List<String> = emptyList(),
    /** The open needed the link's re-auth fallback. */
    val openFallback: Boolean = false,
    /** With the fallback: whether the ring answered after it. */
    val fallbackHelped: Boolean? = null,
    /** The first and the latest `0x4c` page's 16-bit countdown. */
    val firstCountdown: Int? = null,
    val lastCountdown: Int? = null,
    /** Pages by opcode. */
    val pages4c: Int = 0,
    val pages47: Int = 0,
    val pages4d: Int = 0,
    /** New `0x4c` records (unique counters). */
    val records: Int = 0,
    /** The lowest and highest record counter delivered (sync-epoch seconds). */
    val firstCounter: Long? = null,
    val lastCounter: Long? = null,
    /** How the last round ended. */
    val exitReason: HistoryChannelExitReason? = null,
    /** A `0x50` end report came after this channel's `0x82`. */
    val endSeen: Boolean = false,
    /** Open rounds (1 + reopens). */
    val rounds: Int = 0,
    /** From the first open to the last round's end. */
    val durationMillis: Long? = null,
    /** Gaps between consecutive pages of a round. */
    val pageGapP50Millis: Long? = null,
    val pageGapMaxMillis: Long? = null,
    /** From a page's arrival to its acknowledgement written (the journal write included). */
    val ackLatencyP50Millis: Long? = null,
    val ackLatencyP95Millis: Long? = null,
    /** `0x4c` pages that brought no record not already delivered: the ring offered them again. */
    val reoffers: Int = 0,
    /** `0x50` frames before this channel's `0x82`: answers to a status query. */
    val statusReplies: Int = 0,
    /** How far the ring is drained on this channel: its end when complete, else its last record; null when nothing was learned. */
    val drainedThrough: Instant? = null,
    /** Whether this channel's records join the earlier syncs'. */
    val continuity: SyncMeasurement.Continuity? = null,
)

/** One finished sync in the log. */
data class SyncLogEntry(
    val startedAt: Instant,
    val finishedAt: Instant,
    /** How the sync ended, in the app's word (`COMPLETE`, `PARTIAL`, …): kept as written. */
    val outcome: String,
    /** The user left the app during it and it was paused. */
    val paused: Boolean = false,
    /** The firmware version the ring reported ("FR02.018"); null when not read. */
    val firmware: String? = null,
    val channels: List<SyncLogChannel> = emptyList(),
    /** Distinct records the commit stored. */
    val recordsStored: Int = 0,
    /** Distinct records kept on the phone for a later commit, because a channel may still hold older ones. */
    val heldBack: Int = 0,
    /** The channels (labels) that held them back. */
    val heldBackBy: List<String> = emptyList(),
    /** Nights staged, and nights left for a later commit. */
    val nightsStaged: Int = 0,
    val nightsWaiting: Int = 0,
    /** Records dated past the store's bound, never stored. */
    val droppedAfterBound: Int = 0,
    /** Pages the ring sent that were never acknowledged (it offers them again). */
    val pagesUnacknowledged: Int = 0,
    /** Frames the link received that the app never took. */
    val undeliveredFrames: Int = 0,
    /** The oldest and newest record the sync delivered. */
    val oldestRecord: Instant? = null,
    val newestRecord: Instant? = null,
    /** Distinct records per local day (`yyyy-mm-dd`). */
    val recordsPerDay: Map<String, Int> = emptyMap(),
    /** The ring's charge markers in its `0x50` event log. */
    val chargeMarkers: List<Instant> = emptyList(),
    /** What this sync says about the ring's storage. */
    val capacity: SyncMeasurement.CapacityVerdict? = null,
)

/**
 * The sync log: one [SyncLogEntry] per finished sync, the last [KEPT] kept per ring, so "Last
 * synced", the last result and the measurements survive a relaunch, and Connection details can
 * show the owner what each sync saw. Kotlin-only (PORTING.md D-273): upstream keeps no such log.
 *
 * One `store_kv` row per entry, keyed `sync.log/<ring id>/<seq>` with the sequence number written
 * as 16 digits (as the history journal's keys), so one ring's log is one key range in order. The
 * next number is kept under `sync.log.next/<ring id>` and only grows. No schema change. A [ringId]
 * must be non-empty and contain no `/`.
 */
class SyncLog internal constructor(private val db: StoreDatabase, private val kv: KvDao) {
    constructor(db: StoreDatabase) : this(db, db.kvDao())

    /** One ring's log as read: the [entries] that could be read, oldest first, and how many rows could not. */
    data class Read(val entries: List<SyncLogEntry>, val unreadable: Int)

    /**
     * Adds [entry] under the ring's next sequence number, which it returns, and drops the ring's
     * oldest entries beyond [KEPT] — in one write transaction: when this returns the entry is
     * durable, and when it throws nothing was written.
     */
    suspend fun append(ringId: String, entry: SyncLogEntry, now: Instant): Long {
        val prefix = prefixOf(ringId)
        val value = SyncLogCodec.encode(entry)
        return db.withWriteTransaction {
            val counterKey = NEXT + ringId
            val counted = kv.get(counterKey)?.value?.toLongOrNull()?.takeIf { it >= FIRST_SEQ } ?: FIRST_SEQ
            val rows = kv.range(prefix + FIRST_KEY, prefix + END)
            // A counter behind the rows (restored or damaged) must never overwrite an entry.
            val ahead = rows.lastOrNull()?.let { seqOf(prefix, it.key) }
            val seq = if (ahead != null && ahead >= counted) ahead + 1 else counted
            require(seq <= MAX_SEQ) { "the sync log's sequence numbers are used up" }
            kv.upsert(StoreKvEntity(keyOf(prefix, seq), value, now))
            kv.upsert(StoreKvEntity(counterKey, (seq + 1).toString(), now))
            // The rows before this one, oldest first: keep the newest KEPT - 1 of them.
            rows.dropLast(KEPT - 1).forEach { kv.delete(it.key) }
            seq
        }
    }

    /** The ring's log, oldest entry first. */
    suspend fun read(ringId: String): Read {
        val prefix = prefixOf(ringId)
        val rows = kv.range(prefix + FIRST_KEY, prefix + END)
        val entries = ArrayList<SyncLogEntry>(rows.size)
        var unreadable = 0
        for (row in rows) {
            val entry = if (seqOf(prefix, row.key) == null) null else SyncLogCodec.decode(row.value).valueOrNull()
            if (entry == null) unreadable++ else entries += entry
        }
        return Read(entries, unreadable)
    }

    companion object {
        /** Entries kept per ring. */
        const val KEPT = 50

        private const val LOG = "sync.log/"
        private const val NEXT = "sync.log.next/"
        private const val DIGITS = 16
        private const val FIRST_SEQ = 1L
        private const val MAX_SEQ = 9_999_999_999_999_999L
        private val FIRST_KEY = "0".repeat(DIGITS)

        /** Sorts after every 16-digit suffix (`:` follows `9`). */
        private const val END = ":"

        private fun prefixOf(ringId: String): String {
            require(ringId.isNotEmpty() && '/' !in ringId) { "a ring id must be non-empty and contain no '/': \"$ringId\"" }
            return "$LOG$ringId/"
        }

        private fun keyOf(prefix: String, seq: Long): String = prefix + seq.toString().padStart(DIGITS, '0')

        private fun seqOf(prefix: String, key: String): Long? {
            val digits = key.removePrefix(prefix)
            if (digits.length != DIGITS || digits.any { it !in '0'..'9' }) return null
            return digits.toLong()
        }
    }
}
