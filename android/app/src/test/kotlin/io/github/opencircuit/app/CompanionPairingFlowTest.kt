package io.github.opencircuit.app

import io.github.opencircuit.app.connect.AssociationCallback
import io.github.opencircuit.app.connect.CompanionAssociation
import io.github.opencircuit.app.connect.CompanionPairing
import io.github.opencircuit.app.connect.CompanionPort
import io.github.opencircuit.app.connect.ConnectFlowController
import io.github.opencircuit.app.connect.FallbackReason
import io.github.opencircuit.app.connect.PairingOutcome
import io.github.opencircuit.app.connect.PairingSheet
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.ble.AdapterState
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.ScanUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pairing a ring the scan chose, through Android's companion-device manager (CDM): the app says
 * first what Android's sheet is for, removes stale associations for the ring's address (unless the
 * phone is already bonded to it), asks CDM for exactly this ring, and acts on the result code the
 * sheet returns. Allowed: remember the ring and connect. Refused or closed: nothing is remembered
 * and nothing connects. Anything else (no CDM on the phone, a failure before the sheet, a
 * discovery timeout, an internal error): remember and connect anyway, and keep the reason.
 * No upstream equivalent (iOS bonds without one); see PORTING D-250.
 *
 * CDM is faked at its seam ([CompanionPort]); the result codes are the platform's, typed here as
 * the numbers Android documents (`RESULT_OK` -1, `RESULT_CANCELED` 0, `RESULT_USER_REJECTED` 1,
 * `RESULT_DISCOVERY_TIMEOUT` 2, `RESULT_INTERNAL_ERROR` 3, `RESULT_SECURITY_ERROR` 4).
 */
class CompanionPairingFlowTest {

    private val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "RingConn Gen2 TEST")
    private val otherRing = RememberedRing("AA:BB:CC:DD:EE:00", AddressType.RANDOM, "RingConn Gen2 OTHR")

    private class Screen(
        val port: FakeCompanionPort,
        val pairing: CompanionPairing,
        val under: SessionsUnderTest,
        val viewModel: RingViewModel,
        val logLines: MutableList<String>,
    ) {
        val card get() = viewModel.uiState.value.card.link
        val remembered get() = under.store.load()
        val built get() = under.factory.built

        fun tap(action: LinkAction) = viewModel.onAction(RingAction.Link(action))
    }

    /** A Scan & connect that selects [selected] at once, with CDM as [port]. */
    private fun TestScope.screen(port: FakeCompanionPort = FakeCompanionPort(), selected: RememberedRing = ring): Screen {
        val logLines = mutableListOf<String>()
        val under = sessionsUnderTest()
        val pairing = CompanionPairing(port) { logLines += it }
        val flow = ConnectFlowController(
            prefs = PrefsAppPrefs(under.values),
            rings = PrefsRememberedRingStore(under.values),
            scanners = { ScriptedScanner { emit(ScanUpdate.Found(listOf(selected))); emit(ScanUpdate.Selected(selected)) } },
            connector = under.sessions,
            adapterState = MutableStateFlow(AdapterState.ON),
            scope = backgroundScope,
            log = { logLines += it },
            pairing = pairing,
            monotonicMillis = { testScheduler.currentTime },
        )
        val viewModel = RingViewModel(under.sessions, title = "Ring", scope = backgroundScope, connectFlow = flow)
        runCurrent()
        flow.requestScan(granted)
        runCurrent()
        return Screen(port, pairing, under, viewModel, logLines)
    }

    /** Continue past the app's explanation, and the system sheet comes up and is shown. */
    private fun TestScope.continueToSheet(screen: Screen) {
        screen.tap(LinkAction.CONTINUE_PAIRING)
        runCurrent()
        screen.port.showSheet()
        runCurrent()
        assertNotNull(screen.viewModel.pairingSheet.value, "the activity is asked to show the sheet")
        screen.viewModel.onPairingSheetShown()
        runCurrent()
    }

    private fun TestScope.sheetAnswers(screen: Screen, resultCode: Int) {
        screen.viewModel.onPairingSheetResult(resultCode)
        runCurrent()
    }

    @Test
    fun aChosenRingIsExplainedFirstAndNothingIsAskedOrRememberedYet() = runTest {
        val screen = screen()

        assertEquals("Pair with RingConn Gen2 TEST", screen.card.headline)
        assertTrue(screen.card.detail!!.contains("Android will ask"), screen.card.detail)
        assertEquals(LinkAction.CONTINUE_PAIRING, screen.card.action)
        assertEquals(LinkAction.CANCEL_SCAN, screen.card.secondary)
        assertTrue(screen.port.requests.isEmpty(), "the sheet opens only after Continue")
        assertNull(screen.remembered)
        assertTrue(screen.built.isEmpty())
    }

    @Test
    fun continueAsksCdmForExactlyThisRing() = runTest {
        val screen = screen()

        screen.tap(LinkAction.CONTINUE_PAIRING)
        runCurrent()

        assertEquals(listOf(ring), screen.port.requests)
        assertEquals("Waiting for Android's pairing sheet…", screen.card.headline)
        assertNull(screen.viewModel.pairingSheet.value, "no sheet until CDM has one")
    }

    @Test
    fun allowedRemembersTheRingAndConnectsIt() = runTest {
        val screen = screen()
        continueToSheet(screen)
        assertNull(screen.viewModel.pairingSheet.value, "a shown sheet is not shown twice")

        sheetAnswers(screen, -1)

        assertEquals(ring, screen.remembered)
        assertEquals(1, screen.built.single().fake.connectCalls)
        assertEquals(PairingOutcome.Approved, screen.pairing.lastOutcome.value)
    }

    @Test
    fun refusedOrClosedRemembersNothingAndConnectsNothing() = runTest {
        for (code in listOf(1, 0)) {
            val screen = screen()
            continueToSheet(screen)

            sheetAnswers(screen, code)

            assertEquals("Pairing cancelled", screen.card.headline, "result $code")
            assertEquals(LinkAction.SCAN_AND_CONNECT, screen.card.action)
            assertNull(screen.remembered, "result $code")
            assertTrue(screen.built.isEmpty(), "result $code")
            assertEquals(PairingOutcome.Declined(code), screen.pairing.lastOutcome.value)
        }
    }

    @Test
    fun aCancelledSheetStillOffersScanAndConnectAndAlsoPairingWithoutTheSheet() = runTest {
        val screen = screen()
        continueToSheet(screen)

        sheetAnswers(screen, 1)

        assertEquals(LinkAction.SCAN_AND_CONNECT, screen.card.action)
        assertEquals(LinkAction.PAIR_WITHOUT_SHEET, screen.card.secondary)
        assertEquals("Pair without the system sheet", LinkAction.PAIR_WITHOUT_SHEET.label)
    }

    @Test
    fun pairingWithoutTheSheetRemembersAndConnectsTheRingAndRecordsThatTheUserChoseIt() = runTest {
        for (code in listOf(1, 0)) {
            val screen = screen()
            continueToSheet(screen)
            sheetAnswers(screen, code)

            screen.tap(LinkAction.PAIR_WITHOUT_SHEET)
            runCurrent()

            assertEquals(ring, screen.remembered, "result $code")
            assertEquals(1, screen.built.single().fake.connectCalls, "result $code")
            assertEquals(PairingOutcome.Fallback(FallbackReason.USER_CHOSE_PLAIN_BOND, resultCode = code), screen.pairing.lastOutcome.value)
            assertEquals(listOf(ring), screen.port.requests, "the sheet is not asked for again")
            assertFalse(screen.card.headline == "Pairing cancelled", "the card leaves the cancelled state")
        }
    }

    @Test
    fun pairingWithoutTheSheetDoesNothingUnlessThePairingWasCancelled() = runTest {
        // Before the sheet answered (the explanation, then waiting for the sheet), and after Cancel.
        val screen = screen()
        screen.tap(LinkAction.PAIR_WITHOUT_SHEET)
        runCurrent()
        continueToSheet(screen)
        screen.tap(LinkAction.PAIR_WITHOUT_SHEET)
        runCurrent()
        screen.tap(LinkAction.CANCEL_SCAN)
        runCurrent()
        screen.tap(LinkAction.PAIR_WITHOUT_SHEET)
        runCurrent()

        assertNull(screen.remembered)
        assertTrue(screen.built.isEmpty())
        assertNull(screen.pairing.lastOutcome.value)
    }

    @Test
    fun pairingWithoutTheSheetConnectsOnlyOnceForTwoTaps() = runTest {
        val screen = screen()
        continueToSheet(screen)
        sheetAnswers(screen, 1)

        screen.tap(LinkAction.PAIR_WITHOUT_SHEET)
        runCurrent()
        screen.tap(LinkAction.PAIR_WITHOUT_SHEET)
        runCurrent()

        assertEquals(1, screen.built.single().fake.connectCalls)
    }

    @Test
    fun aSheetThatFailsRemembersAndConnectsAnywayAndKeepsTheReason() = runTest {
        val expected = mapOf(
            2 to FallbackReason.DISCOVERY_TIMEOUT,
            3 to FallbackReason.INTERNAL_ERROR,
            4 to FallbackReason.SECURITY_ERROR,
            77 to FallbackReason.UNKNOWN_RESULT,
        )
        for ((code, reason) in expected) {
            val screen = screen()
            continueToSheet(screen)

            sheetAnswers(screen, code)

            assertEquals(ring, screen.remembered, "result $code")
            assertEquals(1, screen.built.single().fake.connectCalls, "result $code")
            assertEquals(PairingOutcome.Fallback(reason, resultCode = code), screen.pairing.lastOutcome.value)
        }
    }

    @Test
    fun aFailureBeforeTheSheetConnectsAnyway() = runTest {
        val screen = screen()
        screen.tap(LinkAction.CONTINUE_PAIRING)
        runCurrent()

        screen.port.fail("no device found")
        runCurrent()

        assertEquals(ring, screen.remembered)
        assertEquals(1, screen.built.single().fake.connectCalls)
        assertEquals(PairingOutcome.Fallback(FallbackReason.FAILED_BEFORE_SHEET), screen.pairing.lastOutcome.value)
        assertNull(screen.viewModel.pairingSheet.value)
    }

    @Test
    fun aFailureAfterTheSheetIsNotTheAnswerTheResultCodeIs() = runTest {
        // Android calls onFailure first, then sets the sheet's result.
        val screen = screen()
        continueToSheet(screen)

        screen.port.fail("user rejected")
        runCurrent()
        assertTrue(screen.built.isEmpty(), "nothing yet: the result code decides")
        sheetAnswers(screen, 1)

        assertNull(screen.remembered)
        assertTrue(screen.built.isEmpty())
    }

    @Test
    fun aPhoneWithoutCdmConnectsWithoutAskingIt() = runTest {
        val screen = screen(FakeCompanionPort(available = false))

        screen.tap(LinkAction.CONTINUE_PAIRING)
        runCurrent()

        assertTrue(screen.port.requests.isEmpty())
        assertTrue(screen.port.disassociated.isEmpty())
        assertEquals(ring, screen.remembered)
        assertEquals(1, screen.built.single().fake.connectCalls)
        assertEquals(PairingOutcome.Fallback(FallbackReason.FEATURE_MISSING), screen.pairing.lastOutcome.value)
    }

    @Test
    fun aRequestCdmRefusesConnectsAnywayAndIsLogged() = runTest {
        val screen = screen(FakeCompanionPort(throwOnAssociate = IllegalStateException("Must declare uses-feature")))

        screen.tap(LinkAction.CONTINUE_PAIRING)
        runCurrent()

        assertEquals(1, screen.built.single().fake.connectCalls)
        assertEquals(PairingOutcome.Fallback(FallbackReason.REQUEST_REFUSED), screen.pairing.lastOutcome.value)
        assertTrue(screen.logLines.any { "IllegalStateException" in it }, "${screen.logLines}")
    }

    @Test
    fun aSheetTheActivityCouldNotShowConnectsAnyway() = runTest {
        val screen = screen()
        screen.tap(LinkAction.CONTINUE_PAIRING)
        runCurrent()
        screen.port.showSheet()
        runCurrent()

        screen.viewModel.onPairingSheetFailed()
        runCurrent()

        assertEquals(1, screen.built.single().fake.connectCalls)
        assertEquals(PairingOutcome.Fallback(FallbackReason.SHEET_NOT_SHOWN), screen.pairing.lastOutcome.value)
        assertNull(screen.viewModel.pairingSheet.value)
    }

    @Test
    fun cancellingWhileWaitingIgnoresTheLateAnswer() = runTest {
        val screen = screen()
        continueToSheet(screen)

        screen.tap(LinkAction.CANCEL_SCAN)
        runCurrent()
        sheetAnswers(screen, -1)

        assertEquals("Ready", screen.card.headline)
        assertNull(screen.remembered)
        assertTrue(screen.built.isEmpty())
    }

    @Test
    fun cancellingTheExplanationAsksNothing() = runTest {
        val screen = screen()

        screen.tap(LinkAction.CANCEL_SCAN)
        runCurrent()

        assertEquals("Ready", screen.card.headline)
        assertTrue(screen.port.requests.isEmpty())
    }

    @Test
    fun staleAssociationsForTheRingAreRemovedBeforeAskingWhateverTheirCase() = runTest {
        val port = FakeCompanionPort()
        port.associations += CompanionAssociation(11, ring.address.lowercase(Locale.ROOT))
        port.associations += CompanionAssociation(12, otherRing.address)
        port.associations += CompanionAssociation(13, ring.address)
        port.associations += CompanionAssociation(14, null)
        val screen = screen(port)

        screen.tap(LinkAction.CONTINUE_PAIRING)
        runCurrent()

        assertEquals(listOf(11, 13), port.disassociated)
        assertEquals(listOf(12, 14), port.associations.map { it.id })
        assertEquals(listOf(ring), port.requests)
    }

    @Test
    fun aBondedRingKeepsItsAssociations() = runTest {
        val port = FakeCompanionPort()
        port.associations += CompanionAssociation(21, ring.address)
        port.bonded += ring.address
        val screen = screen(port)

        screen.tap(LinkAction.CONTINUE_PAIRING)
        runCurrent()

        assertTrue(port.disassociated.isEmpty(), "bonded: Android pairs without a prompt anyway")
        assertEquals(listOf(ring), port.requests)
    }

    @Test
    fun theAssociationIdIsNeverSaved() = runTest {
        val port = FakeCompanionPort(newAssociationId = 4242)
        val screen = screen(port)
        continueToSheet(screen)

        port.created()
        sheetAnswers(screen, -1)

        val raw = screen.under.values.raw("ring.remembered.v1") as String
        assertEquals(3, raw.split('|').size, "address, type, name only")
        assertFalse("4242" in raw)
    }

    @Test
    fun disassociateRemovesEveryAssociationForTheAddressAndNoOther() {
        val port = FakeCompanionPort()
        port.associations += CompanionAssociation(31, ring.address)
        port.associations += CompanionAssociation(32, otherRing.address)
        port.associations += CompanionAssociation(33, ring.address.lowercase(Locale.ROOT))
        port.bonded += ring.address
        val pairing = CompanionPairing(port) {}

        pairing.disassociate(ring.address)

        assertEquals(listOf(31, 33), port.disassociated, "forgetting removes them even while bonded")
        assertEquals(listOf(32), port.associations.map { it.id })
    }

    @Test
    fun aCdmThatThrowsWhileListingStillLetsThePairingGoOn() = runTest {
        val port = FakeCompanionPort(throwOnList = SecurityException("no"))
        val screen = screen(port)

        screen.tap(LinkAction.CONTINUE_PAIRING)
        runCurrent()

        assertEquals(listOf(ring), port.requests)
        assertTrue(screen.logLines.any { "SecurityException" in it }, "${screen.logLines}")
    }
}

/** CDM at its seam: associations held in a list, the sheet and failures triggered by the test. */
internal class FakeCompanionPort(
    override val available: Boolean = true,
    private val throwOnAssociate: RuntimeException? = null,
    private val throwOnList: RuntimeException? = null,
    private val newAssociationId: Int = 99,
) : CompanionPort {
    val associations = mutableListOf<CompanionAssociation>()
    val bonded = mutableSetOf<String>()
    val disassociated = mutableListOf<Int>()
    val requests = mutableListOf<RememberedRing>()
    private var callback: AssociationCallback? = null

    override fun associations(): List<CompanionAssociation> {
        throwOnList?.let { throw it }
        return associations.toList()
    }

    override fun disassociate(id: Int) {
        disassociated += id
        associations.removeAll { it.id == id }
    }

    override fun isBonded(address: String): Boolean = bonded.any { RingAddress.same(it, address) }

    override fun associate(ring: RememberedRing, callback: AssociationCallback) {
        throwOnAssociate?.let { throw it }
        requests += ring
        this.callback = callback
    }

    /** CDM found the ring: the sheet is ready for the activity. */
    fun showSheet() {
        checkNotNull(callback).onPending(object : PairingSheet {})
    }

    /** CDM's `onFailure`. */
    fun fail(message: String) {
        checkNotNull(callback).onFailure(message)
    }

    /** CDM made the association (it does so on approval, alongside the sheet's result). */
    fun created() = associations.add(CompanionAssociation(newAssociationId, requests.last().address))
}
