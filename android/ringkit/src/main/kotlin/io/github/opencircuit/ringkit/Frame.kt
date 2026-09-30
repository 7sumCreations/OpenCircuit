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
 * because Kotlin `Byte` is signed, so a bare `this[i].toInt()` turns 0xb0 into −80.
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
        // XOR in place over [0, size-1) — no per-notification copy on the BLE hot path.
        var x = 0
        for (i in 0 until frame.size - 1) x = x xor frame.u8(i)
        return x == frame.u8(frame.size - 1)
    }

    /** Response opcode for a command opcode: `cmd XOR 0x80` (PROTOCOL.md §3). */
    const val RESPONSE_FLAG = 0x80

    fun responseId(cmd: Int): Int = cmd xor RESPONSE_FLAG

    // There is intentionally NO command encoder here. Commands are NOT XOR-checksummed
    // (PROTOCOL.md §3) — they are sent verbatim from the literals in `Command`.

    /**
     * A parsed response frame: opcode, body (bytes between opcode and trailer), trailer.
     * Content-based equality — `ByteArray` alone compares by reference.
     *
     * Keeps the guarantees Swift's `struct Parsed { let opcode: UInt8; let body: [UInt8] … }` had
     * by type (PORTING.md D-13): [opcode] and [trailer] must be bytes 0..255, and [body] is a
     * private copy — taken on construction and handed out fresh on every read — so no holder can
     * change another holder's frame or move its `hashCode`.
     */
    class Parsed(val opcode: Int, body: ByteArray, val trailer: Int) {
        init {
            require(opcode in 0..0xFF) { "opcode must be a byte 0..255: $opcode" }
            require(trailer in 0..0xFF) { "trailer must be a byte 0..255: $trailer" }
        }

        private val bodyBytes: ByteArray = body.copyOf()

        /** The bytes between opcode and trailer. A fresh copy on every read. */
        val body: ByteArray get() = bodyBytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Parsed && opcode == other.opcode && trailer == other.trailer &&
                bodyBytes.contentEquals(other.bodyBytes)

        override fun hashCode(): Int = (31 * opcode + bodyBytes.contentHashCode()) * 31 + trailer

        override fun toString(): String =
            "Frame.Parsed(opcode=%02x, body=[%s], trailer=%02x)".format(
                opcode, bodyBytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }, trailer,
            )
    }

    /**
     * Splits a frame into opcode/body/trailer, or returns null when the XOR trailer doesn't validate.
     * (The body slice is copied once more inside [Parsed]; ≤ ~20 bytes, and not on the per-notification
     * [isValid] path.)
     */
    fun parse(frame: ByteArray): Parsed? {
        if (!isValid(frame)) return null
        return Parsed(
            opcode = frame.u8(0),
            body = frame.copyOfRange(1, frame.size - 1),
            trailer = frame.u8(frame.size - 1),
        )
    }
}
