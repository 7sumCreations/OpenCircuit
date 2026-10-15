package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A history record's first four bytes are its own big-endian counter, seconds since 2019-12-31
 * 12:00:00 UTC (PROTOCOL.md §5.3: the app's `utc` field is four bytes; `BulkRecord.counter` reads
 * the same `[0:4]`). Upstream treats byte 0 as a fixed `0x0c` delimiter and dates records from a
 * high byte the caller supplies (`EpochRecord.swift:67`, `:83`, `:138-143`). Byte 0 is `0x0c` only
 * until the counter reaches `0x0d000000` at 2026-11-28 20:23:28 UTC; from then on every record would
 * be skipped. A record is now kept when its counter is a plausible date (2022-01-01 up to, not
 * including, 2100-01-01) and dated by that counter. See PORTING.md D-260.
 *
 * Instants are typed literals, never computed from the code under test. Pages are assembled on
 * the raw byte path with an XOR trailer computed here (never `Frame.xorTrailer`).
 */
class EpochRecordRolloverTest {

    private fun testXor(b: ByteArray): Int = b.fold(0) { acc, x -> acc xor (x.toInt() and 0xFF) }

    private fun page(opcode: Int, vararg records: ByteArray): ByteArray {
        val withoutTrailer = bytes(opcode, 0x00, 0x00) + records.fold(ByteArray(0)) { acc, r -> acc + r }
        return withoutTrailer + bytes(testXor(withoutTrailer))
    }

    /** A 23-byte activity record: four counter bytes, an idle body, subtype at byte 8. */
    private fun activity(c0: Int, c1: Int, c2: Int, c3: Int, subtype: Int = 0x12): ByteArray =
        bytes(c0, c1, c2, c3, 0x05, 0x00, 0x0c, 0x00, subtype, 0x0a, 0x01, 0x01, 0x01, 0x01, 0x01, 0, 0, 0, 0, 0, 0, 0, 0)

    /** A 47-byte PPG record: four counter bytes, then 43 bytes of 0x01. */
    private fun ppg(c0: Int, c1: Int, c2: Int, c3: Int): ByteArray = bytes(c0, c1, c2, c3) + ByteArray(43) { 0x01 }

    @Test
    fun aRecordAtTheCounterRolloverIsKeptAndDatedByItsOwnCounter() {
        val records = EpochRecord.parseActivityPage(page(0x4C, activity(0x0D, 0x00, 0x00, 0x00)))

        assertEquals(1, records.size, "a 0x0d record is a record, not a missing delimiter")
        assertEquals(Instant.parse("2026-11-28T20:23:28Z"), records.single().timestamp)
    }

    @Test
    fun aPageThatSpansTheRolloverKeepsBothRecordsOneEpochApart() {
        val before = activity(0x0C, 0xFF, 0xFF, 0x6A) // 150 s before the rollover
        val after = activity(0x0D, 0x00, 0x00, 0x00)

        val records = EpochRecord.parseActivityPage(page(0x4C, before, after))

        assertEquals(
            listOf(Instant.parse("2026-11-28T20:20:58Z"), Instant.parse("2026-11-28T20:23:28Z")),
            records.map { it.timestamp },
        )
    }

    @Test
    fun aPpgRecordAfterTheRolloverIsKept() {
        val records = EpochRecord.parsePPGPage(page(0x47, ppg(0x0D, 0x00, 0x03, 0x84)))

        assertEquals(listOf(Instant.parse("2026-11-28T20:38:28Z")), records.map { it.timestamp })
    }

    @Test
    fun aSessionKeepsEveryRecordAtItsOwnDateWhenTheEndFrameCarriesTheNewHighByte() {
        val session = EpochSyncSession()
        session.appendActivityPage(page(0x4C, activity(0x0C, 0xFF, 0xFF, 0x6A), activity(0x0D, 0x00, 0x00, 0x00)))

        session.complete(hex("500000120cffff6a0d000096"))

        assertEquals(
            listOf(Instant.parse("2026-11-28T20:20:58Z"), Instant.parse("2026-11-28T20:23:28Z")),
            session.activityRecords.map { it.timestamp },
            "the record before the rollover is not moved 194 days ahead by the end frame's high byte",
        )
    }

    @Test
    fun aRecordIsDatedByItsCounterBeforeAnyEndFrameArrives() {
        val session = EpochSyncSession()

        val records = session.appendActivityPage(page(0x4C, activity(0x0C, 0x22, 0xA1, 0x6B)))

        assertEquals(listOf(Instant.parse("2026-06-13T22:28:59Z")), records.map { it.timestamp })
    }

    @Test
    fun theFirstAndLastPlausibleCountersAreKept() {
        val first = activity(0x03, 0xC4, 0x61, 0x40) // 2022-01-01T00:00:00Z
        val last = activity(0x96, 0x7B, 0x1E, 0xBF) // 2099-12-31T23:59:59Z

        val records = EpochRecord.parseActivityPage(page(0x4C, first, last))

        assertEquals(
            listOf(Instant.parse("2022-01-01T00:00:00Z"), Instant.parse("2099-12-31T23:59:59Z")),
            records.map { it.timestamp },
        )
    }

    @Test
    fun implausibleCountersAreStillRejected() {
        val implausible = mapOf(
            "zero filler" to activity(0x00, 0x00, 0x00, 0x00),
            "2020, an unset clock" to activity(0x01, 0x01, 0x01, 0x01),
            "one second before 2022" to activity(0x03, 0xC4, 0x61, 0x3F),
            "2100-01-01T00:00:00Z" to activity(0x96, 0x7B, 0x1E, 0xC0),
            "all-ones filler" to activity(0xFF, 0xFF, 0xFF, 0xFF),
        )
        for ((name, record) in implausible) {
            assertEquals(emptyList(), EpochRecord.parseActivityPage(page(0x4C, record)), name)
        }
        assertEquals(emptyList(), EpochRecord.parsePPGPage(page(0x47, ppg(0xFF, 0xFF, 0xFF, 0xFF))))
        assertEquals(emptyList(), EpochRecord.parsePPGPage(page(0x47, ppg(0x00, 0x00, 0x00, 0x00))))
    }

    @Test
    fun anImplausibleRecordIsDroppedWithoutLosingItsNeighbours() {
        val good = activity(0x0C, 0x22, 0xA1, 0x6B)
        val filler = activity(0xFF, 0xFF, 0xFF, 0xFF)
        val next = activity(0x0C, 0x22, 0xA2, 0x01)

        val records = EpochRecord.parseActivityPage(page(0x4C, good, filler, next))

        assertEquals(
            listOf(Instant.parse("2026-06-13T22:28:59Z"), Instant.parse("2026-06-13T22:31:29Z")),
            records.map { it.timestamp },
        )
    }
}
