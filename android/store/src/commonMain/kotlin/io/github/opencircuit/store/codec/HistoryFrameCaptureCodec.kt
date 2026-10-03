package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.CapturedFrame
import io.github.opencircuit.ringkit.HistoryFrameCapture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of the diagnostic frame capture:
 * `{"frames":[{"byteCount":4,"date":<ms>,"hex":"4c 00 de ad","opcode":76}, …]}`, oldest first.
 *
 * Upstream's synthesized Codable on `HistoryFrameCapture` / `CapturedFrame`. Two differences, both
 * reachable only by a capture this build did not write: a frame's bytes are read from `hex` and
 * must be exactly the lowercase, space-separated form the capture writes, and its `opcode` and
 * `byteCount` must agree with those bytes, or the whole capture is unreadable (upstream decodes an
 * inconsistent frame as stored); and the capture is rebuilt through its own constructor, so a
 * stored capture over [HistoryFrameCapture.CAP] frames keeps the newest ones (upstream's decoder
 * skips that trim and keeps them all).
 */
object HistoryFrameCaptureCodec {

    fun encode(capture: HistoryFrameCapture): String = jsonObjectOf(
        "frames" to JsonArray(
            capture.frames.map { f ->
                jsonObjectOf(
                    "byteCount" to JsonPrimitive(f.byteCount),
                    "date" to f.date.json(),
                    "hex" to JsonPrimitive(f.hex),
                    "opcode" to JsonPrimitive(f.opcode),
                )
            },
        ),
    ).toString()

    fun decode(text: String): Decoded<HistoryFrameCapture> = readStored(text) { root ->
        val frames = root.obj().required("frames").array().map { e ->
            val o = e.obj()
            val frame = CapturedFrame(o.required("date").instant(), bytesOf(o.required("hex").string()))
            val opcode = o.required("opcode").uInt8()
            val byteCount = o.required("byteCount").int()
            if (frame.opcode != opcode || frame.byteCount != byteCount) {
                unreadable("frame header $opcode/$byteCount disagrees with its bytes ${frame.opcode}/${frame.byteCount}")
            }
            frame
        }
        HistoryFrameCapture(frames)
    }

    /** Bytes from `"4c 00 de ad"`: two lowercase hex digits per byte, one space between. */
    private fun bytesOf(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        val parts = hex.split(' ')
        return ByteArray(parts.size) { i ->
            val p = parts[i]
            if (p.length != 2) unreadable("hex is not in the capture's form")
            ((lowerHexDigit(p[0]) shl 4) or lowerHexDigit(p[1])).toByte()
        }
    }
}
