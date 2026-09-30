package io.github.opencircuit.ringkit

// RingConn per-connection auth — the "activation" handshake. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/RingAuth.swift:18-131 (@ b1c2fdd).
// Ground truth: ../docs/PROTOCOL.md §5.8 (🟢 verified against 24 captured pairs + the SM3 KAT).
//
// Sequence every connect: host `01 00 00` → ring `81 00 <challenge> <xor>`; host must reply
//   `01 01 <r0> <r1> <r2> 00`   where (r0,r1,r2) = SM3([V, challenge])[29..31]  (last 3 bytes)
//   and  V = mac[3] ^ mac[4] ^ mac[5]  (XOR of the ring's last 3 BLE-MAC bytes).
//
// The only key material is the ring's own MAC — no cloud key, no app secret.

object RingAuth {

    /** The `01 01 r0 r1 r2 00` auth reply for a ring [challenge] (0..255), given the ring's 6-byte [mac]. */
    fun authCommand(challenge: Int, mac: ByteArray): ByteArray {
        val r = response(challenge, mac)
        return byteArrayOf(0x01, 0x01, r[0], r[1], r[2], 0x00)
    }

    /**
     * The 3 response bytes = last 3 bytes of SM3([V, challenge]), V = XOR of the last 3 MAC bytes.
     * Throws [IllegalArgumentException] for a [challenge] outside 0..255 (PORTING.md D-2).
     */
    fun response(challenge: Int, mac: ByteArray): ByteArray {
        require(challenge in 0..0xFF) { "challenge must be a byte 0..255: $challenge" }
        val v = macTailXor(mac)
        val digest = SM3.hash(byteArrayOf(v.toByte(), challenge.toByte()))
        return digest.copyOfRange(digest.size - 3, digest.size)
    }

    /**
     * V = mac[3] ^ mac[4] ^ mac[5] (0..255). Returns 0 for a MAC shorter than 6 bytes
     * (callers must not auth without a real MAC).
     */
    fun macTailXor(mac: ByteArray): Int {
        if (mac.size < 6) return 0
        return mac.u8(3) xor mac.u8(4) xor mac.u8(5)
    }

    /**
     * Extracts the 6-byte MAC from a Device-Information System ID (0x2a23) value: an 8-byte
     * EUI-64 `OUI(3) + FF FE + NIC(3)`, stored forward or byte-reversed. A raw 6-byte MAC is
     * returned as a copy; a longer unrecognised value yields its trailing 6 bytes; shorter → null.
     */
    fun macFromSystemID(sysid: ByteArray): ByteArray? {
        // 8-byte EUI-64 with the FF FE marker in the middle (forward order).
        // (Upstream :49-52 repeats this exact condition and is unreachable — not ported.)
        if (sysid.size == 8 && sysid.u8(3) == 0xFF && sysid.u8(4) == 0xFE) {
            return byteArrayOf(sysid[0], sysid[1], sysid[2], sysid[5], sysid[6], sysid[7])
        }
        // Reversed EUI-64.
        if (sysid.size == 8) {
            val rev = sysid.reversedArray()
            if (rev.u8(3) == 0xFF && rev.u8(4) == 0xFE) {
                return byteArrayOf(rev[0], rev[1], rev[2], rev[5], rev[6], rev[7])
            }
        }
        // Raw 6-byte MAC, or the trailing 6 bytes as a last resort.
        if (sysid.size == 6) return sysid.copyOf()
        if (sysid.size > 6) return sysid.copyOfRange(sysid.size - 6, sysid.size)
        return null
    }
}

/**
 * SM3 — the Chinese national 256-bit hash (GB/T 32905-2016), the only crypto the RingConn
 * auth needs. Words are Kotlin `Int`: 32-bit two's complement is bit-identical to Swift's
 * `UInt32` for `xor`/`and`/`or`/`inv`/wrapping `+`, provided right shifts are `ushr` (logical),
 * never `shr` (arithmetic). Verified by the `SM3("abc")` KAT in `RingAuthTest`.
 */
object SM3 {
    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
    )

    private fun rotl(x: Int, n: Int): Int {
        val s = n and 31
        return if (s == 0) x else (x shl s) or (x ushr (32 - s))
    }

    private fun t(j: Int): Int = if (j < 16) 0x79cc4519 else 0x7a879d8a

    private fun ff(x: Int, y: Int, z: Int, j: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    private fun gg(x: Int, y: Int, z: Int, j: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)

    private fun p0(x: Int): Int = x xor rotl(x, 9) xor rotl(x, 17)

    private fun p1(x: Int): Int = x xor rotl(x, 15) xor rotl(x, 23)

    /** SM3 digest of [input] — always 32 bytes, big-endian words. */
    fun hash(input: ByteArray): ByteArray {
        // Padding: 0x80, zeros to 56 mod 64, then the 64-bit big-endian bit length.
        val bitLen = input.size.toLong() * 8
        var padded = input.size + 1
        while (padded % 64 != 56) padded++
        val msg = input.copyOf(padded + 8)
        msg[input.size] = 0x80.toByte()
        for (k in 0 until 8) msg[padded + k] = (bitLen ushr (56 - 8 * k)).toByte()

        val v = IV.copyOf()
        val w = IntArray(68)
        val w1 = IntArray(64)
        var blk = 0
        while (blk < msg.size) {
            for (j in 0 until 16) {
                val o = blk + j * 4
                w[j] = (msg.u8(o) shl 24) or (msg.u8(o + 1) shl 16) or (msg.u8(o + 2) shl 8) or msg.u8(o + 3)
            }
            for (j in 16 until 68) {
                w[j] = p1(w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)) xor rotl(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]

            var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
            var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]
            for (j in 0 until 64) {
                val ss1 = rotl(rotl(a, 12) + e + rotl(t(j), j % 32), 7)
                val ss2 = ss1 xor rotl(a, 12)
                val tt1 = ff(a, b, c, j) + d + ss2 + w1[j]
                val tt2 = gg(e, f, g, j) + h + ss1 + w[j]
                d = c; c = rotl(b, 9); b = a; a = tt1
                h = g; g = rotl(f, 19); f = e; e = p0(tt2)
            }
            v[0] = v[0] xor a; v[1] = v[1] xor b; v[2] = v[2] xor c; v[3] = v[3] xor d
            v[4] = v[4] xor e; v[5] = v[5] xor f; v[6] = v[6] xor g; v[7] = v[7] xor h
            blk += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[4 * i] = (v[i] ushr 24).toByte()
            out[4 * i + 1] = (v[i] ushr 16).toByte()
            out[4 * i + 2] = (v[i] ushr 8).toByte()
            out[4 * i + 3] = v[i].toByte()
        }
        return out
    }
}
