package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two charging signals, two jobs. The descriptor's state byte (`[2] == 0x04`) alone says the ring
 * is on its charger: it draws the bolt, shows "Ring is on the charger — Measure unavailable" and
 * disables Measure. The rising-battery inference (`ChargingInference`, fed the last 4 distinct
 * readings as upstream, `ios/OpenCircuit/BLE/RingSession.swift:206-217, 5076-5081` @ b1c2fdd) only
 * labels the battery line "charging (inferred)" and turns the time line towards full; it never
 * blocks Measure, because a worn ring's battery can read one point higher by chance (PORTING D-241,
 * D-242).
 */
class ChargingInferenceWiringTest {

    private val logLines = mutableListOf<String>()

    private fun TestScope.screen(firstByte: Int = 0x02): Pair<RingSessionController, RingViewModel> {
        val link = timedLink()
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        val viewModel = RingViewModel(controller, title = "Ring", scope = backgroundScope)
        testScheduler.runCurrent()
        link.fake.setState(LinkState.Authenticated)
        link.fake.emitFrame(descriptor(battery = 66, state = firstByte))
        testScheduler.runCurrent()
        return controller to viewModel
    }

    private fun TestScope.ringSends(controller: RingSessionController, battery: Int, state: Int = 0x02) {
        (controller.link as TimedRingLink).fake.emitFrame(descriptor(battery = battery, state = state))
        testScheduler.runCurrent()
    }

    @Test
    fun aRisingBatteryWithTheWornStateByteIsLabelledInferredAndMeasureStaysEnabled() = runTest {
        val (controller, viewModel) = screen()
        assertNull(viewModel.uiState.value.card.battery!!.inferredChargingLabel)

        ringSends(controller, 67)

        val card = viewModel.uiState.value.card
        assertEquals("charging (inferred)", card.battery!!.inferredChargingLabel, "66 → 67 rises: inferred, and said so")
        assertFalse(card.battery!!.charging, "no bolt: the charger byte says worn")
        assertEquals("estimating time to full…", card.battery?.timeLine, "the inference turns the time line towards full")
        assertNull(card.chargerHint, "no charger hint without the charger byte")
        assertTrue(viewModel.uiState.value.measure!!.heartRate.enabled)
        assertTrue(viewModel.uiState.value.measure!!.spo2.enabled)

        viewModel.onAction(RingAction.Measure(LiveMode.SPO2))
        testScheduler.runCurrent()
        assertTrue(controller.liveMeasure.isMeasuring.value, "an inferred charge never blocks Measure")
    }

    @Test
    fun theChargerByteAloneDisablesMeasureWithoutAnyRise() = runTest {
        val (controller, viewModel) = screen(firstByte = 0x04)

        val card = viewModel.uiState.value.card
        assertTrue(card.battery!!.charging)
        assertNull(card.battery!!.inferredChargingLabel, "the byte is certain: no 'inferred' label")
        assertEquals("Ring is on the charger — Measure unavailable", card.chargerHint)
        assertFalse(viewModel.uiState.value.measure!!.heartRate.enabled)
        assertFalse(viewModel.uiState.value.measure!!.spo2.enabled)

        viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
        testScheduler.runCurrent()
        assertFalse(controller.liveMeasure.isMeasuring.value)
    }

    @Test
    fun aRiseOnTheChargerShowsTheByteNotTheLabel() = runTest {
        val (controller, viewModel) = screen(firstByte = 0x04)
        ringSends(controller, 67, state = 0x04)

        val card = viewModel.uiState.value.card
        assertTrue(card.battery!!.charging)
        assertNull(card.battery!!.inferredChargingLabel)
        assertEquals("Ring is on the charger — Measure unavailable", card.chargerHint)
    }

    @Test
    fun aDropEndsTheInferenceLabel() = runTest {
        val (controller, viewModel) = screen()
        ringSends(controller, 67)
        assertEquals("charging (inferred)", viewModel.uiState.value.card.battery!!.inferredChargingLabel)

        ringSends(controller, 66)

        assertNull(viewModel.uiState.value.card.battery!!.inferredChargingLabel, "66, 67, 66 is not strictly rising")
        assertEquals("estimating time left…", viewModel.uiState.value.card.battery?.timeLine)
        assertTrue(viewModel.uiState.value.measure!!.heartRate.enabled)
    }

    @Test
    fun aRepeatedReadingNeitherStartsNorEndsTheInference() = runTest {
        val (controller, viewModel) = screen()
        ringSends(controller, 66) // the same reading again: not added to the window
        assertNull(viewModel.uiState.value.card.battery!!.inferredChargingLabel)

        ringSends(controller, 67)
        ringSends(controller, 67)
        assertEquals(
            "charging (inferred)",
            viewModel.uiState.value.card.battery!!.inferredChargingLabel,
            "66, 67 — the repeat does not flatten it",
        )
    }
}
