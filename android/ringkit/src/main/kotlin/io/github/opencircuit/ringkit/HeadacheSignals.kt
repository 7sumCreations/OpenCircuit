package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/HeadacheSignals.swift (@ b1c2fdd),
// whole.
//
// Headache signals — a per-user "how unusual was last night, for you?" index.
//
// READ THIS BEFORE CHANGING A CONSTANT (upstream's header, kept in substance):
//  1. THIS IS AN ESTIMATE, AND A WEAK ONE. The published ceiling for physiology-only headache
//     forecasting is AUC ≈ 0.62–0.68; flagging the top 10 % of a user's own days, about three in four
//     flagged days will not become a headache and about three in four headaches will not be flagged.
//     Nothing here may be presented as a probability, a percentage chance or a risk. The index is a
//     RELATIVE position on one person's own scale and means nothing across people.
//  2. IT SCORES HOW UNUSUAL A NIGHT WAS, IN EITHER DIRECTION — NOT HOW BAD. Every feature except
//     `arousalLetdown` and `perimenstrual` contributes UNSIGNED |z|, because pre-attack signal
//     directions invert between people. An unusually restorative night scores like a bad one.
//  3. It cannot tell a hangover, a late night or a hard training day from a prodrome.
//  4. Not a medical device. Not a diagnosis.
//
// Port notes:
//  • Every type is immutable: upstream's structs with `var` fields (`Tuning`, `Series`, `DayInput`)
//    change through `copy`; lists are copied in and read-only out; doubles compare by IEEE `==`, as
//    Swift's synthesized `Equatable` does. `Verdict` is a sealed type.
//  • An unreadable (NaN or infinite) reading for today is a MISSING reading: a series' today, the
//    skin-temperature offset and the let-down's two day-HR values are treated as absent before
//    `RobustBaseline.z` is asked. Upstream reads them as normal (z 0), or traps on a NaN offset.
//  • Where upstream traps on a hostile tuning or a direct call, the port bounds at the edge: a
//    non-finite index gives no score, a percentile's fraction is clamped to 0…1 (NaN → a threshold
//    no index reaches), a negative band window and a negative cap-pass count take nothing. The cap
//    loop stops at the first pass that changes nothing — the answer every pass count gives upstream.
//  • Swift's `min` / `max` / `rounded()` semantics (`swiftMin`, `swiftMax`, `roundHalfAwayFromZero`);
//    elapsed time as `Double` seconds through `secondsBetween`, as upstream's `timeIntervalSince`.

import java.time.Instant
import java.util.Collections
import java.util.EnumMap
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.floor

/** A per-user "how unusual was last night, for you?" index. Pure, no clock, no zone, no locale. */
object HeadacheSignals {

    // Features

    /**
     * The nine features, in upstream's declaration order. [noiseFloor] is the absolute deviation
     * below which the feature contributes exactly 0 regardless of z (a very tight baseline is not
     * flagged on a 1-LSB wobble); [weight] is its share of the renormalised pool — the eight
     * ring-derived features sum to exactly 1.00, `perimenstrual` is additive and ring-fenced.
     */
    enum class Feature(val rawValue: String, val noiseFloor: Double, val weight: Double) {
        /** 🟢 %-pt floor; the only sleep term with day-1 support (Bertisch OR 1.39). */
        SLEEP_EFFICIENCY_DROP("sleepEfficiencyDrop", 5.0, 0.18),

        /**
         * 🟢 Lipton 2014: a stress DECLINE. Floor deliberately 0: this feature's value is a DIFFERENCE
         * OF TWO z-SCORES; both day-HR values are floored at the resting-HR floor (5 bpm) and the
         * difference is gated by `Tuning.onsetZ`.
         */
        AROUSAL_LETDOWN("arousalLetdown", 0.0, 0.18),

        /** 🟡 floor `VitalsBaseline` minDeltaHRV (8 ms). */
        HRV_DEVIATION("hrvDeviation", 8.0, 0.14),

        /** 🟡 floor `VitalsBaseline` minDeltaRestingHR (5 bpm); 🟢 weight. */
        RESTING_HR_DEVIATION("restingHRDeviation", 5.0, 0.14),

        /** 🔴 PROVISIONAL floor, minutes; 🟢 weight demoted (Bertisch's day-0 association ran the wrong way). */
        SLEEP_FRAGMENTATION("sleepFragmentation", 15.0, 0.10),

        /** 🔴 PROVISIONAL floor, minutes; 🟢 weight demoted ("not temporally associated"). */
        SLEEP_DURATION_DEVIATION("sleepDurationDeviation", 30.0, 0.10),

        /** 🟡 floor: half the 60-min SD that `TrendsEngine.sleepRegularity` maps to 0; 🔴 PROVISIONAL weight. */
        SCHEDULE_SHIFT("scheduleShift", 30.0, 0.08),

        /** 🟡 floor `SkinTempBaseline` fluctuation gate (0.3 °C); 🟢 weight kept low. */
        SKIN_TEMP_DEVIATION("skinTempDeviation", 0.3, 0.08),

        /** Binary (floor 0); 🟢 MacGregor perimenstrual estrogen withdrawal. */
        PERIMENSTRUAL("perimenstrual", 0.0, 0.20),
        ;

        /**
         * Whether the feature comes from the RING. `perimenstrual` is a calendar lookup and never
         * counts toward the minimum that decides whether enough was measured to score at all.
         */
        val isRingDerived: Boolean get() = this != PERIMENSTRUAL

        companion object {
            /**
             * Features that can ANCHOR a verdict. A day made only of cycle phase, schedule and skin
             * temperature has measured nothing about how the person slept or how their autonomic
             * state moved. Read-only.
             */
            val ANCHORS: Set<Feature> = Collections.unmodifiableSet(
                EnumSet.of(SLEEP_EFFICIENCY_DROP, SLEEP_FRAGMENTATION, SLEEP_DURATION_DEVIATION, HRV_DEVIATION, RESTING_HR_DEVIATION),
            )
        }
    }

    /** Why a feature is absent. */
    enum class AbsentReason(val rawValue: String) {
        NO_BASELINE("noBaseline"),
        NO_DATA_THIS_DAY("noDataThisDay"),
        FEATURE_DISABLED("featureDisabled"),
        LOW_COVERAGE("lowCoverage"),
        NOT_APPLICABLE("notApplicable"),
    }

    /** The band, ordered typical < elevated < flagged (declaration order is the raw-value order). */
    enum class Band(val rawValue: Int) {
        TYPICAL(0),
        ELEVATED(1),
        FLAGGED(2),
    }

    /** Why the notification candidate is withheld (the score is still computed and shown). */
    enum class Suppression(val rawValue: String) {
        FEVER("fever"),
        HEADACHE_ALREADY_LOGGED("headacheAlreadyLogged"),
    }

    // Tuning

    /**
     * Every constant, defaulted: `Tuning()` is exactly the shipped behaviour, so a test can vary one
     * knob without re-specifying the world. Immutable (upstream's `var`s change through `copy`).
     */
    data class Tuning(
        /** Below this |z| a feature contributes exactly 0. 🔴 PROVISIONAL. */
        val onsetZ: Double = 1.0,
        /**
         * 🟡 Numerically equal to `VitalsBaseline`'s significant z, but COPIED AS A LITERAL on purpose:
         * frozen rows are never recomputed, so a live read would mix two scales in one evaluation.
         */
        val saturationZ: Double = 2.5,
        /** Skin temp ramps in °C, not z (🟡 the vitals engine's minor / significant temperature steps). */
        val tempOnsetC: Double = 0.5,
        val tempSaturationC: Double = 1.0,
        /** Minimum RING-derived features present before a score exists at all. 🔴 PROVISIONAL. */
        val minRingFeaturesForScore: Int = 4,
        /** No single feature may exceed this share of the renormalised pool. 🔴 PROVISIONAL. */
        val maxSingleFeatureShare: Double = 0.35,
        /** Iterative cap passes. Three is enough for nine features and bounds the loop. */
        val maxCapPasses: Int = 3,
        /** A ring-buffer-truncated night halves the three sleep features' weight. 🔴 PROVISIONAL. */
        val truncatedSleepQuality: Double = 0.5,
        /** Banding percentiles over the user's OWN trailing frozen indices — an alert budget. 🔴 PROVISIONAL. */
        val elevatedPercentile: Double = 0.75,
        val flaggedPercentile: Double = 0.90,
        /** Below this many prior frozen days there is no band, ever. 🔴 PROVISIONAL. */
        val minDaysForBanding: Int = 21,
        /** Trailing window (entries) the percentiles are taken over. 🔴 PROVISIONAL. */
        val bandWindowDays: Int = 60,
        /** `.flagged` additionally requires this many features each contributing ≥ [contributingThreshold]. */
        val minContributingFeatures: Int = 3,
        val contributingThreshold: Double = 0.5,
        /** Ring silence that flips the card to `.interrupted`. 🟢 RingConn's own 24 h. */
        val dataGapHours: Double = 24.0,
    ) {
        private fun doubles(): List<Double> = listOf(
            onsetZ, saturationZ, tempOnsetC, tempSaturationC, maxSingleFeatureShare, truncatedSleepQuality,
            elevatedPercentile, flaggedPercentile, contributingThreshold, dataGapHours,
        )

        private fun ints(): List<Int> = listOf(minRingFeaturesForScore, maxCapPasses, minDaysForBanding, bandWindowDays, minContributingFeatures)

        override fun equals(other: Any?): Boolean = other is Tuning && ints() == other.ints() && ieeeListEquals(doubles(), other.doubles())

        override fun hashCode(): Int = ints().hashCode() * 31 + doubles().map(::ieeeHash).hashCode()
    }

    // Input

    /** One feature's today-value plus the trailing prior series it is scored against ([prior] excludes today). */
    class Series(val today: Double, prior: List<Double>) {
        /** Oldest → newest; copied in, read-only out. */
        val prior: List<Double> = readOnly(prior)

        fun copy(today: Double = this.today, prior: List<Double> = this.prior): Series = Series(today, prior)

        override fun equals(other: Any?): Boolean = other is Series && today == other.today && ieeeListEquals(prior, other.prior)

        override fun hashCode(): Int = 31 * ieeeHash(today) + prior.map(::ieeeHash).hashCode()

        override fun toString(): String = "Series(today=$today, prior=$prior)"
    }

    /**
     * Everything one day's assessment needs, as plain values. [now] is required (upstream takes it
     * too; nothing here reads a clock). [lastRingDataAt] null = the ring never delivered anything.
     * [skinTempOffsetC] is the CANONICAL offset from `SkinTempBaseline`, never re-derived here.
     * Bedtimes are minutes since midnight (wrap-aware). [isPerimenstrual] null = cycle tracking off or
     * too little logged. [priorIndices] are the trailing FROZEN indices, oldest → newest, excluding today.
     */
    class DayInput(
        val day: Instant,
        val now: Instant,
        val lastRingDataAt: Instant? = null,
        val restingHR: Series? = null,
        val hrvSDNN: Series? = null,
        val sleepEfficiencyPct: Series? = null,
        val sleepFragmentationMin: Series? = null,
        val sleepDurationMin: Series? = null,
        val skinTempOffsetC: Double? = null,
        val inBedStartMinutes: Int? = null,
        priorInBedStartMinutes: List<Int> = emptyList(),
        val dayHRPrevious: Double? = null,
        val dayHRTwoDaysAgo: Double? = null,
        dayHRPrior: List<Double> = emptyList(),
        val isPerimenstrual: Boolean? = null,
        val sleepLikelyTruncated: Boolean = false,
        val feverSuspected: Boolean = false,
        val headacheAlreadyLoggedToday: Boolean = false,
        priorIndices: List<Int> = emptyList(),
    ) {
        val priorInBedStartMinutes: List<Int> = readOnly(priorInBedStartMinutes)
        val dayHRPrior: List<Double> = readOnly(dayHRPrior)
        val priorIndices: List<Int> = readOnly(priorIndices)

        fun copy(
            day: Instant = this.day,
            now: Instant = this.now,
            lastRingDataAt: Instant? = this.lastRingDataAt,
            restingHR: Series? = this.restingHR,
            hrvSDNN: Series? = this.hrvSDNN,
            sleepEfficiencyPct: Series? = this.sleepEfficiencyPct,
            sleepFragmentationMin: Series? = this.sleepFragmentationMin,
            sleepDurationMin: Series? = this.sleepDurationMin,
            skinTempOffsetC: Double? = this.skinTempOffsetC,
            inBedStartMinutes: Int? = this.inBedStartMinutes,
            priorInBedStartMinutes: List<Int> = this.priorInBedStartMinutes,
            dayHRPrevious: Double? = this.dayHRPrevious,
            dayHRTwoDaysAgo: Double? = this.dayHRTwoDaysAgo,
            dayHRPrior: List<Double> = this.dayHRPrior,
            isPerimenstrual: Boolean? = this.isPerimenstrual,
            sleepLikelyTruncated: Boolean = this.sleepLikelyTruncated,
            feverSuspected: Boolean = this.feverSuspected,
            headacheAlreadyLoggedToday: Boolean = this.headacheAlreadyLoggedToday,
            priorIndices: List<Int> = this.priorIndices,
        ): DayInput = DayInput(
            day, now, lastRingDataAt, restingHR, hrvSDNN, sleepEfficiencyPct, sleepFragmentationMin, sleepDurationMin,
            skinTempOffsetC, inBedStartMinutes, priorInBedStartMinutes, dayHRPrevious, dayHRTwoDaysAgo, dayHRPrior,
            isPerimenstrual, sleepLikelyTruncated, feverSuspected, headacheAlreadyLoggedToday, priorIndices,
        )

        private fun exact(): List<Any?> = listOf(
            day, now, lastRingDataAt, restingHR, hrvSDNN, sleepEfficiencyPct, sleepFragmentationMin, sleepDurationMin,
            inBedStartMinutes, priorInBedStartMinutes, isPerimenstrual, sleepLikelyTruncated, feverSuspected,
            headacheAlreadyLoggedToday, priorIndices,
        )

        override fun equals(other: Any?): Boolean =
            other is DayInput && exact() == other.exact() && ieeeEquals(skinTempOffsetC, other.skinTempOffsetC) &&
                ieeeEquals(dayHRPrevious, other.dayHRPrevious) && ieeeEquals(dayHRTwoDaysAgo, other.dayHRTwoDaysAgo) &&
                ieeeListEquals(dayHRPrior, other.dayHRPrior)

        override fun hashCode(): Int =
            exact().hashCode() * 31 + listOf(skinTempOffsetC, dayHRPrevious, dayHRTwoDaysAgo).map { it?.let(::ieeeHash) }.hashCode() +
                dayHRPrior.map(::ieeeHash).hashCode()

        override fun toString(): String =
            "DayInput(day=$day, now=$now, lastRingDataAt=$lastRingDataAt, restingHR=$restingHR, hrvSDNN=$hrvSDNN, " +
                "sleepEfficiencyPct=$sleepEfficiencyPct, sleepFragmentationMin=$sleepFragmentationMin, sleepDurationMin=$sleepDurationMin, " +
                "skinTempOffsetC=$skinTempOffsetC, inBedStartMinutes=$inBedStartMinutes, priorInBedStartMinutes=$priorInBedStartMinutes, " +
                "dayHRPrevious=$dayHRPrevious, dayHRTwoDaysAgo=$dayHRTwoDaysAgo, dayHRPrior=$dayHRPrior, isPerimenstrual=$isPerimenstrual, " +
                "sleepLikelyTruncated=$sleepLikelyTruncated, feverSuspected=$feverSuspected, " +
                "headacheAlreadyLoggedToday=$headacheAlreadyLoggedToday, priorIndices=$priorIndices)"
    }

    // Output

    /**
     * One feature's part in a day. [z] and [contribution] are null when the feature is absent — NEVER
     * 0: a missing input is not a normal reading. [contribution] is the 0…1 ramp position;
     * [effectiveWeight] the weight used after quality multipliers and the single-feature cap.
     */
    data class Contribution(
        val feature: Feature,
        val z: Double?,
        val contribution: Double?,
        val effectiveWeight: Double,
        val absentReason: AbsentReason?,
    ) {
        val isPresent: Boolean get() = contribution != null

        override fun equals(other: Any?): Boolean =
            other is Contribution && feature == other.feature && ieeeEquals(z, other.z) &&
                ieeeEquals(contribution, other.contribution) && effectiveWeight == other.effectiveWeight &&
                absentReason == other.absentReason

        override fun hashCode(): Int =
            listOf(feature, z?.let(::ieeeHash), contribution?.let(::ieeeHash), ieeeHash(effectiveWeight), absentReason).hashCode()
    }

    /**
     * A scored day. [index] is 0…100, a RELATIVE index on this user's own scale — not a probability,
     * not comparable between people. [coverageFraction] is the present ring weight out of 1.00.
     */
    class Assessment(
        val day: Instant,
        val index: Int,
        val band: Band,
        contributions: List<Contribution>,
        val ringFeatureCount: Int,
        val coverageFraction: Double,
        val suppressedBy: Suppression?,
    ) {
        /** In upstream's order; copied in, read-only out. */
        val contributions: List<Contribution> = readOnly(contributions)

        override fun equals(other: Any?): Boolean =
            other is Assessment && day == other.day && index == other.index && band == other.band &&
                contributions == other.contributions && ringFeatureCount == other.ringFeatureCount &&
                coverageFraction == other.coverageFraction && suppressedBy == other.suppressedBy

        override fun hashCode(): Int =
            listOf(day, index, band, contributions, ringFeatureCount, ieeeHash(coverageFraction), suppressedBy).hashCode()

        override fun toString(): String =
            "Assessment(day=$day, index=$index, band=$band, contributions=$contributions, ringFeatureCount=$ringFeatureCount, " +
                "coverageFraction=$coverageFraction, suppressedBy=$suppressedBy)"
    }

    /** The day's verdict. */
    sealed interface Verdict {
        data object NotEnabled : Verdict

        data class BuildingBaseline(val daysRemaining: Int) : Verdict

        /** The ring has been silent for the data gap; [since] is when it last delivered (null = never). */
        data class Interrupted(val since: Instant?) : Verdict

        /** Too little measured to score; [missing] names each absent feature's reason (read-only). */
        class InsufficientData(missing: Map<Feature, AbsentReason>) : Verdict {
            val missing: Map<Feature, AbsentReason> =
                Collections.unmodifiableMap(EnumMap<Feature, AbsentReason>(Feature::class.java).apply { putAll(missing) })

            override fun equals(other: Any?): Boolean = other is InsufficientData && missing == other.missing

            override fun hashCode(): Int = missing.hashCode()

            override fun toString(): String = "InsufficientData(missing=$missing)"
        }

        data class Scored(val assessment: Assessment) : Verdict
    }

    // Assessment

    /** Assess one day. Gates run in order and each returns early (upstream's design doc §3.9). */
    fun assess(input: DayInput, tuning: Tuning = Tuning()): Verdict {
        // GATE 1 — ring silence: say "we stopped looking because you took the ring off" rather than
        // emitting a normal-looking day built from nothing.
        val last = input.lastRingDataAt ?: return Verdict.Interrupted(since = null)
        if (secondsBetween(last, input.now) >= tuning.dataGapHours * 3600) return Verdict.Interrupted(since = last)

        val contributions = ArrayList<Contribution>(Feature.entries.size)
        val absent = EnumMap<Feature, AbsentReason>(Feature::class.java)

        fun add(feature: Feature, z: Double?, reason: AbsentReason? = null, quality: Double = 1.0) {
            if (z != null) {
                val ramp = rampContribution(feature, z, tuning)
                contributions += Contribution(feature, z, ramp, feature.weight * quality, null)
            } else {
                val r = reason ?: AbsentReason.NO_DATA_THIS_DAY
                absent[feature] = r
                contributions += Contribution(feature, null, null, 0.0, r)
            }
        }

        // Unsigned |z| features driven by a trailing series. An unreadable today is a missing today:
        // it never reaches RobustBaseline.z, which would read it as an ordinary 0.
        fun seriesZ(series: Series?, feature: Feature): Pair<Double?, AbsentReason?> {
            if (series == null || !series.today.isFinite()) return null to AbsentReason.NO_DATA_THIS_DAY
            val stats = RobustBaseline.stats(series.prior) ?: return null to AbsentReason.NO_BASELINE
            return abs(RobustBaseline.z(today = series.today, stats = stats, noiseFloor = feature.noiseFloor)) to null
        }

        // The quality multiplier applies to all three sleep features, efficiency included (the
        // largest sleep weight, and the one an unmeasured hole distorts most).
        val sleepQuality = if (input.sleepLikelyTruncated) tuning.truncatedSleepQuality else 1.0

        val (effZ, effReason) = seriesZ(input.sleepEfficiencyPct, Feature.SLEEP_EFFICIENCY_DROP)
        add(Feature.SLEEP_EFFICIENCY_DROP, effZ, effReason, sleepQuality)

        val (hrvZ, hrvReason) = seriesZ(input.hrvSDNN, Feature.HRV_DEVIATION)
        add(Feature.HRV_DEVIATION, hrvZ, hrvReason)

        val (rhrZ, rhrReason) = seriesZ(input.restingHR, Feature.RESTING_HR_DEVIATION)
        add(Feature.RESTING_HR_DEVIATION, rhrZ, rhrReason)

        val (fragZ, fragReason) = seriesZ(input.sleepFragmentationMin, Feature.SLEEP_FRAGMENTATION)
        add(Feature.SLEEP_FRAGMENTATION, fragZ, fragReason, sleepQuality)

        val (durZ, durReason) = seriesZ(input.sleepDurationMin, Feature.SLEEP_DURATION_DEVIATION)
        add(Feature.SLEEP_DURATION_DEVIATION, durZ, durReason, sleepQuality)

        // Skin temp: ramps in °C off the CANONICAL offset, not a re-derived z. An unreadable offset
        // is a missing offset.
        val offset = input.skinTempOffsetC?.takeIf { it.isFinite() }
        if (offset != null) {
            val ramp = clamp01((abs(offset) - tuning.tempOnsetC) / swiftMax(tuning.tempSaturationC - tuning.tempOnsetC, java.lang.Double.MIN_NORMAL))
            contributions += Contribution(Feature.SKIN_TEMP_DEVIATION, offset, ramp, Feature.SKIN_TEMP_DEVIATION.weight, null)
        } else {
            absent[Feature.SKIN_TEMP_DEVIATION] = AbsentReason.NO_DATA_THIS_DAY
            contributions += Contribution(Feature.SKIN_TEMP_DEVIATION, null, null, 0.0, AbsentReason.NO_DATA_THIS_DAY)
        }

        // Schedule shift: circular, so a 23:50-vs-00:10 sleeper is regular, not maximally irregular.
        val todayStart = input.inBedStartMinutes
        val habitual = if (todayStart != null && input.priorInBedStartMinutes.size >= RobustBaseline.MIN_BASELINE_DAYS) {
            RobustBaseline.circularMedianMinutes(input.priorInBedStartMinutes)
        } else {
            null
        }
        if (todayStart != null && habitual != null) {
            val deltaMin = RobustBaseline.circularDeltaMinutes(todayStart, habitual).toDouble()
            add(Feature.SCHEDULE_SHIFT, deltaMin / swiftMax(Feature.SCHEDULE_SHIFT.noiseFloor, java.lang.Double.MIN_NORMAL))
        } else {
            add(Feature.SCHEDULE_SHIFT, null, if (todayStart == null) AbsentReason.NO_DATA_THIS_DAY else AbsentReason.NO_BASELINE)
        }

        // Let-down: SIGNED. A FALL in arousal from D−2 to D−1 is the risk direction, so a RISE
        // contributes nothing. An unreadable day is a missing day.
        val prev = input.dayHRPrevious?.takeIf { it.isFinite() }
        val prev2 = input.dayHRTwoDaysAgo?.takeIf { it.isFinite() }
        val dayStats = RobustBaseline.stats(input.dayHRPrior)
        if (prev != null && prev2 != null && dayStats != null) {
            val floor = Feature.RESTING_HR_DEVIATION.noiseFloor
            val z1 = RobustBaseline.z(today = prev, stats = dayStats, noiseFloor = floor)
            val z2 = RobustBaseline.z(today = prev2, stats = dayStats, noiseFloor = floor)
            add(Feature.AROUSAL_LETDOWN, swiftMax(0.0, z2 - z1)) // positive = arousal fell
        } else {
            // Name the real cause: a short prior series is a BASELINE problem, not a data problem.
            add(Feature.AROUSAL_LETDOWN, null, if (dayStats != null) AbsentReason.NO_DATA_THIS_DAY else AbsentReason.NO_BASELINE)
        }

        // Cycle phase: binary, and ring-fenced by both guards below.
        val peri = input.isPerimenstrual
        if (peri != null) {
            val v = if (peri) 1.0 else 0.0
            contributions += Contribution(Feature.PERIMENSTRUAL, v, v, Feature.PERIMENSTRUAL.weight, null)
        } else {
            contributions += Contribution(Feature.PERIMENSTRUAL, null, null, 0.0, AbsentReason.NOT_APPLICABLE)
        }

        // GATE 2 — cold start: would the MISSING BASELINES alone have carried us to the minimum?
        val ringPresent = contributions.count { it.isPresent && it.feature.isRingDerived }
        val noBaselineRing = absent.count { (f, r) -> f.isRingDerived && r == AbsentReason.NO_BASELINE }
        if (ringPresent < tuning.minRingFeaturesForScore && noBaselineRing.toLong() >= tuning.minRingFeaturesForScore.toLong() - ringPresent) {
            return Verdict.BuildingBaseline(daysRemaining = maxOf(0, RobustBaseline.MIN_BASELINE_DAYS - maxPriorDays(input)))
        }

        // GATE 3 — coverage. `perimenstrual` is EXCLUDED: a calendar lookup is not a measurement.
        if (ringPresent < tuning.minRingFeaturesForScore) return Verdict.InsufficientData(absent)

        // GATE 4 — anchor. Context alone (cycle + schedule + temperature) never synthesises a verdict.
        if (contributions.none { it.isPresent && it.feature.isRingDerived && it.feature in Feature.ANCHORS }) {
            return Verdict.InsufficientData(absent)
        }

        // Weight capping, then renormalisation over PRESENT features only.
        val capped = applySingleFeatureCap(contributions, tuning)
        val totalWeight = capped.fold(0.0) { acc, c -> acc + c.effectiveWeight }
        if (!(totalWeight > 0)) return Verdict.InsufficientData(absent)
        val weighted = capped.fold(0.0) { acc, c -> acc + c.effectiveWeight * (c.contribution ?: 0.0) }
        val rawIndex = 100 * weighted / totalWeight
        // A weighted index that is not a number Int can hold (only a hostile tuning reaches this) is
        // no score; upstream traps converting a non-finite one.
        if (!(abs(rawIndex) < 2_147_483_647.0)) return Verdict.InsufficientData(absent)
        val index = roundHalfAwayFromZero(rawIndex).toInt()

        val ringWeightPresent = capped.filter { it.isPresent && it.feature.isRingDerived }.fold(0.0) { acc, c -> acc + c.feature.weight }
        val band = band(index = index, priorIndices = input.priorIndices, contributions = capped, tuning = tuning)

        // GATE 5/6 — suppression: the score is still SHOWN; only the notification candidate is
        // withheld. Fever wins (HRV↓ + RHR↑ + temp↑ IS the fever signature).
        val suppression = when {
            input.feverSuspected -> Suppression.FEVER
            input.headacheAlreadyLoggedToday -> Suppression.HEADACHE_ALREADY_LOGGED
            else -> null
        }

        return Verdict.Scored(
            Assessment(
                day = input.day, index = index, band = band, contributions = capped, ringFeatureCount = ringPresent,
                coverageFraction = swiftMin(1.0, ringWeightPresent), suppressedBy = suppression,
            ),
        )
    }

    /**
     * Band today's index against the user's OWN trailing frozen indices — a false-alarm BUDGET, not
     * an accuracy threshold. An index of 0, or a day on which nothing contributed, is never above
     * typical (the floor lives here so the FROZEN band is right too). The top band additionally needs
     * [Tuning.minContributingFeatures] features contributing — it can never be reached by
     * thresholding one input. A negative band window takes no days.
     */
    fun band(index: Int, priorIndices: List<Int>, contributions: List<Contribution> = emptyList(), tuning: Tuning = Tuning()): Band {
        val windowDays = maxOf(tuning.bandWindowDays, 0)
        val window = if (priorIndices.size > windowDays) priorIndices.takeLast(windowDays) else priorIndices
        if (window.size < tuning.minDaysForBanding) return Band.TYPICAL

        if (index <= 0) return Band.TYPICAL
        if (contributions.isNotEmpty() && contributions.none { (it.contribution ?: 0.0) > 0 }) return Band.TYPICAL
        val sorted = window.map { it.toDouble() }.sorted() // whole numbers: every sort agrees
        val value = index.toDouble()
        if (value >= percentile(sorted, tuning.flaggedPercentile)) {
            val contributing = contributions.count { (it.contribution ?: 0.0) >= tuning.contributingThreshold }
            return if (contributions.isEmpty() || contributing >= tuning.minContributingFeatures) Band.FLAGGED else Band.ELEVATED
        }
        if (value >= percentile(sorted, tuning.elevatedPercentile)) return Band.ELEVATED
        return Band.TYPICAL
    }

    // Internals

    /**
     * How many prior nights are held, for the cold-start "n more nights" message: the LONGEST series
     * wins (reporting the shortest would pin the count at 0 forever for a user with patchy skin-temp
     * capture). Not derivable from `priorIndices`, which only start once a day scores.
     */
    internal fun maxPriorDays(input: DayInput): Int = maxOf(
        maxOf(input.restingHR?.prior?.size ?: 0, input.hrvSDNN?.prior?.size ?: 0, input.sleepEfficiencyPct?.prior?.size ?: 0),
        maxOf(input.sleepFragmentationMin?.prior?.size ?: 0, input.sleepDurationMin?.prior?.size ?: 0, input.priorInBedStartMinutes.size),
    )

    /** The 0…1 ramp from [Tuning.onsetZ] to [Tuning.saturationZ] of |[z]| (upstream's signature keeps [feature]). */
    internal fun rampContribution(@Suppress("UNUSED_PARAMETER") feature: Feature, z: Double, tuning: Tuning): Double {
        val span = swiftMax(tuning.saturationZ - tuning.onsetZ, java.lang.Double.MIN_NORMAL)
        return clamp01((abs(z) - tuning.onsetZ) / span)
    }

    /**
     * No single feature may exceed [Tuning.maxSingleFeatureShare] of the renormalised pool. Iterative,
     * because scaling one weight down changes every other share. A negative pass count caps nothing.
     * The loop stops at the first pass that changes nothing: every later pass would repeat it, so the
     * answer is the one any pass count gives (a fixed point can sit a rounding above the share, where
     * repeating the no-op pass would only spend the count).
     */
    internal fun applySingleFeatureCap(contributions: List<Contribution>, tuning: Tuning): List<Contribution> {
        val out = ArrayList(contributions)
        var pass = 0
        while (pass < tuning.maxCapPasses && out.isNotEmpty()) {
            pass++
            val total = out.fold(0.0) { acc, c -> acc + c.effectiveWeight }
            if (!(total > 0)) break
            // Swift's `indices.max(by: <)`: the first index no later one exceeds.
            var idx = 0
            for (k in 1 until out.size) if (out[idx].effectiveWeight < out[k].effectiveWeight) idx = k
            if (!(out[idx].effectiveWeight / total > tuning.maxSingleFeatureShare)) break
            // Solve w' / (total - w + w') = share  ⇒  w' = share·(total - w) / (1 - share)
            val others = total - out[idx].effectiveWeight
            val share = tuning.maxSingleFeatureShare
            val capped = share * others / swiftMax(1 - share, java.lang.Double.MIN_NORMAL)
            val c = out[idx]
            out[idx] = c.copy(effectiveWeight = capped)
            if (capped.toRawBits() == c.effectiveWeight.toRawBits()) break
        }
        return readOnly(out)
    }

    /**
     * Linear-interpolated percentile over a SORTED list: +∞ for an empty list, the value for one.
     * A fraction outside 0…1 is clamped into it, and a NaN fraction is a NaN threshold that no index
     * reaches (upstream traps on all three).
     */
    internal fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.POSITIVE_INFINITY
        if (sorted.size == 1) return sorted[0]
        if (p.isNaN()) return Double.NaN
        val rank = swiftMin(swiftMax(p, 0.0), 1.0) * (sorted.size - 1)
        val lo = floor(rank).toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo])
    }

    internal fun clamp01(x: Double): Double = swiftMin(swiftMax(x, 0.0), 1.0)

    private fun <T> readOnly(xs: List<T>): List<T> = Collections.unmodifiableList(ArrayList(xs))

    private fun ieeeListEquals(a: List<Double>, b: List<Double>): Boolean = a.size == b.size && a.indices.all { a[it] == b[it] }
}
