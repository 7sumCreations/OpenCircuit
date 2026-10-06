package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ble.ScanUpdate
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Scan & connect tap with Nearby devices granted and Bluetooth on: each thing the scanner
 * reports becomes the Ring screen's own words and action (upstream's connect control, no-ring
 * card and picker, `ios/OpenCircuit/ContentView.swift:1267-1336, 1353-1406` @ b1c2fdd).
 *
 * The scanner's contract drives the collection: Selected, NoRingFound and Failed end it; after
 * Choose the scan goes on, a later ring arrives as Found then Choose again and the picker shows
 * it, and the collection ends only when the user picks a ring or cancels.
 */
class ScanStateTest {

    private val alpha = nearby("RingConn Alpha", 1)
    private val zulu = nearby("RingConn Zulu", 2)
    private val mike = nearby("RingConn Mike", 3)

    @Test
    fun whileScanningTheCardSaysSearchingAndOffersCancel() = runTest {
        val screen = connectScreen(ScriptedScanner { emit(ScanUpdate.Found(listOf(alpha))); awaitCancellation() })

        screen.flow.requestScan(granted)
        runCurrent()

        assertEquals("Searching for ring…", screen.link.headline)
        assertEquals(LinkAction.CANCEL_SCAN, screen.link.action)
        assertTrue(screen.link.searching)
        assertEquals(1, screen.scanner.running)
    }

    @Test
    fun oneRingSelectedIsConnectedOnceAndTheScanEnds() = runTest {
        val screen = connectScreen(
            ScriptedScanner {
                emit(ScanUpdate.Found(listOf(alpha)))
                delay(2_500)
                emit(ScanUpdate.Selected(alpha))
            },
        )

        screen.flow.requestScan(granted)
        advanceTo(2_499)
        assertTrue(screen.connected.isEmpty())

        advanceTo(2_500)
        assertEquals(0, screen.scanner.running)
        assertEquals("Pair with RingConn Alpha", screen.link.headline, "pairing is explained before it is asked for")
        assertTrue(screen.connected.isEmpty())

        screen.approvePairing()
        runCurrent()

        assertEquals(listOf(alpha), screen.connected)
        assertEquals(listOf(alpha), screen.companion.requests)
        assertEquals("Ready", screen.link.headline, "the link's own state shows next")
    }

    @Test
    fun severalRingsShowThePickerWithTheSavedRingFirstThenByNameNotInTheOrderFound() = runTest {
        val values = InMemoryKeyValues()
        // The saved ring is stored upper-case; the scan reports it in another case.
        PrefsRememberedRingStore(values).save(mike.copy(address = mike.address))
        val mikeLowerCase = mike.copy(address = mike.address.lowercase(java.util.Locale.ROOT))
        val unnamed = nearby(null, 4)
        val foundOrder = listOf(zulu, mikeLowerCase, alpha, unnamed)
        val screen = connectScreen(ScriptedScanner { emit(ScanUpdate.Found(foundOrder)); emit(ScanUpdate.Choose(foundOrder)); awaitCancellation() }, values)

        screen.flow.requestScan(granted)
        runCurrent()

        assertEquals("Multiple rings found — pick one", screen.link.headline)
        val choices = screen.link.choices
        assertEquals(listOf("RingConn Mike", "RingConn", "RingConn Alpha", "RingConn Zulu"), choices.map { it.label })
        assertEquals(listOf(true, false, false, false), choices.map { it.lastUsed })
        assertEquals(LinkAction.CANCEL_SCAN, screen.link.action)
    }

    @Test
    fun theScanGoesOnWhileThePickerShowsAndALaterRingJoinsTheList() = runTest {
        val screen = connectScreen(
            ScriptedScanner {
                emit(ScanUpdate.Found(listOf(zulu, alpha)))
                emit(ScanUpdate.Choose(listOf(zulu, alpha)))
                delay(5_000)
                emit(ScanUpdate.Found(listOf(zulu, alpha, mike)))
                emit(ScanUpdate.Choose(listOf(zulu, alpha, mike)))
                awaitCancellation()
            },
        )
        screen.flow.requestScan(granted)
        runCurrent()
        assertEquals(listOf("RingConn Alpha", "RingConn Zulu"), screen.link.choices.map { it.label })
        assertEquals(1, screen.scanner.running, "Choose does not end the scan")

        advanceTo(5_000)

        assertEquals(listOf("RingConn Alpha", "RingConn Mike", "RingConn Zulu"), screen.link.choices.map { it.label })
        assertEquals(1, screen.scanner.running)
        assertTrue(screen.connected.isEmpty())
    }

    @Test
    fun pickingARingStopsTheScanAndConnectsThatRing() = runTest {
        val screen = connectScreen(ScriptedScanner { emit(ScanUpdate.Choose(listOf(zulu, alpha))); awaitCancellation() })
        screen.flow.requestScan(granted)
        runCurrent()

        screen.viewModel.onAction(RingAction.Pick(screen.link.choices.first { it.label == "RingConn Zulu" }.ring))
        runCurrent()
        assertEquals(0, screen.scanner.running)
        assertEquals(1, screen.scanner.cancelled)
        assertTrue(screen.link.choices.isEmpty())
        assertEquals("Pair with RingConn Zulu", screen.link.headline)

        screen.approvePairing()
        runCurrent()

        assertEquals(listOf(zulu), screen.connected)
    }

    @Test
    fun cancelWhileChoosingStopsTheScanAndConnectsNothing() = runTest {
        val screen = connectScreen(ScriptedScanner { emit(ScanUpdate.Choose(listOf(zulu, alpha))); awaitCancellation() })
        screen.flow.requestScan(granted)
        runCurrent()

        screen.viewModel.onAction(RingAction.Link(LinkAction.CANCEL_SCAN))
        runCurrent()

        assertTrue(screen.connected.isEmpty())
        assertEquals(1, screen.scanner.cancelled)
        assertEquals("Ready", screen.link.headline)
        assertEquals(LinkAction.SCAN_AND_CONNECT, screen.link.action)
    }

    @Test
    fun aRingThatIsNotOnTheListCannotBePicked() = runTest {
        val screen = connectScreen(ScriptedScanner { emit(ScanUpdate.Choose(listOf(zulu, alpha))); awaitCancellation() })
        screen.flow.requestScan(granted)
        runCurrent()

        screen.viewModel.onAction(RingAction.Pick(mike))
        runCurrent()

        assertTrue(screen.connected.isEmpty())
        assertEquals(1, screen.scanner.running)
        assertEquals("Multiple rings found — pick one", screen.link.headline)
    }

    @Test
    fun noRingFoundGivesTheHintsAndSearchAgainButNoSavedRingHintWithoutASavedRing() = runTest {
        val screen = connectScreen(ScriptedScanner { delay(15_000); emit(ScanUpdate.NoRingFound) })
        screen.flow.requestScan(granted)

        advanceTo(15_000)

        assertEquals("No ring found", screen.link.headline)
        assertEquals(LinkAction.SEARCH_AGAIN, screen.link.action)
        assertEquals(
            listOf(
                "Take the ring out of its charging case and put it on.",
                "Force-stop the official RingConn app — it can hold the Bluetooth connection.",
                "Keep the ring within a few feet of your phone.",
            ),
            screen.link.hints,
        )
        assertEquals(0, screen.scanner.running)
    }

    @Test
    fun noRingFoundAddsTheSavedRingHintWhenARingIsSaved() = runTest {
        val values = InMemoryKeyValues()
        PrefsRememberedRingStore(values).save(alpha)
        val screen = connectScreen(ScriptedScanner { emit(ScanUpdate.NoRingFound) }, values)
        screen.flow.requestScan(granted)
        runCurrent()

        assertEquals(4, screen.link.hints.size)
        assertEquals("A ring you've paired before reconnects automatically once it's back in range.", screen.link.hints.last())
    }

    @Test
    fun searchAgainStartsAFreshScanOnTheSameController() = runTest {
        var round = 0
        val screen = connectScreen(
            ScriptedScanner {
                round++
                if (round == 1) emit(ScanUpdate.NoRingFound) else emit(ScanUpdate.Found(listOf(alpha))).also { awaitCancellation() }
            },
        )
        screen.flow.requestScan(granted)
        runCurrent()
        assertEquals("No ring found", screen.link.headline)

        screen.flow.requestScan(granted)
        runCurrent()

        assertEquals(2, screen.scanner.scans)
        assertEquals("Searching for ring…", screen.link.headline)
        assertTrue(screen.link.hints.isEmpty(), "the last scan's hints do not carry over")
    }

    @Test
    fun aSecondTapWhileScanningStartsNoSecondScan() = runTest {
        val screen = connectScreen(ScriptedScanner { emit(ScanUpdate.Found(emptyList())); awaitCancellation() })

        screen.flow.requestScan(granted)
        runCurrent()
        screen.flow.requestScan(granted)
        runCurrent()

        assertEquals(1, screen.scanner.scans)
    }

    @Test
    fun aScanAndroidRefusedToStartSaysSo() = runTest {
        val screen = connectScreen(ScriptedScanner { emit(ScanUpdate.Failed(-1)) })
        screen.flow.requestScan(granted)
        runCurrent()

        assertEquals("Couldn't search for rings", screen.link.headline)
        assertEquals(
            "Android didn't start the scan. Check that Bluetooth is on and Nearby devices is allowed, then search again.",
            screen.link.detail,
        )
        assertEquals(LinkAction.SEARCH_AGAIN, screen.link.action)
    }

    @Test
    fun aScanErrorShowsItsCode() = runTest {
        val screen = connectScreen(ScriptedScanner { delay(1_000); emit(ScanUpdate.Failed(2)) })
        screen.flow.requestScan(granted)
        advanceTo(1_000)

        assertEquals("Couldn't search for rings", screen.link.headline)
        assertEquals("Android reported a scan error (code 2). Search again.", screen.link.detail)
    }

    @Test
    fun aScannerThatThrowsIsShownAsAFailedScanAndLogged() = runTest {
        val screen = connectScreen(ScriptedScanner { throw IllegalStateException("boom") })
        screen.flow.requestScan(granted)
        runCurrent()

        assertEquals("Couldn't search for rings", screen.link.headline)
        assertEquals(1, screen.logLines.count { "scan" in it.lowercase(java.util.Locale.ROOT) }, screen.logLines.toString())
    }

    @Test
    fun aScanThatEndsWithoutAnAnswerReadsAsNoRingFound() = runTest {
        val screen = connectScreen(ScriptedScanner { emit(ScanUpdate.Found(listOf(alpha))) })
        screen.flow.requestScan(granted)
        runCurrent()

        assertEquals("No ring found", screen.link.headline)
        assertTrue(screen.connected.isEmpty())
    }

    @Test
    fun theScanCardShowsEvenWithoutARingSession() = runTest {
        val screen = connectScreen(ScriptedScanner { awaitCancellation() })
        assertNull(screen.viewModel.uiState.value.card.ringName)
        assertFalse(screen.link.searching)

        screen.flow.requestScan(granted)
        runCurrent()

        assertTrue(screen.link.searching)
    }
}
