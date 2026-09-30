package io.github.opencircuit.ringkit

// RingConn response-frame codec. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/Frame.swift (@ b1c2fdd).
//
// Ground truth is ../docs/PROTOCOL.md §3 (🟢 on FW FR02.018):
//
//     [cmd][len][payload…][xor]
//
//   • cmd — 1-byte command id (TX) / response id (RX)
//   • xor — trailer = XOR of every byte before it (RESPONSES only)
//   • len — 2nd byte; semantics still 🟡, so this codec does NOT impose a length
//           interpretation — it only validates the XOR trailer.

/**
 * Unsigned read of one byte (0..255). The ONLY sanctioned byte-read path in `:ringkit`
 * (ADR E1 D1 A): Kotlin `Byte` is signed, so a bare `this[i].toInt()` turns 0xb0 into −80.
 */
internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

object Frame {

    /** XOR of every byte in [body] (0..255). The RingConn response-frame checksum. */
    fun xorTrailer(body: ByteArray): Int {
        var x = 0
        for (i in body.indices) x = x xor body.u8(i)
        return x
    }

    /** True when a whole frame's last byte is the correct XOR trailer. Frames shorter than 2 bytes are invalid. */
    fun isValid(frame: ByteArray): Boolean {
        if (frame.size < 2) return false
        return xorTrailer(frame.copyOfRange(0, frame.size - 1)) == frame.u8(frame.size - 1)
    }

    /** Response opcode for a command opcode: `cmd XOR 0x80` (PROTOCOL.md §3). */
    const val RESPONSE_FLAG = 0x80

    fun responseId(cmd: Int): Int = cmd xor RESPONSE_FLAG

    // There is intentionally NO command encoder here. Commands are NOT XOR-checksummed
    // (PROTOCOL.md §3) — they are sent verbatim from the literals in `Command`.

    /**
     * A parsed response frame: opcode, body (bytes between opcode and trailer), trailer.
     * Content-based equality (ADR E1 D1 A) — `ByteArray` alone compares by reference.
     */
    class Parsed(val opcode: Int, val body: ByteArray, val trailer: Int) {
        override fun equals(other: Any?): Boolean =
            other is Parsed && opcode == other.opcode && trailer == other.trailer &&
                body.contentEquals(other.body)

        override fun hashCode(): Int = (31 * opcode + body.contentHashCode()) * 31 + trailer

        override fun toString(): String =
            "Frame.Parsed(opcode=%02x, body=[%s], trailer=%02x)".format(
                opcode, body.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }, trailer,
            )
    }

    /** Splits a frame into opcode/body/trailer, or returns null when the XOR trailer doesn't validate. */
    fun parse(frame: ByteArray): Parsed? {
        if (!isValid(frame)) return null
        return Parsed(
            opcode = frame.u8(0),
            body = frame.copyOfRange(1, frame.size - 1),
            trailer = frame.u8(frame.size - 1),
        )
    }
}
