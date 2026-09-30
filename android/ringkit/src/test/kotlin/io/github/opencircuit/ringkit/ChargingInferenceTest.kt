package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Charging inference from battery % trend (upstream #60): fires only on a strictly rising
 * sequence of ≥ 2 readings; any flat, falling, or mixed pattern returns false.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ChargingInferenceTests.swift:10-66
 * (@ b1c2fdd) — all 13 tests. Pulled into E1 from E2 because `DeviceStatus.isCharging` delegates to it.
 */
class ChargingInferenceTest {

    // Degenerate inputs

    @Test
    fun emptyTrendReturnsFalse() { // :10-12
        assertFalse(ChargingInference.inferred(emptyList()))
    }

    @Test
    fun singleReadingReturnsFalse() { // :14-16
        assertFalse(ChargingInference.inferred(listOf(75)))
    }

    // Rising (charging)

    @Test
    fun twoRisingReadingsReturnsTrue() { // :20-22
        assertTrue(ChargingInference.inferred(listOf(74, 76)))
    }

    @Test
    fun threeRisingReadingsReturnsTrue() { // :24-26
        assertTrue(ChargingInference.inferred(listOf(74, 76, 78)))
    }

    @Test
    fun fourRisingReadingsReturnsTrue() { // :28-30
        assertTrue(ChargingInference.inferred(listOf(60, 65, 70, 75)))
    }

    @Test
    fun risingByOneReturnsTrueEachPair() { // :32-35
        // Single-unit increments still count as rising.
        assertTrue(ChargingInference.inferred(listOf(80, 81, 82)))
    }

    // Non-rising (not charging)

    @Test
    fun flatTwoReadingsReturnsFalse() { // :39-41
        assertFalse(ChargingInference.inferred(listOf(75, 75)))
    }

    @Test
    fun flatThreeReadingsReturnsFalse() { // :43-45
        assertFalse(ChargingInference.inferred(listOf(75, 75, 75)))
    }

    @Test
    fun fallingTwoReadingsReturnsFalse() { // :47-49
        assertFalse(ChargingInference.inferred(listOf(80, 78)))
    }

    @Test
    fun fallingThreeReadingsReturnsFalse() { // :51-53
        assertFalse(ChargingInference.inferred(listOf(80, 78, 76)))
    }

    // Mixed (one pair not rising → whole sequence fails)

    @Test
    fun mixedRisingThenFlatReturnsFalse() { // :57-59
        assertFalse(ChargingInference.inferred(listOf(74, 76, 76)))
    }

    @Test
    fun mixedRisingThenFallingReturnsFalse() { // :61-63
        assertFalse(ChargingInference.inferred(listOf(74, 76, 75)))
    }

    @Test
    fun mixedFlatThenRisingReturnsFalse() { // :65-67
        assertFalse(ChargingInference.inferred(listOf(74, 74, 76)))
    }
}
