package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.MeasureFailure
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What ends a live measure — the Stop button, the link tearing a connection down, the link
 * leaving `Authenticated` — and that nothing brings it back: a reconnect does not resume it
 * (`ios/OpenCircuit/BLE/RingScanner.swift:989-1012` @ b1c2fdd). Driven through the session
 * controller, so the teardown and state come from the link exactly as in the app.
 */
class LiveMeasureStopTest {

    private val logLines = mutableListOf<String>()

    private fun TestScope.session(link: TimedRingLink): RingSessionController {
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.start()
        link.fake.setState(LinkState.Authenticated)
        testScheduler.runCurrent()
        // The keepalive's d0 as the link authenticates is KeepaliveTickerTest's to judge, not these tests'.
        link.clear()
        return controller
    }

    /** After a measure ends the app may still ask for the ring's status (`d0 00 00`), and nothing else. */
    private fun assertOnlyStatusQueriesAfter(link: TimedRingLink, count: Int, message: String) {
        val after = link.writes.drop(count)
        assertTrue(after.all { it.hex == Wire.STATUS_QUERY }, "$message: $after")
    }

    @Test
    fun stopEndsThePollingAndNothingMoreIsWritten() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        live.start(LiveMode.HEART_RATE)
        advanceTo(10_000)
        val writes = link.writes

        live.stop()
        advanceTo(60_000)

        assertFalse(live.isMeasuring.value)
        assertEquals(writes, link.writes.take(writes.size))
        assertOnlyStatusQueriesAfter(link, writes.size, "no measure write after the stop")
        assertNull(live.state.value.heartRate.failure, "a stop the user asked for is not a failure")
    }

    @Test
    fun aTeardownStopsTheMeasureAndSaysTheRingDisconnected() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        live.start(LiveMode.HEART_RATE)
        advanceTo(10_000)
        val writes = link.writes

        link.fake.emitTeardown(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 0))
        advanceTo(10_000)

        assertFalse(live.isMeasuring.value)
        assertEquals(MeasureFailure.RingDisconnected, live.state.value.heartRate.failure)
        advanceTo(60_000)
        assertOnlyStatusQueriesAfter(link, writes.size, "no poll after the teardown")
    }

    @Test
    fun aTeardownDuringTheEntryStopsItBeforeTheNextEntryWrite() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        live.start(LiveMode.SPO2)
        advanceTo(100)

        link.fake.emitTeardown(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 0))
        advanceTo(10_000)

        assertEquals(listOf(TimedWrite(0, Wire.STATUS_QUERY)), link.writes.filter { it.atMillis < 100 }, "the entry's first write only")
        assertOnlyStatusQueriesAfter(link, 1, "no mode or 07 00 00 after the teardown")
        assertFalse(live.isMeasuring.value)
    }

    @Test
    fun leavingAuthenticatedStopsTheMeasureAndReconnectingDoesNotResumeIt() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        live.start(LiveMode.HEART_RATE)
        advanceTo(10_000)

        link.fake.setState(LinkState.Reconnecting(attempt = 1, delay = Duration.ofSeconds(1)))
        advanceTo(10_000)
        assertFalse(live.isMeasuring.value)
        assertEquals(MeasureFailure.RingDisconnected, live.state.value.heartRate.failure)
        val writes = link.writes

        link.fake.setState(LinkState.Authenticated)
        advanceTo(120_000)

        assertFalse(live.isMeasuring.value, "not resumed")
        assertOnlyStatusQueriesAfter(link, writes.size, "no measure write after the reconnect")
    }

    @Test
    fun notStreamingIsNotAuthenticatedAndStopsTheMeasureToo() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        live.start(LiveMode.SPO2)
        advanceTo(5_000)

        link.fake.setState(LinkState.NotStreaming)
        advanceTo(5_000)

        assertFalse(live.isMeasuring.value)
    }

    @Test
    fun aStoppedHeartRateKeepsItsSettledValueOnTheRowForTheSession() = runTest {
        val link = timedLink()
        val controller = session(link)
        val live = controller.liveMeasure
        live.start(LiveMode.HEART_RATE)
        advanceTo(3_000)
        // Through the link and the dispatcher, as the ring would send them.
        TestFrames.realHeartRateRead.forEach(link.fake::emitFrame) // warm-up + 82 84 88 90 91 66 61
        advanceTo(3_000)
        assertEquals(88, live.state.value.settledHeartRate) // last 5: 88 90 91 66 61

        live.stop()

        assertEquals(88, live.state.value.heartRate.lastValue)
        assertNull(live.state.value.newest, "the live readout closes")
        assertEquals(emptyList(), live.state.value.session.points)
    }

    @Test
    fun aSecondMeasureOnTheSameControllerStartsFromNothing() = runTest {
        val link = timedLink()
        val controller = session(link)
        val live = controller.liveMeasure
        live.start(LiveMode.HEART_RATE)
        advanceTo(3_000)
        TestFrames.realHeartRateRead.forEach(link.fake::emitFrame)
        advanceTo(3_000)
        live.stop()
        advanceTo(10_000)

        live.start(LiveMode.HEART_RATE)
        advanceTo(13_750)
        assertTrue(live.isMeasuring.value)
        assertNull(live.state.value.newest)
        assertNull(live.state.value.settledHeartRate, "the first measure's frames do not count")
        assertEquals(emptyList(), live.state.value.session.points)
        assertEquals(88, live.state.value.heartRate.lastValue, "the row keeps the last result meanwhile")

        // Four new locked frames are still not a settled reading.
        TestFrames.realHeartRateRead.drop(1).take(4).forEach(link.fake::emitFrame)
        advanceTo(13_750)
        assertNull(live.state.value.settledHeartRate)
        assertEquals(listOf(Wire.STATUS_QUERY, Wire.HR_MODE, Wire.FETCH, Wire.POLL), link.writes.filter { it.atMillis >= 10_000 }.map { it.hex })
    }

    @Test
    fun switchingModeWhileTheEntryIsBeingWrittenRestartsTheEntryInTheNewMode() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        live.start(LiveMode.HEART_RATE)
        advanceTo(300) // d0 at 0, 06 01 at 250

        live.start(LiveMode.SPO2)
        advanceTo(2_000)

        assertEquals(
            listOf(
                TimedWrite(0, Wire.STATUS_QUERY),
                TimedWrite(250, Wire.HR_MODE),
                TimedWrite(300, Wire.STATUS_QUERY),
                TimedWrite(550, Wire.SPO2_MODE),
                TimedWrite(800, Wire.FETCH),
            ),
            link.writes,
            "the heart-rate entry never reaches its 07 00 00",
        )
        assertEquals(LiveMode.SPO2, live.state.value.mode)
    }
}
