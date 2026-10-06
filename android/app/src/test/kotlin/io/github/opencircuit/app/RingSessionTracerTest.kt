package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingUiState
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.RememberedRing
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * End to end on the JVM: a frame the fake ring sends crosses the link's `frames`, the session
 * controller, the dispatcher and the device-status model, and shows in the Ring screen's state.
 * No step is called by hand.
 */
class RingSessionTracerTest {

    private val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "Test ring")
    private val logLines = mutableListOf<String>()

    @Test
    fun anAuthenticatedRingAndOneDescriptorShowAsConnectedWithItsBattery() = runTest {
        val link = FakeRingLink(ring)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        val viewModel = RingViewModel(controller, title = "Ring", scope = backgroundScope)
        runCurrent()
        assertEquals(RingUiState(title = "Ring", status = "Not connected", batteryPercent = null), viewModel.uiState.value)
        assertEquals(1, link.connectCalls, "opening the Ring screen connects the link")

        link.setState(LinkState.Connecting)
        runCurrent()
        assertEquals("Connecting…", viewModel.uiState.value.status)

        link.setState(LinkState.Authenticated)
        link.emitFrame(TestFrames.wornDescriptor)
        runCurrent()

        assertEquals(
            RingUiState(title = "Ring", status = "Connected", batteryPercent = 66),
            viewModel.uiState.value.copy(measure = null),
        )
        assertEquals("Measure heart rate", viewModel.uiState.value.measure?.heartRate?.actionLabel, "Measure cards once connected")
    }

    @Test
    fun everyRouteIsReachedFromTheLinkThroughTheController() = runTest {
        val link = FakeRingLink(ring)
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.start()
        runCurrent()

        link.emitFrame(TestFrames.chargingResponseDescriptor) // 0x87 → device status
        link.emitFrame(TestFrames.liveHeartRate) // 0x15 → live measure (none running: counted)
        link.emitFrame(TestFrames.heartbeat) // 0x11 → ignored, :ble answered it
        link.emitFrame(TestFrames.historyPage47) // no handler → counted
        link.emitFrame(TestFrames.unknown) // no handler → counted
        runCurrent()

        assertEquals(71, controller.deviceStatus.state.value.batteryPercent)
        assertEquals(1, controller.liveMeasure.state.value.framesNotUsed)
        assertEquals(1, controller.dispatcher.counts.value.ignored)
        assertEquals(mapOf(0x47 to 1, 0xee to 1), controller.dispatcher.counts.value.unhandled)
        assertEquals(2, logLines.size, "one line per unhandled frame: $logLines")
    }

    @Test
    fun aMeasureTapPollsTheRingAndItsLiveFramesShowInTheReadout() = runTest {
        val link = timedLink()
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        val viewModel = RingViewModel(controller, title = "Ring", scope = backgroundScope)
        runCurrent()
        assertNull(viewModel.uiState.value.measure, "no Measure cards before the ring is authenticated")
        link.fake.setState(LinkState.Authenticated)
        runCurrent()
        assertEquals("No reading yet", viewModel.uiState.value.measure?.heartRate?.caption)

        viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
        advanceTo(2_750)
        assertEquals(listOf(Wire.STATUS_QUERY, Wire.HR_MODE, Wire.FETCH, Wire.POLL), link.writes.map { it.hex })
        assertEquals("—", viewModel.uiState.value.measure?.live?.readout)

        link.fake.emitFrame(TestFrames.liveHeartRate) // the ring answers the poll: 91 bpm
        runCurrent()
        assertEquals("91", viewModel.uiState.value.measure?.live?.readout)

        viewModel.onAction(RingAction.StopMeasure)
        runCurrent()
        assertNull(viewModel.uiState.value.measure?.live, "the Live card closes")
        advanceTo(20_000)
        assertEquals(1, link.timesOf(Wire.POLL).size, "no poll after Stop")
    }

    @Test
    fun withoutALinkTheScreenSaysNotConnected() = runTest {
        val viewModel = RingViewModel(controller = null, title = "Ring", scope = backgroundScope)
        runCurrent()

        assertEquals(RingUiState(title = "Ring", status = "Not connected", batteryPercent = null), viewModel.uiState.value)
    }
}
