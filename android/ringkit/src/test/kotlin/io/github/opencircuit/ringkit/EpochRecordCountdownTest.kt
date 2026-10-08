package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `0x47` / `0x4c` page header counts the records still queued on the ring in bytes 1–2, big
 * endian (PROTOCOL.md §5.2 / §5.3; a third-party hardware capture read `4c 02 cd`, 717 queued, after
 * about 30 hours offline). Upstream reads byte 2 alone and refuses every page whose byte 1 is not
 * `00` (`EpochRecord.swift:95`, `:134`), so any backlog of more than 255 records decoded to nothing.
 * See PORTING.md D-259.
 *
 * Every page here is assembled on the raw byte path: header bytes typed out, the six records of a
 * real FR02.018 sleep page, and an XOR trailer computed in this file (never `Frame.xorTrailer`).
 */
class EpochRecordCountdownTest {

    /** The six 23-byte records of a real `0x4c` page (header `4c 00 26` and trailer `cc` cut off). */
    private val realRecords = hex(
        "0c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
            "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
            "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
            "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0",
    )

    private fun testXor(b: ByteArray): Int = b.fold(0) { acc, x -> acc xor (x.toInt() and 0xFF) }

    private fun page(opcode: Int, countHigh: Int, countLow: Int, records: ByteArray): ByteArray {
        val withoutTrailer = bytes(opcode, countHigh, countLow) + records
        return withoutTrailer + bytes(testXor(withoutTrailer))
    }

    @Test
    fun theRealPageReadsItsOwnCountdown() {
        val original = page(0x4C, 0x00, 0x26, realRecords)
        assertEquals(0xCC, original.last().toInt() and 0xFF, "the test XOR rebuilds the captured trailer")
        assertEquals(38, EpochRecord.remainingRecordCountdown(original))
    }

    @Test
    fun aPageWith717RecordsQueuedReadsTheWholeSixteenBitCountdown() {
        val queued717 = page(0x4C, 0x02, 0xCD, realRecords)

        assertEquals(717, EpochRecord.remainingRecordCountdown(queued717))
    }

    @Test
    fun aPageWhoseHighCountdownByteIsSetStillDecodesItsRecords() {
        val low = EpochRecord.parseActivityPage(page(0x4C, 0x00, 0x26, realRecords))
        val queued717 = EpochRecord.parseActivityPage(page(0x4C, 0x02, 0xCD, realRecords))
        val queued256 = EpochRecord.parseActivityPage(page(0x4C, 0x01, 0x00, realRecords))

        assertEquals(6, low.size)
        assertEquals(low, queued717, "the countdown is header, not payload: the same six records")
        assertEquals(low, queued256)
    }

    @Test
    fun aPpgPageWhoseHighCountdownByteIsSetStillDecodesItsRecords() {
        val record = bytes(0x0C, 0x22, 0xA1, 0x6B) + ByteArray(43) { (it + 1).toByte() }
        val pageWithHighByte = page(0x47, 0x01, 0x2C, record)

        val records = EpochRecord.parsePPGPage(pageWithHighByte)

        assertEquals(1, records.size)
        assertContentEquals(ByteArray(38) { (it + 6).toByte() }, records.single().rawPayload)
        assertEquals(300, EpochRecord.remainingRecordCountdown(pageWithHighByte))
    }

    @Test
    fun theCountdownCoversBothBytesAcrossTheirWholeRange() {
        val cases = listOf(
            Triple(0x00, 0x00, 0),
            Triple(0x00, 0x01, 1),
            Triple(0x00, 0xFF, 255),
            Triple(0x01, 0x00, 256),
            Triple(0x02, 0xCD, 717),
            Triple(0xFF, 0xFF, 65535),
        )
        for ((high, low, expected) in cases) {
            assertEquals(expected, EpochRecord.remainingRecordCountdown(page(0x4C, high, low, realRecords)), "4c $high $low")
            assertEquals(expected, EpochRecord.remainingRecordCountdown(page(0x47, high, low, ByteArray(0))), "47 $high $low")
        }
    }

    @Test
    fun framesThatAreNotHistoryPagesOrAreTooShortHaveNoCountdown() {
        assertNull(EpochRecord.remainingRecordCountdown(bytes(0x4C, 0x02)))
        assertNull(EpochRecord.remainingRecordCountdown(ByteArray(0)))
        assertNull(EpochRecord.remainingRecordCountdown(hex("500000120c22aae40c22acb5")))
        assertNull(EpochRecord.remainingRecordCountdown(page(0x4D, 0x02, 0xCD, ByteArray(0))))
    }
}
