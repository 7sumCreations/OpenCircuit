package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `RecorderStall.verdict` must never blame the ring for something we did not measure.
 *
 * The scenario every test here is anchored to is the real one: a Gen 2 Air export whose newest
 * `0x4c` epoch was 4 h 04 m old at 01:52 local while the ring was connected and skin temperature was
 * 12 s fresh, and whose manual sync at 01:52 returned an empty, acknowledged drain with no pages.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RecorderStallTests.swift
 * (@ b1c2fdd): all 7 tests. Upstream's fractional hours are exact milliseconds here.
 */
class RecorderStallTest {

    private val now = Instant.ofEpochSecond(1_788_000_000)

    private fun ago(hours: Double): Instant = now.minusMillis(Math.round(hours * 3_600_000))

    // :16 — a ring handing over epochs on the normal 150 s cadence is recording; nothing to say.
    @Test
    fun freshHeadIsRecording() {
        assertEquals(
            RecorderStall.Verdict.Recording,
            RecorderStall.verdict(newestEpochAt = ago(0.05), completedDrainsSinceHeadMoved = 9, isCharging = false, now = now),
        )
    }

    // :25 — THE HONESTY CASE. Stale head, but not drained enough times to know whose fault it is.
    @Test
    fun staleButUndrainedIsNotARingFault() {
        for (drains in 0 until RecorderStall.MINIMUM_UNMOVED_DRAINS) {
            assertEquals(
                RecorderStall.Verdict.UnknownNotDrained,
                RecorderStall.verdict(newestEpochAt = ago(4.0), completedDrainsSinceHeadMoved = drains, isCharging = false, now = now),
                "$drains completed drains cannot prove a stall",
            )
        }
    }

    // :37 — ONE completed-but-empty drain is explicitly not enough.
    @Test
    fun oneEmptyDrainIsNotAStall() {
        assertEquals(
            RecorderStall.Verdict.UnknownNotDrained,
            RecorderStall.verdict(newestEpochAt = ago(17.0), completedDrainsSinceHeadMoved = 1, isCharging = false, now = now),
        )
    }

    // :45 — the tester's actual state: 4 h stale, repeatedly drained, connected, not charging.
    @Test
    fun theTesterCaseIsCalledAStall() {
        assertEquals(
            RecorderStall.Verdict.Stalled(since = ago(4.07)),
            RecorderStall.verdict(newestEpochAt = ago(4.07), completedDrainsSinceHeadMoved = 3, isCharging = false, now = now),
        )
    }

    // :54 — charging outranks the drain count, so a docked ring can never produce a fault message.
    @Test
    fun chargingIsNeverAFault() {
        assertEquals(
            RecorderStall.Verdict.ExpectedWhileCharging,
            RecorderStall.verdict(newestEpochAt = ago(17.0), completedDrainsSinceHeadMoved = 99, isCharging = true, now = now),
        )
    }

    // :62 — no epochs at all is not a stall; it is a ring we have never drained.
    @Test
    fun noEpochsIsUnknownNotStalled() {
        assertEquals(
            RecorderStall.Verdict.UnknownNotDrained,
            RecorderStall.verdict(newestEpochAt = null, completedDrainsSinceHeadMoved = 99, isCharging = false, now = now),
        )
    }

    // :71 — just inside the threshold stays quiet, exactly at it reports. Pins the stale bound
    // against a silent retune.
    @Test
    fun staleAfterBoundaryHolds() {
        val justFresh = now.minus(RecorderStall.STALE_AFTER).plus(Duration.ofSeconds(1))
        assertEquals(
            RecorderStall.Verdict.Recording,
            RecorderStall.verdict(newestEpochAt = justFresh, completedDrainsSinceHeadMoved = 9, isCharging = false, now = now),
        )
        val justStale = now.minus(RecorderStall.STALE_AFTER)
        assertEquals(
            RecorderStall.Verdict.Stalled(since = justStale),
            RecorderStall.verdict(newestEpochAt = justStale, completedDrainsSinceHeadMoved = 9, isCharging = false, now = now),
        )
    }
}
