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
import kotlin.test.assertTrue

/**
 * Charging is the descriptor's state byte OR a strictly rising battery (`ChargingInference`,
 * fed the last 4 distinct readings as upstream, `ios/OpenCircuit/BLE/RingSession.swift:206-217,
 * 5076-5081` @ b1c2fdd). Here the state byte always says "worn" (0x02), so only the inference can
 * make the card say charging.
 */
class ChargingInferenceWiringTest {

    private val logLines = mutableListOf<String>()

    private fun TestScope.screen(): Pair<RingSessionController, RingViewModel> {
        val link = timedLink()
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        val viewModel = RingViewModel(controller, title = "Ring", scope = backgroundScope)
        testScheduler.runCurrent()
        link.fake.setState(LinkState.Authenticated)
        link.fake.emitFrame(descriptor(battery = 66))
        testScheduler.runCurrent()
        return controller to viewModel
    }

    private fun TestScope.ringSends(controller: RingSessionController, battery: Int) {
        (controller.link as TimedRingLink).fake.emitFrame(descriptor(battery = battery, state = 0x02))
        testScheduler.runCurrent()
    }

    @Test
    fun aRisingBatteryWithTheWornStateByteReadsAsCharging() = runTest {
        val (controller, viewModel) = screen()
        assertFalse(viewModel.uiState.value.card.battery!!.charging)

        ringSends(controller, 67)

        val card = viewModel.uiState.value.card
        assertTrue(card.battery!!.charging, "66 → 67 rises: inferred charging")
        assertEquals("estimating time to full…", card.battery?.timeLine)
        assertEquals("Ring is on the charger — Measure unavailable", card.chargerHint)
        assertFalse(viewModel.uiState.value.measure!!.heartRate.enabled)
        viewModel.onAction(RingAction.Measure(LiveMode.SPO2))
        testScheduler.runCurrent()
        assertFalse(controller.liveMeasure.isMeasuring.value)
    }

    @Test
    fun aDropEndsTheInferenceAndMeasureComesBack() = runTest {
        val (controller, viewModel) = screen()
        ringSends(controller, 67)
        assertTrue(viewModel.uiState.value.card.battery!!.charging)

        ringSends(controller, 66)

        assertFalse(viewModel.uiState.value.card.battery!!.charging, "66, 67, 66 is not strictly rising")
        assertEquals("estimating time left…", viewModel.uiState.value.card.battery?.timeLine)
        assertTrue(viewModel.uiState.value.measure!!.heartRate.enabled)
    }

    @Test
    fun aRepeatedReadingNeitherStartsNorEndsTheInference() = runTest {
        val (controller, viewModel) = screen()
        ringSends(controller, 66) // the same reading again: not added to the window
        assertFalse(viewModel.uiState.value.card.battery!!.charging)

        ringSends(controller, 67)
        ringSends(controller, 67)
        assertTrue(viewModel.uiState.value.card.battery!!.charging, "66, 67 — the repeat does not flatten it")
    }
}
