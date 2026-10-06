package io.github.opencircuit.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ScanPort] over Android's `BluetoothLeScanner`. A mechanical mapping with no scanner logic:
 * one unfiltered scan per [start], each `ScanResult` copied into a [ScanEvent.Result] and each
 * `onScanFailed` into a [ScanEvent.Error]. Compiled, not exercised, on the JVM.
 *
 * Unfiltered, because the ring is matched by its name prefix, which no `ScanFilter` can express,
 * and it may not advertise its data service. Low-latency, because the scan runs only while the
 * user is pairing with the app on screen. The app holds `BLUETOOTH_SCAN`; a missing or revoked
 * permission, or Bluetooth being off, refuses the start (null) and is never thrown.
 */
@SuppressLint("MissingPermission") // the caller holds BLUETOOTH_SCAN; a revocation is caught below
internal class AndroidScanPort(context: Context) : ScanPort {

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)

    override fun start(events: ScanPort.Sink): ScanPort.Scan? {
        val scanner = try {
            manager?.adapter?.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        } ?: return null
        val callback = Callback(events)
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (_: SecurityException) {
            return null
        } catch (_: IllegalStateException) {
            return null // Bluetooth turned off
        }
        val stopped = AtomicBoolean(false)
        return ScanPort.Scan {
            if (stopped.compareAndSet(false, true)) {
                try {
                    scanner.stopScan(callback)
                } catch (_: SecurityException) {
                    // Nothing more to stop: without the permission the scan cannot be running for us.
                } catch (_: IllegalStateException) {
                    // Bluetooth is off, which ended the scan already.
                }
            }
        }
    }

    /** Copies each report into a [ScanEvent] and hands it on; nothing else. */
    private class Callback(private val events: ScanPort.Sink) : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            events.deliver(result.toEvent())
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { events.deliver(it.toEvent()) }
        }

        override fun onScanFailed(errorCode: Int) {
            events.deliver(ScanEvent.Error(errorCode))
        }
    }
}

private fun ScanResult.toEvent(): ScanEvent.Result {
    val record = scanRecord
    return ScanEvent.Result(
        address = device.address,
        reportedAddressType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            device.addressType.toAddressType()
        } else {
            null
        },
        name = record?.deviceName ?: device.cachedName(),
        serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
        rawScanRecord = record?.bytes ?: ByteArray(0),
        rssi = rssi,
    )
}

/** `ADDRESS_TYPE_PUBLIC` / `ADDRESS_TYPE_RANDOM`; anything else (anonymous, unknown) is no report. */
private fun Int.toAddressType(): AddressType? = when (this) {
    BluetoothDevice.ADDRESS_TYPE_PUBLIC -> AddressType.PUBLIC
    BluetoothDevice.ADDRESS_TYPE_RANDOM -> AddressType.RANDOM
    else -> null
}

/** The name Android already knows for the device, when the advertisement carries none. */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT; a revocation reads as no name
private fun BluetoothDevice.cachedName(): String? = try {
    name
} catch (_: SecurityException) {
    null
}
