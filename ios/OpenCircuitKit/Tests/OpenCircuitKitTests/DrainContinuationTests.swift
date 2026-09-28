import XCTest
@testable import OpenCircuitKit

/// The half-night-sync rule (2026-09-28): a channel that goes quiet WITHOUT the ring's `0x50` is
/// re-asked while it keeps yielding, and never otherwise.
final class DrainContinuationTests: XCTestCase {

    func testNudgeOnlyAfterPagesWithoutEndMarker() {
        XCTAssertTrue(DrainContinuation.shouldNudge(sawPages: true, sawEndMarker: false, nudgesWithoutProgress: 0))
        XCTAssertFalse(DrainContinuation.shouldNudge(sawPages: true, sawEndMarker: true, nudgesWithoutProgress: 0),
                       "the ring said it is done")
        XCTAssertFalse(DrainContinuation.shouldNudge(sawPages: false, sawEndMarker: false, nudgesWithoutProgress: 0),
                       "an empty channel keeps its own exits")
        XCTAssertFalse(DrainContinuation.shouldNudge(sawPages: true, sawEndMarker: false, nudgesWithoutProgress: 1),
                       "a nudge that brought nothing is not repeated")
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
