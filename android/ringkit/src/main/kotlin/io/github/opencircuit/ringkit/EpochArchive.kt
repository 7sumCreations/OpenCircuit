package io.github.opencircuit.ringkit

// Rolling archive of recent `0x4c` epoch records — the foundation for stitching a night the ring
// hands off in MORE THAN ONE drain. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/EpochArchive.swift:20-57 (@ b1c2fdd).
//
// Sleep staging needs the per-epoch motion channel `[10:15]`, which the derived HR/HRV/SpO2
// samples do not keep, so the raw records are kept and a night is re-staged from the UNION of
// every drain's records. (The ring buffers history for DAYS — ../docs/PROTOCOL.md §3 — not the
// ~4.75 h some older upstream comments assumed.)
//
// The stored form is dead simple: a `0x4c` record is a fixed 23 bytes, so the archive serializes
// as the records' raw bytes concatenated, and decodes with the same `BulkSleep.recordsFromStream`
// used for a live page. Where that blob lives is the persistence layer's business.

import java.time.Duration
import java.time.Instant

object EpochArchive {

    /**
     * How much history to retain. ~30 h covers "last night" even after a lie-in or a late first
     * sync. 30 h > 24 h, so the archive can hold TWO nights — staging must scope to the most recent
     * night itself. Counters are epoch-seconds, so this is compared directly against counter deltas.
     */
    val RETENTION: Duration = Duration.ofHours(30)

    /** The largest counter delta a retention can express: counters are unsigned 32-bit seconds. */
    private const val UINT32_MAX = 0xFFFF_FFFFL

    /**
     * Merge [incoming] records into [existing]: dedup by counter (a later drain's copy wins on
     * collision), sort ascending by counter, and prune anything older than [retention] before the
     * newest record. Returns the new archive.
     *
     * [notAfter] (Kotlin-only, PORTING D-44) drops every record dated later than it, from the
     * result AND from the retention anchor. Without it one garbage record with a far-future counter
     * (the 1-byte XOR trailer lets ~1 in 256 garbage frames through) becomes the "newest" and prunes
     * every genuine record more than [retention] older — upstream's behaviour, kept when `null`.
     * A caller passes the current time plus a clock-skew allowance. A bound set too tight (the phone
     * clock behind the ring's by more than the allowance) drops genuine records, and the merge result
     * is what gets persisted, so size the allowance generously.
     *
     * [retention] is truncated to whole seconds and must be 0 … 2³²−1 s, the range upstream's
     * unsigned 32-bit conversion accepts.
     */
    fun merge(
        existing: List<BulkRecord>,
        incoming: List<BulkRecord>,
        retention: Duration = RETENTION,
        notAfter: Instant? = null,
    ): List<BulkRecord> {
        val span = retention.seconds
        require(!retention.isNegative && span <= UINT32_MAX) { "retention must be 0..2^32-1 seconds: $retention" }
        if (existing.isEmpty() && incoming.isEmpty()) return emptyList()
        val byCounter = HashMap<Long, BulkRecord>(existing.size + incoming.size)
        for (r in existing) byCounter[r.counter] = r
        for (r in incoming) byCounter[r.counter] = r // a fresher drain overrides an older copy
        val all = byCounter.values.filter { notAfter == null || !it.date().isAfter(notAfter) }.sortedBy { it.counter }
        val newest = all.lastOrNull()?.counter ?: return emptyList()
        // Counters are unsigned: a newest counter smaller than the span must clamp to 0, never go
        // negative (upstream guards the same subtraction against UInt32 underflow).
        val cutoff = if (newest > span) newest - span else 0L
        return all.filter { it.counter >= cutoff }
    }

    /**
     * The most the archive keeps while nights wait to be staged from it: 14 days — a Gen 3 ring's
     * ~10 days of storage plus a margin (about 16k records, measured at ~15 ms to encode and write).
     */
    val STAGED_RETENTION_CAP: Duration = Duration.ofDays(14)

    /**
     * How far back from [newest] the archive keeps records once nights are staged from it
     * (Kotlin-only, PORTING.md D-270): back to [RETENTION] before [stagedThrough] — the end of the
     * newest night saved with every earlier one saved too — so every night not yet staged stays,
     * never less than [RETENTION], never more than [STAGED_RETENTION_CAP]. Nothing staged yet
     * (null) keeps the cap: a first backlog of several days is kept until its nights are staged.
     * Upstream keeps a flat 30 h, which prunes every night of a multi-day drain but the last.
     */
    fun retentionKeepingUnstaged(newest: Instant, stagedThrough: Instant?): Duration {
        if (stagedThrough == null) return STAGED_RETENTION_CAP
        val sinceStaged = Duration.between(stagedThrough.minus(RETENTION), newest)
        return minOf(STAGED_RETENTION_CAP, maxOf(RETENTION, sinceStaged))
    }

    /**
     * [merge] with the retention [retentionKeepingUnstaged] gives for [stagedThrough], anchored on
     * the newest record not dated after [notAfter] (required: PORTING.md D-44 — a far-future
     * garbage record can neither be kept nor become the anchor that prunes genuine history).
     */
    fun mergeKeepingUnstaged(existing: List<BulkRecord>, incoming: List<BulkRecord>, stagedThrough: Instant?, notAfter: Instant): List<BulkRecord> {
        val newest = (existing.asSequence() + incoming.asSequence()).map { it.date() }.filter { !it.isAfter(notAfter) }.maxOrNull()
            ?: return emptyList()
        return merge(existing, incoming, retentionKeepingUnstaged(newest, stagedThrough), notAfter)
    }

    /** Serialize to a flat blob (concatenated 23-byte records). A new array on every call. */
    fun encode(records: List<BulkRecord>): ByteArray {
        val out = ByteArray(records.size * BulkRecord.LENGTH)
        records.forEachIndexed { i, r -> r.raw.copyInto(out, i * BulkRecord.LENGTH) }
        return out
    }

    /** Decode a blob back into records (a trailing partial chunk, if any, is dropped). */
    fun decode(data: ByteArray): List<BulkRecord> = BulkSleep.recordsFromStream(data)
}
