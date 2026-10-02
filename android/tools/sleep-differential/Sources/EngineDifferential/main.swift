// EngineDifferential — runs upstream's engine functions over seeded synthetic inputs and writes four
// files into the directory given as the only argument:
//
//   inputs.txt    every case's inputs
//   goldens.txt   upstream's canonical outputs for each case
//   random.txt    the Swift standard library's random draws the headache evaluation tests are built
//                 from, read by `SwiftRandomTest` (format with the code, "Swift's random draws" below)
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
//   kind assess (one day of the headache signals index; times are seconds since 1970):
//     d <day> <now> <lastRingData|-> <truncated 0/1> <fever 0/1> <logged 0/1> <perimenstrual 0/1/->
//       <skinTempOffset|-> <inBedStart|-> <dayHRPrevious|-> <dayHRTwoDaysAgo|->
//     s <rhr|hrv|eff|frag|dur> <today> <prior doubles>   one per series present
//     b <prior in-bed starts>           h <prior day HRs>           i <prior frozen indices>
//   kind ix (the same day lines, then one series' today swept):
//     x <series> <today0> <step> <count>   today_k = today0 + k · step
//   kind pct:   p <sorted doubles>      f <fractions>
//   kind rank:  r <positives count> <doubles>   (the first <count> are the positives)
//   kind se:    n <nPos> <nNeg>         a <aucs>
//   kind tail:  q <observed> <flagged> <positives> <total>   (one line each)
//               w <successes> <trials> <z>                   (one line each)
//   kind cycle (a period history; every date is a double of seconds since 2001, Foundation's own form):
//     e <start> <end|->                 one period entry, in the order given (may be unsorted)
//     t <night> <offsetC>               one skin-temperature night (none, or several)
//     n <nows>                          the clocks `predict` is asked at
//   kind prox (link RSSI readings, dBm):
//     r <ints or "-">                   the readings ("-" = no reading)
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
//   assess: v scored <index> <band raw> <ring features> <coverage> <suppression|->
//           | v building <days> | v interrupted <since|-> | v insufficient (<feature>=<reason>)*
//           c (<feature> <z|-> <contribution|-> <effectiveWeight> <absentReason|->)*  or "c -"
//           q <100 · weighted / total, the quotient the index is rounded from>     or "q -"
//   ix:     ix <k0> <64 indices>        iq <k0> <64 quotients>
//   pct:    pc <HeadacheSignals.percentile(sorted, f) per fraction>
//   rank:   mr <HeadacheEvaluation.midranks(values)>
//           au <auc(positives, negatives)|-> <hanleyMcNeilSE(auc, nPos, nNeg)|->
//   se:     se <hanleyMcNeilSE(auc, nPos, nNeg)|-> per auc
//   tail:   tl (<hypergeometricUpperTail|-> <w if ≤ 0.01, n if not, - if nil>)* per q line
//           wl <wilsonUpperBound|-> per w line
//   cycle:  st <avgCycleLengthDays> <sampleCount> <avgPeriodDurationDays|->   or "st - - -"
//           pr <k> <nextPeriodStart> <nextPeriodEnd> <fertileWindowStart> <ovulationEstimate> <1|0>
//                                       CyclePredictor.predict at the k-th clock (dates as seconds since
//                                       2001; the last token is tempCorroborated), or "pr <k> -"
//   prox:   pm <RingProximity.approximateMeters per reading|->
//           pf <RingProximity.approximateFeet per reading|->
//           pt <RingProximity.distanceText per reading: "u" + its UTF-8 bytes in lowercase hex, or "-">
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

// MARK: - Headache signals: the whole assessment, the index's rounding, the percentile

let hsDay = 1_753_660_800
let hsSeriesNames = ["rhr", "hrv", "eff", "frag", "dur"]
let hsSeriesBases: [Double] = [60, 50, 90, 40, 420]

/// One day's input, as plain values (the Kotlin test builds the same `DayInput` from these lines).
struct HSDay {
    var day = hsDay
    var now = hsDay + 8 * 3600
    var last: Int? = hsDay + 7 * 3600
    var series: [String: (today: Double, prior: [Double])] = [:]
    var offset: Double? = 0
    var inBed: Int? = 1_380
    var priorInBed: [Int] = Array(repeating: 1_380, count: 14)
    var prev: Double? = 70
    var prev2: Double? = 70
    var dayHRPrior: [Double] = Array(repeating: 70, count: 14)
    var peri: Bool? = false
    var truncated = false
    var fever = false
    var logged = false
    var priorIndices: [Int] = []

    func input() -> HeadacheSignals.DayInput {
        func s(_ name: String) -> HeadacheSignals.Series? { series[name].map { HeadacheSignals.Series(today: $0.today, prior: $0.prior) } }
        return HeadacheSignals.DayInput(
            day: Date(timeIntervalSince1970: Double(day)), now: Date(timeIntervalSince1970: Double(now)),
            lastRingDataAt: last.map { Date(timeIntervalSince1970: Double($0)) },
            restingHR: s("rhr"), hrvSDNN: s("hrv"), sleepEfficiencyPct: s("eff"),
            sleepFragmentationMin: s("frag"), sleepDurationMin: s("dur"), skinTempOffsetC: offset,
            inBedStartMinutes: inBed, priorInBedStartMinutes: priorInBed, dayHRPrevious: prev,
            dayHRTwoDaysAgo: prev2, dayHRPrior: dayHRPrior, isPerimenstrual: peri,
            sleepLikelyTruncated: truncated, feverSuspected: fever, headacheAlreadyLoggedToday: logged,
            priorIndices: priorIndices)
    }

    func lines() -> [String] {
        func b(_ x: Bool) -> String { x ? "1" : "0" }
        var out = ["d \(day) \(now) \(optI(last)) \(b(truncated)) \(b(fever)) \(b(logged)) \(peri.map(b) ?? "-") \(optD(offset)) \(optI(inBed)) \(optD(prev)) \(optD(prev2))"]
        for name in hsSeriesNames {
            if let s = series[name] { out.append("s \(name) \(d(s.today))" + s.prior.map { " " + d($0) }.joined()) }
        }
        out.append("b" + ints(priorInBed))
        out.append("h" + dayHRPrior.map { " " + d($0) }.joined())
        out.append("i" + ints(priorIndices))
        return out
    }
}

/// The verdict in three canonical lines: `v …`, `c …` (every contribution, or "-"), `q …` (the
/// weighted quotient the index is rounded from, or "-").
func renderVerdict(_ v: HeadacheSignals.Verdict) -> [String] {
    switch v {
    case .notEnabled:
        return ["v notEnabled", "c -", "q -"]
    case .buildingBaseline(let n):
        hit("assess-building")
        return ["v building \(n)", "c -", "q -"]
    case .interrupted(let since):
        hit("assess-interrupted")
        return ["v interrupted " + (since.map { String(Int($0.timeIntervalSince1970)) } ?? "-"), "c -", "q -"]
    case .insufficientData(let missing):
        hit("assess-insufficient")
        let tokens = HeadacheSignals.Feature.allCases.compactMap { f in missing[f].map { "\(f.rawValue)=\($0.rawValue)" } }
        return ["v insufficient" + tokens.map { " " + $0 }.joined(), "c -", "q -"]
    case .scored(let a):
        hit("assess-scored")
        let total = a.contributions.reduce(0.0) { $0 + $1.effectiveWeight }
        let weighted = a.contributions.reduce(0.0) { $0 + $1.effectiveWeight * ($1.contribution ?? 0) }
        let q = 100 * weighted / total
        hit("assess-band-flagged", a.band == .flagged)
        hit("assess-band-elevated", a.band == .elevated)
        hit("assess-suppressed", a.suppressedBy != nil)
        let quality = a.contributions.map { c -> Bool in
            c.isPresent && c.effectiveWeight != c.feature.weight && c.effectiveWeight != c.feature.weight / 2
        }
        hit("assess-capped", quality.contains(true))
        let cs = a.contributions.map { c in "\(c.feature.rawValue) \(optD(c.z)) \(optD(c.contribution)) \(d(c.effectiveWeight)) \(c.absentReason?.rawValue ?? "-")" }
        return ["v scored \(a.index) \(a.band.rawValue) \(a.ringFeatureCount) \(d(a.coverageFraction)) \(a.suppressedBy?.rawValue ?? "-")",
                "c " + cs.joined(separator: " "), "q \(d(q))"]
    }
}

func randomSeries(_ rng: inout SplitMix64, base: Double, spread: Double, count: Int) -> [Double] {
    (0..<count).map { _ in base + rng.real(-spread, spread) }
}

let assessShapes = ["typical", "deviant", "sparse", "cold", "gap", "flags", "extreme", "unreadable", "band", "capped"]
let assessCasesPerShape = 4

func assessCase(_ index: Int) -> Case {
    let shape = assessShapes[index / assessCasesPerShape]
    var rng = SplitMix64(state: 0x4853_0000 &+ UInt64(index))
    var day = HSDay()
    func fill(present: Int, deviation: Double, priorCount: ClosedRange<Int>) {
        for (k, name) in hsSeriesNames.enumerated() where rng.chance(present) {
            let base = hsSeriesBases[k]
            let floor = [5.0, 8, 5, 15, 30][k]
            let prior = randomSeries(&rng, base: base, spread: floor * rng.real(0, 1.5), count: rng.int(priorCount.lowerBound, priorCount.upperBound))
            day.series[name] = (base + floor * rng.real(-deviation, deviation), prior)
        }
        day.offset = rng.chance(present) ? rng.real(-deviation / 2, deviation / 2) : nil
        day.inBed = rng.chance(present) ? 1_380 + rng.int(-180, 180) : nil
        day.priorInBed = (0..<rng.int(priorCount.lowerBound, priorCount.upperBound)).map { _ in 1_380 + rng.int(-40, 40) }
        day.prev = rng.chance(present) ? 70 + rng.real(-deviation * 5, deviation * 5) : nil
        day.prev2 = rng.chance(present) ? 70 + rng.real(-deviation * 5, deviation * 5) : nil
        day.dayHRPrior = randomSeries(&rng, base: 70, spread: rng.real(0, 8), count: rng.int(priorCount.lowerBound, priorCount.upperBound))
        day.peri = rng.chance(50) ? rng.chance(30) : nil
    }
    switch shape {
    case "typical":
        fill(present: 90, deviation: 1.5, priorCount: 14...60)
    case "deviant":
        fill(present: 95, deviation: 5, priorCount: 14...60)
        day.priorIndices = (0..<rng.int(21, 80)).map { _ in rng.chance(40) ? 0 : rng.int(1, 60) }
    case "sparse":
        fill(present: 35, deviation: 3, priorCount: 7...30)
        day.priorIndices = (0..<rng.int(0, 30)).map { _ in rng.int(0, 40) }
    case "cold":
        fill(present: 90, deviation: 2, priorCount: 0...8)
    case "gap":
        fill(present: 90, deviation: 2, priorCount: 14...30)
        day.last = [nil, day.now - 24 * 3600, day.now - 24 * 3600 + 1, day.now + 3600][index % assessCasesPerShape]
    case "flags":
        fill(present: 90, deviation: 4, priorCount: 14...30)
        day.truncated = true
        day.fever = index % 2 == 0
        day.logged = index % 4 >= 2
        day.priorIndices = (0..<rng.int(21, 60)).map { _ in rng.int(0, 30) }
    case "extreme":
        // Finite extremes: they take the same path in the port as upstream.
        fill(present: 95, deviation: 2, priorCount: 14...30)
        day.series["rhr"]?.today = [1e308, -1e308, 1e-300, 70][index % assessCasesPerShape]
        day.offset = [1e300, -1e300, 0.5, 1.0][index % assessCasesPerShape]
        day.inBed = [Int(Int32.max), Int(Int32.min), -60, 1_440 * 3][index % assessCasesPerShape]
        day.prev = [1e300, 70, -1e300, 70][index % assessCasesPerShape]
    case "unreadable":
        // A reading that cannot be read (NaN or infinite) among real ones: upstream reads it as an
        // ordinary 0, the port as a missing reading (PORTING.md D-108). A NaN offset traps upstream,
        // so the offset here is only ever infinite.
        fill(present: 100, deviation: 1, priorCount: 14...30)
        day.series["eff"]?.today = 70 // one real deviation, so the denominator shows
        let bad = [Double.nan, .infinity, -.infinity][index % 3]
        day.series[["rhr", "hrv", "frag", "dur"][index % 4]]?.today = bad
        if index % 2 == 1 { day.offset = index % 4 == 1 ? .infinity : -.infinity }
        if index % 4 == 2 { day.prev = .nan }
        hit("assess-unreadable-input")
    case "band":
        // One saturated feature over a user whose own trailing indices sit low: the index clears the
        // band's percentiles but one feature can never flag — elevated.
        for (k, name) in hsSeriesNames.enumerated() { day.series[name] = (hsSeriesBases[k], randomSeries(&rng, base: hsSeriesBases[k], spread: 1, count: 20)) }
        day.series["eff"]?.today = 70
        day.priorIndices = (0..<rng.int(21, 70)).map { _ in rng.int(0, 16 + 4 * (index % 4)) }
    case "capped":
        // Four ring features, efficiency the largest: its share of the pool is over 35 % and is capped.
        day.series["eff"] = (90 - rng.real(0, 25), randomSeries(&rng, base: 90, spread: 2, count: 20))
        day.series["hrv"] = (50 + rng.real(-25, 25), randomSeries(&rng, base: 50, spread: 4, count: 20))
        day.offset = rng.real(-1.5, 1.5)
        day.inBed = 1_380 + rng.int(-120, 120)
        day.prev = nil
        day.peri = nil
    default:
        fatalError("unknown assess shape \(shape)")
    }
    var c = Case(id: String(format: "assess-%03d", index), kind: "assess", shape: shape)
    c.inputs = day.lines()
    c.goldens = renderVerdict(HeadacheSignals.assess(day.input()))
    return c
}

/// One feature's today swept across a grid, the rest of the day held: the index's rounding at and
/// around every half.
let indexSweeps: [(name: String, today0: Double, step: Double, peri: Bool?, truncated: Bool)] = [
    ("eff", 90, -1.0 / 64, false, false),
    ("eff", 90, -1.0 / 64, nil, true),
    ("frag", 40, 1.0 / 16, true, false),
]
let sweepCount = 512

func indexSweepCase(_ index: Int) -> Case {
    let sweep = indexSweeps[index]
    var day = HSDay()
    for (k, name) in hsSeriesNames.enumerated() { day.series[name] = (hsSeriesBases[k], Array(repeating: hsSeriesBases[k], count: 14)) }
    day.peri = sweep.peri
    day.truncated = sweep.truncated
    var c = Case(id: String(format: "ix-%03d", index), kind: "ix", shape: "sweep")
    c.inputs = day.lines() + ["x \(sweep.name) \(d(sweep.today0)) \(d(sweep.step)) \(sweepCount)"]
    var indices: [Int] = []
    var quotients: [String] = []
    for k in 0..<sweepCount {
        var probe = day
        probe.series[sweep.name]?.today = sweep.today0 + Double(k) * sweep.step
        guard case .scored(let a) = HeadacheSignals.assess(probe.input()) else { fatalError("sweep day did not score") }
        let total = a.contributions.reduce(0.0) { $0 + $1.effectiveWeight }
        let weighted = a.contributions.reduce(0.0) { $0 + $1.effectiveWeight * ($1.contribution ?? 0) }
        let q = 100 * weighted / total
        hit("ix-near-half", abs(q - q.rounded(.down) - 0.5) < 1e-9)
        indices.append(a.index)
        quotients.append(d(q))
    }
    for k0 in stride(from: 0, to: sweepCount, by: 64) {
        c.goldens.append("ix \(k0)" + ints(Array(indices[k0..<(k0 + 64)])))
        c.goldens.append("iq \(k0) " + quotients[k0..<(k0 + 64)].joined(separator: " "))
    }
    return c
}

let pctShapes = ["ints", "reals", "short"]

func percentileCase(_ index: Int) -> Case {
    let shape = pctShapes[index / 4]
    var rng = SplitMix64(state: 0x5043_5400 &+ UInt64(index))
    let n: Int
    switch shape {
    case "ints": n = rng.int(2, 80)
    case "reals": n = rng.int(2, 80)
    default: n = index % 4 // 0, 1, 2, 3 values
    }
    let values: [Double] = (0..<n).map { _ in shape == "reals" ? rng.real(-50, 150) : Double(rng.int(0, 100)) }
    let sorted = values.sorted()
    let fractions: [Double] = [0, 0.25, 0.5, 0.75, 0.9, 1] + (0..<10).map { _ in rng.real(0, 1) }
    var c = Case(id: String(format: "pct-%03d", index), kind: "pct", shape: shape)
    c.inputs = ["p" + sorted.map { " " + d($0) }.joined(), "f" + fractions.map { " " + d($0) }.joined()]
    c.goldens = ["pc" + fractions.map { " " + d(HeadacheSignals.percentile(sorted, $0)) }.joined()]
    hit("pct-empty", sorted.isEmpty)
    hit("pct-single", sorted.count == 1)
    hit("pct-interpolated", sorted.count > 1 && fractions.contains { f in let r = f * Double(sorted.count - 1); return r != r.rounded(.down) })
    return c
}

// MARK: - Headache evaluation: ranks, the AUC and its error, the exact tail, the Wilson bound

let rankShapes = ["ties", "signed", "unreadable", "long"]

func rankCase(_ index: Int) -> Case {
    let shape = rankShapes[index / 4]
    var rng = SplitMix64(state: 0x524B_0000 &+ UInt64(index))
    let n = shape == "long" ? rng.int(21, 70) : rng.int(1, 20)
    let pool: [Double]
    switch shape {
    case "ties": pool = [0, 1, 2, 3, 4]
    case "signed": pool = [0, -0.0, 1, -1, 0.5]
    case "unreadable": pool = [.nan, .infinity, -.infinity, 0, 1, 2]
    default: pool = [.nan, 0, 1, 2, 3, -0.0]
    }
    let values = (0..<n).map { _ in rng.pick(pool) }
    let nPos = rng.int(0, n)
    let ranks = HeadacheEvaluation.midranks(values)
    let a = HeadacheEvaluation.auc(positiveScores: Array(values.prefix(nPos)), negativeScores: Array(values.dropFirst(nPos)))
    let se = a.flatMap { HeadacheEvaluation.hanleyMcNeilSE(auc: $0, nPos: nPos, nNeg: n - nPos) }
    hit("rank-ties", Set(values.map(\.bitPattern)).count < values.count)
    hit("rank-signed-zero", values.contains { $0 == 0 && $0.sign == .minus })
    hit("rank-nan", values.contains { $0.isNaN })
    hit("rank-past-insertion-sort", n > 20)
    hit("auc-nil", a == nil)
    hit("auc-value", a != nil)
    var c = Case(id: String(format: "rank-%03d", index), kind: "rank", shape: shape)
    c.inputs = ["r \(nPos)" + values.map { " " + d($0) }.joined()]
    c.goldens = ["mr" + ranks.map { " " + d($0) }.joined(), "au \(optD(a)) \(optD(se))"]
    return c
}

func seCase(_ index: Int) -> Case {
    let sizes = [(1, 1), (3, 5), (48, 312), (400, 7)][index]
    let aucs: [Double] = (0...20).map { Double($0) / 20 } + [.nan, -0.5, 1.5, 0.66, 0.839]
    var c = Case(id: String(format: "se-%03d", index), kind: "se", shape: "grid")
    c.inputs = ["n \(sizes.0) \(sizes.1)", "a" + aucs.map { " " + d($0) }.joined()]
    let ses = aucs.map { HeadacheEvaluation.hanleyMcNeilSE(auc: $0, nPos: sizes.0, nNeg: sizes.1) }
    hit("se-nil", ses.contains { $0 == nil })
    hit("se-value", ses.contains { $0 != nil })
    c.goldens = ["se" + ses.map { " " + optD($0) }.joined()]
    return c
}

let tailShapes = ["small", "year", "large", "edge"]
let workingAlphaForTokens = 0.01

func tailCase(_ index: Int) -> Case {
    let shape = tailShapes[index / 4]
    var rng = SplitMix64(state: 0x5441_494C &+ UInt64(index))
    var quads: [(Int, Int, Int, Int)] = []
    switch shape {
    case "small":
        for _ in 0..<40 {
            let n = rng.int(1, 40), f = rng.int(0, n), p = rng.int(0, n)
            quads.append((rng.int(0, min(f, p) + 2), f, p, n))
        }
    case "year":
        for _ in 0..<40 {
            let n = rng.int(100, 400), f = n / 10 + rng.int(-3, 3), p = rng.int(8, 60)
            quads.append((rng.int(0, min(f, p)), f, p, n))
        }
    case "large":
        for k in 0..<6 {
            let n = k == 0 ? 100_000 : rng.int(1_000, 60_000), f = n / 10, p = n / 8
            let expected = f * p / n
            quads.append((expected + rng.int(-20, 60), f, p, n))
        }
    default:
        // Every guard and bound: no rows, negative counts, more flags or positives than rows, an
        // observation at the forced floor, above the ceiling, at the ceiling.
        quads = [(0, 0, 0, 0), (1, -1, 2, 5), (1, 2, -1, 5), (-1, 2, 2, 5), (1, 6, 2, 5), (1, 2, 6, 5),
                 (2, 8, 4, 10), (5, 5, 4, 10), (4, 5, 4, 10), (0, 0, 0, 10), (1, 10, 10, 10), (10, 10, 10, 10)]
            + (0..<10).map { _ in let n = rng.int(1, 12); return (rng.int(-1, n + 1), rng.int(0, n), rng.int(0, n), n) }
    }
    let triples: [(Int, Int, Double)] = (0..<12).map { k in
        let t = rng.int(0, 60), s = rng.int(-1, t + 1)
        return (s, t, [1.645, 1.96, 0, -1.645, .nan, .infinity][k % 6])
    }
    var c = Case(id: String(format: "tail-%03d", index), kind: "tail", shape: shape)
    c.inputs = quads.map { "q \($0.0) \($0.1) \($0.2) \($0.3)" } + triples.map { "w \($0.0) \($0.1) \(d($0.2))" }
    var tokens: [String] = []
    for (o, f, p, n) in quads {
        let v = HeadacheEvaluation.hypergeometricUpperTail(observed: o, flagged: f, positives: p, total: n)
        // The value, and whether it clears the working alpha: a discrete answer a last-bit
        // difference in log or exp could flip.
        tokens.append(optD(v) + " " + (v.map { $0 <= workingAlphaForTokens ? "w" : "n" } ?? "-"))
        hit("tail-nil", v == nil)
        hit("tail-certain", v == 1)
        hit("tail-impossible", v == 0 && o > 0)
        hit("tail-summed", v.map { $0 > 0 && $0 < 1 } ?? false)
        hit("tail-large", n > 10_000)
        hit("tail-below-alpha", v.map { $0 > 0 && $0 <= workingAlphaForTokens } ?? false)
    }
    let wilson = triples.map { HeadacheEvaluation.wilsonUpperBound(successes: $0.0, trials: $0.1, z: $0.2) }
    hit("wilson-nil", wilson.contains { $0 == nil })
    hit("wilson-value", wilson.contains { $0.map { !$0.isNaN } ?? false })
    hit("wilson-nan", wilson.contains { $0.map(\.isNaN) ?? false })
    c.goldens = ["tl " + tokens.joined(separator: " "), "wl" + wilson.map { " " + optD($0) }.joined()]
    return c
}

// MARK: - Cycle prediction: Foundation's Double-seconds date arithmetic
//
// `cycleStats` and `predict` add, subtract and compare `Date`s, i.e. doubles of seconds since 2001,
// rounding at every step of the roll-forward. Histories are whole-day, fractional-second, stale (rolled
// forward for centuries, to Foundation's distant future), across both 2026 New York clock changes,
// hostile (unsorted, duplicated, backwards, overlapping, out of range), with skin-temperature nights at
// and around the corroboration window's edges, and at the length, duration and roll-forward edges.

/// New York, named explicitly (never the machine's zone): the clock-change histories are its local times.
let cycleNY: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "America/New_York")!
    return c
}()
let cycleShapes = ["regular", "fractional", "stale", "clock", "hostile", "skin", "edges"]
let cycleCasesPerShape = 4
let cycleDay = 86_400.0
typealias CycleEntry = CyclePredictor.PeriodEntry

func nyDate(_ y: Int, _ m: Int, _ day: Int, _ h: Int = 0, _ mi: Int = 0) -> Date {
    cycleNY.date(from: DateComponents(year: y, month: m, day: day, hour: h, minute: mi))!
}

/// How many additions upstream's roll-forward makes for this history and clock (its loop, counted).
func rollCount(_ entries: [CycleEntry], now: Date) -> Int {
    guard let stats = CyclePredictor.cycleStats(from: entries),
          let last = entries.sorted(by: { $0.start < $1.start }).last else { return -1 }
    let interval = stats.avgCycleLengthDays * cycleDay
    var next = last.start.addingTimeInterval(interval)
    var n = 0
    while next < now { next = next.addingTimeInterval(interval); n += 1 }
    return n
}

/// A history of `count` periods from `start`, gaps of `gap()` seconds, each ended with chance `endChance`.
func cycleHistory(_ rng: inout SplitMix64, from start: Date, count: Int, endChance: Int,
                  gap: (inout SplitMix64) -> Double, length: (inout SplitMix64) -> Double) -> [CycleEntry] {
    var t = start
    var out: [CycleEntry] = []
    for _ in 0..<count {
        let end: Date? = rng.chance(endChance) ? t.addingTimeInterval(length(&rng)) : nil
        out.append(CycleEntry(start: t, end: end))
        t = t.addingTimeInterval(gap(&rng))
    }
    return out
}

func cycleCase(_ index: Int) -> Case {
    let shape = cycleShapes[index / cycleCasesPerShape]
    let k = index % cycleCasesPerShape
    var rng = SplitMix64(state: 0x4359_434C &+ UInt64(index))
    // Whole days from 2023-11-14 00:00 UTC onward.
    let base = Date(timeIntervalSince1970: 1_699_920_000 + Double(rng.int(0, 400)) * cycleDay)
    var entries: [CycleEntry] = []
    var nights: [(night: Date, offsetC: Double)] = []
    var nows: [Date] = []
    switch shape {
    case "regular":
        entries = cycleHistory(&rng, from: base, count: rng.int(2, 8), endChance: 60,
                               gap: { Double($0.int(24, 36)) * cycleDay }, length: { Double($0.int(2, 8)) * cycleDay })
        let last = entries.last!.start
        nows = [last, last.addingTimeInterval(10 * cycleDay), last.addingTimeInterval(200 * cycleDay),
                last.addingTimeInterval(3 * 365 * cycleDay)]
    case "fractional":
        entries = cycleHistory(&rng, from: base.addingTimeInterval(rng.real(0, 86_399)), count: rng.int(3, 8), endChance: 70,
                               gap: { Double($0.int(23, 34)) * cycleDay + $0.real(0, 86_399) }, length: { $0.real(1, 10) * cycleDay })
        let last = entries.last!.start
        nows = [last.addingTimeInterval(rng.real(0, 3) * cycleDay), last.addingTimeInterval(rng.real(0, 30) * cycleDay),
                last.addingTimeInterval(400 * cycleDay), last.addingTimeInterval(30 * 365.25 * cycleDay)]
    case "stale":
        entries = cycleHistory(&rng, from: base.addingTimeInterval(rng.real(0, 86_399)), count: rng.int(2, 4), endChance: 50,
                               gap: { Double($0.int(25, 32)) * cycleDay + $0.real(0, 86_399) }, length: { $0.real(2, 7) * cycleDay })
        let last = entries.last!.start
        nows = [last.addingTimeInterval(100 * 365.25 * cycleDay), last.addingTimeInterval(1_000 * 365.25 * cycleDay),
                .distantFuture, last.addingTimeInterval(Double(k + 1) * 3_000 * cycleDay)]
    case "clock":
        // Local midnights and evenings in New York around both 2026 clock changes.
        let starts: [[Date]] = [
            [nyDate(2026, 2, 8), nyDate(2026, 3, 8), nyDate(2026, 4, 5)],
            [nyDate(2026, 10, 4), nyDate(2026, 11, 1), nyDate(2026, 11, 29)],
            [nyDate(2025, 12, 20, 21, 30), nyDate(2026, 1, 17, 22), nyDate(2026, 2, 14, 23, 45)],
            [nyDate(2026, 9, 5, 1, 30), nyDate(2026, 10, 3, 1, 30), nyDate(2026, 10, 31, 1, 30)],
        ]
        entries = starts[k].enumerated().map { i, s in
            CycleEntry(start: s, end: i % 2 == 0 ? cycleNY.date(byAdding: .day, value: 4 + i, to: s) : nil)
        }
        let last = entries.last!.start
        nows = [last, nyDate(2026, 3, 8, 3), nyDate(2026, 11, 1, 1, 30), last.addingTimeInterval(70 * cycleDay)]
        hit("cycle-clock-change", cycleNY.timeZone.secondsFromGMT(for: entries.first!.start) != cycleNY.timeZone.secondsFromGMT(for: last))
    case "hostile":
        let a = base
        switch k {
        case 0: // unsorted, a duplicated start
            entries = [CycleEntry(start: a.addingTimeInterval(56 * cycleDay)), CycleEntry(start: a),
                       CycleEntry(start: a.addingTimeInterval(28 * cycleDay), end: a.addingTimeInterval(33 * cycleDay)),
                       CycleEntry(start: a), CycleEntry(start: a.addingTimeInterval(85 * cycleDay))]
        case 1: // ends before their starts, overlapping periods, durations out of range
            entries = [CycleEntry(start: a, end: a.addingTimeInterval(-5 * cycleDay)),
                       CycleEntry(start: a.addingTimeInterval(27 * cycleDay), end: a.addingTimeInterval(67 * cycleDay)),
                       CycleEntry(start: a.addingTimeInterval(58 * cycleDay), end: a.addingTimeInterval(58.5 * cycleDay)),
                       CycleEntry(start: a.addingTimeInterval(86 * cycleDay), end: a.addingTimeInterval(90.25 * cycleDay))]
        case 2: // every interval out of range → no statistics
            entries = [0.0, 15, 65, 115].map { CycleEntry(start: a.addingTimeInterval($0 * cycleDay)) }
        default: // a single period
            entries = [CycleEntry(start: a, end: a.addingTimeInterval(4 * cycleDay))]
        }
        nows = [a, a.addingTimeInterval(120 * cycleDay), a.addingTimeInterval(1_000 * cycleDay)]
    case "skin":
        entries = cycleHistory(&rng, from: base, count: 3, endChance: 50,
                               gap: { Double($0.int(26, 31)) * cycleDay }, length: { Double($0.int(3, 6)) * cycleDay })
        let now = entries.last!.start
        let ov = CyclePredictor.predict(from: entries, now: now)!.ovulationEstimate
        let offsets: [[(Double, Double)]] = [
            [(-3 * cycleDay, 0.2), (3 * cycleDay, 0.2)], // both window edges, at the threshold
            [(-3 * cycleDay - 1e-3, 0.5), (3 * cycleDay + 1e-3, 0.5), (0, 0.19999999999999998), (cycleDay, .nan)], // just outside, just under
            [(0, 0.3), (0, 0.3), (-7 * cycleDay, 1), (2 * cycleDay, -.infinity)], // one night twice
            [(-cycleDay, .infinity), (cycleDay, 0.25), (-2 * cycleDay, 0.1)],
        ]
        nights = offsets[k].map { (night: ov.addingTimeInterval($0.0), offsetC: $0.1) }
        nows = [now, now.addingTimeInterval(1), now.addingTimeInterval(45 * cycleDay)]
    default: // "edges"
        let a = base
        switch k {
        case 0: // intervals exactly 21 and 45 days, and a second either side
            entries = [0.0, 21, 66].map { CycleEntry(start: a.addingTimeInterval($0 * cycleDay)) }
                + [CycleEntry(start: a.addingTimeInterval(87 * cycleDay - 1)), CycleEntry(start: a.addingTimeInterval(132 * cycleDay))]
                + [CycleEntry(start: a.addingTimeInterval(177 * cycleDay + 1))]
        case 1: // durations exactly 1 and 10 days, and a second either side
            entries = [(0.0, 1.0), (28, 10), (56, 1 - 1 / cycleDay), (84, 10 + 1 / cycleDay)].map {
                CycleEntry(start: a.addingTimeInterval($0.0 * cycleDay), end: a.addingTimeInterval(($0.0 + $0.1) * cycleDay))
            }
        case 2: // `now` exactly at the next start, a millisecond either side, and one cycle on
            entries = [CycleEntry(start: a), CycleEntry(start: a.addingTimeInterval(28 * cycleDay))]
        default: // a long fractional history (12 periods)
            entries = cycleHistory(&rng, from: a.addingTimeInterval(0.5), count: 12, endChance: 80,
                                   gap: { Double($0.int(21, 45)) * cycleDay + $0.real(0, 3_600) }, length: { $0.real(1, 10) * cycleDay })
        }
        let next = entries.sorted(by: { $0.start < $1.start }).last!.start
            .addingTimeInterval((CyclePredictor.cycleStats(from: entries)?.avgCycleLengthDays ?? 28) * cycleDay)
        nows = [next, next.addingTimeInterval(-1e-3), next.addingTimeInterval(1e-3), next.addingTimeInterval(28 * cycleDay)]
    }

    var c = Case(id: String(format: "cyc-%03d", index), kind: "cycle", shape: shape)
    c.inputs = entries.map { "e \(d($0.start.timeIntervalSinceReferenceDate)) \($0.end.map { d($0.timeIntervalSinceReferenceDate) } ?? "-")" }
        + nights.map { "t \(d($0.night.timeIntervalSinceReferenceDate)) \(d($0.offsetC))" }
        + ["n" + nows.map { " " + d($0.timeIntervalSinceReferenceDate) }.joined()]
    let stats = CyclePredictor.cycleStats(from: entries)
    c.goldens = ["st " + (stats.map { "\(d($0.avgCycleLengthDays)) \($0.sampleCount) \(optD($0.avgPeriodDurationDays))" } ?? "- - -")]
    hit("cycle-stats-nil", stats == nil)
    hit("cycle-stats-value", stats != nil)
    hit("cycle-interval-excluded", entries.count >= 2 && (stats?.sampleCount ?? 0) < entries.count - 1)
    hit("cycle-duration-logged", stats?.avgPeriodDurationDays != nil)
    hit("cycle-duration-default", stats != nil && stats?.avgPeriodDurationDays == nil)
    hit("cycle-fractional-interval", stats.map { let x = $0.avgCycleLengthDays * cycleDay; return x != x.rounded() } ?? false)
    for (i, now) in nows.enumerated() {
        guard let p = CyclePredictor.predict(from: entries, skinTempDeviations: nights, now: now) else {
            c.goldens.append("pr \(i) -")
            continue
        }
        let dates = [p.nextPeriodStart, p.nextPeriodEnd, p.fertileWindowStart, p.ovulationEstimate]
        c.goldens.append("pr \(i) " + dates.map { d($0.timeIntervalSinceReferenceDate) }.joined(separator: " ") + (p.tempCorroborated ? " 1" : " 0"))
        let rolls = rollCount(entries, now: now)
        hit("cycle-roll-none", rolls == 0)
        hit("cycle-roll-few", rolls >= 1 && rolls <= 100)
        hit("cycle-roll-many", rolls > 100)
        hit("cycle-now-at-next", p.nextPeriodStart == now)
        hit("cycle-corroborated", p.tempCorroborated)
        hit("cycle-not-corroborated", !p.tempCorroborated && !nights.isEmpty)
    }
    return c
}

// MARK: - Swift's random draws, as the headache evaluation tests make them
//
// Upstream's HeadacheEvaluationTests build their synthetic years from a test-local SplitMix64 driving
// the standard library's `Double.random(in:using:)`, `Int.random(in:using:)` and `shuffle(using:)`,
// plus a Box-Muller `gaussian` over Foundation's `log` and `cos`. The Kotlin port reproduces those
// draws in a test helper; random.txt is what it is checked against. Each line starts a FRESH generator
// from its seed, draws the shape a number of times and records the generator's state afterwards, so a
// draw that consumed a different number of words (a rejected integer, a retried double) shows.

/// The upstream test's `gaussian`, verbatim.
func gaussian(_ rng: inout SplitMix64) -> Double {
    let u1 = Double.random(in: 1e-12..<1, using: &rng)
    let u2 = Double.random(in: 0..<1, using: &rng)
    return (-2 * Foundation.log(u1)).squareRoot() * Foundation.cos(2 * .pi * u2)
}

/// The random half of the upstream test's `makeYear`, verbatim: which days are positive, then each
/// day's index from a latent N(0, 1) shifted by `dPrime` on positive days.
func yearDraws(count: Int, positives: Int, dPrime: Double, rng: inout SplitMix64) -> (positive: [Bool], indices: [Int]) {
    var isPositive = [Bool](repeating: false, count: count)
    for i in 0..<min(positives, count) { isPositive[i] = true }
    isPositive.shuffle(using: &rng)
    var indices = [Int](repeating: 0, count: count)
    for i in 0..<count {
        let latent = gaussian(&rng) + (isPositive[i] ? dPrime : 0)
        indices[i] = max(0, Int((20 * latent + 5).rounded()))
    }
    return (isPositive, indices)
}

func u64(_ x: UInt64) -> String {
    let h = String(x, radix: 16)
    return String(repeating: "0", count: 16 - h.count) + h
}

/// The seeds of three of the upstream tests, with the d' each of them draws its year at.
let rngSeeds: [(seed: UInt64, dPrime: Double)] = [(0x5EED_1234, 0), (0x1234_5678, 1.40), (0xFACE_0001, 0.55)]
let rngDraws = 24

/// One line: `<shape> <state after> <values>`, from a fresh generator seeded with `seed`.
func rngLine(_ shape: String, _ seed: UInt64, _ draw: (inout SplitMix64) -> String) -> String {
    var rng = SplitMix64(state: seed)
    var values: [String] = []
    for _ in 0..<rngDraws { values.append(draw(&rng)) }
    return "\(shape) \(u64(rng.state)) " + values.joined(separator: " ")
}

/// A generator that counts the words drawn from it.
struct CountingGenerator: RandomNumberGenerator {
    var inner: SplitMix64
    var calls = 0
    mutating func next() -> UInt64 {
        calls += 1
        return inner.next()
    }
}

/// How many words each of the first draws consumed, to name the rejection and retry branches.
func words(_ seed: UInt64, _ draw: (inout CountingGenerator) -> Void) -> [Int] {
    var rng = CountingGenerator(inner: SplitMix64(state: seed))
    var out: [Int] = []
    for _ in 0..<rngDraws {
        let before = rng.calls
        draw(&rng)
        out.append(rng.calls - before)
    }
    return out
}

func randomLines() -> [String] {
    var lines: [String] = []
    // The upper half of Int: a draw is rejected about a quarter of the time (Lemire's method).
    let wideLo = Int.min, wideHi = Int.max / 2
    // One ulp wide: delta · u rounds up to the upper bound about half the time, and is drawn again.
    let narrowLo = 1.0, narrowHi = 1.0.nextUp
    for (seed, dPrime) in rngSeeds {
        lines.append("seed \(u64(seed))")
        lines.append(rngLine("next", seed) { u64($0.next()) })
        lines.append(rngLine("unit", seed) { d(Double.random(in: 0..<1, using: &$0)) })
        lines.append(rngLine("u1", seed) { d(Double.random(in: 1e-12..<1, using: &$0)) })
        lines.append(rngLine("wide-double", seed) { d(Double.random(in: -1e300..<1e300, using: &$0)) })
        lines.append(rngLine("narrow-double", seed) { d(Double.random(in: narrowLo..<narrowHi, using: &$0)) })
        lines.append(rngLine("int-1-12", seed) { String(Int.random(in: 1...12, using: &$0)) })
        lines.append(rngLine("int-0-4", seed) { String(Int.random(in: 0...4, using: &$0)) })
        lines.append(rngLine("int-0-360", seed) { String(Int.random(in: 0..<360, using: &$0)) })
        lines.append(rngLine("int-wide", seed) { String(Int.random(in: wideLo...wideHi, using: &$0)) })
        lines.append(rngLine("int-full", seed) { String(Int.random(in: Int.min...Int.max, using: &$0)) })
        lines.append(rngLine("gauss", seed) { d(gaussian(&$0)) })
        var shuffled = SplitMix64(state: seed)
        var xs = Array(0..<360)
        xs.shuffle(using: &shuffled)
        lines.append("shuffle-360 \(u64(shuffled.state))" + ints(xs))
        var year = SplitMix64(state: seed)
        let (positive, indices) = yearDraws(count: 360, positives: 48, dPrime: dPrime, rng: &year)
        lines.append("year \(d(dPrime)) \(u64(year.state)) positive"
            + ints(positive.indices.filter { positive[$0] }) + " index" + ints(indices))

        let wide = words(seed) { _ = Int.random(in: wideLo...wideHi, using: &$0) }
        hit("rng-int-accepted-first", wide.contains(1))
        hit("rng-int-rejected", wide.contains { $0 > 1 })
        let narrow = words(seed) { _ = Double.random(in: narrowLo..<narrowHi, using: &$0) }
        hit("rng-double-accepted-first", narrow.contains(1))
        hit("rng-double-redrawn", narrow.contains { $0 > 1 })
        hit("rng-year-index-zero", indices.contains(0))
        hit("rng-year-index-positive", indices.contains { $0 > 0 })
    }
    // Foundation's erfc, which the upstream p-value test calls: whole and half steps over -4…8, the
    // tails, and the argument that test passes.
    let total = 180.0, positives = 24.0, flagged = 18.0, observed = 6.0
    let p = positives / total
    let mean = flagged * p
    let variance = flagged * p * (1 - p) * (total - flagged) / (total - 1)
    let testArgument = ((observed - 0.5 - mean) / variance.squareRoot()) / 2.0.squareRoot()
    let erfcArgs = stride(from: -4.0, through: 8.0, by: 0.125).map { $0 } + [-30, -6, 1e-9, -1e-9, 0, 12, 20, 26, 27.25, testArgument]
    for chunk in stride(from: 0, to: erfcArgs.count, by: 25) {
        let xs = erfcArgs[chunk..<min(chunk + 25, erfcArgs.count)]
        lines.append("erfc" + xs.map { " " + d($0) + " " + d(erfc($0)) }.joined())
        hit("rng-erfc")
    }
    return lines
}

// MARK: - Ring proximity: the path-loss distance (`pow`) and its text
//
// Every reading from −110 to +10 dBm (the distance exists only for −99…−1), then the sentinels and the
// 32-bit ends. The text is written as its UTF-8 bytes so its "≈" and spaces survive the token format.

let proxReadings: [[Int?]] = [
    Array(-110 ... -60),
    Array(-59 ... 10),
    [nil, Int(Int32.min), Int(Int32.max), -1_000, -101, -100, -99, -96, -95, -59, -45, -1, 0, 1, 127],
]

func utf8Token(_ s: String) -> String {
    "u" + s.utf8.map { b in let h = String(b, radix: 16); return h.count == 1 ? "0" + h : h }.joined()
}

func proxCase(_ index: Int) -> Case {
    let readings = proxReadings[index]
    var c = Case(id: String(format: "prx-%03d", index), kind: "prox", shape: "rssi")
    c.inputs = ["r" + readings.map { " " + ($0.map { String($0) } ?? "-") }.joined()]
    c.goldens = [
        "pm" + readings.map { " " + optD(RingProximity.approximateMeters(forRSSI: $0)) }.joined(),
        "pf" + readings.map { " " + optD(RingProximity.approximateFeet(forRSSI: $0)) }.joined(),
        "pt" + readings.map { " " + (RingProximity.distanceText(forRSSI: $0).map(utf8Token) ?? "-") }.joined(),
    ]
    for r in readings {
        let feet = RingProximity.approximateFeet(forRSSI: r)
        hit("prox-no-distance", feet == nil)
        hit("prox-right-here", feet.map { $0 < 1.5 } ?? false)
        hit("prox-twenty-plus", feet.map { $0 >= 20 } ?? false)
        hit("prox-feet-rounded-up", feet.map { $0 >= 1.5 && $0 < 20 && $0 - $0.rounded(.down) >= 0.5 } ?? false)
        hit("prox-feet-rounded-down", feet.map { $0 >= 1.5 && $0 < 20 && $0 - $0.rounded(.down) < 0.5 } ?? false)
    }
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
    + (0..<(assessShapes.count * assessCasesPerShape)).map(assessCase)
    + (0..<indexSweeps.count).map(indexSweepCase)
    + (0..<(pctShapes.count * 4)).map(percentileCase)
    + (0..<(rankShapes.count * 4)).map(rankCase)
    + (0..<4).map(seCase)
    + (0..<(tailShapes.count * 4)).map(tailCase)
    + (0..<(cycleShapes.count * cycleCasesPerShape)).map(cycleCase)
    + (0..<proxReadings.count).map(proxCase)
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
let random = [header] + randomLines()
let coverageLines = [header] + coverage.keys.sorted().map { "branch \($0) \(coverage[$0]!)" }

func write(_ lines: [String], _ file: String) throws {
    try (lines.joined(separator: "\n") + "\n").write(to: outDir.appendingPathComponent(file), atomically: true, encoding: .utf8)
}
do {
    try write(inputs, "inputs.txt")
    try write(goldens, "goldens.txt")
    try write(random, "random.txt")
    try write(coverageLines, "coverage.txt")
} catch {
    FileHandle.standardError.write("write failed: \(error)\n".data(using: .utf8)!)
    exit(1)
}
let elapsed = Date().timeIntervalSince(started)
print("cases \(cases.count), input lines \(inputs.count), golden lines \(goldens.count), random lines \(random.count), generated in \(Int((elapsed * 1000).rounded())) ms")
for l in coverageLines.dropFirst() { print(l) }
