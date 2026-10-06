package io.github.opencircuit.app

import io.github.opencircuit.app.connect.PermissionSnapshot
import io.github.opencircuit.app.connect.ScanStep
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.ScanUpdate
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first Scan & connect tap, in order: a device policy stops it; otherwise Android's
 * Nearby-devices dialog is shown (never by onboarding, never twice on its own); a refusal for
 * good offers Settings and "Ask again"; Bluetooth off offers to turn it on; only then a scan.
 * The "asked" flag is saved when the dialog answers, and every resume re-reads the permission
 * (a grant made in Settings has no callback).
 */
class FirstTapSequenceTest {

    private val refusedOnce = PermissionSnapshot(scanGranted = false, connectGranted = false, rationale = true, restricted = false)
    private val refusedForGood = PermissionSnapshot(scanGranted = false, connectGranted = false, rationale = false, restricted = false)
    private val restricted = PermissionSnapshot(scanGranted = true, connectGranted = true, rationale = false, restricted = true)

    private fun scanner() = ScriptedScanner { emit(ScanUpdate.Found(emptyList())); awaitCancellation() }

    @Test
    fun beforeAnyTapTheCardOffersScanAndConnect() = runTest {
        val screen = connectScreen(scanner())
        screen.flow.onResume(nothingGranted)
        runCurrent()

        assertEquals("Ready", screen.link.headline)
        assertEquals(LinkAction.SCAN_AND_CONNECT, screen.link.action)
    }

    @Test
    fun theFirstTapAsksForNearbyDevicesAndScansNothingYet() = runTest {
        val screen = connectScreen(scanner())

        assertEquals(ScanStep.ASK_PERMISSION, screen.flow.requestScan(nothingGranted))
        runCurrent()

        assertEquals(0, screen.scanner.scans)
        assertNull(screen.values.raw("bt.permission.asked.v1"), "saved when the dialog answers, not before")
    }

    @Test
    fun aGrantSavesTheAskedFlagAndScans() = runTest {
        val screen = connectScreen(scanner())
        screen.flow.requestScan(nothingGranted)

        screen.flow.onPermissionResult(granted)
        runCurrent()

        assertEquals(true, screen.values.raw("bt.permission.asked.v1"))
        assertEquals(1, screen.scanner.scans)
        assertEquals("Searching for ring…", screen.link.headline)
    }

    @Test
    fun aRefusalSavesTheFlagOffersToAllowAndDoesNotAskAgainByItself() = runTest {
        val screen = connectScreen(scanner())
        screen.flow.requestScan(nothingGranted)

        screen.flow.onPermissionResult(refusedOnce)
        runCurrent()

        assertEquals(true, screen.values.raw("bt.permission.asked.v1"))
        assertEquals(0, screen.scanner.scans)
        assertEquals("Bluetooth not allowed", screen.link.headline)
        assertEquals(LinkAction.ALLOW_NEARBY, screen.link.action)
        assertTrue("Nearby devices" in screen.link.detail.orEmpty(), screen.link.detail)
        assertEquals(ScanStep.ASK_PERMISSION, screen.flow.requestScan(refusedOnce), "the next tap may ask again")
    }

    @Test
    fun aRefusalForGoodOffersSettingsAndAskAgainAndTheTapNoLongerAsks() = runTest {
        val values = InMemoryKeyValues().apply { putRaw("bt.permission.asked.v1", true) }
        val screen = connectScreen(scanner(), values)

        assertEquals(ScanStep.NOT_ALLOWED, screen.flow.requestScan(refusedForGood))
        runCurrent()

        assertEquals("Bluetooth not allowed", screen.link.headline)
        assertEquals(
            "OpenCircuit needs Nearby devices to find your ring. Allow it in Settings › Apps › OpenCircuit › Permissions, " +
                "then come back and tap Scan & connect.",
            screen.link.detail,
        )
        assertEquals(LinkAction.OPEN_APP_SETTINGS, screen.link.action)
        assertEquals(LinkAction.ASK_AGAIN, screen.link.secondary)
        assertEquals(0, screen.scanner.scans)
    }

    @Test
    fun aDismissedDialogWithNoRationaleReadsAsRefusedForGood() = runTest {
        val screen = connectScreen(scanner())
        screen.flow.requestScan(nothingGranted)

        screen.flow.onPermissionResult(refusedForGood)
        runCurrent()

        assertEquals(LinkAction.OPEN_APP_SETTINGS, screen.link.action)
        assertEquals(LinkAction.ASK_AGAIN, screen.link.secondary, "Ask again covers a dialog that was only dismissed")
    }

    @Test
    fun aDevicePolicyBlocksTheTapWithNoActionAndNoDialog() = runTest {
        val screen = connectScreen(scanner())

        assertEquals(ScanStep.BLOCKED, screen.flow.requestScan(restricted))
        runCurrent()

        assertEquals("Bluetooth is blocked by a device policy", screen.link.headline)
        assertNull(screen.link.action)
        assertNull(screen.link.secondary)
        assertEquals(0, screen.scanner.scans)
    }

    @Test
    fun bluetoothOffOffersToTurnItOnAndScansNothing() = runTest {
        val values = InMemoryKeyValues().apply { putRaw("bt.permission.asked.v1", true) }
        val screen = connectScreen(scanner(), values, adapter = AdapterState.OFF)

        assertEquals(ScanStep.BLUETOOTH_OFF, screen.flow.requestScan(granted))
        runCurrent()

        assertEquals("Bluetooth off", screen.link.headline)
        assertEquals(LinkAction.TURN_ON_BLUETOOTH, screen.link.action)
        assertEquals(0, screen.scanner.scans)
    }

    @Test
    fun theCardFollowsTheAdapterLive() = runTest {
        val screen = connectScreen(scanner(), adapter = AdapterState.ON)
        screen.flow.onResume(granted)
        runCurrent()
        assertEquals("Ready", screen.link.headline)

        screen.adapter.value = AdapterState.OFF
        runCurrent()
        assertEquals("Bluetooth off", screen.link.headline)

        screen.adapter.value = AdapterState.ON
        runCurrent()
        assertEquals("Ready", screen.link.headline)
        assertEquals(0, screen.scanner.scans, "the adapter coming back does not start a scan on its own")
    }

    @Test
    fun turningBluetoothOnFromTheCardGoesOnToTheScan() = runTest {
        val screen = connectScreen(scanner(), adapter = AdapterState.OFF)
        screen.flow.requestScan(granted)

        screen.adapter.value = AdapterState.ON
        screen.flow.onBluetoothEnabled(granted)
        runCurrent()

        assertEquals(1, screen.scanner.scans)
    }

    @Test
    fun aGrantMadeInSettingsShowsOnResume() = runTest {
        val values = InMemoryKeyValues().apply { putRaw("bt.permission.asked.v1", true) }
        val screen = connectScreen(scanner(), values)
        screen.flow.onResume(refusedForGood)
        runCurrent()
        assertEquals("Bluetooth not allowed", screen.link.headline)

        screen.flow.onResume(granted)
        runCurrent()

        assertEquals("Ready", screen.link.headline)
        assertEquals(0, screen.scanner.scans, "a resume never scans by itself")
    }

    @Test
    fun aFlagThatCannotBeSavedIsLoggedAndRememberedForTheSession() = runTest {
        val values = InMemoryKeyValues()
        val screen = connectScreen(scanner(), values)
        screen.flow.requestScan(nothingGranted)
        values.failWrites = true

        screen.flow.onPermissionResult(refusedForGood)
        runCurrent()

        assertEquals(1, screen.logLines.size, screen.logLines.toString())
        assertEquals(ScanStep.NOT_ALLOWED, screen.flow.requestScan(refusedForGood), "this session knows it asked")
        assertEquals(LinkAction.OPEN_APP_SETTINGS, screen.link.action)
        assertFalse(screen.link.searching)
    }
}
