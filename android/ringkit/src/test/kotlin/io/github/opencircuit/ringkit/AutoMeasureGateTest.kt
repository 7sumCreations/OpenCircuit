package io.github.opencircuit.ringkit

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto-measure wear gate: infers "not worn" from consecutive auto-measures that never lock (🟢) plus
 * an optional cold raw skin-temperature reading (🟡), and backs the probe interval off exponentially
 * so a ring on the charger isn't probed every 10 min. No charging-flag byte is consulted (undecoded).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/AutoMeasureGateTests.swift
 * (@ b1c2fdd): all 8 tests. `:36` reads the shared wear threshold, `ActivityPeriod.WORN_MIN_TEMPERATURE_C`.
 */
class AutoMeasureGateTest {

    // MARK: not-worn inference

    // :13
    @Test
    fun wornUntilThresholdWithoutTemperature() {
        assertFalse(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 0))
        assertFalse(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 1), "one transient miss (e.g. a moving hand) is not yet not-worn")
        assertTrue(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 2))
        assertTrue(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 9))
    }

    // :21 — a warm reading is direct evidence of wear even after many missed locks.
    @Test
    fun warmSkinTempIsAlwaysWorn() {
        assertFalse(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 9, rawSkinTempC = 32.0))
        assertFalse(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 0, rawSkinTempC = 30.0))
    }

    // :27 — cold alone (no miss yet) is NOT enough: a worn-but-cool ring would have locked.
    @Test
    fun coldSkinTempConfirmsNotWornAfterOneMiss() {
        assertFalse(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 0, rawSkinTempC = 22.0), "cold reading with no failed lock is not conclusive")
        assertTrue(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 1, rawSkinTempC = 22.0), "cold + a missed lock ⇒ not worn")
    }

    // :35
    @Test
    fun thresholdBoundaryMatchesWornMinTemperature() {
        val worn = ActivityPeriod.WORN_MIN_TEMPERATURE_C // 28 °C
        assertFalse(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 1, rawSkinTempC = worn), "exactly at the worn threshold counts as worn")
        assertTrue(AutoMeasureGate.appearsNotWorn(consecutiveNoLock = 1, rawSkinTempC = worn - 0.1))
    }

    // MARK: backoff schedule

    private val base: Duration = Duration.ofSeconds(600) // 10 min
    private val cap: Duration = Duration.ofSeconds(7200) // 2 h

    // :47
    @Test
    fun intervalStaysAtBaseWhileWorn() {
        assertEquals(base, AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 0))
        assertEquals(base, AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 1), "a single miss without temp evidence does not back off yet")
        assertEquals(base, AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 9, rawSkinTempC = 33.0), "warm skin ⇒ keep the normal cadence")
    }

    // :55
    @Test
    fun intervalDoublesOnceNotWornThenCaps() {
        assertEquals(Duration.ofSeconds(1200), AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 2)) // ×2
        assertEquals(Duration.ofSeconds(2400), AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 3)) // ×4
        assertEquals(Duration.ofSeconds(4800), AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 4)) // ×8
        assertEquals(cap, AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 5), "base ×16 = 9600 is clamped to the 2 h cap")
        assertEquals(cap, AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 99), "stays capped")
    }

    // :65 — cold + 1 miss is not-worn, but the first backed-off interval is still `base` (0 doublings).
    @Test
    fun coldTempBacksOffFromTheFirstConfirmedMiss() {
        assertEquals(base, AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 1, rawSkinTempC = 21.0))
        assertEquals(Duration.ofSeconds(1200), AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 2, rawSkinTempC = 21.0))
    }

    // :72
    @Test
    fun intervalIsMonotonicNonDecreasing() {
        var prev = AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = 0)
        for (n in 1..12) {
            val v = AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = n)
            assertTrue(v >= prev, "n=$n: $v < $prev")
            prev = v
        }
    }
}
