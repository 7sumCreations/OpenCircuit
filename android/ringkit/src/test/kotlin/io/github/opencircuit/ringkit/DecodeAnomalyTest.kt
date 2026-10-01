package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pattern-level decode anomalies: a drain whose worn epochs ALL decode HR as null, and a
 * SUSTAINED run of implausible skin-temperature readings. A single bad sample is never a pattern.
 * See `DecodeAnomaly.detect` and `DecodeAnomaly.hasSustainedTemperatureAnomaly`.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/DecodeAnomalyTests.swift
 * (@ b1c2fdd) — all 9 tests: the 4 `detect(records:)` tests (:23-45) and the 5 temperature tests
 * (:49-73). Kotlin-only edges live in `DecodeAnomalyEdgeTest`, so this class keeps upstream's
 * exact count.
 */
class DecodeAnomalyTest {

    // :16 — real sleep-vitals record (the BulkSleep deep-sleep record): HR 68, decodes fine.
    private val goodRec = "0c22d5bf444d057a620a01010101012aa0000090000004"

    // :21 — a worn (non-idle) record whose HR byte is below the live HR floor (30), so its heart
    // rate decodes null. [4]=0x04 (4 bpm, invalid), [8]=0x62 (a sleep-vitals SpO2, so the layout
    // isn't idle), [9]=0x0a, motion 01×5 — only [4] differs from goodRec.
    private val noHRRec = "0c22d5bf044d057a620a01010101012aa0000090000004"

    private fun record(h: String): BulkRecord = assertNotNull(BulkRecord.of(hex(h)))

    // detect(records:)

    @Test
    fun noAnomalyOnNormalRecords() { // :23-26
        val records = List(10) { record(goodRec) }
        assertTrue(DecodeAnomaly.detect(records).isEmpty())
    }

    @Test
    fun flagsAllZeroHRWhileWorn() { // :28-31
        val records = List(10) { record(noHRRec) }
        assertEquals(setOf(DecodeAnomaly.ALL_ZERO_HR_WHILE_WORN), DecodeAnomaly.detect(records))
    }

    @Test
    fun doesNotFlagSparseSyncBelowMinWornEpochs() { // :33-38
        // Same "all null HR" records, but fewer than minWornEpochs — a near-empty/contended sync,
        // not a decode-format anomaly.
        val records = List(3) { record(noHRRec) }
        assertTrue(DecodeAnomaly.detect(records, minWornEpochs = 5).isEmpty())
    }

    @Test
    fun mixedRecordsDoNotFlag() { // :40-45
        // Most epochs decode HR fine; one bad epoch is not a pattern.
        val records = List(9) { record(goodRec) } + record(noHRRec)
        assertTrue(DecodeAnomaly.detect(records).isEmpty())
    }

    // Temperature

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
