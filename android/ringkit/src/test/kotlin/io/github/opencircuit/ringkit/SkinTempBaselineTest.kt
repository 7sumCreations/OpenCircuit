package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SkinTempBaseline.DeviationBand
import io.github.opencircuit.ringkit.SkinTempBaseline.NightlyTemp
import io.github.opencircuit.ringkit.SkinTempBaseline.NightlyVerdict
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SYNTHETIC-ONLY tests for the sleeping skin-temp baseline + nightly deviation (#69). No real health
 * values — controlled inputs with a known expected baseline/offset/band.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SkinTempBaselineTests.swift
 * (@ b1c2fdd), all 22 tests. Upstream reads the device's calendar and clock in three places, each
 * replaced by a named zone and a fixed instant:
 *  - the `night(daysAgo, c)` helper (`:8-11`) steps back whole calendar days from "now" in the device
 *    calendar. Here "now" is 02:30 on 9 March 2026 in America/New_York, the day after the spring-forward,
 *    so the night one day back falls on the skipped 02:30 and resolves to 03:30 (as Foundation's
 *    `date(byAdding: .day)` does) — the baseline reads only the ORDER of the nights, and a day-step that
 *    went wrong at a missing hour is the way that order could break.
 *  - `testNightlyMeanWindowed` (`:21`) and `testWindowedCoverageCountsOnlyInWindowSamples` (`:52`)
 *    start their windows at `Date()`. Here they start at 05:59 UTC on 1 November 2026, one minute
 *    before New York's fall-back; the windows are plain elapsed seconds, so the answer cannot depend on
 *    the instant, and a fixed one keeps it from depending on when the test runs.
 */
class SkinTempBaselineTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 3, 9, 2, 30, 0, 0, zone)
    private val fixedBase: Instant = Instant.parse("2026-11-01T05:59:00Z")

    private fun night(daysAgo: Int, c: Double): NightlyTemp = NightlyTemp(night = now.minusDays(daysAgo.toLong()).toInstant(), celsius = c)

    private fun Instant.adding(seconds: Double): Instant = plusMillis(Math.round(seconds * 1000))

    /** Swift's `stride(from:to:by:)` over doubles. */
    private fun stride(from: Double, to: Double, by: Double): List<Double> =
        generateSequence(0) { it + 1 }.map { from + it * by }.takeWhile { it < to }.toList()

    private fun sample(t: Instant, celsius: Double) = TemperatureSample(time = t, celsius = celsius)

    private fun assertMean(expected: Double, actual: Double?, message: String? = null) =
        assertEquals(expected, assertNotNull(actual, message), 1e-9, message)

    /**
     * The arithmetic, exercised with the coverage gate opened (`minSamples: 1`) so this test keeps
     * asserting what it always asserted: the mean itself. The gate has its own tests below.
     */
    @Test
    fun nightlyMean() { // :15-18
        assertNull(SkinTempBaseline.nightlyMean(emptyList(), minSamples = 1))
        assertMean(31.0, SkinTempBaseline.nightlyMean(listOf(30.0, 31.0, 32.0), minSamples = 1))
    }

    @Test
    fun nightlyMeanWindowed() { // :20-30
        val base = fixedBase
        val samples = listOf(
            sample(base, 30.0),
            sample(base.adding(60.0), 32.0),
            sample(base.adding(10_000.0), 99.0), // outside window
        )
        val window = DateInterval(base, base.adding(120.0))
        assertMean(31.0, SkinTempBaseline.nightlyMean(samples, window, minSamples = 1))
    }

    // Coverage gate — a barely-connected night is not comparable to a well-covered one

    @Test
    fun nightlyMeanRejectsAThinlyCoveredNight() { // :34-41
        // Three readings is what a night with one short connected stretch produces. Sleeping skin temp
        // follows a circadian curve, so three samples from one corner of it are not a night.
        assertNull(SkinTempBaseline.nightlyMean(listOf(30.0, 31.0, 32.0)), "below minNightlySamples → no nightly value at all")
        assertNull(SkinTempBaseline.nightlyMean(List(SkinTempBaseline.MIN_NIGHTLY_SAMPLES - 1) { 31.0 }))
    }

    @Test
    fun nightlyMeanAcceptsAtTheCoverageFloor() { // :43-47
        val n = SkinTempBaseline.MIN_NIGHTLY_SAMPLES
        val values = List(n) { 31.0 }
        assertMean(31.0, SkinTempBaseline.nightlyMean(values))
    }

    @Test
    fun windowedCoverageCountsOnlyInWindowSamples() { // :49-61
        // Enough samples overall, but only two land inside the sleep window → still nil. The gate must
        // judge the night, not the array it was sliced from.
        val base = fixedBase
        val inWindow = (0 until 2).map { sample(base.adding(it * 60.0), 31.0) }
        val outside = (0 until 20).map { sample(base.adding(10_000 + it * 60.0), 31.0) }
        val window = DateInterval(base, base.adding(300.0))
        assertNull(SkinTempBaseline.nightlyMean(inWindow + outside, window))
    }

    // The gate that actually matters — coverage of the night's curve

    private fun night(hours: Double = 10.0): DateInterval {
        val start = Instant.ofEpochSecond(1_700_000_000)
        return DateInterval.of(start, Duration.ofMillis(Math.round(hours * 3_600_000)))
    }

    private val DateInterval.seconds: Double get() = duration.toMillis() / 1000.0

    /**
     * A well-connected night: the real cadence measured off a tester's descriptor frames is ~1 reading
     * per 68 s, giving 47–69 per hour in every hour of a 10 h window. It must pass with a wide margin —
     * the gate exists to reject broken nights, not normal ones.
     */
    @Test
    fun aWellConnectedNightPasses() { // :73-82
        val w = night()
        val samples = stride(0.0, w.seconds, 68.0).map { sample(w.start.adding(it), 35.5) }
        assertTrue(samples.size > 500)
        assertEquals(1.0, SkinTempBaseline.coverage(samples, w), 1e-9)
        assertMean(35.5, SkinTempBaseline.nightlyMean(samples, w, minCoverage = SkinTempBaseline.CANDIDATE_NIGHTLY_COVERAGE))
    }

    /**
     * The REPORTED case, and the one a count floor cannot catch: the link held for two hours of ten.
     * Hundreds of readings — far past any sane count floor — but only one corner of the circadian
     * curve, so its mean is not comparable to a full night's.
     */
    @Test
    fun aPartiallyConnectedNightIsRejectedDespiteManyReadings() { // :87-107
        val w = night()
        val samples = stride(0.0, 2.0 * 3600, 68.0).map { sample(w.start.adding(it), 34.0) }
        assertTrue(samples.size > SkinTempBaseline.MIN_NIGHTLY_SAMPLES, "the count floor alone would let this through")
        assertEquals(0.2, SkinTempBaseline.coverage(samples, w), 1e-9)
        assertNull(SkinTempBaseline.nightlyMean(samples, w, minCoverage = SkinTempBaseline.CANDIDATE_NIGHTLY_COVERAGE))
        // …and with the SHIPPED default the same night is now WITHHELD too. This assertion is the
        // tripwire the old comment promised: it used to assert NotNil to pin the deliberate gate-off
        // ship-state, so turning the gate on had to be a visible change rather than a silent one. It was
        // turned on deliberately on 2026-09-24 against a 39-night coverage distribution (see
        // `SkinTempBaseline.MIN_NIGHTLY_COVERAGE`), and this is that flip.
        assertNull(SkinTempBaseline.nightlyMean(samples, w))
        // The literal, not CANDIDATE_NIGHTLY_COVERAGE — asserting one constant equals the other is a
        // tautology given the declaration, and would not catch an edit to the value itself.
        assertEquals(0.6, SkinTempBaseline.MIN_NIGHTLY_COVERAGE, 1e-9, "the coverage gate ships ON at 0.6; changing it needs a new measurement")
    }

    /**
     * 🟢 THE GEN 3 NIGHT THAT FORCED THE GATE ON (tester export, FR05.011, 2026-09-24).
     *
     * Shape measured from his export, reproduced here as TIMESTAMPS ONLY — which is all `coverage`
     * reads, and it keeps a real person's temperature readings out of the repo. A 12 h 19 m staged
     * window carrying 31 samples in 2 of its 13 hour buckets: 24 of them inside ONE 28-minute stretch,
     * the other 7 in a single later stretch after he had got up but before `inBedEnd`.
     *
     * The count floor waves this through — 31 is over `MIN_NIGHTLY_SAMPLES` — which is precisely why the
     * count floor is not the real gate. Unweighted, those samples published 31.39 °C against 33.87 °C
     * for its readings at or above 31 °C, and he reported it as a Gen 3 calibration fault.
     */
    @Test
    fun theClusteredGen3NightIsWithheld() { // :120-138
        val start = Instant.ofEpochSecond(1_700_000_000)
        val w = DateInterval.of(start, Duration.ofSeconds(12 * 3600L + 19 * 60 + 3))
        // 24 samples across 28 min, starting 2 h 19 m in (one hour bucket).
        val warm = stride(0.0, 24 * 73.0, 73.0).map { sample(start.adding(2 * 3600.0 + 19 * 60 + it), 34.2) }
        // 7 samples across 8 min, starting 12 h 09 m in (one much later bucket).
        val ambient = stride(0.0, 7 * 70.0, 70.0).map { sample(start.adding(12 * 3600.0 + 9 * 60 + it), 28.1) }
        val samples = warm + ambient
        assertEquals(31, samples.size)
        assertTrue(samples.size > SkinTempBaseline.MIN_NIGHTLY_SAMPLES, "the count floor alone would publish this night")
        assertEquals(2.0 / 13.0, SkinTempBaseline.coverage(samples, w), 1e-9)
        assertNull(SkinTempBaseline.nightlyMean(samples, w), "a night measured in two short corners must not publish a nightly mean")
    }

    /** A night with gaps but readings spread across most of it is still comparable. */
    @Test
    fun aSparseButWellSpreadNightPasses() { // :141-150
        val w = night()
        // One reading every 20 min → 30 readings, every hour represented.
        val samples = stride(0.0, w.seconds, 1200.0).map { sample(w.start.adding(it), 35.0) }
        assertEquals(1.0, SkinTempBaseline.coverage(samples, w), 1e-9)
        assertMean(35.0, SkinTempBaseline.nightlyMean(samples, w, minCoverage = SkinTempBaseline.CANDIDATE_NIGHTLY_COVERAGE))
    }

    /** `minCoverage: 0` is the kill-switch for the coverage half. */
    @Test
    fun coverageKillSwitch() { // :153-159
        val w = night()
        val samples = stride(0.0, 2.0 * 3600, 68.0).map { sample(w.start.adding(it), 34.0) }
        assertNotNull(SkinTempBaseline.nightlyMean(samples, w, minCoverage = 0.0))
    }

    @Test
    fun baselineNeedsMinimumHistory() { // :161-166
        assertNull(SkinTempBaseline.baseline(listOf(night(1, 30.0), night(2, 31.0))), "below minBaselineNights → no baseline")
        val three = listOf(night(1, 30.0), night(2, 31.0), night(3, 32.0))
        assertMean(31.0, SkinTempBaseline.baseline(three))
    }

    @Test
    fun baselineTrailingWindow() { // :168-172
        // 5 nights but a window of 3 → only the 3 most-recent count.
        val nights = listOf(night(5, 20.0), night(4, 20.0), night(3, 30.0), night(2, 31.0), night(1, 32.0))
        assertMean(31.0, SkinTempBaseline.baseline(nights, windowNights = 3))
    }

    @Test
    fun offsetSign() { // :174-177
        assertEquals(1.0, SkinTempBaseline.offset(tonight = 32.0, baseline = 31.0), 1e-9)
        assertEquals(-1.0, SkinTempBaseline.offset(tonight = 30.0, baseline = 31.0), 1e-9)
    }

    @Test
    fun deviationBand() { // :179-184
        assertEquals(DeviationBand.NORMAL, SkinTempBaseline.deviationBand(offset = 0.5))
        assertEquals(DeviationBand.ABNORMAL_RISE, SkinTempBaseline.deviationBand(offset = 1.5))
        assertEquals(DeviationBand.ABNORMAL_DROP, SkinTempBaseline.deviationBand(offset = -1.5))
        assertEquals(DeviationBand.NORMAL, SkinTempBaseline.deviationBand(offset = 1.0), "exactly ±1 °C is still normal")
    }

    @Test
    fun anomalyFlags() { // :186-203
        // +1.2 °C vs baseline → abnormalRise; +0.8 °C vs last night → fluctuationRise.
        val f = SkinTempBaseline.anomalyFlags(tonight = 32.2, baseline = 31.0, previousNight = 31.4)
        assertTrue(f.abnormalRise)
        assertFalse(f.abnormalDrop)
        assertTrue(f.fluctuationRise)
        assertFalse(f.fluctuationDrop)
        assertTrue(f.any)

        // A calm night within both bands → no flags.
        val calm = SkinTempBaseline.anomalyFlags(tonight = 31.1, baseline = 31.0, previousNight = 31.0)
        assertFalse(calm.any)

        // A sharp DROP vs last night even when baseline is unknown.
        val drop = SkinTempBaseline.anomalyFlags(tonight = 30.0, baseline = null, previousNight = 31.0)
        assertTrue(drop.fluctuationDrop)
        assertFalse(drop.abnormalDrop, "no baseline → no abnormal classification")
    }

    /**
     * Regression (user-reported, 2026-07-03): one artifact night (86 °F ≈ 30 °C — a cold object held
     * while asleep) must alert ONCE, on the artifact night. The next night — back at baseline, i.e.
     * normal — must NOT alert "rose sharply vs the previous night": tonight being ON the baseline means
     * the previous night was the outlier, and it already had its alert.
     */
    @Test
    fun artifactNightAlertsButRecoveryNightStaysQuiet() { // :210-227
        val baseline = 34.4 // ~93.9 °F habitual sleeping skin temp

        // Artifact night: far below baseline AND far below the previous (normal) night — both the
        // abnormal and fluctuation drops fire. This is the expected alert.
        val artifact = SkinTempBaseline.anomalyFlags(tonight = 30.0, baseline = baseline, previousNight = 34.5)
        assertTrue(artifact.abnormalDrop)
        assertTrue(artifact.fluctuationDrop)
        assertFalse(artifact.fluctuationRise)

        // Recovery night: dead on baseline (the 30-night mean barely moves from one outlier), but
        // +4.5 °C vs the artifact night. Previously flagged fluctuationRise ("rose sharply") — wrong;
        // the baseline gate must keep it quiet.
        val recovery = SkinTempBaseline.anomalyFlags(tonight = 34.5, baseline = 34.25, previousNight = 30.0)
        assertFalse(recovery.any, "a return to baseline is normal, not a sharp rise")
    }

    /**
     * The gate must NOT swallow a genuine rapid rise: +0.8 °C overnight that lands well above baseline
     * (but still inside the ±1 °C abnormal band) is exactly what the fluctuation flag exists to catch
     * early.
     */
    @Test
    fun genuineRapidRiseStillFlagged() { // :232-236
        val f = SkinTempBaseline.anomalyFlags(tonight = 35.2, baseline = 34.4, previousNight = 34.4)
        assertTrue(f.fluctuationRise, "+0.8 °C overnight and +0.8 °C over baseline → early-warning flag")
        assertFalse(f.abnormalRise, "still inside the ±1 °C band — fluctuation is the early signal")
    }

    @Test
    fun reportWithAndWithoutBaseline() { // :238-252
        val prior = listOf(night(1, 30.8), night(2, 31.0), night(3, 31.2)) // baseline 31.0
        val r = SkinTempBaseline.report(tonight = 32.5, priorNights = prior, previousNight = 31.0)
        assertMean(31.0, r.baselineC)
        assertMean(1.5, r.offsetC)
        assertEquals(DeviationBand.ABNORMAL_RISE, r.band)
        assertTrue(r.flags.abnormalRise)

        // Too little history → nightly value present, but no baseline/offset/band.
        val thin = SkinTempBaseline.report(tonight = 32.5, priorNights = listOf(night(1, 31.0)))
        assertEquals(32.5, thin.nightlyC, 1e-9)
        assertNull(thin.baselineC)
        assertNull(thin.offsetC)
        assertNull(thin.band)
    }

    // The verdict — "didn't look" vs "looked and rejected"

    /**
     * The distinction `nightlyMean`'s `Double?` cannot carry, and which the store needs in order to
     * clear a stale stored mean instead of preserving it forever.
     */
    @Test
    fun verdictSeparatesNotMeasuredFromRejected() { // :258-283
        val w = night()
        // Too few readings to judge at all -> preserve whatever is stored.
        val thin = stride(0.0, 5 * 600.0, 600.0).map { sample(w.start.adding(it), 34.0) }
        assertTrue(thin.size < SkinTempBaseline.MIN_NIGHTLY_SAMPLES)
        assertEquals(NightlyVerdict.NotMeasured, SkinTempBaseline.nightlyVerdict(thin, w))

        // Enough readings to judge, all in one corner -> a real verdict, so the store must CLEAR.
        val clustered = stride(0.0, 2.0 * 3600, 68.0).map { sample(w.start.adding(it), 34.0) }
        assertTrue(clustered.size >= SkinTempBaseline.MIN_NIGHTLY_SAMPLES)
        val verdict = SkinTempBaseline.nightlyVerdict(clustered, w)
        val rejected = verdict as? NightlyVerdict.RejectedCoverage ?: fail("a well-sampled but clustered night is a rejection, not an absence")
        assertEquals(0.2, rejected.coverage, 1e-9)

        // A good night publishes.
        val good = stride(0.0, w.seconds, 600.0).map { sample(w.start.adding(it), 35.0) }
        assertEquals(NightlyVerdict.Published(35.0), SkinTempBaseline.nightlyVerdict(good, w))
    }

    /**
     * The Gen 3 night is a REJECTION, not an absence — this is what lets the stale 31.39 °C be cleared
     * on a re-stage rather than kept because "nothing was computed".
     */
    @Test
    fun theClusteredGen3NightIsRejectedNotMerelyUncomputed() { // :287-301
        val start = Instant.ofEpochSecond(1_700_000_000)
        val w = DateInterval.of(start, Duration.ofSeconds(12 * 3600L + 19 * 60 + 3))
        val warm = stride(0.0, 24 * 73.0, 73.0).map { sample(start.adding(2 * 3600.0 + 19 * 60 + it), 34.2) }
        val ambient = stride(0.0, 7 * 70.0, 70.0).map { sample(start.adding(12 * 3600.0 + 9 * 60 + it), 28.1) }
        val verdict = SkinTempBaseline.nightlyVerdict(warm + ambient, w)
        val rejected = verdict as? NightlyVerdict.RejectedCoverage ?: fail("31 readings is plenty to judge this night by — it must REJECT, not abstain")
        assertEquals(2.0 / 13.0, rejected.coverage, 1e-9)
    }

    /**
     * `nightlyMean` is `nightlyVerdict` keeping only the published case; the two must never disagree on
     * whether a night publishes, since one delegates to the other.
     */
    @Test
    fun verdictAndMeanAgreeOnEveryShape() { // :305-338
        val w = night()
        val shapes: List<List<TemperatureSample>> = listOf(
            emptyList(),
            stride(0.0, 5 * 600.0, 600.0).map { sample(w.start.adding(it), 34.0) },
            stride(0.0, 2.0 * 3600, 68.0).map { sample(w.start.adding(it), 34.0) },
            stride(0.0, w.seconds, 600.0).map { sample(w.start.adding(it), 35.0) },
            stride(0.0, w.seconds, 68.0).map { sample(w.start.adding(it), 35.5) },
        )
        // Also exercise the documented escape hatches, since the delegation reordered the two checks
        // (count now runs before coverage) and the kill-switch is the one path that skips coverage.
        val knobs: List<Pair<Int, Double>> = listOf(
            SkinTempBaseline.MIN_NIGHTLY_SAMPLES to SkinTempBaseline.MIN_NIGHTLY_COVERAGE,
            SkinTempBaseline.MIN_NIGHTLY_SAMPLES to 0.0, // coverage kill-switch
            1 to SkinTempBaseline.MIN_NIGHTLY_COVERAGE, // count kill-switch
            1 to 0.0, // both off
            500 to SkinTempBaseline.MIN_NIGHTLY_COVERAGE,
        )
        for (samples in shapes) {
            for ((minSamples, minCoverage) in knobs) {
                val mean = SkinTempBaseline.nightlyMean(samples, w, minSamples = minSamples, minCoverage = minCoverage)
                when (val v = SkinTempBaseline.nightlyVerdict(samples, w, minSamples = minSamples, minCoverage = minCoverage)) {
                    is NightlyVerdict.Published -> assertEquals(v.celsius, mean, "verdict published ${v.celsius} but nightlyMean said $mean")
                    NightlyVerdict.NotMeasured, is NightlyVerdict.RejectedCoverage -> assertNull(mean, "verdict withheld but nightlyMean published $mean")
                }
            }
        }
    }

    /**
     * 🟢 THE PROPERTY THAT MAKES THE CLEARING PATH SAFE, pinned because it rests on the ORDER of the two
     * checks. A pass with no in-window readings must be NotMeasured, never RejectedCoverage — a night
     * with zero readings has coverage 0.0 and would fail a coverage-first gate, so if the checks were
     * reversed every empty re-stage would clear a stored skin temperature. Count-before-coverage is
     * load-bearing, not cosmetic.
     */
    @Test
    fun anEmptyPassAbstainsRatherThanRejecting() { // :345-353
        val w = night()
        assertEquals(NightlyVerdict.NotMeasured, SkinTempBaseline.nightlyVerdict(emptyList(), w))
        // Readings exist but all OUTSIDE the window — same requirement, since they are filtered first.
        val outside = listOf(sample(w.end.adding(3600.0), 35.0))
        assertEquals(NightlyVerdict.NotMeasured, SkinTempBaseline.nightlyVerdict(outside, w))
        assertEquals(0.0, SkinTempBaseline.coverage(emptyList(), w), 1e-9, "an empty night's coverage IS 0 — which is why the count must be checked first")
    }
}
