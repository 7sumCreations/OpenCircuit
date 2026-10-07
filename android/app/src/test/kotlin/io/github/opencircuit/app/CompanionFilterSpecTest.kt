package io.github.opencircuit.app

import io.github.opencircuit.app.connect.CompanionFilter
import io.github.opencircuit.app.connect.CompanionFilterSpec
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.RememberedRing
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The companion-device sheet's filter must match the ring the app's own scan found, or the sheet
 * sits on "Looking for a device" for good. Android's `BluetoothLeDeviceFilter.matches` applies a
 * name pattern to `BluetoothDevice.getName()` (the stack's cached remote name), not to the name in
 * the advertisement the app's scan read. So the filter names the ring by its address only.
 */
class CompanionFilterSpecTest {

    @Test
    fun aNamedRingIsFilteredByItsAddressOnlyNeverByItsName() {
        val ring = RememberedRing("aa:bb:cc:dd:ee:ff", AddressType.PUBLIC, "RingConn Gen2 TEST")

        val spec = CompanionFilter.specFor(ring)

        // The whole spec: no name pattern, which Android would match against the stack's cached name.
        assertEquals(CompanionFilterSpec("AA:BB:CC:DD:EE:FF"), spec)
    }
}
