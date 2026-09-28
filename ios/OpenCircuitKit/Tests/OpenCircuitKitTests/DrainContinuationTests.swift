import XCTest
@testable import OpenCircuitKit

/// The half-night-sync rule (2026-09-28): a channel that goes quiet WITHOUT the ring's `0x50` is
/// re-asked while it keeps yielding, and never otherwise.
final class DrainContinuationTests: XCTestCase {

    private func nudge(pages: Bool = true, end: Bool = false, noProgress: Int = 0, thisRound: Int = 0,
                       tick: Int = 10, ceiling: Int = 180, bg: Bool = false, left: TimeInterval = 0,
                       allowed: Bool = true) -> Bool {
        DrainContinuation.shouldNudge(sawPages: pages, sawEndMarker: end, nudgesWithoutProgress: noProgress,
                                      nudgesThisRound: thisRound, tick: tick, ceiling: ceiling,
                                      inBackground: bg, backgroundSecondsRemaining: left, allowed: allowed)
    }

    func testNudgeOnlyAfterPagesWithoutEndMarker() {
        XCTAssertTrue(nudge())
        XCTAssertFalse(nudge(end: true), "the ring said it is done")
        XCTAssertFalse(nudge(pages: false), "an empty channel keeps its own exits")
        XCTAssertFalse(nudge(noProgress: 1), "a nudge that brought nothing is not repeated")
        XCTAssertFalse(nudge(allowed: false), "sport channel / workout-start prime")
    }

    /// Review 2026-09-28 BLOCKER: a ring that answers EVERY ask with exactly one page must not hold a
    /// round open until the ceiling (→ .hardTimeout → .partial → night banked but not staged).
    /// Simulate the loop: 3 quiet ticks, nudge, page lands ~2 ticks later, repeat.
    func testOnePagePerAskRingCannotRunARoundIntoTheCeiling() {
        let ceiling = 180
        var tick = 5, noProgress = 0, thisRound = 0
        while tick < ceiling {
            tick += 3                                                        // quiet exit reached
            guard nudge(noProgress: noProgress, thisRound: thisRound, tick: tick, ceiling: ceiling) else { break }
            thisRound += 1; noProgress = 1
            tick += 2; noProgress = 0                                        // one page answers the ask
        }
        XCTAssertLessThan(tick, ceiling, "the round ends on its quiet exit, not the ceiling")
        XCTAssertEqual(thisRound, DrainContinuation.maxNudgesPerRound)
    }

    func testNoNudgeWithoutHeadroomBeforeTheCeiling() {
        XCTAssertFalse(nudge(tick: 180 - DrainContinuation.nudgeHeadroomTicks, ceiling: 180))
        XCTAssertTrue(nudge(tick: 180 - DrainContinuation.nudgeHeadroomTicks - 1, ceiling: 180))
    }

    /// Review MAJOR: the nudge honours the background window exactly like a reopen.
    func testBackgroundNudgeNeedsWindowLeft() {
        XCTAssertFalse(nudge(bg: true, left: 10))
        XCTAssertTrue(nudge(bg: true, left: 25))
    }

    func testReopenIsNotAllowedForThePrimeOrSport() {
        XCTAssertFalse(DrainContinuation.shouldReopen(exitReason: .quietAfterPages, recordsAdded: 9, round: 0,
                                                      inBackground: false, backgroundSecondsRemaining: 0,
                                                      allowed: false))
    }

    /// Review BLOCKER: the night arrived on the ALL-DAY channel while sleep came back `.empty`.
    func testNightOnTheAllDayChannelRestagesFromTheArchive() {
        XCTAssertEqual(HistoryCommitGate.decide(outcome: .empty, recordsAdded: 0, adoptedRecordCount: 0,
                                                nightRecordsOnOtherChannels: 8), .restageFromArchive)
        XCTAssertEqual(HistoryCommitGate.decide(outcome: .empty, recordsAdded: 0, adoptedRecordCount: 0), .skip,
                       "unchanged without night records")
        XCTAssertEqual(HistoryCommitGate.decide(outcome: .complete, recordsAdded: 3, adoptedRecordCount: 0,
                                                nightRecordsOnOtherChannels: 8), .stage,
                       "a complete sleep channel still stages its own slice")
    }

    /// The tester's four drains each ended `quietAfterPages` with records added — every one would
    /// now have been followed by a reopen in the foreground.
    func testTricklingRoundsReopenInForeground() {
        for added in [9, 3, 3, 8] {
            XCTAssertTrue(DrainContinuation.shouldReopen(exitReason: .quietAfterPages, recordsAdded: added,
                                                         round: 0, inBackground: false,
                                                         backgroundSecondsRemaining: 0))
        }
    }

    func testNeverReopenWhenTheRingSaysDoneOrGivesNothing() {
        let stop: [(HistoryChannelExitReason?, Int)] = [
            (.endMarker, 122),          // the healthy ring: 22 pages, then 0x50
            (.quietAfterPages, 0),      // re-ask answered with nothing
            (.quietNoPages, 0), (.hardTimeout, 5), (.cancelled, 5), (.linkUnusable, 0), (nil, 5),
        ]
        for (reason, added) in stop {
            XCTAssertFalse(DrainContinuation.shouldReopen(exitReason: reason, recordsAdded: added, round: 0,
                                                          inBackground: false, backgroundSecondsRemaining: 0),
                           "\(String(describing: reason)) / \(added)")
        }
    }

    func testRoundsAreBounded() {
        let fg = DrainContinuation.maxReopenRoundsForeground
        XCTAssertTrue(DrainContinuation.shouldReopen(exitReason: .quietAfterPages, recordsAdded: 4, round: fg - 1,
                                                     inBackground: false, backgroundSecondsRemaining: 0))
        XCTAssertFalse(DrainContinuation.shouldReopen(exitReason: .quietAfterPages, recordsAdded: 4, round: fg,
                                                      inBackground: false, backgroundSecondsRemaining: 0))
    }

    func testBackgroundNeedsTimeAndAFewerRounds() {
        let bg = DrainContinuation.maxReopenRoundsBackground
        XCTAssertTrue(DrainContinuation.shouldReopen(exitReason: .quietAfterPages, recordsAdded: 4, round: 0,
                                                     inBackground: true, backgroundSecondsRemaining: 25))
        XCTAssertFalse(DrainContinuation.shouldReopen(exitReason: .quietAfterPages, recordsAdded: 4, round: 0,
                                                      inBackground: true, backgroundSecondsRemaining: 10),
                       "not enough window left — the next wake resumes it")
        XCTAssertFalse(DrainContinuation.shouldReopen(exitReason: .quietAfterPages, recordsAdded: 4, round: bg,
                                                      inBackground: true, backgroundSecondsRemaining: 25))
    }
}
