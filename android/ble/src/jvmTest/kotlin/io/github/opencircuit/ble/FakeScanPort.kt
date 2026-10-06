package io.github.opencircuit.ble

import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted LE scanner: records every scan started and stopped, and delivers advertisements and
 * errors to the scans running at the time, as Android's `ScanCallback` would.
 */
internal class FakeScanPort : ScanPort {
    private val running = CopyOnWriteArrayList<ScanPort.Sink>()

    /** When true, the next starts are refused (Android could not start the scan). */
    var refuseStarts = false

    /** Scans started (refused ones included). */
    var starts = 0
        private set

    /** Scans stopped (each running scan counted once). */
    var stops = 0
        private set

    /** Scans running now. */
    val runningScans: Int get() = running.size

    override fun start(events: ScanPort.Sink): ScanPort.Scan? {
        starts++
        if (refuseStarts) return null
        running += events
        return ScanPort.Scan { if (running.remove(events)) stops++ }
    }

    /** Every running scan sees one advertisement. */
    fun advertise(
        address: String,
        name: String? = null,
        serviceUuids: List<String> = emptyList(),
        reportedAddressType: AddressType? = null,
        record: ByteArray = advertisementOf(name),
        rssi: Int = -60,
    ) {
        val result = ScanEvent.Result(address, reportedAddressType, name, serviceUuids, record, rssi)
        running.forEach { it.deliver(result) }
    }

    /** Every running scan fails with [errorCode]. */
    fun fail(errorCode: Int) {
        running.forEach { it.deliver(ScanEvent.Error(errorCode)) }
    }

    companion object {
        /** The ring's data service UUID, typed from `docs/PROTOCOL.md` §1 (not read from the code). */
        const val RING_SERVICE = "8327ad99-2d87-4a22-a8ce-6dd7971c0437"

        /**
         * A synthetic address `XX:00:00:00:00:YY` built at run time (no real device has it);
         * [first] decides the address type a guess would give.
         */
        fun address(first: Int, last: Int): String =
            listOf(first, 0, 0, 0, 0, last).joinToString(":") { String.format(Locale.ROOT, "%02X", it) }

        /** An advertisement on the raw path: flags `02 01 06`, then the complete local name when there is one. */
        fun advertisementOf(name: String?): ByteArray {
            val flags = byteArrayOf(0x02, 0x01, 0x06)
            if (name == null) return flags
            val utf8 = name.toByteArray(Charsets.UTF_8)
            return flags + byteArrayOf((utf8.size + 1).toByte(), 0x09) + utf8
        }
    }
}
