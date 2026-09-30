package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Kotlin-only checks of the `Metrics.kt` value types (upstream
 * ios/OpenCircuitKit/Sources/OpenCircuitKit/Metrics.swift:9-173 @ b1c2fdd). Upstream tests these
 * only through `RingKitVerify` :155-158 (ported in `RingKitVerifyTest`) and through
 * `SleepProvenanceTests.swift`, which is deferred to E3 whole (it tests sleep code). These pin the stable
 * raw values (persistence / cursor keys), the verbatim unit and label tables, and the provenance
 * predicates that E3's derived statistics will build on.
 */
class MetricsTest {

    @Test
    fun metricKindRawValuesUnitsAndLabelsAreVerbatim() { // Metrics.swift:9-59
        val expected = listOf(
            Triple("heartRate", "count/min", "Heart Rate"),
            Triple("restingHeartRate", "count/min", "Resting HR"),
            Triple("hrvSDNN", "ms", "HRV"),
            Triple("spo2", "fraction", "SpO₂"),
            Triple("temperature", "degC", "Skin Temp"),
            Triple("respiratoryRate", "count/min", "Respiratory Rate"),
            Triple("steps", "count", "Steps"),
            Triple("activeEnergy", "kcal", "Active Energy"),
            Triple("sleep", "category", "Sleep"),
            Triple("distance", "m", "Distance (est.)"),
            Triple("exerciseMinutes", "min", "Exercise Time (est.)"),
        )
        assertEquals(expected, MetricKind.entries.map { Triple(it.rawValue, it.unit, it.displayName) })
    }

    @Test
    fun quantitySampleKeepsAnExplicitEndAndComparesByValue() { // :64-76
        val start = Instant.ofEpochSecond(100)
        val end = Instant.ofEpochSecond(160)
        val interval = QuantitySample(kind = MetricKind.STEPS, start = start, end = end, value = 42.0)
        assertEquals(end, interval.end, "an explicit end is kept, not replaced by start")
        assertEquals(QuantitySample(MetricKind.STEPS, start, end, 42.0), interval, "value equality (Swift Equatable)")
        assertNotEquals(QuantitySample(MetricKind.STEPS, start, end, 43.0), interval)
    }

    @Test
    fun sleepStageHasExactlyTheFiveHealthCategories() { // :87-89 — see the warning on SleepStage
        assertEquals(
            listOf("inBed", "awake", "asleepCore", "asleepDeep", "asleepREM"),
            SleepStage.entries.map { it.rawValue },
        )
    }

    @Test
    fun sleepProvenanceRawValuesAndPredicates() { // :116-148
        assertEquals(
            listOf("measured", "asserted", "assertedOverMeasured", "assertedCoverageUnknown"),
            SleepProvenance.entries.map { it.rawValue },
        )
        // Each row: isProvenUnmeasured, hasMeasurement, isCoverageUnknown, isAsserted.
        val table = mapOf(
            SleepProvenance.MEASURED to listOf(false, true, false, false),
            SleepProvenance.ASSERTED to listOf(true, false, false, true),
            SleepProvenance.ASSERTED_OVER_MEASURED to listOf(false, true, false, true),
            SleepProvenance.ASSERTED_COVERAGE_UNKNOWN to listOf(false, false, true, true),
        )
        for ((p, want) in table) {
            assertEquals(want, listOf(p.isProvenUnmeasured, p.hasMeasurement, p.isCoverageUnknown, p.isAsserted), "$p")
        }
    }

    @Test
    fun sleepSegmentDefaultsToMeasuredAndReportsItsDuration() { // :152-168
        val seg = SleepSegment(
            start = Instant.ofEpochSecond(1_000),
            end = Instant.ofEpochSecond(1_000 + 90 * 60),
            stage = SleepStage.ASLEEP_DEEP,
        )
        assertEquals(SleepProvenance.MEASURED, seg.provenance)
        assertEquals(Duration.ofMinutes(90), seg.duration)
        // A zero-length segment is legal and has zero duration.
        assertEquals(Duration.ZERO, seg.copy(end = seg.start).duration)
    }

    @Test
    fun withProvenanceRetagsWithoutTouchingSpanOrStage() { // :171-173
        val seg = SleepSegment(Instant.ofEpochSecond(10), Instant.ofEpochSecond(70), SleepStage.AWAKE)
        val retagged = seg.withProvenance(SleepProvenance.ASSERTED_COVERAGE_UNKNOWN)
        assertEquals(SleepSegment(seg.start, seg.end, seg.stage, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN), retagged)
        assertEquals(SleepProvenance.MEASURED, seg.provenance, "the original is unchanged")
        assertTrue(retagged.provenance.isCoverageUnknown)
        assertFalse(retagged.provenance.hasMeasurement)
    }
}
