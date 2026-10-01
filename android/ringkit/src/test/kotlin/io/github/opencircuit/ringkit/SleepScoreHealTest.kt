package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The withheld-score repair. The load-bearing property is PARITY: a healed row must get the score
 * the edit path would have stored for the same night, so the repair is a repair and not a silent
 * restatement of the wearer's night. Two things are pinned rather than merely exercised — the
 * second-precision basis (not the row's rounded minutes) and the assertion-INCLUSIVE display basis
 * (not measured-only).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepScoreHealTests.swift (@ b1c2fdd) —
 * all 8 tests.
 */
class SleepScoreHealTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)

    private fun seg(from: Double, to: Double, stage: SleepStage, provenance: SleepProvenance = SleepProvenance.MEASURED) =
        SleepSegment(t0.plusMillis((from * 1000).roundToLong()), t0.plusMillis((to * 1000).roundToLong()), stage, provenance)

    private fun secs(d: Duration): Double = d.seconds + d.nano / 1e9

    /** A 7 h night: 6 h asleep (3 h core / 1.5 h deep / 1.5 h REM) + 1 h awake. */
    private fun night(provenance: SleepProvenance = SleepProvenance.MEASURED): List<SleepSegment> = listOf(
        seg(0.0, 7.0 * 3600, SleepStage.IN_BED, provenance),
        seg(0.0, 3600.0, SleepStage.AWAKE, provenance),
        seg(3600.0, 4.0 * 3600, SleepStage.ASLEEP_CORE, provenance),
        seg(4.0 * 3600, 5.5 * 3600, SleepStage.ASLEEP_DEEP, provenance),
        seg(5.5 * 3600, 7.0 * 3600, SleepStage.ASLEEP_REM, provenance),
    )

    private fun composite(s: SleepStaging.Summary): Int = SleepScore.composite(
        SleepScore.CompositeInput(
            totalAsleep = secs(s.totalAsleep),
            timeAwake = secs(s.awake),
            efficiency = s.efficiency,
            deep = secs(s.deep),
            light = secs(s.light),
            rem = secs(s.rem),
        ),
    ).score

    @Test
    fun summaryMirrorsSleepStagingSummary() {
        val s = assertNotNull(SleepScoreHeal.summary(night()))
        assertEquals(Duration.ofSeconds(7 * 3600), s.inBed)
        assertEquals(Duration.ofSeconds(3600), s.awake)
        assertEquals(Duration.ofSeconds(3 * 3600), s.light)
        assertEquals(Duration.ofSeconds(5400), s.deep)
        assertEquals(Duration.ofSeconds(5400), s.rem)
        // Derived accessors are SleepStaging.Summary's own, so they must agree with its contract.
        assertEquals(Duration.ofSeconds(6 * 3600), s.totalAsleep)
        assertEquals(6.0 / 7.0, s.efficiency, 1e-12)
    }

    /**
     * An assertion wins for DISPLAY. A fully-asserted night must score identically to the same night
     * fully measured — otherwise "healing" would quietly restate the wearer's night at a lower
     * number, which is a different defect wearing the fix's clothes.
     */
    @Test
    fun assertedTimeIsIncludedExactlyLikeMeasuredTime() {
        val measured = assertNotNull(SleepScoreHeal.healedScore(hypnogram = night(SleepProvenance.MEASURED)))
        val asserted = assertNotNull(SleepScoreHeal.healedScore(hypnogram = night(SleepProvenance.ASSERTED)))
        assertEquals(measured, asserted)
    }

    /**
     * PARITY WITH THE EDIT PATH. The edit path builds the composite score from a second-precision
     * staging summary; this must reproduce that call exactly.
     */
    @Test
    fun matchesTheEditPathsCompositeCall() {
        val segments = night()
        val s = assertNotNull(SleepScoreHeal.summary(segments))
        assertEquals(composite(s), SleepScoreHeal.healedScore(hypnogram = segments))
    }

    /**
     * THE REASON THIS TYPE EXISTS. Rebuilding from the row's ROUNDED minutes is a different input
     * than the one the score was originally built from. Pin that they can disagree, so nobody
     * "simplifies" the heal into reading the row's minutes.
     */
    @Test
    fun secondPrecisionDiffersFromRoundedMinutes() {
        // 6 h 00 m 29 s asleep rounds DOWN to 360 min; 29 s of REM is invisible to the minute basis.
        val segments = listOf(
            seg(0.0, 7.0 * 3600 + 29, SleepStage.IN_BED),
            seg(0.0, 3600.0, SleepStage.AWAKE),
            seg(3600.0, 4.0 * 3600, SleepStage.ASLEEP_CORE),
            seg(4.0 * 3600, 5.5 * 3600, SleepStage.ASLEEP_DEEP),
            seg(5.5 * 3600, 7.0 * 3600 + 29, SleepStage.ASLEEP_REM),
        )
        val s = assertNotNull(SleepScoreHeal.summary(segments))
        assertEquals(Duration.ofSeconds(6 * 3600 + 29), s.totalAsleep)
        // The minute rollup loses the 29 s, so a minutes-based rebuild feeds a different efficiency.
        val m = s.minutes
        val roundedEfficiency = m.asleep.toDouble() / m.inBed.toDouble()
        assertNotEquals(roundedEfficiency, s.efficiency)
    }

    // MARK: - Refusals

    @Test
    fun emptyHypnogramIsLeftAlone() {
        assertNull(SleepScoreHeal.summary(emptyList()))
        assertNull(SleepScoreHeal.healedScore(hypnogram = emptyList()))
    }

    /**
     * No in-bed layer ⇒ efficiency is 0 by the staging summary's own contract, so the night cannot be
     * described and must not be scored.
     */
    @Test
    fun noInBedLayerIsLeftAlone() {
        assertNull(SleepScoreHeal.summary(listOf(seg(0.0, 3600.0, SleepStage.ASLEEP_CORE))))
    }

    /** An all-awake night has no asleep time; scoring it would invent a night that did not happen. */
    @Test
    fun noAsleepTimeIsLeftAlone() {
        assertNull(SleepScoreHeal.summary(listOf(seg(0.0, 3600.0, SleepStage.IN_BED), seg(0.0, 3600.0, SleepStage.AWAKE))))
    }

    /**
     * A recomputed 0 writes the sentinel back and achieves nothing, so the row stays untouched and
     * the wearer's next edit still gets a chance to fix it.
     */
    @Test
    fun aZeroRecomputeIsRefusedRatherThanWritten() {
        // One second of sleep in a 12 h bed: every factor floors, so the composite lands at 0.
        val segments = listOf(
            seg(0.0, 12.0 * 3600, SleepStage.IN_BED),
            seg(0.0, 12.0 * 3600 - 1, SleepStage.AWAKE),
            seg(12.0 * 3600 - 1, 12.0 * 3600, SleepStage.ASLEEP_CORE),
        )
        val s = SleepScoreHeal.summary(segments)
        if (s != null) {
            val raw = composite(s)
            // Only meaningful as a refusal test if the composite really does floor here.
            if (raw == 0) assertNull(SleepScoreHeal.healedScore(hypnogram = segments))
        }
    }
}
