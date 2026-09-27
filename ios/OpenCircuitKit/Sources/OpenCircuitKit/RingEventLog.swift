// Ring event log carried by `0x50` frames (docs/PROTOCOL.md §5.5.1) — and the ring's OWN
// activity sessions decoded from it.
//
// WHY THIS EXISTS. The ring decides for itself when the wearer is active: while it is, it stops
// its ~2.5-min unsolicited pushes (the link stays UP — no disconnect, no error), so a suspended
// app is never woken, observes no steps, and receives the whole bout later as back-filled `0x4c`
// history. The step-based activity gate (#144) then sees HR ≥ 100 with no concurrent steps and
// fires "elevated heart rate while inactive" on a walk (tester report 2026-09-27). The ring's own
// start/stop markers for those bouts ride in the `0x50` frame we already receive at the end of
// every drain — this file turns them into intervals the alert gate can honour, which is what the
// official app does ("… while in a non-exercising state", with a separate auto-sport reminder).
//
// Pure (no Apple frameworks) so it unit-tests on the CLI.

import Foundation

/// One 6-byte entry of a `0x50` frame: `[type][value][cursor:4 BE]`, cursor in `Command.syncEpoch`
/// seconds. 🟢 layout (every one of 314 distinct entries across 29 diagnostics bundles / 2 rings
/// divides the payload exactly, no trailer); the meaning of each (type, value) is tagged on
/// `RingEventLog.Kind`.
public struct RingEvent: Equatable, Hashable, Codable, Sendable {
    public let type: UInt8
    public let value: UInt8
    public let cursor: UInt32

    public init(type: UInt8, value: UInt8, cursor: UInt32) {
        self.type = type
        self.value = value
        self.cursor = cursor
    }

    public var date: Date {
        Date(timeIntervalSince1970: TimeInterval(Command.syncEpoch) + TimeInterval(cursor))
    }
}

public enum RingEventLog {
    public static let opcode: UInt8 = 0x50
    public static let entryLength = 6

    /// Activity-session markers (type `0x10`). 🟡 PROBABLE, from the 2026-09-27 AD/Gen2 bundle +
    /// that day's iPhone system log: `0f`→`0a` brackets a user-confirmed 10:40–11:15 walk
    /// (10:52:05 → 11:23:55), and the ring resumed its pushes 8 s after the `0a`; a 09-26 pair
    /// (18:01:40 → 18:22:20) ended its silent gap 1 s after the `0a`. On the AD ring (known
    /// timezone) all 7 `0f`/`0a` pairs are daytime while its `07`/`08` pairs sharing the type sit in
    /// its overnight window (e.g. 00:09 → 08:34) — so `07`/`08` are NOT treated as activity here
    /// (🔴 guess: sleep markers). Both corpus rings emit `0f`/`0a` (21 pairs in all).
    public static let activityType: UInt8 = 0x10
    public static let activityStart: UInt8 = 0x0f
    public static let activityEnd: UInt8 = 0x0a

    /// Longest an UNCLOSED session may run. A POLICY bound, not a measurement: the longest closed
    /// pair in the corpus is 68 min (21 pairs, 2 rings), so 4 h covers a long hike with margin
    /// while capping what a lost end marker can suppress.
    public static let openSessionCap: TimeInterval = 4 * 3600

    /// Decode every event entry of a `0x50` frame, or nil when the frame is not an event list.
    /// The frame has NO XOR trailer (§5.5): the payload after `50 00 00` must be a whole number of
    /// 6-byte entries. Of the legacy shapes `EpochRecord.parseEndOfHistory` handles, the 8- and
    /// 12-byte ones are not whole entries and return nil here; the 9-byte `15 <sub> <cursor>` one IS
    /// a single entry and decodes as one (type `0x15`), which no consumer below reads.
    public static func decode(_ frame: [UInt8]) -> [RingEvent]? {
        guard frame.count >= 3 + entryLength,
              frame[0] == opcode, frame[1] == 0x00, frame[2] == 0x00 else { return nil }
        let payload = frame[3...]
        guard payload.count % entryLength == 0 else { return nil }
        return stride(from: payload.startIndex, to: payload.endIndex, by: entryLength).map { o in
            RingEvent(type: frame[o], value: frame[o + 1],
                      cursor: UInt32(frame[o + 2]) << 24 | UInt32(frame[o + 3]) << 16
                          | UInt32(frame[o + 4]) << 8 | UInt32(frame[o + 5]))
        }
    }

    /// The ring's activity sessions as `[start, end]` intervals, from `0x10` start/end markers.
    ///
    /// A start with no later end is an activity still in progress: it runs to `now`, but never
    /// longer than `openSessionCap` — so a LOST end marker can silence the inactive-HR rule for a
    /// bounded time, not for the ledger's whole retention. An end with
    /// no preceding start (its start fell off the ring's rolling log) is DROPPED — its start cannot
    /// be bounded, and inventing one would suppress alerts on a guess. Duplicate entries (the ring
    /// re-sends its log on every drain) collapse. Only ever used to SUPPRESS, so an unpaired or
    /// unknown marker costs at most a missed suppression, never a missed alert.
    public static func activitySessions(_ events: [RingEvent], now: Date) -> [(Date, Date)] {
        let markers = Set(events.filter { $0.type == activityType
                && ($0.value == activityStart || $0.value == activityEnd) })
            .sorted { $0.cursor < $1.cursor }
        var sessions: [(Date, Date)] = []
        var open: Date?
        for m in markers {
            if m.value == activityStart {
                if open == nil { open = m.date }       // a repeated start keeps the earliest
            } else if let s = open {
                sessions.append((s, m.date))
                open = nil
            }
        }
        if let s = open, s <= now { sessions.append((s, min(now, s.addingTimeInterval(openSessionCap)))) }
        return sessions
    }
}

/// Persisted, de-duplicated activity markers across drains (the ring's log is rolling, and one
/// `0x50` only shows its recent tail). Codable so the app can keep it in UserDefaults — no
/// SwiftData schema change.
public struct RingActivityEventLedger: Codable, Equatable, Sendable {
    public private(set) var events: [RingEvent]

    /// How long a marker is kept. The alert engine looks back at most ~24 h
    /// (`HealthNotificationCenter.instantLookback`), so 48 h keeps every marker it can ask about
    /// with a day of margin, and bounds the blob.
    public static let retention: TimeInterval = 48 * 3600

    public init(events: [RingEvent] = []) { self.events = events }

    /// One ledger for the whole app, not per ring: the alert gate asks "was the WEARER active",
    /// and a marker from any ring they wore answers it.
    public static let defaultsKey = "ring.activityEvents.v1"

    /// The stored ledger, or an empty one when absent or unreadable (an unreadable blob can only
    /// cost a missed suppression — the alert still fires — so it is not worth surfacing).
    public static func load(_ defaults: UserDefaults = .standard) -> RingActivityEventLedger {
        guard let data = defaults.data(forKey: defaultsKey),
              let ledger = try? JSONDecoder().decode(Self.self, from: data) else { return .init() }
        return ledger
    }

    public func save(_ defaults: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(self) { defaults.set(data, forKey: Self.defaultsKey) }
    }

    /// The ring's activity sessions currently on record (`RingEventLog.activitySessions`).
    public func sessions(now: Date) -> [(Date, Date)] {
        RingEventLog.activitySessions(events, now: now)
    }

    /// Merge the activity markers from one decoded frame, dropping duplicates and anything older
    /// than `retention` (or implausibly in the future — the log also carries entries with
    /// nonsensical cursors, e.g. type `0x17` years off, which are filtered by type anyway).
    public mutating func merge(_ incoming: [RingEvent], now: Date) {
        let keep = incoming.filter { $0.type == RingEventLog.activityType
            && ($0.value == RingEventLog.activityStart || $0.value == RingEventLog.activityEnd) }
        let lo = now.addingTimeInterval(-Self.retention)
        let hi = now.addingTimeInterval(3600)
        events = Array(Set(events + keep))
            .filter { $0.date >= lo && $0.date <= hi }
            .sorted { $0.cursor < $1.cursor }
    }
}
