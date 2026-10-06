package io.github.opencircuit.app.demo

import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingScanner
import io.github.opencircuit.ble.ScanDiagnostic
import io.github.opencircuit.ble.ScanDiagnostics
import io.github.opencircuit.ble.ScanUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pretend scanner for debug builds: the emulator has no ring to find, so each scan finds
 * [ring] (the demo ring) at once and selects it after 2.5 s with no other ring, then ends, as
 * `:ble`'s scanner does when exactly one ring is nearby. Like `:ble`'s scanner it keeps the last
 * advertisement it matched ([lastMatch]): a made-up one with flags, the ring's data service, the
 * demo's name and a tx power. It counts the scans it started ([scansStarted]) so the on-device
 * tests can tell a reconnect without a scan from one with. Only in the debug source set.
 */
class DemoRingScanner(private val ring: RememberedRing) : RingScanner, ScanDiagnostics {

    private val matchFlow = MutableStateFlow<ScanDiagnostic?>(null)
    private val scans = AtomicInteger()

    override val lastMatch: StateFlow<ScanDiagnostic?> = matchFlow.asStateFlow()

    /** How many scans were started since this scanner was built. */
    val scansStarted: Int get() = scans.get()

    override fun scan(): Flow<ScanUpdate> = flow {
        scans.incrementAndGet()
        matchFlow.value = ScanDiagnostic(ring.addressType, demoAdvertisement(), DEMO_RSSI)
        emit(ScanUpdate.Found(listOf(ring)))
        delay(QUIET_WINDOW_MILLIS)
        emit(ScanUpdate.Selected(ring))
    }

    /** Flags 0x06, the data service `8327ad99-…` (little-endian), the demo's name, tx power 0 dBm. */
    private fun demoAdvertisement(): ByteArray {
        val name = (ring.name ?: "Demo ring").toByteArray(Charsets.UTF_8)
        val service = intArrayOf(0x37, 0x04, 0x1c, 0x97, 0xd7, 0x6d, 0xce, 0xa8, 0x22, 0x4a, 0x87, 0x2d, 0x99, 0xad, 0x27, 0x83)
        return byteArrayOf(0x02, 0x01, 0x06) +
            byteArrayOf(0x11, 0x07) + ByteArray(service.size) { service[it].toByte() } +
            byteArrayOf((name.size + 1).toByte(), 0x09) + name +
            byteArrayOf(0x02, 0x0a, 0x00)
    }

    private companion object {
        /** `:ble`'s selection quiet window (upstream `selectionQuietWindow`, RingScanner.swift:293). */
        const val QUIET_WINDOW_MILLIS = 2_500L

        /** A made-up signal strength. */
        const val DEMO_RSSI = -55
    }
}
