package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Distance from the decoded step count: RingConn's own fixed per-step constant (PROTOCOL.md §5.3.1).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/DistanceEstimateTests.swift (@ b1c2fdd)
 * — all 5 tests.
 */
class DistanceEstimateTest {

    // Per-step constant (PROTOCOL.md §5.3.1 — RingConn's own fixed multiplier)

    @Test
    fun metersPerStepConstant() { // :8-10
        assertEquals(0.248, DistanceEstimate.METERS_PER_STEP, 0.0001)
    }

    // Distance in metres

    @Test
    fun distance10000Steps() { // :14-17
        // 10000 × 0.248 = 2480 m
        assertEquals(2480.0, DistanceEstimate.meters(steps = 10_000), 0.01)
    }

    @Test
    fun distance8000Steps() { // :19-22
        // 8000 × 0.248 = 1984 m
        assertEquals(1984.0, DistanceEstimate.meters(steps = 8_000), 0.01)
    }

    @Test
    fun distanceZeroSteps() { // :24-26
        assertEquals(0.0, DistanceEstimate.meters(steps = 0))
    }

    @Test
    fun distanceNegativeStepsReturnsZero() { // :28-30
        assertEquals(0.0, DistanceEstimate.meters(steps = -100))
    }
}
