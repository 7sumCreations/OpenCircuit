package io.github.opencircuit.ble

/**
 * Android's bond state codes, as `android.bluetooth.BluetoothDevice` defines them
 * (`BOND_NONE`, `BOND_BONDING`, `BOND_BONDED`): public constants that do not change between
 * releases. Kept here so the mapping in [bondStateFromCode] runs on the JVM.
 */
internal object BondStateCode {
    /** `BluetoothDevice.BOND_NONE`. */
    const val NONE: Int = 10

    /** `BluetoothDevice.BOND_BONDING`. */
    const val BONDING: Int = 11

    /** `BluetoothDevice.BOND_BONDED`. */
    const val BONDED: Int = 12
}

/**
 * The bond state for Android's raw [code] (`BluetoothDevice.getBondState()`), null when there is
 * no device to read it from.
 *
 * @throws IllegalStateException when [code] is null or not one of the three codes Android
 *   defines. The state is unknown then, not "not bonded": the link counts a throwing read as a
 *   failed operation and reconnects (PORTING.md D-187), where "not bonded" would ask to bond.
 */
internal fun bondStateFromCode(code: Int?): GattPort.BondState =
    bondStateFromCodeOrNull(code)
        ?: throw IllegalStateException(if (code == null) "bond state unreadable: no device" else "bond state unknown: $code")

/**
 * The bond state for Android's raw [code], or null when [code] is missing or not one of the three
 * codes Android defines. A bond broadcast with such a state is ignored: it says nothing about the
 * bond, and reading it as "not bonded" would fail a pairing in progress.
 */
internal fun bondStateFromCodeOrNull(code: Int?): GattPort.BondState? = when (code) {
    BondStateCode.BONDED -> GattPort.BondState.BONDED
    BondStateCode.BONDING -> GattPort.BondState.BONDING
    BondStateCode.NONE -> GattPort.BondState.NONE
    else -> null
}
