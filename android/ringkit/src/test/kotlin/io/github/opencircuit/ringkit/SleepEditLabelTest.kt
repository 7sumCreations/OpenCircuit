package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Supervised labels harvested from the user's own sleep edits: signed onset/wake errors, the
 * picker-friction filter, signed means with robust absolute medians, "no evidence" as null, and the
 * conservative fit gate.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepEditLabelTests.swift
 * (@ b1c2fdd) — all 11 tests. Upstream compares with `XCTAssertEqual(x ?? 0, …, accuracy:)`; the
 * same tolerances are kept.
 */
class SleepEditLabelTest {

    private val night: Instant = Instant.ofEpochSecond(1_780_000_000)
    private fun t(minutes: Double): Instant = night.plusNanos((minutes * 60e9).roundToLong())

    private fun label(onsetErr: Double?, wakeErr: Double?) = SleepEditLabel(
        night = night,
        recordedOnset = onsetErr?.let { t(100 + it) },
        recordedWake = wakeErr?.let { t(600 + it) },
        trueOnset = if (onsetErr == null) null else t(100.0),
        trueWake = if (wakeErr == null) null else t(600.0),
    )

    // Error arithmetic

    @Test
    fun wakeErrorIsPositiveWhenTheDetectorHeldTheNightOpenTooLong() {
        val l = label(onsetErr = null, wakeErr = 50.0)
        assertEquals(50.0, l.wakeErrorMinutes ?: 0.0, 0.001)
        assertNull(l.onsetErrorMinutes)
        assertTrue(l.isUsable)
    }

    @Test
    fun onsetErrorIsNegativeWhenTheDetectorCalledSleepTooEarly() {
        assertEquals(-22.0, label(onsetErr = -22.0, wakeErr = null).onsetErrorMinutes ?: 0.0, 0.001)
    }

    @Test
    fun labelWithNeitherEdgeIsNotUsable() {
        assertFalse(SleepEditLabel(night = night).isUsable)
    }

    // Filtering

    @Test
    fun pickerFrictionIsNotALabel() {
        val noise = listOf(label(1.0, 2.0), label(-2.0, 1.0))
        assertTrue(SleepEditLabels.usable(noise).isEmpty(), "a one- or two-minute nudge is not an assertion that the detector was wrong")
    }

    @Test
    fun aRealCorrectionOnEitherEdgeQualifies() {
        assertEquals(1, SleepEditLabels.usable(listOf(label(0.0, 50.0))).size)
        assertEquals(1, SleepEditLabels.usable(listOf(label(40.0, 0.0))).size)
    }

    // Accuracy

    /** Mean must be SIGNED so a systematically late detector is visible. */
    @Test
    fun meanErrorIsSignedSoSystematicBiasSurvives() {
        val allLate = listOf(label(null, 40.0), label(null, 50.0), label(null, 60.0))
        val scattered = listOf(label(null, -50.0), label(null, 50.0), label(null, 50.0))
        assertEquals(50.0, SleepEditLabels.accuracy(allLate).meanWakeError ?: 0.0, 0.001)
        assertEquals(16.667, SleepEditLabels.accuracy(scattered).meanWakeError ?: 0.0, 0.01)
        // …while the typical magnitude is the same for both.
        assertEquals(50.0, SleepEditLabels.accuracy(allLate).medianAbsWakeError ?: 0.0, 0.001)
        assertEquals(50.0, SleepEditLabels.accuracy(scattered).medianAbsWakeError ?: 0.0, 0.001)
    }

    /** "No evidence" must not read as "no error". */
    @Test
    fun absentEdgeIsNilNotZero() {
        val a = SleepEditLabels.accuracy(listOf(label(null, 40.0)))
        assertNull(a.meanOnsetError)
        assertNull(a.medianAbsOnsetError)
        assertNotNull(a.meanWakeError)
    }

    @Test
    fun accuracyOfNothingIsAllNil() {
        val a = SleepEditLabels.accuracy(emptyList())
        assertEquals(0, a.count)
        assertNull(a.meanWakeError)
        assertNull(a.medianAbsWakeError)
    }

    @Test
    fun medianAbsoluteIsRobustToOneWildNight() {
        val labels = List(8) { label(null, 30.0) } + label(null, 900.0)
        assertEquals(30.0, SleepEditLabels.accuracy(labels).medianAbsWakeError ?: 0.0, 0.001)
    }

    // The fit gate

    @Test
    fun oneNightIsNeverEnoughToFit() {
        assertFalse(SleepEditLabels.isFittable(listOf(label(null, 50.0))), "a knob may not be promoted off a single night")
    }

    @Test
    fun fitGateCountsOnlyRealCorrections() {
        val friction = List(20) { label(1.0, 1.0) }
        assertFalse(SleepEditLabels.isFittable(friction), "twenty one-minute nudges are not twenty labels")
        val real = List(SleepEditLabels.MINIMUM_NIGHTS_TO_FIT) { label(null, 40.0) }
        assertTrue(SleepEditLabels.isFittable(real))
    }
}
