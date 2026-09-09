// THE "Asleep 1:24 AM–12:27 PM" LINE — and the one night it was lying on.
//
// A Gen 2 Air tester's card read:
//
//     4h 0m asleep
//     4h 21m in bed
//     Asleep 1:24 AM–12:27 PM · 2m to fall asleep
//
// She reported it as "it seems like it's calculating the sleep wrong". The totals were right. The
// CAPTION was wrong: 1:24 AM–12:27 PM is an 11 h 3 m span, and an en-dash between two clock times
// asserts a CONTINUOUS block. The night held a multi-hour hole, so onset was the first asleep epoch
// of the head fragment and wake the last asleep epoch of a fragment near noon, with nothing recorded
// in between.
//
// The card ALREADY knew. `inBedText` suppresses its own clock range on exactly this night — the
// screenshot shows a bare "4h 21m in bed" with no range — because a stitched span that far exceeds
// the summed in-bed cannot be presented as one interval. `coverageHints` applies the same test
// before it will call efficiency meaningful. This caption was the third site of the same rule and
// the only one that never got it, so it printed the 11 h span next to a 4 h total and left the
// wearer to reconcile them. Hence `isContiguous` here: ONE predicate the three callers share, so
// they cannot drift apart again.
//
// THE COPY FOLLOWS `SleepConfidenceCopy`'s RULES, which are load-bearing, not stylistic:
//  1. NAME THE MEASUREMENT, NEVER A CAUSE. "Nothing was recorded" is what the store says. "The ring
//     stopped" is a guess — a stopped recorder, a contended resume pointer, a second app draining
//     the same ring and a removed ring are indistinguishable from the persisted stream.
//  2. STATE THE GAP, NEVER AN INFERRED TOTAL. The gapped line quotes the SPAN (certain: two
//     timestamps) and says part of it holds no records (certain: the span cannot fit the measured
//     in-bed). It does NOT quote a gap duration — the measured in-bed includes any sleep latency,
//     which sits OUTSIDE the asleep window, so `span - measuredInBed` is a bound, not a value, and
//     printing it as a value would be the same over-claim in a new place.
//
// "between X and Y" instead of "X–Y" is the whole fix in one phrase: it bounds the window without
// asserting the wearer slept across all of it.
//
// Pure (no SwiftUI) so the exact rendered sentence unit-tests on the CLI, the same reason
// `SleepConfidenceCopy` and `SleepEditedNightNotice` live here. The one thing it cannot own is the
// CLOCK format — locale- and calendar-dependent — so the caller injects it.

import Foundation

/// The sleep-card caption under the stage legend: when the wearer was asleep, and — when the window
/// cannot be presented as one continuous block — that part of it holds no records.
public enum SleepWindowCaption {

    /// How far a wall-clock span may exceed the summed in-bed time before the night is treated as
    /// STITCHED rather than continuous.
    ///
    /// 15 % is slack for epoch-boundary rounding (150 s cadence) and the awake tail a staging pass
    /// trims, NOT a gap allowance. It is deliberately loose in the safe direction: the predicate is
    /// only allowed to say "contiguous", so a night has to miss by a wide margin before either
    /// caller changes what it prints. On the tester night above the margin is not close — 11 h 3 m
    /// against a 5 h 0 m ceiling.
    ///
    /// The value is unchanged from the two sites that already applied it (`SleepCardView.inBedText`,
    /// `SleepCardView.coverageHints`); this is a consolidation, not a retune.
    public static let contiguousTolerance = 1.15

    /// Can `span` be presented as one continuous recorded interval?
    ///
    /// `measuredInBed` is the GAP-EXCLUDED in-bed total (`SleepStaging.Summary.inBed`) — the sum of
    /// the epochs actually held, which is what the card's own "in bed" figure reports. A span that
    /// exceeds it by more than the tolerance therefore contains ground we hold nothing across.
    ///
    /// Answers `true` when `measuredInBed` is zero or negative: with no basis to test against there
    /// is no positive evidence of a gap, and every caller's default is the plain rendering.
    public static func isContiguous(span: TimeInterval, measuredInBed: TimeInterval) -> Bool {
        guard measuredInBed > 0 else { return true }
        return span <= measuredInBed * contiguousTolerance
    }

    /// Least sleep latency worth printing, and the most that can be a measurement rather than an
    /// artifact of a late-starting archive. Unchanged from the view code this replaces.
    static let minimumLatency: TimeInterval = 60
    static let maximumLatency: TimeInterval = 4 * 3600

    /// The caption, or nil when there is no real asleep window to describe.
    ///
    /// - Parameters:
    ///   - onset: first asleep epoch. nil (a legacy stored row) yields nil — labelling the whole
    ///     bedtime "Asleep" would re-assert the over-count that recording an onset removed.
    ///   - wake: last asleep epoch.
    ///   - inBedStart: start of the in-bed window, for the sleep-latency clause.
    ///   - measuredInBed: gap-excluded in-bed seconds — `SleepStaging.Summary.inBed`.
    ///   - clock: renders an instant as a short local time ("1:24 AM"). Injected so the caption and
    ///     the times printed elsewhere on the card are formatted identically.
    public static func line(onset: Date?,
                            wake: Date?,
                            inBedStart: Date?,
                            measuredInBed: TimeInterval,
                            clock: (Date) -> String) -> String? {
        guard let onset, let wake, wake > onset else { return nil }
        let span = wake.timeIntervalSince(onset)

        guard isContiguous(span: span, measuredInBed: measuredInBed) else {
            // No latency clause here on purpose. A stitched night's head fragment can begin anywhere,
            // so "2m to fall asleep" alongside "part of this was never recorded" reads as a precision
            // the night does not have — and the wearer already has the one number that matters.
            return "Asleep between \(clock(onset)) and \(clock(wake)) · nothing was recorded across "
                + "part of that \(SleepConfidence.approximateDuration(span)) window"
        }

        var parts = ["Asleep \(clock(onset))–\(clock(wake))"]
        if let inBedStart {
            let latency = onset.timeIntervalSince(inBedStart)
            if latency >= minimumLatency, latency < maximumLatency {
                parts.append("\(Int((latency / 60).rounded()))m to fall asleep")
            }
        }
        return parts.joined(separator: " · ")
    }
}
