package io.github.opencircuit.ringkit

import java.time.Duration
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The non-destructive nightly-summary merge: a shorter slice can never shrink a fuller night for the
 * same date; completeness is judged on time asleep first, in-bed span second.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepSummaryMergeTests.swift
 * (@ b1c2fdd) — all 13 tests. Upstream passes seconds as `TimeInterval`; here the same spans are
 * `Duration`s (`h(x)` = x hours, to the millisecond).
 */
class SleepSummaryMergeTest {

    private fun h(x: Double): Duration = Duration.ofMillis((x * 3_600_000).roundToLong())
    private fun s(x: Long): Duration = Duration.ofSeconds(x)

    /** The bug: a 4 h morning fragment must NOT overwrite a fuller (8 h) stored night. */
    @Test
    fun shorterSliceDoesNotReplaceFullerNight() {
        assertFalse(SleepSummaryMerge.shouldReplace(storedInBed = h(8.0), newInBed = h(4.0)))
    }

    /**
     * A fuller capture SHOULD supersede a smaller stored slice (e.g. the early-night partial gets
     * replaced once the whole night is finally drained).
     */
    @Test
    fun longerSliceReplacesSmallerNight() {
        assertTrue(SleepSummaryMerge.shouldReplace(storedInBed = h(4.0), newInBed = h(8.0)))
    }

    /** Equal spans replace — re-staging the SAME night (refined extras, identical window) must apply. */
    @Test
    fun equalSpanReplaces() {
        assertTrue(SleepSummaryMerge.shouldReplace(storedInBed = h(7.5), newInBed = h(7.5)))
    }

    /**
     * A legacy / first row with no valid window (span 0) is always replaced, so the first real capture
     * of a night always lands.
     */
    @Test
    fun zeroStoredAlwaysReplaces() {
        assertTrue(SleepSummaryMerge.shouldReplace(storedInBed = Duration.ZERO, newInBed = h(1.0)))
        assertTrue(SleepSummaryMerge.shouldReplace(storedInBed = Duration.ZERO, newInBed = Duration.ZERO))
    }

    /** A negative/degenerate stored span (defensive) is treated as "no window" → replace. */
    @Test
    fun negativeStoredReplaces() {
        assertTrue(SleepSummaryMerge.shouldReplace(storedInBed = s(-10), newInBed = h(2.0)))
    }

    /** Last night's exact shape: a 4 h 22 m fragment can't clobber the (already-saved) ~8 h night. */
    @Test
    fun regressionLastNightFragment() {
        val fragment = h(4.0).plusMinutes(22)
        val fullNight = h(8.0)
        assertFalse(SleepSummaryMerge.shouldReplace(storedInBed = fullNight, newInBed = fragment))
    }

    // MARK: - Completeness judged on time ASLEEP, not just in-bed span

    /**
     * The span-only proxy's blind spot: a WIDER in-bed window carrying LESS sleep (padded with
     * awake/gaps) must NOT replace a fuller-asleep night — that shrank the displayed total.
     */
    @Test
    fun widerSpanButLessAsleepDoesNotReplace() {
        assertFalse(SleepSummaryMerge.shouldReplace(storedInBed = h(6.0), newInBed = h(8.0), storedAsleep = h(6.0), newAsleep = h(3.0)))
    }

    /**
     * A classifier update may reduce time asleep while retaining the exact same raw-epoch coverage
     * (quiet wake is reclassified as awake-in-bed). That corrected result must replace the stale one.
     */
    @Test
    fun sameCoverageAllowsLowerAsleepReclassification() {
        assertTrue(
            SleepSummaryMerge.shouldReplace(
                storedInBed = h(9.25),
                newInBed = h(9.25),
                storedAsleep = h(9.2),
                newAsleep = h(7.6),
                sameCoverage = true,
            ),
        )
    }

    /**
     * A capture that recovers MORE sleep (a stitched night) supersedes a thinner stored one, even at
     * the same or smaller in-bed span.
     */
    @Test
    fun moreAsleepReplaces() {
        assertTrue(SleepSummaryMerge.shouldReplace(storedInBed = h(5.0), newInBed = h(5.0), storedAsleep = h(3.0), newAsleep = h(7.0)))
    }

    /**
     * On an EQUAL-asleep tie the WIDER in-bed span wins. An idempotent re-stage (equal span) still
     * replaces; a NARROWER-span slice does NOT — that's the lead-in-less drain that must not clobber a
     * bedtime-widened row.
     */
    @Test
    fun equalAsleepKeepsWiderInBed() {
        // Same night re-staged identically → replace (idempotent).
        assertTrue(SleepSummaryMerge.shouldReplace(storedInBed = h(5.0), newInBed = h(5.0), storedAsleep = h(5.0), newAsleep = h(5.0)))
        // Equal asleep but NARROWER new span → keep the fuller stored night.
        assertFalse(SleepSummaryMerge.shouldReplace(storedInBed = h(8.0), newInBed = h(5.0), storedAsleep = h(5.0), newAsleep = h(5.0)))
    }

    /**
     * Bedtime-widen durability: a morning drain widens in-bed back over the awake-in-bed lead-in
     * (in bed 8.5 h, asleep 7.5 h); a LATER same-night slice lands the same sleep core WITHOUT the
     * lead-in (in bed 7.5 h == asleep). Time asleep ties, so an asleep-only rule would clobber the
     * widened row back to 100 %. The tie-break must KEEP the widened (wider in-bed) row.
     */
    @Test
    fun leadInLessSliceDoesNotClobberWidenedRow() {
        assertFalse(
            SleepSummaryMerge.shouldReplace(storedInBed = h(8.5), newInBed = h(7.5), storedAsleep = h(7.5), newAsleep = h(7.5)),
            "a lead-in-less later slice must not collapse a bedtime-widened night to 100 % efficiency",
        )
    }

    /** With no asleep info on EITHER side (legacy rows), it falls back to the in-bed span comparison. */
    @Test
    fun fallsBackToSpanWhenAsleepUnknown() {
        assertFalse(SleepSummaryMerge.shouldReplace(storedInBed = h(8.0), newInBed = h(4.0), storedAsleep = Duration.ZERO, newAsleep = Duration.ZERO))
        assertTrue(SleepSummaryMerge.shouldReplace(storedInBed = h(4.0), newInBed = h(8.0), storedAsleep = Duration.ZERO, newAsleep = Duration.ZERO))
    }

    /** A first real capture (nothing usable stored) always lands, even when its asleep is unknown. */
    @Test
    fun firstCaptureLandsWhenNothingStored() {
        assertTrue(SleepSummaryMerge.shouldReplace(storedInBed = Duration.ZERO, newInBed = h(7.0), storedAsleep = Duration.ZERO, newAsleep = h(6.0)))
    }
}
