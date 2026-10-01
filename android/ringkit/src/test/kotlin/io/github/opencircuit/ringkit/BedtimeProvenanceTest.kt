package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The night this exists for: the ring charged 22:19:38–22:35:12 on 2026-08-08, records resume
 * 22:36:18, and the app printed 22:36:18 as "bedtime" with no qualification. The classifier must call
 * that edge what it is — a resumption — without inventing a cause.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/BedtimeProvenanceTests.swift
 * (@ b1c2fdd) — all 10 tests.
 */
class BedtimeProvenanceTest {

    /** 2026-08-08 22:36:18 local, the real resumption instant. */
    private val resumed: Instant = Instant.ofEpochSecond(1_786_602_978)
    private fun t(offsetFromResumed: Long): Instant = resumed.plusSeconds(offsetFromResumed)
    private val tolerance: Long = BedtimeProvenance.CONTINUOUS_TOLERANCE_SECONDS.toLong()

    // MARK: the night

    @Test
    fun theChargeCycleNightIsNotWitnessed() {
        // Last wrist HR before the charge cycle: 22:19:38 is 16m40s (1000 s) before the resume.
        val verdict = BedtimeProvenance.classify(inBedStart = resumed, lastMeasurementBefore = t(-1000), earliestRetainedMeasurement = t(-14L * 86_400))
        assertEquals(BedtimeProvenance.Verdict.ResumedAfterGap(1000.0), verdict)
        assertTrue(BedtimeProvenance.needsQualification(verdict))
    }

    // MARK: continuity

    @Test
    fun unbrokenStreamIsWitnessed() {
        // One epoch (150 s) before the edge — an intact stream.
        val verdict = BedtimeProvenance.classify(inBedStart = resumed, lastMeasurementBefore = t(-150), earliestRetainedMeasurement = t(-14L * 86_400))
        assertEquals(BedtimeProvenance.Verdict.Witnessed, verdict)
        assertFalse(BedtimeProvenance.needsQualification(verdict))
    }

    @Test
    fun oneDroppedEpochStillCountsAsContinuous() {
        // Two cadences: a single unparsed/dropped epoch must not be reported to the user as a gap.
        val verdict = BedtimeProvenance.classify(inBedStart = resumed, lastMeasurementBefore = t(-tolerance), earliestRetainedMeasurement = t(-14L * 86_400))
        assertEquals(BedtimeProvenance.Verdict.Witnessed, verdict)
    }

    @Test
    fun justBeyondToleranceIsAGap() {
        val verdict = BedtimeProvenance.classify(inBedStart = resumed, lastMeasurementBefore = t(-tolerance - 1), earliestRetainedMeasurement = t(-14L * 86_400))
        assertEquals(BedtimeProvenance.Verdict.ResumedAfterGap(BedtimeProvenance.CONTINUOUS_TOLERANCE_SECONDS + 1), verdict)
    }

    @Test
    fun toleranceIsBoundedByTheEpochCadence() {
        // The documented rationale is "two 150 s cadences". A future edit that inflates this would
        // start calling real multi-epoch gaps "witnessed".
        assertEquals(300.0, BedtimeProvenance.CONTINUOUS_TOLERANCE_SECONDS)
    }

    // MARK: absence vs non-retention — the distinction that must not collapse

    @Test
    fun noPriorMeasurementWithDeepRetentionIsEvidence() {
        val verdict = BedtimeProvenance.classify(inBedStart = resumed, lastMeasurementBefore = null, earliestRetainedMeasurement = t(-7L * 86_400))
        assertEquals(BedtimeProvenance.Verdict.NoPriorMeasurement, verdict)
        assertTrue(BedtimeProvenance.needsQualification(verdict))
    }

    @Test
    fun noPriorMeasurementAtTheRetentionEdgeIsUnknown() {
        // The oldest row we hold sits INSIDE the evidence window, so the absence of anything earlier
        // says nothing about the wearer — it says our retention stops there.
        val verdict = BedtimeProvenance.classify(
            inBedStart = resumed,
            lastMeasurementBefore = null,
            earliestRetainedMeasurement = t(-BedtimeProvenance.PRIOR_EVIDENCE_WINDOW_SECONDS.toLong() + 1),
        )
        assertEquals(BedtimeProvenance.Verdict.Unknown, verdict)
    }

    @Test
    fun emptyStoreIsUnknownNotWitnessed() {
        val verdict = BedtimeProvenance.classify(inBedStart = resumed, lastMeasurementBefore = null, earliestRetainedMeasurement = null)
        assertEquals(BedtimeProvenance.Verdict.Unknown, verdict)
        assertTrue(BedtimeProvenance.needsQualification(verdict), "'we did not look' must never render as an unqualified measured bedtime")
    }

    // MARK: caller hazards

    @Test
    fun measurementAtOrAfterTheEdgeIsUnknownNotANegativeGap() {
        for (offset in listOf(0L, 60L)) {
            val verdict = BedtimeProvenance.classify(inBedStart = resumed, lastMeasurementBefore = t(offset), earliestRetainedMeasurement = t(-14L * 86_400))
            assertEquals(BedtimeProvenance.Verdict.Unknown, verdict, "offset $offset must not produce a negative gap")
        }
    }

    // MARK: the qualification rule

    @Test
    fun onlyWitnessedEarnsAnUnqualifiedBedtime() {
        assertFalse(BedtimeProvenance.needsQualification(BedtimeProvenance.Verdict.Witnessed))
        assertTrue(BedtimeProvenance.needsQualification(BedtimeProvenance.Verdict.ResumedAfterGap(600.0)))
        assertTrue(BedtimeProvenance.needsQualification(BedtimeProvenance.Verdict.NoPriorMeasurement))
        assertTrue(BedtimeProvenance.needsQualification(BedtimeProvenance.Verdict.Unknown))
    }
}
