package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/HeadacheEvaluation.swift
// (@ b1c2fdd), whole.
//
// Headache signals — the per-user QUALITY MONITOR.
//
// POLARITY: THIS IS NOT A PERMISSION GATE. The notification unlocks at
// `HeadacheSignals.Tuning.minDaysForBanding` (21) frozen days — the natural floor, below which there
// is no band to notify about. Everything here AUTO-RETIRES the notification for users it
// demonstrably does not help, instead of withholding it from everyone until proven.
//
// THE LABEL-BIAS RULE (upstream's header): every number computed here is valid ONLY while label
// capture stays INDEPENDENT of the flag. The notification carries no logging action and no "did you
// have a headache?" reply — those would collect labels disproportionately from flagged days and
// inflate every precision, lift and p-value below by construction, permanently.
//
// `.monitoring` IS THE DEFAULT AND IS NOT A FAILURE. Not a medical device; no number here may be
// presented as the probability that a headache is coming.
//
// Port notes:
//  • `ScoredDay`, `Metrics` and `Tuning` are immutable (upstream's `var`s change through `copy`);
//    `Metrics` compares its doubles by IEEE `==`. `Status` is a sealed type.
//  • Window arithmetic is upstream's: `Double` seconds added to an instant (`addingSeconds`, which
//    saturates at the ends of `Instant`'s range where a Swift `Date` has none; a NaN offset makes a
//    date that compares false with everything, as upstream's NaN `Date` does).
//  • `log` and `exp` are `StrictMath`'s (fdlibm). Ranks use Swift's own stable sort
//    (`swiftSortedIndices`), so -0.0 ties 0.0 and NaN lands where Swift's sort leaves it.
//  • Counts are combined in 64 bits where upstream's `Int` is 64-bit. The exact tail answers nil
//    above [MAX_TAIL_ROWS] rows instead of allocating a table the size of the row count.

import java.time.Instant
import kotlin.math.sqrt

/** Measures whether the headache index is helping this user, from their own frozen rows and labels. */
object HeadacheEvaluation {

    // Input

    /**
     * One frozen daily row plus whatever label the user's own log supplies for it.
     *
     * [day] is the start of the calendar day the row covers. [computedAt] is when the index was
     * FROZEN — the outcome window opens here (a row frozen at 10:00 is never credited with a headache
     * that started at 07:30). [index] is the frozen 0…100 index, used only for RANKING.
     * [headacheOnset] is the onset relevant to this row, null when nothing was logged near it.
     * [sleepRestaged]: the night re-staged after the freeze (excluded, never rescored).
     * [postUnlock]: frozen after the notification was switched on. [alerted]: a notification
     * actually reached the user for this day.
     */
    data class ScoredDay(
        val day: Instant,
        val computedAt: Instant,
        val index: Int,
        val band: HeadacheSignals.Band,
        val headacheOnset: Instant? = null,
        val sleepRestaged: Boolean = false,
        val postUnlock: Boolean = true,
        val alerted: Boolean = false,
    )

    /** Which rows a [Metrics] run covers (the split keeps the nocebo question askable). */
    enum class Scope(val rawValue: String) {
        ALL("all"),
        PRE_UNLOCK("preUnlock"),
        POST_UNLOCK("postUnlock"),
    }

    // Output

    /**
     * Everything measured over one window. Quantities that are genuinely undefined are null, never 0:
     * a precision of null ("never flagged") and 0.0 ("flagged 40 times, none a headache") are opposite
     * findings.
     *
     * [scoredDays] entered the statistics; [labelledDays] are the POSITIVES; [flaggedDays] banded
     * `.flagged`; [truePositives] both. [lift] = precision / base rate. [auc] is the Mann-Whitney AUC
     * (null with no positives or no negatives), [aucCILow] / [aucCIHigh] its Hanley-McNeil 95 %
     * interval clamped to 0…1, [pValue] the EXACT hypergeometric upper tail. [alertsPerWeek] is
     * interruptions per 7 days of elapsed time. The three exclusion counts make every dropped row
     * visible.
     */
    data class Metrics(
        val scoredDays: Int,
        val labelledDays: Int,
        val flaggedDays: Int,
        val truePositives: Int,
        val baseRate: Double?,
        val precision: Double?,
        val recall: Double?,
        val lift: Double?,
        val auc: Double?,
        val aucCILow: Double?,
        val aucCIHigh: Double?,
        val pValue: Double?,
        val alertsPerWeek: Double?,
        val excludedRestaged: Int,
        val excludedInProgress: Int,
        val excludedUnresolved: Int,
    ) {
        private fun counts(): List<Int> =
            listOf(scoredDays, labelledDays, flaggedDays, truePositives, excludedRestaged, excludedInProgress, excludedUnresolved)

        private fun doubles(): List<Double?> = listOf(baseRate, precision, recall, lift, auc, aucCILow, aucCIHigh, pValue, alertsPerWeek)

        override fun equals(other: Any?): Boolean =
            other is Metrics && counts() == other.counts() && doubles().zip(other.doubles()).all { (a, b) -> ieeeEquals(a, b) }

        override fun hashCode(): Int = counts().hashCode() * 31 + doubles().map { it?.let(::ieeeHash) }.hashCode()
    }

    /** Why the notification was retired. */
    enum class Reason(val rawValue: String) {
        /** The 95 % interval for AUC lies at or below chance. */
        NO_BETTER_THAN_CHANCE("noBetterThanChance"),

        /** Enough flagged days bound the benefit below the smallest gain worth an interruption. */
        NO_USEFUL_PRECISION_GAIN("noUsefulPrecisionGain"),
    }

    /** Where the monitor stands for this user. */
    sealed interface Status {
        /** Fewer frozen rows than the banding floor: no band exists, nothing to notify about. */
        data class Building(val daysRemaining: Int) : Status

        /** Notifying, and the evidence cannot judge the detector either way. THE DEFAULT. */
        data class Monitoring(val metrics: Metrics) : Status

        /** Notifying, and this user's own labels say it beats chance. */
        data class Working(val metrics: Metrics) : Status

        /** Switched off because the evidence says it does not help. */
        data class Retired(val metrics: Metrics, val reason: Reason) : Status
    }

    // Tuning

    /** Every threshold, defaulted; immutable (upstream's `var`s change through `copy`). */
    data class Tuning(
        /** Trailing window the statistics are measured over. 🟡 365. */
        val evaluationWindowDays: Int = 365,
        /** A row is credited with an onset in `(computedAt, computedAt + outcomeWindowHours]`. 🟡 24 h. */
        val outcomeWindowHours: Double = 24.0,
        /** An onset in `[computedAt − inProgressLookbackHours, computedAt]` was already under way. 🟡 24 h. */
        val inProgressLookbackHours: Double = 24.0,
        /** Frozen rows required before the notification exists at all — read live from the banding floor. */
        val minFrozenDaysForNotification: Int = HeadacheSignals.Tuning().minDaysForBanding,
        /** 🟢 α for the exact test, tightened because `status` is stateless and may be asked on every refresh. */
        val workingAlpha: Double = 0.01,
        /** 🔴 PROVISIONAL. Eligible days before `.working` may be claimed. */
        val minScoredDaysForWorking: Int = 120,
        /** 🟢 Below 8 positives the exact tail cannot reach [workingAlpha]. */
        val minPositivesForWorking: Int = 8,
        /** 🔴 PROVISIONAL — a detector that genuinely works still fails α = 0.05 at 180 days. */
        val minScoredDaysForRetirement: Int = 180,
        /** 🟢 Never retire on data that could not have promoted. */
        val minPositivesForRetirement: Int = 8,
        /** 🔴 PROVISIONAL. Precision on fewer than 10 flagged days is noise. */
        val minFlaggedForRetirement: Int = 10,
        /** 🟢 Chance. */
        val chanceAUC: Double = 0.5,
        /** 🔴 PROVISIONAL. The smallest precision gain over the base rate worth an interruption. */
        val minUsefulPrecisionGain: Double = 0.05,
        /** 🟢 Two-sided 95 % normal deviate, for the AUC interval. */
        val ciZ: Double = 1.96,
        /** 🟢 One-sided 95 % normal deviate, for the equivalence bound on precision. */
        val equivalenceZ: Double = 1.645,
    ) {
        private fun ints(): List<Int> = listOf(
            evaluationWindowDays, minFrozenDaysForNotification, minScoredDaysForWorking, minPositivesForWorking,
            minScoredDaysForRetirement, minPositivesForRetirement, minFlaggedForRetirement,
        )

        private fun doubles(): List<Double> =
            listOf(outcomeWindowHours, inProgressLookbackHours, workingAlpha, chanceAUC, minUsefulPrecisionGain, ciZ, equivalenceZ)

        override fun equals(other: Any?): Boolean =
            other is Tuning && ints() == other.ints() && doubles().zip(other.doubles()).all { (a, b) -> ieeeEquals(a, b) }

        override fun hashCode(): Int = ints().hashCode() * 31 + doubles().map(::ieeeHash).hashCode()
    }

    /** Above this many rows the exact tail answers null (about 270 years of daily rows; the table is 8 bytes a row). */
    internal const val MAX_TAIL_ROWS: Int = 100_000

    // Metrics

    /** Measure one window. Pure, deterministic, and free of any calendar or zone. */
    fun metrics(days: List<ScoredDay>, now: Instant, tuning: Tuning = Tuning(), scope: Scope = Scope.ALL): Metrics {
        val cutoff = addingSeconds(now, -tuning.evaluationWindowDays.toDouble() * 86_400)
        val outcome = tuning.outcomeWindowHours * 3600
        val lookback = tuning.inProgressLookbackHours * 3600

        var excludedRestaged = 0
        var excludedInProgress = 0
        var excludedUnresolved = 0

        val positiveScores = ArrayList<Double>()
        val negativeScores = ArrayList<Double>()
        var flagged = 0
        var truePositives = 0
        var alerted = 0L
        var earliest: Instant? = null
        var latest: Instant? = null

        for (row in days) {
            if (!(atOrAfter(row.day, cutoff) && !row.computedAt.isAfter(now))) continue
            when (scope) {
                Scope.ALL -> Unit
                Scope.PRE_UNLOCK -> if (row.postUnlock) continue
                Scope.POST_UNLOCK -> if (!row.postUnlock) continue
            }

            // Interruptions are counted BEFORE the statistical exclusions: an alert that fired on a
            // night which later re-staged still woke the user up.
            if (row.alerted) alerted += 1
            if (earliest == null || row.day.isBefore(earliest)) earliest = row.day
            if (latest == null || row.day.isAfter(latest)) latest = row.day

            // EXCLUSION ORDER IS DELIBERATE: each row is counted at most once.
            // 1. Restaged: the score describes staging the app no longer believes.
            if (row.sleepRestaged) {
                excludedRestaged += 1
                continue
            }
            // 2. Already in progress: a permanent property of the row, so it outranks "not resolved yet".
            val onset = row.headacheOnset
            if (onset != null && !onset.isAfter(row.computedAt) && atOrAfter(onset, addingSeconds(row.computedAt, -lookback))) {
                excludedInProgress += 1
                continue
            }
            // 3. Outcome window still open — dropped ENTIRELY, known positives included, or the base
            //    rate and the precision would be biased upward by construction.
            if (isAfter(addingSeconds(row.computedAt, outcome), now)) {
                excludedUnresolved += 1
                continue
            }

            val positive = onset != null && onset.isAfter(row.computedAt) && atOrBefore(onset, addingSeconds(row.computedAt, outcome))
            val score = row.index.toDouble()
            if (positive) positiveScores += score else negativeScores += score
            if (row.band == HeadacheSignals.Band.FLAGGED) {
                flagged += 1
                if (positive) truePositives += 1
            }
        }

        val scoredDays = positiveScores.size + negativeScores.size
        val labelled = positiveScores.size

        val baseRate = if (scoredDays > 0) labelled.toDouble() / scoredDays.toDouble() else null
        val precision = if (flagged > 0) truePositives.toDouble() / flagged.toDouble() else null
        val recall = if (labelled > 0) truePositives.toDouble() / labelled.toDouble() else null
        val lift = if (precision != null && baseRate != null && baseRate > 0) precision / baseRate else null

        val a = auc(positiveScores, negativeScores)
        var ciLow: Double? = null
        var ciHigh: Double? = null
        val se = a?.let { hanleyMcNeilSE(auc = it, nPos = positiveScores.size, nNeg = negativeScores.size) }
        if (a != null && se != null) {
            ciLow = swiftMin(swiftMax(a - tuning.ciZ * se, 0.0), 1.0)
            ciHigh = swiftMin(swiftMax(a + tuning.ciZ * se, 0.0), 1.0)
        }

        // null, not 1.0, when there is nothing to test: "never flagged" is not "flagging told us nothing".
        val p = if (flagged > 0 && labelled > 0) {
            hypergeometricUpperTail(observed = truePositives, flagged = flagged, positives = labelled, total = scoredDays)
        } else {
            null
        }

        // Elapsed calendar time across the rows held, not out to `now`: extrapolating across a stretch
        // where the ring was not worn would invent alerts that could never have fired.
        val first = earliest
        val last = latest
        val alertsPerWeek = if (first != null && last != null) {
            val spanDays = swiftMax(1.0, roundHalfAwayFromZero(secondsBetween(first, last) / 86_400) + 1)
            7 * alerted.toDouble() / spanDays
        } else {
            null
        }

        return Metrics(
            scoredDays = scoredDays, labelledDays = labelled, flaggedDays = flagged, truePositives = truePositives,
            baseRate = baseRate, precision = precision, recall = recall, lift = lift,
            auc = a, aucCILow = ciLow, aucCIHigh = ciHigh, pValue = p, alertsPerWeek = alertsPerWeek,
            excludedRestaged = excludedRestaged, excludedInProgress = excludedInProgress, excludedUnresolved = excludedUnresolved,
        )
    }

    // Status

    /** Building below the banding floor; else retired, working or (the default) monitoring — retirement checked first. */
    fun status(days: List<ScoredDay>, now: Instant, tuning: Tuning = Tuning()): Status {
        val frozen = frozenRowCount(days, now, tuning)
        if (frozen < tuning.minFrozenDaysForNotification) return Status.Building(daysRemaining = tuning.minFrozenDaysForNotification - frozen)
        val m = metrics(days, now, tuning)
        shouldRetire(m, tuning)?.let { return Status.Retired(m, it) }
        if (meetsWorkingBar(m, tuning)) return Status.Working(m)
        return Status.Monitoring(m)
    }

    /**
     * Evidence that the detector does NOT help this user, or null. `.noBetterThanChance`: the AUC
     * interval's upper bound is at or below chance. `.noUsefulPrecisionGain`: an EQUIVALENCE test —
     * the Wilson upper bound on precision is within [Tuning.minUsefulPrecisionGain] of the base rate.
     * Behind the minimum evidence bar (scored days, positives, flagged days).
     *
     * CALLER CONTRACT: stateless, so asking more often finds more bad-luck runs. Require the
     * recommendation to PERSIST across consecutive decision points before acting on it.
     */
    fun shouldRetire(m: Metrics, tuning: Tuning = Tuning()): Reason? {
        val barMet = m.scoredDays >= tuning.minScoredDaysForRetirement && m.labelledDays >= tuning.minPositivesForRetirement &&
            m.flaggedDays >= tuning.minFlaggedForRetirement
        if (!barMet) return null

        val high = m.aucCIHigh
        if (high != null && high <= tuning.chanceAUC) return Reason.NO_BETTER_THAN_CHANCE

        val base = m.baseRate ?: return null
        val upper = wilsonUpperBound(successes = m.truePositives, trials = m.flaggedDays, z = tuning.equivalenceZ) ?: return null
        return if (upper <= base + tuning.minUsefulPrecisionGain) Reason.NO_USEFUL_PRECISION_GAIN else null
    }

    /** Evidence that the detector DOES help: enough days and positives, the AUC interval's LOWER bound above chance, and p ≤ α. */
    fun meetsWorkingBar(m: Metrics, tuning: Tuning = Tuning()): Boolean {
        if (m.scoredDays < tuning.minScoredDaysForWorking || m.labelledDays < tuning.minPositivesForWorking) return false
        val low = m.aucCILow ?: return false
        if (!(low > tuning.chanceAUC)) return false
        val p = m.pValue ?: return false
        return p <= tuning.workingAlpha
    }

    // Internals

    /** Frozen rows inside the evaluation window — restaged ones INCLUDED (they still sit in the band's window). */
    internal fun frozenRowCount(days: List<ScoredDay>, now: Instant, tuning: Tuning): Int {
        val cutoff = addingSeconds(now, -tuning.evaluationWindowDays.toDouble() * 86_400)
        return days.count { atOrAfter(it.day, cutoff) && !it.computedAt.isAfter(now) }
    }

    /**
     * Mann-Whitney AUC: the probability that a randomly chosen headache day outranks a randomly chosen
     * ordinary day, ties split 0.5 (the only convention under which a constant detector reads as
     * chance). Through the midrank identity `AUC = (R⁺ − n⁺(n⁺+1)/2) / (n⁺·n⁻)`. Null when either
     * side is empty.
     */
    internal fun auc(positiveScores: List<Double>, negativeScores: List<Double>): Double? {
        val nPos = positiveScores.size
        val nNeg = negativeScores.size
        if (nPos <= 0 || nNeg <= 0) return null
        val ranks = midranks(positiveScores + negativeScores)
        var rankSumPositives = 0.0
        for (k in 0 until nPos) rankSumPositives += ranks[k]
        val u = rankSumPositives - nPos.toDouble() * (nPos.toLong() + 1).toDouble() / 2
        return u / (nPos.toDouble() * nNeg.toDouble())
    }

    /** 1-based ranks with tied values (Swift `==`) sharing their average rank, in Swift's sort order. */
    internal fun midranks(values: List<Double>): List<Double> {
        val v = values.toDoubleArray()
        val order = swiftSortedIndices(v)
        val out = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j += 1
            val shared = ((i.toLong() + 1) + (j.toLong() + 1)).toDouble() / 2
            for (k in i..j) out[order[k]] = shared
            i = j + 1
        }
        return out.toList()
    }

    /**
     * Hanley-McNeil standard error of an AUC: `Q1 = A/(2−A)`, `Q2 = 2A²/(1+A)`,
     * `SE = √([A(1−A) + (n⁺−1)(Q1−A²) + (n⁻−1)(Q2−A²)] / (n⁺·n⁻))`; null when undefined. Derived for
     * continuous scores; on this heavily tied index the interval is on the WIDE side (conservative
     * both ways).
     */
    internal fun hanleyMcNeilSE(auc: Double, nPos: Int, nNeg: Int): Double? {
        if (nPos <= 0 || nNeg <= 0) return null
        val q1 = auc / (2 - auc)
        val q2 = 2 * auc * auc / (1 + auc)
        val numerator = auc * (1 - auc) + (nPos - 1).toDouble() * (q1 - auc * auc) + (nNeg - 1).toDouble() * (q2 - auc * auc)
        val variance = numerator / (nPos.toDouble() * nNeg.toDouble())
        if (!(variance.isFinite() && variance >= 0)) return null
        return sqrt(variance)
    }

    /**
     * EXACT upper tail `P(X ≥ observed)` for `X ~ Hypergeometric(total, positives, flagged)` — the
     * permutation test in closed form (a normal approximation is anti-conservative exactly where this
     * is used). Log-factorials are a prefix sum of `log(i)`, as upstream. Null when there is no test
     * to run, and above [MAX_TAIL_ROWS] rows where the table would be needed (upstream allocates one
     * double per row: 4 000 000 000 rows ran to 32 GB; `Int.max` rows trap). The answers that need no
     * table (certain, impossible) stay upstream's at any size; counts are combined in 64 bits.
     */
    internal fun hypergeometricUpperTail(observed: Int, flagged: Int, positives: Int, total: Int): Double? {
        if (!(total > 0 && flagged >= 0 && positives >= 0 && flagged <= total && positives <= total && observed >= 0)) return null
        val lo = maxOf(0L, flagged.toLong() + positives.toLong() - total.toLong())
        val hi = minOf(flagged, positives).toLong()
        if (hi < lo) return null
        if (observed <= lo) return 1.0
        if (observed > hi) return 0.0
        if (total > MAX_TAIL_ROWS) return null

        val logFactorial = DoubleArray(total + 1)
        var acc = 0.0
        for (i in 1..total) {
            acc += StrictMath.log(i.toDouble())
            logFactorial[i] = acc
        }

        fun logChoose(n: Int, k: Int): Double =
            if (k < 0 || k > n) Double.NEGATIVE_INFINITY else logFactorial[n] - logFactorial[k] - logFactorial[n - k]

        val logDenominator = logChoose(total, flagged)
        var sum = 0.0
        // Descending, so the smallest terms are accumulated first.
        var k = hi.toInt()
        while (k >= observed) {
            val logTerm = logChoose(positives, k) + logChoose(total - positives, flagged - k) - logDenominator
            if (logTerm.isFinite()) sum += StrictMath.exp(logTerm)
            k -= 1
        }
        return swiftMin(swiftMax(sum, 0.0), 1.0)
    }

    /**
     * Wilson score interval upper bound for a proportion (Wald's collapses to the point estimate at 0
     * successes, which would retire instantly on a handful of days). Null when undefined.
     */
    internal fun wilsonUpperBound(successes: Int, trials: Int, z: Double): Double? {
        if (!(trials > 0 && successes >= 0 && successes <= trials)) return null
        val n = trials.toDouble()
        val p = successes.toDouble() / n
        val z2 = z * z
        val denominator = 1 + z2 / n
        val centre = (p + z2 / (2 * n)) / denominator
        val half = z * sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denominator
        return swiftMin(swiftMax(centre + half, 0.0), 1.0)
    }

    // An instant built from a NaN offset (null) compares false with everything, as a Swift NaN `Date` does.
    private fun atOrAfter(t: Instant, bound: Instant?): Boolean = bound != null && !t.isBefore(bound)
    private fun atOrBefore(t: Instant, bound: Instant?): Boolean = bound != null && !t.isAfter(bound)
    private fun isAfter(t: Instant?, bound: Instant): Boolean = t != null && t.isAfter(bound)
}
