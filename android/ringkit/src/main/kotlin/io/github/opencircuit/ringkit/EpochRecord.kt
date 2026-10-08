package io.github.opencircuit.ringkit

// Timestamp-only decoder for epoch sync records. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/EpochRecord.swift:8-151 (@ b1c2fdd).
//
// Exposes only byte-structure facts confirmed by captures (../docs/PROTOCOL.md §5.6): record
// splitting, cursor-space timestamp reconstruction, subtype tags, and raw payload bytes for
// later metric decoding.
//
// A record's bytes [0:4] are its own big-endian counter (../docs/PROTOCOL.md §5.3), the same
// reading as `BulkRecord.counter`. Upstream treats byte 0 as a fixed `0x0c` delimiter and takes the
// high byte from the caller; that byte becomes `0x0d` at 2026-11-28 20:23:28 UTC. Here a record is
// dated by its own counter and kept when that counter is a plausible date (PORTING.md D-260).
//
// Value-type notes (Swift structs copy on assignment and `UInt8`/`UInt32` cannot leave their
// range): every record keeps a private copy of its payload and hands out a fresh copy on each
// read; byte fields are checked 0..255 and cursor fields 0..0xFFFFFFFF at construction.

import java.time.Instant

object EpochRecord {
    /** Seconds since 2019-12-31 12:00:00 UTC; one home, in [Command]. */
    const val SYNC_EPOCH = Command.SYNC_EPOCH
    const val PPG_OPCODE = 0x47
    const val ACTIVITY_OPCODE = 0x4C
    const val END_OF_HISTORY_OPCODE = 0x50
    const val PPG_RECORD_SIZE = 47
    const val ACTIVITY_RECORD_SIZE = 23

    private const val UINT32_MAX = 0xFFFF_FFFFL

    /**
     * Record counters that are a plausible date: 2022-01-01T00:00:00Z up to, not including,
     * 2100-01-01T00:00:00Z. Outside it are zero / all-ones filler and a clock that was never set,
     * which upstream's `0x0c` byte check also refused (D-260).
     */
    val PLAUSIBLE_COUNTERS: LongRange =
        (Instant.parse("2022-01-01T00:00:00Z").epochSecond - SYNC_EPOCH) until
            (Instant.parse("2100-01-01T00:00:00Z").epochSecond - SYNC_EPOCH)

    /** `0x4C` activity/sleep record — 23 bytes. [rawPayload] is bytes `[15:22]`. */
    class ActivityRecord(val timestamp: Instant, val subtype: Int, rawPayload: ByteArray) {
        init {
            require(subtype in 0..0xFF) { "subtype must be a byte 0..255: $subtype" }
        }

        private val payload: ByteArray = rawPayload.copyOf()

        /** A fresh copy on every read. */
        val rawPayload: ByteArray get() = payload.copyOf()

        override fun equals(other: Any?): Boolean =
            other is ActivityRecord && timestamp == other.timestamp && subtype == other.subtype &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int = (31 * timestamp.hashCode() + subtype) * 31 + payload.contentHashCode()

        override fun toString(): String =
            "ActivityRecord(timestamp=$timestamp, subtype=%02x, rawPayload=[%s])".format(subtype, payload.hexString())
    }

    /** `0x47` PPG/waveform record — 47 bytes. [rawPayload] is bytes `[9:47]`. */
    class PPGRecord(val timestamp: Instant, rawPayload: ByteArray) {
        private val payload: ByteArray = rawPayload.copyOf()

        /** A fresh copy on every read. */
        val rawPayload: ByteArray get() = payload.copyOf()

        override fun equals(other: Any?): Boolean =
            other is PPGRecord && timestamp == other.timestamp && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * timestamp.hashCode() + payload.contentHashCode()

        override fun toString(): String = "PPGRecord(timestamp=$timestamp, rawPayload=[${payload.hexString()}])"
    }

    /** The `0x50` end-of-history cursor report. Cursors are unsigned 32-bit values held in a `Long`. */
    data class EndOfHistoryFrame(val subtype: Int, val cursorFrom: Long, val cursorTo: Long) {
        init {
            require(subtype in 0..0xFF) { "subtype must be a byte 0..255: $subtype" }
            require(cursorFrom in 0..UINT32_MAX) { "cursorFrom must be unsigned 32-bit: $cursorFrom" }
            require(cursorTo in 0..UINT32_MAX) { "cursorTo must be unsigned 32-bit: $cursorTo" }
        }

        /** High byte of the 4-byte stream cursor (0..255). */
        val streamHighByte: Int get() = ((cursorTo ushr 24) and 0xFF).toInt()
    }

    /**
     * Parse all activity records out of a `0x4C` page frame, each dated by its own counter.
     * A page that fails the XOR check, has the wrong opcode, or does not split into whole records
     * yields no records; a record whose counter is outside [PLAUSIBLE_COUNTERS] is skipped.
     */
    fun parseActivityPage(data: ByteArray): List<ActivityRecord> {
        val payload = pagePayload(data, ACTIVITY_OPCODE) ?: return emptyList()
        if (payload.size % ACTIVITY_RECORD_SIZE != 0) return emptyList()
        return (0 until payload.size step ACTIVITY_RECORD_SIZE).mapNotNull { offset ->
            val record = payload.copyOfRange(offset, offset + ACTIVITY_RECORD_SIZE)
            val timestamp = plausibleTimestamp(record) ?: return@mapNotNull null
            ActivityRecord(timestamp = timestamp, subtype = record.u8(8), rawPayload = record.copyOfRange(15, 22))
        }
    }

    /** Parse all PPG records out of a `0x47` page frame; same rejection rules as [parseActivityPage]. */
    fun parsePPGPage(data: ByteArray): List<PPGRecord> {
        val payload = pagePayload(data, PPG_OPCODE) ?: return emptyList()
        if (payload.size % PPG_RECORD_SIZE != 0) return emptyList()
        return (0 until payload.size step PPG_RECORD_SIZE).mapNotNull { offset ->
            val record = payload.copyOfRange(offset, offset + PPG_RECORD_SIZE)
            val timestamp = plausibleTimestamp(record) ?: return@mapNotNull null
            PPGRecord(timestamp = timestamp, rawPayload = record.copyOfRange(9, 47))
        }
    }

    /**
     * Bytes 1–2 (big endian) of a `0x47`/`0x4C` page: the ring's remaining-record countdown,
     * 0..65535, or null for any other frame. Upstream reads byte 2 alone, which wraps above 255
     * records queued (PORTING.md D-259).
     */
    fun remainingRecordCountdown(data: ByteArray): Int? {
        if (data.size < 3) return null
        val op = data.u8(0)
        if (op != PPG_OPCODE && op != ACTIVITY_OPCODE) return null
        return (data.u8(1) shl 8) or data.u8(2)
    }

    /**
     * Parse the no-XOR `0x50` end-of-history cursor report.
     *
     * The confirmed capture shape is a 6-byte cursor entry after `50 00 00`. Newer issue notes
     * describe a compact from/to form. All three documented lengths (12, 9 with `0x15` at byte 3,
     * and 8) are accepted so the session can recover the high cursor byte without blocking the
     * drain; any other length returns null.
     */
    fun parseEndOfHistory(data: ByteArray): EndOfHistoryFrame? {
        if (data.size < 8 || data.u8(0) != END_OF_HISTORY_OPCODE || data.u8(1) != 0x00 || data.u8(2) != 0x00) {
            return null
        }
        return when {
            data.size == 12 ->
                EndOfHistoryFrame(subtype = data.u8(3), cursorFrom = uint32BE(data, 4), cursorTo = uint32BE(data, 8))
            data.size == 9 && data.u8(3) == 0x15 -> {
                val cursor = uint32BE(data, 5)
                EndOfHistoryFrame(subtype = data.u8(4), cursorFrom = cursor, cursorTo = cursor)
            }
            data.size == 8 -> {
                val cursor = uint32BE(data, 4)
                EndOfHistoryFrame(subtype = data.u8(3), cursorFrom = cursor, cursorTo = cursor)
            }
            else -> null
        }
    }

    /** The records of a page: the body after its two countdown bytes, whatever their value (D-259). */
    private fun pagePayload(bytes: ByteArray, opcode: Int): ByteArray? {
        if (bytes.isEmpty() || bytes.u8(0) != opcode) return null
        val parsed = Frame.parse(bytes) ?: return null
        val body = parsed.body
        if (parsed.opcode != opcode || body.size < 2) return null
        return body.copyOfRange(2, body.size)
    }

    /** The record's own `[0:4]` counter as a date, or null when it is not a plausible one. */
    private fun plausibleTimestamp(record: ByteArray): Instant? {
        val counter = uint32BE(record, 0)
        if (counter !in PLAUSIBLE_COUNTERS) return null
        return Instant.ofEpochSecond(counter + SYNC_EPOCH)
    }

    private fun uint32BE(bytes: ByteArray, offset: Int): Long =
        (bytes.u8(offset).toLong() shl 24) or (bytes.u8(offset + 1).toLong() shl 16) or
            (bytes.u8(offset + 2).toLong() shl 8) or bytes.u8(offset + 3).toLong()

    private fun ByteArray.hexString(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
