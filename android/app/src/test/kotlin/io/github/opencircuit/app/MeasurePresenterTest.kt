package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMeasureState
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.LivePoint
import io.github.opencircuit.app.live.LiveSessionSnapshot
import io.github.opencircuit.app.live.MeasureFailure
import io.github.opencircuit.app.live.MeasureResult
import io.github.opencircuit.app.live.measureUi
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The words and numbers the Measure cards and the Live card show for each measure state. Copy:
 * the progress lines are upstream's (`ios/OpenCircuit/ContentView.swift:1455-1461` @ b1c2fdd),
 * the no-reading line is upstream's `userMeasureFailedMessage` (`RingSession.swift:1167`).
 */
class MeasurePresenterTest {

    @Test
    fun idleCardsSayNoReadingYetAndOfferMeasureWithSpo2MarkedAsAnEstimate() {
        val ui = measureUi(LiveMeasureState())

        assertNull(ui.live)
        assertEquals("Heart rate", ui.heartRate.title)
        assertFalse(ui.heartRate.estimate)
        assertEquals("No reading yet", ui.heartRate.caption)
        assertEquals("Measure heart rate", ui.heartRate.actionLabel)
        assertEquals("SpO₂", ui.spo2.title)
        assertTrue(ui.spo2.estimate)
        assertEquals("Measure SpO₂", ui.spo2.actionLabel)
    }

    @Test
    fun whilePreparingTheLiveCardShowsADashAndAsksToHoldStill() {
        val ui = measureUi(LiveMeasureState(mode = LiveMode.HEART_RATE, preparing = true))

        val live = ui.live!!
        assertEquals("Live heart rate", live.title)
        assertEquals("—", live.readout)
        assertEquals("bpm", live.unit)
        assertNull(live.range)
        assertEquals("Hold still — getting a reading", live.progress)
        assertEquals("preparing…", ui.heartRate.caption)
        assertTrue(ui.heartRate.measuring)
        assertEquals("Stop measuring heart rate", ui.heartRate.actionLabel)
        assertFalse(ui.spo2.measuring)
    }

    @Test
    fun heartRateFramesThatHaveNotLockedAskToHoldStillAndLockedOnesSayMeasuring() {
        val warming = measureUi(LiveMeasureState(mode = LiveMode.HEART_RATE, warmingUp = true))
        assertEquals("Hold still — getting a reading", warming.live!!.progress)

        val locked = measureUi(
            LiveMeasureState(
                mode = LiveMode.HEART_RATE,
                newest = 63,
                session = LiveSessionSnapshot(points = listOf(LivePoint(0, 58), LivePoint(2_000, 63)), range = 58..66),
            ),
        )
        val live = locked.live!!
        assertEquals("63", live.readout)
        assertEquals("58–66 so far", live.range)
        assertEquals("Measuring heart rate…", live.progress)
        assertEquals("measuring…", locked.heartRate.caption, "not settled yet")
    }

    @Test
    fun aSettledHeartRateShowsOnTheRowWhileTheMeasureContinues() {
        val ui = measureUi(LiveMeasureState(mode = LiveMode.HEART_RATE, newest = 61, settledHeartRate = 62))

        assertEquals("62 bpm (settled) · measuring…", ui.heartRate.caption)
        assertEquals("61", ui.live!!.readout, "the readout is the newest frame, the row the settled value")
    }

    @Test
    fun spo2SaysMeasuringAndIsAnEstimateInPercent() {
        val ui = measureUi(LiveMeasureState(mode = LiveMode.SPO2, newest = 97))

        val live = ui.live!!
        assertEquals("Live SpO₂", live.title)
        assertTrue(live.estimate)
        assertEquals("97", live.readout)
        assertEquals("%", live.unit)
        assertEquals("Measuring SpO₂…", live.progress)
        assertEquals("measuring…", ui.spo2.caption)
    }

    @Test
    fun aStaleReadoutShowsADashAndAsksToHoldStillAgain() {
        val ui = measureUi(LiveMeasureState(mode = LiveMode.HEART_RATE, newest = 70, stale = true))

        val live = ui.live!!
        assertEquals("—", live.readout)
        assertEquals("Hold still — getting a reading", live.progress)
    }

    @Test
    fun afterAMeasureTheRowShowsTheLastReading() {
        val ui = measureUi(LiveMeasureState(heartRate = MeasureResult(lastValue = 62), spo2 = MeasureResult(lastValue = 97)))

        assertEquals("Last: 62 bpm", ui.heartRate.caption)
        assertEquals("Last: 97%", ui.spo2.caption)
    }

    @Test
    fun eachFailureHasItsOwnLineOnTheRowAndKeepsTheLastReading() {
        val expected = mapOf(
            MeasureFailure.NoReading to
                "Couldn't get a reading — make sure the ring is worn snugly and not on the charger, then hold still.",
            MeasureFailure.RingDisconnected to "Measurement stopped — the ring disconnected.",
            MeasureFailure.CommandRefused(RefusalReason.NOT_AUTHENTICATED) to "Couldn't measure — the ring isn't connected.",
            MeasureFailure.CommandRefused(RefusalReason.NOT_BONDED) to "Couldn't measure — this phone isn't paired with the ring.",
            MeasureFailure.CommandRefused(RefusalReason.AUTH_COMMAND_RESERVED) to "Couldn't measure — the ring link refused the command.",
            MeasureFailure.CommandRefused(RefusalReason.HISTORY_UNSAFE) to "Couldn't measure — the ring link refused the command.",
            MeasureFailure.CommandFailed(SendFailure.TIMED_OUT) to "Measurement stopped — the ring stopped answering.",
            MeasureFailure.CommandFailed(SendFailure.GATT_ERROR) to "Measurement stopped — the ring stopped answering.",
            MeasureFailure.CommandFailed(SendFailure.LINK_LOST) to "Measurement stopped — the ring stopped answering.",
        )
        for ((failure, line) in expected) {
            val ui = measureUi(LiveMeasureState(spo2 = MeasureResult(lastValue = 96, failure = failure)))
            assertEquals(line, ui.spo2.failure, "$failure")
            assertEquals("Last: 96%", ui.spo2.caption)
            assertNull(ui.heartRate.failure)
        }
    }

    @Test
    fun theLiveCardCarriesTheChartPointsAndItsWindow() {
        val points = listOf(LivePoint(0, 60), LivePoint(2_000, 62), LivePoint(4_000, 61))
        val ui = measureUi(LiveMeasureState(mode = LiveMode.HEART_RATE, newest = 61, session = LiveSessionSnapshot(points, 60..62)))

        val live = ui.live!!
        assertEquals(points, live.points)
        assertEquals(90_000L, live.windowMillis)
        assertEquals("last 90 s", live.windowLabel)
    }
}
