package io.github.opencircuit.ringkit

// Firmware version parsing + generation detection for the device-info screen (upstream #79).
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/FirmwareInfo.swift:24-72 (@ b1c2fdd).
//
// The RingConn firmware prefix encodes the hardware generation:
//   FR01 → Gen 1 · FR02 → Gen 2 · FR04 → Gen 2 Air · FR05 → Gen 3 (model "RingConn Gen3-C384").
// An overnight FR05.008 capture decodes byte-for-byte with the Gen-2 schemas, so no decode path
// branches on generation — it labels the device-info screen and gates the vibration motor.
//
// `hasFirmwareMismatch`: the version is non-empty AND doesn't start with the pinned string — we
// reverse-engineered on a different FW build and some packet offsets may differ. Non-alarming.

/** Hardware generation derived from the DIS Firmware-Revision-String prefix. [rawValue] is the display string. */
enum class RingGeneration(val rawValue: String) {
    GEN1("Gen 1"),
    GEN2("Gen 2"),
    GEN2_AIR("Gen 2 Air"),
    GEN3("Gen 3"),
    UNKNOWN("Unknown"),
}

/**
 * The Device Information Service fields from a connected ring. Populated incrementally as each
 * DIS characteristic is read (GATT reads arrive asynchronously); unread fields stay at their
 * empty defaults. Immutable (PORTING.md D-12): upstream's `var` fields sit on a Swift STRUCT, which
 * copies on assignment; a Kotlin class is a shared reference, so `var` here would let one holder
 * mutate another's snapshot (and never emit through a StateFlow). Accumulate reads with `copy(...)`.
 */
data class FirmwareInfo(
    val version: String = "",
    val modelName: String = "",
    val manufacturer: String = "",
    val hardwareRevision: String? = null,
    /** Ring MAC recovered from the System ID (0x2A23) characteristic. */
    val mac: String? = null,
) {
    /**
     * True when a version string is known AND doesn't start with [PINNED_VERSION]. The non-empty
     * guard prevents a false positive before the DIS read completes.
     */
    val hasFirmwareMismatch: Boolean
        get() = version.isNotEmpty() && !version.startsWith(PINNED_VERSION)

    /** Generation decoded from the four-character FW prefix (FR01/FR02/FR04/FR05); case-sensitive. */
    val generation: RingGeneration
        get() = when {
            version.startsWith("FR01") -> RingGeneration.GEN1
            version.startsWith("FR02") -> RingGeneration.GEN2
            version.startsWith("FR04") -> RingGeneration.GEN2_AIR
            version.startsWith("FR05") -> RingGeneration.GEN3
            else -> RingGeneration.UNKNOWN
        }

    companion object {
        /** The FW version this build was reverse-engineered and tested against. */
        const val PINNED_VERSION = "FR02.018"
    }
}
