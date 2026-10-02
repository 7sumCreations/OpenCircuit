package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.HeadacheEvaluation.Reason
import io.github.opencircuit.ringkit.HeadacheEvaluation.ScoredDay
import io.github.opencircuit.ringkit.HeadacheEvaluation.Scope
import io.github.opencircuit.ringkit.HeadacheEvaluation.Status
import io.github.opencircuit.ringkit.HeadacheEvaluation.Tuning
import io.github.opencircuit.ringkit.HeadacheSignals.Band
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HeadacheEvaluationTests.swift
 * (@ b1c2fdd), all 26 tests. Expected values are typed from upstream's test file, never from the
 * Kotlin constants.
 *
 * SYNTHETIC-ONLY, as upstream: every index, label and date comes from a seeded generator. These prove
 * the MONITOR is honest, not that the detector has skill; the load-bearing test is the NEGATIVE
 * control ([noSkillIsNeverJudgedWorking]).
 *
 * Upstream's synthetic years are drawn with its test-local SplitMix64 through the Swift standard
 * library's `Double.random(in:)`, `Int.random(in:)` and `shuffle(using:)`, and some assertions are tied
 * to exact seeds (`:537-539` expects 360 / 48 / 36). Those draws are reproduced bit for bit by
 * `SwiftRandom.kt` (checked against the pinned toolchain by `SwiftRandomTest`), so every seed here
 * draws the year it draws upstream. The test uses fixed instants only (no calendar, no clock); its
 * `var` edits of a tuning become `copy`.
 */
class HeadacheEvaluationTest {

    // Synthetic world

    /** A fixed synthetic instant. Not a real date for anybody. */
    private val anchor: Instant = Instant.ofEpochSecond(1_600_000_000)

    /** Rows are frozen at ~10:00 — after the 3 h settle margin on a normal wake. */
    private val freezeOffsetSeconds = 10L * 3600

    private fun day(i: Int): Instant = anchor.plusSeconds(86_400L * i)
    private fun computedAt(i: Int): Instant = day(i).plusSeconds(freezeOffsetSeconds)

    /** An evaluation instant late enough that day `count-1`'s 24 h outcome window has closed. */
    private fun now(after: Int): Instant = computedAt(after - 1).plusSeconds(28 * 3600)

    /**
     * A synthetic year for one user (upstream's `makeYear`): the latent-plus-shift ("binormal")
     * construction — negatives draw N(0, 1), positives N(d', 1), so AUC is Φ(d'/√2) — mapped to the
     * 0…100 index by `max(0, round(20·latent + 5))` (a large tie mass at zero, as real data has).
     * Flagging takes the top `flaggedFraction` of the series by index, ties broken by position.
     */
    private fun makeYear(
        count: Int = 360,
        positives: Int = 48,
        dPrime: Double,
        flaggedFraction: Double = 0.10,
        alertFlaggedDays: Boolean = true,
        restagedEvery: Int? = null,
        rng: SplitMix64,
    ): List<ScoredDay> {
        val draws = syntheticYearDraws(count = count, positives = positives, dPrime = dPrime, rng = rng)
        val isPositive = draws.positive
        val indices = draws.indices

        // Top-N by index; ties broken by position so the flag set is deterministic.
        val flagCount = roundHalfAwayFromZero(count * flaggedFraction).toInt()
        val flaggedSet = indices.indices
            .sortedWith { a, b -> if (indices[a] == indices[b]) a.compareTo(b) else indices[b].compareTo(indices[a]) }
            .take(flagCount).toSet()

        return (0 until count).map { i ->
            val flagged = i in flaggedSet
            ScoredDay(
                day = day(i),
                computedAt = computedAt(i),
                index = indices[i],
                band = if (flagged) Band.FLAGGED else if (indices[i] > 0) Band.ELEVATED else Band.TYPICAL,
                // A headache 6 h after the freeze — inside the outcome window, a genuine look-ahead.
                headacheOnset = if (isPositive[i]) computedAt(i).plusSeconds(6 * 3600) else null,
                sleepRestaged = restagedEvery?.let { i % it == 0 } ?: false,
                postUnlock = true,
                alerted = flagged && alertFlaggedDays,
            )
        }
    }

    private fun assertClose(expected: Double, actual: Double, accuracy: Double, message: String? = null) =
        assertTrue(abs(expected - actual) <= accuracy, "${message ?: ""} expected $expected ± $accuracy, was $actual")

    // AUC

    @Test
    fun aucKnownAnswers() { // HeadacheEvaluationTests.swift:114
        // Perfect separation.
        assertClose(1.0, HeadacheEvaluation.auc(listOf(10.0, 9.0, 8.0), listOf(1.0, 2.0, 3.0))!!, 1e-12)
        // Perfectly inverted.
        assertClose(0.0, HeadacheEvaluation.auc(listOf(1.0, 2.0, 3.0), listOf(10.0, 9.0, 8.0))!!, 1e-12)
        // ALL TIES — splitting ties is the only convention under which a constant detector reads as chance.
        assertClose(0.5, HeadacheEvaluation.auc(listOf(0.0, 0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0))!!, 1e-12)
        // Hand-computed 5×5 with a tie: U = 16.5, AUC = 16.5 / 25 = 0.66.
        assertClose(0.66, HeadacheEvaluation.auc(listOf(5.0, 4.0, 3.0, 2.0, 1.0), listOf(4.0, 3.0, 3.0, 0.0, 0.0))!!, 1e-12)
        // Undefined, not 0.5: one side empty means there is nothing to rank against.
        assertNull(HeadacheEvaluation.auc(emptyList(), listOf(1.0, 2.0)))
        assertNull(HeadacheEvaluation.auc(listOf(1.0, 2.0), emptyList()))
    }

    /** The midrank identity pinned to the pairwise definition (ties 0.5) on tie-heavy random data. */
    @Test
    fun aucMidrankIdentityMatchesPairwiseCount() { // HeadacheEvaluationTests.swift:142
        val rng = SplitMix64(0xC0FFEE01uL)
        repeat(200) {
            // Deliberately coarse, so ties are common rather than incidental.
            val pos = List(rng.int(1, 12).toInt()) { rng.int(0, 4).toDouble() }
            val neg = List(rng.int(1, 12).toInt()) { rng.int(0, 4).toDouble() }
            var u = 0.0
            for (p in pos) {
                for (n in neg) u += if (p > n) 1.0 else if (p == n) 0.5 else 0.0
            }
            assertClose(u / (pos.size * neg.size), HeadacheEvaluation.auc(pos, neg)!!, 1e-12)
        }
    }

    @Test
    fun hanleyMcNeilCINarrowsAsNGrows() { // HeadacheEvaluationTests.swift:161
        // Same AUC, more data: the interval must shrink and must stay inside [0, 1].
        var previousWidth = Double.POSITIVE_INFINITY
        for (scale in listOf(1, 2, 4, 8, 16)) {
            val pos = List(5 * scale) { 60.0 } + List(5 * scale) { 10.0 }
            val neg = List(10 * scale) { 40.0 }
            val a = HeadacheEvaluation.auc(pos, neg)!!
            val se = HeadacheEvaluation.hanleyMcNeilSE(auc = a, nPos = pos.size, nNeg = neg.size)!!
            val low = maxOf(0.0, a - 1.96 * se)
            val high = minOf(1.0, a + 1.96 * se)
            assertTrue(low >= 0)
            assertTrue(high <= 1)
            assertTrue(high - low < previousWidth)
            previousWidth = high - low
        }
    }

    // The exact p-value

    @Test
    fun hypergeometricTailMatchesHandComputedValue() { // HeadacheEvaluationTests.swift:179
        // N = 10 days, K = 4 headache days, n = 5 flagged, 3 of the flags landed on headaches:
        // P(X ≥ 3) = [C(4,3)·C(6,2) + C(4,4)·C(6,1)] / C(10,5) = 66 / 252.
        assertClose(66.0 / 252.0, HeadacheEvaluation.hypergeometricUpperTail(observed = 3, flagged = 5, positives = 4, total = 10)!!, 1e-12)
        // The whole distribution.
        assertClose(1.0, HeadacheEvaluation.hypergeometricUpperTail(observed = 0, flagged = 5, positives = 4, total = 10)!!, 1e-12)
        // More hits than there are headaches is impossible, not merely unlikely.
        assertClose(0.0, HeadacheEvaluation.hypergeometricUpperTail(observed = 5, flagged = 5, positives = 4, total = 10)!!, 1e-12)
        // Forced-hit floor: flagging 8 of 10 days when 4 are headaches guarantees ≥ 2 hits.
        assertClose(1.0, HeadacheEvaluation.hypergeometricUpperTail(observed = 2, flagged = 8, positives = 4, total = 10)!!, 1e-12)
    }

    /** The p-value must be the EXACT tail: at these counts the normal approximation is anti-conservative. */
    @Test
    fun pValueIsExactNotNormalApproximated() { // HeadacheEvaluationTests.swift:201
        val total = 180
        val positives = 24
        val flagged = 18
        val observed = 6

        // Independent exact computation, written the naive way on purpose: a direct multiplicative
        // binomial coefficient, no log-gamma, no shared code with the implementation.
        fun choose(n: Int, k: Int): Double {
            if (k < 0 || k > n) return 0.0
            var r = 1.0
            for (i in 0 until minOf(k, n - k)) r = r * (n - i).toDouble() / (i + 1).toDouble()
            return r
        }
        var exact = 0.0
        for (k in observed..minOf(flagged, positives)) exact += choose(positives, k) * choose(total - positives, flagged - k)
        exact /= choose(total, flagged)

        val measured = HeadacheEvaluation.hypergeometricUpperTail(observed = observed, flagged = flagged, positives = positives, total = total)!!
        assertClose(exact, measured, 1e-9)

        // The normal approximation with a continuity correction, for contrast. `erfc` is the
        // test-side one in SwiftRandom.kt (checked against Foundation's by SwiftRandomTest).
        val p = positives.toDouble() / total
        val mean = flagged * p
        val variance = flagged * p * (1 - p) * (total - flagged).toDouble() / (total - 1).toDouble()
        val z = (observed - 0.5 - mean) / sqrt(variance)
        val normal = 0.5 * erfc(z / sqrt(2.0))

        assertTrue(normal < measured, "the normal approximation should be anti-conservative here")
        assertTrue(measured - normal > 1e-3, "the gap must be large enough to matter at α = 0.01")
    }

    // Exclusions

    /** Restaged and already-in-progress rows leave BOTH terms, and the counts are visible. */
    @Test
    fun restagedAndInProgressExcludedFromBothTermsAndCounted() { // HeadacheEvaluationTests.swift:241
        val rows = mutableListOf<ScoredDay>()
        // 10 clean flagged-and-positive days.
        for (i in 0 until 10) rows += ScoredDay(day(i), computedAt(i), index = 90, band = Band.FLAGGED, headacheOnset = computedAt(i).plusSeconds(3600))
        // 5 restaged days that also happen to be flagged hits — if they leaked in they would flatter every number.
        for (i in 10 until 15) rows += ScoredDay(day(i), computedAt(i), index = 95, band = Band.FLAGGED, headacheOnset = computedAt(i).plusSeconds(3600), sleepRestaged = true)
        // 4 days whose headache was already under way at freeze time.
        for (i in 15 until 19) rows += ScoredDay(day(i), computedAt(i), index = 95, band = Band.FLAGGED, headacheOnset = computedAt(i).minusSeconds(2 * 3600))
        // 6 clean non-flagged days with no headache.
        for (i in 19 until 25) rows += ScoredDay(day(i), computedAt(i), index = 0, band = Band.TYPICAL)

        val m = HeadacheEvaluation.metrics(rows, now = now(after = 25))
        assertEquals(16, m.scoredDays) // 10 + 6, nothing else
        assertEquals(10, m.labelledDays)
        assertEquals(10, m.flaggedDays)
        assertEquals(10, m.truePositives)
        assertEquals(5, m.excludedRestaged)
        assertEquals(4, m.excludedInProgress)
        assertEquals(0, m.excludedUnresolved)
        assertClose(10.0 / 16.0, m.baseRate!!, 1e-12)
        assertClose(1.0, m.precision!!, 1e-12)
        assertClose(1.0, m.recall!!, 1e-12)
    }

    /** A row is excluded ONCE. Restaged outranks in-progress. */
    @Test
    fun exclusionPrecedenceCountsEachRowOnce() { // HeadacheEvaluationTests.swift:280
        val rows = listOf(
            ScoredDay(day(0), computedAt(0), index = 50, band = Band.FLAGGED, headacheOnset = computedAt(0).minusSeconds(3600), sleepRestaged = true),
        )
        val m = HeadacheEvaluation.metrics(rows, now = now(after = 1))
        assertEquals(1, m.excludedRestaged)
        assertEquals(0, m.excludedInProgress)
        assertEquals(0, m.scoredDays)
    }

    /** A headache that started BEFORE the freeze is never a hit — a retrodiction, not a prediction. */
    @Test
    fun onsetBeforeComputedAtIsNeverCredited() { // HeadacheEvaluationTests.swift:293
        val atFreeze = ScoredDay(day(0), computedAt(0), index = 99, band = Band.FLAGGED, headacheOnset = computedAt(0))
        assertEquals(1, HeadacheEvaluation.metrics(listOf(atFreeze), now = now(after = 1)).excludedInProgress)

        // One second after the freeze IS inside the window — the boundary is half-open on purpose.
        val justAfter = ScoredDay(day(0), computedAt(0), index = 99, band = Band.FLAGGED, headacheOnset = computedAt(0).plusSeconds(1))
        val m = HeadacheEvaluation.metrics(listOf(justAfter), now = now(after = 1))
        assertEquals(0, m.excludedInProgress)
        assertEquals(1, m.labelledDays)
        assertEquals(1, m.truePositives)
    }

    /** A headache older than the in-progress look-back belongs to a different episode. */
    @Test
    fun staleOnsetIsNotTreatedAsInProgress() { // HeadacheEvaluationTests.swift:310
        val stale = ScoredDay(day(3), computedAt(3), index = 10, band = Band.TYPICAL, headacheOnset = computedAt(3).minusSeconds(72 * 3600))
        val m = HeadacheEvaluation.metrics(listOf(stale), now = now(after = 4))
        assertEquals(0, m.excludedInProgress)
        assertEquals(1, m.scoredDays)
        assertEquals(0, m.labelledDays) // outside the outcome window ⇒ a negative day
    }

    /** A row whose 24 h outcome window is still open cannot be labelled yet — even a known positive. */
    @Test
    fun unresolvedOutcomeWindowExcludedIncludingKnownPositives() { // HeadacheEvaluationTests.swift:322
        val justFrozen = computedAt(1)
        val evaluatedAt = justFrozen.plusSeconds(2 * 3600) // 22 h of window still open
        val rows = listOf(
            ScoredDay(day(1), justFrozen, index = 80, band = Band.FLAGGED, headacheOnset = justFrozen.plusSeconds(3600)), // already positive
            ScoredDay(day(1), justFrozen, index = 5, band = Band.TYPICAL),
        )
        val m = HeadacheEvaluation.metrics(rows, now = evaluatedAt)
        assertEquals(2, m.excludedUnresolved)
        assertEquals(0, m.scoredDays)
        assertNull(m.auc)
        assertNull(m.baseRate)
    }

    // Absent, not zero

    @Test
    fun undefinedQuantitiesAreNilNeverZero() { // HeadacheEvaluationTests.swift:339
        // Never flagged: "never flagged" and "40 flags, none a headache" are opposite findings.
        val neverFlagged = (0 until 30).map {
            ScoredDay(day(it), computedAt(it), index = 0, band = Band.TYPICAL, headacheOnset = if (it < 5) computedAt(it).plusSeconds(3600) else null)
        }
        val m1 = HeadacheEvaluation.metrics(neverFlagged, now = now(after = 30))
        assertNull(m1.precision)
        assertNull(m1.lift)
        assertNull(m1.pValue)
        assertClose(0.0, m1.recall!!, 1e-12) // measured: 0 of 5 caught

        // No headaches at all: AUC has nothing to rank, so it is absent — not 0.5.
        val noLabels = (0 until 30).map { ScoredDay(day(it), computedAt(it), index = it, band = if (it > 25) Band.FLAGGED else Band.TYPICAL) }
        val m2 = HeadacheEvaluation.metrics(noLabels, now = now(after = 30))
        assertNull(m2.auc)
        assertNull(m2.aucCILow)
        assertNull(m2.aucCIHigh)
        assertNull(m2.recall)
        assertNull(m2.pValue)
        assertClose(0.0, m2.baseRate!!, 1e-12)
        assertNull(m2.lift) // a base rate of 0 has no lift
        assertClose(0.0, m2.precision!!, 1e-12)
    }

    @Test
    fun alertsPerWeekCountsDeliveredAlertsNotBandedDays() { // HeadacheEvaluationTests.swift:368
        // 28 days, 8 banded `.flagged`, but only 4 alerts actually reached the user.
        val rows = (0 until 28).map { i -> ScoredDay(day(i), computedAt(i), index = 50, band = if (i < 8) Band.FLAGGED else Band.TYPICAL, alerted = i < 4) }
        val m = HeadacheEvaluation.metrics(rows, now = now(after = 28))
        assertEquals(8, m.flaggedDays)
        assertClose(1.0, m.alertsPerWeek!!, 1e-12) // 4 alerts over 28 days
    }

    /** An alert that fired on a night which later re-staged still woke the user up. */
    @Test
    fun alertsPerWeekCountsAlertsOnRowsExcludedFromTheStatistics() { // HeadacheEvaluationTests.swift:384
        val rows = (0 until 28).map { i ->
            ScoredDay(
                day(i), computedAt(i), index = 50, band = if (i < 4) Band.FLAGGED else Band.TYPICAL,
                sleepRestaged = i < 2, // two of the four alerted days later re-staged
                alerted = i < 4,
            )
        }
        val m = HeadacheEvaluation.metrics(rows, now = now(after = 28))
        assertEquals(2, m.excludedRestaged)
        assertEquals(26, m.scoredDays)
        assertEquals(2, m.flaggedDays) // statistics see only the survivors
        assertClose(1.0, m.alertsPerWeek!!, 1e-12) // but all 4 interruptions count
    }

    @Test
    fun scopeSplitsPreAndPostUnlockRows() { // HeadacheEvaluationTests.swift:398
        val rows = (0 until 40).map { i ->
            ScoredDay(day(i), computedAt(i), index = 50, band = Band.FLAGGED, headacheOnset = if (i >= 20) computedAt(i).plusSeconds(3600) else null, postUnlock = i >= 20)
        }
        val n = now(after = 40)
        assertEquals(40, HeadacheEvaluation.metrics(rows, now = n, scope = Scope.ALL).scoredDays)
        assertEquals(20, HeadacheEvaluation.metrics(rows, now = n, scope = Scope.PRE_UNLOCK).scoredDays)
        assertEquals(0, HeadacheEvaluation.metrics(rows, now = n, scope = Scope.PRE_UNLOCK).labelledDays)
        assertEquals(20, HeadacheEvaluation.metrics(rows, now = n, scope = Scope.POST_UNLOCK).scoredDays)
        assertEquals(20, HeadacheEvaluation.metrics(rows, now = n, scope = Scope.POST_UNLOCK).labelledDays)
    }

    // Building

    /** Below `minDaysForBanding` there is no band, so nothing to notify about — WHATEVER the labels say. */
    @Test
    fun buildingBelowMinDaysWhateverTheLabelsSay() { // HeadacheEvaluationTests.swift:416
        val tuning = Tuning()
        val n = tuning.minFrozenDaysForNotification - 1
        val perfect = (0 until n).map { i ->
            ScoredDay(
                day(i), computedAt(i), index = if (i < 5) 100 else 0, band = if (i < 5) Band.FLAGGED else Band.TYPICAL,
                headacheOnset = if (i < 5) computedAt(i).plusSeconds(3600) else null,
            )
        }
        val remaining = (HeadacheEvaluation.status(perfect, now = now(after = n)) as? Status.Building)?.daysRemaining
            ?: fail("expected .building below the banding floor")
        assertEquals(1, remaining)

        // Restaged rows still COUNT toward the floor: they are real frozen indices in the percentile window.
        val withRestaged = perfect + ScoredDay(day(n), computedAt(n), index = 0, band = Band.TYPICAL, sleepRestaged = true)
        if (HeadacheEvaluation.status(withRestaged, now = now(after = n + 1)) is Status.Building) {
            fail("a restaged row is still a frozen row for banding purposes")
        }
    }

    // The negative control (the reason this file exists)

    /**
     * A detector with NO SKILL must not be judged `.working`, across REPEATED looks either. Upstream
     * MEASURED 2026-07-31: 0 of 400 trials, across all 9 looks each; the bound is a regression fence.
     */
    @Test
    fun noSkillIsNeverJudgedWorking() { // HeadacheEvaluationTests.swift:449
        var falseWorking = 0
        val trials = 400
        for (seed in 0uL until trials.toULong()) {
            val year = makeYear(dPrime = 0.0, rng = SplitMix64(0xA11CE000uL + seed))
            var everWorking = false
            for (look in 120..year.size step 28) {
                if (HeadacheEvaluation.status(year.take(look), now = now(after = look)) is Status.Working) {
                    everWorking = true
                    break
                }
            }
            if (everWorking) falseWorking += 1
        }
        val rate = falseWorking.toDouble() / trials
        assertTrue(rate <= 0.02, "noise is being read as skill ($falseWorking/$trials)")
    }

    /**
     * Retiring a detector that DOES work. Upstream MEASURED 2026-07-31 (every 28 days from day 180):
     * AUC ≈ 0.59 → 4.0 % retired (8/200); AUC ≈ 0.65 → 0.5 % (1/200).
     */
    @Test
    fun aRealDetectorIsAlmostNeverRetired() { // HeadacheEvaluationTests.swift:478
        for ((dPrime, bound) in listOf(0.35 to 0.06, 0.55 to 0.03)) {
            var retired = 0
            val trials = 200
            for (seed in 0uL until trials.toULong()) {
                // Swift: 0xBEEF_0000 &+ seed &+ UInt64(dPrime * 100) &* 104_729 (&* binds tighter).
                val rng = SplitMix64(0xBEEF0000uL + seed + (dPrime * 100).toLong().toULong() * 104_729uL)
                val year = makeYear(dPrime = dPrime, rng = rng)
                var everRetired = false
                for (look in 180..year.size step 28) {
                    if (HeadacheEvaluation.status(year.take(look), now = now(after = look)) is Status.Retired) {
                        everRetired = true
                        break
                    }
                }
                if (everRetired) retired += 1
            }
            assertTrue(retired.toDouble() / trials <= bound, "d'=$dPrime: a real detector is being retired ($retired/$trials)")
        }
    }

    /**
     * Characterisation, not a bound: a chance-level detector IS eventually switched off for some users,
     * but `.monitoring` must remain the MAJORITY outcome. Upstream MEASURED 2026-07-31: retired for
     * 15.5 % (31/200) at a single terminal look.
     */
    @Test
    fun chanceLevelDetectorIsEventuallyRetiredButMonitoringDominates() { // HeadacheEvaluationTests.swift:513
        var retired = 0
        var monitoring = 0
        val trials = 200
        for (seed in 0uL until trials.toULong()) {
            val year = makeYear(dPrime = 0.0, rng = SplitMix64(0xC0DE0000uL + seed))
            when (HeadacheEvaluation.status(year, now = now(after = year.size))) {
                is Status.Retired -> retired += 1
                is Status.Monitoring -> monitoring += 1
                is Status.Working -> fail("chance read as working")
                is Status.Building -> fail("a full year should be past the banding floor")
            }
        }
        assertTrue(retired > 0, "a useless alert is never switched off")
        assertTrue(monitoring > trials / 2, ".monitoring must stay the majority outcome")
    }

    /** `.monitoring` is the DEFAULT and is not a failure state. */
    @Test
    fun monitoringIsTheDefaultForAChanceDetector() { // HeadacheEvaluationTests.swift:531
        val year = makeYear(dPrime = 0.0, rng = SplitMix64(0x5EED1234uL))
        val m = (HeadacheEvaluation.status(year, now = now(after = year.size)) as? Status.Monitoring)?.metrics
            ?: fail("a chance-level year should read as .monitoring, not a verdict")
        assertEquals(360, m.scoredDays)
        assertEquals(48, m.labelledDays)
        assertEquals(36, m.flaggedDays)
        assertNotNull(m.auc)
    }

    // The positive controls (the bar must not be merely impossible)

    @Test
    fun realSkillReachesWorking() { // HeadacheEvaluationTests.swift:545
        var working = 0
        val trials = 100
        for (seed in 0uL until trials.toULong()) {
            // d' = 1.40 ⇒ AUC ≈ 0.84, a strong personal detector.
            val year = makeYear(dPrime = 1.40, rng = SplitMix64(0x600D0000uL + seed))
            if (HeadacheEvaluation.status(year, now = now(after = year.size)) is Status.Working) working += 1
        }
        // Upstream MEASURED 2026-07-31: 100 of 100.
        assertTrue(working.toDouble() / trials >= 0.90, "a genuinely strong detector cannot reach .working ($working/$trials)")
    }

    @Test
    fun antiPredictiveDetectorIsRetired() { // HeadacheEvaluationTests.swift:560
        var retired = 0
        var viaAUC = 0
        val trials = 100
        for (seed in 0uL until trials.toULong()) {
            // d' = −0.75 ⇒ AUC ≈ 0.30: the index ranks this user's headache days BELOW their ordinary ones.
            val year = makeYear(dPrime = -0.75, rng = SplitMix64(0xDEAD0000uL + seed))
            val status = HeadacheEvaluation.status(year, now = now(after = year.size))
            if (status is Status.Retired) {
                retired += 1
                if (status.reason == Reason.NO_BETTER_THAN_CHANCE) viaAUC += 1
            }
        }
        // Upstream MEASURED 2026-07-31: retired in 98 of 100, the AUC test catching 95 of those 98.
        assertTrue(retired.toDouble() / trials >= 0.90, "an anti-predictive detector keeps notifying ($retired/$trials)")
        assertTrue(viaAUC > retired * 3 / 4, "the AUC test should be what catches an inverted detector")
    }

    // The minimum evidence bar

    /** Hanley-McNeil's SE collapses to zero at AUC 0 or 1; the bar is what makes retirement safe. */
    @Test
    fun retirementRequiresTheMinimumEvidenceBar() { // HeadacheEvaluationTests.swift:589
        // 30 days, 3 positives, 5 flagged. Every positive scores below every negative: AUC exactly 0,
        // and Hanley-McNeil hands back the degenerate zero-width interval [0, 0].
        val rows = (0 until 30).map { i ->
            val positive = i < 3
            val flagged = i in 3 until 8
            ScoredDay(
                day(i), computedAt(i), index = if (positive) 0 else 50, band = if (flagged) Band.FLAGGED else Band.TYPICAL,
                headacheOnset = if (positive) computedAt(i).plusSeconds(3600) else null,
            )
        }
        val m = HeadacheEvaluation.metrics(rows, now = now(after = 30))
        assertClose(0.0, m.auc!!, 1e-12)
        assertClose(0.0, m.aucCIHigh!!, 1e-12) // the degenerate interval, unguarded
        assertEquals(5, m.flaggedDays)
        assertNull(HeadacheEvaluation.shouldRetire(m), "retired on 30 days and 3 headaches")

        // Each leg of the bar vetoes on its own — a bar is only a bar if every term can block.
        var tuning = Tuning(minScoredDaysForRetirement = 30)
        assertNull(HeadacheEvaluation.shouldRetire(m, tuning = tuning), "positives floor should still block")
        tuning = tuning.copy(minPositivesForRetirement = 3)
        assertNull(HeadacheEvaluation.shouldRetire(m, tuning = tuning), "flagged floor should still block")
        tuning = tuning.copy(minFlaggedForRetirement = 5)
        assertEquals(
            Reason.NO_BETTER_THAN_CHANCE, HeadacheEvaluation.shouldRetire(m, tuning = tuning),
            "with every floor lowered the same data does retire — so the bar, not the statistic, is what protects the user here",
        )
    }

    /** A detector exactly at chance retires only once enough flagged days BOUND the benefit. */
    @Test
    fun chanceDetectorRetiresOnlyOnceTheBenefitIsBounded() { // HeadacheEvaluationTests.swift:622
        /** Every index identical (AUC exactly 0.5), 10 % flagged, precision equal to the base rate. */
        fun chanceSeries(days: Int): List<ScoredDay> {
            val flaggedIdx = (0 until days).filter { it % 10 == 0 }
            val unflaggedIdx = (0 until days).filter { it % 10 != 0 }
            val positives = days / 15
            val truePositives = (flaggedIdx.size * positives) / days
            val positiveSet = flaggedIdx.take(truePositives).toMutableSet()
            positiveSet.addAll(unflaggedIdx.take(positives - truePositives))
            return (0 until days).map { i ->
                ScoredDay(
                    day(i), computedAt(i), index = 40, band = if (i % 10 == 0) Band.FLAGGED else Band.TYPICAL,
                    headacheOnset = if (i in positiveSet) computedAt(i).plusSeconds(3600) else null,
                )
            }
        }
        val tuning = Tuning(evaluationWindowDays = 4000)

        // One year: 36 flagged days cannot BOUND the benefit — "we cannot tell yet", not "it failed".
        val oneYear = HeadacheEvaluation.metrics(chanceSeries(days = 360), now = now(after = 360), tuning = tuning)
        assertClose(0.5, oneYear.auc!!, 1e-12)
        assertNull(HeadacheEvaluation.shouldRetire(oneYear, tuning = tuning))

        // Several years: the AUC interval still straddles chance, but the precision bound has
        // tightened under the margin, so the alert retires itself for the right reason.
        val long = HeadacheEvaluation.metrics(chanceSeries(days = 1800), now = now(after = 1800), tuning = tuning)
        assertTrue(long.aucCIHigh!! > 0.5)
        assertEquals(Reason.NO_USEFUL_PRECISION_GAIN, HeadacheEvaluation.shouldRetire(long, tuning = tuning))
    }

    /** Every leg of the `.working` conjunction blocks on its own. */
    @Test
    fun eachWorkingCriterionAloneBlocks() { // HeadacheEvaluationTests.swift:663
        val year = makeYear(dPrime = 1.40, rng = SplitMix64(0x12345678uL))
        val n = now(after = year.size)
        val m = HeadacheEvaluation.metrics(year, now = n)
        assertTrue(HeadacheEvaluation.meetsWorkingBar(m))

        assertFalse(HeadacheEvaluation.meetsWorkingBar(m, tuning = Tuning(minScoredDaysForWorking = 100_000)))
        assertFalse(HeadacheEvaluation.meetsWorkingBar(m, tuning = Tuning(minPositivesForWorking = 10_000)))
        assertFalse(HeadacheEvaluation.meetsWorkingBar(m, tuning = Tuning(workingAlpha = 0.0)))
        assertFalse(HeadacheEvaluation.meetsWorkingBar(m, tuning = Tuning(chanceAUC = 0.999)))
    }

    /** The gate reads the CI LOWER BOUND, not the point estimate. */
    @Test
    fun workingUsesTheCILowerBoundNotThePointEstimate() { // HeadacheEvaluationTests.swift:685
        // d' = 0.55 ⇒ AUC ≈ 0.65. Over one year the interval still straddles chance for many users.
        var straddled = 0
        for (seed in 0uL until 50uL) {
            val m = HeadacheEvaluation.metrics(makeYear(dPrime = 0.55, rng = SplitMix64(0x77770000uL + seed)), now = now(after = 360))
            val a = m.auc ?: continue
            val low = m.aucCILow ?: continue
            if (!(a > 0.5 && low <= 0.5)) continue
            straddled += 1
            assertFalse(HeadacheEvaluation.meetsWorkingBar(m), "AUC $a with a lower bound of $low was called working")
        }
        assertTrue(straddled > 0, "no trial produced an above-chance point estimate with a straddling interval, so this test proves nothing")
    }

    // Determinism

    @Test
    fun metricsAreDeterministic() { // HeadacheEvaluationTests.swift:709
        val year = makeYear(dPrime = 0.55, restagedEvery = 17, rng = SplitMix64(0xFACE0001uL))
        val n = now(after = year.size)
        val first = HeadacheEvaluation.metrics(year, now = n)
        repeat(5) { assertEquals(first, HeadacheEvaluation.metrics(year, now = n)) }
        // Row order must not move a single number: the statistics are a property of the SET.
        val shuffled = year.toMutableList()
        SplitMix64(0xFACE0002uL).shuffle(shuffled)
        assertEquals(first, HeadacheEvaluation.metrics(shuffled, now = n))
    }
}
