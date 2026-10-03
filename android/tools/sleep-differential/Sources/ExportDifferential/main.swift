// ExportDifferential — runs upstream's export writers over synthetic inputs and writes, into the
// directory given as the only argument:
//
//   inputs.txt                     every case's inputs
//   <case>.samples.csv             ExportEngine.samplesCSV(rows), verbatim (UTF-8, no newline added)
//   <case>.sleep.csv               ExportEngine.sleepCSV(sleep rows), likewise
//   <case>.daily.csv               ExportEngine.dailyCSV(daily rows)
//   <case>.stepSamples.csv         ExportEngine.stepSamplesCSV(step rows)
//   <case>.naps.csv                ExportEngine.napsCSV(nap rows)
//   <case>.daytimeTemperatures.csv ExportEngine.daytimeTemperatureCSV(temperature rows)
//   <case>.historySyncEvidence.csv ExportEngine.historySyncEvidenceCSV(evidence rows)
//   <case>.labels.txt              ExportEngine.sessionID(night:) of every sleep row, then
//                                  ExportEngine.dayStamp(_:) of every daily row, one per line
//   <case>.json                    ExportEngine.toJSON(...) of all the rows — only for cases marked "json"
//   <case>.metadata.csv            ExportEngine.metadataCSV(meta) — schema-3 cases with a metadata block
//   <case>.sleepSessions.csv       ExportEngine.sleepSessionsCSV(sessions) — schema-3 cases
//   <case>.hypnogram.csv           ExportEngine.hypnogramCSV(sessions) — schema-3 cases
//   keyorder.txt                   JSONSerialization's .sortedKeys order of the export's key vocabulary
//                                  and of seeded random printable-ASCII keys, one key per line
// Every case writes every schema-2 CSV (a header alone when it has no rows of that kind) and its
// labels file; a schema-3 case ("v3") also writes the two session CSVs, and the metadata CSV when it
// has a metadata block.
//
// `ExportDifferentialTest` (Kotlin) rebuilds the same rows from inputs.txt, runs the port's writers
// and compares BYTES with the golden files; it sorts the same keys with the port's comparator and
// compares the order. Gradle never runs this program; `regenerate.sh export` does.
//
// Everything is deterministic (SplitMix64 from fixed seeds) and synthetic — no health data. Doubles
// are written as "d" followed by their IEEE-754 bit pattern in 16 lowercase hex digits; a date is the
// double of its seconds since 2001 (Foundation's own form); a string is "x" followed by its UTF-8
// bytes in lowercase hex (so any character survives the line format).
//
// inputs.txt, per case (rows of each kind in order; "-" stands for nil; integers in decimal):
//   case <id> <time zone identifier> <now: date> <json | csvonly> [v3]
//   s <kind: string> <start: date> <end: date> <value: double>                       a sample row
//   sl <night: date> <asleepMin> <deepMin> <lightMin> <remMin> <awakeMin> <efficiency: double>
//      <inBedStart: date|-> <inBedEnd: date|-> <skinTempC: double> <sleepScore> <stressScore>
//      <feelScore> <hrDeep> <hrLight> <hrRem> <hrAwake> <movementLevels: m + "|"-joined>  a sleep row
//   dy <day: date> <steps>                                                           a daily row
//   st <start: date> <end: date> <delta>                                             a step row
//   np <start: date> <end: date> <asleepMin> <isLongNap: 0|1>                        a nap row
//   dt <time: date> <celsius: double>                                                a temperature row
//   ev <capturedAt: date> <ringID: string> <trigger: string> <sleepCommitted: 0|1>
//      <stagedSleepSegments> <mergedRecordCount> <historySampleCount>
//      <rawRecordBlobBase64: string> <nightRowOutcome: string|->                     an evidence row
//   ch <label: string> <channel> <startedAt: date> <finishedAt: date|-> <sawSyncAck: 0|1>
//      <syncAckFlag|-> <sawEmptyHistorySignal: 0|1> <openWriteFailed: 0|1|-> <fetchNudges|->
//      <reopenRound|-> <page4CCount> <page47Count> <page4DCount|-> <sportSampleCount|->
//      <endMarkerCount> <recordsAtStart> <recordsAtEnd> <firstOpcode|-> <lastOpcode|->
//      <exitReason: string|->                     a channel trace of the evidence row above it
//   md <exportedAt: date> <rangeStart: date> <rangeEnd: date> <appVersion: string> <appBuild: string>
//      <deviceModel: string> <osVersion: string> <ringModel: string> <ringFirmware: string>
//      <ringGeneration: string> <ringIdentifier: string> <timeZoneIdentifier: string>
//      <timestampPolicy: string> <timeZoneOffsetSeconds> <schemaVersion>               the metadata block
//   ss <sessionID: string> <night: date> <inBedStart|-> <inBedEnd|-> <sleepOnset|-> <sleepWake|->
//      <isManuallyEdited: 0|1> <recordedInBedStart|-> <recordedInBedEnd|-> <recordedOnset|->
//      <recordedWake|->                           a sleep session; the lines below it up to the next
//                                                 "ss" or "end" are its parts:
//   sm <the 18 fields of an "sl" line>            its summary
//   sg <start: date> <end: date> <stage: string> <provenance: string>                 a hypnogram segment
//   os <avgSpO2: double> <minSpO2: double> <timeBelow90Sec: double> <odi: double> <validWindows>
//   cv <from: date> <to: date> <sample time: date>...      coverage = ExportCoverage.assess(those times)
//   rm <reference: string> <reportedStart: date> <reportedEnd: date> <referenceEnd: date>
//                                                 reference wake measured over the cv line's times
//   ru <reason: string>                           reference wake unavailable
//   ea <asleep: double> <inBed: double> <inBedStart: date> <inBedEnd: date> <lastBefore|->
//      <firstAfter|-> <earliestRetained|-> <durationBasis: string> <measurement after: date>...
//                                                 edge row from SleepConfidence.assess over that window
//   ed <windowStart: date> <windowEnd: date> <bedtimeVerdict: string> <bedtimeGap: double|->
//      <wakeVerdict: string> <wakeGap: double|-> <materialGap: double> <durationBasis: string>
//      <reasons: r + "|"-joined strings>          an edge row built directly
//   end
// (each row is one line; the wrapped lines above are a single line in the file).
// A "csvonly" case holds a value JSONSerialization cannot write (NaN or an infinity): upstream's
// toJSON raises and the process dies there, so no JSON golden exists; the Kotlin port returns null.
//
// Upstream's device-local formatters read Calendar.current, so each case sets NSTimeZone.default to
// its zone first and checks that ExportEngine.localTimeZone followed it (measured on this toolchain:
// Calendar.current honours the override, TimeZone.current does not).

import Foundation
@testable import OpenCircuitKit

// MARK: - Deterministic randomness

struct SplitMix64: RandomNumberGenerator {
    var state: UInt64

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// An integer in `lo...hi`. Modulo bias does not matter here; only determinism does.
    mutating func int(_ lo: Int, _ hi: Int) -> Int { lo + Int(next() % UInt64(hi - lo + 1)) }
}

// MARK: - Canonical rendering

func d(_ x: Double) -> String {
    let h = String(x.bitPattern, radix: 16)
    return "d" + String(repeating: "0", count: 16 - h.count) + h
}
func date(_ t: Date) -> String { d(t.timeIntervalSinceReferenceDate) }
let hexDigits = Array("0123456789abcdef")
func x(_ s: String) -> String { "x" + String(s.utf8.flatMap { [hexDigits[Int($0 >> 4)], hexDigits[Int($0 & 15)]] }) }
func opt<T>(_ v: T?, _ render: (T) -> String) -> String { v.map(render) ?? "-" }
func flag(_ b: Bool) -> String { b ? "1" : "0" }

// MARK: - Cases

struct Case {
    let id: String
    let zone: String
    let now: Date
    let rows: [ExportEngine.SampleRow]
    var sleep: [ExportEngine.SleepRow] = []
    var daily: [ExportEngine.DailyRow] = []
    var steps: [ExportEngine.StepSampleRow] = []
    var naps: [ExportEngine.NapRow] = []
    var temperatures: [ExportEngine.DaytimeTemperatureRow] = []
    var evidence: [ExportEngine.HistorySyncEvidenceRow] = []
    let json: Bool
    /// Schema-3 cases also write the metadata (when present), sessions and hypnogram CSVs.
    var v3 = false
    var meta: ExportEngine.ExportMetadata? = nil
    var sessions: [SessionSpec] = []
}

/// How a session's reference-wake verdict is built: measured over the session's coverage sample times
/// (`ExportReferenceCoverage.assess`), or unavailable with a reason.
enum RefSpec {
    case measured(ExportReferenceCoverage.Reference, reportedStart: Date, reportedEnd: Date, referenceEnd: Date)
    case unavailable(String)
}

/// How a session's edge row is built: through `SleepConfidence.assess` (the app's path), or directly.
enum EdgeSpec {
    case assessed(asleep: Double, inBed: Double, start: Date, end: Date, lastBefore: Date?, firstAfter: Date?, earliest: Date?,
                  basis: String, after: [Date])
    case direct(ExportEngine.SleepEdgeProvenanceRow)
}

/// One sleep session, built by upstream's own constructors from these inputs; the inputs are what
/// inputs.txt records, so the Kotlin side rebuilds the coverage, reference and edge verdicts itself.
struct SessionSpec {
    var id: String
    var night: Date
    var inBedStart: Date? = nil, inBedEnd: Date? = nil, onset: Date? = nil, wake: Date? = nil
    var edited = false
    var recStart: Date? = nil, recEnd: Date? = nil, recOnset: Date? = nil, recWake: Date? = nil
    var summary: ExportEngine.SleepRow
    var segments: [SleepSegment] = []
    var osa: ExportEngine.OSARow? = nil
    var coverage: (from: Date, to: Date, times: [Date])? = nil
    var reference: RefSpec? = nil
    var edge: EdgeSpec? = nil

    func row() -> ExportEngine.SleepSessionRow {
        let cov = coverage.map { ExportCoverage.assess(sampleTimes: $0.times, from: $0.from, to: $0.to) }
        let ref: ExportReferenceCoverage.Outcome? = reference.map {
            switch $0 {
            case .measured(let r, let rs, let re, let end):
                let row = ExportReferenceCoverage.assess(sampleTimes: coverage?.times ?? [], reportedStart: rs, reportedEnd: re,
                                                         referenceEnd: end, reference: r)!
                return .measured(row)
            case .unavailable(let reason):
                return .unavailable(reason: reason)
            }
        }
        let edgeRow: ExportEngine.SleepEdgeProvenanceRow? = edge.map {
            switch $0 {
            case .assessed(let asleep, let inBed, let s, let e, let last, let first, let earliest, let basis, let after):
                let a = SleepConfidence.assess(asleep: asleep, inBed: inBed, coverage: SleepConfidence.Coverage(
                    inBedStart: s, inBedEnd: e, lastMeasurementBeforeStart: last, firstMeasurementAfterEnd: first,
                    measurementsAfterEnd: after, earliestRetainedMeasurement: earliest))
                return ExportEngine.SleepEdgeProvenanceRow(windowStart: s, windowEnd: e, assessment: a, durationBasis: basis)
            case .direct(let r):
                return r
            }
        }
        return ExportEngine.SleepSessionRow(
            sessionID: id, night: night, inBedStart: inBedStart, inBedEnd: inBedEnd, sleepOnset: onset, sleepWake: wake,
            isManuallyEdited: edited, recordedInBedStart: recStart, recordedInBedEnd: recEnd, recordedOnset: recOnset,
            recordedWake: recWake, hypnogram: segments, summary: summary, osa: osa, coverage: cov, referenceCoverage: ref,
            edgeProvenance: edgeRow)
    }
}

/// 2023-11-14T22:13:20Z, and seconds after it.
let base = Date(timeIntervalSince1970: 1_700_000_000)
func at(_ seconds: Double) -> Date { Date(timeIntervalSinceReferenceDate: base.timeIntervalSinceReferenceDate + seconds) }
let kinds = MetricKind.allCases.map(\.rawValue)

func minuteRows(_ values: [Double], kinds ks: [String] = kinds, from start: Double = 0) -> [ExportEngine.SampleRow] {
    values.enumerated().map { i, v in
        ExportEngine.SampleRow(kind: ks[i % ks.count], start: at(start + Double(i) * 60), end: at(start + Double(i) * 60 + 60), value: v)
    }
}

/// The measured number texts: JSON `%.17g` cases and Swift `String(Double)` / `%.0f` cases.
let measuredDoubles: [Double] = [
    72.0, 0.9375, 34.2, 0.1, 1.0 / 3.0, 1e-7, 1e-5, 1e16, 1e21, 123456789012.5, -0.0, 5e-324,
    1.7976931348623157e308, 36.5, 0.30000000000000004, 2.5e-4, 100.0, 1e15, 12345678.9, 1e-4, 0.001, 1e17,
    9007199254740992.0, 9007199254740994.0, 9.5e15, 9999999999999998.0, 0.0001.nextDown, 1.5e-7, 2.5e-5,
    123456789.123, 1e100, 1e-100, 4.35, 2.2250738585072014e-308, 100.25, -1e-5, -9.5e15, 2.5, 3.5, 0.5,
    -3.0, 1e20, 1e300, 123456789012345678.0, 9.999e-5, 1.5, 12.0, 0.0, -72.0, 0.97,
]

/// Hostile kind strings: every CSV quoting trigger, every JSON escape class, non-ASCII text.
let hostileKinds: [String] = [
    "", "a,b", "say \"hi\"", "line\nbreak", "car\rriage", "crlf\r\nrow", " lead", "trail ", " ", "a/b", "back\\slash",
    "nul\u{0}x", "c0 \u{1} \u{8} \u{c} \u{1b} \u{1f}", "tab\there", "del\u{7f}", "c1\u{80}", "bom\u{feff}",
    "ls\u{2028}ps\u{2029}", "caf\u{e9}", "emoji \u{1F600}", "\u{65e5}\u{672c}", "\",\"", "plain",
]

func tieDates(_ rng: inout SplitMix64) -> [Date] {
    // The measured millisecond edges, and seeded doubles at and beside half-millisecond products.
    var out: [Double] = [
        721692800.0005, 721692800.0014999, 721692800.9995, 721692800.0004001, 721692800.9995999,
        -978307200.0015, -978307200.0005, -1078301190.8125,
    ]
    for _ in 0..<40 {
        let ms = Double(rng.int(0, 9_999_999))
        let t = 7.2e8 + Double(rng.int(0, 99_999_999)) + (ms + 0.5) / 1000
        out += [t, t.nextUp, t.nextDown]
    }
    for _ in 0..<12 {
        let t = -978307200.0 - Double(rng.int(1, 999_999_999)) + (Double(rng.int(0, 999)) + 0.5) / 1000
        out += [t, t.nextUp]
    }
    return out.map { Date(timeIntervalSinceReferenceDate: $0) }
}

func sweepDoubles(_ rng: inout SplitMix64) -> [Double] {
    var out: [Double] = []
    // Finite random bit patterns (every exponent).
    while out.count < 160 {
        let v = Double(bitPattern: rng.next())
        if v.isFinite { out.append(v) }
    }
    // Decimal-looking values at many scales (what a sensor or a computed average holds).
    for _ in 0..<160 {
        let mantissa = Double(rng.int(-999_999, 999_999))
        let scale = rng.int(-12, 18)
        out.append(mantissa * pow(10, Double(scale)) / 1000)
    }
    // Whole values: the %.0f branch, up to past 2^53.
    for _ in 0..<60 { out.append(Double(rng.int(-2_000_000, 2_000_000)) * pow(2, Double(rng.int(0, 40)))) }
    // Neighbours of the format boundaries (1e-4, 2^53, 1e16, 1e17, 1).
    for b in [1e-4, 9007199254740992.0, 1e16, 1e17, 1.0] {
        var u = b, w = b
        for _ in 0..<4 { u = u.nextUp; w = w.nextDown; out += [u, w] }
    }
    return out
}

var cases: [Case] = []
cases.append(Case(id: "measured-numbers", zone: "Europe/Amsterdam", now: base, rows: minuteRows(measuredDoubles), json: true))
cases.append(Case(id: "hostile-kinds", zone: "America/St_Johns", now: at(0.0005),
                  rows: minuteRows(hostileKinds.indices.map { Double($0) + 0.25 }, kinds: hostileKinds), json: true))
cases.append(Case(id: "empty", zone: "Asia/Kolkata", now: at(86_400), rows: [], json: true))
var tieRng = SplitMix64(state: 0x4558_5054_0001)
let ties = tieDates(&tieRng)
cases.append(Case(id: "millisecond-ties", zone: "UTC", now: ties[0],
                  rows: ties.indices.map { i in
                      ExportEngine.SampleRow(kind: "heartRate", start: ties[i], end: ties[(i + 1) % ties.count], value: Double(60 + i))
                  }, json: true))
var sweepRng = SplitMix64(state: 0x4558_5054_0002)
cases.append(Case(id: "double-sweep", zone: "Europe/London", now: at(3_600), rows: minuteRows(sweepDoubles(&sweepRng)), json: true))
var nonFinite = Case(id: "non-finite", zone: "UTC", now: base,
                     rows: minuteRows([60, .nan, -.nan, .infinity, -.infinity, Double(bitPattern: 0x7ff4_0000_0000_0000), 61.5]),
                     json: false)
let badDoubles: [Double] = [.nan, -.nan, .infinity, -.infinity, Double(bitPattern: 0x7ff4_0000_0000_0000), 0.5]
nonFinite.sleep = badDoubles.indices.map { k in
    sleepRow(night: at(Double(k) * 86_400), k: k, efficiency: badDoubles[k], skinTempC: badDoubles[(k + 2) % badDoubles.count])
}
nonFinite.temperatures = badDoubles.indices.map { k in ExportEngine.DaytimeTemperatureRow(time: at(Double(k) * 60), celsius: badDoubles[k]) }
cases.append(nonFinite)

// MARK: - Schema-2 cases

/// Fixed-decimal edges: exact binary ties (half to even), values beside a decimal tie, signed zeros.
let efficiencyEdges: [Double] = [
    0.03125, 0.09375, 0.15625, 0.96875, 0.00005, 0.93755, 0.99995, 1.0, -0.03125, -0.0, -0.00001, 5e-324, 0.9375,
    0.1 + 0.2, 0.5,
]
let celsiusEdges: [Double] = [
    36.125, 36.375, 36.625, 36.875, 0.125, -0.125, -0.001, 34.2, 36.005, 36.015, 99.995, 1e20, 36.5, 0.0, -0.0,
]

/// Local midnight of y-m-d in `zone` — the instant a night or a day is bucketed by.
func localMidnight(_ zone: String, _ y: Int, _ m: Int, _ d: Int) -> Date {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: zone)!
    return cal.date(from: DateComponents(year: y, month: m, day: d))!
}

func sleepRow(night: Date, k: Int, efficiency: Double, skinTempC: Double) -> ExportEngine.SleepRow {
    // In bed from two hours before the bucket instant to six and a half after (across a DST change on
    // the nights that have one); every fifth row has no in-bed times, every fifth-plus-three only a start.
    let start: Date? = k % 5 == 4 ? nil : night.addingTimeInterval(-7_200.0005)
    let end: Date? = k % 5 == 4 || k % 5 == 3 ? nil : night.addingTimeInterval(23_400.25)
    if k % 7 == 6 {
        // The short initializer: every defaulted field at its default.
        return ExportEngine.SleepRow(night: night, asleepMin: 300 + k, deepMin: 60, lightMin: 170, remMin: 70, awakeMin: 25,
                                     efficiency: efficiency, inBedStart: start, inBedEnd: end, skinTempC: skinTempC,
                                     sleepScore: 70 + k, stressScore: 30)
    }
    return ExportEngine.SleepRow(night: night, asleepMin: 400 + k, deepMin: 80 + k, lightMin: 190 - k, remMin: 100 + 2 * k,
                                 awakeMin: 20 + k, efficiency: efficiency, inBedStart: start, inBedEnd: end, skinTempC: skinTempC,
                                 sleepScore: 60 + k, stressScore: 45 - k, feelScore: k % 4, hrDeep: 50 + k, hrLight: 58 + k,
                                 hrRem: 62 + k, hrAwake: 70 + k, movementLevels: k == 0 ? [] : [0, 1, k, 2, -k])
}

func trace(_ label: String, channel: UInt8, startedAt: Date, finished: Date?, ack: Bool, ackFlag: UInt8?,
           emptySignal: Bool = false, openFailed: Bool? = nil, nudges: Int? = nil, reopen: Int? = nil,
           p4c: Int, p47: Int, p4d: Int?, sport: Int?, ends: Int, atStart: Int, atEnd: Int,
           first: UInt8?, last: UInt8?, exit: HistoryChannelExitReason?) -> HistoryChannelTrace {
    var t = HistoryChannelTrace(label: label, channel: channel, startedAt: startedAt)
    t.finishedAt = finished
    t.sawSyncAck = ack
    t.syncAckFlag = ackFlag
    t.sawEmptyHistorySignal = emptySignal
    t.openWriteFailed = openFailed
    t.fetchNudges = nudges
    t.reopenRound = reopen
    t.page4CCount = p4c
    t.page47Count = p47
    t.page4DCount = p4d
    t.sportSampleCount = sport
    t.endMarkerCount = ends
    t.recordsAtStart = atStart
    t.recordsAtEnd = atEnd
    t.firstOpcode = first
    t.lastOpcode = last
    t.exitReason = exit
    return t
}

/// One trace per outcome, with the sport counters present, absent, and each absent on its own.
func traceSet(_ s: Date, labels: [String] = ["sleep", "sleep", "ppg", "sport", "sport", "sleep", "sleep", "sleep"]) -> [HistoryChannelTrace] {
    let f = s.addingTimeInterval(42.125)
    return [
        trace(labels[0], channel: 0, startedAt: s, finished: f, ack: true, ackFlag: 1, p4c: 12, p47: 3, p4d: 0, sport: 0,
              ends: 1, atStart: 100, atEnd: 112, first: 0x4c, last: 0x50, exit: .endMarker),
        // Pages, no end marker, a hard timeout: partial on both sides. (Quiet after pages without the
        // end marker is the one partial trace the port classifies differently — its own case below.)
        trace(labels[1], channel: 0, startedAt: s, finished: f, ack: true, ackFlag: 0, p4c: 5, p47: 0, p4d: nil, sport: nil,
              ends: 0, atStart: 10, atEnd: 4, first: 0x4c, last: 0x4c, exit: .hardTimeout),
        trace(labels[2], channel: 1, startedAt: s, finished: f, ack: true, ackFlag: nil, p4c: 0, p47: 7, p4d: 2, sport: nil,
              ends: 1, atStart: 0, atEnd: 0, first: 0x47, last: 0x50, exit: .hardTimeout),
        trace(labels[3], channel: 2, startedAt: s, finished: f, ack: true, ackFlag: 2, p4c: 0, p47: 0, p4d: 7, sport: 210,
              ends: 1, atStart: 0, atEnd: 0, first: 0x4d, last: 0x50, exit: .endMarker),
        trace(labels[4], channel: 2, startedAt: s, finished: f, ack: true, ackFlag: 0, emptySignal: true, p4c: 0, p47: 0, p4d: nil,
              sport: 5, ends: 1, atStart: 3, atEnd: 3, first: 0x50, last: 0x50, exit: .quietNoPages),
        trace(labels[5], channel: 0, startedAt: s, finished: f, ack: false, ackFlag: nil, openFailed: true, p4c: 0, p47: 0, p4d: 0,
              sport: nil, ends: 0, atStart: 0, atEnd: 0, first: nil, last: nil, exit: .linkUnusable),
        trace(labels[6], channel: 0, startedAt: s, finished: f, ack: false, ackFlag: nil, openFailed: false, p4c: 0, p47: 0, p4d: 0,
              sport: 0, ends: 0, atStart: 0, atEnd: 0, first: nil, last: nil, exit: .cancelled),
        trace(labels[7], channel: 0, startedAt: s, finished: nil, ack: false, ackFlag: nil, nudges: 3, reopen: 1, p4c: 0, p47: 0,
              p4d: 0, sport: 0, ends: 0, atStart: 7, atEnd: 7, first: nil, last: nil, exit: nil),
    ]
}

/// Every schema-2 row kind around the given local days of `zone`.
func schema2Case(_ id: String, zone: String, days: [(Int, Int, Int)], now: Date) -> Case {
    let midnights = days.map { localMidnight(zone, $0.0, $0.1, $0.2) }
    var c = Case(id: id, zone: zone, now: now,
                 rows: minuteRows([72, 0.98, 14.5, 36.25], from: midnights[0].timeIntervalSince(base) + 3_600), json: true)
    c.sleep = efficiencyEdges.indices.map { k in
        sleepRow(night: midnights[k % midnights.count], k: k, efficiency: efficiencyEdges[k], skinTempC: celsiusEdges[k])
    }
    c.daily = midnights.enumerated().map { i, m in ExportEngine.DailyRow(day: m, steps: [0, 8_000, 123_456, 7, 1][i % 5]) }
    c.steps = midnights.flatMap { m in
        [0, 123, 4_711].enumerated().map { i, delta in
            ExportEngine.StepSampleRow(start: m.addingTimeInterval(28_800 + Double(i) * 900), end: m.addingTimeInterval(29_700 + Double(i) * 900), delta: delta)
        }
    }
    c.naps = midnights.enumerated().map { i, m in
        ExportEngine.NapRow(start: m.addingTimeInterval(46_800.0005), end: m.addingTimeInterval(48_300 + Double(i) * 1_800),
                            asleepMin: 20 + 30 * i, isLongNap: i % 2 == 1)
    }
    c.temperatures = celsiusEdges.indices.map { k in
        ExportEngine.DaytimeTemperatureRow(time: midnights[k % midnights.count].addingTimeInterval(32_400 + Double(k) * 60.0015), celsius: celsiusEdges[k])
    }
    c.evidence = midnights.enumerated().map { i, m in
        ExportEngine.HistorySyncEvidenceRow(
            capturedAt: m.addingTimeInterval(25_200.9995), ringID: "ring-\(i)", trigger: ["manual", "auto", "background"][i % 3],
            sleepCommitted: i % 2 == 0, stagedSleepSegments: 4 * i, mergedRecordCount: 8 + i, historySampleCount: 10 * i,
            rawRecordBlobBase64: i % 3 == 2 ? "" : "AQID", channels: i % 3 == 1 ? [] : traceSet(m.addingTimeInterval(25_000)),
            nightRowOutcome: i % 2 == 0 ? SleepPersistOutcome.allCases[i % SleepPersistOutcome.allCases.count].rawValue : nil)
    }
    return c
}

// Three zones — a whole-hour offset with DST, a half-hour offset without, a half-hour negative
// offset with DST — each across both of its DST changes where it has them.
cases.append(schema2Case("v2-amsterdam", zone: "Europe/Amsterdam",
                         days: [(2023, 10, 28), (2023, 10, 29), (2023, 10, 30), (2024, 3, 30), (2024, 3, 31), (2024, 4, 1)],
                         now: localMidnight("Europe/Amsterdam", 2024, 4, 2)))
var kolkata = schema2Case("v2-kolkata", zone: "Asia/Kolkata", days: [(2023, 11, 13), (2023, 11, 14), (2023, 11, 15)],
                          now: at(250_000.5))
// Half a millisecond before a local midnight: the label carries into the next day.
kolkata.daily.append(ExportEngine.DailyRow(day: localMidnight("Asia/Kolkata", 2023, 11, 16).addingTimeInterval(-0.0005), steps: 99))
cases.append(kolkata)
cases.append(schema2Case("v2-st-johns", zone: "America/St_Johns",
                         days: [(2023, 11, 4), (2023, 11, 5), (2023, 11, 6), (2024, 3, 9), (2024, 3, 10), (2024, 3, 11)],
                         now: localMidnight("America/St_Johns", 2024, 3, 12)))

// Hostile text in every free-form evidence column and in the channel labels; 64-bit extremes in
// every Int of the new rows (a trace's counts stay inside 32 bits, the port's type for them).
var hostile = schema2Case("v2-hostile", zone: "UTC", days: [(2023, 11, 14), (2023, 11, 15)], now: base)
hostile.evidence = hostileKinds.indices.map { i in
    ExportEngine.HistorySyncEvidenceRow(
        capturedAt: at(Double(i) * 60), ringID: hostileKinds[i], trigger: hostileKinds[(i + 1) % hostileKinds.count],
        sleepCommitted: i % 2 == 1, stagedSleepSegments: [Int.max, Int.min, 0, -1][i % 4], mergedRecordCount: Int.min + i,
        historySampleCount: Int.max - i, rawRecordBlobBase64: hostileKinds[(i + 2) % hostileKinds.count],
        channels: i % 4 == 0 ? traceSet(at(Double(i)), labels: (0..<8).map { hostileKinds[(i + $0) % hostileKinds.count] }) : [],
        nightRowOutcome: i % 3 == 0 ? nil : hostileKinds[(i + 3) % hostileKinds.count])
}
hostile.sleep.append(ExportEngine.SleepRow(night: at(0), asleepMin: Int.max, deepMin: Int.min, lightMin: -1, remMin: 0, awakeMin: Int.max,
                                           efficiency: 0.5, skinTempC: 36.0, sleepScore: Int.min, stressScore: Int.max, feelScore: Int.min,
                                           hrDeep: Int.max, hrLight: Int.min, hrRem: -42, hrAwake: 42, movementLevels: [Int.max, Int.min, -1, 0]))
hostile.daily.append(ExportEngine.DailyRow(day: at(0), steps: Int.min))
hostile.steps.append(ExportEngine.StepSampleRow(start: at(0), end: at(1), delta: Int.max))
hostile.naps.append(ExportEngine.NapRow(start: at(0), end: at(1), asleepMin: Int.min, isLongNap: true))
let maxTrace = trace("max", channel: 255, startedAt: at(0), finished: at(1), ack: true, ackFlag: 255, nudges: Int(Int32.max),
                     reopen: Int(Int32.min), p4c: Int(Int32.max), p47: Int(Int32.max), p4d: Int(Int32.max), sport: Int(Int32.min),
                     ends: Int(Int32.max), atStart: 0, atEnd: Int(Int32.max), first: 0, last: 255, exit: .endMarker)
hostile.evidence.append(ExportEngine.HistorySyncEvidenceRow(
    capturedAt: at(0), ringID: "max", trigger: "max", sleepCommitted: true, stagedSleepSegments: 1, mergedRecordCount: 1,
    historySampleCount: 1, rawRecordBlobBase64: "", channels: [maxTrace], nightRowOutcome: ""))
cases.append(hostile)

// A comma, a quote or an edge space carrying a combining mark, a joiner, a variation selector or a
// keycap: upstream's csvField tests those on Swift Characters and misses them (the port quotes them;
// its PORTING.md D-134 — the Kotlin test lists the CSV outputs of this case as that divergence).
let graphemeTriggers: [String] = [
    "a,\u{301}b", "a,\u{200d}b", "a,\u{fe0f}b", "a,\u{20e3}", "a\"\u{301}b", "q\"\u{301}\"x", " \u{301}lead", " \u{308}",
]
var grapheme = Case(id: "v2-grapheme", zone: "UTC", now: base,
                    rows: minuteRows(graphemeTriggers.indices.map { Double($0) }, kinds: graphemeTriggers), json: true)
grapheme.evidence = graphemeTriggers.indices.map { i in
    ExportEngine.HistorySyncEvidenceRow(
        capturedAt: at(Double(i) * 60), ringID: graphemeTriggers[i], trigger: "manual", sleepCommitted: true,
        stagedSleepSegments: 1, mergedRecordCount: 2, historySampleCount: 3, rawRecordBlobBase64: "AQID", channels: [],
        nightRowOutcome: nil)
}
cases.append(grapheme)

// Epoch pages, then quiet, with no end marker: upstream's HistoryChannelTrace calls that complete,
// the port partial (its PORTING.md D-43, from the history-sync port) — so this case's evidence CSV
// and JSON are listed as that divergence in the Kotlin test, and no other case holds such a trace.
var quietAfterPages = Case(id: "v2-quiet-after-pages", zone: "UTC", now: base, rows: [], json: true)
quietAfterPages.evidence = [ExportEngine.HistorySyncEvidenceRow(
    capturedAt: base, ringID: "ring-1", trigger: "manual", sleepCommitted: false, stagedSleepSegments: 0, mergedRecordCount: 6,
    historySampleCount: 6, rawRecordBlobBase64: "AQID",
    channels: [trace("sleep", channel: 0, startedAt: base, finished: at(30), ack: true, ackFlag: 0, p4c: 4, p47: 0, p4d: 0, sport: 0,
                     ends: 0, atStart: 2, atEnd: 8, first: 0x82, last: 0x4c, exit: .quietAfterPages)],
    nightRowOutcome: nil)]
cases.append(quietAfterPages)

// MARK: - Schema-3 cases

/// The offset `ExportEngine.offsetISO8601` prints for `t` in `zone`, read back from the text — the
/// offset a metadata block must declare so that the declared zone is the printed one.
func printedOffsetSeconds(_ t: Date, _ zone: TimeZone) -> Int {
    let text = ExportEngine.offsetISO8601(t, timeZone: zone)
    if text.hasSuffix("Z") { return 0 }
    let suffix = text.suffix(6) // ±hh:mm (no export zone below has a seconds offset)
    let sign = suffix.first == "-" ? -1 : 1
    let parts = suffix.dropFirst().split(separator: ":").map { Int($0)! }
    return sign * (parts[0] * 3600 + parts[1] * 60)
}

func metadata(_ zone: String, exportedAt: Date, rangeStart: Date, rangeEnd: Date, ringModel: String = "RingConn Gen2") -> ExportEngine.ExportMetadata {
    let tz = TimeZone(identifier: zone)!
    return ExportEngine.ExportMetadata(
        exportedAt: exportedAt, rangeStart: rangeStart, rangeEnd: rangeEnd,
        appVersion: "1.0", appBuild: "37", deviceModel: "iPhone15,2", osVersion: "18.5",
        ringModel: ringModel, ringFirmware: "FR02.018", ringGeneration: "Gen 2",
        ringIdentifier: "1E2E3E4E-0000-0000-0000-000000000001",
        timeZoneIdentifier: tz.identifier, timeZoneOffsetSeconds: printedOffsetSeconds(exportedAt, tz))
}

func summary(_ night: Date, k: Int, start: Date?, end: Date?) -> ExportEngine.SleepRow {
    ExportEngine.SleepRow(night: night, asleepMin: 400 + k, deepMin: 80 + k, lightMin: 190 - k, remMin: 100 + k, awakeMin: 20 + k,
                          efficiency: [0.93755, 0.03125, 0.9375, 0.5, 0.96875][k % 5], inBedStart: start, inBedEnd: end,
                          skinTempC: [36.125, 36.375, 0.0, -0.001, 34.2][k % 5], sleepScore: 60 + k, stressScore: 45 - k,
                          feelScore: k % 4, hrDeep: 50 + k, hrLight: 58 + k, hrRem: 62 + k, hrAwake: 70 + k, movementLevels: [0, k, 2])
}

/// Millisecond-stamped epoch times from `from`, every 150 s plus `jitter` times a cycling 0…6 (so the
/// instants carry different fractions of a second, as stored rows do — a constant offset would make every
/// gap a whole number of seconds in both arithmetics), with the given epoch index ranges left out.
func epochTimes(_ from: Date, count: Int, holes: [Range<Int>], jitter: Double) -> [Date] {
    (0..<count).filter { i in !holes.contains { $0.contains(i) } }.map { from.addingTimeInterval(Double($0) * 150 + jitter * Double($0 % 7)) }
}

/// Five kinds of night around the local midnight `night` (variant `k % 5`), each starting two hours
/// before it at a millisecond-stamped instant:
///   0 — unedited, a staged night with its envelope; OSA measured; coverage with two holes; reference
///       wake measured past the reported end; edges assessed with a gap on both sides.
///   1 — edited (recorded instants present), every provenance in the hypnogram, asserted envelopes
///       (efficiency withheld); OSA with no valid window; reference unavailable; a direct edge row
///       with a measured 0 s bedtime gap and no wake gap, computed on the edited totals.
///   2 — an envelope-only hypnogram; coverage without holes; reference wake "so far", before the
///       reported end (negative); no edges.
///   3 — no timeline, no clock times, nothing measured.
///   4 — a stitched night (two envelopes), partly asserted with enough covered ground to publish
///       efficiency; OSA with a negative window count; edges assessed and both witnessed.
func session(_ night: Date, k: Int, zone: TimeZone) -> SessionSpec {
    let label = "night-" + {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = zone
        return f.string(from: night)
    }()
    let s = night.addingTimeInterval(-7_200.580)
    let e = s.addingTimeInterval(25_200.913)
    switch k % 5 {
    case 0:
        var spec = SessionSpec(id: label, night: night, inBedStart: s, inBedEnd: e, onset: s.addingTimeInterval(900.25),
                               wake: e, summary: summary(night, k: k, start: s, end: e))
        spec.segments = [
            SleepSegment(start: s, end: e, stage: .inBed),
            SleepSegment(start: s, end: s.addingTimeInterval(900.25), stage: .awake),
            SleepSegment(start: s.addingTimeInterval(900.25), end: s.addingTimeInterval(9_000.5), stage: .asleepCore),
            SleepSegment(start: s.addingTimeInterval(9_000.5), end: s.addingTimeInterval(12_600.125), stage: .asleepDeep),
            SleepSegment(start: s.addingTimeInterval(12_600.125), end: s.addingTimeInterval(18_000.75), stage: .asleepREM),
            SleepSegment(start: s.addingTimeInterval(18_000.75), end: e, stage: .asleepCore),
        ]
        spec.osa = ExportEngine.OSARow(avgSpO2: 95.4, minSpO2: 88.0, timeBelow90Sec: 312.5, odi: 4.25, validWindows: 96)
        spec.coverage = (s, e, epochTimes(s, count: 168, holes: [20..<34, 130..<140], jitter: 0.337))
        spec.reference = .measured(.manualScheduleWake, reportedStart: s, reportedEnd: e, referenceEnd: e.addingTimeInterval(3_600.457))
        spec.edge = .assessed(asleep: 5.5 * 3600, inBed: 7 * 3600, start: s, end: e, lastBefore: s.addingTimeInterval(-1_000.457),
                              firstAfter: e.addingTimeInterval(14_399.543), earliest: s.addingTimeInterval(-7 * 86_400), basis: "recorded",
                              after: [e.addingTimeInterval(60.25), e.addingTimeInterval(14_399.543), e.addingTimeInterval(14_549.1)])
        return spec
    case 1:
        let m = s.addingTimeInterval(7_000.333)
        var spec = SessionSpec(id: label, night: night, inBedStart: s, inBedEnd: e, onset: s.addingTimeInterval(1_200), wake: e.addingTimeInterval(-600),
                               edited: true, recStart: s.addingTimeInterval(600.5), recEnd: e.addingTimeInterval(-1_800.25),
                               recOnset: nil, recWake: e.addingTimeInterval(-1_800.25), summary: summary(night, k: k, start: s, end: e))
        spec.segments = [
            SleepSegment(start: s, end: m, stage: .inBed),
            SleepSegment(start: m, end: e, stage: .inBed, provenance: .asserted),
            SleepSegment(start: s, end: s.addingTimeInterval(1_200), stage: .awake, provenance: .assertedOverMeasured),
            SleepSegment(start: s.addingTimeInterval(1_200), end: m, stage: .asleepCore),
            SleepSegment(start: m, end: m.addingTimeInterval(3_000.5), stage: .asleepCore, provenance: .asserted),
            SleepSegment(start: m.addingTimeInterval(3_000.5), end: e.addingTimeInterval(-600), stage: .asleepREM, provenance: .assertedCoverageUnknown),
            SleepSegment(start: e.addingTimeInterval(-600), end: e, stage: .awake, provenance: .asserted),
        ]
        spec.osa = ExportEngine.OSARow(avgSpO2: 0, minSpO2: 0, timeBelow90Sec: 0, odi: 0, validWindows: 0)
        spec.reference = .unavailable(ExportReferenceCoverage.Outcome.noManualSleepSchedule)
        spec.edge = .direct(ExportEngine.SleepEdgeProvenanceRow(
            windowStart: s.addingTimeInterval(600.5), windowEnd: e.addingTimeInterval(-1_800.25), bedtimeVerdict: "resumedAfterGap",
            bedtimeGapSeconds: 0, wakeVerdict: "unknown", wakeGapSeconds: nil, reasons: ["durationLikelyHigh", "noRecordingAfterWake"],
            materialGapSeconds: 1_800, durationBasis: ExportEngine.SleepEdgeProvenanceRow.durationBasisEdited))
        return spec
    case 2:
        var spec = SessionSpec(id: label, night: night, inBedStart: s, inBedEnd: e, onset: nil, wake: nil, summary: summary(night, k: k, start: s, end: nil))
        spec.segments = [SleepSegment(start: s, end: e, stage: .inBed)]
        spec.coverage = (s, e, epochTimes(s, count: 169, holes: [], jitter: 0.0005))
        spec.reference = .measured(.manualScheduleWakeSoFar, reportedStart: s, reportedEnd: e, referenceEnd: e.addingTimeInterval(-1_200.75))
        return spec
    case 3:
        return SessionSpec(id: label, night: night, summary: summary(night, k: k, start: nil, end: nil))
    default:
        let f2 = s.addingTimeInterval(15_000)
        var spec = SessionSpec(id: label, night: night, inBedStart: s, inBedEnd: f2.addingTimeInterval(5_000.2), onset: s,
                               wake: f2.addingTimeInterval(5_000.2), summary: summary(night, k: k, start: s, end: f2.addingTimeInterval(5_000.2)))
        spec.segments = [
            SleepSegment(start: s, end: s.addingTimeInterval(12_000), stage: .inBed),
            SleepSegment(start: s, end: s.addingTimeInterval(11_000.4), stage: .asleepCore),
            SleepSegment(start: s.addingTimeInterval(11_000.4), end: s.addingTimeInterval(12_000), stage: .awake),
            SleepSegment(start: f2, end: f2.addingTimeInterval(5_000.2), stage: .inBed, provenance: .asserted),
            SleepSegment(start: f2, end: f2.addingTimeInterval(5_000.2), stage: .asleepCore, provenance: .asserted),
        ]
        spec.osa = ExportEngine.OSARow(avgSpO2: 93.125, minSpO2: 0, timeBelow90Sec: 0.05, odi: 0, validWindows: -1)
        let end = f2.addingTimeInterval(5_000.2)
        spec.edge = .assessed(asleep: 4 * 3600, inBed: 4.5 * 3600, start: s, end: end, lastBefore: s.addingTimeInterval(-100.5),
                              firstAfter: end.addingTimeInterval(60.125), earliest: s.addingTimeInterval(-86_400), basis: "recorded",
                              after: (0..<20).map { end.addingTimeInterval(60.125 + Double($0) * 150) })
        return spec
    }
}

/// A schema-3 case: sessions on the given local nights of `zone`, variants rotating, plus a metadata block.
func schema3Case(_ id: String, zone: String, nights: [(Int, Int, Int)], firstVariant: Int, exportedAt: Date?) -> Case {
    let tz = TimeZone(identifier: zone)!
    let midnights = nights.map { localMidnight(zone, $0.0, $0.1, $0.2) }
    let now = exportedAt ?? midnights.last!.addingTimeInterval(36_000.0005)
    var c = Case(id: id, zone: zone, now: now, rows: [], json: true)
    c.v3 = true
    c.meta = metadata(zone, exportedAt: now, rangeStart: midnights.first!.addingTimeInterval(-86_400.25), rangeEnd: now)
    c.sessions = midnights.enumerated().map { i, m in session(m, k: firstVariant + i, zone: tz) }
    return c
}

// A half-hour zone without DST, every variant once.
cases.append(schema3Case("v3-kolkata", zone: "Asia/Kolkata",
                         nights: [(2025, 8, 10), (2025, 8, 11), (2025, 8, 12), (2025, 8, 13), (2025, 8, 14)], firstVariant: 0, exportedAt: nil))
// A half-hour negative zone across its spring change, every variant once more, starting elsewhere.
cases.append(schema3Case("v3-st-johns", zone: "America/St_Johns",
                         nights: [(2024, 3, 9), (2024, 3, 10), (2024, 3, 11), (2024, 3, 12), (2024, 3, 13)], firstVariant: 2, exportedAt: nil))
// Exported half a millisecond before Amsterdam's spring change: the timestamp prints as the change and
// the metadata declares the printed offset. One session (no timeline); the schema-2 rows of a day.
var amsterdamEdge = schema3Case("v3-amsterdam-dst-edge", zone: "Europe/Amsterdam", nights: [(2024, 3, 30)], firstVariant: 3,
                                exportedAt: Date(timeIntervalSince1970: 1_711_846_799.9995))
amsterdamEdge.sleep = [sleepRow(night: localMidnight("Europe/Amsterdam", 2024, 3, 30), k: 0, efficiency: 0.9375, skinTempC: 36.5)]
cases.append(amsterdamEdge)
// Metadata with nothing else, and nothing at all: the schema-3 additions must leave every other byte alone.
var metaOnly = Case(id: "v3-meta-only", zone: "Europe/London", now: base, rows: minuteRows([72, 0.98]), json: true)
metaOnly.v3 = true
metaOnly.meta = metadata("Europe/London", exportedAt: base, rangeStart: base.addingTimeInterval(-86_400), rangeEnd: base, ringModel: "")
cases.append(metaOnly)

// MARK: - Key order

/// The export's JSON key vocabulary: every key the full schema can emit (161).
let vocabulary: [String] = [
    "activeEnergy", "archiveRecordCount", "asleepMin", "assertedAsleepSec", "assertedAwakeSec", "assertedOverMeasuredAsleepSec",
    "assertedOverMeasuredAwakeSec", "avgSpO2", "awakeMin", "bedtimeGapSeconds", "bedtimeVerdict", "beyondReportedEndSeconds",
    "capturedAt", "celsius", "channel", "channels", "coverage", "coverageFraction",
    "coverageToReference", "coverageToReferenceWake", "coverageUnknownAsleepSec", "coverageUnknownAwakeSec", "coverageUnknownInBedSec", "coverageWithinReportedWindow",
    "coveredInBedSec", "daily", "day", "daytimeTemperatures", "deepMin", "delta",
    "distance", "durationBasis", "durationSec", "edgeProvenance", "efficiency", "end",
    "endMarkerCount", "epochArchive", "epochArchive.evidenceBlobCoverage", "evidenceBlobCoverage", "evidenceRecordCount", "exerciseMinutes",
    "exitReason", "expectedSamples", "exportedAt", "exportRange", "feelScore", "finishedAt",
    "firstEpoch", "firstOpcode", "gaps", "heartRate", "historySampleCount", "historySyncEvidence",
    "hrAwake", "hrDeep", "hrLight", "hrRem", "hrvSDNN", "hypnogram",
    "hypnogramProvenance", "inBedEnd", "inBedStart", "isComplete", "isLongNap", "isManuallyEdited",
    "kind", "label", "lastEpoch", "lastOpcode", "lightMin", "longestGapSeconds",
    "longestMissingRunSeconds", "longestUnmeasuredGapSec", "materialGapSeconds", "measuredAsleepSec", "measuredAwakeSec", "measuredEfficiency",
    "mergedRecordCount", "meta", "minSpO2", "missingFromEvidenceCount", "movementLevels", "naps",
    "night", "nightRowOutcome", "notes", "observedSamples", "odi", "osa",
    "osaAvgSpO2", "osaMinSpO2", "osaODI", "osaTimeBelow90Sec", "outcome", "page47Count",
    "page4CCount", "page4DCount", "provenance", "provenanceSummary", "rawRecordBlobBase64", "reasons",
    "recordCount", "recorded", "recordsAdded", "recordsAtEnd", "recordsAtStart", "recordsBase64",
    "reference", "referenceCoverage", "referenceEnd", "remMin", "respiratoryRate", "restingHeartRate",
    "ringID", "ringIdentity", "samples", "sawSyncAck", "schemaVersion", "scorable",
    "seconds", "sessionID", "skinTempC", "skinTemperature", "sleep", "sleepCommitted",
    "sleepOnset", "sleepScore", "sleepSessions", "sleepSessions.coverage", "sleepSessions.edgeProvenance", "sleepSessions.hypnogram",
    "sleepSessions.osa", "sleepSessions.referenceCoverage", "sleepSessions.summary", "sleepStages", "sleepWake", "spo2",
    "sportSampleCount", "stage", "stagedSleepSegments", "start", "startedAt", "steps",
    "stepSamples", "stressScore", "summary", "syncAckFlag", "temperature", "time",
    "timeBelow90Sec", "timeZoneOffsetSeconds", "trigger", "unavailableReason", "units", "validWindows",
    "value", "wakeGapSeconds", "wakeVerdict", "windowEnd", "windowStart",
]

/// The keys of a compact `.sortedKeys` object, in the order JSONSerialization wrote them.
func writtenKeyOrder(_ keys: [String]) -> [String] {
    var dict: [String: Any] = [:]
    for k in keys { dict[k] = 0 }
    let text = Array(String(data: try! JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys]), encoding: .utf8)!.unicodeScalars)
    var out: [String] = []
    var i = 1 // past "{"
    while i < text.count && text[i] != "}" {
        precondition(text[i] == "\"")
        i += 1
        var key = String.UnicodeScalarView()
        while text[i] != "\"" {
            if text[i] == "\\" {
                i += 1
                switch text[i] {
                case "b": key.append("\u{8}")
                case "f": key.append("\u{c}")
                case "n": key.append("\n")
                case "r": key.append("\r")
                case "t": key.append("\t")
                case "u":
                    var v: UInt32 = 0
                    for k in 1...4 { v = v * 16 + UInt32(String(text[i + k]), radix: 16)! }
                    key.append(Unicode.Scalar(v)!)
                    i += 4
                default: key.append(text[i]) // \" \\ \/
                }
            } else {
                key.append(text[i])
            }
            i += 1
        }
        out.append(String(key))
        i += 1 // closing quote
        precondition(text[i] == ":")
        while text[i] != "," && text[i] != "}" { i += 1 }
        if text[i] == "," { i += 1 }
    }
    precondition(out.count == keys.count, "key order lost a key")
    return out
}

precondition(vocabulary.count == 161 && Set(vocabulary).count == 161)
var keyRng = SplitMix64(state: 0x4558_5054_0003)
var randomKeys = Set<String>()
while randomKeys.count < 2_000 {
    let n = keyRng.int(1, 6)
    randomKeys.insert(String(String.UnicodeScalarView((0..<n).map { _ in Unicode.Scalar(UInt8(keyRng.int(0x20, 0x7e))) })))
}
// A Set's iteration order is seeded per process; sort first so the input order never matters.
let randomKeyList = randomKeys.sorted()

// MARK: - Output

let args = CommandLine.arguments
guard args.count == 2 else {
    FileHandle.standardError.write("usage: ExportDifferential <output directory>\n".data(using: .utf8)!)
    exit(2)
}
let outDir = URL(fileURLWithPath: args[1], isDirectory: true)

func writeText(_ text: String, _ file: String) {
    do {
        try Data(text.utf8).write(to: outDir.appendingPathComponent(file), options: .atomic)
    } catch {
        FileHandle.standardError.write("write failed: \(error)\n".data(using: .utf8)!)
        exit(1)
    }
}

/// A sleep row's fields after the line kind ("sl", or "sm" for a session's summary).
func sleepFields(_ r: ExportEngine.SleepRow) -> String {
    let ints = [r.asleepMin, r.deepMin, r.lightMin, r.remMin, r.awakeMin].map(String.init).joined(separator: " ")
    let scores = [r.sleepScore, r.stressScore, r.feelScore, r.hrDeep, r.hrLight, r.hrRem, r.hrAwake].map(String.init).joined(separator: " ")
    return "\(date(r.night)) \(ints) \(d(r.efficiency)) \(opt(r.inBedStart, date)) \(opt(r.inBedEnd, date)) "
        + "\(d(r.skinTempC)) \(scores) m\(r.movementLevels.map(String.init).joined(separator: "|"))"
}

/// A session's lines: its head, its summary, then whichever of its parts it has.
func sessionLines(_ s: SessionSpec) -> [String] {
    var out = ["ss \(x(s.id)) \(date(s.night)) \(opt(s.inBedStart, date)) \(opt(s.inBedEnd, date)) \(opt(s.onset, date)) "
        + "\(opt(s.wake, date)) \(flag(s.edited)) \(opt(s.recStart, date)) \(opt(s.recEnd, date)) \(opt(s.recOnset, date)) \(opt(s.recWake, date))"]
    out.append("sm " + sleepFields(s.summary))
    for g in s.segments { out.append("sg \(date(g.start)) \(date(g.end)) \(x(g.stage.rawValue)) \(x(g.provenance.rawValue))") }
    if let o = s.osa { out.append("os \(d(o.avgSpO2)) \(d(o.minSpO2)) \(d(o.timeBelow90Sec)) \(d(o.odi)) \(o.validWindows)") }
    if let c = s.coverage { out.append((["cv", date(c.from), date(c.to)] + c.times.map(date)).joined(separator: " ")) }
    switch s.reference {
    case .measured(let r, let rs, let re, let end)?:
        out.append("rm \(x(r.rawValue)) \(date(rs)) \(date(re)) \(date(end))")
    case .unavailable(let reason)?:
        out.append("ru \(x(reason))")
    case nil:
        break
    }
    switch s.edge {
    case .assessed(let asleep, let inBed, let start, let end, let last, let first, let earliest, let basis, let after)?:
        out.append((["ea", d(asleep), d(inBed), date(start), date(end), opt(last, date), opt(first, date), opt(earliest, date), x(basis)]
            + after.map(date)).joined(separator: " "))
    case .direct(let r)?:
        out.append("ed \(date(r.windowStart)) \(date(r.windowEnd)) \(x(r.bedtimeVerdict)) \(opt(r.bedtimeGapSeconds, d)) "
            + "\(x(r.wakeVerdict)) \(opt(r.wakeGapSeconds, d)) \(d(r.materialGapSeconds)) \(x(r.durationBasis)) "
            + "r" + r.reasons.map(x).joined(separator: "|"))
    case nil:
        break
    }
    return out
}

var inputs = [
    "# ExportDifferential inputs, written by tools/sleep-differential (regenerate.sh export); do not edit.",
    "# Format: see the header of Sources/ExportDifferential/main.swift.",
]
for c in cases {
    let zone = TimeZone(identifier: c.zone)! // Foundation names "UTC" "GMT"; compare zones, not names
    NSTimeZone.default = zone
    precondition(ExportEngine.localTimeZone == zone, "Calendar.current did not follow NSTimeZone.default for \(c.zone)")
    inputs.append("case \(c.id) \(c.zone) \(date(c.now)) \(c.json ? "json" : "csvonly")" + (c.v3 ? " v3" : ""))
    for r in c.rows { inputs.append("s \(x(r.kind)) \(date(r.start)) \(date(r.end)) \(d(r.value))") }
    for r in c.sleep { inputs.append("sl " + sleepFields(r)) }
    for r in c.daily { inputs.append("dy \(date(r.day)) \(r.steps)") }
    for r in c.steps { inputs.append("st \(date(r.start)) \(date(r.end)) \(r.delta)") }
    for r in c.naps { inputs.append("np \(date(r.start)) \(date(r.end)) \(r.asleepMin) \(flag(r.isLongNap))") }
    for r in c.temperatures { inputs.append("dt \(date(r.time)) \(d(r.celsius))") }
    for r in c.evidence {
        inputs.append("ev \(date(r.capturedAt)) \(x(r.ringID)) \(x(r.trigger)) \(flag(r.sleepCommitted)) \(r.stagedSleepSegments) "
            + "\(r.mergedRecordCount) \(r.historySampleCount) \(x(r.rawRecordBlobBase64)) \(opt(r.nightRowOutcome, x))")
        for t in r.channels {
            let fields: [String] = [
                x(t.label), "\(t.channel)", date(t.startedAt), opt(t.finishedAt, date), flag(t.sawSyncAck), opt(t.syncAckFlag) { "\($0)" },
                flag(t.sawEmptyHistorySignal), opt(t.openWriteFailed, flag), opt(t.fetchNudges) { "\($0)" }, opt(t.reopenRound) { "\($0)" },
                "\(t.page4CCount)", "\(t.page47Count)", opt(t.page4DCount) { "\($0)" }, opt(t.sportSampleCount) { "\($0)" },
                "\(t.endMarkerCount)", "\(t.recordsAtStart)", "\(t.recordsAtEnd)", opt(t.firstOpcode) { "\($0)" },
                opt(t.lastOpcode) { "\($0)" }, opt(t.exitReason?.rawValue, x),
            ]
            inputs.append("ch " + fields.joined(separator: " "))
        }
    }
    if let m = c.meta {
        let texts = [m.appVersion, m.appBuild, m.deviceModel, m.osVersion, m.ringModel, m.ringFirmware, m.ringGeneration,
                     m.ringIdentifier, m.timeZoneIdentifier, m.timestampPolicy].map(x).joined(separator: " ")
        inputs.append("md \(date(m.exportedAt)) \(date(m.rangeStart)) \(date(m.rangeEnd)) \(texts) \(m.timeZoneOffsetSeconds) \(m.schemaVersion)")
    }
    for s in c.sessions { inputs += sessionLines(s) }
    inputs.append("end")
    writeText(ExportEngine.samplesCSV(c.rows), "\(c.id).samples.csv")
    writeText(ExportEngine.sleepCSV(c.sleep), "\(c.id).sleep.csv")
    writeText(ExportEngine.dailyCSV(c.daily), "\(c.id).daily.csv")
    writeText(ExportEngine.stepSamplesCSV(c.steps), "\(c.id).stepSamples.csv")
    writeText(ExportEngine.napsCSV(c.naps), "\(c.id).naps.csv")
    writeText(ExportEngine.daytimeTemperatureCSV(c.temperatures), "\(c.id).daytimeTemperatures.csv")
    writeText(ExportEngine.historySyncEvidenceCSV(c.evidence), "\(c.id).historySyncEvidence.csv")
    writeText((c.sleep.map { ExportEngine.sessionID(night: $0.night) } + c.daily.map { ExportEngine.dayStamp($0.day) })
        .joined(separator: "\n"), "\(c.id).labels.txt")
    let sessionRows = c.sessions.map { $0.row() }
    if c.v3 {
        if let m = c.meta { writeText(ExportEngine.metadataCSV(m), "\(c.id).metadata.csv") }
        writeText(ExportEngine.sleepSessionsCSV(sessionRows), "\(c.id).sleepSessions.csv")
        writeText(ExportEngine.hypnogramCSV(sessionRows), "\(c.id).hypnogram.csv")
    }
    if c.json {
        guard let json = ExportEngine.toJSON(samples: c.rows, sleep: c.sleep, daily: c.daily, stepSamples: c.steps, naps: c.naps,
                                             daytimeTemperatures: c.temperatures, historySyncEvidence: c.evidence, now: c.now,
                                             metadata: c.meta, sleepSessions: sessionRows) else {
            FileHandle.standardError.write("toJSON returned nil for \(c.id)\n".data(using: .utf8)!)
            exit(1)
        }
        writeText(json, "\(c.id).json")
    }
}
writeText(inputs.joined(separator: "\n") + "\n", "inputs.txt")

let orderLines = ["# JSONSerialization .sortedKeys order: the vocabulary, then the seeded random keys; each key as x + UTF-8 hex."]
    + ["vocabulary"] + writtenKeyOrder(vocabulary).map(x)
    + ["random"] + writtenKeyOrder(randomKeyList).map(x)
writeText(orderLines.joined(separator: "\n") + "\n", "keyorder.txt")

var sampleRowCount = 0, schema2RowCount = 0, traceCount = 0, sessionCount = 0, segmentCount = 0, metaCount = 0
for c in cases {
    sampleRowCount += c.rows.count
    schema2RowCount += c.sleep.count + c.daily.count + c.steps.count
    schema2RowCount += c.naps.count + c.temperatures.count + c.evidence.count
    for r in c.evidence { traceCount += r.channels.count }
    sessionCount += c.sessions.count
    for s in c.sessions { segmentCount += s.segments.count }
    if c.meta != nil { metaCount += 1 }
}

FileHandle.standardError.write(
    ("ExportDifferential: \(cases.count) cases, \(sampleRowCount) sample rows, \(schema2RowCount) schema-2 rows, "
        + "\(traceCount) channel traces, \(metaCount) metadata blocks, \(sessionCount) sessions, \(segmentCount) hypnogram segments, "
        + "\(vocabulary.count) vocabulary keys, \(randomKeyList.count) random keys\n")
        .data(using: .utf8)!
)
