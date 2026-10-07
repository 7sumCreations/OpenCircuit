package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMeasureController
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.MeasureEvidence
import io.github.opencircuit.app.live.MeasureFailure
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How long a measure runs: heart rate 90 s, SpO₂ 45 s, counted from when the budget is armed
 * (750 ms after the `d0 00 00`, just before the settle — `ios/OpenCircuit/BLE/RingSession.swift:1144`
 * @ b1c2fdd) or from the latest re-tap or mode switch (`:3359-3363`). The measure stops AT the
 * budget, never later and without one more poll (PORTING.md D-236).
 */
class LiveMeasureBudgetTest {

    private fun TestScope.controllerOn(link: TimedRingLink) =
        LiveMeasureController(send = link::send, scope = backgroundScope, monotonicMillis = { testScheduler.currentTime })

    @Test
    fun heartRateStillMeasures1MsBeforeItsBudgetAndStopsAt90Seconds() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        live.start(LiveMode.HEART_RATE)

        advanceTo(ARMED_AT + 90_000 - 1)
        assertTrue(live.isMeasuring.value, "still measuring at 89.999 s")
        val pollsBefore = link.timesOf(Wire.POLL)
        assertEquals(88_750L, pollsBefore.last())

        advanceTo(ARMED_AT + 90_000)
        assertFalse(live.isMeasuring.value, "stopped at 90 s")
        assertEquals(pollsBefore, link.timesOf(Wire.POLL), "no poll at the budget itself")

        advanceTo(200_000)
        assertEquals(pollsBefore.size, link.timesOf(Wire.POLL).size, "and none after it")
    }

    @Test
    fun spo2StillMeasures1MsBeforeItsBudgetAndStopsAt45Seconds() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        live.start(LiveMode.SPO2)

        advanceTo(ARMED_AT + 45_000 - 1)
        assertTrue(live.isMeasuring.value, "still measuring at 44.999 s")
        assertEquals(44_750L, link.timesOf(Wire.POLL).last())

        advanceTo(ARMED_AT + 45_000)
        assertFalse(live.isMeasuring.value, "stopped at 45 s, not at the next poll")
        assertEquals(44_750L, link.timesOf(Wire.POLL).last())
    }

    @Test
    fun aBudgetThatEndsWithNoReadingShowsTheFailureCopyOnThatRow() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        live.start(LiveMode.SPO2)

        advanceTo(ARMED_AT + 45_000)

        assertEquals(
            MeasureFailure.NoReading(MeasureEvidence(LiveMode.SPO2, liveFrames = 0, unusableFrames = 0, modeReplies = emptyList(), resets = 0)),
            live.state.value.spo2.failure,
        )
        assertNull(live.state.value.heartRate.failure, "the other row is untouched")
    }

    @Test
    fun aReTapReWritesTheEntryKeepsThePollCadenceAndRestartsTheBudget() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        live.start(LiveMode.HEART_RATE)
        advanceTo(30_000)
        val writesBefore = link.writes.size

        live.start(LiveMode.HEART_RATE) // the same Measure button again
        advanceTo(31_000)

        assertEquals(
            listOf(
                TimedWrite(30_000, Wire.STATUS_QUERY),
                TimedWrite(30_250, Wire.HR_MODE),
                TimedWrite(30_500, Wire.FETCH),
                TimedWrite(30_750, Wire.POLL), // the running loop's own cadence, not restarted
            ),
            link.writes.drop(writesBefore),
        )
        advanceTo(30_000 + 90_000 - 1)
        assertTrue(live.isMeasuring.value, "the budget runs from the re-tap")
        advanceTo(30_000 + 90_000)
        assertFalse(live.isMeasuring.value)
    }

    @Test
    fun switchingModeWritesTheOtherModeAndArmsItsOwnBudget() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        live.start(LiveMode.HEART_RATE)
        advanceTo(20_000)
        val writesBefore = link.writes.size

        live.start(LiveMode.SPO2)
        advanceTo(20_500)

        assertEquals(LiveMode.SPO2, live.state.value.mode)
        assertEquals(
            listOf(
                TimedWrite(20_000, Wire.STATUS_QUERY),
                TimedWrite(20_250, Wire.SPO2_MODE),
                TimedWrite(20_500, Wire.FETCH),
            ),
            link.writes.drop(writesBefore),
        )
        advanceTo(20_000 + 45_000 - 1)
        assertTrue(live.isMeasuring.value)
        advanceTo(20_000 + 45_000)
        assertFalse(live.isMeasuring.value, "the SpO₂ budget from the switch")
    }

    @Test
    fun aReTapWhileTheEntryIsStillBeingWrittenDoesNotWriteASecondEntry() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        live.start(LiveMode.HEART_RATE)
        advanceTo(100)

        live.start(LiveMode.HEART_RATE)
        advanceTo(ARMED_AT)

        assertEquals(listOf(Wire.STATUS_QUERY, Wire.HR_MODE, Wire.FETCH), link.writes.map { it.hex })
    }

    private companion object {
        /** When the budget is armed: after the three entry writes and their 250 ms sleeps. */
        const val ARMED_AT = 750L
    }
}
