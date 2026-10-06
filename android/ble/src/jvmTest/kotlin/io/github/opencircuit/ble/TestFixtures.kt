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
