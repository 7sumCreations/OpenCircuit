package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.HeadacheSignals.AbsentReason
import io.github.opencircuit.ringkit.HeadacheSignals.Assessment
import io.github.opencircuit.ringkit.HeadacheSignals.Band
import io.github.opencircuit.ringkit.HeadacheSignals.Contribution
import io.github.opencircuit.ringkit.HeadacheSignals.DayInput
import io.github.opencircuit.ringkit.HeadacheSignals.Feature
import io.github.opencircuit.ringkit.HeadacheSignals.Series
import io.github.opencircuit.ringkit.HeadacheSignals.Tuning
import io.github.opencircuit.ringkit.HeadacheSignals.Verdict
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HeadacheSignalsTests.swift (@ b1c2fdd),
 * all 20 tests. Expected values are typed from upstream's test file, never from the Kotlin constants.
 *
 * SYNTHETIC-ONLY, as upstream: every series is a flat hand-built vector with a known median and MAD —
 * never a real health value. These prove CORRECTNESS, NOT SKILL: the machine computes what
 * `HeadacheSignals` says it computes, a missing input is ABSENT rather than a substituted zero, and
 * the structural guards (ring-feature minimum, anchor, 35 % cap, multi-feature top band, alert
 * budget) hold. Nothing here says the index predicts anything.
 *
 * Upstream's fixture uses fixed instants only (no calendar, no clock). Its `var` edits of a day's
 * input become `copy`, and its private `SeededUniform` LCG is `ULong` arithmetic (wrapping, as `&*`
 * and `&+`).
 */
class HeadacheSignalsTest {

    // Fixture — a fixed synthetic instant. Nothing about it is a real night.
    private val day: Instant = Instant.ofEpochSecond(1_753_660_800)
    private val now: Instant = day.plusSeconds(8 * 3600)

    // Flat baselines, so 1.4826 · MAD == 0 and the z-scale is exactly the feature's noise floor. Every
    // expected value below is therefore hand-computable as `delta / noiseFloor`.
    private val rhrBase = 60.0 // floor 5 bpm
    private val hrvBase = 50.0 // floor 8 ms
    private val effBase = 90.0 // floor 5 %-pt
    private val fragBase = 40.0 // floor 15 min
    private val durBase = 420.0 // floor 30 min
    private val bedBase = 23 * 60 // 23:00, floor 30 min
    private val dayHRBase = 70.0 // let-down uses the restingHR floor, 5 bpm

    private fun flat(value: Double, count: Int = 14): List<Double> = List(count) { value }

    /** One day's input. Passing null for a today-value makes that feature ABSENT. */
    private fun input(
        rhrToday: Double? = 60.0,
        hrvToday: Double? = 50.0,
        effToday: Double? = 90.0,
        fragToday: Double? = 40.0,
        durToday: Double? = 420.0,
        tempOffsetC: Double? = 0.0,
        bedToday: Int? = 23 * 60,
        bedPriorNights: Int = 14,
        dayHRPrevious: Double? = 70.0,
        dayHRTwoDaysAgo: Double? = 70.0,
        dayHRPriorNights: Int = 14,
        peri: Boolean? = false,
        truncated: Boolean = false,
        fever: Boolean = false,
        alreadyLogged: Boolean = false,
        priorIndices: List<Int> = emptyList(),
        lastRingDataAt: Instant? = null,
    ): DayInput {
        fun series(today: Double?, base: Double): Series? = today?.let { Series(today = it, prior = flat(base)) }
        return DayInput(
            day = day,
            now = now,
            lastRingDataAt = lastRingDataAt ?: now.minusSeconds(3600),
            restingHR = series(rhrToday, rhrBase),
            hrvSDNN = series(hrvToday, hrvBase),
            sleepEfficiencyPct = series(effToday, effBase),
            sleepFragmentationMin = series(fragToday, fragBase),
            sleepDurationMin = series(durToday, durBase),
            skinTempOffsetC = tempOffsetC,
            inBedStartMinutes = bedToday,
            priorInBedStartMinutes = List(bedPriorNights) { bedBase },
            dayHRPrevious = dayHRPrevious,
            dayHRTwoDaysAgo = dayHRTwoDaysAgo,
            dayHRPrior = flat(dayHRBase, dayHRPriorNights),
            isPerimenstrual = peri,
            sleepLikelyTruncated = truncated,
            feverSuspected = fever,
            headacheAlreadyLoggedToday = alreadyLogged,
            priorIndices = priorIndices,
        )
    }

    private fun assessment(verdict: Verdict): Assessment? = (verdict as? Verdict.Scored)?.assessment

    private fun contribution(a: Assessment, feature: Feature): Contribution? = a.contributions.firstOrNull { it.feature == feature }

    private fun assertClose(expected: Double, actual: Double, accuracy: Double, message: String? = null) =
        assertTrue(abs(expected - actual) <= accuracy, "${message ?: ""} expected $expected ± $accuracy, was $actual")

    // An ordinary day

    @Test
    fun ordinaryDayScoresZero() { // HeadacheSignalsTests.swift:102
        val a = assertNotNull(assessment(HeadacheSignals.assess(input())))

        assertEquals(0, a.index)
        assertEquals(Band.TYPICAL, a.band)
        assertNull(a.suppressedBy)
        assertEquals(8, a.ringFeatureCount, "all eight ring features measured")
        assertClose(1.0, a.coverageFraction, 1e-9)
        for (c in a.contributions.filter { it.isPresent }) {
            assertClose(0.0, c.contribution!!, 1e-9, "${c.feature.rawValue} must contribute 0")
        }
    }

    // Absent ≠ zero

    @Test
    fun missingInputsAreAbsentNotZero() { // HeadacheSignalsTests.swift:119
        // Sleep efficiency 70 % against a flat 90 % baseline → (70-90)/5 = -4 z, clamped at the ±4
        // zClamp, |z| ≥ saturationZ → contribution exactly 1.0. Everything else sits at 0.
        // `peri: nil` keeps the pool to ring features only.
        val a = assertNotNull(assessment(HeadacheSignals.assess(input(hrvToday = null, effToday = 70.0, peri = null))))

        val hrv = assertNotNull(contribution(a, Feature.HRV_DEVIATION))
        assertNull(hrv.contribution, "absent, NEVER 0")
        assertNull(hrv.z)
        assertEquals(AbsentReason.NO_DATA_THIS_DAY, hrv.absentReason)
        assertClose(0.0, hrv.effectiveWeight, 1e-9)
        assertFalse(hrv.isPresent)

        assertClose(1.0, assertNotNull(contribution(a, Feature.SLEEP_EFFICIENCY_DROP)?.contribution), 1e-9)

        // Present weight = 1.00 − 0.14 (HRV) = 0.86, so index = round(100 · 0.18 / 0.86) = 21.
        assertEquals(21, a.index)
        // The diluted answer — as if HRV had been measured and read exactly normal — is 18.
        assertNotEquals(18, a.index, "an absent feature must not dilute the score")
        assertEquals(7, a.ringFeatureCount)
        assertClose(0.86, a.coverageFraction, 1e-9)
    }

    /** "We have never had enough of your history" is a different message from "the ring gave us nothing". */
    @Test
    fun shortHistoryIsNoBaselineNotNoData() { // HeadacheSignalsTests.swift:146
        val i = input().copy(hrvSDNN = Series(today = 50.0, prior = flat(hrvBase, RobustBaseline.MIN_BASELINE_DAYS - 1)))
        val a = assertNotNull(assessment(HeadacheSignals.assess(i)))
        assertEquals(AbsentReason.NO_BASELINE, assertNotNull(contribution(a, Feature.HRV_DEVIATION)).absentReason)
    }

    // Coverage and anchor gates

    @Test
    fun belowMinRingFeaturesReturnsNil() { // HeadacheSignalsTests.swift:155
        // Three ring features measured (RHR, HRV, efficiency); everything else absent.
        val v = HeadacheSignals.assess(
            input(fragToday = null, durToday = null, tempOffsetC = null, bedToday = null, dayHRPrevious = null, dayHRTwoDaysAgo = null, peri = null),
        )
        val missing = (v as? Verdict.InsufficientData)?.missing ?: fail("3 ring features must never produce a score, got $v")
        assertNull(assessment(v), "no index, not even 0")
        assertEquals(AbsentReason.NO_DATA_THIS_DAY, missing[Feature.SKIN_TEMP_DEVIATION])
        assertEquals(AbsentReason.NO_DATA_THIS_DAY, missing[Feature.SLEEP_FRAGMENTATION])
        assertEquals(AbsentReason.NO_DATA_THIS_DAY, missing[Feature.SLEEP_DURATION_DEVIATION])
    }

    /** A calendar lookup is not a measurement, so it can never make up the ring-feature shortfall. */
    @Test
    fun perimenstrualDoesNotCountTowardRingMinimum() { // HeadacheSignalsTests.swift:171
        val without = HeadacheSignals.assess(
            input(fragToday = null, durToday = null, tempOffsetC = null, bedToday = null, dayHRPrevious = null, dayHRTwoDaysAgo = null, peri = null),
        )
        val with = HeadacheSignals.assess(
            input(fragToday = null, durToday = null, tempOffsetC = null, bedToday = null, dayHRPrevious = null, dayHRTwoDaysAgo = null, peri = true),
        )
        assertIs<Verdict.InsufficientData>(with, "3 ring features + cycle phase is still 3 ring features, got $with")
        assertEquals(without, with, "adding the calendar term changes nothing about the verdict")
    }

    /** A day built only from context has measured nothing about how the person slept. */
    @Test
    fun anchorRuleRejectsContextOnlyDay() { // HeadacheSignalsTests.swift:186
        val contextOnly = input(rhrToday = null, hrvToday = null, effToday = null, fragToday = null, durToday = null, tempOffsetC = 0.6, peri = true)

        // Under the shipped tuning the coverage gate (4 ring features) catches this first: there are
        // only three non-anchor ring features in existence, so the anchor gate is defence in depth
        // rather than the operative rule. Both must reject.
        assertIs<Verdict.InsufficientData>(HeadacheSignals.assess(contextOnly), "context-only day must never score")

        // Isolate the anchor gate by lowering the coverage minimum to the three features present.
        val loose = Tuning(minRingFeaturesForScore = 3)
        assertIs<Verdict.InsufficientData>(HeadacheSignals.assess(contextOnly, tuning = loose), "skin temp + schedule + let-down are all non-anchors — no verdict")

        // Positive control: ADD one anchor and change nothing else — the same day now scores, so the
        // rejection above was the ANCHOR rule and not simply thin data.
        val anchored = input(hrvToday = null, effToday = null, fragToday = null, durToday = null, tempOffsetC = 0.6, peri = true)
        assertNotNull(assessment(HeadacheSignals.assess(anchored, tuning = loose)), "one anchor (resting HR) is enough")
    }

    /**
     * A genuine first-week install must be told we are LEARNING, not that its ring gave us nothing.
     * REGRESSION LOCK upstream: the gate asks whether the MISSING BASELINES alone would have carried
     * us to the minimum.
     */
    @Test
    fun coldStartReportsBuildingBaselineNotMissingData() { // HeadacheSignalsTests.swift:223
        // Six nights of history everywhere: real values, no baseline yet anywhere.
        val coldStart = input(tempOffsetC = null, bedPriorNights = 6, dayHRPriorNights = 0, peri = null).copy(
            restingHR = Series(today = rhrBase, prior = flat(rhrBase, 6)),
            hrvSDNN = Series(today = hrvBase, prior = flat(hrvBase, 6)),
            sleepEfficiencyPct = Series(today = effBase, prior = flat(effBase, 6)),
            sleepFragmentationMin = Series(today = fragBase, prior = flat(fragBase, 6)),
            sleepDurationMin = Series(today = durBase, prior = flat(durBase, 6)),
        )

        val verdict = HeadacheSignals.assess(coldStart)
        val daysRemaining = (verdict as? Verdict.BuildingBaseline)?.daysRemaining
            ?: fail("a 6-day-old install is still learning, not missing data: got $verdict")
        assertEquals(1, daysRemaining, "6 nights held, 7 needed — one more night")

        // The count must come from the SERIES, not from `priorIndices`: those only accumulate once a
        // day actually scores, which is the very thing this gate is blocking.
        assertTrue(coldStart.priorIndices.isEmpty(), "fixture holds no frozen rows, by construction")
    }

    /** An ESTABLISHED user whose ring simply did not report must NOT be told we are still learning. */
    @Test
    fun establishedUserWithNoDataIsInsufficientNotBuildingBaseline() { // HeadacheSignalsTests.swift:247
        // 60 nights of history on every series, but nothing measured last night.
        val gap = input(tempOffsetC = null, bedPriorNights = 60, dayHRPriorNights = 0, peri = null).copy(
            restingHR = null, hrvSDNN = null, sleepEfficiencyPct = null, sleepFragmentationMin = null, sleepDurationMin = null,
        )

        val verdict = HeadacheSignals.assess(gap)
        val missing = (verdict as? Verdict.InsufficientData)?.missing
            ?: fail("no readings last night is a DATA gap, not a baseline gap: got $verdict")
        assertEquals(AbsentReason.NO_DATA_THIS_DAY, missing[Feature.RESTING_HR_DEVIATION])
        assertEquals(AbsentReason.NO_DATA_THIS_DAY, missing[Feature.HRV_DEVIATION])
    }

    // The 35 % cap

    /**
     * The guard against a top-band day produced by a CALENDAR LOOKUP with no ring measurement
     * contributing. Exhaustive over every 4-subset of the nine features INCLUDING `perimenstrual`.
     */
    @Test
    fun noSingleFeatureExceedsCapAtMinimumFeatureCount() { // HeadacheSignalsTests.swift:270
        val all = Feature.entries
        val tuning = Tuning()
        val subsets = mutableListOf<List<Feature>>()
        for (a in all.indices) {
            for (b in a + 1 until all.size) {
                for (c in b + 1 until all.size) {
                    for (d in c + 1 until all.size) subsets += listOf(all[a], all[b], all[c], all[d])
                }
            }
        }
        assertEquals(126, subsets.size, "C(9,4) — every 4-subset, not a sample")

        var subsetsThatNeededCapping = 0
        for (subset in subsets) {
            val contributions = all.map { f ->
                if (f in subset) {
                    Contribution(feature = f, z = 4.0, contribution = 1.0, effectiveWeight = f.weight, absentReason = null)
                } else {
                    Contribution(feature = f, z = null, contribution = null, effectiveWeight = 0.0, absentReason = AbsentReason.NO_DATA_THIS_DAY)
                }
            }
            val label = subset.joinToString("+") { it.rawValue }

            val preTotal = contributions.fold(0.0) { acc, c -> acc + c.effectiveWeight }
            if (contributions.maxOf { it.effectiveWeight } / preTotal > tuning.maxSingleFeatureShare) subsetsThatNeededCapping += 1

            val capped = HeadacheSignals.applySingleFeatureCap(contributions, tuning = tuning)
            val total = capped.fold(0.0) { acc, c -> acc + c.effectiveWeight }
            val share = capped.maxOf { it.effectiveWeight } / total
            assertTrue(share <= tuning.maxSingleFeatureShare + 1e-9, "$label leaves one feature at $share of the pool")
            // The cap redistributes; it must never zero a measured feature out of the pool.
            for (c in capped.filter { it.feature in subset }) assertTrue(c.effectiveWeight > 0, "$label dropped ${c.feature.rawValue}")
        }
        assertTrue(subsetsThatNeededCapping > 0, "if nothing needed capping the test is vacuous")
    }

    // Unsigned by design

    /** Every feature except the let-down term scores departure in EITHER direction. */
    @Test
    fun unsignedDeviationIsSymmetric() { // HeadacheSignalsTests.swift:318
        // (feature, base, noise floor) — a ±2 z probe is base ± 2 · floor.
        val probes: List<Triple<Feature, Double, (Double) -> DayInput>> = listOf(
            Triple(Feature.RESTING_HR_DEVIATION, rhrBase) { x -> input(rhrToday = x) },
            Triple(Feature.HRV_DEVIATION, hrvBase) { x -> input(hrvToday = x) },
            Triple(Feature.SLEEP_EFFICIENCY_DROP, effBase) { x -> input(effToday = x) },
            Triple(Feature.SLEEP_FRAGMENTATION, fragBase) { x -> input(fragToday = x) },
            Triple(Feature.SLEEP_DURATION_DEVIATION, durBase) { x -> input(durToday = x) },
        )
        // (|z| 2 − onsetZ 1) / (saturationZ 2.5 − onsetZ 1) = 2/3.
        val expected = 2.0 / 3.0

        for ((feature, base, make) in probes) {
            val delta = 2 * feature.noiseFloor
            val up = assertNotNull(assessment(HeadacheSignals.assess(make(base + delta))))
            val down = assertNotNull(assessment(HeadacheSignals.assess(make(base - delta))))
            val cUp = assertNotNull(contribution(up, feature))
            val cDown = assertNotNull(contribution(down, feature))

            assertClose(expected, cUp.contribution!!, 1e-9, feature.rawValue)
            assertClose(cUp.contribution!!, cDown.contribution!!, 1e-9, "${feature.rawValue} must score the same in both directions")
            assertClose(cUp.z!!, cDown.z!!, 1e-9, "|z| is stored, so the sign is gone")
            assertEquals(up.index, down.index, "${feature.rawValue}: the whole index is symmetric")
        }

        // Skin temperature ramps in °C rather than z. The raw signed offset IS kept on the
        // contribution (the UI needs it) — only the contribution is unsigned.
        val warm = assertNotNull(assessment(HeadacheSignals.assess(input(tempOffsetC = 0.75))))
        val cool = assertNotNull(assessment(HeadacheSignals.assess(input(tempOffsetC = -0.75))))
        assertClose(0.5, assertNotNull(contribution(warm, Feature.SKIN_TEMP_DEVIATION)).contribution!!, 1e-9)
        assertClose(0.5, assertNotNull(contribution(cool, Feature.SKIN_TEMP_DEVIATION)).contribution!!, 1e-9)
        assertEquals(warm.index, cool.index)
    }

    // The one signed term

    /** A FALL in daytime arousal from D−2 to D−1 is the risk direction; a RISE contributes nothing. */
    @Test
    fun letdownIsSignedAndOnlyCountsAFall() { // HeadacheSignalsTests.swift:360
        // Baseline day HR is flat at 70 with the 5 bpm resting-HR floor, so 80 → z = +2.
        val falling = assertNotNull(assessment(HeadacheSignals.assess(input(dayHRPrevious = 70.0, dayHRTwoDaysAgo = 80.0)))) // z2 − z1 = 2 − 0 = +2
        val rising = assertNotNull(assessment(HeadacheSignals.assess(input(dayHRPrevious = 80.0, dayHRTwoDaysAgo = 70.0)))) // −2 → clipped to 0

        val fell = assertNotNull(contribution(falling, Feature.AROUSAL_LETDOWN))
        val rose = assertNotNull(contribution(rising, Feature.AROUSAL_LETDOWN))
        // Upstream's SPEC-vs-CODE note: both days are scored against the resting-HR floor (5 bpm), never
        // `arousalLetdown.noiseFloor`. Pinned to the CODE, as upstream.
        assertClose(2.0, fell.z!!, 1e-9)
        assertClose(2.0 / 3.0, fell.contribution!!, 1e-9)
        assertClose(0.0, rose.z!!, 1e-9)
        assertClose(0.0, rose.contribution!!, 1e-9, "a RISE in arousal is not the signal")
        assertTrue(falling.index > rising.index)

        // Only D−1 and D−2 feed it: same let-down inputs, everything else about the day pushed to an extreme.
        val noisyDay = assertNotNull(
            assessment(HeadacheSignals.assess(input(rhrToday = 100.0, effToday = 60.0, tempOffsetC = 2.0, dayHRPrevious = 70.0, dayHRTwoDaysAgo = 80.0, truncated = true))),
        )
        assertEquals(fell, assertNotNull(contribution(noisyDay, Feature.AROUSAL_LETDOWN)), "the let-down term saw only D−1 and D−2")

        // Absent when either day is missing — not silently treated as "no fall".
        val missing = assertNotNull(assessment(HeadacheSignals.assess(input(dayHRTwoDaysAgo = null))))
        assertEquals(AbsentReason.NO_DATA_THIS_DAY, assertNotNull(contribution(missing, Feature.AROUSAL_LETDOWN)).absentReason)
    }

    // Saturation and degenerate baselines

    @Test
    fun saturationClamps() { // HeadacheSignalsTests.swift:396
        // 100 bpm against a flat 60 baseline is z = 8 raw, clamped to the ±4 zClamp; the ramp then
        // clamps at 1.0. Neither may overshoot.
        val a = assertNotNull(assessment(HeadacheSignals.assess(input(rhrToday = 100.0))))
        val rhr = assertNotNull(contribution(a, Feature.RESTING_HR_DEVIATION))
        assertClose(RobustBaseline.Z_CLAMP, rhr.z!!, 1e-9)
        assertClose(1.0, rhr.contribution!!, 1e-9)
        assertTrue(rhr.contribution!! <= 1.0)

        // Skin temp saturates on its own °C ramp.
        val hot = assertNotNull(assessment(HeadacheSignals.assess(input(tempOffsetC = 25.0))))
        assertClose(1.0, assertNotNull(contribution(hot, Feature.SKIN_TEMP_DEVIATION)).contribution!!, 1e-9)

        // Everything saturated at once is still an index of 100, never more.
        val everything = assertNotNull(
            assessment(
                HeadacheSignals.assess(
                    input(
                        rhrToday = 100.0, hrvToday = 0.0, effToday = 40.0, fragToday = 200.0, durToday = 0.0, tempOffsetC = 3.0,
                        bedToday = 12 * 60, dayHRPrevious = 60.0, dayHRTwoDaysAgo = 100.0, peri = true,
                    ),
                ),
            ),
        )
        assertEquals(100, everything.index)
    }

    /** MAD == 0: the noise floor takes over as the scale, so a sub-floor wobble contributes exactly 0. */
    @Test
    fun zeroMADIsAbsentNotInfinite() { // HeadacheSignalsTests.swift:421
        // 64 bpm against a flat 60 baseline: 4 bpm is below the 5 bpm floor → z 0.8 → below onsetZ 1.0
        // → contributes 0. The feature is PRESENT with a real z; only the deviation is absent.
        val a = assertNotNull(assessment(HeadacheSignals.assess(input(rhrToday = 64.0))))
        val rhr = assertNotNull(contribution(a, Feature.RESTING_HR_DEVIATION))
        assertTrue(rhr.isPresent, "a flat baseline is a baseline, not a missing one")
        assertNull(rhr.absentReason)
        assertTrue(rhr.z!!.isFinite())
        assertClose(0.8, rhr.z!!, 1e-9)
        assertClose(0.0, rhr.contribution!!, 1e-9)

        for (c in a.contributions) {
            assertTrue(c.z?.isFinite() ?: true, "${c.feature.rawValue} z is not finite")
            assertTrue(c.contribution?.isFinite() ?: true, "${c.feature.rawValue} contribution is not finite")
            assertTrue(c.effectiveWeight.isFinite())
        }
        assertEquals(0, a.index)
    }

    // Quality multipliers

    /**
     * A ring-buffer-truncated night looks exactly like a genuinely short, broken one, so the THREE
     * sleep features are halved — and NOTHING else moves (upstream's 2026-08-20 change added
     * `sleepEfficiencyDrop` to the halved set).
     */
    @Test
    fun truncatedNightHalvesSleepWeight() { // HeadacheSignalsTests.swift:458
        val normal = assertNotNull(assessment(HeadacheSignals.assess(input())))
        val cut = assertNotNull(assessment(HeadacheSignals.assess(input(truncated = true))))
        val halved = setOf(Feature.SLEEP_DURATION_DEVIATION, Feature.SLEEP_FRAGMENTATION, Feature.SLEEP_EFFICIENCY_DROP)

        for (feature in Feature.entries) {
            val before = assertNotNull(contribution(normal, feature))
            val after = assertNotNull(contribution(cut, feature))
            if (feature in halved) {
                assertClose(feature.weight * Tuning().truncatedSleepQuality, after.effectiveWeight, 1e-9, feature.rawValue)
                assertClose(before.effectiveWeight / 2, after.effectiveWeight, 1e-9)
            } else {
                assertClose(before.effectiveWeight, after.effectiveWeight, 1e-9, "${feature.rawValue} must be untouched by the truncation flag")
            }
            // Truncation changes the WEIGHT of the evidence, never the measurement itself.
            assertEquals(before.contribution, after.contribution, feature.rawValue)
            assertEquals(before.z, after.z, feature.rawValue)
        }
    }

    // Banding — the alert budget

    @Test
    fun bandingRequiresMinDays() { // HeadacheSignalsTests.swift:484
        val tuning = Tuning()
        val thin = List(tuning.minDaysForBanding - 1) { 0 }
        assertEquals(Band.TYPICAL, HeadacheSignals.band(index = 100, priorIndices = thin), "below minDaysForBanding there is no band, ever")
        assertEquals(Band.FLAGGED, HeadacheSignals.band(index = 100, priorIndices = thin + 0), "…and exactly at the minimum there is one")

        // End to end: a maximal day with too little history is still `.typical`.
        val a = assessment(HeadacheSignals.assess(input(rhrToday = 100.0, hrvToday = 0.0, effToday = 40.0, priorIndices = thin)))
        assertEquals(Band.TYPICAL, a?.band)
        assertTrue((a?.index ?: 0) > 0, "the index is real; only the band is withheld")
    }

    /**
     * The false-alarm BUDGET: flagging the top decile of a person's own scale must cost roughly 0.8
     * interrupts a week. Pooled over 50 independent synthetic years, as upstream.
     */
    @Test
    fun highBandRateIsBoundedByPercentile() { // HeadacheSignalsTests.swift:506
        val years = 50
        val daysPerYear = 365
        var flagged = 0
        var elevatedOrWorse = 0

        for (seed in 1uL..years.toULong()) {
            val rng = SeededUniform(seed)
            val priorIndices = mutableListOf<Int>()
            repeat(daysPerYear) {
                val index = roundHalfAwayFromZero(rng.next01() * 100).toInt()
                val band = HeadacheSignals.band(index = index, priorIndices = priorIndices)
                if (band == Band.FLAGGED) flagged += 1
                if (band > Band.TYPICAL) elevatedOrWorse += 1
                priorIndices += index
            }
        }

        val total = (years * daysPerYear).toDouble()
        val flaggedRate = flagged / total
        assertTrue(flaggedRate <= 0.11, "flagged on $flaggedRate of days — over the ~0.8 alerts/week budget")
        // Positive control: the rule must not be vacuously safe by never firing.
        assertTrue(flaggedRate > 0.08)
        assertTrue(elevatedOrWorse / total > flaggedRate, "the p75 band must be wider than the p90 band")
    }

    /** The top band can NEVER be reached by thresholding one input, however high that day ranks. */
    @Test
    fun flaggedRequiresThreeContributingFeatures() { // HeadacheSignalsTests.swift:536
        val tuning = Tuning()
        val priors = List(tuning.minDaysForBanding) { 0 }

        fun contributions(saturated: Int): List<Contribution> = Feature.entries.mapIndexed { i, f ->
            Contribution(feature = f, z = 4.0, contribution = if (i < saturated) 1.0 else 0.0, effectiveWeight = f.weight, absentReason = null)
        }

        // Index 100 clears the p90 of every prior, so only the multi-feature rule can hold it back.
        assertEquals(Band.ELEVATED, HeadacheSignals.band(index = 100, priorIndices = priors, contributions = contributions(saturated = 1)), "one feature at 1.0 can never flag")
        assertEquals(Band.ELEVATED, HeadacheSignals.band(index = 100, priorIndices = priors, contributions = contributions(saturated = 2)), "two is still not enough")
        assertEquals(Band.FLAGGED, HeadacheSignals.band(index = 100, priorIndices = priors, contributions = contributions(saturated = 3)))
    }

    // Interruption

    @Test
    fun twentyFourHourGapYieldsInterrupted() { // HeadacheSignalsTests.swift:561 (test24HourGapYieldsInterrupted)
        val tuning = Tuning()
        val gap = now.minusSeconds((tuning.dataGapHours * 3600).toLong())
        assertEquals(Verdict.Interrupted(since = gap), HeadacheSignals.assess(input(lastRingDataAt = gap)), "exactly 24 h of silence already counts")

        val longer = now.minusSeconds(30 * 3600)
        assertEquals(Verdict.Interrupted(since = longer), HeadacheSignals.assess(input(lastRingDataAt = longer)))

        // Never worn at all: still interrupted, and honest about having no `since`.
        val never = input().copy(lastRingDataAt = null)
        assertEquals(Verdict.Interrupted(since = null), HeadacheSignals.assess(never))

        // Just inside the window a normal day is still assessed — the gate must not swallow it.
        val fresh = now.minusSeconds(23 * 3600)
        assertNotNull(assessment(HeadacheSignals.assess(input(lastRingDataAt = fresh))))
    }

    // Suppression

    /** Suppression withholds the notification candidate, not the score. */
    @Test
    fun feverSuppressesCandidateNotScore() { // HeadacheSignalsTests.swift:585
        val plain = assertNotNull(assessment(HeadacheSignals.assess(input(effToday = 70.0))))
        val fevered = assertNotNull(assessment(HeadacheSignals.assess(input(effToday = 70.0, fever = true))))

        assertNull(plain.suppressedBy)
        assertEquals(15, plain.index, "round(100 · 0.18 / 1.20) with every feature present")
        assertEquals(HeadacheSignals.Suppression.FEVER, fevered.suppressedBy)
        assertEquals(plain.index, fevered.index)
        assertEquals(plain.band, fevered.band)
        assertEquals(plain.contributions, fevered.contributions)
        assertClose(plain.coverageFraction, fevered.coverageFraction, 1e-9)

        val logged = assertNotNull(assessment(HeadacheSignals.assess(input(effToday = 70.0, alreadyLogged = true))))
        assertEquals(HeadacheSignals.Suppression.HEADACHE_ALREADY_LOGGED, logged.suppressedBy)
        assertEquals(plain.index, logged.index)

        // Fever wins: two interrupts for one physiological event devalues both.
        val both = assertNotNull(assessment(HeadacheSignals.assess(input(effToday = 70.0, fever = true, alreadyLogged = true))))
        assertEquals(HeadacheSignals.Suppression.FEVER, both.suppressedBy)
    }

    // Determinism

    /** A frozen row is written once and never recomputed, so two evaluations of the same day must agree. */
    @Test
    fun idempotent() { // HeadacheSignalsTests.swift:612
        val sparse = input(hrvToday = null, effToday = 70.0, truncated = true, priorIndices = (0 until 30).toList())
        for (d in listOf(input(), sparse, input(rhrToday = 100.0, peri = true, fever = true))) {
            assertEquals(HeadacheSignals.assess(d), HeadacheSignals.assess(d))
            assertEquals(HeadacheSignals.assess(d), HeadacheSignals.assess(d, tuning = Tuning()))
        }
    }

    /** Upstream's synthetic index generator (`HeadacheSignalsTests.swift:627-634`): a tiny deterministic LCG. */
    private class SeededUniform(seed: ULong) {
        private var state: ULong = seed

        fun next01(): Double {
            state = state * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL
            return (state shr 11).toDouble() * (1.0 / 9_007_199_254_740_992.0) // 2⁻⁵³
        }
    }
}
