package io.github.opencircuit.app

import io.github.opencircuit.app.connect.PermissionMapper
import io.github.opencircuit.app.connect.PermissionSnapshot
import io.github.opencircuit.app.connect.ScanGate
import io.github.opencircuit.app.connect.ScanStep
import io.github.opencircuit.app.connect.adapterStateOf
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.BluetoothPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android's permission state mapped into `:ble`'s four-value [BluetoothPermission], and the
 * step a Scan & connect tap takes from it and the adapter.
 *
 * Android cannot tell "never asked" from "refused for good" (the rationale is false for both), so
 * the app's own "asked" flag decides [BluetoothPermission.NOT_DETERMINED]; a partial grant (one of
 * the two permissions) is not a grant; a device policy that forbids Bluetooth is
 * [BluetoothPermission.RESTRICTED]. The step uses `:ble`'s real `BTAvailability.of`.
 */
class PermissionMapperTest {

    private fun snapshot(scan: Boolean = false, connect: Boolean = false, rationale: Boolean = false, restricted: Boolean = false) =
        PermissionSnapshot(scanGranted = scan, connectGranted = connect, rationale = rationale, restricted = restricted)

    @Test
    fun neverAskedIsNotDeterminedAndCanBeAsked() {
        val s = snapshot()
        assertEquals(BluetoothPermission.NOT_DETERMINED, PermissionMapper.permission(s, everAsked = false))
        assertTrue(PermissionMapper.canAskAgain(s, everAsked = false))
    }

    @Test
    fun askedAndRefusedOnceIsDeniedAndCanBeAskedAgain() {
        val s = snapshot(rationale = true)
        assertEquals(BluetoothPermission.DENIED, PermissionMapper.permission(s, everAsked = true))
        assertTrue(PermissionMapper.canAskAgain(s, everAsked = true))
    }

    @Test
    fun askedAndRefusedForGoodIsDeniedAndCannotBeAskedAgain() {
        val s = snapshot(rationale = false)
        assertEquals(BluetoothPermission.DENIED, PermissionMapper.permission(s, everAsked = true))
        assertFalse(PermissionMapper.canAskAgain(s, everAsked = true))
    }

    @Test
    fun bothGrantedIsGrantedWhateverTheFlag() {
        for (asked in listOf(false, true)) {
            assertEquals(BluetoothPermission.GRANTED, PermissionMapper.permission(snapshot(scan = true, connect = true), asked))
        }
    }

    @Test
    fun aPartialGrantIsNotAGrant() {
        assertEquals(BluetoothPermission.DENIED, PermissionMapper.permission(snapshot(scan = true), everAsked = true))
        assertEquals(BluetoothPermission.DENIED, PermissionMapper.permission(snapshot(connect = true), everAsked = true))
        assertEquals(BluetoothPermission.NOT_DETERMINED, PermissionMapper.permission(snapshot(scan = true), everAsked = false))
    }

    @Test
    fun aDevicePolicyRestrictsEvenWhenGrantedAndCannotBeAsked() {
        for (granted in listOf(false, true)) for (asked in listOf(false, true)) {
            val s = snapshot(scan = granted, connect = granted, rationale = true, restricted = true)
            assertEquals(BluetoothPermission.RESTRICTED, PermissionMapper.permission(s, asked))
            assertFalse(PermissionMapper.canAskAgain(s, asked))
        }
    }

    @Test
    fun aGrantedPermissionIsNotAskedForAgain() {
        assertFalse(PermissionMapper.canAskAgain(snapshot(scan = true, connect = true, rationale = true), everAsked = true))
    }

    @Test
    fun theTapStepFollowsPermissionThenAdapter() {
        // Through :ble's BTAvailability.of: GRANTED + OFF is the only "Bluetooth off".
        assertEquals(ScanStep.BLOCKED, ScanGate.step(BluetoothPermission.RESTRICTED, canAskAgain = false, adapter = AdapterState.ON))
        assertEquals(ScanStep.ASK_PERMISSION, ScanGate.step(BluetoothPermission.NOT_DETERMINED, canAskAgain = true, adapter = AdapterState.OFF))
        assertEquals(ScanStep.ASK_PERMISSION, ScanGate.step(BluetoothPermission.DENIED, canAskAgain = true, adapter = AdapterState.ON))
        assertEquals(ScanStep.NOT_ALLOWED, ScanGate.step(BluetoothPermission.DENIED, canAskAgain = false, adapter = AdapterState.ON))
        assertEquals(ScanStep.BLUETOOTH_OFF, ScanGate.step(BluetoothPermission.GRANTED, canAskAgain = false, adapter = AdapterState.OFF))
        assertEquals(ScanStep.SCAN, ScanGate.step(BluetoothPermission.GRANTED, canAskAgain = false, adapter = AdapterState.ON))
    }

    @Test
    fun aTurningOrUnreadAdapterLetsTheScanGoAhead() {
        // BTAvailability.of reads TURNING_ON / TURNING_OFF / not read yet as READY: the scan then
        // reports its own failure if Bluetooth is not on.
        for (adapter in listOf(AdapterState.TURNING_ON, AdapterState.TURNING_OFF, null)) {
            assertEquals(ScanStep.SCAN, ScanGate.step(BluetoothPermission.GRANTED, canAskAgain = false, adapter = adapter), "$adapter")
        }
    }

    @Test
    fun theAdapterBroadcastCodesMapToTheFourStates() {
        // BluetoothAdapter.STATE_OFF / TURNING_ON / ON / TURNING_OFF, typed from the platform's
        // documented values rather than read from the constants.
        assertEquals(AdapterState.OFF, adapterStateOf(10))
        assertEquals(AdapterState.TURNING_ON, adapterStateOf(11))
        assertEquals(AdapterState.ON, adapterStateOf(12))
        assertEquals(AdapterState.TURNING_OFF, adapterStateOf(13))
        assertNull(adapterStateOf(14), "an unknown code is not a state")
        assertNull(adapterStateOf(-1), "a missing extra is not a state")
    }
}
