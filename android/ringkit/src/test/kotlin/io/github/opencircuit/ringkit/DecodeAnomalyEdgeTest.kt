package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Kotlin-only edges of `DecodeAnomaly` that upstream's `DecodeAnomalyTests.swift` does not test:
 * the band ends are inclusive-plausible, an empty input, a run straddling the band on both
 * sides, the `sustainedRun` / band parameters actually being honoured, the `detect` wear-count
 * boundary (exactly `minWornEpochs`, idle epochs not counted), and the stable raw values.
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

    // detect(records:) — the wear count is exactly `minWornEpochs`, and idle epochs are not worn.

    /** Upstream's `noHRRec`: a worn sleep-vitals record whose HR byte (4 bpm) decodes null. */
    private val noHR = assertNotNull(BulkRecord.of(hex("0c22d5bf044d057a620a01010101012aa0000090000004")))

    /** The idle/unworn template (upstream BulkSleepTests' idle record): also null HR, but not worn. */
    private val idle = assertNotNull(BulkRecord.of(hex("0c099dbf05000c00120a01010101010000000000000000")))

    @Test
    fun exactlyMinWornEpochsFlagsAndOneFewerDoesNot() {
        assertEquals(setOf(DecodeAnomaly.ALL_ZERO_HR_WHILE_WORN), DecodeAnomaly.detect(List(5) { noHR }))
        assertTrue(DecodeAnomaly.detect(List(4) { noHR }).isEmpty())
        assertEquals(setOf(DecodeAnomaly.ALL_ZERO_HR_WHILE_WORN), DecodeAnomaly.detect(List(2) { noHR }, minWornEpochs = 2))
    }

    @Test
    fun idleEpochsDoNotCountAsWorn() {
        assertEquals(BulkRecord.Layout.IDLE, idle.layout)
        // 14 records, every HR null — but only 4 are worn, so this is a sparse sync, not an anomaly.
        assertTrue(DecodeAnomaly.detect(List(4) { noHR } + List(10) { idle }).isEmpty())
        assertTrue(DecodeAnomaly.detect(List(10) { idle }).isEmpty())
        // Idle epochs mixed into a flagged drain don't mask it either.
        assertEquals(setOf(DecodeAnomaly.ALL_ZERO_HR_WHILE_WORN), DecodeAnomaly.detect(List(5) { noHR } + List(10) { idle }))
        assertTrue(DecodeAnomaly.detect(emptyList()).isEmpty())
    }

    @Test
    fun rawValuesAreTheUpstreamStrings() {
        assertEquals(
            listOf("allZeroHRWhileWorn", "skinTempOutOfPhysicalRange"),
            DecodeAnomaly.entries.map { it.rawValue },
        )
    }
}
