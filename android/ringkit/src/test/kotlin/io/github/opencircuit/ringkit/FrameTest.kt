package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/FrameTests.swift (@ b1c2fdd).
 *
 * Fixtures are REAL frames from the FR02.018 reference capture, typed as hex literals —
 * never produced by `Frame`/`Command`. Several carry bytes ≥ 0x80
 * (81, b0, 87, 9e, fd, ff), which is what catches a signed-`Byte` slip.
 *
 * `testLiveHRDecode` (:112-116) → `liveHrDecode`, added with `LiveHR` in E1-S4-T6.
 * `testLiveHRStartSequenceShape` (:68-73) is replaced by an absence check: legacy auth,
 * including `Command.liveHRStart`, is not ported (E1 1.3 Option 1, PORTING.md).
 */
class FrameTest {
    // FrameTests.swift:19-23 — real validated notify frames from the capture.
    private val realFrames = listOf(
        "8100b031", "82000082", "860086", "1500080ab0a7",
        "874e0400000000fd00fd00000000100c0b9e44",
        "104e0100000000fd00fd00000000100c0bffb7",
    )

    // FrameTests.swift:25-28
    @Test
    fun xorTrailerValidatesResponses() {
        // XOR trailer applies to RESPONSES: 81^00^b0 = 31.
        assertEquals(0x31, Frame.xorTrailer(bytes(0x81, 0x00, 0xB0)))
    }

    // FrameTests.swift:30-35
    @Test
    fun commandsAreVerbatimNotChecksummed() {
        // The real poll is 95 00 00 (NOT the GB-guessed XOR'd 95 00 95).
        assertContentEquals(bytes(0x95, 0x00, 0x00), Command.poll)
        assertNotEquals(Frame.xorTrailer(bytes(0x95, 0x00)), Command.poll.last().toInt() and 0xFF)
        assertContentEquals(bytes(0x02, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x01, 0x00), Command.syncAll)
    }

    // FrameTests.swift:37-41
    @Test
    fun realFramesValidate() {
        for (f in realFrames) {
            assertTrue(Frame.isValid(hex(f)), "should validate: $f")
        }
    }

    // FrameTests.swift:43-49
    @Test
    fun corruptedFrameRejected() {
        val bad = hex("8100b031")
        bad[1] = (bad[1].toInt() xor 0xFF).toByte()
        assertFalse(Frame.isValid(bad))
        assertNull(Frame.parse(bad))
        assertFalse(Frame.isValid(ByteArray(0)), "empty frame")
        assertFalse(Frame.isValid(bytes(0x81)), "1-byte frame has no body+trailer")
    }

    // FrameTests.swift:51-61
    @Test
    fun responseIdRuleIsCommandXor0x80AndInvolutive() {
        val pairs = listOf(
            0x01 to 0x81, 0x02 to 0x82, 0x06 to 0x86, 0x07 to 0x87,
            0x95 to 0x15, 0xC7 to 0x47, 0xCC to 0x4C, 0xD0 to 0x50,
        )
        for ((cmd, resp) in pairs) {
            assertEquals(resp, Frame.responseId(cmd), "responseId(0x%02x)".format(cmd))
            assertEquals(cmd, Frame.responseId(resp), "involutive: responseId(0x%02x)".format(resp))
        }
    }

    // FrameTests.swift:63-66
    @Test
    fun parseSplitsOpcodeBodyTrailer() {
        val p = Frame.parse(hex("8100b031"))
        assertEquals(Frame.Parsed(opcode = 0x81, body = bytes(0x00, 0xB0), trailer = 0x31), p)
    }

    // Replaces FrameTests.swift:68-73 (testLiveHRStartSequenceShape) — the API is dropped.
    @Test
    fun liveHRStartIsNotInTheProductionApi() {
        val c = Command::class.java
        val members = c.declaredMethods.map { it.name } + c.declaredFields.map { it.name }
        assertTrue(
            members.none { it == "getLiveHRStart" || it == "liveHRStart" },
            "Command must not expose liveHRStart (legacy auth dropped); members: $members",
        )
    }

    // FrameTests.swift:75-81
    @Test
    fun syncCursorBuilder() {
        // cursor = unix − 1577793600, big-endian; matches a captured cursor.
        assertContentEquals(
            bytes(0x02, 0x00, 0x0c, 0x22, 0x98, 0xc3, 0x00, 0x01, 0x00),
            Command.syncSince(unixSeconds = Command.SYNC_EPOCH + 0x0c2298c3),
        )
        // floors negative cursors to 0.
        assertContentEquals(bytes(0, 0, 0, 0), Command.syncSince(unixSeconds = 0).copyOfRange(2, 6))
    }

    // FrameTests.swift:83-93
    @Test
    fun syncUpToNowOpensAtNowNotSyncAll() {
        val now = Instant.ofEpochSecond(1_750_000_000)
        val open = Command.syncUpToNow(now = now)
        assertContentEquals(Command.syncSince(unixSeconds = 1_750_000_000), open)
        assertFalse(open.contentEquals(Command.syncAll), "history open must not be syncAll")
        assertFalse(
            open.copyOfRange(2, 6).contentEquals(bytes(0xFF, 0xFF, 0xFF, 0xFF)),
            "cursor must be ≈now, not 0xFFFFFFFF",
        )
        assertContentEquals(bytes(0x02, 0x00), open.copyOfRange(0, 2))
        assertContentEquals(bytes(0x00, 0x01, 0x00), open.copyOfRange(6, 9))
    }

    // FrameTests.swift:95-110
    @Test
    fun syncOpenChannelSelectorLandsAtByte6() {
        assertEquals(0x00, Command.SYNC_CHANNEL_SLEEP)
        assertEquals(0x03, Command.SYNC_CHANNEL_ALL_DAY)
        val sleep = Command.syncSince(unixSeconds = Command.SYNC_EPOCH + 0x0c2298c3)
        assertEquals(0x00, sleep[6].toInt() and 0xFF, "default channel is the sleep log")
        val allDay = Command.syncSince(
            unixSeconds = Command.SYNC_EPOCH + 0x0c2298c3,
            channel = Command.SYNC_CHANNEL_ALL_DAY,
        )
        assertContentEquals(bytes(0x02, 0x00, 0x0c, 0x22, 0x98, 0xc3, 0x03, 0x01, 0x00), allDay)
        // Only byte[6] differs between the two channel opens at the same cursor.
        assertContentEquals(sleep.copyOfRange(0, 6), allDay.copyOfRange(0, 6))
        assertContentEquals(sleep.copyOfRange(7, 9), allDay.copyOfRange(7, 9))
        val nowAllDay = Command.syncUpToNow(
            now = Instant.ofEpochSecond(1_750_000_000),
            channel = Command.SYNC_CHANNEL_ALL_DAY,
        )
        assertEquals(0x03, nowAllDay[6].toInt() and 0xFF)
    }

    // FrameTests.swift:112-116
    @Test
    fun liveHrDecode() {
        // Real 0x15 frame from the capture: byte[2] = 0x5B = 91 bpm.
        assertEquals(91, LiveHR.decode(hex("15005b0ab0f4")))
        assertNull(LiveHR.decode(ByteArray(0)))
    }
}
