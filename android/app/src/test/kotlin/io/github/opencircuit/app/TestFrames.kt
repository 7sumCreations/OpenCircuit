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

    /**
     * One real live heart-rate read, in arrival order: the warm-up sentinel (8) and then 82, 84,
     * 88, 90, 91, 66, 61 bpm. `realHRFrames` in
     * `ringkit/src/test/kotlin/io/github/opencircuit/ringkit/RingKitVerifyTest.kt`.
     */
    val realHeartRateRead: List<ByteArray>
        get() = listOf(
            "1500080ab0a7", "1500520ab0fd", "1500540ab0fb", "1500580ab0f7",
            "15005a0ab0f5", "15005b0ab0f4", "1500420ab0ed", "15003d0ab092",
        ).map(::hex)

    /** The warm-up sentinel frame (byte 2 = 8): the sensor has not locked on yet. */
    val heartRateWarmUp: ByteArray get() = hex("1500080ab0a7")

    /** Short frames at the band edges, built on the raw path with a correct XOR trailer. */
    val heartRate29: ByteArray get() = hex("15001d0ab0b2")
    val heartRate30: ByteArray get() = hex("15001e0ab0b1")
    val heartRate220: ByteArray get() = hex("1500dc0ab073")
    val heartRate225: ByteArray get() = hex("1500e10ab04e")

    /** The 91 bpm frame with its last byte off by one: a bad XOR trailer. */
    val heartRateBadTrailer: ByteArray get() = hex("15005b0ab0f5")

    /**
     * Three real long SpO₂ frames (`15 01 …`, byte 14): 96, 96, 97 %. `realSpO2Frames` in
     * `RingKitVerifyTest.kt`.
     */
    val realSpO2Read: List<ByteArray>
        get() = listOf(
            "15010000207afb00000024a1c800600098",
            "150100001615ac0000001a2c8f00600062",
            "15010000102e8000000010be5c00610039",
        ).map(::hex)

    /** The first real SpO₂ frame with byte 14 set to 69 / 70 / 101 %, XOR trailer recomputed. */
    val spo2At69: ByteArray get() = hex("15010000207afb00000024a1c8004500bd")
    val spo2At70: ByteArray get() = hex("15010000207afb00000024a1c8004600be")
    val spo2At101: ByteArray get() = hex("15010000207afb00000024a1c80065009d")
}
