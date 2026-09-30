package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Kotlin-only edge cases for the live decoders (`LiveHR`, `SportFrame`) that upstream's vectors
 * never hit: the edges of every plausibility band, every minimum-length guard, and bytes ≥ 0x80 in
 * the positions a signed read or a signed shift would corrupt (A3, A5).
 *
 * Kept out of `LiveHRSettlingTest` / `SportFrameTest` so those keep the test plan's pinned upstream
 * counts (5 / 6), the same split as `DeviceStatusBandEdgeTest`.
 *
 * Fixtures are built on the RAW byte path: every XOR trailer is written out by
 * hand as `0x4e xor …`, the way upstream writes its warm-up fixture — never via `Frame.xorTrailer`.
 */
class LiveDecodeBandEdgeTest {

    /** A short HR-mode frame `15 00 <hr> 0a b0 <any>` — `decode` does not check the trailer. */
    private fun hrFrame(hr: Int) = bytes(0x15, 0x00, hr, 0x0a, 0xb0, 0x00)

    /** A 15-byte SpO2-mode frame `15 01 … <spo2>` with the value at byte 14. */
    private fun spo2Frame(spo2: Int, size: Int = 15, mode: Int = 0x01): ByteArray {
        val f = ByteArray(size)
        f[0] = 0x15
        f[1] = mode.toByte()
        if (size > 14) f[14] = spo2.toByte()
        return f
    }

    /** A minimal 8-byte sport frame `4e 00 00 00 00 <hr> <steps> <xor>` with a hand-written trailer. */
    private fun sportFrame(hr: Int, steps: Int, xor: Int) = bytes(0x4e, 0x00, 0x00, 0x00, 0x00, hr, steps, xor)

    @Test
    fun decodeReadsTheHrByteUnsignedAndNeedsFourBytes() {
        assertEquals(255, LiveHR.decode(hrFrame(0xff)), "0xff must read 255, not -1 (A5)")
        assertEquals(0x5b, LiveHR.decode(bytes(0x15, 0x00, 0x5b, 0x0a)), "exactly 4 bytes is enough")
        assertNull(LiveHR.decode(bytes(0x15, 0x00, 0x5b)), "3 bytes is too short")
        assertNull(LiveHR.decode(bytes(0x16, 0x00, 0x5b, 0x0a)), "wrong opcode")
        assertNull(LiveHR.decode(spo2Frame(96)), "a long 15 01 SpO2 frame carries no HR")
    }

    @Test
    fun decodeLockedKeepsOnlyTheThirtyToTwoTwentyBand() {
        assertEquals(30, LiveHR.MIN_VALID_BPM)
        assertEquals(220, LiveHR.MAX_VALID_BPM)
        assertEquals(30..220, LiveHR.VALID_BPM)
        assertNull(LiveHR.decodeLocked(hrFrame(8)), "warm-up sentinel ≈ 8")
        assertNull(LiveHR.decodeLocked(hrFrame(29)))
        assertEquals(30, LiveHR.decodeLocked(hrFrame(30)))
        assertEquals(220, LiveHR.decodeLocked(hrFrame(220)))
        assertNull(LiveHR.decodeLocked(hrFrame(221)))
        assertNull(LiveHR.decodeLocked(hrFrame(0xff)), "0xff is 255 — over the ceiling, not a negative")
        assertNull(LiveHR.decodeLocked(ByteArray(0)))
    }

    @Test
    fun decodeSpO2KeepsOnlySeventyToHundredAtByte14() {
        assertNull(LiveHR.decodeSpO2(spo2Frame(69)))
        assertEquals(70, LiveHR.decodeSpO2(spo2Frame(70)))
        assertEquals(100, LiveHR.decodeSpO2(spo2Frame(100)))
        assertNull(LiveHR.decodeSpO2(spo2Frame(101)))
        assertNull(LiveHR.decodeSpO2(spo2Frame(0xe0)), "0xe0 is 224, out of band")
        assertNull(LiveHR.decodeSpO2(spo2Frame(96, size = 14)), "14 bytes is too short")
        assertNull(LiveHR.decodeSpO2(spo2Frame(96, mode = 0x00)), "HR-mode frame carries no SpO2")
        assertEquals(96, LiveHR.decodeSpO2(spo2Frame(96, size = 20)), "longer frames still read byte 14")
    }

    @Test
    fun sportFrameNeedsEightBytes() {
        // 7 bytes, trailer valid (4e ^ 4b = 05): the size guard alone must reject it.
        assertNull(SportFrame.decode(bytes(0x4e, 0x00, 0x00, 0x00, 0x00, 0x4b, 0x4e xor 0x4b)))
        assertEquals(
            SportFrame.Sample(hr = 75, steps = 2, cursor = 0L),
            SportFrame.decode(sportFrame(0x4b, 0x02, 0x4e xor 0x4b xor 0x02)),
        )
        assertNull(SportFrame.decode(ByteArray(0)))
    }

    @Test
    fun sportCursorIsAnUnsignedThirtyTwoBitValue() {
        // ff ff ff ff: a signed or Int-width shift gives -1; the cursor must be 4294967295.
        val top = bytes(0x4e, 0xff, 0xff, 0xff, 0xff, 0x4b, 0x00, 0x4e xor 0x4b)
        assertEquals(0xFFFF_FFFFL, SportFrame.decode(top)?.cursor)
        // 80 00 00 01: only the top bit of the first cursor byte set.
        val high = bytes(0x4e, 0x80, 0x00, 0x00, 0x01, 0x4b, 0x00, 0x4e xor 0x80 xor 0x01 xor 0x4b)
        assertEquals(0x8000_0001L, SportFrame.decode(high)?.cursor)
    }

    @Test
    fun sportHrBandEdgesAndUnsignedSteps() {
        // Whole-Sample equality, so an out-of-band HR can't pass by the frame decoding to null.
        assertEquals(SportFrame.Sample(null, 0, 0L), SportFrame.decode(sportFrame(29, 0, 0x4e xor 29)))
        assertEquals(SportFrame.Sample(30, 0, 0L), SportFrame.decode(sportFrame(30, 0, 0x4e xor 30)))
        assertEquals(SportFrame.Sample(220, 0, 0L), SportFrame.decode(sportFrame(220, 0, 0x4e xor 220)))
        assertEquals(SportFrame.Sample(null, 0, 0L), SportFrame.decode(sportFrame(221, 0, 0x4e xor 221)))
        assertEquals(255, SportFrame.decode(sportFrame(0x4b, 0xff, 0x4e xor 0x4b xor 0xff))?.steps, "0xff steps is 255")
    }

    @Test
    fun sportTypeDisplayNamesAndOrder() {
        assertEquals(
            listOf(
                0x01 to "Outdoor Running", 0x02 to "Outdoor Walking", 0x03 to "Indoor Running",
                0x04 to "Outdoor Cycling", 0x05 to "Indoor Cycling", 0x06 to "Indoor Rowing",
                0x07 to "Yoga",
            ),
            SportType.entries.map { it.rawValue to it.displayName },
        )
    }
}
