package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.CapturedFrame
import io.github.opencircuit.ringkit.HistoryFrameCapture
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The frame capture's stored form beyond upstream's round trip.
 *
 * Kotlin-only. Upstream's synthesized decoder does not cross-check a frame's `hex` against its
 * `opcode` / `byteCount` (an inconsistent frame decodes) and bypasses `init(frames:)`, so a stored
 * capture of 1 600 frames decodes with all 1 600 — both measured on Swift 6.3.2. Here the bytes
 * come from `hex`, a frame whose `opcode` or `byteCount` disagrees with them (or whose `hex` is not
 * the lowercase, space-separated form the capture writes) makes the capture unreadable, and the
 * capture's own constructor keeps the newest 1 500.
 */
class HistoryFrameCaptureCodecTest {

    private fun frame(hex: String, opcode: Int, byteCount: Int, date: Long = 5): String =
        """{"frames":[{"byteCount":$byteCount,"date":$date,"hex":"$hex","opcode":$opcode}]}"""

    @Test
    fun theStoredFormIsTheFramesHexOpcodeLengthAndMillisecondDate() {
        val cap = HistoryFrameCapture(listOf(CapturedFrame(Instant.ofEpochMilli(5), byteArrayOf(0x4c, 0x00, 0xde.toByte(), 0xad.toByte()))))
        assertEquals(frame("4c 00 de ad", 76, 4), HistoryFrameCaptureCodec.encode(cap))
        assertEquals("""{"frames":[]}""", HistoryFrameCaptureCodec.encode(HistoryFrameCapture()))
    }

    @Test
    fun anEmptyFrameRoundTrips() {
        val cap = HistoryFrameCapture(listOf(CapturedFrame(Instant.ofEpochMilli(5), ByteArray(0))))
        assertEquals(cap, readable(HistoryFrameCaptureCodec.decode(HistoryFrameCaptureCodec.encode(cap))))
        assertEquals(cap, readable(HistoryFrameCaptureCodec.decode(frame("", 0, 0))))
    }

    @Test
    fun aFrameWhoseOpcodeOrLengthDisagreesWithItsBytesMakesTheCaptureUnreadable() {
        for (raw in listOf(frame("4c 00 de ad", 0x47, 4), frame("4c 00 de ad", 76, 3), frame("", 76, 0), frame("4c", 76, 2))) {
            assertUnreadable(HistoryFrameCaptureCodec.decode(raw), raw)
        }
    }

    @Test
    fun hexNotInTheFormTheCaptureWritesMakesTheCaptureUnreadable() {
        for (hex in listOf("4C 00", "4c  00", "4c 00 ", " 4c 00", "4c00", "4c 0", "zz 00", "4c-00", "4c\\t00", "\\uff14c 00")) {
            val raw = frame(hex, 76, 2)
            assertUnreadable(HistoryFrameCaptureCodec.decode(raw), raw, hex)
        }
    }

    @Test
    fun aMissingKeyOrAWrongTypeMakesTheCaptureUnreadable() {
        for (raw in listOf(
            "{}",
            """{"frames":[{"date":5,"hex":"4c","opcode":76}]}""",
            """{"frames":[{"byteCount":1,"hex":"4c","opcode":76}]}""",
            """{"frames":[{"byteCount":1,"date":5,"opcode":76}]}""",
            """{"frames":[{"byteCount":1,"date":5,"hex":"4c"}]}""",
            """{"frames":{}}""",
            frame("4c", 76, 1).replace("\"byteCount\":1", "\"byteCount\":4294967297"),
        )) {
            assertUnreadable(HistoryFrameCaptureCodec.decode(raw), raw)
        }
    }

    @Test
    fun aStoredCaptureOverTheCapKeepsTheNewest1500() {
        val frames = (0 until 1_600).joinToString(",") { i ->
            val b = String.format(Locale.ROOT, "%02x", i and 0xff)
            """{"byteCount":2,"date":$i,"hex":"4c $b","opcode":76}"""
        }
        val cap = readable(HistoryFrameCaptureCodec.decode("""{"frames":[$frames]}"""))
        assertEquals(HistoryFrameCapture.CAP, cap.count)
        assertEquals(Instant.ofEpochMilli(100), cap.frames.first().date)
        assertEquals(Instant.ofEpochMilli(1_599), cap.frames.last().date)
    }
}
