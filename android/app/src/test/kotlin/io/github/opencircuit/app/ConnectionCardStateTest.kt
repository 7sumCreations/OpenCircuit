package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.BatteryBand
import io.github.opencircuit.app.ring.ConnectionCardUi
import io.github.opencircuit.app.ring.LinkAction
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.ring.batteryBand
import io.github.opencircuit.app.ring.durationWords
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connection card as the Ring screen shows it, from descriptors the fake ring sends through
 * the link, the session controller, the dispatcher and the device-status model: battery and its
 * icon band, charging and time to full, time left, the case battery only while docked, the
 * charger hint with Measure disabled, and "as of …" once the battery is out of date
 * (`ios/OpenCircuit/ContentView.swift:1105-1258, 1484-1512` @ b1c2fdd).
 */
class ConnectionCardStateTest {

    private val logLines = mutableListOf<String>()

    private class Screen(val link: TimedRingLink, val controller: RingSessionController, val viewModel: RingViewModel) {
        val card: ConnectionCardUi get() = viewModel.uiState.value.card
    }

    private fun TestScope.screen(): Screen {
        val link = timedLink()
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        val viewModel = RingViewModel(controller, title = "Ring", scope = backgroundScope)
        testScheduler.runCurrent()
        link.fake.setState(LinkState.Authenticated)
        testScheduler.runCurrent()
        return Screen(link, controller, viewModel)
    }

    private fun TestScope.send(screen: Screen, frame: ByteArray) {
        screen.link.fake.emitFrame(frame)
        testScheduler.runCurrent()
    }

    @Test
    fun aConnectedRingShowsItsNameBatteryAndThatTheTimeLeftIsBeingEstimated() = runTest {
        val screen = screen()
        send(screen, TestFrames.wornDescriptor) // 66 %, worn, not in the case

        val card = screen.card
        assertEquals("Test ring", card.ringName)
        assertEquals("Connected", card.link.headline)
        val battery = card.battery!!
        assertEquals(66, battery.percent)
        assertEquals(BatteryBand.THREE_QUARTERS, battery.band)
        assertFalse(battery.charging)
        assertEquals("estimating time left…", battery.timeLine)
        assertNull(battery.caseLine)
        assertNull(battery.asOf)
        assertNull(card.chargerHint)
    }

    @Test
    fun noBatteryIsShownBeforeTheFirstDescriptor() = runTest {
        assertNull(screen().card.battery)
    }

    @Test
    fun aSlowDischargeShowsTheTimeLeft() = runTest {
        val screen = screen()
        send(screen, descriptor(battery = 80))
        advanceTo(HOUR)
        send(screen, descriptor(battery = 79))
        advanceTo(2 * HOUR)
        send(screen, descriptor(battery = 78))

        assertEquals("~3 d 6 h left", screen.card.battery?.timeLine) // 1 %/h, 78 % left
    }

    @Test
    fun onTheChargerTheCardShowsTimeToFullTheCaseAndTheHintAndMeasureIsDisabled() = runTest {
        val screen = screen()
        send(screen, descriptor(battery = 60, state = 0x04, case = 0x46))
        advanceTo(10 * MINUTE)
        send(screen, descriptor(battery = 62, state = 0x04, case = 0x46))
        advanceTo(20 * MINUTE)
        send(screen, descriptor(battery = 64, state = 0x04, case = 0x46))

        val card = screen.card
        assertTrue(card.battery!!.charging)
        assertEquals("~3 h to full", card.battery?.timeLine) // 12 %/h, 36 % to go
        assertEquals("Case 70%", card.battery?.caseLine)
        assertFalse(card.battery!!.caseCharging)
        assertEquals("Ring is on the charger — Measure unavailable", card.chargerHint)
        val measure = screen.viewModel.uiState.value.measure!!
        assertFalse(measure.heartRate.enabled)
        assertFalse(measure.spo2.enabled)

        val before = screen.link.writes.size
        screen.viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
        advanceTo(25 * MINUTE)
        assertFalse(screen.controller.liveMeasure.isMeasuring.value, "Measure does nothing on the charger")
        assertTrue(screen.link.writes.drop(before).none { it.hex == Wire.HR_MODE || it.hex == Wire.POLL })
    }

    @Test
    fun takenOffTheChargerTheHintGoesAndMeasureWorksAgain() = runTest {
        val screen = screen()
        send(screen, TestFrames.chargingDescriptor) // 71 %, on the charger
        assertEquals("Ring is on the charger — Measure unavailable", screen.card.chargerHint)

        send(screen, descriptor(battery = 71, state = 0x02))

        assertNull(screen.card.chargerHint)
        assertTrue(screen.viewModel.uiState.value.measure!!.heartRate.enabled)
        screen.viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
        testScheduler.runCurrent()
        assertTrue(screen.controller.liveMeasure.isMeasuring.value)
    }

    @Test
    fun aChargeWithOneReadingIsStillBeingEstimatedAndAFullRingSaysFull() = runTest {
        val screen = screen()
        send(screen, descriptor(battery = 99, state = 0x04))
        assertEquals("estimating time to full…", screen.card.battery?.timeLine)

        send(screen, descriptor(battery = 100, state = 0x04))
        assertEquals("Full", screen.card.battery?.timeLine)
    }

    @Test
    fun theCaseLineShowsTheCaseChargingAndGoesWhenTheRingLeavesTheCase() = runTest {
        val screen = screen()
        send(screen, descriptor(battery = 71, state = 0x04, case = 0xda)) // 0x80 | 90

        assertEquals("Case 90%", screen.card.battery?.caseLine)
        assertTrue(screen.card.battery!!.caseCharging)

        send(screen, descriptor(battery = 71, state = 0x02, case = 0xff))
        assertNull(screen.card.battery?.caseLine)
    }

    @Test
    fun theBatterySaysAsOfOnlyFrom120SecondsWithoutAReading() = runTest {
        val screen = screen() // the fake ring answers the keepalive's d0s with no descriptor
        send(screen, TestFrames.wornDescriptor)

        advanceTo(119_999)
        assertNull(screen.card.battery?.asOf)
        assertEquals("estimating time left…", screen.card.battery?.timeLine)

        advanceTo(120_000)
        assertEquals("as of 2 min ago", screen.card.battery?.asOf)
        assertTrue(screen.card.battery!!.stale)
        assertNull(screen.card.battery?.timeLine, "no time left on an out-of-date reading")

        advanceTo(2 * HOUR + 5 * MINUTE)
        assertEquals("as of 2 h ago", screen.card.battery?.asOf)
    }

    @Test
    fun theChargerHintIsHiddenWhileAMeasureRunsAndItsStopStaysAvailable() = runTest {
        val screen = screen()
        screen.viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
        advanceTo(5_000)

        send(screen, TestFrames.chargingDescriptor)

        assertNull(screen.card.chargerHint)
        val heartRate = screen.viewModel.uiState.value.measure!!.heartRate
        assertTrue(heartRate.measuring)
        assertTrue(heartRate.enabled, "the running card's Stop stays usable")
        assertFalse(screen.viewModel.uiState.value.measure!!.spo2.enabled, "no switch to SpO₂ on the charger")
    }

    @Test
    fun disconnectingDuringAMeasureStopsItWithoutAFailureLine() = runTest {
        val screen = screen()
        screen.viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
        advanceTo(5_000)

        screen.viewModel.onAction(RingAction.Link(LinkAction.DISCONNECT))
        // What the link does on disconnect(): Idle and one user-disconnected teardown.
        screen.link.fake.setState(LinkState.Idle)
        screen.link.fake.emitTeardown(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 0))
        testScheduler.runCurrent()

        assertEquals(1, screen.link.fake.disconnectCalls)
        assertFalse(screen.controller.liveMeasure.isMeasuring.value)
        assertNull(screen.controller.liveMeasure.state.value.heartRate.failure, "the user's own disconnect is not a failure")
    }

    @Test
    fun aRefusedKeepaliveIsShownOnTheCardInWords() = runTest {
        val link = timedLink()
        link.fake.answerSendsWith(SendResult.Refused(RefusalReason.NOT_BONDED))
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        val viewModel = RingViewModel(controller, title = "Ring", scope = backgroundScope)
        testScheduler.runCurrent()
        link.fake.setState(LinkState.Authenticated)
        testScheduler.runCurrent()

        assertEquals("Couldn't ask the ring for its status — this phone isn't paired with the ring.", viewModel.uiState.value.card.problem)
    }

    @Test
    fun aLowBatteryIsMarkedUnlessCharging() = runTest {
        val screen = screen()
        send(screen, descriptor(battery = 20))
        assertTrue(screen.card.battery!!.low)
        send(screen, descriptor(battery = 21))
        assertFalse(screen.card.battery!!.low)
        send(screen, descriptor(battery = 20, state = 0x04))
        assertFalse(screen.card.battery!!.low, "low but charging is not marked")
    }

    @Test
    fun theIconBandFollowsUpstreamsThresholds() {
        val bands = listOf(1, 12, 13, 37, 38, 62, 63, 87, 88, 100).map(::batteryBand)
        assertEquals(
            listOf(
                BatteryBand.EMPTY, BatteryBand.EMPTY, BatteryBand.QUARTER, BatteryBand.QUARTER, BatteryBand.HALF,
                BatteryBand.HALF, BatteryBand.THREE_QUARTERS, BatteryBand.THREE_QUARTERS, BatteryBand.FULL, BatteryBand.FULL,
            ),
            bands,
        )
    }

    @Test
    fun durationsReadInDaysHoursAndMinutes() {
        assertEquals("3 d 6 h", durationWords(78.0 * 3_600))
        assertEquals("3 d", durationWords(72.0 * 3_600))
        assertEquals("2 h 15 min", durationWords(2.25 * 3_600))
        assertEquals("3 h", durationWords(3.0 * 3_600))
        assertEquals("40 min", durationWords(40.0 * 60))
        assertEquals("1 min", durationWords(20.0), "under a minute still reads as a minute")
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
