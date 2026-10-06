package io.github.opencircuit.ble

/**
 * Raw-path fixtures for the link tests. Every byte string is typed from a capture already public
 * in the `:ringkit` tests, never produced by production code:
 *  - the ring MAC `F8:79:99:F7:03:AD` and its System ID `f8 79 99 ff fe f7 03 ad`
 *    (`RingAuthTest`, `macFromSystemIDForwardEui64`);
 *  - the challenge frame `81 00 b0 31` (`FrameTest.realFrames`) and the reply the official app
 *    sent to challenge `0xb0` from that ring, `01 01 31 82 67 00` (`RingAuthTest.authCommandFor0xb0`);
 *  - a real `0x15` response, `15 00 08 0a b0 a7` (`FrameTest.realFrames`), as the first data frame.
 */
internal object Fixtures {
    const val RING_ADDRESS = "F8:79:99:F7:03:AD"
    val ring = RememberedRing(RING_ADDRESS, AddressType.RANDOM, "RingConn Gen2-03AD")

    val systemId: ByteArray get() = hex("f87999fffef703ad")
    val challengeFrame: ByteArray get() = hex("8100b031")
    val authReplyForChallengeB0: ByteArray get() = hex("010131826700")
    val firstDataFrame: ByteArray get() = hex("1500080ab0a7")

    /**
     * Two more captured challenge → reply pairs for the same ring (`RingAuthTest.capturedPairs`):
     * challenge `0x0f` → `4b cc e6`, challenge `0x49` → `0b 20 6f`. The frames carry their XOR trailer.
     */
    val challengeFrame0f: ByteArray get() = hex("81000f8e")
    val authReplyForChallenge0f: ByteArray get() = hex("01014bcce600")
    val challengeFrame49: ByteArray get() = hex("810049c8")
    val authReplyForChallenge49: ByteArray get() = hex("01010b206f00")

    /**
     * A real overnight `0x4c` sleep page, 142 B with a valid XOR trailer (`ScriptedDrainTest.realPage`,
     * upstream RingKitVerify/main.swift:307-310).
     */
    val sleepPage4c: ByteArray
        get() = hex(
            "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
                "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
                "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
                "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc",
        )

    /** The same `0x4c` page with its XOR trailer flipped: a page that fails its checksum. */
    val sleepPage4cBadXor: ByteArray get() = sleepPage4c.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }

    /**
     * The first 12 bytes of a real `0x47` PPG page (`DiagnosticsFrameImportTest`, a 239 B page
     * logged as `47 00 00 0c 65 86 3a 02 9f 00 30 3c`): a truncated page.
     */
    val ppgPage47Truncated: ByteArray get() = hex("4700000c65863a029f00303c")

    /** A raw `0x4d` sport-history frame too short to decode, `4d 00 4d` (`WorkoutHazardTest`). */
    val sportPage4dShort: ByteArray get() = hex("4d004d")

    /** The ring's `0x11` heartbeat as `docs/PROTOCOL.md` records it on the wire, `11 00 0N 55`, for N = [n] (1..9). */
    fun heartbeat(n: Int): ByteArray {
        require(n in 1..9)
        return hex("11000" + n + "55")
    }

    /** A real `0x10` status descriptor (`FrameTest.realFrames`): a frame the link delivers and does not acknowledge. */
    val descriptor10: ByteArray get() = hex("104e0100000000fd00fd00000000100c0bffb7")

    /** The Device Information values of a Gen 2 ring on its pinned firmware. */
    val deviceInformation: Map<GattPort.Characteristic, ByteArray>
        get() = mapOf(
            GattPort.SYSTEM_ID to systemId,
            GattPort.FIRMWARE_REVISION to "FR02.018".toByteArray(Charsets.UTF_8),
            GattPort.MANUFACTURER_NAME to "RingConn".toByteArray(Charsets.UTF_8),
            GattPort.HARDWARE_REVISION to "V2.0".toByteArray(Charsets.UTF_8),
        )

    /** A ring that answers `01 00 00` with challenge `0xb0` and streams once it gets the right reply. */
    fun acceptingRing(
        deviceInformation: Map<GattPort.Characteristic, ByteArray> = this.deviceInformation,
        mtuGrant: Int = 247,
    ): FakeGatt.Script = FakeGatt.Script(
        deviceInformation = deviceInformation,
        challengeFrame = challengeFrame,
        acceptedAuthReply = authReplyForChallengeB0,
        firstFrameAfterAuth = firstDataFrame,
        mtuGrant = mtuGrant,
    )

    fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length: $s" }
        return ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}
