package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.HealthAlertEvaluator.ActivityInterval
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNTHETIC-ONLY tests for the local health-alert engine: thresholds, flag routing, quiet-hours DND,
 * and the anti-spam de-dupe gate. No real health values.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HealthAlertsTests.swift (@ b1c2fdd),
 * each test named after upstream's with its line.
 *
 * ZONE. Upstream builds every `at(h, m)` in the machine's zone (`Calendar(identifier: .gregorian)`)
 * and calls the quiet-hours gate with `Calendar.current`, so its instants and its gate always agree
 * with each other, whatever the machine. Here both take ONE named zone, [zone] — Asia/Kolkata, UTC+5:30
 * all year. It is chosen so the tests bite: its wall clock is 5 h 30 min from UTC and at least 9 h 30
 * min from every American zone, so a gate that read UTC or the machine's zone instead of the zone it
 * is given would put 23:00 / 02:00 / 22:45 outside the 22:00–07:00 window and 12:00 inside it.
 */
class HealthAlertsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    /**
     * 2026-06-17 [h]:[m] in [zone]. Lenient, as Foundation's `DateComponents` are: a minute past 59
     * rolls into the next hour (`:118` builds up to minute 95).
     */
    private fun at(h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 6, 17, 0, 0).plusHours(h.toLong()).plusMinutes(m.toLong()).atZone(zone).toInstant()
    private fun hr(bpm: Int, h: Int, m: Int = 0): HRSample = HRSample(bpm = bpm, start = at(h, m))
    private fun spo2(pct: Int, h: Int, m: Int = 0): SpO2Reading = SpO2Reading(percent = pct, time = at(h, m))

    // MARK: threshold rules

    @Test
    fun highHRPicksWorstReading() { // :16
        val s = listOf(hr(80, 9), hr(125, 10), hr(140, 11), hr(90, 12))
        val hit = HealthAlertEvaluator.highHR(s, thresholdBpm = 120)
        assertEquals(140, hit?.bpm)
        assertNull(HealthAlertEvaluator.highHR(listOf(hr(80, 9), hr(100, 10)), thresholdBpm = 120))
    }

    /** `minReadings: 1` is the documented kill-switch, and it must reproduce the original rule exactly. */
    @Test
    fun lowSpO2KillSwitchPicksWorstReading() { // :25
        val r = listOf(SpO2Reading(97, at(2)), SpO2Reading(88, at(3)), SpO2Reading(91, at(4)))
        assertEquals(88, HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90, minReadings = 1)?.percent)
        // Zero/invalid placeholders are ignored.
        assertNull(HealthAlertEvaluator.lowSpO2(listOf(SpO2Reading(0, at(2))), thresholdPercent = 90, minReadings = 1))
        assertNull(HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 80, minReadings = 1))
    }

    // MARK: Low-SpO2 persistence gate

    /** The measured tester night, reduced to its shape: ONE artifact epoch at 89 % must NOT alert. */
    @Test
    fun lowSpO2IgnoresAnIsolatedArtifactEpoch() { // :43
        val r = listOf(spo2(97, 6, 54), spo2(96, 6, 59), spo2(89, 7, 4), spo2(95, 7, 9), spo2(96, 7, 14))
        assertNull(HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90))
    }

    /** A genuine desaturation still fires, and still reports the WORST reading of the run. */
    @Test
    fun lowSpO2FiresOnASustainedRunAndReportsTheWorst() { // :50
        val r = listOf(spo2(97, 2), spo2(89, 3, 0), spo2(86, 3, 5), spo2(90, 3, 10), spo2(97, 4))
        assertEquals(86, HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90)?.percent)
    }

    /** The desaturation DEEPENS after the run already qualifies: report the nadir, not the prefix. */
    @Test
    fun lowSpO2ReportsTheRunsNadirNotTheFirstQualifyingPair() { // :62
        val r = listOf(spo2(90, 3, 0), spo2(90, 3, 5), spo2(79, 3, 10), spo2(82, 3, 15))
        val hit = HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90)
        assertEquals(79, hit?.percent)
        assertEquals(at(3, 10), hit?.time, "the reported TIME must be the nadir's, not the prefix's")
        // Identical to what the pre-persistence rule reported for this night.
        assertEquals(hit?.percent, HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90, minReadings = 1)?.percent)
    }

    /** A shallow qualifying pair EARLY must not mask a deeper qualifying run LATER. */
    @Test
    fun lowSpO2PrefersTheDeepestQualifyingRunAcrossTheNight() { // :73
        val r = listOf(spo2(90, 1, 0), spo2(90, 1, 5), spo2(78, 4, 0), spo2(76, 4, 5), spo2(74, 4, 10))
        assertEquals(74, HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90)?.percent)
    }

    /** A deeper reading that is NOT part of any qualifying run must not be borrowed. */
    @Test
    fun lowSpO2IgnoresDepthFromNonQualifyingRuns() { // :81
        val r = listOf(
            spo2(70, 1, 0), // lone artifact, no run
            spo2(89, 4, 0), spo2(88, 4, 5), // the genuine qualifying pair
        )
        assertEquals(88, HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90)?.percent)
    }

    /** A count-based rule IS fooled by one epoch counted twice — hence the de-dupe at the merge site. */
    @Test
    fun lowSpO2IsSatisfiedByOneEpochWhenTheSeriesIsDuplicated() { // :91
        val artifact = listOf(spo2(89, 7, 4))
        assertNull(HealthAlertEvaluator.lowSpO2(artifact, thresholdPercent = 90))
        assertNotNull(
            HealthAlertEvaluator.lowSpO2(artifact + artifact, thresholdPercent = 90),
            "a duplicated series DOES fool the count — hence the de-dupe at the merge",
        )
    }

    /** Two unrelated single-epoch artifacts far apart must not pair up into a fake run. */
    @Test
    fun lowSpO2DoesNotPairTwoDistantArtifacts() { // :99
        val r = listOf(spo2(89, 1, 0), spo2(97, 1, 30), spo2(88, 3, 0))
        assertNull(HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90), "2 h apart exceeds both the window and the max gap")
    }

    /** Two lows inside the window but separated by more than `maxGap` are still rejected. */
    @Test
    fun lowSpO2RejectsAWideGapInsideTheWindow() { // :107
        val r = listOf(spo2(89, 1, 0), spo2(88, 1, 25))
        assertNull(HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90, window = 30 * 60.0, maxGap = 20 * 60.0))
        // Bring them within the gap and the same pair now qualifies.
        assertEquals(88, HealthAlertEvaluator.lowSpO2(listOf(spo2(89, 1, 0), spo2(88, 1, 15)), thresholdPercent = 90)?.percent)
    }

    /** A long, genuinely sustained desaturation qualifies rather than being disqualified for lasting. */
    @Test
    fun lowSpO2FiresOnARunLongerThanTheWindow() { // :118
        val r = (0 until 20).map { spo2(88, 1, it * 5) } // 100 min of continuous lows
        assertEquals(88, HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90)?.percent)
    }

    @Test
    fun lowSpO2EmptyAndAllHealthyAreNil() { // :123
        assertNull(HealthAlertEvaluator.lowSpO2(emptyList(), thresholdPercent = 90))
        assertNull(HealthAlertEvaluator.lowSpO2(listOf(spo2(97, 1), spo2(96, 2)), thresholdPercent = 90))
    }

    /** The recency cut is applied AFTER the runs are built, so a straddling run is not lost. */
    @Test
    fun lowSpO2DoesNotLoseARunStraddlingThePreviousFire() { // :131
        val run = listOf(spo2(88, 3, 0), spo2(84, 3, 5), spo2(86, 3, 10))
        // We already alerted just after the first reading; only two of the three are "new".
        val cut = at(3, 2)
        assertEquals(84, HealthAlertEvaluator.lowSpO2(run, thresholdPercent = 90, since = cut)?.percent, "the run survives and still reports its true nadir")
        // The pathological shape: only ONE reading postdates the cut.
        val straddle = listOf(spo2(88, 3, 0), spo2(84, 3, 5))
        assertEquals(84, HealthAlertEvaluator.lowSpO2(straddle, thresholdPercent = 90, since = at(3, 2))?.percent)
    }

    /** …but a run that is entirely OLD must not re-alert. */
    @Test
    fun lowSpO2DoesNotReAlertAFinishedRun() { // :146
        val run = listOf(spo2(88, 3, 0), spo2(84, 3, 5))
        assertNull(HealthAlertEvaluator.lowSpO2(run, thresholdPercent = 90, since = at(4, 0)))
    }

    /** The kill-switch honours the cut too, so turning persistence off cannot resurrect old hits. */
    @Test
    fun lowSpO2KillSwitchHonoursTheRecencyCut() { // :152
        val r = listOf(spo2(80, 1, 0), spo2(88, 5, 0))
        assertEquals(88, HealthAlertEvaluator.lowSpO2(r, thresholdPercent = 90, minReadings = 1, since = at(3, 0))?.percent)
    }

    // MARK: Quiet hours must delay, never destroy

    /** `suppressedSpan` is what a rolling look-back is widened by. */
    @Test
    fun quietHoursSuppressedSpanIsTheWindowLength() { // :163
        assertEquals(9 * 3600.0, QuietHours(enabled = true, startMinutes = 22 * 60, endMinutes = 7 * 60).suppressedSpan, 1e-9)
        assertEquals(5 * 3600.0, QuietHours(enabled = true, startMinutes = 1 * 60, endMinutes = 6 * 60).suppressedSpan, 1e-9)
        assertEquals(0.0, QuietHours(enabled = false, startMinutes = 22 * 60, endMinutes = 7 * 60).suppressedSpan, 1e-9)
        assertEquals(0.0, QuietHours(enabled = true, startMinutes = 300, endMinutes = 300).suppressedSpan, 1e-9, "a degenerate window suppresses nothing")
    }

    /** The gate is wired into `evaluate`, not just callable in isolation. */
    @Test
    fun evaluateAppliesThePersistenceGate() { // :176
        val artifact = listOf(spo2(97, 6, 54), spo2(89, 7, 4), spo2(96, 7, 14))
        val hits = HealthAlertEvaluator.evaluate(hr = emptyList(), spo2 = artifact, inactiveHR = emptyList(), thresholds = HealthAlertThresholds())
        assertFalse(hits.any { it.notification == HealthNotification.LOW_SPO2 })

        val real = listOf(spo2(89, 3, 0), spo2(86, 3, 5))
        val fired = HealthAlertEvaluator.evaluate(hr = emptyList(), spo2 = real, inactiveHR = emptyList(), thresholds = HealthAlertThresholds())
        assertEquals(86.0, fired.firstOrNull { it.notification == HealthNotification.LOW_SPO2 }?.value)
    }

    @Test
    fun elevatedHRInactiveSustained() { // :188
        // 5 readings ≥100 spanning 10 min (epochs ~2.5 min apart) → fires on the last.
        val s = listOf(hr(105, 1, 0), hr(108, 1, 3), hr(110, 1, 6), hr(106, 1, 9), hr(112, 1, 12))
        val hit = HealthAlertEvaluator.elevatedHRInactive(s, thresholdBpm = 100, minDuration = 10 * 60.0)
        assertEquals(112, hit?.bpm)
    }

    @Test
    fun elevatedHRInactiveTooShort() { // :195
        // Elevated for only ~6 min → no fire.
        val s = listOf(hr(105, 1, 0), hr(108, 1, 3), hr(110, 1, 6))
        assertNull(HealthAlertEvaluator.elevatedHRInactive(s, thresholdBpm = 100, minDuration = 10 * 60.0))
    }

    @Test
    fun elevatedHRInactiveResetsBelowThreshold() { // :201
        // A dip below threshold breaks the run; the later cluster is too short on its own.
        val s = listOf(hr(105, 1, 0), hr(108, 1, 3), hr(70, 1, 6), hr(110, 1, 9), hr(112, 1, 12))
        assertNull(HealthAlertEvaluator.elevatedHRInactive(s, thresholdBpm = 100, minDuration = 10 * 60.0))
    }

    @Test
    fun elevatedHRInactiveGapBreaksRun() { // :207
        // Two elevated readings 30 min apart — gap exceeds maxGap, so not one continuous run.
        val s = listOf(hr(110, 1, 0), hr(112, 1, 30))
        assertNull(HealthAlertEvaluator.elevatedHRInactive(s, thresholdBpm = 100, minDuration = 10 * 60.0, maxGap = 5 * 60.0))
    }

    @Test
    fun evaluateRespectsEnableFlags() { // :214
        val highOnly = HealthAlertThresholds(highHREnabled = true, lowSpO2Enabled = false, elevatedHREnabled = false)
        val hits = HealthAlertEvaluator.evaluate(
            hr = listOf(hr(130, 10)),
            spo2 = listOf(SpO2Reading(85, at(3))),
            inactiveHR = emptyList(),
            thresholds = highOnly,
        )
        assertEquals(listOf(HealthNotification.HIGH_HR), hits.map { it.notification })
    }

    @Test
    fun evaluateSuppressesReadingsAtOrBeforeLastFired() { // :224
        val thresholds = HealthAlertThresholds(highHRBpm = 120, lowSpO2Percent = 90, elevatedHRBpm = 100)
        val oldInactiveRun = listOf(hr(105, 1, 0), hr(108, 1, 3), hr(110, 1, 6), hr(106, 1, 9), hr(112, 1, 12))
        val hits = HealthAlertEvaluator.evaluate(
            hr = listOf(hr(130, 2)),
            spo2 = listOf(SpO2Reading(85, at(2))),
            inactiveHR = oldInactiveRun,
            thresholds = thresholds,
            lastFired = mapOf(
                HealthNotification.HIGH_HR to at(3), HealthNotification.LOW_SPO2 to at(3), HealthNotification.ELEVATED_HR_INACTIVE to at(3),
            ),
        )
        assertTrue(hits.isEmpty(), "old threshold crossings must not replay after the backoff expires")
    }

    @Test
    fun evaluateAllowsFreshInactiveRunAfterLastFired() { // :240
        val thresholds = HealthAlertThresholds(highHREnabled = false, lowSpO2Enabled = false, elevatedHRBpm = 100)
        val freshRun = listOf(hr(105, 4, 0), hr(108, 4, 3), hr(110, 4, 6), hr(106, 4, 9), hr(112, 4, 12))
        val hits = HealthAlertEvaluator.evaluate(
            hr = emptyList(),
            spo2 = emptyList(),
            inactiveHR = freshRun,
            thresholds = thresholds,
            lastFired = mapOf(HealthNotification.ELEVATED_HR_INACTIVE to at(3)),
        )
        assertEquals(listOf(HealthNotification.ELEVATED_HR_INACTIVE), hits.map { it.notification })
        assertEquals(112.0, hits.firstOrNull()?.value)
    }

    // MARK: Background-drain latency (30–60 min old timestamps) — de-dupe is the ONLY gate

    @Test
    fun drainLatencyOldHighHRCrossingFiresOnFirstSight() { // :259
        // A not-yet-fired crossing ~45 min old on arrival must still alert once.
        val thresholds = HealthAlertThresholds(highHRBpm = 120, lowSpO2Enabled = false, elevatedHREnabled = false)
        val hits = HealthAlertEvaluator.evaluate(
            hr = listOf(hr(145, 9, 15)), // ~45 min before the post-drain evaluation at ~10:00
            spo2 = emptyList(),
            inactiveHR = emptyList(),
            thresholds = thresholds,
            lastFired = emptyMap(),
        )
        assertEquals(listOf(HealthNotification.HIGH_HR), hits.map { it.notification })
        assertEquals(145.0, hits.firstOrNull()?.value)
    }

    @Test
    fun drainLatencyOldSustainedRunFiresOnFirstSight() { // :278
        // A sustained elevated-while-inactive run whose 10-min completion is ~40 min old on arrival.
        val thresholds = HealthAlertThresholds(highHREnabled = false, lowSpO2Enabled = false, elevatedHRBpm = 100, elevatedSustained = 10 * 60.0)
        val run = listOf(hr(105, 9, 0), hr(108, 9, 3), hr(110, 9, 6), hr(106, 9, 9), hr(112, 9, 12))
        val hits = HealthAlertEvaluator.evaluate(hr = emptyList(), spo2 = emptyList(), inactiveHR = run, thresholds = thresholds, lastFired = emptyMap())
        assertEquals(listOf(HealthNotification.ELEVATED_HR_INACTIVE), hits.map { it.notification })
        assertEquals(112.0, hits.firstOrNull()?.value)
    }

    @Test
    fun drainLatencyFiredCrossingDoesNotReplayOnNextDrain() { // :297
        // The next hourly drain re-delivers the SAME hours-old samples; `lastFired` must drop them.
        val thresholds = HealthAlertThresholds(highHRBpm = 120, lowSpO2Enabled = false, elevatedHRBpm = 100, elevatedSustained = 10 * 60.0)
        val redeliveredHigh = listOf(hr(145, 9, 15))
        val redeliveredRun = listOf(hr(105, 9, 0), hr(108, 9, 3), hr(110, 9, 6), hr(106, 9, 9), hr(112, 9, 12))
        val hits = HealthAlertEvaluator.evaluate(
            hr = redeliveredHigh,
            spo2 = emptyList(),
            inactiveHR = redeliveredRun,
            thresholds = thresholds,
            lastFired = mapOf(HealthNotification.HIGH_HR to at(9, 15), HealthNotification.ELEVATED_HR_INACTIVE to at(9, 12)),
        )
        assertTrue(hits.isEmpty(), "already-fired crossings must not replay on the next drain")
    }

    // MARK: activity gate (nonExercising)

    @Test
    fun nonExercisingDropsHROverlappingSteps() { // :319
        // A high HR concurrent with a stepping window is dropped; a high HR in a still window survives.
        val stepping = ActivityInterval(at(10, 0), at(10, 20)) // 20-min walk
        val series = listOf(
            hr(165, 10, 10), // during the walk → excluded
            hr(122, 14, 0), // still period → kept
        )
        val filtered = HealthAlertEvaluator.nonExercising(series, activeIntervals = listOf(stepping), pad = 10 * 60.0)
        assertEquals(listOf(122), filtered.map { it.bpm })
    }

    @Test
    fun nonExercisingExcludesRecoveryTail() { // :328
        // A crossing within the `pad` recovery tail AFTER the walk ends is still excluded…
        val stepping = ActivityInterval(at(10, 0), at(10, 20))
        val inTail = listOf(hr(150, 10, 25)) // 5 min after the walk, inside the 10-min pad
        assertTrue(HealthAlertEvaluator.nonExercising(inTail, activeIntervals = listOf(stepping), pad = 10 * 60.0).isEmpty())
        // …but beyond the pad it survives (recovery is over).
        val afterTail = listOf(hr(150, 10, 35)) // 15 min after → outside the 10-min pad
        assertEquals(1, HealthAlertEvaluator.nonExercising(afterTail, activeIntervals = listOf(stepping), pad = 10 * 60.0).size)
    }

    @Test
    fun nonExercisingNoStepDataSuppressesNothing() { // :338
        // Missing step data must NEVER silence a real crossing — empty intervals returns the series as-is.
        val series = listOf(hr(165, 10, 10), hr(122, 14, 0))
        assertEquals(series, HealthAlertEvaluator.nonExercising(series, activeIntervals = emptyList(), pad = 10 * 60.0))
    }

    @Test
    fun nonExercisingGatedEvaluateOnlyAlertsStillCrossing() { // :344
        // End-to-end: the exercising 165 bpm is suppressed while the resting crossing still fires once.
        val thresholds = HealthAlertThresholds(highHRBpm = 120, lowSpO2Enabled = false, elevatedHREnabled = false)
        val stepping = ActivityInterval(at(10, 0), at(10, 20))
        val mixed = listOf(
            hr(165, 10, 10), // exercising → suppressed
            hr(128, 14, 0), // resting crossing → alerts
        )
        val gated = HealthAlertEvaluator.nonExercising(mixed, activeIntervals = listOf(stepping))
        val hits = HealthAlertEvaluator.evaluate(hr = gated, spo2 = emptyList(), inactiveHR = gated, thresholds = thresholds)
        assertEquals(listOf(HealthNotification.HIGH_HR), hits.map { it.notification })
        assertEquals(128.0, hits.firstOrNull()?.value)
    }

    // MARK: activeStepIntervals — the production step-source path + day-wide fallback guard

    @Test
    fun activeStepIntervalsKeepsNarrowNonzeroWindows() { // :359
        // A normal per-reading window (a few minutes, nonzero delta) becomes a gate interval.
        val w = listOf(StepWindow(start = at(10, 0), end = at(10, 3), delta = 40))
        val intervals = HealthAlertEvaluator.activeStepIntervals(w)
        assertEquals(1, intervals.size)
        assertEquals(at(10, 0), intervals.firstOrNull()?.start)
        assertEquals(at(10, 3), intervals.firstOrNull()?.end)
    }

    @Test
    fun activeStepIntervalsDropsZeroDeltaWindow() { // :368
        val w = listOf(StepWindow(start = at(10, 0), end = at(10, 3), delta = 0))
        assertTrue(HealthAlertEvaluator.activeStepIntervals(w).isEmpty())
    }

    @Test
    fun activeStepIntervalsExcludesDayWideFallbackWindow() { // :373
        // SAFETY GUARD: a day-wide [startOfDay, sampleDate] window must not become a gate interval.
        val dayWide = listOf(StepWindow(start = at(0, 0), end = at(10, 15), delta = 900)) // 10h15m fallback
        assertTrue(HealthAlertEvaluator.activeStepIntervals(dayWide).isEmpty(), "day-wide fallback window must not become a gate interval")
        // The boundary: a window exactly at the cap is kept; just over it is dropped.
        val atCap = listOf(StepWindow(start = at(10, 0), end = at(10, 30), delta = 5)) // == 30 min
        val overCap = listOf(StepWindow(start = at(10, 0), end = at(10, 31), delta = 5)) // 31 min
        assertEquals(1, HealthAlertEvaluator.activeStepIntervals(atCap).size)
        assertTrue(HealthAlertEvaluator.activeStepIntervals(overCap).isEmpty())
    }

    @Test
    fun dayWideFallbackWindowCannotSuppressGenuineCrossing() { // :386
        // A resting crossing at 08:30 must STILL fire when a day-wide fallback window [00:00, 10:15] is present.
        val thresholds = HealthAlertThresholds(highHRBpm = 120, lowSpO2Enabled = false, elevatedHREnabled = false)
        val steps = listOf(StepWindow(start = at(0, 0), end = at(10, 15), delta = 900)) // day-wide fallback only
        val intervals = HealthAlertEvaluator.activeStepIntervals(steps)
        val crossing = listOf(hr(150, 8, 30)) // resting, no concurrent steps
        val gated = HealthAlertEvaluator.nonExercising(crossing, activeIntervals = intervals)
        val hits = HealthAlertEvaluator.evaluate(hr = gated, spo2 = emptyList(), inactiveHR = gated, thresholds = thresholds)
        assertEquals(listOf(HealthNotification.HIGH_HR), hits.map { it.notification }, "a day-wide fallback window must never silence a real crossing")
    }

    @Test
    fun narrowWindowGateEngagesOnRealStepData() { // :400
        // The gate DOES engage on real narrow windows (proving it's not a no-op).
        val thresholds = HealthAlertThresholds(highHRBpm = 120, lowSpO2Enabled = false, elevatedHREnabled = false)
        val steps = listOf(StepWindow(start = at(10, 0), end = at(10, 3), delta = 60))
        val intervals = HealthAlertEvaluator.activeStepIntervals(steps)
        val mixed = listOf(
            hr(165, 10, 1), // exercising → suppressed
            hr(128, 14, 0), // resting crossing → alerts
        )
        val gated = HealthAlertEvaluator.nonExercising(mixed, activeIntervals = intervals)
        val hits = HealthAlertEvaluator.evaluate(hr = gated, spo2 = emptyList(), inactiveHR = gated, thresholds = thresholds)
        assertEquals(listOf(HealthNotification.HIGH_HR), hits.map { it.notification })
        assertEquals(128.0, hits.firstOrNull()?.value)
    }

    // MARK: bedtime reminder bypasses the quiet-hours gate (caller-side split)

    @Test
    fun bedtimeReminderBypassesQuietHoursWhileVitalsStayMuted() { // :416
        // `now` is 22:45 — inside BOTH the default 22:00–07:00 quiet window AND a typical bedtime window.
        // Upstream's three filter calls read `Calendar.current`; here they take [zone], as `at` does.
        val gate = NotificationGate()
        val quiet = QuietHours(enabled = true, startMinutes = 22 * 60, endMinutes = 7 * 60)
        val now = at(22, 45)
        // Body-vital alert: still suppressed during quiet hours.
        assertTrue(gate.filter(listOf(HealthNotification.HIGH_HR), now = now, lastFired = emptyMap(), quietHours = quiet, zone = zone).isEmpty())
        // Bedtime reminder: gated with quiet disabled → fires even though `now` is inside quiet hours.
        assertEquals(
            listOf(HealthNotification.BEDTIME_REMINDER),
            gate.filter(listOf(HealthNotification.BEDTIME_REMINDER), now = now, lastFired = emptyMap(), quietHours = QuietHours(enabled = false), zone = zone),
        )
        // Backoff still applies: a second eval later in the same window is suppressed (fires once/night).
        assertTrue(
            gate.filter(
                listOf(HealthNotification.BEDTIME_REMINDER), now = at(22, 50), lastFired = mapOf(HealthNotification.BEDTIME_REMINDER to now),
                quietHours = QuietHours(enabled = false), zone = zone,
            ).isEmpty(),
        )
    }

    // MARK: Quiet hours (DND)

    @Test
    fun quietHoursWrapsMidnight() { // :494
        val q = QuietHours(enabled = true, startMinutes = 22 * 60, endMinutes = 7 * 60)
        assertTrue(q.contains(at(23), zone))
        assertTrue(q.contains(at(2), zone))
        assertFalse(q.contains(at(12), zone))
        assertFalse(q.contains(at(7), zone), "end is exclusive")
    }

    @Test
    fun quietHoursDisabled() { // :502
        val q = QuietHours(enabled = false, startMinutes = 22 * 60, endMinutes = 7 * 60)
        assertFalse(q.contains(at(2), zone))
    }

    // MARK: De-dupe / DND gate

    @Test
    fun gateSuppressesDuringQuietHours() { // :509
        val gate = NotificationGate()
        val q = QuietHours(enabled = true, startMinutes = 22 * 60, endMinutes = 7 * 60)
        assertFalse(gate.shouldFire(HealthNotification.HIGH_HR, now = at(2), lastFired = emptyMap(), quietHours = q, zone = zone))
        assertTrue(gate.shouldFire(HealthNotification.HIGH_HR, now = at(12), lastFired = emptyMap(), quietHours = q, zone = zone))
    }

    @Test
    fun gateRenotifyBackoff() { // :516
        val gate = NotificationGate(renotifyInterval = 2 * 3600.0)
        val last = mapOf(HealthNotification.LOW_SPO2 to at(10))
        val q = QuietHours(enabled = false)
        // 1h later — still inside backoff.
        assertFalse(gate.shouldFire(HealthNotification.LOW_SPO2, now = at(11), lastFired = last, quietHours = q, zone = zone))
        // 3h later — backoff elapsed.
        assertTrue(gate.shouldFire(HealthNotification.LOW_SPO2, now = at(13), lastFired = last, quietHours = q, zone = zone))
        // A DIFFERENT condition is independent.
        assertTrue(gate.shouldFire(HealthNotification.HIGH_HR, now = at(11), lastFired = last, quietHours = q, zone = zone))
    }

    @Test
    fun gateFilterStableOrder() { // :528
        val gate = NotificationGate()
        val q = QuietHours(enabled = false)
        val out = gate.filter(
            listOf(HealthNotification.FEVER, HealthNotification.HIGH_HR, HealthNotification.LOW_SPO2),
            now = at(12), lastFired = emptyMap(), quietHours = q, zone = zone,
        )
        // Returned in declaration order: highHR, lowSpO2, …, fever.
        assertEquals(listOf(HealthNotification.HIGH_HR, HealthNotification.LOW_SPO2, HealthNotification.FEVER), out)
    }
}
