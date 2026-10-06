package io.github.opencircuit.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Android's raw bond state code mapped to the link's [GattPort.BondState]. A state that cannot be
 * read (no device) or a code Android does not define throws, so the link treats it as a failed
 * read (PORTING.md D-187: close and reconnect) instead of "not bonded", which would ask Android
 * to bond and show a pairing failure the user cannot fix.
 */
class BondStateCodeTest {

    @Test
    fun eachAndroidCodeMapsToItsState() {
        assertEquals(GattPort.BondState.BONDED, bondStateFromCode(12))
        assertEquals(GattPort.BondState.BONDING, bondStateFromCode(11))
        assertEquals(GattPort.BondState.NONE, bondStateFromCode(10))
    }

    @Test
    fun anUnreadableBondStateThrowsInsteadOfReadingAsNotBonded() {
        assertFailsWith<IllegalStateException> { bondStateFromCode(null) }
    }

    @Test
    fun aCodeAndroidDoesNotDefineThrowsInsteadOfReadingAsNotBonded() {
        assertFailsWith<IllegalStateException> { bondStateFromCode(13) }
        assertFailsWith<IllegalStateException> { bondStateFromCode(Int.MIN_VALUE) }
    }

    /** Pinned to literals typed here: `BluetoothDevice.BOND_NONE` / `BOND_BONDING` / `BOND_BONDED`. */
    @Test
    fun theCodesAreAndroidsPublicConstants() {
        assertEquals(10, BondStateCode.NONE)
        assertEquals(11, BondStateCode.BONDING)
        assertEquals(12, BondStateCode.BONDED)
    }
}
