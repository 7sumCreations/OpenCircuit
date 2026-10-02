// EngineDifferential — runs upstream's engine functions over seeded synthetic inputs and writes three
// files into the directory given as the only argument:
//
//   inputs.txt    every case's inputs
//   goldens.txt   upstream's canonical outputs for each case
//   coverage.txt  how many cases reached each named branch
//
// It is narrow by design: only the named sites where a line-for-line port can silently differ —
// libm calls, summation order, rounding and date arithmetic — are run here; everything else is
// checked by the ported upstream tests and the hostile-input tests.
//
// Everything is deterministic (SplitMix64 from fixed seeds), synthetic and locale-free: single
// spaces, '\n' line ends, integers in decimal, doubles as "d" followed by their IEEE-754 bit pattern
// in 16 lowercase hex digits, "-" for an absent value. Rerunning on the same upstream commit
// reproduces every byte. `EngineDifferentialTest` (Kotlin) reads these files and derives the list of
// branches the cases must reach from the branch-counting calls in THIS file's code (comments are not
// read), so every branch is named by a string literal; Gradle never runs this program.
//
// inputs.txt, per case:
//   case <id> <kind> <shape>
//   kind days (a list of daily points, oldest first; a point's date is a label the engine never reads):
//     p <steps> <sleepMinutes> <sleepScore> <stressScore> <13 doubles>   one daily point; the doubles
//                                       are skinTempC, dayTempC, sleepHRAvg, sleepHRVAvg, sleepSpO2Avg,
//                                       sleepRRAvg, dayHRAvg, dayHRVAvg, daySpO2Avg, dayRRAvg,
//                                       activeEnergyKcal, distanceM, exerciseMin ("-" = absent)
//     w <avg windows> / <trend windows> / <minDeltaFractions>   the queries, "/"-separated groups
//   kind bed (bedtimes, minutes since midnight, oldest first):
//     m <ints>                          the bedtimes (may be empty)
//     w <ints>                          the regularity windows
//   kind flat (every constant bedtime list of one length):
//     n <count>                         the list length; the lists are [x] * count for x in 0..1439
//   kind angles (the platform's cos, sin and log at the regularity's own arguments):
//     range <lo> <hi>                   bedtime minutes lo..<hi
//     lx <doubles>                      arguments for log (values of R in (0, 1])
//   end
// goldens.txt, per case:
//   case <id>
//   days:   avg <w> <17 tokens>         TrendsEngine.rollingAverages(points, window: w): steps,
//                                       sleepMinutes, sleepScore, stressScore, then the 13 doubles in
//                                       input order (double or "-")
//           tr <w> <tokens>             TrendsEngine.trend(for:window:minDeltaFraction:extract:) for
//                                       each extractor (steps, sleepScore, skinTempC, sleepHRAvg,
//                                       dayHRVAvg, distanceM) and, inside it, each minDeltaFraction:
//                                       up / down / flat / "-"
//   bed:    reg <tokens>                TrendsEngine.sleepRegularity(bedtimeMinutes:window:) per window
//           cs (<cos> <sin>)*           cos and sin of 2π·m/1440 for each bedtime, in order
//   flat:   fl <x0> <tokens>            the regularity of [x] * count for x = x0 ..< x0 + 144
//   angles: cs <m0> (<cos> <sin>)*      cos and sin of 2π·m/1440 for m = m0 ..< m0 + 40
//           ln <doubles>                log of each lx argument
//   end
// coverage.txt: "branch <name> <count>" for every name reached, sorted.
//
// Shapes include, from the start, the inputs a deliberate difference from upstream would touch:
// unreadable (NaN, infinite, signed-zero, subnormal, ±1e308) values and counts at the 32-bit ends,
// values at and around every guard, windows of 0, 1 and Int32.max, short lists, trends exactly at
// their thresholds and against a zero prior, bedtimes outside 0…1439, opposite bedtimes whose mean
// resultant length falls below 1e-9, and every constant bedtime. regenerate.sh runs this program with
// Swift's deterministic hashing (the engines' own Dictionary and Set orders then repeat run to run).

import Foundation
@testable import OpenCircuitKit

// MARK: - Deterministic randomness

struct SplitMix64 {
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
    mutating func chance(_ percent: Int) -> Bool { int(0, 99) < percent }
    mutating func pick<T>(_ xs: [T]) -> T { xs[int(0, xs.count - 1)] }
    /// A double in `lo...hi` on a 1/1024 grid, so it prints the same everywhere.
    mutating func real(_ lo: Double, _ hi: Double) -> Double {
        let steps = Int((hi - lo) * 1024)
        return lo + Double(int(0, steps)) / 1024
    }
}

// MARK: - Canonical rendering

func d(_ x: Double) -> String {
    let h = String(x.bitPattern, radix: 16)
    return "d" + String(repeating: "0", count: 16 - h.count) + h
}
func optD(_ x: Double?) -> String { x.map(d) ?? "-" }
func optI(_ x: Int?) -> String { x.map { String($0) } ?? "-" }
func ints(_ xs: [Int]) -> String { xs.map { " " + String($0) }.joined() }

var coverage: [String: Int] = [:]
func hit(_ branch: String, _ yes: Bool = true) { if yes { coverage[branch, default: 0] += 1 } }

struct Case {
    let id: String
    let kind: String
    let shape: String
    var inputs: [String] = []
    var goldens: [String] = []
}

// MARK: - Daily points → rolling averages and trends

/// A point's raw values: steps, sleepMinutes, sleepScore, stressScore; then the 13 doubles in the
/// input-file order.
struct RawPoint {
    var counts: [Int?]
    var doubles: [Double?]
}

/// Labels only: the engine never reads a point's date.
let labelBase = Date(timeIntervalSince1970: 1_767_225_600)

func point(_ r: RawPoint, _ i: Int) -> TrendsEngine.DailyPoint {
    let x = r.doubles
    return TrendsEngine.DailyPoint(
        date: labelBase.addingTimeInterval(Double(i) * 86_400),
        steps: r.counts[0], sleepMinutes: r.counts[1], sleepScore: r.counts[2], stressScore: r.counts[3],
        skinTempC: x[0], dayTempC: x[1], sleepHRAvg: x[2], sleepHRVAvg: x[3], sleepSpO2Avg: x[4],
        sleepRRAvg: x[5], dayHRAvg: x[6], dayHRVAvg: x[7], daySpO2Avg: x[8], dayRRAvg: x[9],
        activeEnergyKcal: x[10], distanceM: x[11], exerciseMin: x[12]
    )
}

let countRanges: [(Int, Int)] = [(1_500, 16_000), (240, 560), (40, 98), (5, 90)]
let doubleRanges: [(Double, Double)] = [
    (33, 36.5), (31, 35), (44, 78), (18, 95), (0.9, 0.995), (11, 19),
    (55, 95), (20, 80), (0.9, 0.995), (12, 20), (80, 900), (900, 12_000), (0, 95),
]
let hrIndices = [2, 6]

func typicalPoint(_ rng: inout SplitMix64, presence: Int) -> RawPoint {
    var c: [Int?] = []
    var x: [Double?] = []
    for (lo, hi) in countRanges {
        let v = rng.int(lo, hi)
        c.append(rng.chance(presence) ? v : nil)
    }
    for (lo, hi) in doubleRanges {
        let v = rng.real(lo, hi)
        x.append(rng.chance(presence) ? v : nil)
    }
    if c[2] != nil && rng.chance(10) { c[2] = 0 } // a score of 0 is "not computed"
    return RawPoint(counts: c, doubles: x)
}

/// Every count scaled by `f` (truncated) and every double multiplied by `f`.
func scaled(_ p: RawPoint, _ f: Double) -> RawPoint {
    RawPoint(counts: p.counts.map { $0.map { Int(Double($0) * f) } }, doubles: p.doubles.map { $0.map { $0 * f } })
}

/// Every present value set to `count` (counts) or `value` (doubles).
func level(_ p: RawPoint, count: Int, value: Double) -> RawPoint {
    RawPoint(counts: p.counts.map { $0.map { _ in count } }, doubles: p.doubles.map { $0.map { _ in value } })
}

let dayShapes = ["typical", "sparse", "guard", "hostile", "short", "long", "step", "threshold", "zero-prior"]
let dayCasesPerShape = 4
let avgWindows = [7, 1, 0, 3, 14, Int(Int32.max)]
let trendWindows = [7, 3, 1, 14, 1000]
let minDeltas: [Double] = [0.03, 0, 0.1]
let extractors: [(TrendsEngine.DailyPoint) -> Double?] = [
    { $0.steps.map(Double.init) }, { $0.sleepScore.map(Double.init) }, { $0.skinTempC },
    { $0.sleepHRAvg }, { $0.dayHRVAvg }, { $0.distanceM },
]

func dayCase(_ index: Int) -> Case {
    let shape = dayShapes[index / dayCasesPerShape]
    var rng = SplitMix64(state: 0x4441_5900 &+ UInt64(index))
    var raws: [RawPoint] = []
    switch shape {
    case "typical":
        for _ in 0..<rng.int(8, 16) { raws.append(typicalPoint(&rng, presence: 85)) }
    case "sparse":
        for _ in 0..<rng.int(8, 14) { raws.append(typicalPoint(&rng, presence: 25)) }
        // A last day with nothing at all, so the one-day window averages nothing.
        if index % 2 == 0 { raws.append(RawPoint(counts: Array(repeating: nil, count: 4), doubles: Array(repeating: nil, count: 13))) }
    case "guard":
        // Values at and around every guard: HR at 29 and one 1/1024 either side, scores of 0 and
        // below, zero and signed-zero temperatures, zero and negative counts.
        let hrs: [Double] = [0, 29, 29 + 1.0 / 1024, 29 - 1.0 / 1024, 30, -5, 60]
        let scores = [0, -1, 1, 100, -100, 70]
        let positives: [Double] = [0, -0.0, 1.0 / 1024, -1, 35]
        let counts = [0, -1, -500, 4_000]
        for _ in 0..<rng.int(8, 14) {
            var p = typicalPoint(&rng, presence: 90)
            for k in 0..<13 where p.doubles[k] != nil {
                p.doubles[k] = hrIndices.contains(k) ? rng.pick(hrs) : rng.pick(positives)
            }
            for k in 0..<4 where p.counts[k] != nil { p.counts[k] = k < 2 ? rng.pick(counts) : rng.pick(scores) }
            raws.append(p)
        }
    case "hostile":
        let doubles: [Double] = [.nan, .infinity, -.infinity, -0.0, 1e308, -1e308, .leastNonzeroMagnitude, 60]
        let counts = [Int(Int32.max), Int(Int32.min), 0, -1, 5_000]
        for _ in 0..<rng.int(8, 14) {
            var p = typicalPoint(&rng, presence: 90)
            for k in 0..<13 where p.doubles[k] != nil && rng.chance(60) { p.doubles[k] = rng.pick(doubles) }
            for k in 0..<4 where p.counts[k] != nil && rng.chance(60) { p.counts[k] = rng.pick(counts) }
            raws.append(p)
        }
    case "short":
        for _ in 0..<(index % dayCasesPerShape) { raws.append(typicalPoint(&rng, presence: 85)) }
    case "long":
        for _ in 0..<rng.int(20, 30) { raws.append(typicalPoint(&rng, presence: 85)) }
    case "step":
        // A level change half way: up by half, down by 40 %, and just over and under 3 %.
        let f = [1.5, 0.6, 1.0302734375, 0.9697265625][index % dayCasesPerShape]
        let n = rng.int(5, 10)
        let first = (0..<n).map { _ in typicalPoint(&rng, presence: 95) }
        raws = first + first.map { scaled($0, f) }
    case "threshold":
        // Prior days at 100, recent days at 100 + delta: the relative change lands exactly on a
        // minDeltaFraction (3 %, 10 %) or on 0.
        let k = [1, 3, 7, 7][index % dayCasesPerShape]
        let delta = [3, -3, 10, 0][index % dayCasesPerShape]
        let prior = (0..<k).map { _ in level(typicalPoint(&rng, presence: 95), count: 100, value: 100) }
        let recent = (0..<k).map { _ in level(typicalPoint(&rng, presence: 95), count: 100 + delta, value: 100 + Double(delta)) }
        raws = prior + recent
    case "zero-prior":
        let k = rng.int(2, 7)
        let prior = (0..<k).map { _ in level(typicalPoint(&rng, presence: 95), count: 0, value: rng.chance(50) ? 0 : -0.0) }
        raws = prior + (0..<k).map { _ in typicalPoint(&rng, presence: 95) }
    default:
        fatalError("unknown day shape \(shape)")
    }

    var c = Case(id: String(format: "days-%03d", index), kind: "days", shape: shape)
    for r in raws {
        c.inputs.append("p" + r.counts.map { " " + optI($0) }.joined() + r.doubles.map { " " + optD($0) }.joined())
    }
    c.inputs.append("w" + ints(avgWindows) + " /" + ints(trendWindows) + " /" + minDeltas.map { " " + d($0) }.joined())

    let points = raws.enumerated().map { point($0.element, $0.offset) }
    for w in avgWindows {
        let r = TrendsEngine.rollingAverages(points, window: w)
        let vals: [Double?] = [
            r.steps, r.sleepMinutes, r.sleepScore, r.stressScore, r.skinTempC, r.dayTempC, r.sleepHRAvg,
            r.sleepHRVAvg, r.sleepSpO2Avg, r.sleepRRAvg, r.dayHRAvg, r.dayHRVAvg, r.daySpO2Avg, r.dayRRAvg,
            r.activeEnergyKcal, r.distanceM, r.exerciseMin,
        ]
        c.goldens.append("avg \(w)" + vals.map { " " + optD($0) }.joined())
        let tail = Array(raws.suffix(w))
        hit("roll-window-zero", w == 0)
        hit("roll-window-limited", w > raws.count && !raws.isEmpty)
        hit("roll-empty-list", raws.isEmpty)
        hit("roll-all-nil", w > 0 && !tail.isEmpty && vals.allSatisfy { $0 == nil })
        hit("roll-hr-guarded", tail.contains { p in hrIndices.contains { k in p.doubles[k].map { !($0 > TrendsEngine.minValidHR) } ?? false } })
        hit("roll-score-dropped", tail.contains { p in [p.counts[2], p.counts[3]].contains { v in v.map { $0 <= 0 } ?? false } })
        hit("roll-negative-count", tail.contains { p in [p.counts[0], p.counts[1]].contains { v in v.map { $0 < 0 } ?? false } })
        hit("roll-infinite-average", vals.contains { v in v.map { $0.isInfinite } ?? false })
        let stepSum = tail.compactMap { $0.counts[0] }.reduce(0, +)
        hit("roll-sum-past-32-bits", stepSum > Int(Int32.max) || stepSum < Int(Int32.min))
    }
    for w in trendWindows {
        var tokens: [String] = []
        for extract in extractors {
            for m in minDeltas {
                let t = TrendsEngine.trend(for: points, window: w, minDeltaFraction: m, extract: extract)
                tokens.append(t?.rawValue ?? "-")
                switch t {
                case .up: hit("trend-up")
                case .down: hit("trend-down")
                case .flat: hit("trend-flat")
                case nil: hit("trend-nil")
                }
                // The means as upstream forms them, to name the edge a case reached.
                let recent = Array(points.suffix(w))
                let prior = Array(points.dropLast(recent.count).suffix(w))
                let rv = recent.compactMap(extract)
                let pv = prior.compactMap(extract)
                if points.count >= 2 && !rv.isEmpty && !pv.isEmpty {
                    let rm = rv.reduce(0, +) / Double(rv.count)
                    let pm = pv.reduce(0, +) / Double(pv.count)
                    hit("trend-zero-prior", pm == 0)
                    hit("trend-nonfinite-mean", !rm.isFinite || !pm.isFinite)
                    hit("trend-exact-threshold", m > 0 && abs(pm) > 0 && abs((rm - pm) / abs(pm)) == m)
                }
            }
        }
        c.goldens.append("tr \(w) " + tokens.joined(separator: " "))
    }
    return c
}

// MARK: - Bedtimes → sleep regularity, and the platform maths at its arguments

func angle(_ m: Int) -> Double { 2.0 * Double.pi * Double(m) / 1440.0 }

/// The mean resultant length as upstream forms it, to name the branch a case reached.
func resultantLength(_ tail: [Int]) -> Double {
    let n = Double(tail.count)
    let meanCos = tail.map { cos(angle($0)) }.reduce(0, +) / n
    let meanSin = tail.map { sin(angle($0)) }.reduce(0, +) / n
    return min(max((meanCos * meanCos + meanSin * meanSin).squareRoot(), 0), 1)
}

let bedShapes = ["regular", "irregular", "midnight", "outside", "opposite", "short"]
let bedCasesPerShape = 4
let regWindows = [7, 1, 2, 3, 14, Int(Int32.max)]

func bedCase(_ index: Int) -> Case {
    let shape = bedShapes[index / bedCasesPerShape]
    var rng = SplitMix64(state: 0x4245_4400 &+ UInt64(index))
    var m: [Int] = []
    switch shape {
    case "regular":
        let center = rng.int(1_260, 1_380)
        for _ in 0..<rng.int(5, 12) { m.append(center + rng.int(-15, 15)) }
        // The first case is the same bedtime every night (22:00, as upstream's own test).
        if index % bedCasesPerShape == 0 { m = Array(repeating: 1_320, count: 7) }
    case "irregular":
        for _ in 0..<rng.int(5, 12) { m.append(rng.int(0, 1_439)) }
    case "midnight":
        for _ in 0..<rng.int(5, 12) { m.append((1_440 + rng.int(-20, 20)) % 1_440) }
    case "outside":
        // The same clock time written a whole number of days away, and the 32-bit ends.
        let center = rng.int(0, 1_439)
        for _ in 0..<rng.int(5, 10) { m.append(center + rng.int(-10, 10) + 1_440 * rng.int(-3, 3)) }
        if index % 2 == 0 { m += [Int(Int32.max), Int(Int32.min)] }
    case "opposite":
        // Opposite (or quartered) bedtimes: the mean resultant length falls to about 1e-16.
        let a = rng.int(0, 719)
        m = index % 2 == 0 ? [a, a + 720] : [a, a + 360, a + 720, a + 1_080]
        if index % 4 >= 2 { m = m + m }
    case "short":
        for _ in 0..<(index % bedCasesPerShape) { m.append(rng.int(0, 1_439)) }
    default:
        fatalError("unknown bed shape \(shape)")
    }

    var c = Case(id: String(format: "bed-%03d", index), kind: "bed", shape: shape)
    c.inputs.append("m" + ints(m))
    c.inputs.append("w" + ints(regWindows))
    var tokens: [String] = []
    for w in regWindows {
        let s = TrendsEngine.sleepRegularity(bedtimeMinutes: m, window: w)
        tokens.append(optI(s))
        let tail = Array(m.suffix(w))
        switch s {
        case nil: hit("reg-nil")
        case 100: hit("reg-100")
        case 0: hit("reg-0")
        default: hit("reg-between")
        }
        hit("reg-tiny-r", tail.count >= 2 && !(resultantLength(tail) > 1e-9))
        hit("reg-midnight-wrap", tail.contains { $0 < 60 } && tail.contains { $0 > 1_380 && $0 < 1_440 })
        hit("reg-outside-day", tail.contains { !(0..<1_440).contains($0) })
    }
    c.goldens.append("reg " + tokens.joined(separator: " "))
    c.goldens.append("cs" + m.map { " " + d(cos(angle($0))) + " " + d(sin(angle($0))) }.joined())
    return c
}

let flatCounts = [2, 3, 7]

func flatCase(_ index: Int) -> Case {
    let count = flatCounts[index]
    var c = Case(id: String(format: "flat-%03d", index), kind: "flat", shape: "constant")
    c.inputs.append("n \(count)")
    for x0 in stride(from: 0, to: 1_440, by: 144) {
        var tokens: [String] = []
        for x in x0..<(x0 + 144) {
            let s = TrendsEngine.sleepRegularity(bedtimeMinutes: Array(repeating: x, count: count))
            tokens.append(optI(s))
            hit("reg-constant-100", s == 100)
            hit("reg-constant-99", s == 99)
        }
        c.goldens.append("fl \(x0) " + tokens.joined(separator: " "))
    }
    return c
}

func anglesCase(_ index: Int) -> Case {
    var rng = SplitMix64(state: 0x414E_4700 &+ UInt64(index))
    let lo = index * 480
    let hi = lo + 480
    // Values of R near 1 (the regular sleeper) and spread over (0, 1].
    let near = (1...40).map { 1.0 - Double($0 + 40 * index) * 0x1p-30 }
    let spread = (0..<40).map { _ in rng.real(0.0009765625, 1) }
    var c = Case(id: String(format: "angles-%03d", index), kind: "angles", shape: "sweep")
    c.inputs.append("range \(lo) \(hi)")
    c.inputs.append("lx" + (near + spread).map { " " + d($0) }.joined())
    for m0 in stride(from: lo, to: hi, by: 40) {
        c.goldens.append("cs \(m0)" + (m0..<(m0 + 40)).map { " " + d(cos(angle($0))) + " " + d(sin(angle($0))) }.joined())
        hit("libm-cos-sin")
    }
    c.goldens.append("ln" + (near + spread).map { " " + d(log($0)) }.joined())
    hit("libm-log")
    return c
}

// MARK: - Main

let args = CommandLine.arguments
guard args.count == 2 else {
    FileHandle.standardError.write("usage: EngineDifferential <output directory>\n".data(using: .utf8)!)
    exit(2)
}
let outDir = URL(fileURLWithPath: args[1], isDirectory: true)

let started = Date()
let cases = (0..<(dayShapes.count * dayCasesPerShape)).map(dayCase)
    + (0..<(bedShapes.count * bedCasesPerShape)).map(bedCase)
    + (0..<flatCounts.count).map(flatCase)
    + (0..<3).map(anglesCase)
let header = "# Generated by android/tools/sleep-differential (EngineDifferential) from upstream OpenCircuitKit; do not edit by hand."
var inputs = [header]
var goldens = [header]
for c in cases {
    inputs.append("case \(c.id) \(c.kind) \(c.shape)")
    inputs += c.inputs
    inputs.append("end")
    goldens.append("case \(c.id)")
    goldens += c.goldens
    goldens.append("end")
}
let coverageLines = [header] + coverage.keys.sorted().map { "branch \($0) \(coverage[$0]!)" }

func write(_ lines: [String], _ file: String) throws {
    try (lines.joined(separator: "\n") + "\n").write(to: outDir.appendingPathComponent(file), atomically: true, encoding: .utf8)
}
do {
    try write(inputs, "inputs.txt")
    try write(goldens, "goldens.txt")
    try write(coverageLines, "coverage.txt")
} catch {
    FileHandle.standardError.write("write failed: \(error)\n".data(using: .utf8)!)
    exit(1)
}
let elapsed = Date().timeIntervalSince(started)
print("cases \(cases.count), input lines \(inputs.count), golden lines \(goldens.count), generated in \(Int((elapsed * 1000).rounded())) ms")
for l in coverageLines.dropFirst() { print(l) }
