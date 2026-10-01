package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepScoreHeal.swift (@ b1c2fdd): the repair
// for upstream's "correcting a night deleted my score" scar.
//
// THE DEFECT. Two upstream builds zeroed the stored sleep score whenever a night's provenance breakdown
// was not scorable. `0` is that column's app-wide "never computed" sentinel, so the wearer read it as
// data loss — and the rule could only ever fire on a wearer's own CORRECTION. The next build removed the
// rule but did not heal rows already written; this is the one-shot repair.
//
// IT RECOMPUTES FROM THE STORED HYPNOGRAM, NEVER FROM THE ROW'S ROUNDED MINUTES: the edit path built the
// score from a second-precision staging summary, and rebuilding from rounded minutes would move it by a
// point or two for no reason the wearer could see. The hypnogram is second-precision and reconstructs
// the original input exactly.
//
// THE BASIS IS DISPLAY, NOT MEASURED — assertion-INCLUSIVE, matching every other number on the row.
// Filtering to measured-only would recompute a different, lower score: a silent restatement of the
// wearer's night rather than a repair of a missing value.
//
// "Is this row a scar?" (an edited row with score 0 and a known basis) is the store's predicate; it
// lives with the store's basis type, not here.

import java.time.Duration

object SleepScoreHeal {

    /**
     * Rebuild the second-precision staging summary a stored hypnogram was scored from: in-bed is the
     * sum of the IN_BED layer (data gaps are not in bed), light / deep / REM the three asleep stages;
     * total asleep and efficiency fall out of the summary's own accessors. Every segment counts whatever
     * its provenance.
     *
     * Null when the segments cannot describe a night — no positive in-bed span, or no positive asleep
     * time — and when the totals overflow `Duration` (a hypnogram spanning ~10^11 years; upstream sums
     * `Double` seconds there, which a `Duration` cannot hold).
     */
    fun summary(segments: List<SleepSegment>): SleepStaging.Summary? {
        if (segments.isEmpty()) return null
        return try {
            var inBed = Duration.ZERO
            var awake = Duration.ZERO
            var light = Duration.ZERO
            var deep = Duration.ZERO
            var rem = Duration.ZERO
            for (segment in segments) {
                val d = segment.duration
                when (segment.stage) {
                    SleepStage.IN_BED -> inBed = inBed.plus(d)
                    SleepStage.AWAKE -> awake = awake.plus(d)
                    SleepStage.ASLEEP_CORE -> light = light.plus(d)
                    SleepStage.ASLEEP_DEEP -> deep = deep.plus(d)
                    SleepStage.ASLEEP_REM -> rem = rem.plus(d)
                }
            }
            val asleep = light.plus(deep).plus(rem)
            if (!(inBed > Duration.ZERO && asleep > Duration.ZERO)) return null
            SleepStaging.Summary(inBed = inBed, awake = awake, light = light, deep = deep, rem = rem)
        } catch (e: ArithmeticException) {
            null // totals beyond Duration's range: leave the row alone
        }
    }

    /**
     * The score a scarred row should be repaired to, or null when it must be left alone.
     *
     * The composite score is called with exactly the argument list the edit path uses (resting heart
     * rate and temperature omitted there too — the score renormalises over the factors it is given), so
     * a healed row and a freshly edited one agree by construction. Null when the summary cannot be
     * rebuilt, or when the recomputed score is itself 0: writing the sentinel back achieves nothing, and
     * "repaired" and "still broken" would be indistinguishable afterwards.
     */
    fun healedScore(hypnogram: List<SleepSegment>): Int? {
        val s = summary(hypnogram) ?: return null
        val score = SleepScore.composite(
            SleepScore.CompositeInput(
                totalAsleep = SleepStaging.seconds(s.totalAsleep),
                timeAwake = SleepStaging.seconds(s.awake),
                efficiency = s.efficiency,
                deep = SleepStaging.seconds(s.deep),
                light = SleepStaging.seconds(s.light),
                rem = SleepStaging.seconds(s.rem),
            ),
        ).score
        return if (score > 0) score else null
    }
}
