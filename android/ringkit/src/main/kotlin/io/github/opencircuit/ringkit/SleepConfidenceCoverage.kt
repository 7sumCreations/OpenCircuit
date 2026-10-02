package io.github.opencircuit.ringkit

// Coverage-aware sleep confidence: WHY this night's number might be wrong, not just THAT it might.
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepConfidenceCoverage.swift
// (@ b1c2fdd); the types (`Coverage`, `Reason`, `Assessment`) and the public `assess` entry points are
// declared on `SleepConfidence`, the logic is here.
//
// `SleepConfidence.classify` sees two totals and nothing else, which is why it cannot see a data hole.
// Upstream measured that blindness on its 21-night corpus: the two worst nights (each understated by
// 246 min because nothing was recorded after the wake for ~4 h) never reach the efficiency test,
// because the 5 h gate discards them. THE GATE IS NOT THE BUG, AND LOWERING IT IS HARMFUL: a
// truncated night that reaches the efficiency test reads near 1.0 and would be told "duration likely
// high" when it reads LOW. So this ADDS a second, independent question — did the recording actually
// cover the night? — and makes the duration verdict yield to it. Nothing here changes a staged number.

/** `SleepConfidence.assess` — see its KDoc for the parameters. */
internal fun assessCoverage(
    asleep: Double,
    inBed: Double,
    coverage: SleepConfidence.Coverage?,
    materialGapSeconds: Double,
): SleepConfidence.Assessment {
    val level = SleepConfidence.classify(asleep, inBed)

    val c = coverage ?: return SleepConfidence.Assessment(
        level = level,
        reasons = if (level == SleepConfidence.Level.DURATION_LIKELY_HIGH) listOf(SleepConfidence.Reason.DurationLikelyHigh) else emptyList(),
        bedtime = BedtimeProvenance.Verdict.Unknown,
        wake = WakeProvenance.Verdict.Unknown,
        materialGapSeconds = materialGapSeconds,
    )

    // Both edges take the same three inputs and both apply the retention guard themselves.
    val bedtime = BedtimeProvenance.classify(
        inBedStart = c.inBedStart,
        lastMeasurementBefore = c.lastMeasurementBeforeStart,
        earliestRetainedMeasurement = c.earliestRetainedMeasurement,
    )
    // The UNION of both inputs, never one or the other: a series missing the first record would
    // invent a stop the single-step rule called witnessed, and a series that stops early would silence
    // one it reported. Adding the first instant back can only make the walk quieter. (A set, as
    // upstream: duplicates collapse before the walk.)
    val afterEnd = (c.measurementsAfterEnd + listOfNotNull(c.firstMeasurementAfterEnd)).toSortedSet().toList()
    val stoppage = WakeProvenance.stoppage(
        inBedEnd = c.inBedEnd,
        measurementsAfter = afterEnd,
        earliestRetainedMeasurement = c.earliestRetainedMeasurement,
    )
    val wake = stoppage.verdict

    val reasons = ArrayList<SleepConfidence.Reason>(2)

    if (wake is WakeProvenance.Verdict.StoppedThenResumed && wake.seconds > materialGapSeconds) {
        // `silenceBegan`, NOT `inBedEnd`: the walk can consume records before the hole, so the silence
        // may begin AFTER the edge. The copy relies on `from + silentFor == the record that resumed`.
        reasons += SleepConfidence.Reason.NoRecordingAfterWake(from = stoppage.silenceBegan ?: c.inBedEnd, silentFor = wake.seconds)
    }
    if (bedtime is BedtimeProvenance.Verdict.ResumedAfterGap && bedtime.seconds > materialGapSeconds) {
        reasons += SleepConfidence.Reason.NoRecordingBeforeBedtime(until = c.inBedStart, silentFor = bedtime.seconds)
    }
    // The duration claim is the OPPOSITE of the acquisition claim — never both. It ALSO requires a
    // WITNESSED back edge: "we did not look" must never read as "we watched". A duration-reads-HIGH
    // claim asserts that the measured window is the whole night, and only a witnessed trailing edge
    // establishes that (upstream's 2026-08-26 tester night: stream ended 02:47:30, she got up at 06:46,
    // wake verdict unknown, and the card said "may read a little high" on a night four hours LOW).
    if (reasons.isEmpty() && level == SleepConfidence.Level.DURATION_LIKELY_HIGH && wake == WakeProvenance.Verdict.Witnessed) {
        reasons += SleepConfidence.Reason.DurationLikelyHigh
    }

    return SleepConfidence.Assessment(level = level, reasons = reasons, bedtime = bedtime, wake = wake, materialGapSeconds = materialGapSeconds)
}
