package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/WorkoutSessionTests.swift (@ b1c2fdd),
 * 42 of 42, each test named after upstream's with its line: zone classification, time-in-zone, session
 * aggregation. Zone boundaries from the APK (pp.txt:0x515c0): warmUp 50–60%, fatBurn 61–70%, aerobic
 * 71–80%, anaerobic 81–90%, extreme 91–100%; below 50% of maxHR → not counted (nil zone). maxHR formula:
 * 220 − age.
 *
 * CLOCK. One upstream test (`:184`) starts its session at Foundation's `.distantPast` and ends it at the
 * device clock (`Date()`). Here the start is Foundation's distant past itself (0001-01-01 UTC) and the end
 * a fixed 2026 instant, so the session is still about two thousand years long: a summary that fabricated
 * calories from elapsed time alone would show a number, and the test still bites. Every other test is
 * built from fixed instants, as upstream's are.
 */
class WorkoutSessionTest {

    private val t0: Instant = Instant.EPOCH
    private fun t(seconds: Long): Instant = t0.plusSeconds(seconds)

    /** Upstream's private test extension: `.walking` is `.walkingOutdoor`. */
    private val walking = WorkoutSportType.WALKING_OUTDOOR

    // MARK: - HRZoneClassifier.zone(bpm:maxHR:)

    @Test
    fun testZoneBelowHalfMaxHRIsNil() { // :13
        // 49% of 200 = 98 bpm — below 50%, not counted per APK
        assertNull(HRZoneClassifier.zone(bpm = 98, maxHR = 200))
    }

    @Test
    fun testZoneExactly50PercentIsWarmUp() { // :18
        // 50% of 200 = 100 bpm → warm-up (lower bound inclusive)
        assertEquals(HRZone.WARM_UP, HRZoneClassifier.zone(bpm = 100, maxHR = 200))
    }

    @Test
    fun testZoneWarmUpUpperBound() { // :23
        // 60% of 200 = 120 bpm → still warm-up
        assertEquals(HRZone.WARM_UP, HRZoneClassifier.zone(bpm = 120, maxHR = 200))
    }

    @Test
    fun testZoneFatBurnLower() { // :28
        // 61% of 200 = 122 bpm → fat burn
        assertEquals(HRZone.FAT_BURN, HRZoneClassifier.zone(bpm = 122, maxHR = 200))
    }

    @Test
    fun testZoneFatBurnUpper() { // :33
        // 70% of 200 = 140 bpm → fat burn
        assertEquals(HRZone.FAT_BURN, HRZoneClassifier.zone(bpm = 140, maxHR = 200))
    }

    @Test
    fun testZoneAerobicLower() { // :38
        // 71% of 200 = 142 bpm → aerobic
        assertEquals(HRZone.AEROBIC, HRZoneClassifier.zone(bpm = 142, maxHR = 200))
    }

    @Test
    fun testZoneAerobicUpper() { // :43
        // 80% of 200 = 160 bpm → aerobic
        assertEquals(HRZone.AEROBIC, HRZoneClassifier.zone(bpm = 160, maxHR = 200))
    }

    @Test
    fun testZoneAnaerobicLower() { // :48
        // 81% of 200 = 162 bpm → anaerobic
        assertEquals(HRZone.ANAEROBIC, HRZoneClassifier.zone(bpm = 162, maxHR = 200))
    }

    @Test
    fun testZoneAnaerobicUpper() { // :53
        // 90% of 200 = 180 bpm → anaerobic
        assertEquals(HRZone.ANAEROBIC, HRZoneClassifier.zone(bpm = 180, maxHR = 200))
    }

    @Test
    fun testZoneExtremeLower() { // :58
        // 91% of 200 = 182 bpm → extreme
        assertEquals(HRZone.EXTREME, HRZoneClassifier.zone(bpm = 182, maxHR = 200))
    }

    @Test
    fun testZoneExtremeAtMaxHR() { // :63
        // 100% of 200 = 200 bpm → extreme
        assertEquals(HRZone.EXTREME, HRZoneClassifier.zone(bpm = 200, maxHR = 200))
    }

    @Test
    fun testZoneZeroBpmIsNil() { // :68
        assertNull(HRZoneClassifier.zone(bpm = 0, maxHR = 200))
    }

    @Test
    fun testZoneZeroMaxHRIsNil() { // :72
        assertNull(HRZoneClassifier.zone(bpm = 100, maxHR = 0))
    }

    // MARK: - HRZoneClassifier.timeInZones

    @Test
    fun testTimeInZonesEmptySamplesAllZero() { // :78
        val breakdown = HRZoneClassifier.timeInZones(hrSamples = emptyList(), maxHR = 200)
        for (zone in HRZone.entries) {
            assertEquals(0.0, breakdown.seconds(zone))
        }
        assertEquals(0.0, breakdown.totalZoneSeconds)
    }

    @Test
    fun testTimeInZonesInstantaneousSamplesContributeZeroSeconds() { // :86
        // Instantaneous reading (end == start): classified by BPM but 0 s zone duration.
        val sample = HRSample(bpm = 150, start = t0, end = t0)
        val breakdown = HRZoneClassifier.timeInZones(hrSamples = listOf(sample), maxHR = 200)
        // 150/200 = 75% → aerobic, but 0 duration → 0 s
        assertEquals(0.0, breakdown.seconds(HRZone.AEROBIC))
        assertEquals(0.0, breakdown.totalZoneSeconds)
    }

    @Test
    fun testTimeInZones60SecAerobicSample() { // :96
        // One 60-second sample at 75% maxHR → aerobic zone 60 s
        val sample = HRSample(bpm = 150, start = t0, end = t(60)) // 150/200 = 75% → aerobic
        val breakdown = HRZoneClassifier.timeInZones(hrSamples = listOf(sample), maxHR = 200)
        assertEquals(60.0, breakdown.seconds(HRZone.AEROBIC), 0.001)
        assertEquals(0.0, breakdown.seconds(HRZone.WARM_UP))
        assertEquals(60.0, breakdown.totalZoneSeconds, 0.001)
    }

    @Test
    fun testTimeInZonesMultipleZones() { // :107
        // 30 s in warm-up (100 bpm = 50% of 200)
        val warmUp = HRSample(bpm = 100, start = t0, end = t(30))
        // 60 s in aerobic (150 bpm = 75% of 200)
        val aerobic = HRSample(bpm = 150, start = t(30), end = t(90))
        // 10 s below zone (90 bpm = 45% of 200 — not counted)
        val below = HRSample(bpm = 90, start = t(90), end = t(100))

        val breakdown = HRZoneClassifier.timeInZones(hrSamples = listOf(warmUp, aerobic, below), maxHR = 200)
        assertEquals(30.0, breakdown.seconds(HRZone.WARM_UP), 0.001)
        assertEquals(60.0, breakdown.seconds(HRZone.AEROBIC), 0.001)
        assertEquals(90.0, breakdown.totalZoneSeconds, 0.001) // below-zone not counted
    }

    @Test
    fun testFractionWithTotalZeroReturnsZero() { // :122
        val breakdown = HRZoneClassifier.timeInZones(hrSamples = emptyList(), maxHR = 200)
        assertEquals(0.0, breakdown.fraction(HRZone.AEROBIC))
    }

    @Test
    fun testFractionSumsToOne() { // :127
        val s1 = HRSample(bpm = 100, start = t0, end = t(30)) // warmUp 30 s
        val s2 = HRSample(bpm = 150, start = t(30), end = t(70)) // aerobic 40 s
        val breakdown = HRZoneClassifier.timeInZones(hrSamples = listOf(s1, s2), maxHR = 200)
        val total = HRZone.entries.map { breakdown.fraction(it) }.fold(0.0) { a, b -> a + b }
        assertEquals(1.0, total, 0.001)
    }

    // MARK: - Held (step-function) zone attribution — fixes the "0:50 for a 5:05 ride" undercount

    /** Periodic ~10 s readings stamped with a 2 s span must be HELD to the next reading, so the zone
     *  total tracks real elapsed time (not 5× short). 6 readings @150 bpm, 10 s apart, end at 65 s. */
    @Test
    fun testHeldAttributionTracksElapsedTime() { // :140
        val samples = (0 until 6).map { i ->
            HRSample(bpm = 150, start = t(i * 10L), end = t(i * 10L + 2)) // 2 s stamp, like the real path
        }
        val b = HRZoneClassifier.timeInZonesHeld(hrSamples = samples, maxHR = 200, sessionEnd = t(65))
        // 5 gaps × 10 s + last reading held 15 s to sessionEnd (65−50) = 65 s (vs old per-span 6×2 = 12 s).
        assertEquals(65.0, b.totalZoneSeconds, 0.001)
        assertEquals(65.0, b.seconds(HRZone.AEROBIC), 0.001) // 150/200 = 75% → aerobic
    }

    /** A genuine dropout must NOT be fabricated into zone time: each held interval caps at maxGap. */
    @Test
    fun testHeldAttributionCapsDropoutGaps() { // :154
        val samples = listOf(
            HRSample(bpm = 150, start = t0, end = t(2)),
            HRSample(bpm = 150, start = t(100), end = t(102)), // 100 s gap
        )
        val b = HRZoneClassifier.timeInZonesHeld(hrSamples = samples, maxHR = 200, sessionEnd = t(130), maxGapSeconds = 30.0)
        // First reading capped at 30 (not 100); last capped at 30 (not 28→ok, 130-100=30). Total 60, not 130.
        assertEquals(60.0, b.totalZoneSeconds, 0.001)
    }

    /** Time before the first reading is not attributed (no zone assumed before any data); sub-50% reads
     *  contribute nothing. */
    @Test
    fun testHeldAttributionSkipsPreFirstAndSubThreshold() { // :168
        val samples = listOf(
            HRSample(bpm = 80, start = t(10), end = t(12)), // 80/200=40% → none
            HRSample(bpm = 150, start = t(20), end = t(22)), // aerobic
        )
        val b = HRZoneClassifier.timeInZonesHeld(hrSamples = samples, maxHR = 200, sessionEnd = t(30))
        // [0,10) before first reading: not counted. First reading sub-50%: 0. Second held 10 s → aerobic.
        assertEquals(10.0, b.totalZoneSeconds, 0.001)
        assertEquals(10.0, b.seconds(HRZone.AEROBIC), 0.001)
        assertEquals(0.0, b.seconds(HRZone.WARM_UP), 0.001)
    }

    // MARK: - WorkoutSessionAggregator

    @Test
    fun testAggregatorEmptySessionProducesNilHR() { // :184
        // Upstream: `.distantPast` → `Date()`. Fixed here: Foundation's distant past → a 2026 instant.
        val agg = WorkoutSessionAggregator(startDate = Instant.parse("0001-01-01T00:00:00Z"), userAge = 30)
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
        val summary = agg.finalize(
            sport = WorkoutSportType.RUNNING_OUTDOOR,
            endDate = Instant.parse("2026-10-03T12:00:00Z"),
            distanceMeters = null,
            hasRoute = false,
            profile = profile,
        )
        assertNull(summary.avgHR)
        assertNull(summary.maxHR)
        assertNull(summary.estimatedActiveKcal)
        assertEquals(0, summary.hrSampleCount)
    }

    @Test
    fun testAggregatorComputesAvgAndMaxHR() { // :200
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        agg.add(HRSample(bpm = 100, start = t0, end = t(30)))
        agg.add(HRSample(bpm = 150, start = t(30), end = t(60)))
        agg.add(HRSample(bpm = 200, start = t(60), end = t(90)))

        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
        val summary = agg.finalize(
            sport = WorkoutSportType.RUNNING_OUTDOOR,
            endDate = t(90),
            distanceMeters = 500.0,
            hasRoute = false,
            profile = profile,
        )
        assertEquals(150, summary.avgHR) // (100+150+200)/3 = 150
        assertEquals(200, summary.maxHR)
        assertEquals(3, summary.hrSampleCount)
        assertEquals(500.0, summary.distanceMeters)
    }

    // MARK: - HR backfill (workout window) + distance-based active-energy fallback

    @Test
    fun testBackfillMergesInWindowStoredHRDedupedByTimestamp() { // :223
        val win = DateInterval(t0, t(600))
        val captured = listOf(HRSample(bpm = 120, start = t(100), end = t(102)))
        val stored = listOf(
            HRSample(bpm = 80, start = t(100)), // same start as captured → captured wins
            HRSample(bpm = 130, start = t(300)), // in-window, new → added
            HRSample(bpm = 60, start = t(900)), // out-of-window → ignored
        )
        val merged = WorkoutHRBackfill.merge(captured = captured, stored = stored, window = win)
        assertEquals(listOf(120, 130), merged.map { it.bpm }, "sorted by start; tie kept live 120; out-of-window dropped")
    }

    @Test
    fun testBackfillEmptyStoredLeavesCapturedUntouched() { // :236
        val win = DateInterval(t0, t(600))
        val captured = listOf(HRSample(bpm = 120, start = t(100)))
        assertEquals(listOf(120), WorkoutHRBackfill.merge(captured = captured, stored = emptyList(), window = win).map { it.bpm })
        assertTrue(WorkoutHRBackfill.merge(captured = emptyList(), stored = emptyList(), window = win).isEmpty(), "empty stays empty — never fabricated")
    }

    @Test
    fun testAggregatorBackfillFeedsFinalize() { // :245
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        val win = DateInterval(t0, t(600))
        agg.backfill(listOf(HRSample(bpm = 100, start = t(10), end = t(12)), HRSample(bpm = 140, start = t(20), end = t(22))), window = win)
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
        val summary = agg.finalize(sport = WorkoutSportType.WALKING_OUTDOOR, endDate = t(600), distanceMeters = null, hasRoute = false, profile = profile)
        assertEquals(2, summary.hrSampleCount)
        assertEquals(120, summary.avgHR) // (100+140)/2
        assertEquals(140, summary.maxHR)
    }

    @Test
    fun testDistanceFallbackActiveKcalWhenNoHR() { // :260
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 30) // no HR captured
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
        val summary = agg.finalize(sport = WorkoutSportType.WALKING_OUTDOOR, endDate = t(1800), distanceMeters = 2000.0, hasRoute = true, profile = profile)
        assertNull(summary.avgHR, "no HR was captured")
        // 2 km × 70 kg × 0.5 = 70 kcal — an honest distance estimate instead of nil/--.
        assertEquals(70.0, assertNotNull(summary.estimatedActiveKcal), 0.001)
    }

    @Test
    fun testAggregatorSportTypeAndDatePassThrough() { // :271
        val start = Instant.ofEpochSecond(1_000_000)
        val end = Instant.ofEpochSecond(1_003_600)
        val agg = WorkoutSessionAggregator(startDate = start, userAge = 35)
        val profile = UserProfile(age = 35, weightKg = 80.0, heightCm = 175.0, sex = BiologicalSex.MALE)
        val summary = agg.finalize(
            sport = WorkoutSportType.YOGA,
            endDate = end,
            distanceMeters = null,
            hasRoute = false,
            profile = profile,
        )
        assertEquals(WorkoutSportType.YOGA, summary.sport)
        assertEquals(start, summary.startDate)
        assertEquals(end, summary.endDate)
        assertEquals(3600.0, summary.durationSeconds, 0.001)
        assertFalse(summary.hasRoute)
    }

    @Test
    fun testAggregatorSufficientSamplesProducesCalorieEstimate() { // :290
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        // maxHR for age 30 = 220 - 30 = 190. 150 bpm = ~84% → anaerobic zone.
        for (i in 0 until 600) {
            agg.add(HRSample(bpm = 150, start = t(i.toLong()), end = t(i + 1L)))
        }
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
        val summary = agg.finalize(
            sport = WorkoutSportType.RUNNING_OUTDOOR,
            endDate = t(600),
            distanceMeters = 1000.0,
            hasRoute = false,
            profile = profile,
        )
        assertNotNull(summary.estimatedActiveKcal)
        assertTrue((summary.estimatedActiveKcal ?: 0.0) > 0)
    }

    /** Regression for the "-- calories" bug (5-min indoor cycle, ~30 readings): the ring streams HR
     *  only ~every 10 s, so a real workout has FAR fewer than the old 600-sample Edwards floor. A
     *  sparse-but-real HR series must now yield a positive HR-based estimate, not nil/"--". */
    @Test
    fun testSparseHRProducesCalorieEstimate() { // :314
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 35)
        // 30 readings, one per ~10 s, avg ≈ 101 bpm (the screenshot scenario) — moderate cycling.
        for (i in 0 until 30) {
            val s = t(i * 10L)
            agg.add(HRSample(bpm = 101, start = s, end = s.plusSeconds(2)))
        }
        val profile = UserProfile(age = 35, weightKg = 75.0, heightCm = 178.0, sex = BiologicalSex.MALE)
        val summary = agg.finalize(
            sport = WorkoutSportType.CYCLING_INDOOR,
            endDate = t(305), // 5m05s, no GPS distance (indoor)
            distanceMeters = null,
            hasRoute = false,
            profile = profile,
        )
        assertEquals(101, summary.avgHR)
        assertNotNull(summary.estimatedActiveKcal, "sparse HR must still estimate calories, not '--'")
        // Keytel (male, 75 kg, 35 y, 101 bpm) ≈ 7.3 kcal/min × 5.08 min ≈ 37 kcal — a sane, honest number.
        assertEquals(37.0, summary.estimatedActiveKcal ?: 0.0, 4.0)
        // Held zone attribution: the total tracks the real elapsed time (~305 s), NOT the ~60 s the old
        // per-span sum gave (30 readings × the 2 s stamp) — the 0:50-for-a-5:05-ride bug.
        assertEquals(305.0, summary.zoneBreakdown.totalZoneSeconds, 15.0)
    }

    /** An empty session (no HR, no distance) still reports nil calories — never fabricated. */
    @Test
    fun testNoHRNoDistanceStillNilCalories() { // :340
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 30) // no samples added
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
        val summary = agg.finalize(
            sport = walking,
            endDate = t(600),
            distanceMeters = null,
            hasRoute = false,
            profile = profile,
        )
        assertNull(summary.estimatedActiveKcal)
    }

    // MARK: - WorkoutSportType

    @Test
    fun testOutdoorTypesAreOutdoor() { // :356
        assertTrue(WorkoutSportType.WALKING_OUTDOOR.isOutdoor)
        assertTrue(WorkoutSportType.RUNNING_OUTDOOR.isOutdoor)
        assertTrue(WorkoutSportType.CYCLING_OUTDOOR.isOutdoor)
        assertTrue(WorkoutSportType.HIKING.isOutdoor)
    }

    @Test
    fun testIndoorTypesAreNotOutdoor() { // :363
        assertFalse(WorkoutSportType.STRENGTH_TRAINING.isOutdoor)
        assertFalse(WorkoutSportType.YOGA.isOutdoor)
        assertFalse(WorkoutSportType.OTHER.isOutdoor)
    }

    // MARK: - maxHR formula (220 - age)

    @Test
    fun testFormulaMaxHRAge30() { // :371
        // Indirect test: age 30 → maxHR = 190; 190 bpm at maxHR = 100% → extreme zone.
        assertEquals(HRZone.EXTREME, HRZoneClassifier.zone(bpm = 190, maxHR = 190))
        // 50% of 190 = 95 → warm-up lower bound
        assertEquals(HRZone.WARM_UP, HRZoneClassifier.zone(bpm = 95, maxHR = 190))
        // 94 → below 50% of 190 → nil
        assertNull(HRZoneClassifier.zone(bpm = 94, maxHR = 190))
    }

    // MARK: - Live snapshot (Live Activity feed)

    @Test
    fun testLiveAvgHRNilBeforeAnyReading() { // :382
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        assertNull(agg.currentAvgHR, "no readings ⇒ no live avg — never fabricated")
    }

    @Test
    fun testLiveAvgHRMatchesFinalizeAvg() { // :387
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        agg.add(HRSample(bpm = 100, start = t(10), end = t(12)))
        agg.add(HRSample(bpm = 140, start = t(20), end = t(22)))
        // Same integer truncation as finalize, so the Live Activity number matches the final summary.
        assertEquals(120, agg.currentAvgHR)
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
        val summary = agg.finalize(sport = WorkoutSportType.RUNNING_OUTDOOR, endDate = t(300), distanceMeters = null, hasRoute = false, profile = profile)
        assertEquals(summary.avgHR, agg.currentAvgHR)
    }

    @Test
    fun testLiveActiveKcalZeroBeforeAnyReading() { // :400
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
        // No HR yet ⇒ 0 (honest: the Live Activity shows 0, not a made-up number).
        assertEquals(0.0, agg.liveActiveKcal(profile = profile, asOf = t(60)))
    }

    @Test
    fun testLiveActiveKcalMatchesFinalizeHRKcal() { // :408
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 35)
        for (i in 0 until 30) {
            val s = t(i * 10L)
            agg.add(HRSample(bpm = 130, start = s, end = s.plusSeconds(2)))
        }
        val profile = UserProfile(age = 35, weightKg = 75.0, heightCm = 178.0, sex = BiologicalSex.MALE)
        val end = t(300)
        // Live kcal (HR-only) equals the Keytel model over the same window — no distance fallback,
        // so it matches finalize's HR-based estimate for an indoor (distance-less) session exactly.
        val live = agg.liveActiveKcal(profile = profile, asOf = end)
        val expected = Calories.workoutActiveKcal(avgHR = 130, durationSeconds = 300.0, profile = profile)
        assertEquals(expected, live, 0.001)
        assertTrue(live > 0)
    }

    @Test
    fun testLiveActiveKcalGrowsWithElapsedTime() { // :425
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 30)
        agg.add(HRSample(bpm = 150, start = t(5), end = t(7)))
        val profile = UserProfile(age = 30, weightKg = 70.0, heightCm = 170.0, sex = BiologicalSex.MALE)
        val atOneMin = agg.liveActiveKcal(profile = profile, asOf = t(60))
        val atFiveMin = agg.liveActiveKcal(profile = profile, asOf = t(300))
        assertTrue(atFiveMin > atOneMin, "elapsed time grows ⇒ estimate grows monotonically")
    }

    /** The displayed live calories must NEVER tick down. The raw avg-HR×elapsed model dips when a low
     *  reading pulls the running average down; the high-water clamp holds the number. Without the clamp
     *  this fails (a 160→70 bpm drop shrinks the product ~16→~11 kcal for this profile). */
    @Test
    fun testLiveActiveKcalNeverTicksDownWhenAvgDrops() { // :438
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 35)
        val profile = UserProfile(age = 35, weightKg = 75.0, heightCm = 178.0, sex = BiologicalSex.MALE)
        agg.add(HRSample(bpm = 160, start = t(5), end = t(7)))
        val k1 = agg.liveActiveKcal(profile = profile, asOf = t(60))
        assertTrue(k1 > 0)
        // A much lower reading pulls the running avg down; raw avg×elapsed would DROP below k1.
        agg.add(HRSample(bpm = 70, start = t(65), end = t(67)))
        val k2 = agg.liveActiveKcal(profile = profile, asOf = t(70))
        assertTrue(k2 >= k1, "displayed calories must never tick down when avg HR drops")
    }

    /** With constant HR the live high-water at session end equals `finalize`'s HR-based estimate for a
     *  distance-less (indoor) workout — the live number and the saved summary agree. */
    @Test
    fun testLiveActiveKcalAtEndMatchesFinalizeIndoor() { // :453
        val agg = WorkoutSessionAggregator(startDate = t0, userAge = 40)
        val profile = UserProfile(age = 40, weightKg = 80.0, heightCm = 180.0, sex = BiologicalSex.MALE)
        for (i in 0 until 20) {
            val s = t(i * 10L)
            agg.add(HRSample(bpm = 140, start = s, end = s.plusSeconds(2)))
        }
        val end = t(200)
        val live = agg.liveActiveKcal(profile = profile, asOf = end)
        val summary = agg.finalize(sport = WorkoutSportType.CYCLING_INDOOR, endDate = end, distanceMeters = null, hasRoute = false, profile = profile)
        assertEquals(summary.estimatedActiveKcal ?: -1.0, live, 0.001)
    }
}
