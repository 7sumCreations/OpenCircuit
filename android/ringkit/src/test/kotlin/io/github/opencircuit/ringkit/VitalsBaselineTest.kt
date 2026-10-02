package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.VitalsBaseline.Direction
import io.github.opencircuit.ringkit.VitalsBaseline.Severity
import io.github.opencircuit.ringkit.VitalsBaseline.Status
import io.github.opencircuit.ringkit.VitalsBaseline.Vital
import io.github.opencircuit.ringkit.VitalsBaseline.VitalInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNTHETIC-ONLY tests for vitals baseline + acute anomaly detection + fever (#72). Controlled inputs
 * with a known baseline/severity — never real health values.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/VitalsBaselineTests.swift
 * (@ b1c2fdd), all 15 tests.
 */
class VitalsBaselineTest {

    // Baseline window

    @Test
    fun baselineNeedsMinimumHistory() { // :10-17
        // 6 days < default minBaselineDays (7) → no baseline.
        assertNull(VitalsBaseline.stats(List(6) { 60.0 }))
        val s = assertNotNull(VitalsBaseline.stats(List(7) { 60.0 }))
        assertEquals(60.0, s.mean, 1e-9)
        assertEquals(0.0, s.sd, 1e-9)
        assertEquals(7, s.n)
    }

    @Test
    fun baselineTrailingWindowCaps() { // :19-25
        // 40 prior values but a 30-day max → only the most-recent 30 count.
        val prior = (1..40).map { it.toDouble() } // 1…40 oldest→newest
        val s = assertNotNull(VitalsBaseline.stats(prior)) // last 30 = 11…40, mean 25.5
        assertEquals(30, s.n)
        assertEquals(25.5, s.mean, 1e-9)
    }

    // Severity thresholds

    @Test
    fun restingHRSignificantRise() { // :29-35
        // baseline mean 60, sd 2 → +10 bpm is 5 sd and past the 5-bpm floor → significant.
        val prior = listOf(58.0, 60.0, 62.0, 58.0, 60.0, 62.0, 60.0) // mean 60, sd≈1.6
        val c = VitalsBaseline.classify(today = 75.0, prior = prior, vital = Vital.RESTING_HR)
        assertEquals(Severity.SIGNIFICANT, c.severity)
        assertEquals(Direction.RISE, c.direction)
    }

    @Test
    fun restingHRMinorRise() { // :37-42
        // Wider baseline so a moderate rise lands in the minor (1.5–2.5 sd) band.
        val prior = listOf(50.0, 55.0, 60.0, 65.0, 70.0, 55.0, 60.0) // mean 59.3, sd≈6.3
        val c = VitalsBaseline.classify(today = 71.0, prior = prior, vital = Vital.RESTING_HR)
        assertEquals(Severity.MINOR, c.severity)
    }

    @Test
    fun restingHRLowIsNotConcerning() { // :44-50
        // A LOWER resting HR is healthy — never flagged (concern is .high only).
        val prior = listOf(58.0, 60.0, 62.0, 58.0, 60.0, 62.0, 60.0)
        val c = VitalsBaseline.classify(today = 45.0, prior = prior, vital = Vital.RESTING_HR)
        assertEquals(Severity.NORMAL, c.severity)
        assertEquals(Direction.DROP, c.direction)
    }

    @Test
    fun absoluteFloorSuppressesTinyDeviation() { // :52-60
        // Very tight baseline (sd≈0): a 2-bpm change is huge in z but below the 5-bpm floor → normal.
        val prior = List(7) { 60.0 }
        val c = VitalsBaseline.classify(today = 62.0, prior = prior, vital = Vital.RESTING_HR)
        assertEquals(Severity.NORMAL, c.severity)
        // But a change past the floor with zero sd is treated as significant.
        val big = VitalsBaseline.classify(today = 70.0, prior = prior, vital = Vital.RESTING_HR)
        assertEquals(Severity.SIGNIFICANT, big.severity)
    }

    @Test
    fun spO2DropConcerning() { // :62-69
        val prior = listOf(98.0, 97.0, 98.0, 99.0, 97.0, 98.0, 98.0) // mean ≈97.9, sd≈0.6
        val c = VitalsBaseline.classify(today = 92.0, prior = prior, vital = Vital.OVERNIGHT_SPO2)
        assertEquals(Severity.SIGNIFICANT, c.severity)
        assertEquals(Direction.DROP, c.direction)
        // A higher SpO2 is never an anomaly.
        assertEquals(Severity.NORMAL, VitalsBaseline.classify(today = 100.0, prior = prior, vital = Vital.OVERNIGHT_SPO2).severity)
    }

    @Test
    fun hrvDropConcerning() { // :71-77
        val prior = listOf(60.0, 65.0, 55.0, 60.0, 62.0, 58.0, 60.0) // mean 60, sd≈3
        val c = VitalsBaseline.classify(today = 40.0, prior = prior, vital = Vital.OVERNIGHT_HRV)
        assertEquals(Severity.SIGNIFICANT, c.severity)
        assertEquals(Direction.DROP, c.direction)
        assertEquals(Severity.NORMAL, VitalsBaseline.classify(today = 90.0, prior = prior, vital = Vital.OVERNIGHT_HRV).severity)
    }

    @Test
    fun tempSeverityBands() { // :79-84
        assertEquals(Severity.NORMAL, VitalsBaseline.tempSeverity(offsetC = 0.3))
        assertEquals(Severity.MINOR, VitalsBaseline.tempSeverity(offsetC = 0.7))
        assertEquals(Severity.SIGNIFICANT, VitalsBaseline.tempSeverity(offsetC = 1.4))
        assertEquals(Severity.SIGNIFICANT, VitalsBaseline.tempSeverity(offsetC = -1.4), "a sharp drop is also significant")
    }

    // Fever (HR + temp cross-reference)

    @Test
    fun feverNeedsBothSignals() { // :88-96
        val hrPrior = List(7) { 60.0 }
        // Temp up but HR normal → no fever.
        assertFalse(VitalsBaseline.suspectedFever(restingHRToday = 60.0, restingHRPrior = hrPrior, skinTempOffsetC = 1.2))
        // HR up but temp normal → no fever.
        assertFalse(VitalsBaseline.suspectedFever(restingHRToday = 75.0, restingHRPrior = hrPrior, skinTempOffsetC = 0.3))
        // BOTH up → fever.
        assertTrue(VitalsBaseline.suspectedFever(restingHRToday = 75.0, restingHRPrior = hrPrior, skinTempOffsetC = 1.2))
    }

    @Test
    fun feverRequiresInputs() { // :98-105
        assertFalse(VitalsBaseline.suspectedFever(restingHRToday = null, restingHRPrior = emptyList(), skinTempOffsetC = 1.2))
        assertFalse(VitalsBaseline.suspectedFever(restingHRToday = 80.0, restingHRPrior = emptyList(), skinTempOffsetC = null))
        // Too little HR history → can't establish a personal baseline → no false fever.
        assertFalse(VitalsBaseline.suspectedFever(restingHRToday = 90.0, restingHRPrior = listOf(60.0, 60.0, 60.0), skinTempOffsetC = 1.5))
    }

    // Vitals Status report

    @Test
    fun statusNormal() { // :109-118
        val inputs = listOf(
            VitalInput(vital = Vital.RESTING_HR, today = 60.0, prior = List(7) { 60.0 }),
            VitalInput(vital = Vital.OVERNIGHT_SPO2, today = 98.0, prior = List(7) { 98.0 }),
        )
        val r = VitalsBaseline.report(inputs, skinTempOffsetC = 0.2)
        assertEquals(Status.NORMAL, r.status)
        assertTrue(r.signals.isEmpty())
        assertFalse(r.feverSuspected)
    }

    @Test
    fun statusWatchOnMinor() { // :120-127
        val prior = listOf(50.0, 55.0, 60.0, 65.0, 70.0, 55.0, 60.0)
        val inputs = listOf(VitalInput(vital = Vital.RESTING_HR, today = 71.0, prior = prior))
        val r = VitalsBaseline.report(inputs)
        assertEquals(Status.WATCH, r.status)
        assertEquals(1, r.signals.size)
        assertEquals(Severity.MINOR, r.signals.first().severity)
    }

    @Test
    fun statusAnomalyOnFever() { // :129-136
        // HR + temp both elevated → fever → anomaly, even though HR alone might only be minor.
        val inputs = listOf(VitalInput(vital = Vital.RESTING_HR, today = 75.0, prior = List(7) { 60.0 }))
        val r = VitalsBaseline.report(inputs, skinTempOffsetC = 1.3)
        assertEquals(Status.ANOMALY, r.status)
        assertTrue(r.feverSuspected)
    }

    @Test
    fun statusAnomalyOnSignificant() { // :138-143
        val inputs = listOf(VitalInput(vital = Vital.OVERNIGHT_SPO2, today = 91.0, prior = listOf(98.0, 97.0, 98.0, 99.0, 97.0, 98.0, 98.0)))
        val r = VitalsBaseline.report(inputs)
        assertEquals(Status.ANOMALY, r.status)
    }
}
