package io.github.opencircuit.ringkit

// Test-only byte helpers. Port of the `hex(_:)` helper in upstream
// ios/OpenCircuitKit/Tests/OpenCircuitKitTests/FrameTests.swift:9-16 (@ b1c2fdd).
// Fixtures are built on the RAW byte path with these — never through production
// code such as `Command` or `Frame.xorTrailer` (PL-2026-09-30-m).

/** "8100b031" → [0x81, 0x00, 0xb0, 0x31]. Two hex chars per byte; odd length or a non-hex char throws. */
internal fun hex(s: String): ByteArray {
    require(s.length % 2 == 0) { "hex string must have an even length: \"$s\"" }
    return ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

/** bytes(0x95, 0x00, 0x00) → a ByteArray. Each value must be an unsigned byte 0..255. */
internal fun bytes(vararg v: Int): ByteArray {
    require(v.all { it in 0..0xFF }) { "byte values must be 0..255: ${v.toList()}" }
    return ByteArray(v.size) { v[it].toByte() }
}

/** Lowercase hex, space-separated — for readable assertion messages. */
internal fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
