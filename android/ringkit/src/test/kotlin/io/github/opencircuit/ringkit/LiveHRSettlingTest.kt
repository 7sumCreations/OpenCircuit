package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A user-facing live HR must never be a single poll frame. See `LiveHR.settled`.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/LiveHRSettlingTests.swift:13-52
 * (@ b1c2fdd) — all 5 tests.
 *
 * The fixture in `theRealCaptureSpreadIsWhyThisExists` is the exact locked sequence
 * `RingKitVerify` decodes from the FR02.018 poll capture — the only real one upstream holds.
 *
 * `settled` is pure over its input list, so there is no per-instance state to test across reuse
 * (so a reuse test does not apply here). The caller that keeps the trend across reconnects
 * (E7/E8 `liveHRTrend`) owes its own reuse test — this class does not cover it.
 */
class LiveHRSettlingTest {

    /**
     * Below the window there is NO answer yet — the caller must keep saying "measuring…"
     * rather than showing the newest frame, which is the defect being fixed.
     */
    @Test
    fun noReadingUntilTheWindowFills() { // :13-19
        for (n in 0 until LiveHR.SETTLE_SAMPLE_COUNT) {
            assertNull(LiveHR.settled(List(n) { 70 }), "$n locked frames must not produce a finished reading")
        }
        assertEquals(70, LiveHR.settled(List(LiveHR.SETTLE_SAMPLE_COUNT) { 70 }))
    }

    /**
     * THE REGRESSION. The real capture's locked frames span 61…91 inside one read; the last
     * frame is 61. Showing that raw is the "abnormally low" reading a tester reported.
     */
    @Test
    fun theRealCaptureSpreadIsWhyThisExists() { // :23-30
        val realLocked = listOf(82, 84, 88, 90, 91, 66, 61) // RingKitVerify `realHRFrames`, warm-up dropped
        assertEquals(61, realLocked.last(), "the raw last-frame display would show 61")
        val settled = LiveHR.settled(realLocked)
        assertEquals(88, settled, "median of the last 5 (88,90,91,66,61 -> 88)")
        assertTrue(settled!! > realLocked.last(), "settling must lift the answer off the low tail frame")
    }

    /**
     * One dropout frame cannot move the answer — the 2-sample breakdown point the constant's
     * doc claims. Both a low spike and a high spike are rejected.
     */
    @Test
    fun aSingleOutlierCannotMoveTheAnswer() { // :34-39
        // sorted [30,70,70,71,71] -> 70; the low spike is discarded, not averaged in.
        assertEquals(70, LiveHR.settled(listOf(70, 71, 30, 70, 71)))
        // sorted [70,70,71,71,210] -> 71; likewise for the high spike.
        assertEquals(71, LiveHR.settled(listOf(70, 71, 210, 70, 71)))
    }

    /**
     * Only the most recent window counts, so a read that genuinely changes converges rather
     * than being anchored by stale frames.
     */
    @Test
    fun onlyTheTrailingWindowCounts() { // :43-45
        assertEquals(92, LiveHR.settled(listOf(40, 40, 40, 40, 90, 91, 92, 93, 94)))
    }

    /**
     * `settled` consumes values that already passed `decodeLocked`, so the band guard is the
     * caller's job — pin that contract so nobody "helpfully" re-filters here and changes the
     * median's breakdown behaviour.
     */
    @Test
    fun takesTheTrendVerbatimAndDoesNotRefilter() { // :50-52
        assertEquals(30, LiveHR.settled(listOf(30, 30, 30, 30, 30)), "30 is in band and must survive")
    }
}
