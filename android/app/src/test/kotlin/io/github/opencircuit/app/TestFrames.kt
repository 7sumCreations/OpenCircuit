package io.github.opencircuit.app

// Test-only frames for the app's tests, built on the RAW byte path (hex literals), never through
// production code such as `Command` or a frame builder.

/** "8100b031" → [0x81, 0x00, 0xb0, 0x31]. Two hex chars per byte; odd length or a non-hex char throws. */
internal fun hex(s: String): ByteArray {
    require(s.length % 2 == 0) { "hex string must have an even length: \"$s\"" }
    return ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

/** Lowercase hex without separators — to check that a log line does not carry a frame's bytes. */
internal fun ByteArray.toPlainHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** Frames the ring sends, each a fresh array per access so a test cannot change another's copy. */
internal object TestFrames {
    /**
     * A real worn / streaming `0x10` descriptor: battery `[1]` = 0x42 = 66 %, state `[2]` = 0x02,
     * case byte `[17]` = 0xff (not in the case). Copied from `wornFrame` in
     * `ringkit/src/test/kotlin/io/github/opencircuit/ringkit/DeviceStatusTest.kt`.
     */
    val wornDescriptor: ByteArray get() = hex("1042020000000140013e000000000fa100ff00")

    /**
     * A real on-charger `0x10` descriptor: battery `[1]` = 0x47 = 71 %, state `[2]` = 0x04.
     * Copied from `chargingFrame` in the same test file.
     */
    val chargingDescriptor: ByteArray get() = hex("104704000000010c01060000000010f7024600")

    /** The charging descriptor as the `0x87` response to `07 00 00`: same body, opcode `0x87`. */
    val chargingResponseDescriptor: ByteArray get() = chargingDescriptor.also { it[0] = 0x87.toByte() }

    /**
     * A real short live-HR frame, 91 bpm (`15 00 5b 0a b0 f4`). From
     * `ringkit/src/test/kotlin/io/github/opencircuit/ringkit/RingKitVerifyTest.kt`.
     */
    val liveHeartRate: ByteArray get() = hex("15005b0ab0f4")

    /** A ring heartbeat (`0x11`); `:ble` already acknowledges it with `91 00 00`. */
    val heartbeat: ByteArray get() = hex("11002a3b")

    /** A history page (`0x4c`), as the ring sends it during a sync; marker bytes `de ad be ef`. */
    val historyPage4c: ByteArray get() = hex("4c00deadbeef0102")

    /** A history page (`0x47`); marker bytes `ca fe`. */
    val historyPage47: ByteArray get() = hex("4700cafe0304")

    /** A historical sport page (`0x4d`); marker bytes `f0 0d`. */
    val historyPage4d: ByteArray get() = hex("4d00f00d0506")

    /** An opcode no part of the app knows (`0xee`); marker bytes `ba ad`. */
    val unknown: ByteArray get() = hex("ee00baad")
}
