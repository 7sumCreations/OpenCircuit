import XCTest
@testable import OpenCircuitKit

/// The `0x50` event log (PROTOCOL.md §5.5.1) and the ring-activity gate it feeds into the
/// elevated-HR-while-inactive alert. The two frames below are the ring's own bytes from the
/// 2026-09-27 AD/Gen2 diagnostics bundle — event markers only, no health values. HR series in the
/// gate tests are SYNTHETIC.
final class RingEventLogTests: XCTestCase {

    /// 2026-09-27T15:24:09Z — the frame that closed the post-walk drain.
    private let walkFrame: [UInt8] = [0x50, 0x00, 0x00,
                                      0x10, 0x0f, 0x0c, 0xad, 0xf5, 0x55,
                                      0x10, 0x0a, 0x0c, 0xad, 0xfc, 0xcb]
    /// 2026-09-26T20:55:25Z — two `0x15` entries then two `0x10` start/end pairs.
    private let eveningFrame: [UInt8] = [0x50, 0x00, 0x00,
                                         0x15, 0x21, 0x0c, 0xac, 0xe3, 0x0a,
                                         0x15, 0x12, 0x0c, 0xac, 0xe3, 0x46,
                                         0x10, 0x0f, 0x0c, 0xac, 0xea, 0x53,
                                         0x10, 0x0a, 0x0c, 0xac, 0xed, 0x8c,
                                         0x10, 0x0f, 0x0c, 0xac, 0xf5, 0x27,
                                         0x10, 0x0a, 0x0c, 0xac, 0xf8, 0x42]

    private func utc(_ s: String) -> Date { ISO8601DateFormatter().date(from: s)! }

    // MARK: decode

    func testDecodesWalkFrameToTheRingsOwnStartAndEnd() throws {
        let events = try XCTUnwrap(RingEventLog.decode(walkFrame))
        XCTAssertEqual(events.map(\.type), [0x10, 0x10])
        XCTAssertEqual(events.map(\.value), [0x0f, 0x0a])
        // 10:52:05 and 11:23:55 EDT — the walk the wearer reported as 10:40–11:15.
        XCTAssertEqual(events[0].date, utc("2026-09-27T14:52:05Z"))
        XCTAssertEqual(events[1].date, utc("2026-09-27T15:23:55Z"))
    }

    func testDecodesMultiEntryFrameAndPairsOnlyActivityMarkers() throws {
        let events = try XCTUnwrap(RingEventLog.decode(eveningFrame))
        XCTAssertEqual(events.count, 6)
        let sessions = RingEventLog.activitySessions(events, now: utc("2026-09-27T00:00:00Z"))
        XCTAssertEqual(sessions.count, 2, "the 0x15 entries are not activity")
        XCTAssertEqual(sessions[0].0, utc("2026-09-26T19:52:51Z"))
        XCTAssertEqual(sessions[0].1, utc("2026-09-26T20:06:36Z"))
        XCTAssertEqual(sessions[1].0, utc("2026-09-26T20:39:03Z"))
        XCTAssertEqual(sessions[1].1, utc("2026-09-26T20:52:18Z"))
    }

    func testRejectsNonEventShapes() {
        XCTAssertNil(RingEventLog.decode([0x50, 0x00, 0x00]), "no entries")
        XCTAssertNil(RingEventLog.decode(Array(walkFrame.dropLast())), "partial entry")
        XCTAssertNil(RingEventLog.decode([0x4c] + walkFrame.dropFirst()), "wrong opcode")
        // The legacy 12-byte cursor report is not a whole number of entries.
        XCTAssertNil(RingEventLog.decode([0x50, 0x00, 0x00, 0x15,
                                          0x0c, 0x22, 0xaa, 0xe4, 0x0c, 0x22, 0xac, 0xb5]))
    }

    // MARK: pairing edge cases

    private func ev(_ v: UInt8, _ cursor: UInt32) -> RingEvent { RingEvent(type: 0x10, value: v, cursor: cursor) }

    func testOpenStartRunsToNowAndOrphanEndIsDropped() {
        let base: UInt32 = 0x0cad_0000
        let now = RingEvent(type: 0, value: 0, cursor: base + 3000).date
        let sessions = RingEventLog.activitySessions(
            [ev(0x0a, base),              // end whose start fell off the log → dropped
             ev(0x0f, base + 1000)],      // still in progress
            now: now)
        XCTAssertEqual(sessions.count, 1)
        XCTAssertEqual(sessions[0].0, ev(0x0f, base + 1000).date)
        XCTAssertEqual(sessions[0].1, now)
    }

    func testOpenSessionIsCappedSoALostEndCannotSilenceForDays() {
        let start = ev(0x0f, 0x0cad_0000)
        let now = start.date.addingTimeInterval(30 * 3600)
        let sessions = RingEventLog.activitySessions([start], now: now)
        XCTAssertEqual(sessions.first?.1, start.date.addingTimeInterval(RingEventLog.openSessionCap))
    }

    func testDuplicateLogResendsCollapse() throws {
        let events = try XCTUnwrap(RingEventLog.decode(walkFrame))
        XCTAssertEqual(RingEventLog.activitySessions(events + events, now: utc("2026-09-28T00:00:00Z")).count, 1)
    }

    func testSleepPairSharingTheTypeIsNotActivity() {
        XCTAssertTrue(RingEventLog.activitySessions([ev(0x07, 100), ev(0x08, 5000)], now: Date()).isEmpty)
    }

    // MARK: ledger

    func testLedgerKeepsOnlyActivityMarkersDedupesAndPrunes() throws {
        var ledger = RingActivityEventLedger()
        let now = utc("2026-09-27T15:30:00Z")
        let walk = try XCTUnwrap(RingEventLog.decodeFrame(walkFrame))
        ledger.merge(walk, ring: "A", now: now)
        ledger.merge(walk, ring: "A", now: now)
        ledger.merge(try XCTUnwrap(RingEventLog.decodeFrame(eveningFrame)), ring: "A", now: now)
        XCTAssertEqual(ledger.events["A"]?.count, 6, "2 walk + 4 evening activity markers; 0x15 dropped; resend deduped")
        XCTAssertEqual(ledger.sessions(now: now).count, 3)
        ledger.merge(RingEventLog.Frame(hiddenCount: 0, events: []), ring: "A",
                     now: now.addingTimeInterval(RingActivityEventLedger.retention - 60 * 60))
        XCTAssertEqual(ledger.events["A"]?.count, 2, "evening markers aged out, the walk's kept")
        let blob = try JSONEncoder().encode(ledger)
        XCTAssertEqual(try JSONDecoder().decode(RingActivityEventLedger.self, from: blob), ledger)
    }

    // MARK: review findings (2026-09-27)

    /// An OVERFLOWED log (§5.5.1): `[2]` counts entries not shown and the frame carries the OLDEST
    /// 40. Seen on both corpus rings (e.g. `50 00 0f` + 40 entries, 243 B). The visible entries are
    /// still decoded, and the overflow is recorded so diagnostics can say the gate is blind.
    func testOverflowedFrameDecodesAndIsRecorded() throws {
        var bytes: [UInt8] = [0x50, 0x00, 0x0f]
        for i in 0 ..< 40 { bytes += [0x10, i.isMultiple(of: 2) ? 0x0f : 0x0a, 0x0c, 0xad, UInt8(i), 0x00] }
        XCTAssertEqual(bytes.count, 243)
        let frame = try XCTUnwrap(RingEventLog.decodeFrame(bytes))
        XCTAssertEqual(frame.hiddenCount, 15)
        XCTAssertEqual(frame.events.count, 40)
        var ledger = RingActivityEventLedger()
        let now = frame.events.last!.date.addingTimeInterval(60)
        ledger.merge(frame, ring: "A", now: now)
        XCTAssertEqual(ledger.overflow["A"]?.hidden, 15)
        ledger.merge(try XCTUnwrap(RingEventLog.decodeFrame(walkFrame)), ring: "A", now: now)
        XCTAssertNil(ledger.overflow["A"], "a cleared log clears the flag")
    }

    /// A start whose end was lost, followed by a later complete pair, must NOT merge into one
    /// 10 h session that silences both HR rules — the first closes at the second start, capped.
    func testRepeatedStartClosesTheEarlierSessionAndEverySessionIsCapped() {
        let t: UInt32 = 0x0cad_0000
        let sessions = RingEventLog.activitySessions(
            [ev(0x0f, t), ev(0x0f, t + 10 * 3600), ev(0x0a, t + 10 * 3600 + 20 * 60)], now: Date())
        XCTAssertEqual(sessions.count, 2)
        for (a, b) in sessions {
            XCTAssertLessThanOrEqual(b.timeIntervalSince(a), RingEventLog.openSessionCap)
        }
        XCTAssertEqual(sessions[1].0, ev(0x0f, t + 10 * 3600).date)
        // A CLOSED but absurdly long pair is capped too.
        let long = RingEventLog.activitySessions([ev(0x0f, t), ev(0x0a, t + 20 * 3600)], now: Date())
        XCTAssertEqual(long.first.map { $0.1.timeIntervalSince($0.0) }, RingEventLog.openSessionCap)
    }

    /// While overflowed the same frozen frame arrives every few minutes: it must not rewrite the
    /// ledger each time. And a ring that never reports again ages out.
    func testRepeatedOverflowIsStableAndRetiredRingsAgeOut() {
        let t: UInt32 = 0x0cad_0000
        let now = ev(0x0f, t).date
        var ledger = RingActivityEventLedger()
        ledger.merge(.init(hiddenCount: 15, events: [ev(0x0f, t)]), ring: "A", now: now)
        let once = ledger
        ledger.merge(.init(hiddenCount: 15, events: [ev(0x0f, t)]), ring: "A", now: now.addingTimeInterval(180))
        XCTAssertEqual(ledger, once)
        ledger.merge(.init(hiddenCount: 0, events: []), ring: "B",
                     now: now.addingTimeInterval(RingActivityEventLedger.retention + 60))
        XCTAssertNil(ledger.events["A"])
        XCTAssertNil(ledger.overflow["A"])
    }

    /// Two rings' markers never pair with each other.
    func testMarkersArePairedPerRing() {
        let t: UInt32 = 0x0cad_0000
        var ledger = RingActivityEventLedger()
        let now = ev(0x0a, t + 7200).date
        ledger.merge(.init(hiddenCount: 0, events: [ev(0x0f, t)]), ring: "A", now: now)            // A still active
        ledger.merge(.init(hiddenCount: 0, events: [ev(0x0f, t + 600), ev(0x0a, t + 1200)]), ring: "B", now: now)
        let sessions = ledger.sessions(now: now)
        XCTAssertEqual(sessions.count, 2)
        XCTAssertTrue(sessions.contains { $0.0 == ev(0x0f, t).date && $0.1 == now }, "A runs open to now")
        XCTAssertTrue(sessions.contains { $0.0 == ev(0x0f, t + 600).date && $0.1 == ev(0x0a, t + 1200).date })
    }

    // MARK: the alert gate (synthetic HR)

    /// A synthetic walk: HR ≥ 100 every 150 s from 10:40 to 11:17:30 EDT — elevated from the first
    /// minute, i.e. the full 12-min recognition lag the real walk showed — no steps observed (the
    /// ring was silent), ring session 10:52:05 → 11:23:55 from `walkFrame`.
    private func walkHR() -> [HRSample] {
        let start = utc("2026-09-27T14:40:00Z")
        return (0 ..< 16).map { HRSample(bpm: 105, start: start.addingTimeInterval(Double($0) * 150)) }
    }

    func testRingActivitySessionSuppressesTheWalkAlarm() throws {
        let thresholds = HealthAlertThresholds()
        // Without the ring's verdict the gate has no evidence and the walk alarms — the bug.
        XCTAssertEqual(HealthAlertEvaluator.evaluate(hr: walkHR(), spo2: [], inactiveHR: walkHR(),
                                                     thresholds: thresholds).map(\.notification),
                       [.elevatedHRInactive])

        let sessions = RingEventLog.activitySessions(try XCTUnwrap(RingEventLog.decode(walkFrame)),
                                                     now: utc("2026-09-27T15:30:00Z"))
        let gated = HealthAlertEvaluator.nonExercising(
            walkHR(), activeIntervals: HealthAlertEvaluator.ringActivityIntervals(sessions))
        // Only the 10:40:00 reading precedes `start − lead` (10:42:05); alone it is no run.
        XCTAssertEqual(gated.map(\.start), [utc("2026-09-27T14:40:00Z")])
        XCTAssertTrue(HealthAlertEvaluator.evaluate(hr: gated, spo2: [], inactiveHR: gated,
                                                    thresholds: thresholds).isEmpty)
    }

    /// The lead is load-bearing whenever HR is up ≥ 10 min before the ring's stamp (the real walk
    /// crossed 100 bpm only ~7 min before it, so it would NOT have alarmed on its head alone).
    func testWithoutTheLeadTheUnrecognisedHeadStillAlarms() throws {
        let sessions = RingEventLog.activitySessions(try XCTUnwrap(RingEventLog.decode(walkFrame)),
                                                     now: utc("2026-09-27T15:30:00Z"))
        let gated = HealthAlertEvaluator.nonExercising(
            walkHR(), activeIntervals: HealthAlertEvaluator.ringActivityIntervals(sessions, lead: 0))
        XCTAssertEqual(HealthAlertEvaluator.elevatedHRInactive(gated, thresholdBpm: 100,
                                                               minDuration: 10 * 60)?.bpm, 105)
    }

    /// Safety: a resting run OUTSIDE any ring session (beyond the lead and the recovery pad) still
    /// fires — the ring's verdict only removes readings it covers.
    func testRestingRunOutsideTheSessionStillAlerts() throws {
        let sessions = RingEventLog.activitySessions(try XCTUnwrap(RingEventLog.decode(walkFrame)),
                                                     now: utc("2026-09-27T20:00:00Z"))
        let restStart = utc("2026-09-27T16:30:00Z")   // 12:30 EDT, an hour after the session
        let rest = (0 ..< 6).map { HRSample(bpm: 110, start: restStart.addingTimeInterval(Double($0) * 150)) }
        let gated = HealthAlertEvaluator.nonExercising(
            walkHR() + rest, activeIntervals: HealthAlertEvaluator.ringActivityIntervals(sessions))
        XCTAssertEqual(Array(gated.dropFirst()), rest, "the whole resting run survives the gate")
        XCTAssertNotNil(HealthAlertEvaluator.elevatedHRInactive(gated, thresholdBpm: 100, minDuration: 10 * 60))
    }
}
