package io.github.opencircuit.ble

/**
 * The phone's LE scanner, as the ring scanner uses it: start one unfiltered scan, receive every
 * advertisement it reports, stop it. Android implements it over `BluetoothLeScanner`; the tests
 * use a scripted fake.
 */
internal interface ScanPort {
    /**
     * Starts one scan whose results and error go to [events] (from any thread) until the returned
     * scan is stopped; null when Android refused to start it (Bluetooth off, no scanner, the
     * permission missing).
     */
    fun start(events: Sink): Scan?

    /** One running scan. */
    fun interface Scan {
        /** Stops this scan. Safe to call more than once. */
        fun stop()
    }

    /** Where a scan's reports go. Called from any thread; must only hand the event on. */
    fun interface Sink {
        /** Hands [event] to the scanner. */
        fun deliver(event: ScanEvent)
    }
}

/** What a running scan reports. */
internal sealed interface ScanEvent {
    /** One advertisement Android reported (`ScanCallback.onScanResult`). */
    class Result(
        /** `BluetoothDevice.getAddress()` as Android gave it. */
        val address: String,
        /** The address type Android reported for it (Android 15 and later), else null. */
        val reportedAddressType: AddressType?,
        /** The advertised name, if any. */
        val name: String?,
        /** The service UUIDs in the advertisement, as Android parsed them. */
        val serviceUuids: List<String>,
        rawScanRecord: ByteArray,
        /** The signal strength in dBm. */
        val rssi: Int,
    ) : ScanEvent {
        private val record = rawScanRecord.copyOf()

        /** The advertisement's bytes as received (`ScanRecord.getBytes()`); a fresh copy on every read. */
        val rawScanRecord: ByteArray get() = record.copyOf()
    }

    /** Android stopped the scan with an error (`ScanCallback.onScanFailed`). */
    data class Error(
        /** The `ScanCallback` error code. */
        val errorCode: Int,
    ) : ScanEvent
}
