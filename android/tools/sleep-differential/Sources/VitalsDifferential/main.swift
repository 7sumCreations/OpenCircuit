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
//   kind day (one synthetic day of heart rate, step windows and sleep):
//     profile <age> <weightKg> <heightCm> <male|female>
//     zone <time zone identifier>       the zone the daily resting HR groups days in
//     day <ms>                          the start of the local day (the attribution's day start)
//     steps <int>                       the day's step counter
//     s <bpm> <start ms> <end ms> ...   every heart-rate sample, in order, as triples on one line
//     w <start ms> <end ms> <delta> ... every step window, in order, as triples on one line
//     sw <start ms> <end ms> | sw -     the sleep window excluded from exercise and energy
//     seg <start ms> <end ms> <stage> ...  the sleep segments (stage raw names), in order
//     bw <doubles>                      the attribution bucket widths to evaluate
//     lp <ms>...                        the ledger replay's flush times, in order: flush k sees the
//                                       heart rate that starts before it and the step windows that
//                                       end by it (the last two flushes see the whole day)
//     lw <doubles>                      the day's workout energy known at each of those flushes
//     la <ms>...                        a second replay's flush times, data arriving out of order
//     lah <digits> | lah -              per heart-rate sample, in order, the flush it arrives by
//     law <digits> | law -              per step window, in order, the flush it arrives by
//     ls <doubles>                      legacy written totals to seed the day's ledger from
//     rq (<anchor ms|-> <notBefore ms|-> <now ms> <kcal>)...   write-window queries
//   kind base (prior daily values, oldest first, and today's):
//     pr <doubles> / ps <doubles> / ph <doubles>   resting HR, overnight SpO2, overnight HRV days
//     td <rhr> <spo2> <hrv>             today's three values
//     to <doubles>                      skin-temperature offsets to evaluate
//     bm <ints>                         prior bedtimes, minutes since midnight (not normalised)
//     bt <int>                          tonight's bedtime
//     (the noise floors for z are 0, 0.3 and 5, fixed in both programs)
//   kind temp (one night of skin temperature):
//     w <start ms> <end ms>             the sleep window (closed)
//     s (<ms> <celsius>)*               every reading, in order
//     n (<night ms> <celsius>)*         the prior nights' means, in order
//     wn <ints>                         baseline windows to evaluate
//     tn <double>                       tonight's mean
//     pv <double|->                     the previous night's mean
//   kind wb (readiness sub-scores, one query per line):
//     q <sleep|-> <stress|-> <vitals status raw name|-> <activity|->
//   kind act (activity goals, one query per line):
//     q <steps> <stepGoal> <minutes> <minutesGoal> <kcal> <kcalGoal>   (the last four are doubles)
//   kind trend (one query per line):
//     q <today> <deadband> <prior scores...>
//   kind goal (a window of goal-ring days around a clock change, with its nights and naps):
//     zone <time zone identifier>       the zone days, weekends and sleep credit are judged in
//     szone <time zone identifier>      the zone the summaries are judged in
//     goals <workday steps> <weekend steps> <kcal> <minutes> <workday sleep> <weekend sleep>
//     now <ms>                          build's "today" reference
//     di <ms> <steps|-> <kcal|-> <minutes|-> <sleep|->   one day rollup (any order, any time of day)
//     nt <night key ms> <in-bed start ms|-> <in-bed end ms|-> <minutes>   one stored night
//     np <start ms> <end ms> <minutes>  one nap
//     sn <ms>...                        the instants the summaries are judged at
//     wq <ms>...                        the instants the weekend rule and goal selection are asked at
//   kind fmt (the formatter's three number shapes):
//     v <doubles>                       the values
//     fd <ints>                         the fraction digits, each applied to every value
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
//   day:    rhr <double|->              RestingHR.value(hr:sleep:) over every sample and segment
//           rhrdaily <n> (<day ms> <bpm>)*   RestingHR.dailyValues(hr:sleep:calendar:) in the zone
//           rb <double|->               ExerciseMinutes.restingBaseline
//           thr <maxHR> <int> <int>     ExerciseMinutes.threshold(maxHR:) and with the resting baseline
//           pieces <n> (<start ms> <end ms> <bpm>)*   ExerciseMinutes.elevatedPieces (sleep excluded)
//           minutes <double> <double>   ExerciseMinutes.estimate, shipped and personalised models
//           legacy <kcal> <minutes>     Calories.legacyDailyEstimate
//           est <k> <kcal> <minutes> <n> (<start ms> <end ms> <hrKcal> <stepKcal> <minutes>)*
//                                       Calories.dailyEstimate with bucket width k and the day start
//           lplan <k> <plan>            ActiveEnergyLedger.plan at flush k of the in-order replay,
//                                       over that flush's 900 s daily estimate, every plan's state
//                                       committed before the next (watermarks, carry, saved total,
//                                       workout energy credited)
//           aplan <k> <plan>            the same for the out-of-order replay (no workout energy)
//           seed <k> <n> <nz> (<slot> <mark>)* <carry>   ActiveEnergyLedger.seed of the whole day's
//                                       buckets with legacy total k: the marks' count, then the
//                                       non-zero marks, then the carry
//           splan <k> <plan>            plan at 26 h from that seed
//           win (<start ms> <end ms> | - -)*   ActiveEnergyWindow.resolve for each query
//           bad <k> <plan>              (hostile days) plan of the whole day at 26 h from an
//                                       unreadable stored state k: a NaN watermark, a watermark of
//                                       -40, a NaN carry, a NaN workout credit, a NaN saved total
//     where <plan> is <n writes> (<start ms> <end ms> <kcal>)* <marks count> <non-zero marks>
//     (<slot> <mark>)* <carry remaining> <workout consumed> <total kcal>
//   base:   (r, s, h = resting HR, overnight SpO2, overnight HRV; inputs below)
//           rst <r|s|h> <median> <mad> <n> | rst <r|s|h> none   RobustBaseline.stats of that series
//           z <r|s|h> <double>... | z <r|s|h> none   RobustBaseline.z of today's value at each noise floor
//           vst <r|s|h> <mean> <sd> <n> | vst <r|s|h> none     VitalsBaseline.stats of that series
//           cls <r|s|h> <severity> <mean|-> <sd|-> <n|-> <delta> <direction>   VitalsBaseline.classify
//           temp <severity>...          VitalsBaseline.tempSeverity of each offset
//           fever <0|1>...              VitalsBaseline.suspectedFever(resting HR today and prior, offset)
//           rep <k> <status> <fever 0|1> <n> (<vital|temp> <severity> <delta> <direction> <mean|->)*
//                                       VitalsBaseline.report of all three vitals with offset k
//                                       (k = the offsets' count: no offset)
//           circ <median|-> <delta|->   RobustBaseline.circularMedianMinutes of the prior bedtimes and
//                                       circularDeltaMinutes from tonight's bedtime to it
//   temp:   cov <double>                SkinTempBaseline.coverage of every reading over the window
//           ver <k> (pub <double> | nm | rej <double>)   nightlyVerdict, k 0 = shipped gates,
//                                       k 1 = minSamples 1 and minCoverage 0
//           nmw <double|->              nightlyMean(samples:in:) with the shipped gates
//           nml <double|->              nightlyMean of every reading's temperature (count floor only)
//           base <double|->...          baseline(priorNights:windowNights:) for each window
//           off <double|->              offset(tonight:baseline:) against the 30-night baseline
//           band <raw name|->           deviationBand of that offset
//           flags <a> <b> <c> <d>       anomalyFlags(tonight, 30-night baseline, previous night):
//                                       abnormal rise, abnormal drop, fluctuation rise, drop (0 | 1)
//           nrep <nightly> <baseline|-> <offset|-> <band|-> <a> <b> <c> <d>   report (30 nights)
//   wb:     wbs <k> <score|-> <tier|-> <anchored score|-> <sleep|-> <recovery|-> <vitals|-> <activity|->
//                                       WellnessBalance.score and anchoredScore of query k; the
//                                       factors in declaration order, "-" for an absent one
//   act:    acs <k> <score> <tier> <steps|-> <activeMinutes|-> <activeKcal|->
//                                       ActivityScore.score of query k, factors as above
//   trend:  trd <k> <up|steady|down>    WellnessBalance.trend of query k
//   goal:   gw <k> <weekend 0|1> <step goal> <sleep goal>   GoalDefaults.isWeekend and the goals'
//                                       stepGoal / sleepGoalMinutes at weekend query k
//           gc <n> (<day ms> <minutes>)*   GoalHistory.sleepCreditByDay of the nights and naps,
//                                       days in ascending order
//           gd <k> <day ms> <present> <met> <partial 0|1> <attainment|-> <fraction>×4
//                                       day k of GoalHistory.build (oldest first); present and met
//                                       as four 0/1 digits and the fractions in ring order (steps,
//                                       active kcal, activity minutes, sleep minutes)
//           gs <k> <days with data> <days all closed> <current> <longest> <met count|->×4
//              <data count|->×4         GoalHistory.summarize of the built days at summary instant k
//   fmt:    fmt <j> <a> | <b> | <c> | <d> | <e> | <f>   value-major pair j of the values and the
//                                       fraction digits: UnitsFormatter.temperature in °C and °F,
//                                       temperatureDelta in °C and °F, distance of value × 1000 m in
//                                       km and of value × 1609.344 m in miles (the strings verbatim)
//   end
//
// Shapes include, from the start, the inputs a deliberate difference from upstream would touch:
// duplicated groups and samples, reversed samples, heart-rate days that cross both 2026 clock
// changes in New York, unreadable (NaN / infinite) resting HR and baseline readings, bucket
// widths below one second and above one billion seconds, unreadable stored ledger states,
// unreadable prior days, todays, offsets, readings and nights, a zero noise floor against a
// perfectly flat baseline, readings exactly on the end of a whole-hour night, readiness and
// activity inputs whose rounded score depends on the order the factors are summed, sub-scores and
// goals outside their ranges, trend sums that leave 32 bits, goal days across both 2026 clock
// changes in three zones (one of them a day without a midnight), duplicated, unsorted and future
// day rows, summaries judged in another zone, and formatter values at exact binary ties, signed
// zero, non-finite, huge and with negative or very large fraction digits.
// Upstream's ledger and both scores add a dictionary's values (seeded per process): regenerate.sh
// runs this program with Swift's deterministic hashing so every run writes the same bytes.

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

// MARK: - Synthetic days → resting HR, exercise minutes, the daily energy estimate

let dayShapes = ["workday", "spring-forward", "fall-back", "duplicated", "unsorted", "steps-only",
                 "hr-only", "straddle", "sparse", "sleep-heavy", "spans", "hostile"]
let dayCasesPerShape = 4
let dayZones = ["America/New_York", "Europe/Athens", "Asia/Kolkata", "Australia/Lord_Howe", "UTC", "America/Santiago"]
let stageCycle: [SleepStage] = [.inBed, .asleepCore, .asleepDeep, .asleepCore, .asleepREM, .awake]
let baseWidths: [Double] = [900, 300, 3600]
let hostileWidths: [Double] = [900, 300, 3600, 0.5, 2e9]

/// The start of a local day in `zone`.
func localDayStart(_ zone: String, _ y: Int, _ m: Int, _ dd: Int) -> Date {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: zone)!
    return cal.startOfDay(for: cal.date(from: DateComponents(year: y, month: m, day: dd, hour: 12))!)
}

func dayCase(_ index: Int) -> Case {
    let shape = dayShapes[index / dayCasesPerShape]
    let slot = index % dayCasesPerShape
    var rng = SplitMix64(state: 0x4441_0000 &+ UInt64(index))
    var zone = dayZones[index % dayZones.count]
    var date = (2026, 6, 1 + index % 28)
    switch shape {
    case "spring-forward": zone = "America/New_York"; date = (2026, 3, 8)
    case "fall-back": zone = "America/New_York"; date = (2026, 11, 1)
    case "workday" where slot == 3: zone = "America/Santiago"; date = (2026, 9, 6) // a day without a midnight
    default: break
    }
    let day = localDayStart(zone, date.0, date.1, date.2)
    func at(_ seconds: Int) -> Date { day.addingTimeInterval(Double(seconds)) }
    let profile = UserProfile(age: rng.int(20, 70), weightKg: rng.real(45, 120), heightCm: rng.real(150, 200),
                              sex: rng.chance(50) ? .male : .female)
    let maxHR = max(220 - profile.age, 1)

    var hr: [HRSample] = []
    var windows: [StepWindow] = []
    var segments: [SleepSegment] = []
    var sleepWindow: DateInterval?
    func epochs(_ a: Int, _ b: Int, cadence: Int, _ bpm: () -> Int) {
        var t = a
        while t < b { hr.append(HRSample(bpm: bpm(), start: at(t))); t += cadence }
    }
    func walk(_ a: Int, minutes: Int, steps: Int) {
        windows.append(StepWindow(start: at(a), end: at(a + minutes * 60), delta: steps))
    }

    // The night before (an evening hour, then asleep), tiled by sleep segments.
    let night = shape != "sparse"
    let sleepStart = -rng.int(1, 3) * 3600 - rng.int(0, 3) * 900
    let sleepEnd = rng.int(5, 7) * 3600 + rng.int(0, 3) * 900
    let rest = rng.int(48, 66)
    if night {
        epochs(sleepStart - 3600, sleepEnd, cadence: 150) { rest + rng.int(-4, 8) }
        var t = sleepStart
        var k = 0
        while t < sleepEnd {
            let end = min(t + rng.int(2, 6) * 900, sleepEnd)
            segments.append(SleepSegment(start: at(t), end: at(end), stage: stageCycle[k % stageCycle.count]))
            t = end
            k += 1
        }
        sleepWindow = DateInterval(start: at(sleepStart), end: at(sleepEnd))
    }
    // Daytime spot reads at the auto-measure cadence, then the next night's start.
    epochs(sleepEnd + 600, 22 * 3600, cadence: 600) { rng.int(60, shape == "steps-only" ? 85 : 98) }
    if night { epochs(24 * 3600 + 1800, 27 * 3600, cadence: 150) { rest + rng.int(-4, 8) } }
    // Bouts of elevated heart rate, some with a walk inside them.
    let bouts = ["steps-only", "sparse", "hostile"].contains(shape) ? 0 : rng.int(1, 3)
    for _ in 0..<bouts {
        let a = rng.int(32, 80) * 900
        let minutes = rng.int(2, 12) * 5
        let lo = rng.chance(30) ? maxHR / 2 : maxHR / 2 + rng.int(5, 30)
        let hi = min(lo + rng.int(5, 40), 200)
        epochs(a, a + minutes * 60, cadence: 150) { rng.int(lo, hi) }
        if shape != "hr-only", rng.chance(50) { walk(a, minutes: minutes, steps: rng.int(100, minutes * 120)) }
    }
    if shape == "sleep-heavy", night {
        epochs(sleepStart + 1800, sleepStart + 3000, cadence: 150) { rng.int(maxHR / 2 + 10, maxHR / 2 + 40) }
    }
    // Step windows across the waking day.
    if shape != "hr-only" {
        for _ in 0..<(shape == "hostile" ? 3 : rng.int(5, 12)) {
            let minutes = shape == "hostile" ? rng.int(1, 2) : rng.pick([5, 10, 15, 15, 30, 45, 90])
            walk(rng.int(28, 86) * 900 + rng.pick([0, 0, 300, 450]), minutes: minutes, steps: rng.int(20, minutes * 130))
        }
    }
    switch shape {
    case "straddle":
        walk(-2700, minutes: 90, steps: rng.int(300, 900))            // opened before midnight
        walk(25 * 3600, minutes: 90, steps: rng.int(300, 900))        // runs past the 26 h span
        walk(12 * 3600, minutes: 0, steps: rng.int(10, 200))           // a point snapshot
    case "hostile":
        windows += [StepWindow(start: at(36_000), end: at(32_400), delta: 500),   // ends before it starts
                    StepWindow(start: at(40_000), end: at(43_600), delta: -300),  // negative delta
                    StepWindow(start: at(30 * 3600), end: at(31 * 3600), delta: 200), // outside the day
                    StepWindow(start: at(50_000), end: at(50_000), delta: 0)]
    case "spans":
        hr = hr.map { s in
            if rng.chance(20) { return HRSample(bpm: s.bpm, start: s.start, end: s.start.addingTimeInterval(Double(rng.int(1, 8) * 60))) }
            if rng.chance(5) { return HRSample(bpm: s.bpm, start: s.start, end: s.start.addingTimeInterval(-300)) }
            return s
        }
    case "duplicated":
        hr = hr.flatMap { [$0, $0] }
        windows += windows.prefix(2)
    case "unsorted":
        hr = hr.shuffledDeterministically(&rng)
        windows = windows.shuffledDeterministically(&rng)
        segments = segments.shuffledDeterministically(&rng)
    case "sparse" where slot == 0:
        windows = [] // a step count with no windows to place it: the legacy degrade
    default: break
    }
    let placed = windows.filter { $0.delta > 0 }.reduce(0) { $0 + $1.delta }
    let steps = shape == "hr-only" ? 0 : (shape == "hostile" && slot == 0 ? -50 : max(0, placed + rng.int(-200, 900)))
    let widths = shape == "hostile" ? hostileWidths : baseWidths

    var c = Case(id: String(format: "day-%03d", index), kind: "day", shape: shape)
    let sex = profile.sex == .male ? "male" : "female"
    c.inputs = [
        "profile \(profile.age) \(d(profile.weightKg)) \(d(profile.heightCm)) \(sex)",
        "zone \(zone)",
        "day \(ms(day))",
        "steps \(steps)",
        "s" + hr.map { " \($0.bpm) \(ms($0.start)) \(ms($0.end))" }.joined(),
        "w" + windows.map { " \(ms($0.start)) \(ms($0.end)) \($0.delta)" }.joined(),
        sleepWindow.map { "sw \(ms($0.start)) \(ms($0.end))" } ?? "sw -",
        "seg" + segments.map { " \(ms($0.start)) \(ms($0.end)) \($0.stage.rawValue)" }.joined(),
        "bw" + widths.map { " " + d($0) }.joined(),
    ]

    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: zone)!
    let rhr = RestingHR.value(hr: hr, sleep: segments)
    let daily = RestingHR.dailyValues(hr: hr, sleep: segments, calendar: cal)
    let baseline = ExerciseMinutes.restingBaseline(hr)
    let pieces = ExerciseMinutes.elevatedPieces(hrSamples: hr, maxHR: maxHR, sleepWindow: sleepWindow)
    let legacy = Calories.legacyDailyEstimate(hrSamples: hr, steps: steps, profile: profile, sleepWindow: sleepWindow)
    c.goldens.append("rhr \(optD(rhr))")
    c.goldens.append("rhrdaily \(daily.count)" + daily.map { " \(ms($0.day)) \(d($0.bpm))" }.joined())
    c.goldens.append("rb \(optD(baseline))")
    c.goldens.append("thr \(maxHR) \(ExerciseMinutes.threshold(maxHR: maxHR)) \(ExerciseMinutes.threshold(maxHR: maxHR, restingHR: baseline))")
    c.goldens.append("pieces \(pieces.count)" + pieces.map { " \(ms($0.start)) \(ms($0.end)) \($0.bpm)" }.joined())
    c.goldens.append("minutes \(d(ExerciseMinutes.estimate(hrSamples: hr, maxHR: maxHR, sleepWindow: sleepWindow))) "
                     + d(ExerciseMinutes.estimate(hrSamples: hr, maxHR: maxHR, sleepWindow: sleepWindow, deriveRestingHR: true)))
    c.goldens.append("legacy \(d(legacy.activeKcal)) \(d(legacy.elevatedMinutes))")
    for (k, w) in widths.enumerated() {
        let e = Calories.dailyEstimate(hrSamples: hr, steps: steps, profile: profile, sleepWindow: sleepWindow,
                                       stepWindows: windows, dayStart: day, bucketSeconds: w)
        c.goldens.append("est \(k) \(d(e.activeKcal)) \(d(e.elevatedMinutes)) \(e.buckets.count)"
                         + e.buckets.map { " \(ms($0.start)) \(ms($0.end)) \(d($0.hrKcal)) \(d($0.stepKcal)) \(d($0.elevatedMinutes))" }.joined())
        hit("day-attributed", !e.buckets.isEmpty)
        hit("day-legacy-fallback", e.buckets.isEmpty)
        hit("day-netted-bucket", e.buckets.contains { $0.hrKcal > 0 && $0.stepKcal > 0 })
        hit("day-subsecond-width", w < 1 && !e.buckets.isEmpty)
        hit("day-wide-width", w > 1e9 && !e.buckets.isEmpty)
    }

    // The write ledger over the same day, flushed as a sync would: each flush prices what has arrived
    // with the 900 s daily estimate, plans against the state the previous flush committed, and commits.
    func estimate900(_ h: [HRSample], _ w: [StepWindow], _ st: Int) -> Calories.DailyEstimate {
        Calories.dailyEstimate(hrSamples: h, steps: st, profile: profile, sleepWindow: sleepWindow,
                               stepWindows: w, dayStart: day, bucketSeconds: 900)
    }
    func planLine(_ tag: String, _ k: Int, _ p: ActiveEnergyLedger.Plan) -> String {
        let nz = p.watermarks.enumerated().filter { $0.element != 0 }
        return "\(tag) \(k) \(p.writes.count)" + p.writes.map { " \(ms($0.start)) \(ms($0.end)) \(d($0.kcal))" }.joined()
            + " \(p.watermarks.count) \(nz.count)" + nz.map { " \($0.offset) \(d($0.element))" }.joined()
            + " \(d(p.carryRemaining)) \(d(p.workoutConsumed)) \(d(p.totalKcal))"
    }
    func placedSteps(_ w: [StepWindow]) -> Int { w.filter { $0.delta > 0 }.reduce(0) { $0 + $1.delta } }
    struct LedgerState { var marks: [Double] = []; var carry = 0.0; var saved = 0.0; var credited = 0.0; var lastEnd: Date? = nil }
    func flush(_ tag: String, _ k: Int, _ e: Calories.DailyEstimate, _ now: Date, _ workout: Double, _ s: inout LedgerState) {
        let p = ActiveEnergyLedger.plan(buckets: e.buckets, watermarks: s.marks, dayStart: day, now: now, carry: s.carry,
                                        uncreditedWorkoutKcal: workout - s.credited, savedKcal: s.saved)
        c.goldens.append(planLine(tag, k, p))
        hit("ledger-write", !p.writes.isEmpty)
        hit("ledger-fall-netted", p.carryRemaining > s.carry)
        hit("ledger-workout-consumed", p.workoutConsumed > 0)
        hit("ledger-clamped-to-now", p.writes.contains { $0.end == now })
        hit("ledger-late-bucket", s.lastEnd.map { last in p.writes.contains { $0.start < last } } ?? false)
        s.marks = p.watermarks
        s.carry = p.carryRemaining
        s.saved += p.totalKcal
        s.credited += p.workoutConsumed
        if let end = p.writes.map({ $0.end }).max() { s.lastEnd = max(s.lastEnd ?? end, end) }
    }

    // In order: flush k sees heart rate starting before it and step windows ending by it; the last two
    // flushes see the whole day (with the day's own step count), so the last one has nothing new.
    let cuts = [8, 12, 16, 20, 26, 26].map { at($0 * 3600) }
    let workouts: [Double] = index % 3 == 0 ? [0, 0, 30, 30, 30, 30] : [0, 0, 0, 0, 0, 0]
    var inOrder = LedgerState()
    for (k, cut) in cuts.enumerated() {
        let h = hr.filter { $0.start < cut }
        let w = windows.filter { $0.end <= cut }
        let whole = k >= cuts.count - 2
        let before = inOrder.saved
        flush("lplan", k, estimate900(h, w, whole ? steps : placedSteps(w)), cut, workouts[k], &inOrder)
        hit("ledger-nothing-new", k == cuts.count - 1 && inOrder.saved == before)
    }
    // Out of order: each sample and window arrives by the first flush at or after its time, sometimes
    // one or two flushes late, so an earlier bucket can gain energy after later ones were written.
    let arrivals = [10, 14, 18, 22, 27].map { at($0 * 3600) }
    func batch(_ t: Date) -> Int {
        let first = arrivals.firstIndex { $0 >= t } ?? (arrivals.count - 1)
        return min(arrivals.count - 1, first + (rng.chance(30) ? rng.int(1, 2) : 0))
    }
    let hrBatch = hr.map { batch($0.start) }
    let windowBatch = windows.map { batch($0.end) }
    var outOfOrder = LedgerState()
    for (k, now) in arrivals.enumerated() {
        let h = zip(hr, hrBatch).filter { $0.1 <= k }.map { $0.0 }
        let w = zip(windows, windowBatch).filter { $0.1 <= k }.map { $0.0 }
        flush("aplan", k, estimate900(h, w, k == arrivals.count - 1 ? steps : placedSteps(w)), now, 0, &outOfOrder)
    }
    // Upgrade-day seeding from a legacy total, then the plan that follows it.
    let whole = estimate900(hr, windows, steps)
    let legacies = [whole.activeKcal * 0.5, whole.activeKcal + 37.5]
    for (k, legacy) in legacies.enumerated() {
        let s = ActiveEnergyLedger.seed(buckets: whole.buckets, legacyWrittenKcal: legacy, dayStart: day)
        let nz = s.watermarks.enumerated().filter { $0.element != 0 }
        c.goldens.append("seed \(k) \(s.watermarks.count) \(nz.count)" + nz.map { " \($0.offset) \(d($0.element))" }.joined() + " \(d(s.carry))")
        hit("seed-carry", s.carry > 0)
        let p = ActiveEnergyLedger.plan(buckets: whole.buckets, watermarks: s.watermarks, dayStart: day, now: at(26 * 3600), carry: s.carry)
        c.goldens.append(planLine("splan", k, p))
        hit("seed-then-write", !p.writes.isEmpty)
    }
    // Write windows. Every energy is a multiple of 1.25 kcal, so every widened start stays on the grid.
    let wake = sleepWindow?.end
    let queries: [(Date?, Date?, Date, Double)] = [
        (nil, wake, at(9 * 3600), 0),
        (nil, wake, at(9 * 3600), 1.25 * Double(rng.int(1, 4000))),
        (at(10 * 3600), wake, at(10 * 3600 + 44), 190),
        (Date(timeIntervalSince1970: 0), nil, at(13 * 3600), 1.25 * Double(rng.int(0, 400))),
        (at(-3 * 86_400), wake, at(12 * 3600), 5000),
        (at(30 * 3600), wake, at(11 * 3600), .infinity),
        (at(12 * 3600), nil, at(12 * 3600), 10),
        (day, at(-7200), at(3600), .nan),
        (at(12 * 3600), nil, at(12 * 3600), 0),
        (nil, nil, at(-1), 0),
    ]
    var win = "win"
    for q in queries {
        let r = ActiveEnergyWindow.resolve(anchor: q.0, notBefore: q.1, now: q.2, dayStart: day, kcal: q.3)
        win += r.map { " \(ms($0.start)) \(ms($0.end))" } ?? " - -"
        let unwidened = ActiveEnergyWindow.resolve(anchor: q.0, notBefore: q.1, now: q.2, dayStart: day)
        hit("win-none", r == nil)
        hit("win-day-floor", r?.start == day)
        hit("win-widened", r != nil && unwidened != nil && r!.start < unwidened!.start)
    }
    c.goldens.append(win)
    // Unreadable stored states (hostile days only): what upstream plans from each.
    if shape == "hostile" {
        let bad: [([Double], Double, Double, Double)] = [([.nan], 0, 0, 0), ([-40], 0, 0, 0), ([], .nan, 0, 0), ([], 0, .nan, 0), ([], 0, 0, .nan)]
        for (k, b) in bad.enumerated() {
            let p = ActiveEnergyLedger.plan(buckets: whole.buckets, watermarks: b.0, dayStart: day, now: at(26 * 3600),
                                            carry: b.1, uncreditedWorkoutKcal: b.2, savedKcal: b.3)
            c.goldens.append(planLine("bad", k, p))
            hit("ledger-unreadable-state-written", !p.writes.isEmpty)
        }
    }
    func digits(_ xs: [Int]) -> String { xs.isEmpty ? "-" : xs.map { String($0) }.joined() }
    func optMs(_ t: Date?) -> String { t.map { String(ms($0)) } ?? "-" }
    c.inputs += [
        "lp" + cuts.map { " \(ms($0))" }.joined(),
        "lw" + workouts.map { " " + d($0) }.joined(),
        "la" + arrivals.map { " \(ms($0))" }.joined(),
        "lah \(digits(hrBatch))",
        "law \(digits(windowBatch))",
        "ls" + legacies.map { " " + d($0) }.joined(),
        "rq" + queries.map { " \(optMs($0.0)) \(optMs($0.1)) \(ms($0.2)) \(d($0.3))" }.joined(),
    ]

    let valid = hr.filter { LiveHR.validBPM.contains($0.bpm) }
    let sleepMean = RestingHR.sleepMean(hr: valid, sleep: segments, minSleepSamples: RestingHR.minSleepSamples)
    let sustained = RestingHR.hasSustainedWindow(hr: valid, window: RestingHR.sustainedWindow)
    let threshold = ExerciseMinutes.threshold(maxHR: maxHR)
    hit("day-rhr-sleep-mean", sleepMean != nil)
    hit("day-rhr-sustained", rhr != nil && sleepMean == nil && sustained)
    hit("day-rhr-isolated", rhr != nil && sleepMean == nil && !sustained)
    hit("day-rhr-multi-day", daily.count >= 3)
    hit("day-clock-change", shape == "spring-forward" || shape == "fall-back")
    hit("day-no-midnight", zone == "America/Santiago" && date == (2026, 9, 6))
    hit("day-baseline-derived", baseline != nil)
    hit("day-baseline-none", baseline == nil)
    hit("day-sleep-excluded", sleepWindow.map { w in hr.contains { $0.bpm >= threshold && w.contains($0.start) } } ?? false)
    hit("day-pieces-none", pieces.isEmpty)
    hit("day-span-samples", hr.contains { $0.end != $0.start })
    hit("day-residual-steps", steps > placed)
    hit("day-straddling-window", shape == "straddle")
    hit("day-duplicated-samples", shape == "duplicated")
    return c
}

// MARK: - Prior-day series → robust and vitals baselines, classification, fever, status

let baseShapes = ["steady", "fever", "desat", "hrvdrop", "short", "long", "flat", "artifact", "unreadable"]
let baseCasesPerShape = 5
let noiseFloors: [Double] = [0, 0.3, 5]
let unreadables: [Double] = [.nan, .infinity, -.infinity]

func baseCase(_ index: Int) -> Case {
    let shape = baseShapes[index / baseCasesPerShape]
    let k = index % baseCasesPerShape
    var rng = SplitMix64(state: 0x4241_5345 &+ UInt64(index))
    let n: Int
    switch shape {
    case "short": n = [0, 3, 6, 7, 8][k]
    case "long": n = rng.int(61, 95)
    default: n = rng.int(7, 45)
    }
    let bases = [rng.real(48, 70), rng.real(94, 99), rng.real(30, 90)]
    let spreads = [3.0, 1.0, 8.0]
    var series: [[Double]] = (0..<3).map { v in
        (0..<n).map { _ in shape == "flat" ? bases[v] : bases[v] + rng.real(-spreads[v], spreads[v]) }
    }
    if shape == "artifact" && n > 0 {
        let outliers = [40.0, -15.0, -40.0]
        for v in 0..<3 { series[v][rng.int(0, n - 1)] = bases[v] + outliers[v] }
    }
    if shape == "unreadable" {
        for v in 0..<3 { for _ in 0..<rng.int(1, 3) { series[v].insert(rng.pick(unreadables), at: rng.int(0, series[v].count)) } }
    }
    var today = (0..<3).map { v in shape == "flat" ? bases[v] : bases[v] + rng.real(-spreads[v], spreads[v]) }
    switch shape {
    case "fever": today[0] = bases[0] + rng.real(6, 20)
    case "desat": today[1] = bases[1] - rng.real(2, 8)
    case "hrvdrop": today[2] = bases[2] - rng.real(8, 30)
    case "flat": today[0] = bases[0] + [0.25, 1, 4, 10, -2][k]
    case "unreadable": today[k % 3] = unreadables[k % 3]
    default: break
    }
    var offsets = [rng.real(-1.5, 1.5), [0.5, 1.0, -0.5, -1.0, 0.0][k], rng.real(1, 2)]
    if shape == "unreadable" { offsets[(k + 1) % 3] = unreadables[(k + 2) % 3] }
    let habitual = k == 0 ? (1410 + rng.int(0, 60)) % 1440 : rng.int(0, 1439)
    let bedtimes = (0..<n).map { _ in habitual + rng.int(-90, 90) + (rng.chance(10) ? 1440 * rng.int(-2, 2) : 0) }
    let tonightBed = habitual + rng.int(-240, 240)

    var c = Case(id: String(format: "base-%03d", index), kind: "base", shape: shape)
    let tags = ["r", "s", "h"]
    let vitals: [VitalsBaseline.Vital] = [.restingHR, .overnightSpO2, .overnightHRV]
    c.inputs = (0..<3).map { v in "p\(tags[v])" + series[v].map { " " + d($0) }.joined() } + [
        "td" + today.map { " " + d($0) }.joined(),
        "to" + offsets.map { " " + d($0) }.joined(),
        "bm" + ints(bedtimes),
        "bt \(tonightBed)",
    ]
    for v in 0..<3 {
        let s = RobustBaseline.stats(series[v])
        c.goldens.append(s.map { "rst \(tags[v]) \(d($0.median)) \(d($0.mad)) \($0.n)" } ?? "rst \(tags[v]) none")
        hit("base-rstats-none", s == nil)
        hit("base-rstats-capped", s?.n == RobustBaseline.maxBaselineDays)
        if let s {
            let zs = noiseFloors.map { RobustBaseline.z(today: today[v], stats: s, noiseFloor: $0) }
            c.goldens.append("z \(tags[v])" + zs.map { " " + d($0) }.joined())
            hit("base-z-clamped", zs.contains { abs($0) == RobustBaseline.zClamp })
            hit("base-z-floor-scale", noiseFloors.contains { RobustBaseline.madConsistency * s.mad < $0 })
        } else {
            c.goldens.append("z \(tags[v]) none")
        }
    }
    for v in 0..<3 {
        let s = VitalsBaseline.stats(series[v])
        c.goldens.append(s.map { "vst \(tags[v]) \(d($0.mean)) \(d($0.sd)) \($0.n)" } ?? "vst \(tags[v]) none")
        hit("base-vstats-none", s == nil)
        hit("base-vstats-capped", s?.n == VitalsBaseline.Config().maxBaselineDays)
    }
    for v in 0..<3 {
        let cl = VitalsBaseline.classify(today: today[v], prior: series[v], vital: vitals[v])
        c.goldens.append("cls \(tags[v]) \(cl.severity.rawValue) \(optD(cl.baseline?.mean)) \(optD(cl.baseline?.sd)) \(optI(cl.baseline?.n)) \(d(cl.delta)) \(cl.direction.rawValue)")
        hit("base-cls-minor", cl.severity == .minor)
        hit("base-cls-significant", cl.severity == .significant)
        hit("base-cls-normal-past-floor", cl.baseline != nil && cl.severity == .normal && abs(cl.delta) >= VitalsBaseline.Config().minDelta(vitals[v]))
    }
    c.goldens.append("temp" + offsets.map { " " + VitalsBaseline.tempSeverity(offsetC: $0).rawValue }.joined())
    c.goldens.append("fever" + offsets.map {
        " " + (VitalsBaseline.suspectedFever(restingHRToday: today[0], restingHRPrior: series[0], skinTempOffsetC: $0) ? "1" : "0")
    }.joined())
    let report = (0..<3).map { VitalsBaseline.VitalInput(vital: vitals[$0], today: today[$0], prior: series[$0]) }
    for (j, offset) in (offsets.map { Optional($0) } + [nil]).enumerated() {
        let r = VitalsBaseline.report(report, skinTempOffsetC: offset)
        c.goldens.append("rep \(j) \(r.status.rawValue) \(r.feverSuspected ? 1 : 0) \(r.signals.count)" + r.signals.map {
            " \($0.vital?.rawValue ?? "temp") \($0.severity.rawValue) \(d($0.delta)) \($0.direction.rawValue) \(optD($0.baselineMean))"
        }.joined())
        hit("base-status-\(r.status.rawValue)")
        hit("base-fever", r.feverSuspected)
        hit("base-temp-minor", r.signals.contains { $0.isTemperature && $0.severity == .minor })
        hit("base-temp-significant", r.signals.contains { $0.isTemperature && $0.severity == .significant })
    }
    let median = RobustBaseline.circularMedianMinutes(bedtimes)
    c.goldens.append("circ \(optI(median)) \(optI(median.map { RobustBaseline.circularDeltaMinutes(tonightBed, $0) }))")
    let normalised = bedtimes.map { (($0 % 1440) + 1440) % 1440 }
    hit("base-circ-wrap", normalised.contains { $0 < 120 } && normalised.contains { $0 > 1320 })
    hit("base-unreadable", shape == "unreadable")
    return c
}

// MARK: - Temperature nights → coverage, verdict, nightly means, baseline, offset, band, flags

let tempShapes = ["full", "partial", "clustered", "sparse", "thin", "endreading", "history", "unreadable"]
let tempCasesPerShape = 5

func tempCase(_ index: Int) -> Case {
    let shape = tempShapes[index / tempCasesPerShape]
    let k = index % tempCasesPerShape
    var rng = SplitMix64(state: 0x5445_4d50 &+ UInt64(index))
    let day = Date(timeIntervalSince1970: 1_767_225_600 + Double(index) * 86_400) // 2026-01-01 UTC onwards
    let start = day.addingTimeInterval(Double(rng.int(80, 96)) * 900)
    let wholeHours = shape == "endreading" || rng.chance(50)
    let duration = wholeHours ? Double(rng.int(6, 11)) * 3600 : Double(rng.int(6 * 3600 * 4, 11 * 3600 * 4)) / 4
    let window = DateInterval(start: start, duration: duration)
    let base = rng.real(33, 36)
    var readings: [(Double, Double)] = [] // (seconds after the window's start, celsius)
    func add(_ t: Double) { readings.append((t, base + rng.real(-0.75, 0.75))) }
    switch shape {
    case "full", "history", "unreadable":
        var t = Double(rng.int(0, 120))
        while t < duration { add(t); t += Double(rng.int(240, 600)) }
    case "partial":
        let span = Double(rng.int(1, 3)) * 3600
        var t = Double(rng.int(0, 60))
        while t < span { add(t); t += Double(rng.int(60, 200)) }
    case "clustered":
        for _ in 0..<2 {
            let at = Double(rng.int(0, Int(duration) - 3600))
            for j in 0..<rng.int(8, 25) { add(at + Double(j) * 73) }
        }
        readings += readings.prefix(3) // duplicated readings
    case "sparse":
        var t = Double(rng.int(0, 600))
        while t < duration { add(t); t += Double(rng.int(1200, 2400)) }
    case "thin":
        for _ in 0..<rng.int(1, 12) { add(Double(rng.int(0, Int(duration) - 1))) }
    default: // endreading: the last hour, then a reading exactly on the window's end
        var t = duration - 3600 + Double(rng.int(1, 300))
        while t < duration { add(t); t += 300 }
        add(duration)
        if k % 2 == 0 { add(0) }
        if k == 4 { add(duration - 3600) }
    }
    if ["full", "sparse", "history"].contains(shape) && rng.chance(60) {
        add(-Double(rng.int(1, 3600)))
        add(duration + Double(rng.int(1, 3600)))
    }
    if shape == "unreadable" && !readings.isEmpty {
        if k == 4 {
            readings = readings.map { ($0.0, Double.nan) }
        } else {
            for _ in 0..<rng.int(1, 4) { let j = rng.int(0, readings.count - 1); readings[j].1 = rng.pick(unreadables) }
        }
    }
    if ["clustered", "sparse", "history"].contains(shape) { readings = readings.shuffledDeterministically(&rng) }

    let m = shape == "history" ? rng.int(3, 40) : shape == "thin" ? rng.int(0, 2) : rng.int(0, 12)
    var nights = (0..<m).map { j in
        SkinTempBaseline.NightlyTemp(night: day.addingTimeInterval(-Double(j + 1) * 86_400), celsius: base + rng.real(-0.5, 0.5))
    }
    if shape == "history" && m > 2 {
        nights.append(SkinTempBaseline.NightlyTemp(night: nights[1].night, celsius: base + rng.real(-0.5, 0.5)))
        nights = nights.shuffledDeterministically(&rng)
    }
    if shape == "unreadable" && m > 0 {
        let j = rng.int(0, m - 1)
        nights[j] = SkinTempBaseline.NightlyTemp(night: nights[j].night, celsius: rng.pick(unreadables))
    }
    let windowNights = [30, 3, rng.int(1, 10)]
    var tonight = base + rng.real(-2, 2)
    var prev: Double? = rng.chance(25) ? nil : base + rng.real(-1.5, 1.5)
    if shape == "unreadable" {
        if k % 3 == 0 { tonight = .nan } else if k % 3 == 1 { tonight = .infinity }
        if k == 2 { prev = .nan } else if k == 4 { prev = -.infinity }
    }

    var c = Case(id: String(format: "temp-%03d", index), kind: "temp", shape: shape)
    c.inputs = [
        "w \(ms(window.start)) \(ms(window.end))",
        "s" + readings.map { " \(ms(start.addingTimeInterval($0.0))) \(d($0.1))" }.joined(),
        "n" + nights.map { " \(ms($0.night)) \(d($0.celsius))" }.joined(),
        "wn" + ints(windowNights),
        "tn \(d(tonight))",
        "pv \(optD(prev))",
    ]
    let samples = readings.map { TemperatureSample(time: start.addingTimeInterval($0.0), celsius: $0.1) }
    func verdict(_ v: SkinTempBaseline.NightlyVerdict) -> String {
        switch v {
        case .published(let x): return "pub \(d(x))"
        case .notMeasured: return "nm"
        case .rejectedCoverage(let x): return "rej \(d(x))"
        }
    }
    func bits(_ f: SkinTempBaseline.AnomalyFlags) -> String {
        [f.abnormalRise, f.abnormalDrop, f.fluctuationRise, f.fluctuationDrop].map { $0 ? "1" : "0" }.joined(separator: " ")
    }
    let shipped = SkinTempBaseline.nightlyVerdict(samples: samples, in: window)
    c.goldens += [
        "cov \(d(SkinTempBaseline.coverage(samples: samples, in: window)))",
        "ver 0 \(verdict(shipped))",
        "ver 1 \(verdict(SkinTempBaseline.nightlyVerdict(samples: samples, in: window, minSamples: 1, minCoverage: 0)))",
        "nmw \(optD(SkinTempBaseline.nightlyMean(samples: samples, in: window)))",
        "nml \(optD(SkinTempBaseline.nightlyMean(readings.map { $0.1 })))",
        "base" + windowNights.map { " " + optD(SkinTempBaseline.baseline(priorNights: nights, windowNights: $0)) }.joined(),
    ]
    let baseline = SkinTempBaseline.baseline(priorNights: nights)
    let offset = baseline.map { SkinTempBaseline.offset(tonight: tonight, baseline: $0) }
    c.goldens.append("off \(optD(offset))")
    c.goldens.append("band \(offset.map { SkinTempBaseline.deviationBand(offset: $0).rawValue } ?? "-")")
    let flags = SkinTempBaseline.anomalyFlags(tonight: tonight, baseline: baseline, previousNight: prev)
    c.goldens.append("flags \(bits(flags))")
    let r = SkinTempBaseline.report(tonight: tonight, priorNights: nights, previousNight: prev)
    c.goldens.append("nrep \(d(r.nightlyC)) \(optD(r.baselineC)) \(optD(r.offsetC)) \(r.band?.rawValue ?? "-") \(bits(r.flags))")

    switch shipped {
    case .published: hit("temp-published")
    case .notMeasured: hit("temp-not-measured")
    case .rejectedCoverage: hit("temp-rejected")
    }
    hit("temp-end-reading", readings.contains { $0.0 == duration })
    hit("temp-out-of-window", readings.contains { $0.0 < 0 || $0.0 > duration })
    hit("temp-partial-hour-window", !wholeHours)
    hit("temp-baseline", baseline != nil)
    hit("temp-baseline-none", baseline == nil)
    hit("temp-band-\(r.band?.rawValue ?? "none")")
    hit("temp-flag-abnormal", flags.abnormalRise || flags.abnormalDrop)
    hit("temp-flag-fluct-rise", flags.fluctuationRise)
    hit("temp-flag-fluct-drop", flags.fluctuationDrop)
    hit("temp-flag-gated", baseline != nil && prev.map { abs(tonight - $0) > SkinTempBaseline.fluctuationC } == true
        && !flags.fluctuationRise && !flags.fluctuationDrop)
    hit("temp-duplicated-nights", shape == "history" && m > 2)
    hit("temp-unreadable", shape == "unreadable")
    return c
}

// MARK: - Sub-scores → readiness; goals → activity score; daily scores → trend

/// Every ordering of `xs`, used only to pick inputs whose rounded score depends on the order the
/// present factors are summed (upstream sums them in a dictionary's order, seeded per process).
func orderings<T>(_ xs: [T]) -> [[T]] {
    if xs.count <= 1 { return [xs] }
    var out: [[T]] = []
    for i in xs.indices {
        var rest = xs
        let x = rest.remove(at: i)
        for o in orderings(rest) { out.append([x] + o) }
    }
    return out
}

/// The set of rounded scores the weighted mean of `values` can take over every summation order.
func scoresOverOrders<F: Hashable>(_ values: [F: Double], _ weights: [F: Double]) -> Set<Int> {
    Set(orderings(Array(values.keys)).map { order -> Int in
        var num = 0.0, den = 0.0
        for f in order { num += weights[f]! * values[f]!; den += weights[f]! }
        return Int((num / den * 100).rounded())
    })
}

let wbShapes = ["full", "partial", "edge", "tie", "unanchored"]
let wbCasesPerShape = 3
let wbQueriesPerCase = 16
let wbFactors: [WellnessBalance.Result.Factor] = [.sleep, .recovery, .vitals, .activity]
let vitalsStatuses: [VitalsBaseline.Status] = [.normal, .watch, .anomaly]
let edgeSubScores = [Int(Int32.min), -1, 0, 1, 14, 15, 59, 60, 84, 85, 90, 91, 100, 101, Int(Int32.max)]

/// The factor values `WellnessBalance.score` would build for an input (to search for order-sensitive inputs).
func wbFactorValues(_ i: WellnessBalance.Input) -> [WellnessBalance.Result.Factor: Double] {
    WellnessBalance.score(i)?.factors ?? [:]
}

func wbCase(_ index: Int) -> Case {
    let shape = wbShapes[index / wbCasesPerShape]
    let k = index % wbCasesPerShape
    var rng = SplitMix64(state: 0x5742_414C &+ UInt64(index))
    var c = Case(id: String(format: "wb-%03d", index), kind: "wb", shape: shape)
    var queries: [WellnessBalance.Input] = []
    func random(_ chance: Int) -> WellnessBalance.Input {
        WellnessBalance.Input(
            sleepScore: rng.chance(chance) ? rng.int(0, 100) : nil,
            overnightStress: rng.chance(chance) ? rng.int(15, 90) : nil,
            vitalsStatus: rng.chance(chance) ? rng.pick(vitalsStatuses) : nil,
            activityScore: rng.chance(chance) ? rng.int(0, 100) : nil)
    }
    while queries.count < wbQueriesPerCase {
        switch shape {
        case "full": queries.append(random(100))
        case "partial": queries.append(k == 0 && queries.isEmpty ? WellnessBalance.Input() : random(50))
        case "edge":
            queries.append(WellnessBalance.Input(
                sleepScore: rng.chance(75) ? rng.pick(edgeSubScores) : nil,
                overnightStress: rng.chance(75) ? rng.pick(edgeSubScores) : nil,
                vitalsStatus: rng.chance(75) ? rng.pick(vitalsStatuses) : nil,
                activityScore: rng.chance(75) ? rng.pick(edgeSubScores) : nil))
        case "tie":
            let q = random(70)
            let f = wbFactorValues(q)
            if f.count > 1 && scoresOverOrders(f, WellnessBalance.factorWeights).count > 1 { queries.append(q) }
        default: // unanchored: no sleep sub-score
            var q = random(70)
            q.sleepScore = nil
            queries.append(q)
        }
    }
    func opt(_ x: Int?) -> String { x.map { String($0) } ?? "-" }
    for (j, q) in queries.enumerated() {
        c.inputs.append("q \(opt(q.sleepScore)) \(opt(q.overnightStress)) \(q.vitalsStatus?.rawValue ?? "-") \(opt(q.activityScore))")
        let r = WellnessBalance.score(q)
        let anchored = WellnessBalance.anchoredScore(q)
        c.goldens.append("wbs \(j) \(optI(r?.score)) \(r?.tier.rawValue ?? "-") \(optI(anchored?.score))"
            + wbFactors.map { " " + optD(r?.factors[$0]) }.joined())
        hit("wb-none", r == nil)
        hit("wb-anchor-nil", r != nil && anchored == nil)
        hit("wb-renormalised", (1...3).contains(r?.factors.count ?? 0))
        if let r { hit("wb-tier-\(r.tier.rawValue)") }
        hit("wb-clamped", [q.sleepScore, q.activityScore].contains { $0.map { $0 < 0 || $0 > 100 } == true }
            || q.overnightStress.map { $0 < 15 || $0 > 90 } == true)
        hit("wb-order-sensitive", r.map { $0.factors.count > 1 && scoresOverOrders($0.factors, WellnessBalance.factorWeights).count > 1 } == true)
    }
    return c
}

let actShapes = ["typical", "disabled", "edge", "tie"]
let actCasesPerShape = 3
let actFactors: [ActivityScore.Result.Factor] = [.steps, .activeMinutes, .activeKcal]
let stepGoalChoices = [10_000, 8_000, 7_500, 12_000]

func actCase(_ index: Int) -> Case {
    let shape = actShapes[index / actCasesPerShape]
    var rng = SplitMix64(state: 0x4143_5453 &+ UInt64(index))
    var c = Case(id: String(format: "act-%03d", index), kind: "act", shape: shape)
    var queries: [ActivityScore.Input] = []
    func typical() -> ActivityScore.Input {
        ActivityScore.Input(steps: rng.int(0, 15_000), stepGoal: rng.pick(stepGoalChoices),
                            activeMinutes: rng.real(0, 60), activeMinutesGoal: rng.chance(80) ? 30 : rng.real(5, 90),
                            activeKcal: rng.real(0, 800), activeKcalGoal: rng.chance(80) ? 500 : rng.real(100, 900))
    }
    let count = shape == "tie" ? 8 : 16
    while queries.count < count {
        switch shape {
        case "typical": queries.append(typical())
        case "disabled":
            var q = typical()
            let off: [Double] = [0, -0.0, -30, .nan]
            if rng.chance(50) { q.stepGoal = rng.pick([0, -1, -10_000]) }
            if rng.chance(50) { q.activeMinutesGoal = rng.pick(off) }
            if rng.chance(50) { q.activeKcalGoal = rng.pick(off) }
            queries.append(q)
        case "edge":
            // Hostile values upstream answers without trapping: never a NaN current value, never an
            // infinite current over an infinite goal.
            let currents: [Double] = [.infinity, -.infinity, -0.0, -5, 1e308, .leastNonzeroMagnitude, 0]
            let goals: [Double] = [.infinity, .leastNonzeroMagnitude, 1e308, 30, 500]
            var q = typical()
            if rng.chance(50) { q.steps = rng.pick([Int(Int32.min), -1, Int(Int32.max)]) }
            if rng.chance(30) { q.stepGoal = rng.pick([1, Int(Int32.max)]) }
            if rng.chance(60) { q.activeMinutes = rng.pick(currents) }
            if rng.chance(40) { q.activeMinutesGoal = rng.pick(goals) }
            if rng.chance(60) { q.activeKcal = rng.pick(currents) }
            if rng.chance(40) { q.activeKcalGoal = rng.pick(goals) }
            if q.activeMinutes.isInfinite && q.activeMinutesGoal.isInfinite { q.activeMinutesGoal = 30 }
            if q.activeKcal.isInfinite && q.activeKcalGoal.isInfinite { q.activeKcalGoal = 500 }
            queries.append(q)
        default: // tie: steps on a whole grid, minutes on a one-second grid, energy on a 0.1 kcal grid
            let q = ActivityScore.Input(steps: rng.int(0, 12_000), stepGoal: rng.pick(stepGoalChoices),
                                        activeMinutes: Double(rng.int(0, 2_400)) / 60, activeMinutesGoal: 30,
                                        activeKcal: Double(rng.int(0, 7_000)) / 10, activeKcalGoal: 500)
            let f = ActivityScore.score(q).factors
            if f.count > 1 && scoresOverOrders(f, ActivityScore.factorWeights).count > 1 { queries.append(q) }
        }
    }
    for (j, q) in queries.enumerated() {
        c.inputs.append("q \(q.steps) \(q.stepGoal) \(d(q.activeMinutes)) \(d(q.activeMinutesGoal)) \(d(q.activeKcal)) \(d(q.activeKcalGoal))")
        let r = ActivityScore.score(q)
        c.goldens.append("acs \(j) \(r.score) \(r.tier.rawValue)" + actFactors.map { " " + optD(r.factors[$0]) }.joined())
        hit("act-tier-\(r.tier.rawValue)")
        hit("act-dropped-goal", r.factors.count < 3)
        hit("act-no-factors", r.factors.isEmpty)
        let ratios = [Double(q.steps) / Double(q.stepGoal), q.activeMinutes / q.activeMinutesGoal, q.activeKcal / q.activeKcalGoal]
        hit("act-capped", ratios.contains { $0 > 1 })
        hit("act-nonfinite", [q.activeMinutes, q.activeMinutesGoal, q.activeKcal, q.activeKcalGoal].contains { !$0.isFinite })
        hit("act-order-sensitive", r.factors.count > 1 && scoresOverOrders(r.factors, ActivityScore.factorWeights).count > 1)
    }
    return c
}

let trendShapes = ["typical", "deadband", "extreme"]
let trendCasesPerShape = 3
let trendQueriesPerCase = 12

func trendCase(_ index: Int) -> Case {
    let shape = trendShapes[index / trendCasesPerShape]
    var rng = SplitMix64(state: 0x5452_4E44 &+ UInt64(index))
    var c = Case(id: String(format: "trend-%03d", index), kind: "trend", shape: shape)
    let extremes = [Int(Int32.min), Int(Int32.min) + 1, -1, 0, 1, Int(Int32.max) - 1, Int(Int32.max)]
    for j in 0..<trendQueriesPerCase {
        var prior = (0..<rng.int(0, 14)).map { _ in rng.int(40, 95) }
        var today = rng.int(30, 100)
        var deadband = 3
        switch shape {
        case "deadband":
            deadband = rng.pick([-5, -3, -1, 0, 1, 3, 10, Int(Int32.min), Int(Int32.max)])
            if prior.isEmpty { prior = [rng.int(40, 95)] }
            // Today a whole number of points from the prior mean's floor, so exact deadband hits occur.
            today = prior.reduce(0, +) / prior.count + rng.int(-6, 6)
        case "extreme":
            prior = (0..<rng.int(1, 6)).map { _ in rng.pick(extremes) }
            today = rng.pick(extremes)
            deadband = rng.pick([3, 0, Int(Int32.min), Int(Int32.max)])
        default: break
        }
        c.inputs.append("q \(today) \(deadband)" + ints(prior))
        let t = WellnessBalance.trend(today: today, prior: prior, deadband: deadband)
        c.goldens.append("trd \(j) \(t.rawValue)")
        hit("trend-\(t.rawValue)")
        hit("trend-negative-deadband", deadband < 0)
        hit("trend-empty", prior.isEmpty)
        let sum = prior.reduce(0, +)
        hit("trend-64-bit", sum > Int(Int32.max) || sum < Int(Int32.min))
    }
    return c
}

// MARK: - Goal-ring history → weekend goals, sleep credit, days, summaries

let goalShapes = ["spring", "fall", "streaks", "skew"]
let goalZones = ["America/New_York", "Europe/London", "America/Santiago"]
let goalCasesPerShape = 3
let goalDays = 12
/// The first local day of each case's window: the windows of "spring" and "fall" hold the zone's
/// 2026 clock changes (Santiago's 6 September has no midnight; its 5 April repeats an hour),
/// "streaks" holds Santiago's missing midnight again, and "skew" holds a change in each zone.
let goalWindowStart: [String: [(Int, Int, Int)]] = [
    "spring": [(2026, 3, 2), (2026, 3, 23), (2026, 8, 31)],
    "fall": [(2026, 10, 26), (2026, 10, 19), (2026, 3, 30)],
    "streaks": [(2026, 8, 10), (2026, 8, 10), (2026, 9, 1)],
    "skew": [(2026, 3, 4), (2026, 10, 22), (2026, 4, 1)],
]

func gregorian(_ zone: String) -> Calendar {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: zone)!
    return c
}

func mask(_ rings: Set<GoalHistory.Ring>) -> String {
    GoalHistory.Ring.allCases.map { rings.contains($0) ? "1" : "0" }.joined()
}

func goalCase(_ index: Int) -> Case {
    let shape = goalShapes[index / goalCasesPerShape]
    let zi = index % goalCasesPerShape
    let zone = goalZones[zi]
    let sumZone = shape == "skew" ? goalZones[(zi + 1) % goalZones.count] : zone
    var rng = SplitMix64(state: 0x474F_414C &+ UInt64(index))
    var c = Case(id: String(format: "goal-%03d", index), kind: "goal", shape: shape)
    let cal = gregorian(zone)
    let scal = gregorian(sumZone)
    let (y, m, d0) = goalWindowStart[shape]![zi]
    let firstNoon = cal.date(from: DateComponents(year: y, month: m, day: d0, hour: 12))!
    let noons = (0..<goalDays).map { cal.date(byAdding: .day, value: $0, to: firstNoon)! }
    let starts = noons.map { cal.startOfDay(for: $0) }
    func minutes(_ t: Date, _ n: Int) -> Date { t.addingTimeInterval(Double(n) * 60) }

    let goals = rng.chance(50) ? GoalHistory.Goals() : GoalHistory.Goals(
        workdaySteps: rng.pick([6_000, 8_000, 9_000]), weekendSteps: rng.pick([7_000, 10_000, 12_000]),
        activeKcal: rng.pick([250.0, 300.0, 450.5]), activityMinutes: rng.pick([20.0, 30.0, 45.25]),
        workdaySleepMin: rng.pick([400, 420, 450]), weekendSleepMin: rng.pick([420, 480, 510]))

    // Nights credited to the morning of day k (in bed the evening before, across the clock change
    // where the window holds one), legacy nights without a clock, zero-minute nights, a second
    // clocked night on the same wake day (the guard widens), and naps in the afternoon, inside a
    // night and clipping a wake.
    var nights: [GoalHistory.NightSleep] = []
    var naps: [GoalHistory.NapSleep] = []
    var widened = false
    for k in 1..<goalDays {
        let r = rng.int(0, 99)
        let key = starts[k - 1]
        var wake = minutes(starts[k], rng.int(300, 540))
        if r < 10 {
            nights.append(.init(nightKey: key, inBedStart: nil, inBedEnd: nil, asleepMinutes: rng.int(300, 500)))
        } else if r < 15 {
            nights.append(.init(nightKey: key, inBedStart: minutes(starts[k], -60), inBedEnd: wake, asleepMinutes: 0))
        } else {
            let bed = minutes(starts[k], -rng.int(0, 150))
            nights.append(.init(nightKey: key, inBedStart: bed, inBedEnd: wake, asleepMinutes: rng.int(300, 520)))
            if rng.chance(12) {
                let s = minutes(starts[k], rng.int(600, 700))
                nights.append(.init(nightKey: key, inBedStart: s, inBedEnd: minutes(s, 50), asleepMinutes: rng.int(20, 45)))
                widened = true
            }
        }
        if r < 15 { wake = minutes(starts[k], 480) }
        if rng.chance(40) {
            let s = minutes(starts[k], rng.int(780, 960))
            naps.append(.init(start: s, end: minutes(s, rng.int(20, 90)), asleepMinutes: rng.int(15, 80)))
        }
        if rng.chance(20) {
            let s = minutes(starts[k], -rng.int(10, 40))
            naps.append(.init(start: s, end: minutes(s, 60), asleepMinutes: rng.int(20, 55)))
        }
        if rng.chance(10) {
            let s = minutes(wake, -10)
            naps.append(.init(start: s, end: minutes(s, 40), asleepMinutes: rng.int(10, 30)))
        }
    }
    let credit = GoalHistory.sleepCreditByDay(nights: nights, naps: naps, calendar: cal)

    // Day rollups: some days absent, some with no data, some full, some partial; dated at the day
    // start or at another time of the same day; sleep from the credit where there is one. In
    // "streaks" every day closes all four rings, so each run crosses the window's clock change.
    let streaks = shape == "streaks"
    var inputs: [GoalHistory.DayInput] = []
    for k in 0..<goalDays {
        let roll = streaks ? 99 : rng.int(0, 99)
        if roll < 8 { continue }
        let date = rng.chance(70) ? starts[k] : minutes(noons[k], rng.int(-300, 300))
        if roll < 14 { inputs.append(.init(date: date)); continue }
        let full = streaks || roll < 65
        func some<T>(_ x: T) -> T? { streaks || rng.chance(92) ? x : nil }
        inputs.append(.init(
            date: date,
            steps: some(full ? rng.int(12_000, 20_000) : rng.int(0, 12_000)),
            activeKcal: some(full ? rng.real(460, 800) : rng.real(0, 460)),
            activityMinutes: some(full ? rng.real(46, 90) : rng.real(0, 46)),
            sleepMinutes: full ? 520 + rng.int(0, 120) : (credit[starts[k]] ?? (rng.chance(40) ? rng.int(0, 600) : nil))))
    }
    var duplicated = false
    if shape == "skew" {
        inputs.append(inputs[rng.int(0, inputs.count - 1)])
        inputs.append(inputs[rng.int(0, inputs.count - 1)])
        inputs.append(.init(date: cal.date(byAdding: .day, value: 4, to: starts[goalDays - 1])!, steps: 20_000, activeKcal: 600,
                            activityMinutes: 60, sleepMinutes: 600))
        duplicated = true
    }
    inputs = inputs.shuffledDeterministically(&rng)

    let now = shape == "streaks" ? cal.date(byAdding: .day, value: 1, to: noons[goalDays - 1])!
        : minutes(noons[rng.int(goalDays - 4, goalDays - 1)], rng.int(-600, 600))
    let days = GoalHistory.build(days: inputs, goals: goals, now: now, calendar: cal)
    let summaryNows = [now, cal.date(byAdding: .day, value: 1, to: noons[goalDays - 1])!,
                       cal.date(byAdding: .day, value: 3, to: noons[goalDays - 1])!, noons[goalDays / 2]]
    var weekendQueries = starts
    for _ in 0..<6 { weekendQueries.append(minutes(starts[rng.int(0, goalDays - 1)], rng.int(-30, 1500))) }

    func optMs(_ t: Date?) -> String { t.map { String(ms($0)) } ?? "-" }
    c.inputs.append("zone \(zone)")
    c.inputs.append("szone \(sumZone)")
    c.inputs.append("goals \(goals.workdaySteps) \(goals.weekendSteps) \(d(goals.activeKcal)) \(d(goals.activityMinutes)) \(goals.workdaySleepMin) \(goals.weekendSleepMin)")
    c.inputs.append("now \(ms(now))")
    for i in inputs {
        c.inputs.append("di \(ms(i.date)) \(optI(i.steps)) \(optD(i.activeKcal)) \(optD(i.activityMinutes)) \(optI(i.sleepMinutes))")
    }
    for n in nights { c.inputs.append("nt \(ms(n.nightKey)) \(optMs(n.inBedStart)) \(optMs(n.inBedEnd)) \(n.asleepMinutes)") }
    for p in naps { c.inputs.append("np \(ms(p.start)) \(ms(p.end)) \(p.asleepMinutes)") }
    c.inputs.append("sn" + summaryNows.map { " \(ms($0))" }.joined())
    c.inputs.append("wq" + weekendQueries.map { " \(ms($0))" }.joined())

    for (k, t) in weekendQueries.enumerated() {
        let weekend = GoalDefaults.isWeekend(t, calendar: cal)
        c.goldens.append("gw \(k) \(weekend ? 1 : 0) \(goals.stepGoal(on: t, calendar: cal)) \(goals.sleepGoalMinutes(on: t, calendar: cal))")
        hit(weekend ? "goal-weekend" : "goal-workday")
    }
    c.goldens.append("gc \(credit.count)" + credit.keys.sorted().map { " \(ms($0)) \(credit[$0]!)" }.joined())
    for (k, day) in days.enumerated() {
        c.goldens.append("gd \(k) \(ms(day.date)) \(mask(day.present)) \(mask(day.met)) \(day.isPartial ? 1 : 0) \(optD(day.attainment))"
            + GoalHistory.Ring.allCases.map { " " + d(day.fraction(for: $0)) }.joined())
        hit("goal-partial", day.isPartial)
        hit("goal-no-data", !day.hasData)
        hit("goal-closed-all", day.closedAll)
        hit("goal-no-midnight-day", cal.component(.hour, from: day.date) != 0 || cal.component(.minute, from: day.date) != 0)
    }
    for (k, t) in summaryNows.enumerated() {
        let s = GoalHistory.summarize(days, now: t, calendar: scal)
        c.goldens.append("gs \(k) \(s.daysWithData) \(s.daysAllClosed) \(s.currentStreak) \(s.longestStreak)"
            + GoalHistory.Ring.allCases.map { " " + optI(s.metCounts[$0]) }.joined()
            + GoalHistory.Ring.allCases.map { " " + optI(s.dataCounts[$0]) }.joined())
        hit("goal-streak-current", s.currentStreak > 0)
        hit("goal-streak-stale", s.currentStreak == 0 && s.longestStreak > 0)
    }
    let nightMinutes = nights.filter { $0.asleepMinutes > 0 }.map(\.asleepMinutes).reduce(0, +)
    let napMinutes = naps.filter { $0.asleepMinutes > 0 }.map(\.asleepMinutes).reduce(0, +)
    let credited = credit.values.reduce(0, +)
    hit("goal-nap-excluded", credited < nightMinutes + napMinutes)
    hit("goal-nap-credited", credited > nightMinutes)
    hit("goal-legacy-night", nights.contains { $0.inBedStart == nil && $0.asleepMinutes > 0 })
    hit("goal-widened-night", widened)
    hit("goal-normalised", inputs.contains { cal.startOfDay(for: $0.date) != $0.date })
    hit("goal-missing-day", days.count > 1 && zip(days, days.dropFirst()).contains { cal.dateComponents([.day], from: $0.date, to: $1.date).day! > 1 })
    hit("goal-cross-zone", sumZone != zone)
    hit("goal-duplicate-row", duplicated)
    return c
}

// MARK: - Formatter → the three number shapes

let fmtShapes = ["ties", "near", "edge", "cap"]
let fmtCasesPerShape = 3

func fmtCase(_ index: Int) -> Case {
    let shape = fmtShapes[index / fmtCasesPerShape]
    var rng = SplitMix64(state: 0x464D_5400 &+ UInt64(index))
    var c = Case(id: String(format: "fmt-%03d", index), kind: "fmt", shape: shape)
    var values: [Double] = []
    var fds: [Int] = []
    switch shape {
    case "ties":
        // Exact binary ties at one and two decimals (k/2, k/4, k/8, k/16, k/32), and their negatives.
        values = [0.25, 0.125, 2.5, -2.5, 0.375, -0.625]
        while values.count < 16 { values.append(Double(rng.int(-6_400, 6_400)) / Double(rng.pick([2, 4, 8, 16, 32]))) }
        fds = [0, 1, 2, 3]
    case "near":
        // One ulp either side of a tie, decimal fives that sit just off their tie (0.35, 9.95, 36.65,
        // 0.05), and values on a 1/1024 grid.
        values = [0.35, 9.95, 36.65, 0.05, 0.25.nextUp, 0.25.nextDown, 2.5.nextUp, 2.5.nextDown, -0.15, 98.45]
        while values.count < 16 { values.append(rng.real(-50, 150)) }
        fds = [0, 1, 2, 17]
    case "edge":
        values = [.nan, -Double.nan, .infinity, -.infinity, -0.0, 0.0, -0.04, -1e-300, .leastNonzeroMagnitude, 1e300,
                  -Double.greatestFiniteMagnitude, 1e21]
        values = Array(values.shuffledDeterministically(&rng).prefix(8)) + [rng.pick([36.6, 37.0, 0.5, -0.5]), 123456789012345678.0]
        fds = [-5, -1, 0, 1, 3, 20]
    default: // cap: the number cut at 510 characters, a field 510 wide or wider
        values = [36.6, -0.0, rng.pick([1e300, -Double.greatestFiniteMagnitude, 2.5])]
        fds = [506, 507, 508, 600, -509, -510, -600]
    }
    c.inputs.append("v" + values.map { " " + d($0) }.joined())
    c.inputs.append("fd" + ints(fds))
    var j = 0
    for v in values {
        for fd in fds {
            let strings = [
                UnitsFormatter.temperature(v, unit: .celsius, fractionDigits: fd),
                UnitsFormatter.temperature(v, unit: .fahrenheit, fractionDigits: fd),
                UnitsFormatter.temperatureDelta(v, unit: .celsius, fractionDigits: fd),
                UnitsFormatter.temperatureDelta(v, unit: .fahrenheit, fractionDigits: fd),
                UnitsFormatter.distance(v * 1000, unit: .metric, fractionDigits: fd),
                UnitsFormatter.distance(v * 1609.344, unit: .imperial, fractionDigits: fd),
            ]
            c.goldens.append("fmt \(j) " + strings.joined(separator: " | "))
            j += 1
            hit("fmt-tie", shape == "ties")
            hit("fmt-nan", v.isNaN)
            hit("fmt-inf", v.isInfinite)
            hit("fmt-negative-zero", strings[0].hasPrefix("-0") && v > -1)
            hit("fmt-negative-width", fd < 0)
            hit("fmt-capped", strings.contains { $0.unicodeScalars.count >= 513 })
            hit("fmt-huge", abs(v) >= 1e21 && v.isFinite)
        }
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
    + (0..<(dayShapes.count * dayCasesPerShape)).map(dayCase)
    + (0..<(baseShapes.count * baseCasesPerShape)).map(baseCase)
    + (0..<(tempShapes.count * tempCasesPerShape)).map(tempCase)
    + (0..<(wbShapes.count * wbCasesPerShape)).map(wbCase)
    + (0..<(actShapes.count * actCasesPerShape)).map(actCase)
    + (0..<(trendShapes.count * trendCasesPerShape)).map(trendCase)
    + (0..<(goalShapes.count * goalCasesPerShape)).map(goalCase)
    + (0..<(fmtShapes.count * fmtCasesPerShape)).map(fmtCase)
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
