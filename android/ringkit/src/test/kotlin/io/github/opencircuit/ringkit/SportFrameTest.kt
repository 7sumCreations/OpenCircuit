package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Ground-truth checks for the native sport-mode decode (upstream #90), using a REAL captured
 * `0x4e` frame.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SportFrameTests.swift:9-68
 * (@ b1c2fdd) — all 6 tests.
 *
 * Fixtures are built on the RAW byte path: every XOR trailer is the literal
 * upstream writes (`0x71`, `0x05`, `0x4e xor 0x08 xor 0x03`), never computed with
 * `Frame.xorTrailer`. The real yoga frame carries `0xf2`/`0xce` — a signed read corrupts both,
 * and a signed shift of the cursor bytes sign-extends into a negative cursor.
 */
class SportFrameTest {

    /** Real yoga-workout frame captured 2026-07-06 (FR02.018): `4e 0c 40 78 f2 4b 00 00 ce 7c 00 00 71`. */
    private fun yogaFrame(): ByteArray =
        bytes(0x4e, 0x0c, 0x40, 0x78, 0xf2, 0x4b, 0x00, 0x00, 0xce, 0x7c, 0x00, 0x00, 0x71)

    /** HR 0x4b = 75, steps 0x00 = 0. XOR trailer 0x71 verified. */
    @Test
    fun decodesRealYogaFrame() { // :9-16
        val s = SportFrame.decode(yogaFrame())
        assertEquals(75, s?.hr)
        assertEquals(0, s?.steps)
        assertEquals(0x0c4078f2L, s?.cursor)
    }

    /** A walking interval: HR 90, 17 steps in the window (hand-built, valid XOR = 0x05). */
    @Test
    fun decodesSteps() { // :19-25
        val frame = bytes(0x4e, 0x00, 0x00, 0x00, 0x00, 0x5a, 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05)
        val s = SportFrame.decode(frame)
        assertEquals(90, s?.hr)
        assertEquals(17, s?.steps)
    }

    /** A bad XOR trailer must be rejected (never fabricate a sample from a corrupt frame). */
    @Test
    fun rejectsBadChecksum() { // :28-33
        val frame = yogaFrame()
        frame[12] = 0x00 // corrupt the trailer
        assertNull(SportFrame.decode(frame))
    }

    /**
     * Non-sport opcodes and warm-up HR: opcode gate returns null; a sub-band HR yields null HR but
     * still surfaces steps (steps aren't gated on HR).
     */
    @Test
    fun opcodeAndHrBandGates() { // :37-46
        assertNull(SportFrame.decode(bytes(0x15, 0x00, 0x4b, 0x0a, 0xb0, 0xd0))) // wrong opcode
        // HR byte 8 (warm-up sentinel, < MIN_VALID_BPM) → hr null, steps = 3.
        val warm = bytes(
            0x4e, 0x00, 0x00, 0x00, 0x00, 0x08, 0x03,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x4e xor 0x08 xor 0x03,
        )
        val s = SportFrame.decode(warm)
        assertNotNull(s)
        assertNull(s.hr)
        assertEquals(3, s.steps)
    }

    /** The sport commands must be the exact captured bytes. */
    @Test
    fun sportCommandBytes() { // :49-57
        assertContentEquals(bytes(0x06, 0x03, 0x02, 0x04, 0x00), Command.sportStart(0x02)) // outdoor walk
        assertContentEquals(bytes(0x06, 0x03, 0x07, 0x04, 0x00), Command.sportStart(0x07)) // yoga
        assertContentEquals(bytes(0x06, 0x00, 0x00), Command.sportStop)
        assertContentEquals(bytes(0xCE, 0x00, 0x00), Command.sportStreamAck)
        assertContentEquals(bytes(0x24, 0x01, 0x00), Command.findRingLight) // 🟢 device-verified: lights the ring
        assertContentEquals(bytes(0x24, 0x00, 0x00), Command.findRingLightOff) // 🟡 probable off (on/off convention)
        assertContentEquals(bytes(0x08, 0x04, 0x00), Command.airplaneModeOn)
    }

    /** SportType byte mapping matches the captured enum (0x01..0x07). */
    @Test
    fun sportTypeBytes() { // :60-68
        assertEquals(0x01, SportType.OUTDOOR_RUNNING.rawValue)
        assertEquals(0x02, SportType.OUTDOOR_WALKING.rawValue)
        assertEquals(0x03, SportType.INDOOR_RUNNING.rawValue)
        assertEquals(0x04, SportType.OUTDOOR_CYCLING.rawValue)
        assertEquals(0x05, SportType.INDOOR_CYCLING.rawValue)
        assertEquals(0x06, SportType.INDOOR_ROWING.rawValue)
        assertEquals(0x07, SportType.YOGA.rawValue)
    }
}
