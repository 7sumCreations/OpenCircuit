package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMeasureController
import io.github.opencircuit.app.live.LiveMode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the live measure makes of the `0x15` frames: the newest reading for the readout and chart,
 * the settled heart rate (median of the last 5 locked frames), SpO₂ from the long frames, and
 * the readout going stale after 30 s without a frame. Frames are real captures or raw-path
 * variants of them (`TestFrames`), XOR-checked first as upstream does
 * (`ios/OpenCircuit/BLE/RingSession.swift:5336` @ b1c2fdd).
 */
class LiveSettledTest {

    private fun TestScope.controllerOn(link: TimedRingLink) =
        LiveMeasureController(send = link::send, scope = backgroundScope, monotonicMillis = { testScheduler.currentTime })

    @Test
    fun theSettledHeartRateIsTheMedianOfTheLast5LockedFrames() = runTest {
        val live = controllerOn(timedLink())
        live.start(LiveMode.HEART_RATE)
        advanceTo(2_750)

        // Real read: warm-up 8, then 82 84 88 90 91 66 61, plus 61 once more.
        val frames = TestFrames.realHeartRateRead + listOf(TestFrames.realHeartRateRead.last())
        frames.take(5).forEach(live::onFrame) // warm-up + 4 locked
        assertNull(live.state.value.settledHeartRate, "4 locked frames are not a reading yet")
        assertEquals(90, live.state.value.newest, "the readout shows the newest locked frame")

        frames.drop(5).forEach(live::onFrame) // 91 66 61 61
        // Last 5 locked: 90 91 66 61 61 → median 66. (First 5 → 88, their mean → 73.8, newest → 61.)
        assertEquals(66, live.state.value.settledHeartRate)
        assertEquals(61, live.state.value.newest)
    }

    @Test
    fun warmUpAndOutOfBandHeartRatesAreNotReadingsButShowTheRingIsWarmingUp() = runTest {
        val live = controllerOn(timedLink())
        live.start(LiveMode.HEART_RATE)
        advanceTo(2_750)

        live.onFrame(TestFrames.heartRateWarmUp)
        live.onFrame(TestFrames.heartRate29)
        live.onFrame(TestFrames.heartRate225)
        assertNull(live.state.value.newest)
        assertTrue(live.state.value.warmingUp, "frames arrive, none locked yet")
        assertEquals(emptyList(), live.state.value.session.points)

        live.onFrame(TestFrames.heartRate30)
        live.onFrame(TestFrames.heartRate220)
        assertEquals(220, live.state.value.newest, "30 and 220 are inside the band")
        assertFalse(live.state.value.warmingUp)
        assertEquals(listOf(30, 220), live.state.value.session.points.map { it.value })
        assertEquals(30..220, live.state.value.session.range)
    }

    @Test
    fun spo2ComesFromTheLongFramesAndOutOfRangeValuesAreIgnored() = runTest {
        val live = controllerOn(timedLink())
        live.start(LiveMode.SPO2)
        advanceTo(2_750)

        live.onFrame(TestFrames.spo2At69)
        live.onFrame(TestFrames.spo2At101)
        live.onFrame(TestFrames.liveHeartRate) // a short heart-rate frame carries no SpO₂
        assertNull(live.state.value.newest)

        live.onFrame(TestFrames.spo2At70)
        TestFrames.realSpO2Read.forEach(live::onFrame)
        assertEquals(97, live.state.value.newest)
        assertEquals(listOf(70, 96, 96, 97), live.state.value.session.points.map { it.value })
        assertNull(live.state.value.settledHeartRate, "SpO₂ has no settling")
    }

    @Test
    fun aFrameWithABadTrailerIsDroppedAndCounted() = runTest {
        val live = controllerOn(timedLink())
        live.start(LiveMode.HEART_RATE)
        advanceTo(2_750)

        live.onFrame(TestFrames.heartRateBadTrailer)

        assertNull(live.state.value.newest)
        assertEquals(1, live.state.value.framesNotUsed)
    }

    @Test
    fun aLiveFrameWhileNothingIsMeasuredIsCountedNotShown() = runTest {
        val live = controllerOn(timedLink())

        live.onFrame(TestFrames.liveHeartRate)

        assertNull(live.state.value.newest)
        assertEquals(1, live.state.value.framesNotUsed)
    }

    @Test
    fun theReadoutGoesStale30SecondsAfterTheLastFrameAndANewFrameRefreshesIt() = runTest {
        val live = controllerOn(timedLink())
        live.start(LiveMode.HEART_RATE)
        advanceTo(10_000)
        live.onFrame(TestFrames.liveHeartRate)

        advanceTo(40_000 - 1)
        assertFalse(live.state.value.stale, "29.999 s after the frame")
        advanceTo(40_000)
        assertTrue(live.state.value.stale, "30 s after the frame")

        live.onFrame(TestFrames.liveHeartRate)
        assertFalse(live.state.value.stale)
        assertEquals(91, live.state.value.newest)
    }

    @Test
    fun theChartAndRangeTakeEveryLockedFrameStampedWithItsArrival() = runTest {
        val live = controllerOn(timedLink())
        live.start(LiveMode.HEART_RATE)
        advanceTo(3_000)
        live.onFrame(TestFrames.realHeartRateRead[1]) // 82
        advanceTo(5_000)
        live.onFrame(TestFrames.realHeartRateRead[1]) // 82 again: a repeated value is still a point
        advanceTo(7_000)
        live.onFrame(TestFrames.realHeartRateRead[7]) // 61

        val session = live.state.value.session
        assertEquals(listOf(3_000L to 82, 5_000L to 82, 7_000L to 61), session.points.map { it.atMillis to it.value })
        assertEquals(61..82, session.range)
    }
}
