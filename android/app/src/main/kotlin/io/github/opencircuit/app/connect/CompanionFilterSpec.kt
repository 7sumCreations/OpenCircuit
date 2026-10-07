package io.github.opencircuit.app.connect

import io.github.opencircuit.ble.RememberedRing
import java.util.Locale

/**
 * What the companion-device request filters on, decided without Android types so the JVM tests
 * can read it; [AndroidCompanionPort] turns it into a `BluetoothLeDeviceFilter` (an LE scan).
 *
 * The ring's address, and nothing else. No name pattern: Android's
 * `BluetoothLeDeviceFilter.matches` checks a name pattern against `BluetoothDevice.getName()`, the
 * Bluetooth stack's cached remote name, not the name in the advertisement the app's own scan read
 * (`BluetoothDeviceFilterUtils.matchesName`). For a ring the phone has only ever seen in an LE scan
 * that cache can stay empty, so the filter never matched and the sheet sat on "Looking for a
 * device". PORTING D-258.
 *
 * The public `ScanFilter.Builder.setDeviceAddress(String)` assumes a public address (the overload
 * that takes the type is a system API); a Gen 2 advertises a public one.
 */
data class CompanionFilterSpec(
    /** The ring's address, upper-case, for `ScanFilter.Builder.setDeviceAddress`. */
    val address: String,
)

object CompanionFilter {
    fun specFor(ring: RememberedRing): CompanionFilterSpec =
        CompanionFilterSpec(ring.address.uppercase(Locale.ROOT))
}
