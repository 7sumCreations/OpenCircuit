package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A SUSTAINED run of implausible skin-temperature readings is a decode-drift signal; a single
 * spike is not. See `DecodeAnomaly.hasSustainedTemperatureAnomaly`.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/DecodeAnomalyTests.swift:49-73
 * (@ b1c2fdd) — the 5 temperature tests. The 4 `detect(records:)` tests (:23-45) need
 * `BulkRecord` and are deferred to E2 (PORTING.md D-7). Kotlin-only edges live in
 * `DecodeAnomalyEdgeTest`, so this class keeps upstream's exact count.
 */
class DecodeAnomalyTest {

    @Test
    fun noTemperatureAnomalyForNormalReadings() { // :49-52
        val readings = listOf(30.0, 31.0, 32.5, 33.0, 34.5, 30.2)
        assertFalse(DecodeAnomaly.hasSustainedTemperatureAnomaly(readings))
    }

    @Test
    fun singleSpikeDoesNotFlag() { // :54-58
        // One bad reading among good ones (e.g. a transient donning artifact) resets the run.
        val readings = listOf(30.0, 31.0, 99.0, 32.0, 33.0, 99.0, 31.0)
        assertFalse(DecodeAnomaly.hasSustainedTemperatureAnomaly(readings))
    }

    @Test
    fun sustainedOutOfRangeRunFlags() { // :60-63
        val readings = listOf(30.0, 31.0, 99.0, 99.0, 99.0, 99.0, 99.0)
        assertTrue(DecodeAnomaly.hasSustainedTemperatureAnomaly(readings))
    }

    @Test
    fun sustainedRunBelowMinimumFlags() { // :65-68
        val readings = listOf(5.0, 4.0, 3.0, 2.0, 1.0)
        assertTrue(DecodeAnomaly.hasSustainedTemperatureAnomaly(readings))
    }

    @Test
    fun runShorterThanThresholdDoesNotFlag() { // :70-73
        val readings = listOf(30.0, 99.0, 99.0, 99.0, 30.0)
        assertFalse(DecodeAnomaly.hasSustainedTemperatureAnomaly(readings, sustainedRun = 5))
    }
}
