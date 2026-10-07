package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMeasureController
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.MeasureFailure
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendFailure
import io.github.opencircuit.ble.SendResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A write the link refuses or that fails ends the measure at once, writes nothing more, and
 * leaves the reason on the measured row — never swallowed (PORTING.md D-238; upstream writes
 * without looking at an outcome, `ios/OpenCircuit/BLE/RingSession.swift:1122-1127`).
 */
class MeasureFailureSurfacedTest {

    private fun TestScope.controllerOn(link: TimedRingLink) =
        LiveMeasureController(send = link::send, scope = backgroundScope, monotonicMillis = { testScheduler.currentTime })

    private val outcomes: List<Pair<SendResult, MeasureFailure>> = listOf(
        SendResult.Refused(RefusalReason.NOT_AUTHENTICATED) to MeasureFailure.CommandRefused(RefusalReason.NOT_AUTHENTICATED),
        SendResult.Refused(RefusalReason.NOT_BONDED) to MeasureFailure.CommandRefused(RefusalReason.NOT_BONDED),
        SendResult.Failed(SendFailure.TIMED_OUT) to MeasureFailure.CommandFailed(SendFailure.TIMED_OUT),
        SendResult.Failed(SendFailure.GATT_ERROR) to MeasureFailure.CommandFailed(SendFailure.GATT_ERROR),
        SendResult.Failed(SendFailure.LINK_LOST) to MeasureFailure.CommandFailed(SendFailure.LINK_LOST),
    )

    @Test
    fun aRefusalOrFailureAtEachEntryStepEndsTheMeasureAndIsShown() = runTest {
        val entry = listOf(Wire.STATUS_QUERY, Wire.HR_MODE, Wire.FETCH)
        for ((result, failure) in outcomes) {
            for (step in entry.indices) {
                val link = timedLink()
                val live = controllerOn(link)
                link.fake.answerSendsWith(*Array(step) { SendResult.Sent }, result)
                val startAt = testScheduler.currentTime

                live.start(LiveMode.HEART_RATE)
                advanceTo(startAt + 10_000)

                val case = "$result at entry step ${step + 1}"
                assertEquals(entry.take(step + 1), link.writes.map { it.hex }, "$case: nothing written after it")
                assertFalse(live.isMeasuring.value, case)
                assertEquals(failure, live.state.value.heartRate.failure, case)
            }
        }
    }

    @Test
    fun aRefusalOrFailureOfAPollEndsTheMeasureAndIsShown() = runTest {
        for ((result, failure) in outcomes) {
            val link = timedLink()
            val live = controllerOn(link)
            // Entry (3 writes) and two polls go through; the third poll does not.
            link.fake.answerSendsWith(SendResult.Sent, SendResult.Sent, SendResult.Sent, SendResult.Sent, SendResult.Sent, result)
            val startAt = testScheduler.currentTime

            live.start(LiveMode.SPO2)
            advanceTo(startAt + 20_000)

            assertEquals(3, link.writes.count { it.hex == Wire.POLL }, "$result: no poll after the failed one")
            assertFalse(live.isMeasuring.value)
            assertEquals(failure, live.state.value.spo2.failure, "$result")
            assertNull(live.state.value.heartRate.failure)
        }
    }

    @Test
    fun aFailedReTapEntryEndsTheRunningMeasureToo() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        live.start(LiveMode.HEART_RATE)
        advanceTo(10_000)

        link.fake.answerSendsWith(SendResult.Failed(SendFailure.GATT_ERROR))
        live.start(LiveMode.HEART_RATE)
        advanceTo(30_000)

        assertFalse(live.isMeasuring.value)
        assertEquals(MeasureFailure.CommandFailed(SendFailure.GATT_ERROR), live.state.value.heartRate.failure)
        assertEquals(TimedWrite(10_000, Wire.STATUS_QUERY), link.writes.last(), "nothing after the failed write")
    }

    @Test
    fun aNewMeasureClearsThePreviousFailureOfItsRow() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        link.fake.answerSendsWith(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED))
        live.start(LiveMode.HEART_RATE)
        advanceTo(1_000)
        assertEquals(MeasureFailure.CommandRefused(RefusalReason.NOT_AUTHENTICATED), live.state.value.heartRate.failure)

        live.start(LiveMode.HEART_RATE)
        advanceTo(2_000)

        assertNull(live.state.value.heartRate.failure)
    }
}
