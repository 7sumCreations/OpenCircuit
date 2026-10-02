// VitalsDifferential — runs upstream's vitals and energy functions over seeded synthetic inputs and
// writes three files into the directory given as the only argument:
//
//   inputs.txt    every case's inputs
//   goldens.txt   upstream's canonical outputs for each case
//   coverage.txt  how many cases reached each named branch
//
// Everything is deterministic (SplitMix64 from fixed seeds), synthetic and locale-free: single
// spaces, '\n' line ends, integers in decimal, doubles as "d" followed by their IEEE-754 bit pattern
// in 16 lowercase hex digits, times as integer Unix epoch MILLISECONDS (always a multiple of 250, so
// every time and every duration is exact in binary), "-" for an absent value. Rerunning on the same
// upstream commit reproduces every byte. `VitalsDifferentialTest` (Kotlin) reads these files;
// Gradle never runs this program.
//
// inputs.txt, per case:
//   case <id> <kind> <shape>
//   kind rr (RR intervals, ms):
//     g <groups>                        every RR group in order, one token each: its values joined
//                                       by commas, "_" for an empty group
//     ws <window sizes>                 the rolling windows to evaluate
//   kind bpm (a heart-rate series, one reading per interval):
//     b <ints>                          the series
//     p <maxHR> <restingHR> <seconds>   one strain parameter set (seconds is a double)
//   kind hrs (timestamped heart-rate samples):
//     s <bpm> <start ms> <end ms> ...   every sample, in order, as triples on one line
//     p <maxHR> <restingHR>             one TRIMP parameter set
//     k <maxHRs>                        maxHR values for the TRIMP energy
//   kind energy (a profile and its energy queries):
//     profile <age> <weightKg> <heightCm> <male|female>
//     m <k> <metres>                    distance energy query k
//     n <k> <steps>                     step energy query k
//     w <k> <avgHR> <seconds>           Keytel workout energy query k
//     r <k> <restingHR|-> <baseline|->  resting-HR-scaled basal energy query k
//     prior <k> <minDays> <doubles>     resting-HR baseline query k (prior days, oldest first)
//   end
// goldens.txt, per case:
//   case <id>
//   rr:     clean <ints>                HRV.cleanRR of the groups
//           rmssd <int|->               HRV.rmssd of the cleaned list
//           rmssdraw <int|->            HRV.rmssd of the groups flattened without cleaning
//           stress <double>             Stress.index of the cleaned list
//           stressraw <double>          Stress.index of the groups flattened without cleaning
//           roll <ws> <ints>            HRV.rollingRMSSD(cleaned, windowSize: ws)
//           sum <ws> <min> <max> <avg> | sum <ws> none   HRV.summary(cleaned, windowSize: ws)
//   bpm:    trimp <k> <double|->        Strain.edwardsTRIMP(bpms:...) with parameter set k
//           strain <k> <double|->       Strain(maxHR:restingHR:).calculate(bpms:sampleSeconds:)
//   hrs:    trimphr <k> <double|->      Strain.edwardsTRIMP(hrSamples:...) with parameter set k
//           kcal <maxHR> <double>       Calories.activeKcal(hrSamples:maxHR:)
//   energy: bmr <perDay> <perHour>      Calories.bmrKcalPerDay / bmrKcalPerHour
//           dist <k> <kcal>             Calories.activeKcalFromDistance
//           steps <k> <metres> <kcal>   DistanceEstimate.meters / Calories.activeKcalFromSteps
//           keytel <k> <kcal>           Calories.workoutActiveKcal
//           basal <k> <scale> <kcal/h>  Calories.restingEnergyScale / basalKcalPerHour
//           baseline <k> <double|->     Calories.restingBaselineBpm(prior:minDays:)
//   end
//
// Shapes include, from the start, the inputs a deliberate difference from upstream would touch:
// duplicated groups and samples, reversed samples, heart-rate days that cross both 2026 clock
// changes in New York, and unreadable (NaN / infinite) resting HR and baseline readings.

import Foundation
import OpenCircuitKit

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
func groupToken(_ g: [Int]) -> String { g.isEmpty ? "_" : g.map { String($0) }.joined(separator: ",") }
func ms(_ t: Date) -> Int64 {
    let v = t.timeIntervalSince1970 * 1000
    precondition(v == v.rounded() && Int64(v) % 250 == 0, "time \(v) is not on the 250 ms grid")
    return Int64(v)
}

var coverage: [String: Int] = [:]
func hit(_ branch: String, _ yes: Bool = true) { if yes { coverage[branch, default: 0] += 1 } }

struct Case {
    let id: String
    let kind: String
    let shape: String
    var inputs: [String] = []
    var goldens: [String] = []
}

extension Array {
    /// A Fisher–Yates shuffle driven by the generator, so it is reproducible.
    func shuffledDeterministically(_ rng: inout SplitMix64) -> [Element] {
        var a = self
        if a.count < 2 { return a }
        for i in stride(from: a.count - 1, to: 0, by: -1) { a.swapAt(i, rng.int(0, i)) }
        return a
    }
}

// MARK: - RR intervals → HRV, stress

let rrShapes = ["rest", "exercise", "artifact", "duplicated", "constant", "short", "tiny", "extreme", "tied"]
let rrCasesPerShape = 8

func rrCase(_ index: Int) -> Case {
    let shape = rrShapes[index / rrCasesPerShape]
    var rng = SplitMix64(state: 0x5252_0000 &+ UInt64(index))
    var groups: [[Int]] = []
    func walk(_ n: Int, base: Int, step: Int) -> [Int] {
        var v = base
        var out: [Int] = []
        for _ in 0..<n {
            v = max(250, v + rng.int(-step, step))
            out.append(v)
        }
        return out
    }
    func grouped(_ values: [Int]) -> [[Int]] {
        var out: [[Int]] = []
        var k = 0
        while k < values.count {
            let size = rng.int(1, 4)
            out.append(Array(values[k ..< min(values.count, k + size)]))
            k += size
        }
        return out
    }
    switch shape {
    case "rest":
        groups = grouped(walk(rng.int(320, 900), base: rng.int(800, 1100), step: 25))
    case "exercise":
        groups = grouped(walk(rng.int(320, 900), base: rng.int(400, 600), step: 10))
    case "artifact":
        let clean = grouped(walk(rng.int(320, 700), base: rng.int(750, 1000), step: 30))
        groups = clean.map { g in g.map { v in rng.chance(6) ? (rng.chance(50) ? 0 : -rng.int(1, 900)) : v } }
        for _ in 0..<rng.int(1, 6) { groups.insert([], at: rng.int(0, groups.count)) }
    case "duplicated":
        groups = grouped(walk(rng.int(160, 450), base: rng.int(800, 1000), step: 20)).flatMap { [$0, $0] }
    case "constant":
        groups = grouped(Array(repeating: rng.int(500, 1200), count: rng.int(300, 420)))
    case "short":
        groups = grouped(walk(rng.int(2, 299), base: rng.int(700, 1000), step: 25))
    case "tiny":
        groups = index % 3 == 0 ? [] : [walk(index % 3, base: 900, step: 40)]
    case "extreme":
        let pool = [Int(Int32.min), Int(Int32.max), Int(Int32.max) - 1, 1, 2_000_000_000, 1_999_999_950]
        groups = grouped((0..<rng.int(300, 340)).map { _ in rng.pick(pool) })
    default: // tied: two histogram bins with the same count
        let a = rng.int(10, 20) * 50 + rng.int(0, 49)
        let b = a + rng.int(2, 6) * 50
        let half = rng.int(150, 200)
        let values = Array(repeating: a, count: half) + Array(repeating: b, count: half)
        groups = grouped(values.shuffledDeterministically(&rng))
    }
    let windows = [300, rng.int(2, 60)]

    var c = Case(id: String(format: "rr-%03d", index), kind: "rr", shape: shape)
    c.inputs = ["g" + groups.map { " " + groupToken($0) }.joined(), "ws" + ints(windows)]
    let raw = groups.flatMap { $0 }
    let clean = HRV.cleanRR(groups)
    let stress = Stress.index(rr: clean)
    c.goldens.append("clean" + ints(clean))
    c.goldens.append("rmssd \(optI(HRV.rmssd(clean)))")
    c.goldens.append("rmssdraw \(optI(HRV.rmssd(raw)))")
    c.goldens.append("stress \(d(stress))")
    c.goldens.append("stressraw \(d(Stress.index(rr: raw)))")
    for ws in windows {
        let series = HRV.rollingRMSSD(clean, windowSize: ws)
        c.goldens.append("roll \(ws)" + ints(series))
        if let s = HRV.summary(clean, windowSize: ws) {
            c.goldens.append("sum \(ws) \(s.min) \(s.max) \(s.avg)")
        } else {
            c.goldens.append("sum \(ws) none")
            hit("hrv-summary-none")
        }
        hit("hrv-rolling-empty", series.isEmpty)
    }
    let lo = clean.min() ?? 0
    let hi = clean.max() ?? 0
    let degenerate = Double(hi - lo) / 1000 < 0.0001
    hit("hrv-rmssd-none", HRV.rmssd(clean) == nil)
    hit("hrv-clean-dropped", clean.count < raw.count)
    hit("hrv-duplicated-groups", shape == "duplicated")
    hit("hrv-64-bit", (HRV.rmssd(raw) ?? 0) > Int(Int32.max))
    hit("stress-degenerate", degenerate)
    hit("stress-capped", stress == 10.0 && !degenerate)
    hit("stress-scored", stress < 10.0)
    hit("stress-negative-input", raw.contains { $0 < 0 })
    return c
}

// MARK: - A heart-rate series → TRIMP, strain

let bpmShapes = ["rest", "workout", "mixed", "short", "exact", "duplicated", "extreme"]
let bpmCasesPerShape = 6
let strainParams: [(Int, Int, Double)] = [
    (190, 60, 1), (200, 50, 5), (180, 60, 60), (60, 60, 1), (150, 170, 1),
    (190, 60, 0), (190, 60, -3), (190, 60, .nan), (190, 60, .infinity), (190, 60, 0.25), (190, 60, 600),
]

func bpmCase(_ index: Int) -> Case {
    let shape = bpmShapes[index / bpmCasesPerShape]
    var rng = SplitMix64(state: 0x4250_0000 &+ UInt64(index))
    var bpms: [Int] = []
    switch shape {
    case "rest": bpms = (0..<rng.int(600, 1500)).map { _ in rng.int(52, 78) }
    case "workout": bpms = (0..<rng.int(600, 1800)).map { _ in rng.int(100, 188) }
    case "mixed": bpms = (0..<rng.int(600, 1500)).map { _ in rng.chance(40) ? rng.int(120, 195) : rng.int(55, 110) }
    case "short": bpms = (0..<rng.int(1, 599)).map { _ in rng.int(60, 180) }
    case "exact": bpms = (0..<600).map { _ in rng.int(60, 190) }
    case "duplicated": bpms = (0..<300).map { _ in rng.int(70, 185) }.flatMap { [$0, $0] }
    default:
        let pool = [Int(Int32.min), Int(Int32.max), 0, -40, 400, 75, 130, 170]
        bpms = (0..<600).map { _ in rng.pick(pool) }
    }
    var c = Case(id: String(format: "bpm-%03d", index), kind: "bpm", shape: shape)
    c.inputs = ["b" + ints(bpms)] + strainParams.map { "p \($0.0) \($0.1) \(d($0.2))" }
    for (k, p) in strainParams.enumerated() {
        let trimp = Strain.edwardsTRIMP(bpms: bpms, maxHR: p.0, restingHR: p.1, sampleSeconds: p.2)
        let strain = Strain(maxHR: p.0, restingHR: p.1).calculate(bpms: bpms, sampleSeconds: p.2)
        c.goldens.append("trimp \(k) \(optD(trimp))")
        c.goldens.append("strain \(k) \(optD(strain))")
        let s = strain ?? 0
        hit("strain-none-few", bpms.count < Strain.minReadings && p.0 > p.1)
        hit("strain-none-params", bpms.count >= Strain.minReadings && p.0 <= p.1)
        hit("strain-zero", strain == 0)
        hit("strain-above-21", s > 21)
        hit("strain-scored", s > 0 && s.isFinite)
        hit("trimp-nan", trimp?.isNaN == true)
        hit("trimp-interval-fallback", trimp != nil && !(p.2 > 0))
    }
    hit("strain-duplicated-series", shape == "duplicated")
    return c
}

// MARK: - Timestamped heart-rate samples → TRIMP, TRIMP energy

let hrsShapes = ["day", "spring-forward", "fall-back", "reversed", "duplicated", "quarter-second", "sparse", "point"]
let hrsCasesPerShape = 3
let trimpParams: [(Int, Int)] = [(180, 60), (190, 60), (200, 45), (60, 60)]
let kcalMaxHRs = [180, 190, 60, 50]

/// The start of a local day in New York and its length in seconds (23 h and 25 h on the 2026 changes).
func newYorkDay(_ y: Int, _ m: Int, _ dd: Int) -> (Date, Int) {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: "America/New_York")!
    let noon = cal.date(from: DateComponents(year: y, month: m, day: dd, hour: 12))!
    let start = cal.startOfDay(for: noon)
    let next = cal.date(byAdding: .day, value: 1, to: start)!
    return (start, Int(next.timeIntervalSince(start)))
}

func hrsCase(_ index: Int) -> Case {
    let shape = hrsShapes[index / hrsCasesPerShape]
    var rng = SplitMix64(state: 0x4853_0000 &+ UInt64(index))
    var samples: [HRSample] = []
    /// One sample every `cadence` seconds over `seconds`; each lasts `quarters()` quarter-seconds.
    func series(from start: Date, seconds: Int, cadence: Int, quarters: () -> Int) {
        var t = 0
        while t < seconds {
            let s = start.addingTimeInterval(Double(t))
            let e = s.addingTimeInterval(Double(quarters()) / 4)
            let bpm = rng.chance(30) ? rng.int(110, 190) : rng.int(50, 105)
            samples.append(HRSample(bpm: bpm, start: s, end: e))
            t += cadence
        }
    }
    let offset = Double(index % hrsCasesPerShape * 600)
    switch shape {
    case "day":
        series(from: Date(timeIntervalSince1970: Double(1_780_000_000 + index * 86_400)), seconds: 86_400, cadence: 120) { 480 }
    case "spring-forward":
        let (start, len) = newYorkDay(2026, 3, 8)
        series(from: start.addingTimeInterval(offset), seconds: len, cadence: 120) { 480 }
    case "fall-back":
        let (start, len) = newYorkDay(2026, 11, 1)
        series(from: start.addingTimeInterval(offset), seconds: len, cadence: 120) { 480 }
    case "reversed":
        series(from: Date(timeIntervalSince1970: 1_781_000_000 + offset), seconds: 700 * 60, cadence: 60) { -4 * rng.int(1, 90) }
    case "duplicated":
        series(from: Date(timeIntervalSince1970: 1_782_000_000 + offset), seconds: 350 * 60, cadence: 60) { 240 }
        samples = samples.flatMap { [$0, $0] }
    case "quarter-second":
        series(from: Date(timeIntervalSince1970: 1_783_000_000.25 + offset), seconds: 900, cadence: 1) { rng.int(1, 9) }
    case "sparse":
        series(from: Date(timeIntervalSince1970: 1_784_000_000 + offset), seconds: rng.int(1, 599) * 60, cadence: 60) { 240 }
    default: // point: end == start, the shape the history decoder emits
        series(from: Date(timeIntervalSince1970: 1_785_000_000 + offset), seconds: 800 * 150, cadence: 150) { 0 }
    }
    var c = Case(id: String(format: "hrs-%03d", index), kind: "hrs", shape: shape)
    c.inputs = ["s" + samples.map { " \($0.bpm) \(ms($0.start)) \(ms($0.end))" }.joined()]
        + trimpParams.map { "p \($0.0) \($0.1)" } + ["k" + ints(kcalMaxHRs)]
    for (k, p) in trimpParams.enumerated() {
        c.goldens.append("trimphr \(k) \(optD(Strain.edwardsTRIMP(hrSamples: samples, maxHR: p.0, restingHR: p.1)))")
    }
    for m in kcalMaxHRs {
        let kcal = Calories.activeKcal(hrSamples: samples, maxHR: m)
        c.goldens.append("kcal \(m) \(d(kcal))")
        hit("kcal-zero", kcal == 0)
        hit("kcal-positive", kcal > 0)
    }
    let durations = samples.map { $0.end.timeIntervalSince($0.start) }
    hit("hrs-clock-change-day", shape == "spring-forward" || shape == "fall-back")
    hit("hrs-duration-fallback", durations.contains { !($0 > 0) })
    hit("hrs-fractional-duration", durations.contains { $0 > 0 && $0 != $0.rounded() })
    hit("hrs-duplicated-samples", shape == "duplicated")
    return c
}

// MARK: - Profiles → BMR, basal, distance, step and Keytel energy, resting-HR baseline

let energyShapes = ["typical", "edge-profile", "nonfinite"]
let energyCasesPerShape = 10
let edgeProfiles: [UserProfile] = [
    UserProfile(age: 0, weightKg: 0, heightCm: 0, sex: .female),
    UserProfile(age: 30, weightKg: -70, heightCm: 180, sex: .male),
    UserProfile(age: 30, weightKg: .nan, heightCm: 180, sex: .male),
    UserProfile(age: Int(Int32.max), weightKg: 80, heightCm: 180, sex: .female),
    UserProfile(age: -5, weightKg: 300, heightCm: 250, sex: .male),
    UserProfile(age: 120, weightKg: 25, heightCm: 100, sex: .female),
    UserProfile(age: 30, weightKg: .infinity, heightCm: 180, sex: .male),
    UserProfile(age: 45, weightKg: -0.0, heightCm: -0.0, sex: .female),
    UserProfile(age: 1, weightKg: 3.5, heightCm: 50, sex: .male),
    UserProfile(age: 99, weightKg: 1e300, heightCm: 1e300, sex: .female),
]

func energyCase(_ index: Int) -> Case {
    let shape = energyShapes[index / energyCasesPerShape]
    var rng = SplitMix64(state: 0x454E_0000 &+ UInt64(index))
    let age = rng.int(18, 90)
    let weight = rng.real(40, 150)
    let height = rng.real(140, 205)
    var profile = UserProfile(age: age, weightKg: weight, heightCm: height, sex: rng.chance(50) ? .male : .female)
    if shape == "edge-profile" { profile = edgeProfiles[index % edgeProfiles.count] }

    var c = Case(id: String(format: "energy-%03d", index), kind: "energy", shape: shape)
    let sex = profile.sex == .male ? "male" : "female"
    c.inputs.append("profile \(profile.age) \(d(profile.weightKg)) \(d(profile.heightCm)) \(sex)")
    let bmr = Calories.bmrKcalPerDay(profile: profile)
    c.goldens.append("bmr \(d(bmr)) \(d(Calories.bmrKcalPerHour(profile: profile)))")
    hit("bmr-negative", bmr < 0)

    let metres: [Double] = [rng.real(0, 20_000), rng.real(0, 500), 0, -5, -0.0, .nan, .infinity]
    for (k, m) in metres.enumerated() {
        c.inputs.append("m \(k) \(d(m))")
        c.goldens.append("dist \(k) \(d(Calories.activeKcalFromDistance(meters: m, profile: profile)))")
        hit("dist-nonpositive", !(m > 0))
    }
    let steps = [rng.int(0, 30_000), rng.int(1, 200), 0, -100, Int(Int32.max)]
    for (k, n) in steps.enumerated() {
        c.inputs.append("n \(k) \(n)")
        let metresOut = DistanceEstimate.meters(steps: n)
        let kcal = Calories.activeKcalFromSteps(steps: n, profile: profile)
        c.goldens.append("steps \(k) \(d(metresOut)) \(d(kcal))")
    }
    let w0 = (rng.int(40, 190), rng.real(0, 7200))
    let w1 = (rng.int(90, 180), 305.0)
    let w7 = (rng.int(60, 190), Double.infinity)
    let workouts: [(Int, Double)] = [w0, w1, (40, 600), (0, 300), (120, 0), (120, -5), (120, .nan), w7]
    for (k, w) in workouts.enumerated() {
        let kcal = Calories.workoutActiveKcal(avgHR: w.0, durationSeconds: w.1, profile: profile)
        c.inputs.append("w \(k) \(w.0) \(d(w.1))")
        c.goldens.append("keytel \(k) \(d(kcal))")
        hit("keytel-zero", kcal == 0)
        hit("keytel-positive", kcal > 0)
    }
    let r1 = rng.real(45, 90)
    let b2 = rng.real(50, 75)
    let r3 = rng.real(45, 90)
    let b3 = rng.real(50, 75)
    let r4 = rng.real(45, 90)
    let b4 = rng.real(50, 75)
    var basal: [(Double?, Double?)] = [
        (nil, nil), (r1, nil), (nil, b2), (r3, b3), (r4, b4), (200, 60), (10, 60), (70, 0), (70, -1), (70, .nan),
    ]
    if shape == "nonfinite" {
        // Upstream clamps these unreadable readings; the port reads them as missing (PORTING.md).
        basal += [(.nan, 60), (.infinity, 60), (-.infinity, 60), (70, .infinity)]
    }
    for (k, q) in basal.enumerated() {
        let scale = Calories.restingEnergyScale(restingHR: q.0, baselineRestingHR: q.1)
        let kcal = Calories.basalKcalPerHour(profile: profile, restingHR: q.0, baselineRestingHR: q.1)
        c.inputs.append("r \(k) \(optD(q.0)) \(optD(q.1))")
        c.goldens.append("basal \(k) \(d(scale)) \(d(kcal))")
        let nonFinite = (q.0.map { !$0.isFinite } ?? false) || (q.1.map { !$0.isFinite } ?? false)
        hit("scale-neutral", scale == 1.0)
        hit("scale-clamped-high", scale == 1.2)
        hit("scale-clamped-low", scale == 0.8)
        hit("scale-linear", scale > 0.8 && scale < 1.2 && scale != 1.0)
        hit("scale-nonfinite-input", nonFinite)
    }
    func days(_ n: Int, _ lo: Double, _ hi: Double) -> [Double] { (0..<n).map { _ in rng.real(lo, hi) } }
    var priors: [(Int, [Double])] = []
    priors.append((3, days(rng.int(0, 2), 48, 80)))
    priors.append((3, days(rng.int(3, 4), 48, 80)))
    priors.append((3, days(rng.int(5, 15), 48, 80) + [rng.real(90, 120)]))
    priors.append((3, days(rng.int(20, 40), 48, 80)))
    priors.append((1, days(1, 48, 80)))
    priors.append((0, [60, 61, 62]))
    priors.append((5, days(4, 48, 80)))
    priors.append((3, [-0.0, 0.0, -0.0]))
    if shape == "nonfinite" {
        priors.append((3, [rng.real(55, 65), .nan, rng.real(55, 65)]))
        priors.append((3, [.nan] + days(9, 55, 65)))
        priors.append((3, days(9, 55, 65) + [.infinity]))
        priors.append((3, [.infinity, rng.real(55, 65), -.infinity]))
    }
    for (k, p) in priors.enumerated() {
        let b = Calories.restingBaselineBpm(prior: p.1, minDays: p.0)
        c.inputs.append("prior \(k) \(p.0)" + p.1.map { " " + d($0) }.joined())
        c.goldens.append("baseline \(k) \(optD(b))")
        hit("baseline-none", b == nil)
        hit("baseline-plain-mean", b != nil && p.1.count < Calories.minTrimmedBaselineDays)
        hit("baseline-trimmed", b != nil && p.1.count >= Calories.minTrimmedBaselineDays)
        hit("baseline-nonfinite", b.map { !$0.isFinite } ?? false)
    }
    return c
}

// MARK: - Main

let args = CommandLine.arguments
guard args.count == 2 else {
    FileHandle.standardError.write("usage: VitalsDifferential <output directory>\n".data(using: .utf8)!)
    exit(2)
}
let outDir = URL(fileURLWithPath: args[1], isDirectory: true)

let started = Date()
let cases = (0..<(rrShapes.count * rrCasesPerShape)).map(rrCase)
    + (0..<(bpmShapes.count * bpmCasesPerShape)).map(bpmCase)
    + (0..<(hrsShapes.count * hrsCasesPerShape)).map(hrsCase)
    + (0..<(energyShapes.count * energyCasesPerShape)).map(energyCase)
let header = "# Generated by android/tools/sleep-differential (VitalsDifferential) from upstream OpenCircuitKit; do not edit by hand."
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
