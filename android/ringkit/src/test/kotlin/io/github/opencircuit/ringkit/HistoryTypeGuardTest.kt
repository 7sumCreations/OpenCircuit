package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only checks that the history-decode port keeps the guarantees Swift's value types gave
 * for free. Swift `struct`s and `Data` copy on assignment and `UInt8` / `UInt32` cannot go out of
 * range; the Kotlin stand-ins (`ByteArray`, classes, `Int` / `Long`) need these spelled out.
 * Kept out of the upstream-port test classes so their counts stay exact.
 */
class HistoryTypeGuardTest {

    private val t = Instant.ofEpochSecond(1_700_000_000L)

    // EpochRecord / EpochSyncSession

    @Test
    fun epochRecordsHoldAPrivateCopyOfTheirPayloadAndCompareByContent() {
        val src = bytes(1, 2, 0xB0)
        val a = EpochRecord.ActivityRecord(timestamp = t, subtype = 0x13, rawPayload = src)
        val p = EpochRecord.PPGRecord(timestamp = t, rawPayload = src)
        val hashBefore = a.hashCode()

        src[0] = 0x55 // the caller keeps writing to its own array
        a.rawPayload[1] = 0x09 // a reader writes to the array it was handed
        p.rawPayload[1] = 0x09

        assertContentEquals(bytes(1, 2, 0xB0), a.rawPayload)
        assertContentEquals(bytes(1, 2, 0xB0), p.rawPayload)
        assertEquals(hashBefore, a.hashCode())
        assertEquals(EpochRecord.ActivityRecord(t, 0x13, bytes(1, 2, 0xB0)), a)
        assertEquals(EpochRecord.PPGRecord(t, bytes(1, 2, 0xB0)), p)
        assertTrue(EpochRecord.ActivityRecord(t, 0x12, bytes(1, 2, 0xB0)) != a, "subtype is part of equality")
    }

    @Test
    fun epochRecordFieldsStayInsideTheirUnsignedRanges() {
        assertFailsWith<IllegalArgumentException> { EpochRecord.ActivityRecord(t, 0x100, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { EpochRecord.ActivityRecord(t, -1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { EpochRecord.EndOfHistoryFrame(0x100, 0L, 0L) }
        assertFailsWith<IllegalArgumentException> { EpochRecord.EndOfHistoryFrame(0x12, -1L, 0L) }
        assertFailsWith<IllegalArgumentException> { EpochRecord.EndOfHistoryFrame(0x12, 0L, 0x1_0000_0000L) }
        assertFailsWith<IllegalArgumentException> { EpochSyncSession(syncOpenCursor = -1L) }
        assertFailsWith<IllegalArgumentException> { EpochSyncSession(syncOpenCursor = 0x1_0000_0000L) }
        // Both ends are accepted, and the top byte of a full-range cursor reads unsigned.
        assertEquals(0xFF, EpochRecord.EndOfHistoryFrame(0xFF, 0L, 0xFFFF_FFFFL).streamHighByte)
    }

    @Test
    fun sessionTakesItsHighByteFromTheSyncOpenCursorExceptTheAllOnesSentinel() {
        assertEquals(0xF1, EpochSyncSession(syncOpenCursor = 0xF122_3344L).streamHighByte)
        assertEquals(0, EpochSyncSession(syncOpenCursor = 0xFFFF_FFFFL).streamHighByte)
        assertEquals(0, EpochSyncSession().streamHighByte)
    }

    @Test
    fun endOfHistoryRejectsEveryUndocumentedLength() {
        assertNull(EpochRecord.parseEndOfHistory(hex("5000001200000000000000"))) // 11 bytes
        assertNull(EpochRecord.parseEndOfHistory(hex("50000012000000"))) // 7 bytes
        assertNull(EpochRecord.parseEndOfHistory(hex("500000140c223344ff"))) // 9 bytes, byte 3 not 0x15
        assertNull(EpochRecord.parseEndOfHistory(hex("510000120c2233440c223344"))) // wrong opcode
        assertEquals(0x0c223344L, EpochRecord.parseEndOfHistory(hex("5000001512" + "0c223344"))?.cursorTo)
        assertEquals(0xfc223344L, EpochRecord.parseEndOfHistory(hex("50000012fc223344"))?.cursorFrom)
    }

    @Test
    fun aCopiedSessionIsIndependentOfTheOriginal() {
        val original = EpochSyncSession()
        val copy = original.copy()
        original.complete(hex("500000120c2233440c223344"))

        assertTrue(original.isComplete)
        assertEquals(false, copy.isComplete, "a copy must not see the original's later mutations")
        assertEquals(0, copy.streamHighByte)
        assertTrue(copy != original)
    }
}
