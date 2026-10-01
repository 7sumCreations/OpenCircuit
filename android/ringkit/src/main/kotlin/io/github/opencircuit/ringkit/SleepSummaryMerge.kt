package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepSummaryMerge.swift (@ b1c2fdd): the
// non-destructive nightly-summary merge policy.
//
// The ring's onboard history buffer holds only ~114 epochs (~4.75 h) and DROPS THE OLDEST when full, so
// a later sync often drains only a PARTIAL slice of a night. The store upserts by night key, and blindly
// overwriting let a 4 h morning fragment clobber a fuller capture of the same night. This policy decides
// whether a freshly staged night should REPLACE the stored one: a shorter slice can never shrink a
// fuller night.
//
// SCOPE / LIMITS: it keeps the single MOST-COMPLETE drain; it does NOT stitch two DISJOINT partial
// slices into one night. Two equal DISJOINT slices replace each other by arrival order (the `>=`).
//
// Spans are `Duration`s here (upstream: seconds as `Double`), so NaN and infinite spans cannot reach it.

import java.time.Duration

object SleepSummaryMerge {

    /**
     * Whether a newly staged night should REPLACE the stored summary for the same night key.
     *
     * [storedInBed] is the stored row's in-bed span — zero for a legacy row with no valid clock window;
     * [newInBed] the freshly staged span; [storedAsleep] / [newAsleep] time actually asleep (zero when
     * unknown); [sameCoverage] is true when both summaries came from the same start/end coverage — an
     * intentional reclassification, so refined wake/onset logic may legitimately reduce the asleep total
     * without being mistaken for a thinner capture fragment.
     *
     * True to overwrite. Completeness is judged PRIMARILY by time ASLEEP — a capture that recovers more
     * sleep supersedes a thinner one, and a shorter fragment never shrinks a fuller night; in-bed span is
     * the fallback when neither asleep value is known. A stored row with no usable data (both
     * non-positive) is always replaced, so the first real capture of a night always lands. On an EQUAL
     * asleep tie the WIDER in-bed span wins, so a later slice that drained the same sleep core without
     * the awake-in-bed lead-in cannot clobber a bedtime-widened row back to 100 % efficiency.
     */
    fun shouldReplace(
        storedInBed: Duration,
        newInBed: Duration,
        storedAsleep: Duration = Duration.ZERO,
        newAsleep: Duration = Duration.ZERO,
        sameCoverage: Boolean = false,
    ): Boolean {
        if (!(storedInBed > Duration.ZERO || storedAsleep > Duration.ZERO)) return true
        if (sameCoverage) return true
        if (storedAsleep > Duration.ZERO || newAsleep > Duration.ZERO) {
            // More recovered sleep always wins; on a tie, keep the fuller (wider in-bed) night.
            if (newAsleep != storedAsleep) return newAsleep > storedAsleep
            return newInBed >= storedInBed
        }
        return newInBed >= storedInBed
    }
}
