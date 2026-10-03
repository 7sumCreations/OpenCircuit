// ExportDifferential — runs upstream's export writers over synthetic inputs and writes, into the
// directory given as the only argument:
//
//   inputs.txt          every case's inputs
//   <case>.samples.csv  ExportEngine.samplesCSV(rows), verbatim (UTF-8, no newline added)
//   <case>.json         ExportEngine.toJSON(...), verbatim — only for cases marked "json"
//   keyorder.txt        JSONSerialization's .sortedKeys order of the export's key vocabulary and of
//                       seeded random printable-ASCII keys, one key per line
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
// inputs.txt, per case:
//   case <id> <time zone identifier> <now: date> <json | csvonly>
//   s <kind: string> <start: date> <end: date> <value: double>     one sample row, in order
//   end
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

// MARK: - Cases

struct Case {
    let id: String
    let zone: String
    let now: Date
    let rows: [ExportEngine.SampleRow]
    let json: Bool
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
cases.append(Case(id: "non-finite", zone: "UTC", now: base,
                  rows: minuteRows([60, .nan, -.nan, .infinity, -.infinity, Double(bitPattern: 0x7ff4_0000_0000_0000), 61.5]),
                  json: false))

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

var inputs = [
    "# ExportDifferential inputs, written by tools/sleep-differential (regenerate.sh export); do not edit.",
    "# Format: see the header of Sources/ExportDifferential/main.swift.",
]
for c in cases {
    let zone = TimeZone(identifier: c.zone)! // Foundation names "UTC" "GMT"; compare zones, not names
    NSTimeZone.default = zone
    precondition(ExportEngine.localTimeZone == zone, "Calendar.current did not follow NSTimeZone.default for \(c.zone)")
    inputs.append("case \(c.id) \(c.zone) \(date(c.now)) \(c.json ? "json" : "csvonly")")
    for r in c.rows { inputs.append("s \(x(r.kind)) \(date(r.start)) \(date(r.end)) \(d(r.value))") }
    inputs.append("end")
    writeText(ExportEngine.samplesCSV(c.rows), "\(c.id).samples.csv")
    if c.json {
        guard let json = ExportEngine.toJSON(samples: c.rows, sleep: [], daily: [], now: c.now) else {
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

FileHandle.standardError.write(
    "ExportDifferential: \(cases.count) cases, \(cases.map(\.rows.count).reduce(0, +)) sample rows, \(vocabulary.count) vocabulary keys, \(randomKeyList.count) random keys\n"
        .data(using: .utf8)!
)
