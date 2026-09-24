// Sleeping skin-temperature baseline + nightly deviation (Oura-style) — #69.
//
// The signature ring feature: each night's MEAN sleeping skin temperature, a signed
// DEVIATION from a rolling baseline (the app uses the previous 30 nights), and a
// normal/abnormal classification. The APK's own copy (pp.txt:47432) defines it exactly:
//   "the baseline represents the average sleeping skin temperature of the previous 30
//    days … Temperatures within 1°C (1.8°F) of the baseline are considered normal."
//
// Skin temp is decoded 🟢 (0.1 °C, descriptor 0x10/0x87 — PROTOCOL §5.4) but rides the
// LIVE descriptor, not the bulk 0x4c/0x47 drain — so a nightly value depends on the ring
// staying connected through the night (RingSession window-gates + persists those readings),
// OR on the temp/RR capture ticket (#87) for a connection-free fetch. Honor that caveat in
// the UI ("est." + connected-overnight note); this file is the pure math only.
//
// This computes the temperature INPUT (nightly mean, baseline, signed offset, raw anomaly
// flags). Fever (HR+temp cross-reference) is owned by the vitals-anomaly ticket (#72) and
// notifications by #85 — NOT here.

import Foundation

public enum SkinTempBaseline {

    /// Trailing nights the rolling baseline averages over (the app uses 30).
    public static let baselineWindowNights = 30
    /// Minimum prior nights before a baseline is considered meaningful. Below this the
    /// average swings too much night-to-night to call a deviation, so callers should show
    /// the nightly value without an offset until enough history accrues.
    public static let minBaselineNights = 3
    /// Deviation within ±this many °C of the baseline is "normal" (APK: 1 °C). Beyond it is
    /// an abnormal rise/drop. A signed, symmetric band — never a hardcoded temperature.
    public static let normalDeviationC = 1.0
    /// Night-to-night change beyond ±this °C is a "fluctuation" rise/drop (the 0x10/0x11
    /// flags). Distinct from the baseline deviation (0x12/0x13). Heuristic — labeled as such.
    public static let fluctuationC = 0.6
    /// BASELINE GATE for the fluctuation flags: a night-over-night change only counts when
    /// tonight ALSO sits beyond ±this °C from the baseline, in the same direction. Without
    /// this, one artifact night (e.g. a 30 °C reading from holding something cold) alerts
    /// twice: correctly on the artifact night ("fell sharply"), then WRONGLY on the recovery
    /// night — which is at baseline, i.e. normal, yet reads "+4 °C vs last night". A sharp
    /// change that lands ON the baseline means the PREVIOUS night was the outlier, and that
    /// night already got its alert. Half the fluctuation threshold: small enough to still
    /// catch a genuine rapid rise before it crosses the ±1 °C abnormal band, big enough to
    /// stay quiet on a plain return-to-normal.
    public static let fluctuationBaselineGateC = fluctuationC / 2

    /// One night's mean sleeping skin temperature, keyed by the night it belongs to.
    public struct NightlyTemp: Equatable, Sendable {
        public let night: Date     // start-of-day (or any stable per-night key)
        public let celsius: Double
        public init(night: Date, celsius: Double) {
            self.night = night
            self.celsius = celsius
        }
    }

    /// Minimum readings before a night's mean is comparable to another night's.
    ///
    /// ══ WHY COVERAGE, NOT JUST NON-EMPTINESS ══
    ///
    /// 🟢 A tester reported skin-temperature readings that "seem inconsistent" and wondered whether
    /// switching which finger she wears the ring on was the cause (2026-08-12). Her own diagnostics
    /// bundle names a simpler first-order cause: skin temp rides the LIVE `0x10`/`0x87` descriptor,
    /// NOT the drainable `0x4c` history (see this file's header), so a night only has temperature
    /// for the stretches the ring stayed connected — and her log shows the link dropping repeatedly,
    /// with only 7 of 14 nights carrying any temperature at all.
    ///
    /// Sleeping skin temperature is not flat: it rises after sleep onset, peaks in the small hours
    /// and falls before waking. A mean over three samples near bedtime and a mean over forty spread
    /// across the night are therefore measuring DIFFERENT PARTS OF A CURVE, and differencing them
    /// against a baseline built the same inconsistent way manufactures night-to-night swings out of
    /// nothing but connection luck. The un-gated mean published both with equal confidence.
    ///
    /// A count floor alone, kept as a cheap first cut. ⚠️ IT IS NOT THE REAL GATE — see
    /// `minNightlyCoverage`. 🟢 MEASURED on a tester's own well-connected night (decoded from the
    /// 0x10/0x87 descriptor frames in their diagnostics export): **533 worn readings across a
    /// 10 h window, 47–69 in every single hour** — about one per 68 s. So ten readings is roughly
    /// ELEVEN MINUTES of connected time, and a count floor set anywhere sane rejects only nights
    /// that barely connected at all. It does nothing about the case actually reported: a night
    /// connected for two hours of eight passes a count floor easily while still having measured
    /// only one corner of the curve.
    ///
    /// Finger-switching remains a real second-order effect (different tissue depth and perfusion),
    /// but it cannot be corrected for from the ring's data and it is not what makes a night with 3
    /// samples disagree with a night with 40.
    public static let minNightlySamples = 10

    /// The REAL gate: the fraction of the night's hour-long buckets that must contain at least one
    /// reading before the night's mean is comparable to another night's.
    ///
    /// Sleeping skin temperature follows a circadian curve — it rises after onset, peaks in the
    /// small hours, falls before waking — so a mean is only comparable to another mean if the two
    /// sampled the same SHAPE. Coverage measures that directly; a raw count cannot, because 100
    /// readings from one connected hour and 100 spread over ten hours are the same number and
    /// completely different measurements. Differencing an unevenly-sampled night against a baseline
    /// built the same uneven way manufactures night-to-night swings out of connection luck, which is
    /// exactly the "inconsistent readings" a tester reported (2026-08-12).
    ///
    /// ⚠️ SHIPPED AT `candidateNightlyCoverage` (0.6) — THE GATE IS ON since 2026-09-24, on the
    /// measurement this comment used to ask for. It previously shipped at 0 (off) with the machinery
    /// and tests intact, because 0.6 rested on only two data points — one real well-connected night
    /// (1.00) and one hypothetical two-hours-of-eight (0.25) — and sat in the gap between them.
    ///
    /// ══ THE MEASUREMENT ══
    ///
    /// Reproducible, not hand arithmetic: `desktop/skin_temp_coverage_measure.py` reimplements
    /// `coverage` byte-faithfully and prints everything below. Run it against a set of exports to
    /// re-derive or refute any figure here. Basis: **39 distinct nights carrying in-window
    /// temperature samples, across 6 rings** — Gen 2 FR02.018 (4 nights, 1 ring), Gen 2 Air FR04.009
    /// (13 nights, 2 rings), Gen 3 FR05.011 (1 night), and 21 nights on 3 rings whose exports predate
    /// schema 3 and so record no model or firmware. Rings are attributed from
    /// `historySyncEvidence[].ringID`, which the export's own schema note calls "the only per-capture
    /// ring attribution in this file" (`meta.ring*` names merely the last ring the app connected to);
    /// one of the unlabelled rings is the same `40CFFE2E` that a schema-3 export identifies as a Gen 2
    /// Air FR04.009. A further **5 nights held no in-window readings at all** and are excluded: they
    /// are `.notMeasured` before and after, so they cannot move the delta — 44 nights in total.
    ///
    /// ⚠️ This is a MULTI-TESTER, MULTI-GENERATION pool, not one person's clean history, and the Gen 2
    /// Air in it is the generation with the 16/32/88 % hole shares. Note what the per-ring
    /// decomposition shows: the ring contributing 3 of the 4 sub-gap nights ALSO contributes 4 clean
    /// published ones, so the failure mode is intermittent WITHIN a ring rather than a bad unit.
    ///
    /// The distribution is BIMODAL WITH AN EMPTY GAP:
    ///
    ///     0.1111  0.1429  0.1429  0.1538 │ ←—— gap ——→ │ 0.7500  0.8182  0.8462 … 1.0000
    ///     └──── 4 nights, n = 4…31 ──────┘             └──── 35 nights, n = 53…440 ─────┘
    ///
    /// NOTHING lands between 0.1538 and 0.7500. 0.6 is therefore not a tuned number — it is any
    /// point in an empty interval separating two populations that do not touch.
    ///
    /// ══ THE MARGINAL EFFECT: ONE NIGHT IN 39 (2.6 %) ══
    ///
    /// The honest cost of turning this on, and much smaller than the raw count of sub-gap nights
    /// suggests: of the 4 nights below 0.6, **3 were ALREADY withheld** by `minNightlySamples` (they
    /// hold 4, 6 and 7 readings). This gate newly withholds **exactly one** night in the whole pool.
    ///
    /// THAT NIGHT (Gen 3 tester, FR05.011, 2026-09-24): 31 samples over a 12 h 19 m staged window,
    /// 2 of its 13 hour buckets, coverage 0.1538. They arrive as two clusters — 24 readings inside one
    /// 28-minute stretch, whose second half is a 10.7-minute monotone decay from 34.50 °C to 28.55 °C
    /// (the ring coming off the finger), and 7 readings ten hours later in one 8-minute stretch at a
    /// flat 28.10–28.25 °C, after the wearer had got up but before `inBedEnd`. The unweighted mean
    /// published **31.39 °C**; that night's readings at or above 31 °C alone average 33.87 °C. The
    /// tester reported it as a Gen 3 calibration fault, comparing it against 34.75–34.81 °C on his
    /// own earlier Gen 2 nights (his figure, from data we do not hold — treat it as his report, not
    /// as a measurement of ours). It is not a calibration fault: a scaling error shifts every sample
    /// uniformly and cannot produce a decay curve.
    ///
    /// ══ THE TWO OBJECTIONS THAT HELD THIS AT 0, ANSWERED ══
    ///
    ///  1. CASCADE — ANSWERED, and it is the per-RING answer that matters. Withholding sets
    ///     `skinTempC = 0`, which every consumer reads as "no temperature this night"; below
    ///     `minBaselineNights` (3) surviving nights there is no baseline at all, #85 goes silent and
    ///     #183 loses `skinTempDeviation`. The baseline is read from ONE phone's store, so a pooled
    ///     total is the wrong unit. MEASURED PER RING, surviving nights before → after this change:
    ///     `9B3084A4` 17→17, `B40741BB` 9→9, `6F627CA0` 4→4, `40CFFE2E` 4→4 (of 7 nights; the other 3
    ///     were already withheld by the count floor), `4580443C` 1→1, and the Gen 3 `672AC297` 1→0.
    ///     **Every delta is 0 except the Gen 3 ring, which held a single night and was therefore
    ///     already below the floor of 3 before this change.** Two rings sit below the floor either
    ///     way, both because they hold 1 night. The gate creates no new cascade anywhere in the pool.
    ///  2. THE DENOMINATOR IS THE WRONG WINDOW — STILL TRUE, AND IT CANNOT BITE HERE. Coverage is
    ///     normalised over the STAGED in-bed span while temperature is only persisted inside the
    ///     RECORDING window, so a night far off the habitual schedule scores low however well it
    ///     connected (review once pinned a perfectly-connected night at 0.55). The distribution above
    ///     was measured with that SAME pessimistic denominator and still left the gap empty.
    ///     Normalising over the intersection can only SHRINK the denominator and RAISE coverage, so
    ///     it can only un-withhold a night, never withhold a new one. It remains the right
    ///     refinement; it is not a precondition, and no night in 39 lands near 0.6 from either side
    ///     (worst passing 0.7500).
    ///
    /// ⚠️ WHAT THIS DOES **NOT** FIX. Two limits, both real:
    ///   • A withheld night does not clear an ALREADY-STORED value on its own — see
    ///     `LocalStore.applyExtras`, which needs `SleepNightExtras.skinTempWithheld` to tell
    ///     "not computed" from "computed and rejected". The reporting tester's stored 31.39 °C
    ///     predates this change and will not self-correct.
    ///   • This gate governs the NIGHTLY MEAN only (one production call site,
    ///     `RingSession.computeSleepExtras`). It does nothing about per-sample HealthKit writes:
    ///     off-finger readings in the 28–31 °C band still reach `.bodyTemperature`, because that
    ///     path is gated by `ActivityPeriod.wornMinTemperatureC` (28 °C) instead. That half of the
    ///     blast radius is OPEN.
    public static let minNightlyCoverage = candidateNightlyCoverage

    /// The threshold itself, kept as its own name because the tests assert against it directly and
    /// because the value and the DECISION to ship it are separate facts. `minNightlyCoverage` is now
    /// this value; the two were distinct only while the gate was held off.
    public static let candidateNightlyCoverage = 0.6

    /// Fraction of `window`'s hour-long buckets holding ≥1 reading, in 0…1. Pure so the gate and any
    /// diagnostic can report the same number.
    public static func coverage(samples: [TemperatureSample], in window: DateInterval) -> Double {
        let buckets = max(1, Int((window.duration / 3600).rounded(.up)))
        let hit = Set(samples.filter { window.contains($0.time) }.map {
            Int($0.time.timeIntervalSince(window.start) / 3600)
        })
        return Double(hit.count) / Double(buckets)
    }

    /// Mean of skin-temperature readings inside a sleep window. Pass the night's persisted
    /// `.temperature` samples (already worn- and window-gated by RingSession). nil when there are
    /// too few readings to represent the night — a connection-free night has none until #87 lands,
    /// and a barely-connected one is no more comparable (see `minNightlySamples`).
    ///
    /// `minSamples` is the kill-switch: pass `1` for the old any-non-empty behaviour.
    public static func nightlyMean(_ celsius: [Double],
                                   minSamples: Int = minNightlySamples) -> Double? {
        guard celsius.count >= max(1, minSamples) else { return nil }
        return celsius.reduce(0, +) / Double(celsius.count)
    }

    /// Why a night's mean was or was not published — the distinction `nightlyMean`'s `nil` cannot
    /// carry, and which the store needs in order to obey "gates only move one way".
    ///
    /// `LocalStore.applyExtras` keeps a previously stored `skinTempC` whenever the new pass computes
    /// nothing, so that a quick daytime re-stage with no temperature coverage cannot wipe a good
    /// night. That rule is right for `.notMeasured` and WRONG for `.rejectedCoverage`: without this
    /// split, a night whose stored mean was computed from an unrepresentative window keeps it forever,
    /// because every later, better-judged pass returns the same `nil` as "we didn't look".
    public enum NightlyVerdict: Equatable, Sendable {
        /// Enough readings, spread widely enough to represent the night.
        case published(Double)
        /// Too few readings to judge the night at all (`minSamples`). The caller must PRESERVE any
        /// stored value — this is "we barely looked", not a verdict on the night.
        case notMeasured
        /// Enough readings to judge (≥ `minSamples`) but too clustered to represent the night
        /// (`minCoverage`). Carries the coverage that failed. The caller must CLEAR any stored value:
        /// this IS a verdict, and the stored number is the thing being rejected.
        case rejectedCoverage(Double)
    }

    /// Classify a night's temperature readings without publishing a number — see `NightlyVerdict`.
    /// `nightlyMean(samples:in:)` is this function keeping only the published case.
    public static func nightlyVerdict(samples: [TemperatureSample], in window: DateInterval,
                                     minSamples: Int = minNightlySamples,
                                     minCoverage: Double = minNightlyCoverage) -> NightlyVerdict {
        let inWindow = samples.filter { window.contains($0.time) }
        guard inWindow.count >= max(1, minSamples) else { return .notMeasured }
        let cov = coverage(samples: inWindow, in: window)
        guard minCoverage <= 0 || cov >= minCoverage else { return .rejectedCoverage(cov) }
        return .published(inWindow.map(\.celsius).reduce(0, +) / Double(inWindow.count))
    }

    /// The night's mean, scoped to `[start, end]` (inclusive) and gated on COVERAGE as well as
    /// count. THIS is the overload production should use — the array-only one above cannot see the
    /// timestamps and therefore cannot tell a well-sampled night from a well-connected hour.
    ///
    /// `minCoverage: 0` is the kill-switch for the coverage half of the gate.
    public static func nightlyMean(samples: [TemperatureSample], in window: DateInterval,
                                   minSamples: Int = minNightlySamples,
                                   minCoverage: Double = minNightlyCoverage) -> Double? {
        // One implementation: this is `nightlyVerdict` keeping only the published case. Both nil
        // cases collapse here — `notMeasured` and `rejectedCoverage` are indistinguishable through
        // a `Double?`, which is exactly why the store must call `nightlyVerdict` instead.
        guard case let .published(mean) = nightlyVerdict(samples: samples, in: window,
                                                        minSamples: minSamples,
                                                        minCoverage: minCoverage)
        else { return nil }
        return mean
    }

    /// Rolling baseline = mean of the most recent `windowNights` PRIOR nightly means. The
    /// caller passes prior nights only (exclude tonight); we additionally sort by night and
    /// take the trailing window so order/extra history can't skew it. nil below
    /// `minBaselineNights` (too little history to trust).
    public static func baseline(priorNights: [NightlyTemp],
                                windowNights: Int = baselineWindowNights,
                                minNights: Int = minBaselineNights) -> Double? {
        let trailing = priorNights.sorted { $0.night < $1.night }.suffix(windowNights)
        guard trailing.count >= minNights else { return nil }
        return trailing.map(\.celsius).reduce(0, +) / Double(trailing.count)
    }

    /// Signed nightly offset = tonight − baseline (°C). Positive = warmer than baseline.
    public static func offset(tonight: Double, baseline: Double) -> Double {
        tonight - baseline
    }

    /// Where tonight's deviation from the baseline falls. `.normal` within ±`normalC`,
    /// else a signed rise/drop. These map to the APK's `skinTempAbnormalRise` (0x12) /
    /// `skinTempAbnormalDrop` (0x13) flags — app-side only, NOT fever (0x14).
    public enum DeviationBand: String, Equatable, Sendable {
        case normal, abnormalRise, abnormalDrop
    }

    public static func deviationBand(offset: Double, normalC: Double = normalDeviationC) -> DeviationBand {
        if offset > normalC { return .abnormalRise }
        if offset < -normalC { return .abnormalDrop }
        return .normal
    }

    /// The five raw temperature anomaly flags the app exposes, computed app-side (#69 owns
    /// these; fever 0x14 does not live here). `abnormal*` compare tonight to the BASELINE;
    /// `fluctuation*` compare tonight to the PREVIOUS night, gated on the baseline
    /// (`fluctuationBaselineGateC`) so a recovery back TO baseline after an artifact night
    /// never reads as a "sharp rise". All are honest derivations of the decoded skin temp —
    /// none is fabricated.
    public struct AnomalyFlags: Equatable, Sendable {
        public var abnormalRise = false        // 0x12 — well above baseline
        public var abnormalDrop = false        // 0x13 — well below baseline
        public var fluctuationRise = false     // 0x10 — sharp jump vs last night
        public var fluctuationDrop = false     // 0x11 — sharp fall vs last night
        public init() {}
        public var any: Bool { abnormalRise || abnormalDrop || fluctuationRise || fluctuationDrop }
    }

    /// Classify tonight against an optional baseline and an optional previous night.
    ///
    /// The fluctuation flags are BASELINE-GATED: the night-over-night delta alone can't fire
    /// them unless tonight is also beyond ±`fluctuationBaselineGateC` from the baseline in the
    /// same direction. Rationale (user-reported): an 86 °F artifact night (cold object held
    /// while asleep) correctly alerted "fell sharply"; the NEXT night — dead on baseline —
    /// then wrongly alerted "rose sharply vs the previous night". Tonight-at-baseline means
    /// tonight is the normal one; the outlier already had its alert. With no baseline yet
    /// (< `minBaselineNights` of history) the previous night is the only reference we have,
    /// so the ungated comparison is kept.
    public static func anomalyFlags(tonight: Double,
                                    baseline: Double?,
                                    previousNight: Double?,
                                    normalC: Double = normalDeviationC,
                                    fluctC: Double = fluctuationC) -> AnomalyFlags {
        var flags = AnomalyFlags()
        if let base = baseline {
            switch deviationBand(offset: tonight - base, normalC: normalC) {
            case .abnormalRise: flags.abnormalRise = true
            case .abnormalDrop: flags.abnormalDrop = true
            case .normal: break
            }
        }
        if let prev = previousNight {
            let d = tonight - prev
            let offsetFromBase = baseline.map { tonight - $0 }
            if d > fluctC, offsetFromBase.map({ $0 > fluctuationBaselineGateC }) ?? true {
                flags.fluctuationRise = true
            } else if d < -fluctC, offsetFromBase.map({ $0 < -fluctuationBaselineGateC }) ?? true {
                flags.fluctuationDrop = true
            }
        }
        return flags
    }

    /// Everything the UI needs for one night in a single value: nightly mean, baseline (if
    /// enough history), signed offset, band, and the raw anomaly flags.
    public struct NightReport: Equatable, Sendable {
        public let nightlyC: Double
        public let baselineC: Double?
        public let offsetC: Double?
        public let band: DeviationBand?
        public let flags: AnomalyFlags
    }

    /// Build a `NightReport` from tonight's mean and the prior nights' means.
    public static func report(tonight: Double,
                              priorNights: [NightlyTemp],
                              previousNight: Double? = nil,
                              windowNights: Int = baselineWindowNights,
                              normalC: Double = normalDeviationC,
                              fluctC: Double = fluctuationC) -> NightReport {
        let base = baseline(priorNights: priorNights, windowNights: windowNights)
        let off = base.map { offset(tonight: tonight, baseline: $0) }
        let band = off.map { deviationBand(offset: $0, normalC: normalC) }
        let flags = anomalyFlags(tonight: tonight, baseline: base,
                                 previousNight: previousNight, normalC: normalC, fluctC: fluctC)
        return NightReport(nightlyC: tonight, baselineC: base, offsetC: off, band: band, flags: flags)
    }
}
