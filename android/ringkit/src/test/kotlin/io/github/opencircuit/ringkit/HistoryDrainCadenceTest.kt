package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How often a connected, idle ring's history is drained, and the overnight-quiet gate that
 * suppresses automatic drains inside the sleep window.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HistoryDrainCadenceTests.swift
 * (@ b1c2fdd): all 9 tests. `:24` used the wall clock for `now`; here it is a fixed instant.
 */
class HistoryDrainCadenceTest {

    // :6
    @Test
    fun nightIsTighterThanDay() {
        assertTrue(
            HistoryDrainCadence.interval(isNight = true, batterySaver = false) <
                HistoryDrainCadence.interval(isNight = false, batterySaver = false),
        )
    }

    // :11 — even relaxed, one interval leaves headroom under the ring buffer (~114 epochs × 150 s).
    @Test
    fun batterySaverRelaxesBothButStaysUnderBuffer() {
        val buffer = Duration.ofSeconds((4.75 * 3600).toLong())
        for (night in listOf(true, false)) {
            val saver = HistoryDrainCadence.interval(isNight = night, batterySaver = true)
            val normal = HistoryDrainCadence.interval(isNight = night, batterySaver = false)
            assertTrue(saver > normal)
            assertTrue(saver < buffer)
        }
    }

    // :23
    @Test
    fun dueWhenNeverDrained() {
        assertTrue(HistoryDrainCadence.isDue(lastDrainAt = null, now = Instant.ofEpochSecond(1_000_000), isNight = true, batterySaver = false))
    }

    // :28 — night interval is 30 min, so 20 min ago is NOT yet due.
    @Test
    fun notDueBeforeIntervalElapses() {
        val now = Instant.ofEpochSecond(1_000_000)
        val last = now.minus(Duration.ofMinutes(20))
        assertFalse(HistoryDrainCadence.isDue(lastDrainAt = last, now = now, isNight = true, batterySaver = false))
    }

    // :36
    @Test
    fun dueAfterIntervalElapses() {
        val now = Instant.ofEpochSecond(1_000_000)
        val last = now.minus(Duration.ofMinutes(40))
        assertTrue(HistoryDrainCadence.isDue(lastDrainAt = last, now = now, isNight = true, batterySaver = false))
    }

    // :43
    @Test
    fun boundaryExactlyAtIntervalIsDue() {
        val now = Instant.ofEpochSecond(1_000_000)
        val last = now.minus(HistoryDrainCadence.interval(isNight = false, batterySaver = false))
        assertTrue(HistoryDrainCadence.isDue(lastDrainAt = last, now = now, isNight = false, batterySaver = false))
    }

    // :55 — inside the sleep window an AUTOMATIC drain is suppressed however overdue it is.
    @Test
    fun overnightSuppressesAutomaticDrainEvenWhenDue() {
        assertFalse(
            HistoryDrainCadence.shouldDrain(manual = false, inSleepWindow = true, isDue = true),
            "an automatic drain inside the sleep window must be suppressed",
        )
    }

    // :61 — a user-initiated sync ALWAYS drains.
    @Test
    fun manualSyncAlwaysDrains() {
        assertTrue(HistoryDrainCadence.shouldDrain(manual = true, inSleepWindow = true, isDue = false), "a manual sync bypasses the overnight-quiet gate")
        assertTrue(HistoryDrainCadence.shouldDrain(manual = true, inSleepWindow = false, isDue = false))
    }

    // :70 — outside the window the gate is transparent: shouldDrain mirrors isDue.
    @Test
    fun daytimeIsUnchangedAndWakeCatchUpDrains() {
        assertTrue(
            HistoryDrainCadence.shouldDrain(manual = false, inSleepWindow = false, isDue = true),
            "daytime / wake catch-up: due + out-of-window → drain",
        )
        assertFalse(
            HistoryDrainCadence.shouldDrain(manual = false, inSleepWindow = false, isDue = false),
            "daytime not-due → no drain (mirrors isDue)",
        )
    }
}
