package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.HeadacheEvaluation.Metrics
import io.github.opencircuit.ringkit.HeadacheEvaluation.ScoredDay
import io.github.opencircuit.ringkit.HeadacheEvaluation.Status
import io.github.opencircuit.ringkit.HeadacheSignals.AbsentReason
import io.github.opencircuit.ringkit.HeadacheSignals.Band
import io.github.opencircuit.ringkit.HeadacheSignals.Contribution
import io.github.opencircuit.ringkit.HeadacheSignals.DayInput
import io.github.opencircuit.ringkit.HeadacheSignals.Feature
import io.github.opencircuit.ringkit.HeadacheSignals.Series
import io.github.opencircuit.ringkit.HeadacheSignals.Tuning
import io.github.opencircuit.ringkit.HeadacheSignals.Verdict
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the headache index and its evaluation: what the upstream
 * vectors never feed in. Each night's readings come from the ring and are stored, the frozen rows are
 * stored values, and the tuning and the statistics' counts are caller values, so here readings arrive
 * NaN or infinite, rows arrive duplicated, far in the future or after the clock, counts arrive at the
 * ends of `Int`, and the tuning arrives NaN, negative or huge. Kept out of the upstream-port classes
 * so their counts stay exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class HeadacheHazardTest {

    // The upstream test's fixture: flat baselines, so each z is `delta / noiseFloor`.
    private val day: Instant = Instant.ofEpochSecond(1_753_660_800)
    private val now: Instant = day.plusSeconds(8 * 3600)
    private fun flat(v: Double, n: Int = 14) = List(n) { v }

    private fun input(
        rhr: Double? = 60.0,
        hrv: Double? = 50.0,
        eff: Double? = 90.0,
        frag: Double? = 40.0,
        dur: Double? = 420.0,
        temp: Double? = 0.0,
        bed: Int? = 23 * 60,
        prev: Double? = 70.0,
        prev2: Double? = 70.0,
        dayHRPriorNights: Int = 14,
        peri: Boolean? = false,
        last: Instant? = now.minusSeconds(3600),
    ): DayInput {
        fun s(today: Double?, base: Double) = today?.let { Series(today = it, prior = flat(base)) }
        return DayInput(
            day = day, now = now, lastRingDataAt = last,
            restingHR = s(rhr, 60.0), hrvSDNN = s(hrv, 50.0), sleepEfficiencyPct = s(eff, 90.0),
            sleepFragmentationMin = s(frag, 40.0), sleepDurationMin = s(dur, 420.0),
            skinTempOffsetC = temp, inBedStartMinutes = bed, priorInBedStartMinutes = List(14) { 23 * 60 },
            dayHRPrevious = prev, dayHRTwoDaysAgo = prev2, dayHRPrior = flat(70.0, dayHRPriorNights),
            isPerimenstrual = peri,
        )
    }

    private fun scored(v: Verdict): HeadacheSignals.Assessment = assertIs<Verdict.Scored>(v, "expected a score, got $v").assessment
    private fun HeadacheSignals.Assessment.of(f: Feature): Contribution = contributions.single { it.feature == f }

    private val unreadable = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

    // Unreadable readings are missing readings

    @Test
    fun anUnreadableTodayIsMissingNotNormal() {
        // Upstream hands a NaN or infinite today to RobustBaseline.z, which reads it 0: the feature is
        // PRESENT with z 0 — an unreadable night scored as a normal one (measured: every case below
        // gives a present z of 0). Here the series is treated as missing, exactly as a nil series:
        // absent, "no data this day", out of the denominator — RobustBaseline.z is never asked.
        val cases: List<Pair<Feature, (Double) -> DayInput>> = listOf(
            Feature.RESTING_HR_DEVIATION to { x -> input(rhr = x) },
            Feature.HRV_DEVIATION to { x -> input(hrv = x) },
            Feature.SLEEP_EFFICIENCY_DROP to { x -> input(eff = x) },
            Feature.SLEEP_FRAGMENTATION to { x -> input(frag = x) },
            Feature.SLEEP_DURATION_DEVIATION to { x -> input(dur = x) },
        )
        for ((feature, make) in cases) {
            for (x in unreadable) {
                val c = scored(HeadacheSignals.assess(make(x))).of(feature)
                assertNull(c.z, "$feature today $x: no z")
                assertNull(c.contribution, "$feature today $x: absent, never 0")
                assertEquals(AbsentReason.NO_DATA_THIS_DAY, c.absentReason, "$feature today $x")
                assertEquals(0.0, c.effectiveWeight, "$feature today $x")
            }
        }
        // The missing feature leaves the denominator: an unreadable HRV scores as a nil HRV does.
        assertEquals(scored(HeadacheSignals.assess(input(hrv = null, eff = 70.0))), scored(HeadacheSignals.assess(input(hrv = Double.NaN, eff = 70.0))))
        // Even with too little history for a baseline the reading is missing first, as a nil series is.
        val short = input().copy(hrvSDNN = Series(today = Double.NaN, prior = flat(50.0, RobustBaseline.MIN_BASELINE_DAYS - 1)))
        assertEquals(AbsentReason.NO_DATA_THIS_DAY, scored(HeadacheSignals.assess(short)).of(Feature.HRV_DEVIATION).absentReason)

        // Five unreadable readings and nothing else: upstream scores the day (index 0, typical, 5 ring
        // features, coverage 0.66); here nothing was measured, so there is no score.
        val nothing = input(rhr = Double.NaN, hrv = Double.NaN, eff = Double.NaN, frag = Double.NaN, dur = Double.NaN, temp = null, bed = null, prev = null, peri = null)
        val v = assertIs<Verdict.InsufficientData>(HeadacheSignals.assess(nothing))
        for (f in listOf(Feature.RESTING_HR_DEVIATION, Feature.HRV_DEVIATION, Feature.SLEEP_EFFICIENCY_DROP, Feature.SLEEP_FRAGMENTATION, Feature.SLEEP_DURATION_DEVIATION)) {
            assertEquals(AbsentReason.NO_DATA_THIS_DAY, v.missing[f], "$f")
        }

        // Finite readings take upstream's path, however extreme: 1e308 against a flat 60 is z clamped to 4.
        assertEquals(RobustBaseline.Z_CLAMP, scored(HeadacheSignals.assess(input(rhr = 1e308))).of(Feature.RESTING_HR_DEVIATION).z)
    }

    @Test
    fun anUnreadableSkinTempOffsetIsMissing() {
        // Upstream: a NaN offset traps ("Double value cannot be converted to Int because it is either
        // infinite or NaN", exit 133 — HeadacheSignals.swift:464 via :377); ±∞ saturates the feature
        // (contribution 1, z ±∞, index 7 on an otherwise ordinary day). Here each is missing, as a nil
        // offset is.
        for (x in unreadable) {
            val a = scored(HeadacheSignals.assess(input(temp = x)))
            val c = a.of(Feature.SKIN_TEMP_DEVIATION)
            assertNull(c.z, "offset $x")
            assertNull(c.contribution, "offset $x")
            assertEquals(AbsentReason.NO_DATA_THIS_DAY, c.absentReason, "offset $x")
            assertEquals(0, a.index, "offset $x: nothing else moved")
            assertEquals(scored(HeadacheSignals.assess(input(temp = null))), a, "offset $x is a missing offset")
        }
        // A finite offset, however large, saturates as upstream (measured: 1e308 → contribution 1, index 7).
        val huge = scored(HeadacheSignals.assess(input(temp = 1e308)))
        assertEquals(1.0, huge.of(Feature.SKIN_TEMP_DEVIATION).contribution)
        assertEquals(7, huge.index)
    }

    @Test
    fun anUnreadableLetdownDayIsMissing() {
        // Upstream reads a NaN D−1 as z 0, so a D−2 of 80 against a flat 70 becomes a let-down of z 2
        // (contribution 0.667, index 10); an infinite D−2 reads 0 (no let-down, present). Here either
        // unreadable day is a missing day, with upstream's own reason for a missing one: "no data this
        // day" when the daytime baseline exists, "no baseline" when it does not.
        for (x in unreadable) {
            for (make in listOf({ input(prev = x, prev2 = 80.0) }, { input(prev = 70.0, prev2 = x) })) {
                val a = scored(HeadacheSignals.assess(make()))
                val c = a.of(Feature.AROUSAL_LETDOWN)
                assertNull(c.contribution, "day HR $x")
                assertEquals(AbsentReason.NO_DATA_THIS_DAY, c.absentReason, "day HR $x")
                assertEquals(0, a.index, "day HR $x")
            }
            val noBaseline = scored(HeadacheSignals.assess(input(prev = x, dayHRPriorNights = 3)))
            assertEquals(AbsentReason.NO_BASELINE, noBaseline.of(Feature.AROUSAL_LETDOWN).absentReason, "day HR $x, 3 prior days")
        }
        // Readable days take upstream's path: 70 then 80 is a fall of 2 z.
        assertEquals(2.0, scored(HeadacheSignals.assess(input(prev = 70.0, prev2 = 80.0))).of(Feature.AROUSAL_LETDOWN).z)
    }

    // Crash bounds

    @Test
    fun aNonFiniteIndexIsNoScore() {
        // Upstream traps converting a NaN index to Int (measured with onsetZ NaN: every ramp is NaN).
        // Kotlin would silently read NaN as 0 — "nothing unusual". Here a non-finite index is no score:
        // insufficient data, naming what was absent.
        val hostile = listOf(
            Tuning(onsetZ = Double.NaN), Tuning(saturationZ = Double.NaN), Tuning(tempOnsetC = Double.NaN),
            Tuning(truncatedSleepQuality = Double.POSITIVE_INFINITY),
        )
        for (t in hostile) {
            val v = HeadacheSignals.assess(input(eff = 70.0, temp = 0.6).copy(sleepLikelyTruncated = true), tuning = t)
            assertIs<Verdict.InsufficientData>(v, "$t")
        }
        // A finite index takes upstream's path (round half away from zero): 0.18 / 1.20 → 15.
        assertEquals(15, scored(HeadacheSignals.assess(input(eff = 70.0))).index)
    }

    @Test
    fun percentileClampsItsFraction() {
        val sorted = listOf(1.0, 2.0, 3.0)
        // Upstream traps on all three: NaN at Int(NaN) (HeadacheSignals.swift:575), 1.5 and -0.5 with
        // "Index out of range".
        assertEquals(3.0, HeadacheSignals.percentile(sorted, 1.5), "above 1 reads as 1")
        assertEquals(3.0, HeadacheSignals.percentile(sorted, Double.POSITIVE_INFINITY))
        assertEquals(1.0, HeadacheSignals.percentile(sorted, -0.5), "below 0 reads as 0")
        assertEquals(1.0, HeadacheSignals.percentile(sorted, Double.NEGATIVE_INFINITY))
        assertTrue(HeadacheSignals.percentile(sorted, Double.NaN).isNaN(), "a NaN fraction is a threshold no index reaches")
        // ...so a NaN flagged percentile never flags, and the elevated one still bands.
        assertEquals(Band.ELEVATED, HeadacheSignals.band(index = 100, priorIndices = List(30) { it }, tuning = Tuning(flaggedPercentile = Double.NaN)))
        assertEquals(Band.TYPICAL, HeadacheSignals.band(index = 100, priorIndices = List(30) { it }, tuning = Tuning(flaggedPercentile = Double.NaN, elevatedPercentile = Double.NaN)))
        // In range, as upstream (measured): empty → +∞, one value → that value, 0 and 1 → the ends.
        assertEquals(Double.POSITIVE_INFINITY, HeadacheSignals.percentile(emptyList(), 0.5))
        assertEquals(3.0, HeadacheSignals.percentile(listOf(3.0), 7.0))
        assertEquals(4.0, HeadacheSignals.percentile(listOf(1.0, 2.0, 3.0, 4.0), 1.0))
        assertEquals(1.0, HeadacheSignals.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.0))
        assertEquals(2.5, HeadacheSignals.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.5))
    }

    @Test
    fun aNegativeBandWindowTakesNoDays() {
        // Upstream traps ("Can't take a suffix of negative length from a collection"). Here a negative
        // window is window 0: no prior days, so no band.
        for (w in listOf(-1, -60, Int.MIN_VALUE)) {
            assertEquals(Band.TYPICAL, HeadacheSignals.band(index = 100, priorIndices = List(30) { it }, tuning = Tuning(bandWindowDays = w)), "window $w")
            assertEquals(Band.TYPICAL, HeadacheSignals.band(index = 100, priorIndices = List(30) { it }, tuning = Tuning(bandWindowDays = w, minDaysForBanding = 0)), "window $w, no minimum")
        }
        assertEquals(Band.TYPICAL, HeadacheSignals.band(index = 100, priorIndices = List(30) { it }, tuning = Tuning(bandWindowDays = 0)))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun aNegativeCapPassCountCapsNothing() {
        // Upstream traps on a negative pass count ("Range requires lowerBound <= upperBound"). Here it
        // caps nothing, as 0 passes do (measured upstream with 0: index 32 on a day of efficiency and cycle).
        val day = input(eff = 70.0, peri = true)
        val none = scored(HeadacheSignals.assess(day, tuning = Tuning(maxCapPasses = 0)))
        assertEquals(32, none.index)
        for (p in listOf(-1, -3, Int.MIN_VALUE)) assertEquals(none, scored(HeadacheSignals.assess(day, tuning = Tuning(maxCapPasses = p))), "passes $p")
        assertEquals(32, scored(HeadacheSignals.assess(day, tuning = Tuning(maxCapPasses = Int.MAX_VALUE))).index)

        // A huge pass count. With only HRV and resting HR present each is over the other's share, and
        // the pair shrinks until both weights reach the subnormal 1e-323 (bits 0x2) and stay there,
        // still over the share: upstream then repeats that no-op pass until the count runs out
        // (measured: 10 000 000 passes take 15 s, so Int.max never ends). Here the loop stops at the
        // first pass that changes nothing — the same answer every pass count gives upstream.
        val two = Feature.entries.map { f ->
            val on = f == Feature.HRV_DEVIATION || f == Feature.RESTING_HR_DEVIATION
            Contribution(f, if (on) 1.0 else null, if (on) 1.0 else null, if (on) f.weight else 0.0, null)
        }
        fun weights(passes: Int) = HeadacheSignals.applySingleFeatureCap(two, Tuning(maxCapPasses = passes))
            .filter { it.effectiveWeight != 0.0 }.map { it.feature to it.effectiveWeight.toRawBits() }
        val settled = listOf(Feature.HRV_DEVIATION to 2L, Feature.RESTING_HR_DEVIATION to 2L)
        assertEquals(settled, weights(Int.MAX_VALUE))
        assertEquals(settled, weights(100_000), "measured upstream at 100 000 and 10 000 000 passes")
        // The shipped 3 passes, as upstream (measured bits).
        assertEquals(listOf(Feature.HRV_DEVIATION to 0x3f9661b3a9ec2763L, Feature.RESTING_HR_DEVIATION to 0x3fa4c86ff936b6ddL), weights(3))
    }

    @Test
    fun hostileTuningAndEdgesFollowUpstream() {
        val priors = List(30) { it }
        // Indices outside 0…100 (measured): negative → typical, 1000 and Int.max → flagged, Int.min → typical.
        assertEquals(Band.TYPICAL, HeadacheSignals.band(index = -5, priorIndices = List(30) { 0 }))
        assertEquals(Band.FLAGGED, HeadacheSignals.band(index = 1000, priorIndices = priors))
        assertEquals(Band.FLAGGED, HeadacheSignals.band(index = Int.MAX_VALUE, priorIndices = priors))
        assertEquals(Band.TYPICAL, HeadacheSignals.band(index = Int.MIN_VALUE, priorIndices = priors))
        assertEquals(Band.TYPICAL, HeadacheSignals.band(index = 50, priorIndices = emptyList(), tuning = Tuning(minDaysForBanding = 0)))
        // The clock: a NaN gap never interrupts (10 days of silence still scores); a last reading in the
        // future is not silence; a zero gap interrupts even at the same instant.
        scored(HeadacheSignals.assess(input(last = now.minusSeconds(864_000)), tuning = Tuning(dataGapHours = Double.NaN)))
        scored(HeadacheSignals.assess(input(last = now.plusSeconds(86_400L * 365))))
        assertEquals(Verdict.Interrupted(now), HeadacheSignals.assess(input(last = now), tuning = Tuning(dataGapHours = 0.0)))
        // A last reading at either end of Instant's range never throws (a Swift Date has no ends).
        scored(HeadacheSignals.assess(input(last = Instant.MAX)))
        assertEquals(Verdict.Interrupted(Instant.MIN), HeadacheSignals.assess(input(last = Instant.MIN)))
        // Kept as upstream (a hostile code constant, measured): a share of 0 zeroes the capped weights
        // (index 0); a negative share makes weights negative (index -168); a quality of 0 with a
        // truncated night and only the sleep features measured leaves too few; a negative minimum still
        // needs an anchor.
        assertEquals(0, scored(HeadacheSignals.assess(input(eff = 70.0), tuning = Tuning(maxSingleFeatureShare = 0.0))).index)
        assertEquals(-168, scored(HeadacheSignals.assess(input(eff = 70.0), tuning = Tuning(maxSingleFeatureShare = -0.5))).index)
        val truncated = input(rhr = null, hrv = null, eff = 70.0, temp = null, bed = null, prev = null, peri = null).copy(sleepLikelyTruncated = true)
        assertIs<Verdict.InsufficientData>(HeadacheSignals.assess(truncated, tuning = Tuning(truncatedSleepQuality = 0.0)))
        val nothing = input(rhr = null, hrv = null, eff = null, frag = null, dur = null, temp = null, bed = null, prev = null, peri = null)
        assertEquals(8, assertIs<Verdict.InsufficientData>(HeadacheSignals.assess(nothing, tuning = Tuning(minRingFeaturesForScore = -3))).missing.size)
    }

    // The evaluation

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun theTailIsBoundedInMemory() {
        // Upstream allocates one double per row before answering: 4 000 000 000 rows ran to a 32 GB
        // resident set, Int.max rows trap (total + 1). Above 100 000 rows (about 270 years of daily
        // rows) the answer here is nil — upstream's own "no test to run" — so a detector can never be
        // called working on it.
        assertNull(HeadacheEvaluation.hypergeometricUpperTail(observed = 1, flagged = 2, positives = 2, total = Int.MAX_VALUE))
        assertNull(HeadacheEvaluation.hypergeometricUpperTail(observed = 30_000, flagged = 100_000, positives = 200_000, total = 2_000_000_000))
        assertNull(HeadacheEvaluation.hypergeometricUpperTail(observed = 3, flagged = 5, positives = 4, total = HeadacheEvaluation.MAX_TAIL_ROWS + 1))
        assertEquals(100_000, HeadacheEvaluation.MAX_TAIL_ROWS)
        // At the bound it still computes.
        val atBound = HeadacheEvaluation.hypergeometricUpperTail(observed = 3_000, flagged = 10_000, positives = 20_000, total = HeadacheEvaluation.MAX_TAIL_ROWS)
        assertTrue(atBound != null && atBound in 0.0..1.0, "$atBound")
        // Answers that need no table stay upstream's at any size, and counts at Int's end do not wrap
        // (Swift's Int is 64-bit): every flag a headache, so at least Int.MAX hits — certain.
        assertEquals(1.0, HeadacheEvaluation.hypergeometricUpperTail(observed = 0, flagged = Int.MAX_VALUE, positives = Int.MAX_VALUE, total = Int.MAX_VALUE))
        assertEquals(0.0, HeadacheEvaluation.hypergeometricUpperTail(observed = 3, flagged = 2, positives = 2, total = Int.MAX_VALUE))
        // The guards, as upstream (measured): a negative count → nil, more hits than possible → 0, no
        // rows → nil; the hand-computed 66 / 252 within the bound.
        assertNull(HeadacheEvaluation.hypergeometricUpperTail(observed = -1, flagged = 5, positives = 4, total = 10))
        assertEquals(0.0, HeadacheEvaluation.hypergeometricUpperTail(observed = 11, flagged = 5, positives = 4, total = 10))
        assertNull(HeadacheEvaluation.hypergeometricUpperTail(observed = 1, flagged = -5, positives = 4, total = 10))
        assertNull(HeadacheEvaluation.hypergeometricUpperTail(observed = 1, flagged = 5, positives = 4, total = 0))
        assertEquals(66.0 / 252.0, HeadacheEvaluation.hypergeometricUpperTail(observed = 3, flagged = 5, positives = 4, total = 10)!!, 1e-12)
    }

    @Test
    fun statisticsEdgesFollowUpstream() {
        // Measured upstream.
        assertNull(HeadacheEvaluation.wilsonUpperBound(successes = 0, trials = 0, z = 1.645))
        assertNull(HeadacheEvaluation.wilsonUpperBound(successes = 3, trials = 2, z = 1.645))
        assertTrue(HeadacheEvaluation.wilsonUpperBound(successes = 1, trials = 10, z = Double.NaN)!!.isNaN())
        assertTrue(HeadacheEvaluation.wilsonUpperBound(successes = 1, trials = 10, z = Double.POSITIVE_INFINITY)!!.isNaN())
        assertEquals(0.022632329840006293, HeadacheEvaluation.wilsonUpperBound(successes = 1, trials = 10, z = -1.645))
        assertEquals(1.0, HeadacheEvaluation.wilsonUpperBound(successes = Int.MAX_VALUE, trials = Int.MAX_VALUE, z = 1.645))
        assertNull(HeadacheEvaluation.hanleyMcNeilSE(auc = Double.NaN, nPos = 3, nNeg = 3))
        assertNull(HeadacheEvaluation.hanleyMcNeilSE(auc = 2.0, nPos = 3, nNeg = 3))
        assertNull(HeadacheEvaluation.hanleyMcNeilSE(auc = 0.5, nPos = 0, nNeg = 3))
        assertNull(HeadacheEvaluation.hanleyMcNeilSE(auc = -1.0, nPos = 3, nNeg = 3))
        assertEquals(0.0, HeadacheEvaluation.hanleyMcNeilSE(auc = 1.0, nPos = 3, nNeg = 3))
        assertTrue(HeadacheEvaluation.hanleyMcNeilSE(auc = 0.5, nPos = Int.MAX_VALUE, nNeg = Int.MAX_VALUE)!! > 0)
    }

    @Test
    fun midranksFollowSwiftsSortOnSignedZeroAndNaN() {
        // Swift's sort is driven by `<` and its ties by `==`, so -0.0 ties with 0.0 (Kotlin's own
        // compareTo puts -0.0 first), and NaN, which `<` never orders, lands where Swift's algorithm
        // leaves it. Measured upstream.
        assertEquals(emptyList(), HeadacheEvaluation.midranks(emptyList()))
        assertEquals(listOf(2.0, 2.0, 2.0), HeadacheEvaluation.midranks(listOf(0.0, -0.0, 0.0)))
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), HeadacheEvaluation.midranks(listOf(Double.NaN, 1.0, Double.NaN, 0.0)))
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 7.0, 6.0, 5.0), HeadacheEvaluation.midranks(listOf(2.0, Double.NaN, 1.0, Double.NaN, 2.0, 1.0, 0.0)))
        val longer = List(45) { i -> if (i % 3 == 0) Double.NaN else (i % 5).toDouble() } // past Swift's 20-value insertion sort
        val expected = (1..45).map { it.toDouble() }.toMutableList().apply {
            this[4] = 6.0; this[5] = 5.0; this[19] = 21.0; this[20] = 20.0; this[34] = 36.0; this[35] = 35.0
        }
        assertEquals(expected, HeadacheEvaluation.midranks(longer))
        assertEquals(0.25, HeadacheEvaluation.auc(listOf(Double.NaN, 1.0), listOf(0.0, Double.NaN)))
        assertEquals(1.0, HeadacheEvaluation.auc(listOf(Double.POSITIVE_INFINITY), listOf(Double.NEGATIVE_INFINITY)))
    }

    private val base: Instant = Instant.ofEpochSecond(1_600_000_000)
    private fun row(i: Int, index: Int = 50, band: Band = Band.TYPICAL, onset: Instant? = null) =
        ScoredDay(day = base.plusSeconds(86_400L * i), computedAt = base.plusSeconds(86_400L * i + 36_000), index = index, band = band, headacheOnset = onset)
    private val rows = List(30) { row(it, band = if (it % 5 == 0) Band.FLAGGED else Band.TYPICAL) }
    private val evalNow: Instant = base.plusSeconds(40 * 86_400L)

    /** Foundation's `Date.distantPast` and `.distantFuture`. */
    private val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")
    private val distantFuture: Instant = Instant.parse("4001-01-01T00:00:00Z")

    @Test
    fun storedRowsDuplicatedFarFutureOrAfterTheClockFollowUpstream() {
        // Rows are stored values. Measured upstream: a duplicated row counts twice; a row dated far in
        // the future but frozen before now counts, and stretches the alert span; rows frozen after now
        // count nowhere; the order of the rows changes nothing (upstream's own determinism test).
        val dup = HeadacheEvaluation.metrics(rows + rows, now = evalNow)
        assertEquals(listOf(60, 0, 12, 0), listOf(dup.scoredDays, dup.labelledDays, dup.flaggedDays, dup.truePositives))
        assertEquals(0.0, dup.baseRate)
        assertEquals(0.0, dup.precision)
        assertNull(dup.recall)
        assertNull(dup.pValue)
        assertEquals(0.0, dup.alertsPerWeek)
        assertEquals(dup, HeadacheEvaluation.metrics((rows + rows).reversed(), now = evalNow))

        val farDay = ScoredDay(day = distantFuture, computedAt = base, index = 10, band = Band.TYPICAL, alerted = true)
        val far = HeadacheEvaluation.metrics(rows + farDay, now = evalNow)
        assertEquals(31, far.scoredDays)
        assertEquals(9.677999142805791e-06, far.alertsPerWeek)
        assertEquals(31, HeadacheEvaluation.frozenRowCount(rows + farDay, now = evalNow, tuning = HeadacheEvaluation.Tuning()))

        val before = HeadacheEvaluation.metrics(rows, now = base)
        assertEquals(0, before.scoredDays)
        assertNull(before.alertsPerWeek)
        assertEquals(Status.Building(21), HeadacheEvaluation.status(rows, now = base))
        assertEquals(Status.Building(21), HeadacheEvaluation.status(rows, now = distantPast))
        assertEquals(Status.Building(21), HeadacheEvaluation.status(rows, now = distantFuture))
        // The ends of Instant's range never throw (a Swift Date has no ends).
        assertEquals(Status.Building(21), HeadacheEvaluation.status(rows, now = Instant.MIN))
        assertEquals(Status.Building(21), HeadacheEvaluation.status(rows, now = Instant.MAX))
        val edge = ScoredDay(day = Instant.MAX, computedAt = Instant.MAX, index = 5, band = Band.TYPICAL)
        val atEnd = HeadacheEvaluation.metrics(listOf(edge), now = Instant.MAX)
        assertEquals(1, atEnd.scoredDays + atEnd.excludedUnresolved, "the row is either scored or still open, never lost")
    }

    @Test
    fun hostileEvaluationTuningFollowsUpstream() {
        // Measured upstream: a NaN outcome window leaves no row unresolved and makes no row positive;
        // an enormous window keeps every row and a negative one keeps none; a minimum of Int.min frozen
        // days is already met; an index at either end of Int is still ranked.
        val nan = HeadacheEvaluation.metrics(rows, now = evalNow, tuning = HeadacheEvaluation.Tuning(outcomeWindowHours = Double.NaN))
        assertEquals(listOf(30, 0, 6, 0), listOf(nan.scoredDays, nan.labelledDays, nan.flaggedDays, nan.excludedUnresolved))
        assertEquals(30, HeadacheEvaluation.metrics(rows, now = evalNow, tuning = HeadacheEvaluation.Tuning(evaluationWindowDays = Int.MAX_VALUE)).scoredDays)
        assertEquals(0, HeadacheEvaluation.metrics(rows, now = evalNow, tuning = HeadacheEvaluation.Tuning(evaluationWindowDays = -5)).scoredDays)
        assertIs<Status.Monitoring>(HeadacheEvaluation.status(rows, now = evalNow, tuning = HeadacheEvaluation.Tuning(minFrozenDaysForNotification = Int.MIN_VALUE)))
        val ends = HeadacheEvaluation.metrics(listOf(row(0, index = Int.MAX_VALUE, band = Band.FLAGGED, onset = base.plusSeconds(40_000)), row(1, index = Int.MIN_VALUE)), now = evalNow)
        assertEquals(
            Metrics(
                scoredDays = 2, labelledDays = 1, flaggedDays = 1, truePositives = 1, baseRate = 0.5, precision = 1.0, recall = 1.0,
                lift = 2.0, auc = 1.0, aucCILow = 1.0, aucCIHigh = 1.0, pValue = 0.5, alertsPerWeek = 0.0,
                excludedRestaged = 0, excludedInProgress = 0, excludedUnresolved = 0,
            ),
            ends,
        )
    }
}
