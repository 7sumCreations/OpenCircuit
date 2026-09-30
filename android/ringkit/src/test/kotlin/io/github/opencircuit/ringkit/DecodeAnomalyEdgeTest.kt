package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kotlin-only edges of `DecodeAnomaly` that upstream's `DecodeAnomalyTests.swift` does not test:
 * the band ends are inclusive-plausible, an empty input, a run straddling the band on both
 * sides, the `sustainedRun` / band parameters actually being honoured, and the stable raw values.
 */
class DecodeAnomalyEdgeTest {

    @Test
    fun bandEndsThemselvesAreNotAnomalous() {
        // `r < minC || r > maxC` — exactly 15.0 and 45.0 are plausible, so five of them never flag.
        assertFalse(DecodeAnomaly.hasSustainedTemperatureAnomaly(List(5) { 15.0 }))
        assertFalse(DecodeAnomaly.hasSustainedTemperatureAnomaly(List(5) { 45.0 }))
        // Just outside each end does.
        assertTrue(DecodeAnomaly.hasSustainedTemperatureAnomaly(List(5) { 14.99 }))
        assertTrue(DecodeAnomaly.hasSustainedTemperatureAnomaly(List(5) { 45.01 }))
    }

    @Test
    fun emptyAndShortInputsNeverFlag() {
        assertFalse(DecodeAnomaly.hasSustainedTemperatureAnomaly(emptyList()))
        assertFalse(DecodeAnomaly.hasSustainedTemperatureAnomaly(List(4) { 99.0 }))
    }

    @Test
    fun anInBandReadingResetsTheRunEvenWhenTheTotalReachesTheThreshold() {
        // Upstream's tests never hold >= sustainedRun bad readings split across runs, so a counter
        // that never resets passes all of them (mutation-proven). 3 + 3 bad, split by one good.
        assertFalse(DecodeAnomaly.hasSustainedTemperatureAnomaly(listOf(99.0, 99.0, 99.0, 30.0, 99.0, 99.0, 99.0)))
        // …and the run restarts from zero: 1 good then 5 bad still flags.
        assertTrue(DecodeAnomaly.hasSustainedTemperatureAnomaly(listOf(99.0, 99.0, 30.0, 99.0, 99.0, 99.0, 99.0, 99.0)))
    }

    @Test
    fun aRunMixingHighAndLowOutOfBandStillCounts() {
        // The run counts "out of band", not "out of band on one side".
        assertTrue(DecodeAnomaly.hasSustainedTemperatureAnomaly(listOf(99.0, 0.0, 99.0, 0.0, 99.0)))
    }

    @Test
    fun parametersAreHonoured() {
        val threeBad = listOf(30.0, 99.0, 99.0, 99.0, 30.0)
        assertTrue(DecodeAnomaly.hasSustainedTemperatureAnomaly(threeBad, sustainedRun = 3))
        assertFalse(DecodeAnomaly.hasSustainedTemperatureAnomaly(threeBad, sustainedRun = 4))
        // A narrower band turns normal skin readings into a sustained run.
        val normal = listOf(30.0, 31.0, 32.0, 33.0, 34.0)
        assertTrue(DecodeAnomaly.hasSustainedTemperatureAnomaly(normal, minC = 35.0))
        assertTrue(DecodeAnomaly.hasSustainedTemperatureAnomaly(normal, maxC = 29.0))
    }

    @Test
    fun rawValuesAreTheUpstreamStrings() {
        assertEquals(
            listOf("allZeroHRWhileWorn", "skinTempOutOfPhysicalRange"),
            DecodeAnomaly.entries.map { it.rawValue },
        )
    }
}
