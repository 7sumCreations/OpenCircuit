package io.github.opencircuit.ble

import kotlinx.coroutines.flow.StateFlow

/**
 * What a scanner last saw of a ring, for a "connection details" screen: the phone is not on a
 * cable, so this is where a tester sees the ring's advertisement and its address type.
 *
 * Not part of [RingScanner]: a scanner that keeps it offers it as well, and the app reaches it
 * with `(scanner as? ScanDiagnostics)`. The scanner this module's Android factory builds does.
 *
 * It holds no address field, but the advertisement's bytes carry the ring's advertised name, which
 * ends with two bytes of its MAC (`RingConn Gen2-XXXX`), and may carry manufacturer data: show
 * them on the user's own screen if needed, and never log or store them.
 */
interface ScanDiagnostics {
    /**
     * The latest advertisement of a ring that matched, from any scan of this scanner; null until
     * one matched. With several rings in range it is whichever advertised last.
     */
    val lastMatch: StateFlow<ScanDiagnostic?>
}

/**
 * One matched advertisement. Equal by content; the bytes are copied in and on every read.
 * Its text form shows the record's length, not its bytes.
 */
class ScanDiagnostic(
    /** The address type Android reported for the ring, or the one guessed from its address when Android reports none. */
    val addressType: AddressType,
    rawScanRecord: ByteArray,
    /** The signal strength in dBm. */
    val rssi: Int,
) {
    private val record = rawScanRecord.copyOf()

    /**
     * The advertisement's bytes as Android received them (flags, service UUIDs, name, manufacturer
     * data, …); a fresh copy on every read.
     */
    val rawScanRecord: ByteArray get() = record.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ScanDiagnostic && addressType == other.addressType && rssi == other.rssi &&
            record.contentEquals(other.record)

    override fun hashCode(): Int = (31 * addressType.hashCode() + record.contentHashCode()) * 31 + rssi

    override fun toString(): String = "ScanDiagnostic(addressType=$addressType, rawScanRecord=${record.size} bytes, rssi=$rssi)"
}
