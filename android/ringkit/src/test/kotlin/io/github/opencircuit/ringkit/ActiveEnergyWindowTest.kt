package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ActiveEnergyWindowTests.swift
// (@ b1c2fdd): every test, in upstream order.

/**
 * Locks the clamps in `ActiveEnergyWindow.resolve`. Each of these is a way the naive
 * "[last flush, now]" window silently corrupts the Health store — several of them permanently, since
 * the active-energy flush never backfills a past day.
 */
class ActiveEnergyWindowTest {

    private val day: Instant = Instant.ofEpochSecond(1_785_000_000) // some start-of-day
    private fun t(hours: Double): Instant = day.plusMillis(Math.round(hours * 3_600_000))

    // MARK: The reported bug

    @Test
    fun testFirstFlushOfDayStartsAtWakeNotMidnight() {
        // The tester's exact shape: overnight-quiet suppresses drains, so the day's first flush is the
        // ~09:00 wake catch-up. Without `notBefore` this would be [00:00, 09:00] and Health would still
        // paint active energy across the night — "300 calories at 12am".
        val w = ActiveEnergyWindow.resolve(anchor = null, notBefore = t(7.5), now = t(9.0), dayStart = day)
        assertEquals(t(7.5), w?.start)
        assertEquals(t(9.0), w?.end)
    }

    @Test
    fun testSubsequentFlushTilesFromThePreviousWindowEnd() {
        // Consecutive deltas must not overlap — the store SUMs active energy.
        val w = ActiveEnergyWindow.resolve(anchor = t(9.0), notBefore = t(7.5), now = t(10.0), dayStart = day)
        assertEquals(t(9.0), w?.start)
        assertEquals(t(10.0), w?.end)
    }

    // MARK: The day floor — the load-bearing clamp

    @Test
    fun testUnsetUserDefaultsEpochAnchorIsClampedToDayStart() {
        // An unset stored date reads back as the epoch. Unclamped, the first sample after install would
        // span 1970→now and Health would apportion today's kcal across five decades.
        val epoch = Instant.ofEpochSecond(0)
        val w = ActiveEnergyWindow.resolve(anchor = epoch, notBefore = null, now = t(9.0), dayStart = day)
        assertEquals(day, w?.start)
    }

    @Test
    fun testAnchorFromAnEarlierDayIsClampedToDayStart() {
        // A multi-day gap (app never opened, no background task ran) must not stamp today's delta
        // backwards across days whose totals were already final — that inflation is unretractable.
        val w = ActiveEnergyWindow.resolve(anchor = day.minusSeconds(3 * 86_400L), notBefore = null, now = t(9.0), dayStart = day)
        assertEquals(day, w?.start)
    }

    @Test
    fun testStaleWakeFromAPriorNightIsClampedToDayStart() {
        val w = ActiveEnergyWindow.resolve(anchor = null, notBefore = day.minusSeconds(2 * 3600L), now = t(9.0), dayStart = day)
        assertEquals(day, w?.start)
    }

    @Test
    fun testNoBoundsAtAllFallsBackToTheWholeElapsedDay() {
        // Fresh install mid-day with no sleep summary yet: start-of-day is genuinely the best information
        // available, so this is honest rather than wrong.
        val w = ActiveEnergyWindow.resolve(anchor = null, notBefore = null, now = t(15.0), dayStart = day)
        assertEquals(day, w?.start)
        assertEquals(t(15.0), w?.end)
    }

    // MARK: Tightest bound wins

    @Test
    fun testAnchorWinsWhenItIsLaterThanWake() {
        val w = ActiveEnergyWindow.resolve(anchor = t(11.0), notBefore = t(7.5), now = t(12.0), dayStart = day)
        assertEquals(t(11.0), w?.start)
    }

    @Test
    fun testAnchorWinsOverWakeOnceAWindowHasBeenWrittenToday() {
        // Anchor is authoritative once today has a written window — `notBefore` is a FIRST-FLUSH floor
        // only. See testNotBeforeIsAFirstFlushFloorAndDoesNotCompressLaterWindows for why re-applying it
        // later would compress a delta into a sliver.
        val w = ActiveEnergyWindow.resolve(anchor = t(2.0), notBefore = t(7.5), now = t(9.0), dayStart = day)
        assertEquals(t(2.0), w?.start)
    }

    // MARK: Refuse to write rather than throw

    @Test
    fun testFutureAnchorFallsBackToTheDayFloorRatherThanSkipping() {
        // The store REJECTS end < start, so a future bound must never reach it. Earlier this returned
        // nil, but a nil skips the write WITHOUT advancing the anchor — so a clock step-forward would
        // wedge active energy off until wall-clock caught up, with no self-heal. Discarding the
        // impossible anchor writes now and re-bases it instead.
        val w = ActiveEnergyWindow.resolve(anchor = t(12.0), notBefore = null, now = t(9.0), dayStart = day)
        assertEquals(day, w?.start)
        assertEquals(t(9.0), w?.end)
    }

    @Test
    fun testFutureWakeIsIgnoredRatherThanSkipping() {
        // The night window's end can be TONIGHT's. Same reasoning: ignore the impossible floor, don't stall.
        val w = ActiveEnergyWindow.resolve(anchor = null, notBefore = t(23.0), now = t(9.0), dayStart = day)
        assertEquals(day, w?.start)
    }

    @Test
    fun testZeroWidthWindowYieldsNoWindow() {
        // Two flushes inside the same instant. Skipping is self-healing: the kcal is still owed and rides
        // into the next delta.
        assertNull(ActiveEnergyWindow.resolve(anchor = t(9.0), notBefore = null, now = t(9.0), dayStart = day))
    }

    @Test
    fun testNowBeforeDayStartYieldsNoWindow() {
        assertNull(ActiveEnergyWindow.resolve(anchor = null, notBefore = null, now = day.minusSeconds(1), dayStart = day))
    }

    // MARK: Anchor is authoritative once written today (review: night grows on re-stage)

    @Test
    fun testNotBeforeIsAFirstFlushFloorAndDoesNotCompressLaterWindows() {
        // A stored night only ever GROWS: the morning-tail rescue and re-stage passes legitimately push
        // the in-bed end LATER hours after wake. If notBefore were re-applied above an anchor already
        // written today, elapsed time the delta genuinely accrued in would be discarded and the whole
        // delta would land in whatever sliver remained.
        val anchor = t(8.0)
        val grownWake = t(9.5) // re-stage moved the in-bed end forward, past the anchor
        val w = ActiveEnergyWindow.resolve(anchor = anchor, notBefore = grownWake, now = t(10.0), dayStart = day)
        assertEquals(anchor, w?.start, "an already-written anchor must win over a grown wake time")
    }

    // MARK: A future anchor must not wedge the writer permanently

    @Test
    fun testFutureAnchorIsDiscardedRatherThanWedgingTheWriter() {
        // Clock step-forward (bad RTC before network time, restored backup, manual date change). A skipped
        // write never advances the anchor, so honouring it would kill active energy until wall-clock
        // caught up — hours with nothing reaching Health and no self-heal path.
        val w = ActiveEnergyWindow.resolve(anchor = t(20.0), notBefore = t(7.5), now = t(9.0), dayStart = day)
        assertNotNull(w, "a future anchor must be discarded, not honoured as a floor")
        assertEquals(t(7.5), w.start)
    }

    // MARK: Implausible burn rates widen the window instead of spiking

    @Test
    fun testLargeDeltaOverATinyWindowIsWidenedNotSpiked() {
        // Data arrives in BULK: a drain after hours out of range banks a whole afternoon at once, and a
        // flush seconds after the previous one would otherwise stamp all of it into a sub-minute window —
        // Health then records e.g. 190 kcal in 44 s, forever.
        val w = ActiveEnergyWindow.resolve(anchor = t(9.0), notBefore = null, now = t(9.0).plusSeconds(44), dayStart = day, kcal = 190.0)
        val minutes = assertNotNull(w).duration.toNanos() / 1e9 / 60.0
        assertTrue(minutes > 44.0 / 60.0, "window must have been widened")
        assertTrue(190 / minutes <= ActiveEnergyWindow.MAX_PLAUSIBLE_KCAL_PER_MINUTE + 0.001)
    }

    @Test
    fun testWideningNeverEscapesTheDay() {
        // A huge delta early in the day can only widen back to midnight, never into yesterday.
        val w = ActiveEnergyWindow.resolve(anchor = null, notBefore = null, now = t(0.5), dayStart = day, kcal = 5000.0)
        assertEquals(day, w?.start)
    }

    @Test
    fun testPlausibleDeltaIsNotWidened() {
        // 5 kcal over 30 min is ~0.17 kcal/min — nowhere near the ceiling, so leave it alone.
        val w = ActiveEnergyWindow.resolve(anchor = t(9.0), notBefore = null, now = t(9.5), dayStart = day, kcal = 5.0)
        assertEquals(t(9.0), w?.start)
    }

    @Test
    fun testZeroKcalDoesNotWiden() {
        val w = ActiveEnergyWindow.resolve(anchor = t(9.0), notBefore = null, now = t(9.0).plusSeconds(10), dayStart = day, kcal = 0.0)
        assertEquals(t(9.0), w?.start)
    }

    // MARK: Invariants that must hold for the store's SUM to stay exact

    @Test
    fun testWindowNeverEscapesTheCalendarDayItBelongsTo() {
        for (anchor in listOf(null, Instant.ofEpochSecond(0), t(-5.0), t(3.0))) {
            for (notBefore in listOf(null, t(-2.0), t(7.5))) {
                val w = ActiveEnergyWindow.resolve(anchor = anchor, notBefore = notBefore, now = t(9.0), dayStart = day) ?: continue
                assertTrue(w.start >= day, "window started before its own day")
                assertTrue(w.start < w.end, "window inverted or empty")
                assertEquals(t(9.0), w.end)
            }
        }
    }
}
