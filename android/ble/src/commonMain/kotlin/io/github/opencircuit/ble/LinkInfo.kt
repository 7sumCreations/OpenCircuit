package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.FirmwareInfo

/**
 * What the link learned about the ring on the current connection. Published by [RingLink.info];
 * reset when a new connection starts. Immutable; updated with `copy`.
 */
data class LinkInfo(
    /** The Device Information fields read on this connection (empty until read). */
    val firmware: FirmwareInfo = FirmwareInfo(),
    /**
     * The MAC the auth reply is computed from, upper-case and colon-separated like
     * [FirmwareInfo.mac]. When the System ID (`0x2a23`) and the device address disagree, the
     * System ID wins.
     */
    val mac: String? = null,
    /** True when the System ID and the device address name different MACs. */
    val macMismatch: Boolean = false,
    /** The ATT MTU of the connection; 23 is the ATT default before the MTU exchange. */
    val attMtu: Int = 23,
    /**
     * True when [attMtu] is at least 246, so a 243-byte history frame fits in one notification.
     * Opening history sync is refused while this is false.
     */
    val historySafe: Boolean = false,
    /** True when the phone holds a bond with the ring. */
    val bonded: Boolean = false,
)
