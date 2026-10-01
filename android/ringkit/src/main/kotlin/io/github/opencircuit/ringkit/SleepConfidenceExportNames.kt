package io.github.opencircuit.ringkit

// WIRE NAMES for `SleepConfidence.Assessment` — the strings a tester bundle and the data export carry,
// and nothing else. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepConfidenceExportNames.swift (@ b1c2fdd); the public
// `exportName` / `gapSeconds` entry points are declared on `SleepConfidence`, the mapping is here.
//
// Instrumentation, landed deliberately AHEAD of any user-visible caveat: the verdicts ship into the
// export's per-night edge provenance and the diagnostics bundle so that "on nights the wearer did NOT
// edit, how often does the wake gap exceed an hour?" becomes answerable from real devices.
//
// ⚠️ APPEND-ONLY. These are a wire format. Renaming one silently breaks every bundle already collected,
// and the rename is invisible at compile time on the reading side because the reader is a script.

/** `SleepConfidence.exportName(reason)`. */
internal fun reasonExportName(reason: SleepConfidence.Reason): String = when (reason) {
    is SleepConfidence.Reason.NoRecordingAfterWake -> "noRecordingAfterWake"
    is SleepConfidence.Reason.NoRecordingBeforeBedtime -> "noRecordingBeforeBedtime"
    SleepConfidence.Reason.DurationLikelyHigh -> "durationLikelyHigh"
}

/** `SleepConfidence.exportName(verdict)` for the leading edge. */
internal fun bedtimeExportName(verdict: BedtimeProvenance.Verdict): String = when (verdict) {
    BedtimeProvenance.Verdict.Witnessed -> "witnessed"
    is BedtimeProvenance.Verdict.ResumedAfterGap -> "resumedAfterGap"
    BedtimeProvenance.Verdict.NoPriorMeasurement -> "noPriorMeasurement"
    BedtimeProvenance.Verdict.Unknown -> "unknown"
}

/** `SleepConfidence.exportName(verdict)` for the trailing edge. */
internal fun wakeExportName(verdict: WakeProvenance.Verdict): String = when (verdict) {
    WakeProvenance.Verdict.Witnessed -> "witnessed"
    is WakeProvenance.Verdict.StoppedThenResumed -> "stoppedThenResumed"
    WakeProvenance.Verdict.Unknown -> "unknown"
}

/**
 * The measured silence a leading-edge verdict carries, or null. `Witnessed` and `Unknown` genuinely have
 * no gap — emitting 0 would claim we measured a continuous stream, the opposite of what `Unknown` means.
 */
internal fun bedtimeGapSeconds(verdict: BedtimeProvenance.Verdict): Double? =
    (verdict as? BedtimeProvenance.Verdict.ResumedAfterGap)?.seconds

/** The measured silence a trailing-edge verdict carries, or null. */
internal fun wakeGapSeconds(verdict: WakeProvenance.Verdict): Double? =
    (verdict as? WakeProvenance.Verdict.StoppedThenResumed)?.seconds
