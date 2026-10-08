package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Epoch-page decode (`0x4c` activity, `0x47` PPG) and the `0x50` end-of-history cursor report,
 * plus the session that reparses buffered pages once the end frame reveals the stream high byte.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/EpochSyncTests.swift (@ b1c2fdd)
 * — all 5 tests. Frames are built on the raw byte path; the trailer is computed by [testXor]
 * here, never by the production `Frame.xorTrailer`.
 */
class EpochSyncTest {

    /** Test-only XOR of every byte (the response trailer rule, ../docs/PROTOCOL.md §3). */
    private fun testXor(b: ByteArray): Int = b.fold(0) { acc, x -> acc xor (x.toInt() and 0xFF) }

    // :5-8
    private fun frame(opcode: Int, body: ByteArray): ByteArray {
        val withoutTrailer = bytes(opcode) + body
        return withoutTrailer + bytes(testXor(withoutTrailer))
    }

    // :10-17 — marker, 3-byte big-endian counter, then `fill` up to `size`.
    private fun record(size: Int, counter: Int, fill: Int = 0x01): ByteArray {
        val head = bytes(0x0C, (counter shr 16) and 0xFF, (counter shr 8) and 0xFF, counter and 0xFF)
        return head + ByteArray(size - head.size) { fill.toByte() }
    }

    private fun at(cursorSeconds: Long): Instant = Instant.ofEpochSecond(1_577_793_600L + cursorSeconds)

    @Test
    fun parsesActivityPageTimestampSubtypeAndRawPayload() { // :19-32
        val rec = record(size = 23, counter = 0x223344, fill = 0x01)
        rec[8] = 0x13
        bytes(1, 2, 3, 4, 5, 6, 7).copyInto(rec, destinationOffset = 15)
        val page = frame(0x4C, bytes(0x00, 0x00) + rec)

        val records = EpochRecord.parseActivityPage(page)

        assertEquals(1, records.size)
        assertEquals(0x13, records[0].subtype)
        assertContentEquals(bytes(1, 2, 3, 4, 5, 6, 7), records[0].rawPayload)
        assertEquals(at(0x0c223344L), records[0].timestamp)
    }

    @Test
    fun parsesPPGPageTimestampAndRawPayload() { // :34-46
        val rec = record(size = 47, counter = 0x000100, fill = 0x01)
        ByteArray(38) { it.toByte() }.copyInto(rec, destinationOffset = 9)
        val page = frame(0x47, bytes(0x00, 0x03) + rec)

        val records = EpochRecord.parsePPGPage(page)

        assertEquals(1, records.size)
        assertContentEquals(ByteArray(38) { it.toByte() }, records[0].rawPayload)
        // Upstream passes a high byte of 0x02 and expects 0x02000100; the record's own first byte
        // is 0x0c, and a record is dated by its own four-byte counter (PORTING.md D-260).
        assertEquals(at(0x0c000100L), records[0].timestamp)
        assertEquals(0x03, EpochRecord.remainingRecordCountdown(page))
    }

    @Test
    fun rejectsMalformedEpochPages() { // :48-54
        val wrongMarker = ByteArray(23) { 0x01 }
        val page = frame(0x4C, bytes(0x00, 0x00) + wrongMarker)

        assertEquals(emptyList(), EpochRecord.parseActivityPage(page))
        assertEquals(emptyList(), EpochRecord.parsePPGPage(bytes(0x47, 0x00, 0x00)))
    }

    @Test
    fun decodesEndOfHistoryWithoutXorTrailer() { // :56-69 — 0x50 is the documented no-XOR frame
        val end = hex("500000120c22aae40c22acb5")

        val report = EpochRecord.parseEndOfHistory(end)

        assertNotNull(report)
        assertEquals(0x12, report.subtype)
        assertEquals(0x0c22aae4L, report.cursorFrom)
        assertEquals(0x0c22acb5L, report.cursorTo)
        assertEquals(0x0c, report.streamHighByte)
    }

    @Test
    fun sessionDatesBufferedPagesByTheirOwnCounterBeforeAndAfterTheEndFrame() { // :71-97
        val rec = record(size = 23, counter = 0x223344, fill = 0x00)
        bytes(1, 0, 0, 0, 0, 0, 0).copyInto(rec, destinationOffset = 15)
        val page = frame(0x4C, bytes(0x00, 0x00) + rec)
        val end = hex("500000120c2233440c223344")

        val session = EpochSyncSession()
        // Upstream expects 0x00223344 (2020) until the end frame supplies the high byte; the record
        // already carries it (PORTING.md D-260).
        assertEquals(at(0x0c223344L), session.appendActivityPage(page).first().timestamp)
        assertNotNull(session.complete(end))
        assertEquals(0x0c, session.streamHighByte)

        assertTrue(session.isComplete)
        assertEquals(at(0x0c223344L), session.activityRecords.first().timestamp)
        assertEquals(
            listOf(QuantitySample(kind = MetricKind.HEART_RATE, start = at(0x0c223344L), value = 0.0)),
            session.placeholderQuantitySamples(),
        )
    }
}
