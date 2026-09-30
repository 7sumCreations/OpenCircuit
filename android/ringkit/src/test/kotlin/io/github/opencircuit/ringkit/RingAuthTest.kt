package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for `RingAuth` + `SM3` — port of the auth checks in upstream
 * ios/OpenCircuitKit/Sources/RingKitVerify/main.swift:264-276 (@ b1c2fdd), plus the 24
 * captured challenge→response pairs from `S/Opcodes.swift:170-179` (`knownAuthNonces`).
 *
 * `knownAuthNonces` itself is NOT ported (PORTING.md D-1, legacy auth); its pairs live here
 * as a fixture only. They were captured from the official app talking to the ring with MAC
 * F8:79:99:F7:03:AD (already public upstream), so `RingAuth.response` must reproduce every
 * one of them. Most challenges and response bytes are ≥ 0x80, which catches a signed-`Byte`
 * slip (A5). All fixtures are typed as raw hex, never produced by production code.
 */
class RingAuthTest {
    // RingKitVerify/main.swift:267 — F8:79:99:F7:03:AD → V = 0xf7 ^ 0x03 ^ 0xad = 0x59.
    private val authMac = hex("f87999f703ad")

    // S/Opcodes.swift:170-179 — challenge → 3 response bytes, transcribed verbatim.
    private val capturedPairs: List<Pair<Int, String>> = listOf(
        0x0f to "4bcce6", 0x1f to "9bc931", 0x3b to "4bee0c",
        0x3f to "6797a8", 0x49 to "0b206f", 0x52 to "277d7f",
        0x78 to "da5d57", 0x80 to "a9a3ef", 0x81 to "4cc4ae",
        0x86 to "db2b80", 0x94 to "71e959", 0x96 to "0860ce",
        0x9d to "6b6e2c", 0xa3 to "5eb61e", 0xb0 to "318267",
        0xbc to "1252f2", 0xc2 to "053317", 0xc4 to "a2f827",
        0xcb to "09889c", 0xd8 to "9c6191", 0xda to "f01e88",
        0xe3 to "1be985", 0xe5 to "520be1", 0xf9 to "3609b2",
    )

    // RingKitVerify/main.swift:265-266 — GB/T 32905-2016 SM3("abc") known-answer test.
    // Runs first in the slice: a `shr`-for-`ushr` or signed-byte slip in SM3 fails it.
    @Test
    fun sm3AbcKnownAnswer() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            SM3.hash("abc".toByteArray(Charsets.US_ASCII)).toHex().replace(" ", ""),
        )
    }

    // Not upstream: GB/T 32905-2016 example 2 ("abcd" × 16 = 64 bytes → 2 blocks after padding)
    // and the empty input — the auth pairs are all single-block, so these cover the multi-block
    // loop and the zero-length padding edge (A3). Expected values cross-checked with
    // `openssl dgst -sm3` (OpenSSL 3, Homebrew) on 2026-09-30.
    @Test
    fun sm3StandardTwoBlockAndEmptyVectors() {
        assertEquals(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
            SM3.hash("abcd".repeat(16).toByteArray(Charsets.US_ASCII)).toHex().replace(" ", ""),
            "64-byte two-block vector",
        )
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            SM3.hash(ByteArray(0)).toHex().replace(" ", ""),
            "empty input",
        )
    }

    // RingKitVerify/main.swift:267-268
    @Test
    fun macTailXorIs0x59() {
        assertEquals(0x59, RingAuth.macTailXor(authMac))
    }

    // S/RingAuth.swift:35-38 — short MAC → 0 (A3).
    @Test
    fun macTailXorShortMacIsZero() {
        assertEquals(0, RingAuth.macTailXor(hex("f87999f703")))
        assertEquals(0, RingAuth.macTailXor(ByteArray(0)))
    }

    // S/Opcodes.swift:170-179 + RingKitVerify/main.swift:269-272 (which checks 5 of them).
    @Test
    fun reproducesAll24CapturedPairs() {
        // Anchor the count: a loop over an accidentally empty/truncated table passes.
        assertEquals(24, capturedPairs.size, "fixture must hold all 24 captured pairs")
        assertEquals(24, capturedPairs.map { it.first }.toSet().size, "challenges must be distinct")
        for ((challenge, expected) in capturedPairs) {
            assertContentEquals(
                hex(expected),
                RingAuth.response(challenge, authMac),
                "f(%02x) should be $expected".format(challenge),
            )
        }
    }

    // RingKitVerify/main.swift:273-274
    @Test
    fun authCommandFor0xb0() {
        assertContentEquals(bytes(0x01, 0x01, 0x31, 0x82, 0x67, 0x00), RingAuth.authCommand(0xb0, authMac))
    }

    // PORTING.md D-2 — Swift's `UInt8` challenge made out-of-range values impossible; the
    // Kotlin `Int` parameter rejects them instead of silently truncating 0x100 → 0x00 (A3).
    @Test
    fun challengeOutsideByteRangeIsRejected() {
        assertFailsWith<IllegalArgumentException> { RingAuth.response(0x100, authMac) }
        assertFailsWith<IllegalArgumentException> { RingAuth.authCommand(-1, authMac) }
    }

    // PORTING.md D-11 — upstream computes V = 0 for a short MAC and returns a well-formed but wrong
    // reply; the Kotlin auth entry points reject any MAC that isn't exactly 6 bytes.
    @Test
    fun macNotSixBytesIsRejected() {
        assertFailsWith<IllegalArgumentException> { RingAuth.response(0xb0, hex("f87999f703")) }
        assertFailsWith<IllegalArgumentException> { RingAuth.authCommand(0xb0, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { RingAuth.authCommand(0xb0, hex("f87999fffef703ad")) }
        // The 6-byte MAC still authenticates (the guard can't reject the real input).
        assertContentEquals(bytes(0x01, 0x01, 0x31, 0x82, 0x67, 0x00), RingAuth.authCommand(0xb0, authMac))
    }

    // RingKitVerify/main.swift:275-276, S/RingAuth.swift:46-48 — forward EUI-64.
    @Test
    fun macFromSystemIdForward() {
        val mac = RingAuth.macFromSystemID(hex("f87999fffef703ad"))
        assertNotNull(mac)
        assertContentEquals(authMac, mac)
    }

    // S/RingAuth.swift:53-57 — byte-reversed EUI-64.
    @Test
    fun macFromSystemIdReversed() {
        val mac = RingAuth.macFromSystemID(hex("ad03f7feff9979f8"))
        assertNotNull(mac)
        assertContentEquals(authMac, mac)
    }

    // S/RingAuth.swift:60 — a raw 6-byte MAC is returned as-is.
    @Test
    fun macFromSystemIdRaw6() {
        val mac = RingAuth.macFromSystemID(hex("f87999f703ad"))
        assertNotNull(mac)
        assertContentEquals(authMac, mac)
    }

    // S/RingAuth.swift:61 — longer than 6 bytes without an FF FE marker → trailing 6 bytes.
    @Test
    fun macFromSystemIdNoMarkerTakesTrailingSix() {
        assertContentEquals(authMac, RingAuth.macFromSystemID(hex("0102f87999f703ad")))
        assertContentEquals(authMac, RingAuth.macFromSystemID(hex("aaf87999f703ad")))
    }

    // S/RingAuth.swift:62 — shorter than 6 bytes → null (A3).
    @Test
    fun macFromSystemIdShortIsNull() {
        assertNull(RingAuth.macFromSystemID(hex("f87999f703")))
        assertNull(RingAuth.macFromSystemID(ByteArray(0)))
    }
}
