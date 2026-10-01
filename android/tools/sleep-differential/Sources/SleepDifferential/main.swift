// SleepDifferential — runs upstream's sleep pipeline over seeded synthetic nights and over the
// fixture nights from upstream's own tests, and writes three files into the directory given as the
// only argument:
//
//   inputs.txt    every night's 23-byte 0x4c records and skin-temperature samples
//   goldens.txt   upstream's canonical outputs for each night
//   coverage.txt  how many nights reached each pipeline branch
//
// Everything is deterministic (SplitMix64 from fixed seeds), synthetic and locale-free: single
// spaces, '\n' line ends, lowercase hex, times as integer Unix epoch seconds, doubles as their
// IEEE-754 bit pattern in 16 hex digits. Rerunning on the same upstream commit reproduces every
// byte. `SleepDifferentialTest` (Kotlin) reads these files; Gradle never runs this program.
//
// inputs.txt, per night:
//   night <id> <shape>
//   r <46 hex digits>                  one record, in archive order
//   t <epoch seconds> <celsius bits>   one skin-temperature sample (optional)
//   end
// goldens.txt, per night:
//   night <id>
//   p <sleep|active> <start> <end>     detectFromMotion over the whole night, gates applied
//   main <start> <end> | main none     BulkSleep.mainSleep(from:temperatures:)
//   seg <stage> <start> <end>          BulkSleep.sleepSegments(from:temperatures:)
//   score <bits>                       SleepScore.score(start:end:) of the main block
//   onset <true|false>                 BulkSleep.onsetIsUnobserved(main block, in: the night)
//   overnight <zone> <plain> <presumed>  SleepWindow.isOvernightBlock without / with onsetIsUnobserved
//   sel <zone> <variant> <count> <first> <last>
//                                      BulkSleep.latestNightRecords(from:temperatures:) with the
//                                      device time zone set to <zone>: how many records it returns
//                                      and the first and last counter ("-" when none). The result
//                                      is always a contiguous run of the counter-sorted input, so
//                                      these three numbers fix it exactly. Variants: default; cut0
//                                      (observedGapCoverageCut 0, the guard off); noreanchor
//                                      (declinedBridgeMayReanchor false); nomorning
//                                      (morningContinuationGap 0).
//   stg <stage> <start> <end>          SleepStaging.classify(from:temperatures:), shipped tuning
//   sum <inBed> <awake> <light> <deep> <rem> <efficiency>
//                                      SleepStaging.summary of those segments: the five
//                                      TimeIntervals and the efficiency, as bit patterns
//   min <inBed> <awake> <light> <deep> <rem> <asleep>   Summary.minutes
//   win <onset> <wake> | win none      SleepStaging.sleepWindow
//   stgv <variant> <brief>             classify with one tuning knob changed; <brief> is
//                                      "<segments> <the six minutes> <onset|-> <wake|->".
//                                      Variants: noleadprotect (protectsLeadingHRWake false),
//                                      nocadence (cadenceWakeQuietEpochs 0), nowear (stagedWearGate
//                                      false), nooffset (offsetNoReturnSpreadFraction 0), norescue
//                                      (hrWakeRescueCeilingBPM 0), nowiden (preOnsetBedtimeReachEpochs
//                                      0), baseline (a PersonalBaseline of 44 bpm), rrvar
//                                      (rrVarWeight 0.5)
//   selstg <zone> <brief>              classify(from: latestNightRecords(from:temperatures:)) with the
//                                      device zone set to <zone> (America/New_York only)
//   The night metrics, all from the shipped-tuning staging above ("-" = absent):
//   cscore <score> <tier>              SleepScore.composite of the staged summary, with the mean HR
//                                      over the sleep window and the mean skin temperature there
//                                      minus 33.5 °C (each only when the window holds one)
//   cfac <six factor bit patterns>     its factors, in Factor.allCases order
//   hrstage <awake> <core> <deep> <rem>  SleepDetailMetrics.averageHRByStage
//   mov <still> <light> <active> <levels>  movementSummary over the in-bed span (the whole archive
//                                      when nothing stages); <levels> one digit per epoch
//   movf <bits>                        its movementFraction
//   stress <score>                     SleepStress.overnightScore of the in-bed records
//   stressdur <relaxed> <normal> <medium> <high>   stateDurations of the same, as bit patterns
//   avg <hr> <hrv> <spo2> <rr>         OvernightAverages.mean of each sample kind over the sleep window
//   naps <zone> <count>                NapDetection.naps(from:mainSleep:temperatures:) with the device
//                                      zone set to <zone> (UTC, Asia/Kolkata), then per nap:
//   nap <zone> <start> <end> <long> <asleep seconds>
//   napseg <zone> <stage> <start> <end>
//   and, for nights that carry 0x48 frames:
//   osadom <frames>                    OSAWaveform.dominantSessionFrames count
//   osach <ir> <red> <green>           OSAWaveform.channels of those frames, samples per channel
//   osaraw <bit patterns>              OSASpO2.spo2Series (the gated per-window SpO2, unsmoothed)
//   osaev <events>                     desaturationEvents of its 3-wide median filter
//   osa <validWindows> | osa none      OSASpO2.summarize(frames:)
//   osasum <avg> <min> <below90> <odi> <hours>   its doubles
//   end
// (score, onset and overnight lines appear only when there is a main block; sel, stg-family and
// metric lines always — they describe an empty night when nothing stages.)
// inputs.txt also carries, per OSA night:
//   w <394 hex digits>                 one 0x48 frame, in arrival order
//
// After the synthetic and fixture nights come the selection nights: multi-block archives built to
// reach every night-selection path (two nights, multi-drain holes, still evenings, short tails,
// morning continuations, late naps, truncated tails, daytime-only, all-day SpO2, leapfrogs, and
// nights either side of the intra-night gap). After those come the staging nights, built to reach
// each staging pass: an SpO2 cadence that exits before the data ends, an HR-elevated head, a second
// bout after a mid-night wake, a moving bedtime lead-in across a data gap, a quiet morning rise that
// stops emitting sleep-vitals, and still nights under cold, worn or out-of-block temperatures. Then
// daytime nap days (naps of every length around the 15 min floor and the 3 h long-nap mark,
// activity-tagged still blocks, a nap beside the night), and last the OSA nights: synthetic 0x48
// PPG bursts (clean, desaturating, low-perfusion, noisy, a re-dumped previous-night backlog with
// duplicate frames, and too short to hold one window).

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
    mutating func int(_ r: ClosedRange<Int>) -> Int { int(r.lowerBound, r.upperBound) }
    mutating func chance(_ percent: Int) -> Bool { int(0, 99) < percent }
    mutating func pick<T>(_ xs: [T]) -> T { xs[int(0, xs.count - 1)] }
}

// MARK: - Night construction

struct Night {
    let id: String
    let shape: String
    var records: [[UInt8]] = []
    var temps: [(Int, Double)] = []
    var frames: [[UInt8]] = []
}

/// Builds a night epoch by epoch; every epoch advances the counter by 150 s.
struct Builder {
    var rng: SplitMix64
    var counter: UInt32
    var records: [[UInt8]] = []
    /// Per record: was the ring off the finger (idle / charging) — drives "cold" temperatures.
    var offFinger: [Bool] = []

    mutating func push(hr: Int, hrv: Int, conf: Int, rr: Int, tag: Int,
                       motion: [Int], tail: [Int], trailer: [Int], offFinger off: Bool = false) {
        var b = [UInt8](repeating: 0, count: 23)
        b[0] = UInt8(counter >> 24); b[1] = UInt8((counter >> 16) & 0xFF)
        b[2] = UInt8((counter >> 8) & 0xFF); b[3] = UInt8(counter & 0xFF)
        b[4] = UInt8(hr); b[5] = UInt8(hrv); b[6] = UInt8(conf); b[7] = UInt8(rr)
        b[8] = UInt8(tag); b[9] = 0x0a
        for k in 0..<5 { b[10 + k] = UInt8(motion[k]); b[15 + k] = UInt8(tail[k]) }
        for k in 0..<3 { b[20 + k] = UInt8(trailer[k]) }
        records.append(b)
        offFinger.append(off)
        counter &+= 150
    }

    mutating func gap(_ seconds: Int) { counter &+= UInt32(seconds) }

    /// Awake and moving: varying motion, a non-zero intensity tail, daytime HR.
    mutating func active(_ n: Int, hr: ClosedRange<Int> = 72...110) {
        for _ in 0..<n {
            let motion = (0..<5).map { _ in rng.int(3, 140) }
            let tail = (0..<5).map { _ in rng.int(1, 255) }
            push(hr: rng.int(hr), hrv: rng.chance(50) ? 0 : rng.int(22, 60), conf: rng.int(4, 12),
                 rr: rng.int(0x60, 0x98), tag: rng.chance(85) ? 0x12 : 0x13,
                 motion: motion, tail: tail, trailer: [0x04, 0, 0])
        }
    }

    /// Asleep: still motion with rare twitches, sleep-vitals epochs interleaved with quiet
    /// activity epochs, HR around `base`. `dipPercent` of sleep-vitals epochs carry an SpO2 dip.
    mutating func asleep(_ n: Int, hr base: Int, dipPercent: Int = 0, twitchPercent: Int = 4) {
        for _ in 0..<n {
            var motion = [1, 1, 1, 1, 1]
            var tail = [0, 0, 0, 0, 0]
            if rng.chance(twitchPercent) {
                motion[rng.int(0, 4)] = rng.int(2, 7)
                tail[rng.int(0, 4)] = rng.int(1, 40)
            }
            let hr = base + rng.int(-3, 4)
            if rng.chance(55) {
                let spo2 = rng.chance(dipPercent) ? rng.int(84, 89) : rng.int(94, 99)
                push(hr: hr, hrv: rng.int(20, 90), conf: rng.int(2, 9), rr: rng.int(0x60, 0x88), tag: spo2,
                     motion: motion, tail: tail, trailer: [0, 0, 0x04])
            } else {
                push(hr: hr, hrv: rng.chance(40) ? 0 : rng.int(30, 70), conf: rng.int(3, 10), rr: rng.int(0x60, 0x88),
                     tag: 0x12, motion: motion, tail: tail, trailer: [0x04, 0, 0])
            }
        }
    }

    /// A short awakening inside the night: moderate movement, HR up.
    mutating func stir(_ n: Int, hr base: Int) {
        for _ in 0..<n {
            let motion = (0..<5).map { _ in rng.int(6, 90) }
            let tail = (0..<5).map { _ in rng.int(1, 200) }
            push(hr: base + rng.int(12, 28), hrv: 0, conf: rng.int(4, 10), rr: rng.int(0x60, 0x90), tag: 0x12,
                 motion: motion, tail: tail, trailer: [0x04, 0, 0])
        }
    }

    /// The unworn / charging idle template (layout idle).
    mutating func idle(_ n: Int) {
        for _ in 0..<n {
            push(hr: 0x05, hrv: 0, conf: 0x0c, rr: 0, tag: 0x12, motion: [1, 1, 1, 1, 1], tail: [0, 0, 0, 0, 0],
                 trailer: [0, 0, 0], offFinger: true)
        }
    }

    /// Still but awake (sitting out late): still motion, quiet tail, high HR.
    mutating func awakeStill(_ n: Int, hr: ClosedRange<Int>) {
        for _ in 0..<n {
            var motion = [1, 1, 1, 1, 1]
            if rng.chance(10) { motion[rng.int(0, 4)] = 2 }
            push(hr: rng.int(hr), hrv: 0, conf: rng.int(4, 10), rr: rng.int(0x68, 0x90), tag: 0x12,
                 motion: motion, tail: [0, 0, 0, 0, 0], trailer: [0x04, 0, 0])
        }
    }

    /// A restless morning while still asleep: spiky motion, sleep-vitals epochs, low HR.
    mutating func restless(_ n: Int, hr base: Int) {
        for _ in 0..<n {
            let motion = (0..<5).map { k in k % 2 == 0 ? rng.int(1, 3) : rng.int(60, 200) }
            let tail = (0..<5).map { _ in rng.int(1, 30) }
            push(hr: base + rng.int(-2, 4), hrv: rng.int(25, 70), conf: rng.int(2, 8), rr: rng.int(0x60, 0x80),
                 tag: rng.int(95, 98), motion: motion, tail: tail, trailer: [0, 0, 0x04])
        }
    }

    /// Gen-3 style raised, drifting idle floor (16 → 24 → 39 across the night).
    mutating func raisedFloor(_ n: Int, hr base: Int) {
        for i in 0..<n {
            let floor = i < n / 3 ? 16 : (i < 2 * n / 3 ? 24 : 39)
            let motion = (0..<5).map { _ in floor + rng.int(0, 2) }
            let sv = rng.chance(55)
            push(hr: base + rng.int(-3, 4), hrv: sv ? rng.int(20, 90) : 0, conf: rng.int(2, 9), rr: rng.int(0x60, 0x88),
                 tag: sv ? rng.int(94, 99) : 0x12, motion: motion, tail: [0, 0, 0, 0, 0],
                 trailer: sv ? [0, 0, 0x04] : [0x04, 0, 0])
        }
    }

    /// A primary channel that never reads still: a fixed intra-epoch template on quiet epochs.
    mutating func degenerateQuiet(_ n: Int, hr base: Int) {
        let template = [3, 9, 5, 12, 4]
        for _ in 0..<n {
            let motion = template.map { $0 + rng.int(0, 1) }
            let sv = rng.chance(55)
            push(hr: base + rng.int(-3, 4), hrv: sv ? rng.int(20, 90) : 0, conf: rng.int(2, 9), rr: rng.int(0x60, 0x88),
                 tag: sv ? rng.int(94, 99) : 0x12, motion: motion, tail: [0, 0, 0, 0, 0],
                 trailer: sv ? [0, 0, 0x04] : [0x04, 0, 0])
        }
    }

    /// Constant-filler epochs: all five primary slots equal; the tail carries movement when moving.
    mutating func constantFiller(_ n: Int, moving: Bool, hr base: Int) {
        for _ in 0..<n {
            let c = moving ? rng.int(5, 60) : rng.int(1, 3)
            let tail = moving ? (0..<5).map { _ in rng.int(1, 200) } : [0, 0, 0, 0, 0]
            push(hr: base + rng.int(-3, 6), hrv: 0, conf: rng.int(3, 10), rr: rng.int(0x60, 0x90), tag: 0x12,
                 motion: [c, c, c, c, c], tail: tail, trailer: [0x04, 0, 0])
        }
    }

    /// One still sleep-vitals epoch: placeholder motion, a quiet tail, an SpO2 % in [8].
    mutating func stillVitals(hr: Int, hrv: Int) {
        push(hr: hr, hrv: hrv, conf: rng.int(2, 8), rr: rng.int(0x60, 0x80), tag: rng.int(95, 98),
             motion: [1, 1, 1, 1, 1], tail: [0, 0, 0, 0, 0], trailer: [0, 0, 0x04])
    }

    /// One still activity-template epoch (no SpO2 read in it): placeholder motion, a quiet tail.
    mutating func stillNoSpO2(hr: Int) {
        push(hr: hr, hrv: 0, conf: rng.int(3, 9), rr: rng.int(0x60, 0x80), tag: 0x12,
             motion: [1, 1, 1, 1, 1], tail: [0, 0, 0, 0, 0], trailer: [0x04, 0, 0])
    }

    /// Still sleep in the ring's 1:1 SpO2 duty cycle (sleep-vitals and activity templates
    /// alternating), HR within ±1 of `base`.
    mutating func cadenceSleep(_ n: Int, hr base: Int) {
        for i in 0..<n {
            if i % 2 == 0 { stillVitals(hr: base + rng.int(-1, 1), hrv: rng.int(40, 70)) }
            else { stillNoSpO2(hr: base + rng.int(-1, 1)) }
        }
    }

    /// Awake while moving in bed (reading): varying motion that still carries HR on the
    /// sleep-vitals template.
    mutating func movingLeadIn(_ n: Int, hr: ClosedRange<Int>) {
        for _ in 0..<n {
            let motion = [rng.int(25, 35), rng.int(5, 10), rng.int(25, 35), rng.int(5, 10), rng.int(25, 35)]
            push(hr: rng.int(hr), hrv: 60, conf: rng.int(3, 9), rr: rng.int(0x60, 0x80), tag: rng.int(95, 98),
                 motion: motion, tail: [0, 0, 0, 0, 0], trailer: [0, 0, 0x04])
        }
    }
}

let shapes = [
    "normal", "fragmented", "gapped", "raised-floor", "degenerate-primary", "constant-filler", "cold",
    "charging-gap", "all-active", "nap-bearing", "spo2-dip", "awake-evening", "restless-morning",
    "truncated-onset", "short",
]
let nightsPerShape = 14

func padded(_ n: Int, _ width: Int) -> String {
    let s = String(n)
    return String(repeating: "0", count: max(0, width - s.count)) + s
}

/// Synthetic night `index` (deterministic from its index alone).
func synthetic(_ index: Int) -> Night {
    let shape = shapes[index % shapes.count]
    var rng = SplitMix64(state: 0x5EED_0000_0000_0000 &+ UInt64(index))
    // One night per day from counter day 2350, starting 20:00–24:00 UTC (counter 0 is 12:00 UTC).
    let day = 2350 + index
    let start = UInt32(day * 86_400 + 8 * 3600 + rng.int(0, 4 * 3600))
    var b = Builder(rng: SplitMix64(state: rng.next()), counter: start)
    let hr = rng.int(46, 62)
    var tempMode = rng.pick(["none", "none", "worn", "worn", "mixed"])

    switch shape {
    case "normal":
        b.active(rng.int(8, 30)); b.asleep(rng.int(150, 230), hr: hr); b.active(rng.int(8, 30))
    case "fragmented":
        b.active(rng.int(10, 25))
        for _ in 0..<rng.int(2, 5) { b.asleep(rng.int(25, 60), hr: hr); b.stir(rng.int(3, 8), hr: hr) }
        b.asleep(rng.int(30, 60), hr: hr); b.active(rng.int(10, 20))
    case "gapped":
        b.active(rng.int(8, 20)); b.asleep(rng.int(50, 110), hr: hr)
        b.gap(rng.pick([600, 1100, 1300, 2000, 5400, 10_800]))
        b.asleep(rng.int(40, 100), hr: hr)
        if rng.chance(50) { b.gap(rng.pick([900, 1300, 3600])); b.asleep(rng.int(20, 60), hr: hr) }
        b.active(rng.int(8, 20))
    case "raised-floor":
        b.active(rng.int(8, 20)); b.raisedFloor(rng.int(160, 220), hr: hr); b.active(rng.int(8, 20))
    case "degenerate-primary":
        b.active(rng.int(10, 20)); b.degenerateQuiet(rng.int(150, 200), hr: hr); b.active(rng.int(10, 20))
    case "constant-filler":
        b.constantFiller(rng.int(10, 20), moving: true, hr: hr + 30)
        b.constantFiller(rng.int(150, 200), moving: false, hr: hr)
        b.constantFiller(rng.int(10, 20), moving: true, hr: hr + 30)
    case "cold":
        b.active(rng.int(8, 20)); b.asleep(rng.int(150, 220), hr: hr); b.active(rng.int(8, 20))
        tempMode = rng.pick(["cold", "cold-first-half"])
    case "charging-gap":
        b.active(rng.int(8, 20)); b.asleep(rng.int(60, 120), hr: hr); b.idle(rng.int(6, 30))
        b.asleep(rng.int(60, 120), hr: hr); b.active(rng.int(8, 20))
        tempMode = "worn"
    case "all-active":
        b.active(rng.int(150, 250))
    case "nap-bearing":
        b.active(rng.int(10, 20)); b.asleep(rng.int(150, 200), hr: hr); b.active(rng.int(60, 120))
        b.asleep(rng.int(30, 60), hr: hr + 4); b.active(rng.int(20, 40))
    case "spo2-dip":
        b.active(rng.int(8, 20)); b.asleep(rng.int(150, 220), hr: hr, dipPercent: 15); b.active(rng.int(8, 20))
    case "awake-evening":
        b.active(rng.int(8, 15)); b.awakeStill(rng.int(40, 70), hr: 96...112); b.stir(rng.int(4, 8), hr: hr + 20)
        b.asleep(rng.int(150, 200), hr: hr); b.active(rng.int(8, 15))
    case "restless-morning":
        b.active(rng.int(8, 15)); b.asleep(rng.int(150, 200), hr: hr); b.restless(rng.int(20, 40), hr: hr)
        b.active(rng.int(8, 15))
    case "truncated-onset":
        // Yesterday's evening activity, then hours with nothing recorded, then only the night's tail.
        b.active(rng.int(20, 40)); b.gap(rng.int(5, 9) * 3600); b.asleep(rng.int(40, 140), hr: hr)
        b.active(rng.int(10, 20))
    case "short":
        b.active(rng.int(10, 20)); b.asleep(rng.int(18, 30), hr: hr); b.active(rng.int(10, 20))
    default:
        fatalError("unknown shape \(shape)")
    }

    var night = Night(id: "synthetic-\(padded(index, 3))", shape: shape, records: b.records)
    // Skin temperature every second epoch (5 min) at the epoch's own time.
    if tempMode != "none" {
        let n = b.records.count
        for i in stride(from: 0, to: n, by: 2) {
            let t = Int(BulkRecord(b.records[i])!.date().timeIntervalSince1970)
            let cold: Bool
            switch tempMode {
            case "cold": cold = true
            case "cold-first-half": cold = i < n / 2
            case "mixed": cold = b.offFinger[i] || (i * 7 / n) == 3
            default: cold = b.offFinger[i]
            }
            let tenths = cold ? 220 + b.rng.int(0, 40) : 315 + b.rng.int(0, 30)
            night.temps.append((t, Double(tenths) / 10))
        }
    }
    return night
}

// MARK: - Fixture nights from upstream's own tests (built exactly as there)

/// DeviceStatusTests.rec / RingKitVerify bulkRec: counter, [8] sub, motion [10:15], rest zero.
func bulkRec(_ c: UInt32, motion: UInt8, sub: UInt8) -> [UInt8] {
    var b = [UInt8](repeating: 0, count: 23)
    b[0] = UInt8(c >> 24); b[1] = UInt8((c >> 16) & 0xFF)
    b[2] = UInt8((c >> 8) & 0xFF); b[3] = UInt8(c & 0xFF)
    b[8] = sub
    for k in 0..<5 { b[10 + k] = motion }
    return b
}
func vrec(_ c: UInt32, motion: UInt8, hr: UInt8) -> [UInt8] { var b = bulkRec(c, motion: motion, sub: 0x62); b[4] = hr; return b }
func vrecHRV(_ c: UInt32, hr: UInt8, hrv: UInt8) -> [UInt8] { var b = vrec(c, motion: 0x01, hr: hr); b[5] = hrv; return b }
func hexBytes(_ s: String) -> [UInt8] {
    var out = [UInt8](); var i = s.startIndex
    while i < s.endIndex { let j = s.index(i, offsetBy: 2); out.append(UInt8(s[i..<j], radix: 16)!); i = j }
    return out
}
func recordDate(_ r: [UInt8]) -> Int { Int(BulkRecord(r)!.date().timeIntervalSince1970) }

func fixtures() -> [Night] {
    var out: [Night] = []

    // RingKitVerify main.swift:356-364 — active (varying) -> still 9 h -> active.
    var night: [[UInt8]] = []; var cc: UInt32 = 0x0c22_0000
    let activeMotion: [UInt8] = [0x0a, 0x28, 0x50]
    for i in 0..<20 { night.append(bulkRec(cc, motion: activeMotion[i % 3], sub: 0x12)); cc += 150 }
    for _ in 0..<216 { night.append(bulkRec(cc, motion: 0x01, sub: 0x62)); cc += 150 }
    for i in 0..<20 { night.append(bulkRec(cc, motion: activeMotion[i % 3], sub: 0x12)); cc += 150 }
    out.append(Night(id: "fixture-verify-night", shape: "fixture", records: night))

    // DeviceStatusTests.swift:232-284 — constant 0x14 flanks; cold, warm and no temperatures.
    var ds: [[UInt8]] = []; var c: UInt32 = 0x0c22_0000
    for _ in 0..<20 { ds.append(bulkRec(c, motion: 0x14, sub: 0x12)); c += 150 }
    for _ in 0..<216 { ds.append(bulkRec(c, motion: 0x01, sub: 0x62)); c += 150 }
    for _ in 0..<20 { ds.append(bulkRec(c, motion: 0x14, sub: 0x12)); c += 150 }
    out.append(Night(id: "fixture-device-status-night", shape: "fixture", records: ds))
    out.append(Night(id: "fixture-device-status-cold", shape: "fixture", records: ds, temps: ds.map { (recordDate($0), 22.0) }))
    out.append(Night(id: "fixture-device-status-warm", shape: "fixture", records: ds, temps: ds.map { (recordDate($0), 32.0) }))

    // RingKitVerify main.swift:377-386 — the staged night (REM / Deep / Light bands).
    var staged: [[UInt8]] = []; var sc: UInt32 = 0x0c22_0000
    for _ in 0..<20 { staged.append(bulkRec(sc, motion: 0x14, sub: 0x12)); sc += 150 }
    for k in 0..<60 { staged.append(vrec(sc, motion: 0x01, hr: k % 2 == 0 ? 62 : 70)); sc += 150 }
    for _ in 0..<60 { staged.append(vrec(sc, motion: 0x01, hr: 50)); sc += 150 }
    for k in 0..<60 { staged.append(vrec(sc, motion: 0x01, hr: k % 2 == 0 ? 56 : 62)); sc += 150 }
    for _ in 0..<20 { staged.append(bulkRec(sc, motion: 0x14, sub: 0x12)); sc += 150 }
    out.append(Night(id: "fixture-verify-staged", shape: "fixture", records: staged))

    // RingKitVerify main.swift:392-400 — two sleep cores split by a 2 h hole.
    var frag: [[UInt8]] = []; var fc: UInt32 = 0x0c22_0000
    for _ in 0..<8 { frag.append(bulkRec(fc, motion: 0x14, sub: 0x12)); fc += 150 }
    for _ in 0..<60 { frag.append(vrec(fc, motion: 0x01, hr: 52)); fc += 150 }
    for _ in 0..<8 { frag.append(bulkRec(fc, motion: 0x14, sub: 0x12)); fc += 150 }
    fc += 2 * 3600
    for _ in 0..<8 { frag.append(bulkRec(fc, motion: 0x14, sub: 0x12)); fc += 150 }
    for _ in 0..<60 { frag.append(vrec(fc, motion: 0x01, hr: 52)); fc += 150 }
    for _ in 0..<8 { frag.append(bulkRec(fc, motion: 0x14, sub: 0x12)); fc += 150 }
    out.append(Night(id: "fixture-verify-frag", shape: "fixture", records: frag))

    // RingKitVerify main.swift:419-423 — calm flat low HR.
    var deep: [[UInt8]] = []; var dc: UInt32 = 0x0c22_0000
    for _ in 0..<12 { deep.append(bulkRec(dc, motion: 0x14, sub: 0x12)); dc += 150 }
    for _ in 0..<120 { deep.append(vrec(dc, motion: 0x01, hr: 50)); dc += 150 }
    for _ in 0..<12 { deep.append(bulkRec(dc, motion: 0x14, sub: 0x12)); dc += 150 }
    out.append(Night(id: "fixture-verify-deep", shape: "fixture", records: deep))

    // RingKitVerify main.swift:435-446 — constructed multi-cycle night.
    var n2: [[UInt8]] = []; var nc: UInt32 = 0x0c22_0000
    for _ in 0..<8 { n2.append(bulkRec(nc, motion: 0x14, sub: 0x12)); nc += 150 }
    for cycle in 0..<5 {
        for k in 0..<10 { n2.append(vrecHRV(nc, hr: k % 2 == 0 ? 54 : 62, hrv: 60)); nc += 150 }
        for _ in 0..<8 { n2.append(vrecHRV(nc, hr: 50, hrv: 70)); nc += 150 }
        for k in 0..<8 { n2.append(vrecHRV(nc, hr: k % 2 == 0 ? 54 : 62, hrv: 60)); nc += 150 }
        for k in 0..<10 { n2.append(vrecHRV(nc, hr: k % 2 == 0 ? 64 : 78, hrv: 45)); nc += 150 }
        if cycle < 4 { for _ in 0..<2 { n2.append(bulkRec(nc, motion: 0x15, sub: 0x12)); nc += 150 } }
    }
    for _ in 0..<8 { n2.append(bulkRec(nc, motion: 0x14, sub: 0x12)); nc += 150 }
    out.append(Night(id: "fixture-verify-night2", shape: "fixture", records: n2))

    // RingKitVerify main.swift:306-310 — the real 0x4c page's six records, plus the :316 and :409
    // single epochs: too short to hold a night, they pin the empty path.
    let page = hexBytes("4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300"
        + "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f"
        + "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01"
        + "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc")
    var pageRecs = (0..<6).map { Array(page[(3 + 23 * $0)..<(3 + 23 * ($0 + 1))]) }
    pageRecs.append(hexBytes("0c22d5bf444d057a620a01010101012aa0000090000004"))
    pageRecs.append(hexBytes("0c22cd8b38520973620a01010101010000000000000004"))
    out.append(Night(id: "fixture-verify-page", shape: "fixture", records: pageRecs))
    return out
}

// MARK: - Selection nights (multi-block archives for night selection)

let selectionShapes = [
    "two-nights", "multi-drain-hole", "evening-block", "short-tail", "morning-continuation", "late-nap",
    "truncated-tail", "daytime-only", "all-day-spo2", "leapfrog", "intra-night-gap",
]
let selectionNightsPerShape = 10

/// The counter of UTC wall clock `hour:minute` on the calendar date that counter day `day` starts
/// on (counter 0 is 12:00 UTC, so hours before 12 are that date's morning).
func utc(_ day: Int, _ hour: Int, _ minute: Int = 0) -> UInt32 {
    UInt32(day * 86_400 + (hour - 12) * 3600 + minute * 60)
}

/// Selection archive `index` (deterministic from its index alone). Times are UTC wall clock; the
/// other device zones see the same archive shifted.
func selectionArchive(_ index: Int) -> Night {
    let shape = selectionShapes[index % selectionShapes.count]
    var rng = SplitMix64(state: 0x5E1E_C700_0000_0000 &+ UInt64(index))
    let day = 2700 + index                   // after every synthetic night
    let hr = rng.int(46, 62)
    var b = Builder(rng: SplitMix64(state: rng.next()), counter: utc(day, 18))
    func jump(_ to: UInt32) { b.counter = max(b.counter, to) }

    switch shape {
    case "two-nights":
        jump(utc(day, 21, rng.int(0, 59))); b.active(rng.int(4, 12))
        b.asleep(rng.int(150, 200), hr: hr); b.active(rng.int(8, 24))
        if rng.chance(50) { b.gap(rng.int(4, 8) * 3600); b.active(rng.int(10, 30)) }
        else { b.active(rng.int(60, 120)); b.gap(rng.int(3, 6) * 3600); b.active(rng.int(10, 20)) }
        jump(utc(day + 1, 22, rng.int(0, 59)))
        b.asleep(rng.int(150, 200), hr: hr); b.active(rng.int(8, 20))
    case "multi-drain-hole":
        jump(utc(day, 21, rng.int(0, 59))); b.active(rng.int(8, 16)); b.asleep(rng.int(40, 90), hr: hr)
        b.gap(rng.pick([1800, 3600, 7200, 10_800, 14_400])); b.asleep(rng.int(40, 100), hr: hr)
        if rng.chance(50) { b.gap(rng.pick([2400, 5400])); b.asleep(rng.int(20, 60), hr: hr) }
        b.active(rng.int(8, 16))
    case "evening-block":
        jump(utc(day, 20, rng.int(0, 40))); b.active(rng.int(4, 10))
        b.asleep(rng.int(26, 34), hr: hr); b.active(rng.int(12, 24))
        b.asleep(rng.int(150, 200), hr: hr); b.active(rng.int(8, 16))
    case "short-tail":
        jump(utc(day, 20, rng.int(0, 45))); b.active(rng.int(4, 10))
        b.asleep(rng.int(130, 170), hr: hr); b.active(rng.int(18, 30))
        b.asleep(rng.int(40, 70), hr: hr); b.active(rng.int(8, 16))
    case "morning-continuation":
        jump(utc(day + 1, 1, rng.int(0, 59))); b.active(rng.int(4, 10))
        b.asleep(rng.int(150, 190), hr: hr); b.stir(rng.int(2, 22), hr: hr)
        b.asleep(rng.int(8, 48), hr: hr); b.active(rng.int(8, 16))
    case "late-nap":
        jump(utc(day, 23, rng.int(0, 59))); b.active(rng.int(4, 8))
        b.asleep(rng.int(150, 200), hr: hr); b.active(rng.int(48, 96))
        b.asleep(rng.int(12, 36), hr: hr + 4); b.active(rng.int(8, 16))
    case "truncated-tail":
        jump(utc(day, 8, rng.int(0, 59))); b.active(rng.int(30, 80))
        jump(utc(day + 1, rng.int(7, 9), rng.int(0, 59)))
        b.asleep(rng.int(36, 96), hr: hr); b.active(rng.int(8, 16))
    case "daytime-only":
        jump(utc(day, 9, rng.int(0, 59))); b.active(rng.int(30, 60))
        b.asleep(rng.int(30, 80), hr: hr); b.active(rng.int(20, 40))
    case "all-day-spo2":
        jump(utc(day + 1, 1, rng.int(0, 59))); b.asleep(rng.int(96, 140), hr: hr); b.active(rng.int(4, 10))
        jump(utc(day + 1, 14, rng.int(0, 59)))
        for i in 0..<rng.int(20, 40) {
            if i % 4 == 0 {
                b.push(hr: 75, hrv: 0, conf: 6, rr: 0x70, tag: rng.int(95, 99), motion: [1, 1, 1, 1, 1],
                       tail: [0, 0, 0, 0, 0], trailer: [0, 0, 0x04])
            } else {
                b.active(1, hr: 70...90)
            }
        }
    case "leapfrog":
        jump(utc(day, 21, rng.int(0, 20))); b.asleep(rng.int(26, 34), hr: hr)
        b.gap(rng.int(5400, 9000)); b.asleep(rng.int(26, 34), hr: hr)
        b.active(rng.int(18, 30)); b.asleep(rng.int(80, 130), hr: hr); b.active(rng.int(8, 16))
    case "intra-night-gap":
        jump(utc(day, 20, rng.int(0, 59))); b.active(rng.int(4, 8)); b.asleep(rng.int(50, 80), hr: hr)
        b.gap(rng.int(5 * 3600, 7 * 3600)); b.asleep(rng.int(60, 120), hr: hr); b.active(rng.int(8, 16))
    default:
        fatalError("unknown selection shape \(shape)")
    }

    var night = Night(id: "selection-\(padded(index, 3))", shape: shape, records: b.records)
    // Half the archives carry worn skin temperature every second epoch (cold when off the finger).
    if rng.chance(50) {
        for i in stride(from: 0, to: b.records.count, by: 2) {
            let t = Int(BulkRecord(b.records[i])!.date().timeIntervalSince1970)
            let tenths = b.offFinger[i] ? 220 + b.rng.int(0, 40) : 315 + b.rng.int(0, 30)
            night.temps.append((t, Double(tenths) / 10))
        }
    }
    return night
}

// MARK: - Staging nights (single nights shaped to reach each staging pass)

let stagingShapes = ["cadence-exit", "elevated-head", "second-bout", "bedtime-lead-in", "offset-rise", "temperature-block"]
let stagingNightsPerShape = 10

/// Staging night `index` (deterministic from its index alone).
func stagingNight(_ index: Int) -> Night {
    let shape = stagingShapes[index % stagingShapes.count]
    var rng = SplitMix64(state: 0x57A6_1260_0000_0000 &+ UInt64(index))
    let day = 2900 + index                   // after every selection archive
    let hr = rng.int(46, 58)
    var b = Builder(rng: SplitMix64(state: rng.next()), counter: utc(day, 21, rng.int(0, 59)))
    var temps: [(Int, Double)] = []

    switch shape {
    case "cadence-exit":
        // The duty cycle holds through the night, then the morning is same-template throughout with a
        // rise too small for the wake gate.
        b.active(rng.int(8, 15)); b.cadenceSleep(rng.int(100, 160), hr: hr)
        let rise = rng.int(7, 12)
        for _ in 0..<rng.int(20, 50) { b.stillVitals(hr: hr + rise + rng.int(-1, 1), hrv: rng.int(40, 70)) }
        if rng.chance(50) { b.active(rng.int(6, 12)) }
    case "elevated-head":
        // A still head at an HR well above the night's floor, then flat still sleep.
        b.active(rng.int(8, 15))
        let head = hr + rng.int(20, 26)
        for _ in 0..<rng.int(3, 5) { b.stillVitals(hr: head + rng.int(-1, 1), hrv: rng.int(40, 70)) }
        b.cadenceSleep(rng.int(100, 150), hr: hr); b.active(rng.int(8, 12))
    case "second-bout":
        // A first bout at the floor, a moving bathroom trip, a still second bout a little higher.
        b.active(12); b.cadenceSleep(rng.int(80, 110), hr: hr); b.active(rng.int(3, 5))
        b.cadenceSleep(rng.int(60, 100), hr: hr + rng.int(18, 24)); b.active(12)
    case "bedtime-lead-in":
        // Reading in bed (moving, HR up), a short still settle, a data gap, then the sleep block.
        b.movingLeadIn(rng.int(10, 20), hr: 70...80)
        for _ in 0..<3 { b.stillVitals(hr: hr + 1, hrv: 60) }
        b.gap(150 * rng.int(2, 12))
        for _ in 0..<rng.int(100, 140) { b.stillVitals(hr: hr + rng.int(0, 1), hrv: 60) }
        if rng.chance(50) { b.active(rng.int(6, 12)) }
    case "offset-rise":
        // Flat sleep with HRV on every other epoch, then a quiet rise that carries no sleep-vitals.
        let n = rng.int(140, 170)
        let riseAt = n - rng.int(15, 25)
        for i in 0..<n {
            let tail = i >= riseAt
            b.stillVitals(hr: tail ? hr + 10 : hr, hrv: tail ? 0 : (i % 2 == 0 ? 55 : 0))
        }
    case "temperature-block":
        // A still night ending in an HR rise, with cold, worn or out-of-block skin temperatures.
        let n = rng.int(130, 170)
        b.cadenceSleep(n - 30, hr: hr)
        for i in 0..<30 { if i % 2 == 0 { b.stillVitals(hr: hr + 26, hrv: 55) } else { b.stillNoSpO2(hr: hr + 26) } }
        let first = recordDate(b.records[0])
        let last = recordDate(b.records[b.records.count - 1])
        switch rng.pick(["cold", "worn", "outside"]) {
        case "cold": for t in stride(from: first, through: last, by: 300) { temps.append((t, Double(200 + rng.int(0, 40)) / 10)) }
        case "worn": for t in stride(from: first, through: last, by: 300) { temps.append((t, Double(330 + rng.int(0, 20)) / 10)) }
        default: for k in 0..<60 { temps.append((first - 6 * 3600 + k * 300, 20.0)) }
        }
    default:
        fatalError("unknown staging shape \(shape)")
    }
    return Night(id: "staging-\(padded(index, 3))", shape: shape, records: b.records, temps: temps)
}

// MARK: - Nap days (daytime stillness of every length, beside or without a night)

let napDayShapes = ["nap-floor", "nap-long", "nap-sedentary", "nap-after-night", "nap-restless", "nap-uniform"]
let napDaysPerShape = 2

/// Nap day `index` (deterministic from its index alone). Times are UTC wall clock; Kolkata sees the
/// same archive 5 h 30 min later.
func napDay(_ index: Int) -> Night {
    let shape = napDayShapes[index % napDayShapes.count]
    var rng = SplitMix64(state: 0x0A9D_A400_0000_0000 &+ UInt64(index))
    let day = 3000 + index                   // after every staging night
    let hr = rng.int(48, 60)
    var b = Builder(rng: SplitMix64(state: rng.next()), counter: utc(day, 9, rng.int(0, 59)))
    switch shape {
    case "nap-floor":
        // Blocks either side of the 15 min floor, then an ordinary nap.
        b.active(20); b.asleep(rng.int(5, 9), hr: hr); b.active(20); b.asleep(rng.int(10, 14), hr: hr)
        b.active(20); b.asleep(rng.int(24, 48), hr: hr); b.active(20)
    case "nap-long":
        // Either side of the 3 h long-nap mark.
        b.active(20); b.asleep(rng.int(70, 80), hr: hr); b.active(20)
    case "nap-sedentary":
        // Awake at a desk (still, activity-tagged), then a real nap.
        b.active(20); b.awakeStill(rng.int(30, 50), hr: 68...80); b.active(20); b.asleep(rng.int(20, 40), hr: hr)
        b.active(20)
    case "nap-after-night":
        // The night ends, then a late-morning nap a couple of hours later.
        b.counter = utc(day + 1, 0, rng.int(0, 59))
        b.active(8); b.asleep(rng.int(150, 180), hr: hr); b.active(rng.int(40, 60)); b.asleep(rng.int(24, 48), hr: hr + 4)
        b.active(20)
    case "nap-restless":
        // A nap broken by short stirs.
        b.active(20); b.asleep(rng.int(10, 16), hr: hr); b.stir(2, hr: hr); b.asleep(rng.int(10, 16), hr: hr)
        b.stir(1, hr: hr); b.asleep(rng.int(8, 14), hr: hr); b.active(20)
    case "nap-uniform":
        // Short, perfectly still sleep-vitals blocks: too short to stage, so the nap falls back to its
        // coarse split (or, with nothing detected inside, to the whole window asleep).
        b.active(20)
        for _ in 0..<rng.int(9, 12) { b.stillVitals(hr: hr, hrv: 50) }
        b.active(20)
        for _ in 0..<rng.int(16, 30) { b.stillVitals(hr: hr, hrv: 50) }
        b.active(20)
    default:
        fatalError("unknown nap shape \(shape)")
    }
    var night = Night(id: "napday-\(padded(index, 3))", shape: shape, records: b.records)
    if rng.chance(50) {
        for i in stride(from: 0, to: b.records.count, by: 2) {
            let t = Int(BulkRecord(b.records[i])!.date().timeIntervalSince1970)
            night.temps.append((t, Double(315 + b.rng.int(0, 30)) / 10))
        }
    }
    return night
}

// MARK: - OSA nights (synthetic 0x48 PPG bursts)

let osaShapes = ["osa-clean", "osa-dips", "osa-lowperf", "osa-noisy", "osa-backlog", "osa-short"]
let osaNightsPerShape = 5

/// Pack three channels into 0x48 frames: 20 samples per channel per frame, two 30-sample blocks at
/// [15] and [106] interleaving the channels, counters stepping down by 20 from `top`, an XOR trailer.
func osaFrames(_ ch: [[Int]], cursor: UInt32, top: UInt32) -> [[UInt8]] {
    var out: [[UInt8]] = []
    for k in 0..<(ch[0].count / 20) {
        var f = [UInt8](repeating: 0, count: 197)
        let counter = top &- UInt32(k * 20)
        f[0] = 0x48; f[1] = 0xc1
        for (o, v) in [(2, counter), (6, cursor)] {
            f[o] = UInt8(v >> 24); f[o + 1] = UInt8((v >> 16) & 0xFF); f[o + 2] = UInt8((v >> 8) & 0xFF); f[o + 3] = UInt8(v & 0xFF)
        }
        f[11] = 0x46; f[12] = 0x50; f[14] = 0x5b; f[105] = 0x5c
        for (blockIndex, blk) in [15, 106].enumerated() {
            for s in 0..<30 {
                let v = ch[s % 3][k * 20 + blockIndex * 10 + s / 3]
                f[blk + s * 3] = UInt8((v >> 16) & 0xFF); f[blk + s * 3 + 1] = UInt8((v >> 8) & 0xFF); f[blk + s * 3 + 2] = UInt8(v & 0xFF)
            }
        }
        f[196] = f[0..<196].reduce(0, ^)
        out.append(f)
    }
    return out
}

/// OSA night `index` (deterministic from its index alone): IR / red / green PPG at ~4.15 Hz with a
/// cardiac pulse, the red/IR ratio R setting SpO2 (≈ 104.91 − 15.18·R), and per-shape perfusion,
/// noise and desaturations.
func osaNight(_ index: Int) -> Night {
    let shape = osaShapes[index % osaShapes.count]
    var rng = SplitMix64(state: 0x05A0_0000_0000_0000 &+ UInt64(index))
    let n = shape == "osa-short" ? 20 * rng.int(2, 6) : 20 * rng.int(60, 150)
    let fc = Double(rng.int(240, 310)) / 1000          // cardiac frequency, cycles per sample
    let dcIR = Double(rng.int(330_000, 360_000)), dcRed = Double(rng.int(280_000, 295_000)), dcGreen = Double(rng.int(265_000, 280_000))
    let perfusion = shape == "osa-lowperf" ? 0.0006 : Double(rng.int(80, 150)) / 10_000
    let noise = shape == "osa-noisy" ? rng.int(2_000, 6_000) : rng.int(0, 60)
    let baseR = Double(rng.int(58, 70)) / 100
    let dipEvery = rng.int(500, 900), dipLength = rng.int(80, 170)
    var ch: [[Int]] = [[], [], []]
    for t in 0..<n {
        let inDip = shape == "osa-dips" && t % dipEvery >= dipEvery - dipLength
        let r = inDip ? Double(rng.int(115, 135)) / 100 : baseR + Double(rng.int(-2, 2)) / 100
        let wave = cos(2 * Double.pi * fc * Double(t))
        let acIR = dcIR * perfusion
        let acRed = r * acIR / dcIR * dcRed
        ch[0].append(Int(dcIR + acIR * wave) + rng.int(-noise, noise))
        ch[1].append(Int(dcRed + acRed * wave) + rng.int(-noise, noise))
        ch[2].append(Int(dcGreen + 2 * acIR * wave) + rng.int(-noise, noise))
    }
    let cursor = UInt32(0x0c40_0000 + index * 0x1000)
    var frames = osaFrames(ch, cursor: cursor, top: UInt32(rng.int(80_000, 120_000)))
    if shape == "osa-backlog" {
        // A previous night's session re-dumped first (fewer frames, another cursor), and some of
        // tonight's frames retransmitted.
        var old: [[Int]] = [[], [], []]
        for t in 0..<(20 * rng.int(20, 40)) {
            let wave = cos(2 * Double.pi * fc * Double(t))
            old[0].append(Int(dcIR + dcIR * perfusion * wave)); old[1].append(Int(dcRed + baseR * perfusion * dcRed * wave))
            old[2].append(Int(dcGreen + 2 * dcIR * perfusion * wave))
        }
        let previous = osaFrames(old, cursor: cursor &- 0x100, top: UInt32(rng.int(200_000, 220_000)))
        let dups = (0..<rng.int(5, 20)).map { _ in frames[rng.int(0, frames.count - 1)] }
        frames = previous + frames + dups
    }
    if rng.chance(50) { frames.reverse() }            // arrival order never decides the decode
    return Night(id: "osa-\(padded(index, 3))", shape: shape, frames: frames)
}

// MARK: - Device time zone

/// Night selection reads the device calendar, so the generator sets the process default zone and
/// checks that the calendar really follows it before calling in.
func withDeviceZone<T>(_ zone: String, _ body: () -> T) -> T {
    let tz = TimeZone(identifier: zone)!
    NSTimeZone.default = tz
    precondition(Calendar.current.timeZone.identifier == tz.identifier, "device zone \(zone) not applied")
    return body()
}
let selectionZones = ["UTC", "Asia/Kolkata", "America/New_York"]

// MARK: - Canonical rendering

let hexDigits = Array("0123456789abcdef")
func hex(_ bytes: [UInt8]) -> String {
    var s = ""
    s.reserveCapacity(bytes.count * 2)
    for b in bytes { s.append(hexDigits[Int(b >> 4)]); s.append(hexDigits[Int(b & 0x0f)]) }
    return s
}
func bits(_ d: Double) -> String {
    let h = String(d.bitPattern, radix: 16)
    return String(repeating: "0", count: 16 - h.count) + h
}
func secs(_ d: Date) -> String {
    let t = d.timeIntervalSince1970
    precondition(t == t.rounded(), "non-integral time \(t)")
    return String(Int64(t))
}
func name(_ a: Activity) -> String { a == .sleep ? "sleep" : "active" }

func calendar(_ zone: String) -> Calendar {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: zone)!
    return c
}
let overnightZones = ["UTC", "Asia/Kolkata"]

var coverage: [String: Int] = [:]
func hit(_ branch: String, _ yes: Bool = true) { if yes { coverage[branch, default: 0] += 1 } }

func golden(_ night: Night) -> [String] {
    let recs = night.records.map { BulkRecord($0)! }
    let temps = night.temps.map { TemperatureSample(time: Date(timeIntervalSince1970: TimeInterval($0.0)), celsius: $0.1) }
    let motion = BulkSleep.motionTimeline(from: recs)
    let hr = BulkSleep.heartRateTimeline(from: recs)
    let sv = BulkSleep.sleepVitalTimeline(from: recs)
    let periods = ActivityPeriod.detectFromMotion(motion, temperatureSamples: temps, heartRateSamples: hr, sleepVitalTimes: sv)
    let main = BulkSleep.mainSleep(from: recs, temperatures: temps)
    let segs = BulkSleep.sleepSegments(from: recs, temperatures: temps)

    var lines = ["night \(night.id)"]
    lines += periods.map { "p \(name($0.activity)) \(secs($0.start)) \(secs($0.end))" }
    lines.append(main.map { "main \(secs($0.start)) \(secs($0.end))" } ?? "main none")
    lines += segs.map { "seg \($0.stage.rawValue) \(secs($0.start)) \(secs($0.end))" }
    if let m = main {
        lines.append("score \(bits(SleepScore.score(start: m.start, end: m.end)))")
        let onset = BulkSleep.onsetIsUnobserved(DateInterval(start: m.start, end: m.end), in: recs)
        lines.append("onset \(onset)")
        for z in overnightZones {
            let plain = SleepWindow.isOvernightBlock(start: m.start, end: m.end, calendar: calendar(z))
            let presumed = SleepWindow.isOvernightBlock(start: m.start, end: m.end, onsetIsUnobserved: true, calendar: calendar(z))
            lines.append("overnight \(z) \(plain) \(presumed)")
            hit("overnight-\(z)", plain)
            hit("overnight-presumed-only-\(z)", presumed && !plain)
        }
        hit("onset-unobserved", onset)
    }
    lines += selection(of: recs, temps: temps)
    let staged = staging(of: recs, temps: temps)
    lines += staged.lines
    lines += metrics(of: recs, temps: temps, main: main, segs: staged.segs)
    if !night.frames.isEmpty { lines += osa(night.frames) }
    lines.append("end")

    // Branch coverage — which pipeline paths this night exercised.
    hit("nights")
    hit("shape-\(night.shape)")
    hit("main-none", main == nil)
    hit("multi-fragment", BulkSleep.contiguousFragments(recs).count > 1)
    let noGates = ActivityPeriod.detectFromMotion(motion)
    let wearOnly = ActivityPeriod.detectFromMotion(motion, temperatureSamples: temps)
    let wearHR = ActivityPeriod.detectFromMotion(motion, temperatureSamples: temps, heartRateSamples: hr)
    hit("wear-gate-changed", wearOnly != noGates)
    hit("hr-gate-changed", wearHR != wearOnly)
    hit("rescue-changed", periods != wearHR)
    switch BulkSleep.motionSource(recs) {
    case .primary: hit("motion-primary")
    case .intensityTail(let degenerate): hit(degenerate ? "motion-tail-degenerate" : "motion-tail-constant-filler")
    case .activityMagnitudes: hit("motion-activity-magnitudes")
    }
    hit("has-temperatures", !temps.isEmpty)
    return lines
}

/// `count first last` of a selected slice.
func sliceKey(_ r: [BulkRecord]) -> String {
    "\(r.count) \(r.first.map { String($0.counter) } ?? "-") \(r.last.map { String($0.counter) } ?? "-")"
}

/// The `sel` lines for one archive, in every device zone, and the selection branches it reached.
func selection(of recs: [BulkRecord], temps: [TemperatureSample]) -> [String] {
    var lines: [String] = []
    for z in selectionZones {
        withDeviceZone(z) {
            let variants: [(String, [BulkRecord])] = [
                ("default", BulkSleep.latestNightRecords(from: recs, temperatures: temps)),
                ("cut0", BulkSleep.latestNightRecords(from: recs, temperatures: temps, observedGapCoverageCut: 0)),
                ("noreanchor", BulkSleep.latestNightRecords(from: recs, temperatures: temps, declinedBridgeMayReanchor: false)),
                ("nomorning", BulkSleep.latestNightRecords(from: recs, temperatures: temps, morningContinuationGap: 0)),
            ]
            lines += variants.map { "sel \(z) \($0.0) \(sliceKey($0.1))" }
            let key = Dictionary(uniqueKeysWithValues: variants.map { ($0.0, sliceKey($0.1)) })
            hit("sel-guard-declined", key["default"] != key["cut0"])
            hit("sel-reanchored", key["default"] != key["noreanchor"])
            hit("sel-morning-absorbed", key["default"] != key["nomorning"])
            hit("sel-scoped", variants[0].1.count < recs.count)

            // Which pass of the overnight filter accepted the night (recomputed from the public
            // pieces, for coverage only — the goldens above come from latestNightRecords itself).
            let sorted = recs.sorted { $0.counter < $1.counter }
            let periods = ActivityPeriod.detectFromMotion(BulkSleep.motionTimeline(from: sorted),
                                                          temperatureSamples: temps,
                                                          heartRateSamples: BulkSleep.heartRateTimeline(from: sorted),
                                                          sleepVitalTimes: BulkSleep.sleepVitalTimeline(from: sorted))
            let blocks = periods.filter { $0.activity == .sleep && $0.duration > ActivityPeriod.minSleepDuration }
            let plain = blocks.filter { SleepWindow.isOvernightBlock(start: $0.start, end: $0.end) }
            let corrected = plain.isEmpty ? blocks.filter {
                SleepWindow.isOvernightBlock(
                    start: $0.start, end: $0.end,
                    onsetIsUnobserved: BulkSleep.onsetIsUnobserved(DateInterval(start: $0.start, end: max($0.end, $0.start)),
                                                                   in: sorted))
            } : []
            hit("sel-truncated-correction", !corrected.isEmpty)
            hit("sel-no-night", plain.isEmpty && corrected.isEmpty)
            hit("sel-several-nights", plain.count + corrected.count > 1)
        }
    }
    return lines
}

/// `<segments> <the six minutes> <onset|-> <wake|->` of one staged night.
func brief(_ segs: [SleepSegment]) -> String {
    let m = SleepStaging.summary(segs).minutes
    let w = SleepStaging.sleepWindow(segs)
    return "\(segs.count) \(m.inBed) \(m.awake) \(m.light) \(m.deep) \(m.rem) \(m.asleep) "
        + "\(w.map { secs($0.onset) } ?? "-") \(w.map { secs($0.wake) } ?? "-")"
}

/// The staging lines for one night (and its shipped-tuning segments), and the staging branches it reached.
func staging(of recs: [BulkRecord], temps: [TemperatureSample]) -> (lines: [String], segs: [SleepSegment]) {
    let segs = SleepStaging.classify(from: recs, temperatures: temps)
    let s = SleepStaging.summary(segs)
    let m = s.minutes
    var lines = segs.map { "stg \($0.stage.rawValue) \(secs($0.start)) \(secs($0.end))" }
    lines.append("sum \(bits(s.inBed)) \(bits(s.awake)) \(bits(s.light)) \(bits(s.deep)) \(bits(s.rem)) \(bits(s.efficiency))")
    lines.append("min \(m.inBed) \(m.awake) \(m.light) \(m.deep) \(m.rem) \(m.asleep)")
    lines.append(SleepStaging.sleepWindow(segs).map { "win \(secs($0.onset)) \(secs($0.wake))" } ?? "win none")

    func tuned(_ change: (inout SleepStaging.Tuning) -> Void) -> SleepStaging.Tuning {
        var t = SleepStaging.Tuning.default
        change(&t)
        return t
    }
    let variants: [(String, [SleepSegment])] = [
        ("noleadprotect", SleepStaging.classify(from: recs, temperatures: temps, tuning: tuned { $0.protectsLeadingHRWake = false })),
        ("nocadence", SleepStaging.classify(from: recs, temperatures: temps, tuning: tuned { $0.cadenceWakeQuietEpochs = 0 })),
        ("nowear", SleepStaging.classify(from: recs, temperatures: temps, tuning: tuned { $0.stagedWearGate = false })),
        ("nooffset", SleepStaging.classify(from: recs, temperatures: temps, tuning: tuned { $0.offsetNoReturnSpreadFraction = 0 })),
        ("norescue", SleepStaging.classify(from: recs, temperatures: temps, tuning: tuned { $0.hrWakeRescueCeilingBPM = 0 })),
        ("nowiden", SleepStaging.classify(from: recs, temperatures: temps, tuning: tuned { $0.preOnsetBedtimeReachEpochs = 0 })),
        ("baseline", SleepStaging.classify(from: recs, temperatures: temps,
                                           baseline: SleepStaging.PersonalBaseline(deepSleepHR: 44))),
        ("rrvar", SleepStaging.classify(from: recs, temperatures: temps, tuning: tuned { $0.rrVarWeight = 0.5 })),
    ]
    lines += variants.map { "stgv \($0.0) \(brief($0.1))" }
    let selected = withDeviceZone("America/New_York") {
        SleepStaging.classify(from: BulkSleep.latestNightRecords(from: recs, temperatures: temps), temperatures: temps)
    }
    lines.append("selstg America/New_York \(brief(selected))")

    hit("stg-staged", !segs.isEmpty)
    hit("stg-multi-fragment", !segs.isEmpty && BulkSleep.contiguousFragments(recs).count > 1)
    let branch = ["noleadprotect": "stg-leading-wake", "nocadence": "stg-cadence-wake", "nowear": "stg-wear-gate",
                  "nooffset": "stg-offset", "norescue": "stg-rescue", "nowiden": "stg-bedtime-widen",
                  "baseline": "stg-baseline", "rrvar": "stg-rr-variability"]
    for (name, out) in variants { hit(branch[name]!, out != segs) }
    for stage in [SleepStage.awake, .asleepCore, .asleepDeep, .asleepREM] {
        hit("stg-stage-\(stage.rawValue)", segs.contains { $0.stage == stage })
    }
    return (lines, segs)
}

// MARK: - Night metrics

let napZones = ["UTC", "Asia/Kolkata"]

func opt(_ d: Double?) -> String { d.map(bits) ?? "-" }

/// The metric lines for one night, from its shipped-tuning staging, and the metric branches it reached.
func metrics(of recs: [BulkRecord], temps: [TemperatureSample], main: ActivityPeriod?, segs: [SleepSegment]) -> [String] {
    var lines: [String] = []
    let s = SleepStaging.summary(segs)
    let window = SleepStaging.sleepWindow(segs).map { DateInterval(start: $0.onset, end: $0.wake) }
    let samples = BulkSleep.samples(from: recs)
    func mean(_ kind: MetricKind) -> Double? {
        guard let w = window else { return nil }
        return OvernightAverages.mean(samples.filter { $0.kind == kind }.map { .init(value: $0.value, start: $0.start) }, window: w)
    }
    let restingHR = mean(.heartRate)
    let tempOffset = window.flatMap { w in
        OvernightAverages.mean(temps.map { .init(value: $0.celsius, start: $0.time) }, window: w)
    }.map { $0 - 33.5 }
    let c = SleepScore.composite(.init(totalAsleep: s.totalAsleep, timeAwake: s.awake, efficiency: s.efficiency,
                                       deep: s.deep, light: s.light, rem: s.rem,
                                       restingHR: restingHR, tempOffsetC: tempOffset))
    lines.append("cscore \(c.score) \(c.tier.rawValue)")
    lines.append("cfac " + SleepScore.Composite.Factor.allCases.map { opt(c.factors[$0]) }.joined(separator: " "))
    hit("cscore-\(c.tier.rawValue)")
    hit("cscore-factors-\(c.factors.count)")

    let byStage = SleepDetailMetrics.averageHRByStage(records: recs, segments: segs)
    lines.append("hrstage " + [SleepStage.awake, .asleepCore, .asleepDeep, .asleepREM]
        .map { byStage[$0].map(String.init) ?? "-" }.joined(separator: " "))
    hit("hrstage-any", !byStage.isEmpty)

    let inBed = segs.filter { $0.stage == .inBed }
    let span = inBed.isEmpty ? nil : DateInterval(start: inBed.map(\.start).min()!, end: inBed.map(\.end).max()!)
    let m = SleepDetailMetrics.movementSummary(records: recs, in: span)
    lines.append("mov \(m.still) \(m.light) \(m.active) \(m.levels.isEmpty ? "-" : m.levels.map(String.init).joined())")
    lines.append("movf \(bits(m.movementFraction))")
    hit("mov-light", m.light > 0)
    hit("mov-active", m.active > 0)

    let scoped = BulkSleep.records(recs, within: span)
    let stress = SleepStress.overnightScore(records: scoped)
    let durations = SleepStress.stateDurations(records: scoped)
    lines.append("stress \(stress.map(String.init) ?? "-")")
    lines.append("stressdur " + SleepStress.Band.allCases.map { opt(durations[$0]) }.joined(separator: " "))
    hit("stress-none", stress == nil)
    if let st = stress { hit("stress-\(SleepStress.Band.of(st).rawValue)") }

    lines.append("avg " + [MetricKind.heartRate, .hrvSDNN, .spo2, .respiratoryRate].map { opt(mean($0)) }.joined(separator: " "))

    // Nap candidates (recomputed from the public pieces, for coverage only — the goldens come from naps).
    let candidates = ActivityPeriod.detectFromMotion(BulkSleep.motionTimeline(from: recs), temperatureSamples: temps)
    for z in napZones {
        let naps = withDeviceZone(z) { NapDetection.naps(from: recs, mainSleep: main, temperatures: temps) }
        lines.append("naps \(z) \(naps.count)")
        for n in naps {
            precondition(n.asleep == n.asleep.rounded(), "non-integral nap asleep \(n.asleep)")
            lines.append("nap \(z) \(secs(n.start)) \(secs(n.end)) \(n.isLongNap) \(Int64(n.asleep))")
            lines += n.segments.map { "napseg \(z) \($0.stage.rawValue) \(secs($0.start)) \(secs($0.end))" }
            hit("nap-long", n.isLongNap)
            hit("nap-whole-window", n.segments.count == 2 && n.segments.allSatisfy { $0.start == n.start && $0.end == n.end })
            let stagedNap = BulkSleep.stagedSegments(from: recs.filter { let t = $0.date(); return t >= n.start && t <= n.end },
                                                     within: DateInterval(start: n.start, end: n.end))
            hit("nap-coarse-fallback", !stagedNap.contains { $0.stage == .asleepCore || $0.stage == .asleepDeep || $0.stage == .asleepREM })
            hit("nap-staged-deep-or-rem", n.segments.contains { $0.stage == .asleepDeep || $0.stage == .asleepREM })
            hit("nap-awake-inside", n.segments.contains { $0.stage == .awake })
        }
        hit("nap-found-\(z)", !naps.isEmpty)

        // Why candidate blocks were not naps.
        for p in candidates where p.activity == .sleep {
            if p.duration < NapDetection.minNapDuration { hit("nap-cand-too-short"); continue }
            if let mm = main, p.start < mm.end && p.end > mm.start { hit("nap-cand-overlaps-main"); continue }
            if withDeviceZone(z, { SleepWindow.isOvernightBlock(start: p.start, end: p.end) }) { hit("nap-cand-overnight-\(z)"); continue }
            let worn = recs.filter { let t = $0.date(); return t >= p.start && t <= p.end && $0.layout != .idle }
            let share = worn.isEmpty ? 0 : Double(worn.filter { $0.layout == .sleepVitals }.count) / Double(worn.count)
            hit("nap-cand-share-rejected", share < NapDetection.minNapSleepVitalsShare)
        }
    }
    return lines
}

/// The OSA lines for one night's 0x48 frames, and the OSA branches it reached.
func osa(_ frames: [[UInt8]]) -> [String] {
    var lines: [String] = []
    let dominant = OSAWaveform.dominantSessionFrames(frames)
    let ch = OSAWaveform.channels(from: dominant)
    let raw = OSASpO2.spo2Series(ir: ch[0], red: ch[1], green: ch[2])
    lines.append("osadom \(dominant.count)")
    lines.append("osach \(ch[0].count) \(ch[1].count) \(ch[2].count)")
    lines.append("osaraw" + raw.map { " " + bits($0) }.joined())
    lines.append("osaev \(OSASpO2.desaturationEvents(OSASpO2.medianFilter(raw, 3)))")
    if let s = OSASpO2.summarize(frames: frames) {
        lines.append("osa \(s.validWindows)")
        lines.append("osasum \(bits(s.averageSpO2)) \(bits(s.minSpO2)) \(bits(s.timeBelow90Seconds)) \(bits(s.odi)) \(bits(s.durationHours))")
        hit("osa-summary")
        hit("osa-below90", s.timeBelow90Seconds > 0)
        hit("osa-events", s.odi > 0)
    } else {
        lines.append("osa none")
        hit("osa-none")
    }
    hit("osa-backlog-dropped", dominant.count < frames.count)
    hit("osa-duplicate-frames", ch[0].count < dominant.count * OSAWaveform.samplesPerChannelPerFrame)
    let windows = ch[0].count >= OSASpO2.windowLength ? (ch[0].count - OSASpO2.windowLength) / OSASpO2.windowStep + 1 : 0
    hit("osa-windows-gated", raw.count < windows)
    return lines
}

func inputLines(_ night: Night) -> [String] {
    var lines = ["night \(night.id) \(night.shape)"]
    lines += night.records.map { "r \(hex($0))" }
    lines += night.temps.map { "t \($0.0) \(bits($0.1))" }
    lines += night.frames.map { "w \(hex($0))" }
    lines.append("end")
    return lines
}

// MARK: - Main

let args = CommandLine.arguments
guard args.count == 2 else {
    FileHandle.standardError.write("usage: SleepDifferential <output directory>\n".data(using: .utf8)!)
    exit(2)
}
let outDir = URL(fileURLWithPath: args[1], isDirectory: true)

let nights = (0..<(shapes.count * nightsPerShape)).map(synthetic) + fixtures()
    + (0..<(selectionShapes.count * selectionNightsPerShape)).map(selectionArchive)
    + (0..<(stagingShapes.count * stagingNightsPerShape)).map(stagingNight)
    + (0..<(napDayShapes.count * napDaysPerShape)).map(napDay)
    + (0..<(osaShapes.count * osaNightsPerShape)).map(osaNight)
let header = "# Generated by android/tools/sleep-differential from upstream OpenCircuitKit; do not edit by hand."
var inputs = [header]
var goldens = [header]
for n in nights {
    inputs += inputLines(n)
    goldens += golden(n)
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
for l in coverageLines.dropFirst() { print(l) }
