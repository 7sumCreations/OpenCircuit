package io.github.opencircuit.ringkit

// Accumulates raw epoch pages across one sync drain and records the end-of-history cursor report.
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/EpochSyncSession.swift:5-76 (@ b1c2fdd).
//
// Upstream dates records from the stream high byte and reparses every buffered page when the `0x50`
// report reveals it. Each record carries its own full counter (PORTING.md D-260), so here records
// are dated once, as they arrive, and the report only sets [streamHighByte] and [endOfHistory]: a
// backlog that spans the counter's `0x0c` → `0x0d` rollover keeps every record at its own date.
//
// OWNERSHIP. Upstream is a Swift `mutating struct`, so every assignment was an independent copy.
// Here it is a mutable class owned by ONE drain: sharing the reference shares the state. A caller
// that needs a snapshot takes [copy], which is fully independent (pages are copied too). The
// public record lists are read-only snapshots, and stored pages are private copies of the caller's
// arrays, so neither a caller nor a reader can change the session behind its back.

import java.util.Locale

/**
 * @param syncOpenCursor the cursor sent in the sync-open command (unsigned 32-bit, held in a
 *   `Long`), or null. Its top byte seeds [streamHighByte]; `0xFFFFFFFF` (the skip-backlog open)
 *   and null seed 0.
 */
class EpochSyncSession(syncOpenCursor: Long? = null) {

    /** The cursor's high byte, from the sync-open cursor and then the `0x50` report. Records do not depend on it. */
    var streamHighByte: Int
        private set
    var activityRecords: List<EpochRecord.ActivityRecord> = emptyList()
        private set
    var ppgRecords: List<EpochRecord.PPGRecord> = emptyList()
        private set
    var endOfHistory: EpochRecord.EndOfHistoryFrame? = null
        private set

    private val activityPages = mutableListOf<ByteArray>()
    private val ppgPages = mutableListOf<ByteArray>()

    init {
        require(syncOpenCursor == null || syncOpenCursor in 0..0xFFFF_FFFFL) {
            "syncOpenCursor must be unsigned 32-bit: $syncOpenCursor"
        }
        streamHighByte = if (syncOpenCursor != null && syncOpenCursor != 0xFFFF_FFFFL) {
            ((syncOpenCursor ushr 24) and 0xFF).toInt()
        } else {
            0
        }
    }

    /** Buffer a private copy of a `0x4C` page and return the records parsed from it. */
    fun appendActivityPage(data: ByteArray): List<EpochRecord.ActivityRecord> {
        val page = data.copyOf()
        activityPages += page
        val records = EpochRecord.parseActivityPage(page)
        activityRecords = activityRecords + records
        return records
    }

    /** Buffer a private copy of a `0x47` page and return the records parsed from it. */
    fun appendPPGPage(data: ByteArray): List<EpochRecord.PPGRecord> {
        val page = data.copyOf()
        ppgPages += page
        val records = EpochRecord.parsePPGPage(page)
        ppgRecords = ppgRecords + records
        return records
    }

    /**
     * Apply the `0x50` end-of-history frame: keep it and adopt its high byte. Records already
     * parsed keep their own dates. Returns null (and changes nothing) when [data] is not a valid
     * end-of-history frame.
     */
    fun complete(data: ByteArray): EpochRecord.EndOfHistoryFrame? {
        val frame = EpochRecord.parseEndOfHistory(data) ?: return null
        endOfHistory = frame
        streamHighByte = frame.streamHighByte
        return frame
    }

    val isComplete: Boolean get() = endOfHistory != null

    /** One zero-valued heart-rate placeholder per activity record whose payload is not all zero. */
    fun placeholderQuantitySamples(): List<QuantitySample> =
        activityRecords
            .filter { r -> r.rawPayload.any { it.toInt() != 0 } }
            .map { QuantitySample(kind = MetricKind.HEART_RATE, start = it.timestamp, value = 0.0) }

    /** An independent copy: later changes to either session never reach the other. */
    fun copy(): EpochSyncSession {
        val c = EpochSyncSession()
        c.streamHighByte = streamHighByte
        c.activityRecords = activityRecords
        c.ppgRecords = ppgRecords
        c.endOfHistory = endOfHistory
        activityPages.mapTo(c.activityPages) { it.copyOf() }
        ppgPages.mapTo(c.ppgPages) { it.copyOf() }
        return c
    }

    /** Content equality over the whole state, buffered pages included (upstream `Equatable`). */
    override fun equals(other: Any?): Boolean =
        other is EpochSyncSession && streamHighByte == other.streamHighByte &&
            activityRecords == other.activityRecords && ppgRecords == other.ppgRecords &&
            endOfHistory == other.endOfHistory &&
            pagesEqual(activityPages, other.activityPages) && pagesEqual(ppgPages, other.ppgPages)

    override fun hashCode(): Int {
        var h = streamHighByte
        h = 31 * h + activityRecords.hashCode()
        h = 31 * h + ppgRecords.hashCode()
        h = 31 * h + (endOfHistory?.hashCode() ?: 0)
        activityPages.forEach { h = 31 * h + it.contentHashCode() }
        ppgPages.forEach { h = 31 * h + it.contentHashCode() }
        return h
    }

    override fun toString(): String = String.format(
        Locale.ROOT,
        "EpochSyncSession(streamHighByte=%02x, activityRecords=%d, ppgRecords=%d, endOfHistory=%s)",
        streamHighByte, activityRecords.size, ppgRecords.size, endOfHistory,
    )

    private fun pagesEqual(a: List<ByteArray>, b: List<ByteArray>): Boolean =
        a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }
}
