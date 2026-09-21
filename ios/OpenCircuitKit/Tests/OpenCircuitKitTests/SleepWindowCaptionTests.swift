// THE TESTER NIGHT THE CAPTION WAS LYING ON, pinned to the character.
//
// Gen 2 Air, build 52. Card headline "4h 0m asleep / 4h 21m in bed", caption
// "Asleep 1:24 AM–12:27 PM · 2m to fall asleep". The wearer reported the contradiction herself. The
// span is 11 h 3 m; the measured in-bed is 4 h 21 m; the two cannot both describe one continuous
// interval, and the en-dash claimed they did.
//
// Two things are pinned: WHICH rendering a night gets, and WHAT IT SAYS verbatim. The clock closure
// is a fixed en_US_POSIX formatter so the assertions are locale-independent — the shipped card
// injects the device's own short-time format, which is the only part of the line this file cannot own.

import XCTest
@testable import OpenCircuitKit

final class SleepWindowCaptionTests: XCTestCase {

    private let clock: (Date) -> String = { date in
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "h:mm a"
        return f.string(from: date)
    }

    private func at(_ hour: Int, _ minute: Int) -> Date {
        Date(timeIntervalSince1970: TimeInterval(hour * 3600 + minute * 60))
    }

    private func line(onset: Date?, wake: Date?, inBedStart: Date?, inBed: TimeInterval) -> String? {
        SleepWindowCaption.line(onset: onset, wake: wake, inBedStart: inBedStart,
                                measuredInBed: inBed, clock: clock)
    }

    // MARK: The defect

    /// The reported night. 01:24 → 12:27 is 11 h 3 m of wall clock over 4 h 21 m of records.
    func testStitchedTesterNightDoesNotClaimAContinuousBlock() {
        let text = line(onset: at(1, 24), wake: at(12, 27), inBedStart: at(1, 22),
                        inBed: 4 * 3600 + 21 * 60)
        XCTAssertEqual(text,
                       "Asleep between 1:24 AM and 12:27 PM · nothing was recorded across part of "
                       + "that 11h 3m window")
    }

    /// The en-dash form is what asserts continuity, so its absence is the fix — not a detail of wording.
    func testStitchedNightDropsTheEnDashAndTheLatencyClause() {
        let text = line(onset: at(1, 24), wake: at(12, 27), inBedStart: at(1, 22),
                        inBed: 4 * 3600 + 21 * 60)
        XCTAssertNotNil(text)
        XCTAssertFalse(text!.contains("1:24 AM–12:27 PM"), "an en-dash range asserts one interval")
        XCTAssertFalse(text!.contains("to fall asleep"),
                       "a 2m latency beside an unrecorded hole reads as precision the night lacks")
    }

    // MARK: The ordinary night is untouched

    func testContiguousNightRendersTheRangeAndTheLatency() {
        let text = line(onset: at(0, 24), wake: at(8, 49), inBedStart: at(0, 3),
                        inBed: 8 * 3600 + 30 * 60)
        XCTAssertEqual(text, "Asleep 12:24 AM–8:49 AM · 21m to fall asleep")
    }

    func testContiguousNightWithoutMeasurableLatencyRendersTheRangeAlone() {
        let text = line(onset: at(0, 24), wake: at(8, 49), inBedStart: at(0, 24),
                        inBed: 8 * 3600 + 30 * 60)
        XCTAssertEqual(text, "Asleep 12:24 AM–8:49 AM")
    }

    /// A span inside the 15 % slack is rounding and trimmed awake tail, not a hole.
    func testSpanWithinToleranceStaysContiguous() {
        let text = line(onset: at(0, 0), wake: at(8, 0), inBedStart: nil, inBed: 7 * 3600 + 10 * 60)
        XCTAssertEqual(text, "Asleep 12:00 AM–8:00 AM")
    }

    // MARK: Silence and safe defaults

    func testNoOnsetYieldsNoCaption() {
        XCTAssertNil(line(onset: nil, wake: at(8, 49), inBedStart: at(0, 3), inBed: 8 * 3600))
        XCTAssertNil(line(onset: at(0, 24), wake: nil, inBedStart: at(0, 3), inBed: 8 * 3600))
    }

    func testWakeNotAfterOnsetYieldsNoCaption() {
        XCTAssertNil(line(onset: at(8, 49), wake: at(8, 49), inBedStart: at(0, 3), inBed: 8 * 3600))
    }

    /// With no in-bed basis there is no positive evidence of a gap, so the plain rendering stands.
    /// The predicate is only ever allowed to assert "contiguous"; it may not invent a hole.
    func testNoMeasuredBasisFallsBackToThePlainRendering() {
        let text = line(onset: at(1, 24), wake: at(12, 27), inBedStart: nil, inBed: 0)
        XCTAssertEqual(text, "Asleep 1:24 AM–12:27 PM")
    }

    // MARK: The predicate the three card sites share

    func testContiguityPredicateAgreesWithTheInBedLineOnTheTesterNight() {
        let inBed: TimeInterval = 4 * 3600 + 21 * 60
        // The card suppressed its in-bed clock range on this night; the caption must reach the same
        // verdict from the same numbers, which is the whole reason the predicate is shared.
        XCTAssertFalse(SleepWindowCaption.isContiguous(span: 11 * 3600 + 3 * 60,
                                                       measuredInBed: inBed))
        XCTAssertTrue(SleepWindowCaption.isContiguous(span: inBed, measuredInBed: inBed))
    }

    func testZeroBasisIsTreatedAsContiguous() {
        XCTAssertTrue(SleepWindowCaption.isContiguous(span: 11 * 3600, measuredInBed: 0))
    }
}
