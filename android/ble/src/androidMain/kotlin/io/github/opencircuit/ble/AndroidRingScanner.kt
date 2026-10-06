package io.github.opencircuit.ble

import android.content.Context

/**
 * Builds the [RingScanner] over the phone's Bluetooth LE scanner. The app must hold
 * `BLUETOOTH_SCAN` before collecting [RingScanner.scan]; without it, or with Bluetooth off, the
 * scan ends at once with `ScanUpdate.Failed(-1)`.
 *
 * The scanner also offers [ScanDiagnostics]: `(scanner as? ScanDiagnostics)?.lastMatch`.
 */
fun RingScanner(context: Context): RingScanner = RingScannerCore(AndroidScanPort(context.applicationContext))
