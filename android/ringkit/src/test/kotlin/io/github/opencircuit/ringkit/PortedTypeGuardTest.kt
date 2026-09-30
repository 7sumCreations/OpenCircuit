package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Kotlin-only checks that the port keeps guarantees Swift's value types gave for free
 * (PORTING.md D-13). A Swift `UInt8` / `UInt32` cannot hold an out-of-range value and a Swift
 * `[UInt8]` copies on assignment; the Kotlin `Int` / `Long` / `ByteArray` stand-ins need the
 * checks spelled out. Every value upstream can produce is still accepted unchanged.
 */
class PortedTypeGuardTest {

    @Test
    fun parsedBodyIsNotAliasedByTheCallerOrAReader() {
        val src = bytes(0x00, 0xB0)
        val p = Frame.Parsed(opcode = 0x81, body = src, trailer = 0x31)
        val hashBefore = p.hashCode()

        src[0] = 0x55 // the caller keeps writing to its own array
        p.body[1] = 0x09 // a reader writes to the array it was handed

        assertContentEquals(bytes(0x00, 0xB0), p.body, "Parsed.body must be a private copy")
        assertEquals(hashBefore, p.hashCode(), "hashCode must not move after construction")
        assertEquals(Frame.Parsed(0x81, bytes(0x00, 0xB0), 0x31), p)
    }

    @Test
    fun parsedRejectsAnOpcodeOrTrailerOutsideAByte() {
        assertFailsWith<IllegalArgumentException> { Frame.Parsed(0x100, ByteArray(0), 0x00) }
        assertFailsWith<IllegalArgumentException> { Frame.Parsed(-1, ByteArray(0), 0x00) }
        assertFailsWith<IllegalArgumentException> { Frame.Parsed(0x81, ByteArray(0), 0x100) }
        assertFailsWith<IllegalArgumentException> { Frame.Parsed(0x81, ByteArray(0), -1) }
        // Both byte ends are fine.
        Frame.Parsed(0x00, ByteArray(0), 0xFF)
        Frame.Parsed(0xFF, ByteArray(0), 0x00)
    }

    @Test
    fun sportSampleCursorStaysInsideUnsigned32Bits() {
        assertFailsWith<IllegalArgumentException> { SportFrame.Sample(hr = null, steps = 0, cursor = -1L) }
        assertFailsWith<IllegalArgumentException> { SportFrame.Sample(hr = null, steps = 0, cursor = 0x1_0000_0000L) }
        assertEquals(0L, SportFrame.Sample(hr = null, steps = 0, cursor = 0L).cursor)
        assertEquals(0xFFFF_FFFFL, SportFrame.Sample(hr = null, steps = 0, cursor = 0xFFFF_FFFFL).cursor)
    }

    @Test
    fun sportStartTakesATypedWorkoutWithTheSameBytes() {
        // Fixture written out on the raw byte path (upstream SportFrameTests: yoga `06 03 07 04 00`).
        assertContentEquals(bytes(0x06, 0x03, 0x07, 0x04, 0x00), Command.sportStart(SportType.YOGA))
        for (t in SportType.entries) {
            assertContentEquals(bytes(0x06, 0x03, t.rawValue, 0x04, 0x00), Command.sportStart(t), t.name)
        }
    }
}
