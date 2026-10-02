package io.github.opencircuit.ringkit

// Sleep-stage classifier — Awake / Light / Deep / REM from the 0x4c per-epoch signals
// (../docs/PROTOCOL.md §5.3). Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepStaging.swift (@ b1c2fdd), whole.
//
// The ring does NOT transmit a hypnogram; the RingConn app computes stages on-device from the same
// vitals decoded here, so this approximates that proprietary algorithm with consumer-wearable
// heuristics. ⚠️ APPROXIMATION, NOT GROUND TRUTH: tuned to be physiologically principled and to
// partition a night roughly the way a ring tracker would, not validated per epoch.
//
// Signals per 150 s epoch (forward-filled across epochs that drop a reading): HR `[4]` (the spine —
// stage bands are percentiles of the night's own asleep HR, never absolute bpm), HRV `[5]` (a
// secondary REM cue via its short-term variability) and motion `[10:15]` (a moving sleeper is
// awake). Awake is decided FIRST, from motion OR a smoothed HR a margin above the night's sleeping
// floor; onset/offset are the first/last SUSTAINED asleep run; time in bed outside that span is
// AWAKE-IN-BED, never sleep. Deep = calm low-HR troughs; REM = elevated or variable HR with no
// motion; Light = the rest. Short Deep/REM runs smooth back to Light.
//
// Port notes:
//  • Swift `inout [Bool]` passes take a caller-owned `BooleanArray` and mutate it in place; a pass
//    that upstream commits by whole-array assignment copies its candidate into the caller's array.
//  • `TimeInterval` totals are `Duration`s; `Summary.minutes` rounds half AWAY from zero, as Swift's
//    `rounded()`, and is 64-bit.
//  • Swift's `min`/`max` keep the non-NaN operand where Kotlin's `minOf`/`maxOf` propagate NaN; the
//    passes that can see a non-finite value use [swiftMin]/[swiftMax]/[swiftSequenceMin].
//  • Inputs upstream traps on are bounded or rejected: see [Tuning]'s checks,
//    [PersonalBaseline.fromRecentDeepHR] and the overflow-safe rolling windows.

import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt

/**
 * Stage-by-stage classifier over a night's `0x4c` [BulkRecord]s. Pure: it takes records, returns
 * [SleepSegment]s, and touches no I/O.
 */
object SleepStaging {

    /**
     * Tunable thresholds. All HR/variability cut-offs are PERCENTILES of the night's own asleep
     * distribution (plus small absolute floors), so they adapt per night. The defaults are
     * upstream's, including its calibrated Deep/REM percentiles (fitted against a strap hypnogram and
     * the RingConn app's night totals) and every measured knob; read upstream's comments for the
     * evidence behind each. Immutable: take a variant with `copy(...)`; [DEFAULT] never changes.
     *
     * Rejected at construction (upstream traps on them while staging): a non-finite percentile, and a
     * negative [variabilityHalfWindow] or [hrWakeHalfWindow].
     */
    data class Tuning(
        /** Motion magnitude above the local idle floor (summed over the epoch) above which the epoch is Awake. */
        val awakeMotion: Int = 15,
        /** Lower HR percentile (of asleep epochs) bounding Deep — near the night's floor. */
        val deepHRPercentile: Double = 0.42,
        /** Upper HR percentile bounding "HR elevated toward waking" → a REM cue. */
        val remHRPercentile: Double = 0.86,
        /** Variability percentile below which an epoch is calm enough for Deep. */
        val deepVarPercentile: Double = 0.50,
        /** Variability percentile above which an epoch is "variable" → a REM cue. */
        val remVarPercentile: Double = 0.84,
        /** Half-window (epochs each side) of the rolling HR/HRV variability estimate. */
        val variabilityHalfWindow: Int = 2,
        /** Floor for the Deep variability gate on the BLENDED variability scale, so a flat night still admits Deep. */
        val deepVarFloor: Double = 2.5,
        /** Floor for the REM variability gate (blended scale). */
        val remVarFloor: Double = 3.0,
        /** Minimum consolidated Deep run (epochs); shorter → Light. */
        val minDeepRunEpochs: Int = 3,
        /** Minimum consolidated REM run (epochs); shorter → Light. */
        val minREMRunEpochs: Int = 2,
        /** Minimum Awake run inside the staged window; shorter → Light. */
        val minAwakeRunEpochs: Int = 1,
        /** Weight of HRV short-term variability fused into the variability score (0 = HR only). */
        val hrvVarWeight: Double = 0.5,
        /** Weight of respiratory-rate variability fused in. 0 — byte-identical to the pre-RR model until fitted. */
        val rrVarWeight: Double = 0.0,
        /** Low percentile of the block's HR taken as the night's SLEEPING FLOOR. */
        val sleepFloorPercentile: Double = 0.12,
        /** bpm above the sleeping floor at which a smoothed epoch counts as awake — set above typical REM elevation. */
        val wakeHRMarginBPM: Double = 18.0,
        /** Half-window of the rolling-median HR the wake gate reads. */
        val hrWakeHalfWindow: Int = 2,
        /** Half-window of the sleep-vitals coverage that softens MOTION-awake in the morning tail. 0 disables it. */
        val motionAwakeVitalsHalfWindow: Int = 3,
        /** A sustained asleep run of at least this many epochs anchors onset (its start) and offset (end of the last one). */
        val onsetSustainEpochs: Int = 6,
        /** Minimum HR-only interior awake run; shorter ones erode back to asleep. */
        val minHRWakeRunEpochs: Int = 5,
        /** Exempt the awake run that OPENS the block from erosion. `false` restores the unguarded sweep. */
        val protectsLeadingHRWake: Boolean = true,
        /** bpm above the floor at/above which a candidate second-bout epoch is genuinely awake. 0 disables the rescue. */
        val hrWakeRescueCeilingBPM: Double = 25.0,
        /** Minimum share of a rescued run's epochs that carry raw sleep-vitals. */
        val hrWakeRescueVitalsFraction: Double = 0.5,
        /** Fraction of the evening→floor HR descent at which HR has "settled" into sleep. */
        val onsetSettleFraction: Double = 0.60,
        /** Minimum evening→floor descent (bpm) for the onset trim to fire at all. */
        val onsetMinDescentBPM: Double = 10.0,
        /** Epochs at the window head whose median is the pre-sleep "evening level". */
        val onsetScanEpochs: Int = 12,
        /** The onset settle is sought only within the first this-many epochs (also bounds the offset passes from the tail). */
        val onsetSearchEpochs: Int = 48,
        /** Point-of-no-return offset margin as a fraction of the night's sleeping-HR spread. 0 disables the pass. */
        val offsetNoReturnSpreadFraction: Double = 0.5,
        /** Floor on that derived margin, in bpm. */
        val offsetNoReturnMinMarginBPM: Double = 2.0,
        /** A consolidated asleep run this long means real sleep happened (lead-in guard, rescue guard, survival bar). */
        val minConsolidatedSleepEpochs: Int = 16,
        /** With a personal baseline, Deep requires HR within this many bpm of the person's typical deep HR. */
        val deepBaselineMarginBPM: Double = 18.0,
        /** Max epochs to reach back before onset when re-opening the in-bed envelope. 0 disables the widen. */
        val preOnsetBedtimeReachEpochs: Int = 24,
        /** Largest short data gap (in epochs) the bedtime widen may cross. */
        val preOnsetBedtimeMaxGapEpochs: Int = 5,
        /** Minimum unbroken SpO2-cadence alternation (epochs) the wake locator trusts. 0 disables the pass. */
        val cadenceWakeQuietEpochs: Int = 20,
        /** Upper bound on a trusted cadence run: longer is a ring left in continuous SpO2 mode, not a night. */
        val cadenceWakeMaxQuietEpochs: Int = 240,
        /** Whether the staged path honours the skin-temperature wear gate. `false` drops the samples. */
        val stagedWearGate: Boolean = true,
    ) {
        init {
            require(deepHRPercentile.isFinite()) { "deepHRPercentile must be finite, was $deepHRPercentile" }
            require(remHRPercentile.isFinite()) { "remHRPercentile must be finite, was $remHRPercentile" }
            require(deepVarPercentile.isFinite()) { "deepVarPercentile must be finite, was $deepVarPercentile" }
            require(remVarPercentile.isFinite()) { "remVarPercentile must be finite, was $remVarPercentile" }
            require(sleepFloorPercentile.isFinite()) { "sleepFloorPercentile must be finite, was $sleepFloorPercentile" }
            require(variabilityHalfWindow >= 0) { "variabilityHalfWindow must not be negative, was $variabilityHalfWindow" }
            require(hrWakeHalfWindow >= 0) { "hrWakeHalfWindow must not be negative, was $hrWakeHalfWindow" }
        }

        companion object {
            /** Upstream's defaults. */
            val DEFAULT = Tuning()
        }
    }

    /**
     * A person's rolling, multi-night HR baseline. Anchoring the Deep band to the person's typical
     * deep-sleep HR stops an atypical, globally elevated night (fever, alcohol, illness) from reading
     * its own lowest epochs as Deep. Optional everywhere — absent, staging is the single-night
     * classifier.
     */
    data class PersonalBaseline(
        /** The person's TYPICAL deep-sleep heart rate (bpm) across recent nights. */
        val deepSleepHR: Double,
    ) {
        companion object {
            /**
             * From recent nights' per-night deep-sleep HR means: the true MEDIAN (the two central
             * values averaged for an even count) of the positive entries. Null when fewer than
             * [minNights] valid nights exist — or when none does at all (upstream traps there when
             * [minNights] is zero or negative).
             */
            fun fromRecentDeepHR(deepHRs: List<Int>, minNights: Int = 3): PersonalBaseline? {
                val valid = deepHRs.filter { it > 0 }.map { it.toDouble() }.sorted()
                if (valid.size < minNights || valid.isEmpty()) return null
                val mid = valid.size / 2
                val median = if (valid.size % 2 == 0) (valid[mid - 1] + valid[mid]) / 2 else valid[mid]
                return PersonalBaseline(median)
            }
        }
    }

    /** [Summary.minutes]: each total in whole minutes, rounded half away from zero. */
    data class Minutes(val inBed: Long, val awake: Long, val light: Long, val deep: Long, val rem: Long, val asleep: Long)

    /**
     * Per-stage durations for a night. [inBed] is the whole detected window; [totalAsleep] excludes
     * Awake (and the overlapping in-bed span).
     */
    data class Summary(
        val inBed: Duration,
        val awake: Duration,
        val light: Duration,
        val deep: Duration,
        val rem: Duration,
    ) {
        /** Time actually asleep = Light + Deep + REM. */
        val totalAsleep: Duration get() = light.plus(deep).plus(rem)

        /** Sleep efficiency = asleep / in-bed (0 when there is no positive in-bed window). */
        val efficiency: Double get() = if (inBed > Duration.ZERO) seconds(totalAsleep) / seconds(inBed) else 0.0

        /** The same numbers in whole minutes. */
        val minutes: Minutes
            get() {
                fun m(t: Duration): Long = roundHalfAwayFromZero(seconds(t) / 60).toLong()
                return Minutes(m(inBed), m(awake), m(light), m(deep), m(rem), m(totalAsleep))
            }
    }

    /** The SLEEP window: real onset to final wake. */
    data class SleepInterval(val onset: Instant, val wake: Instant)

    private val ASLEEP = setOf(SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM)

    /**
     * Classify a night's records into an [SleepStage.IN_BED] span plus Awake/Light(core)/Deep/REM
     * segments. Empty when no sleep block (≥ 1 h still) is detected.
     *
     * STITCHING: a night handed off across several drains arrives as contiguous runs separated by
     * data gaps; each run is staged on its own and the results concatenated in start order, each with
     * its own in-bed segment (gaps are not in bed). A single run takes the plain path with the records
     * exactly as passed.
     *
     * WEAR GATE: [temperatures] — the night's skin-temperature samples including the cold/charging
     * ones — let an off-wrist block drop out, as on the coarse path and in night selection. Empty
     * means motion only; [Tuning.stagedWearGate] `false` drops them.
     *
     * DUPLICATES: each counter is read once, its first copy ([BulkSleep.distinctRecords]), as the
     * detector that finds the block reads it — so a doubled night stages as the single night
     * (PORTING D-70; upstream staged the doubled timeline).
     */
    fun classify(
        records: List<BulkRecord>,
        temperatures: List<TemperatureSample> = emptyList(),
        epoch: Long = Command.SYNC_EPOCH,
        tuning: Tuning = Tuning.DEFAULT,
        baseline: PersonalBaseline? = null,
        motionPolicy: BulkSleep.MotionChannelPolicy = BulkSleep.MotionChannelPolicy.DEFAULT,
    ): List<SleepSegment> = classifyDistinct(BulkSleep.distinctRecords(records), temperatures, epoch, tuning, baseline, motionPolicy)

    private fun classifyDistinct(
        records: List<BulkRecord>,
        temperatures: List<TemperatureSample>,
        epoch: Long,
        tuning: Tuning,
        baseline: PersonalBaseline?,
        motionPolicy: BulkSleep.MotionChannelPolicy,
    ): List<SleepSegment> {
        val temps = if (tuning.stagedWearGate) temperatures else emptyList()
        val frags = BulkSleep.contiguousFragments(records)
        val staged = if (frags.size > 1) {
            frags.flatMap { classifyContiguous(it, temps, epoch, tuning, baseline, motionPolicy) }.sortedBy { it.start }
        } else {
            classifyContiguous(records, temps, epoch, tuning, baseline, motionPolicy)
        }
        // Re-open the in-bed envelope over a MEASURED pre-onset lead-in the still-block missed. Runs
        // over the FULL record set so it sees a lead-in a data gap split into another fragment.
        return applyBedtimeWiden(staged, records, epoch, tuning)
    }

    /**
     * Extend the in-bed envelope back over a MEASURED awake-in-bed lead-in. Only the first in-bed
     * segment's start moves and a leading awake segment is added — onset and wake are untouched; it
     * fires only when the envelope opens exactly at onset with nothing awake before it; it never
     * fabricates a lead-in; [Tuning.preOnsetBedtimeReachEpochs] 0 returns [segments] as they are.
     */
    private fun applyBedtimeWiden(segments: List<SleepSegment>, records: List<BulkRecord>, epoch: Long, tuning: Tuning): List<SleepSegment> {
        if (tuning.preOnsetBedtimeReachEpochs <= 0 || segments.isEmpty()) return segments
        val onset = segments.filter { it.stage in ASLEEP }.minOfOrNull { it.start } ?: return segments
        val inBedIdx = segments.indexOfFirst { it.stage == SleepStage.IN_BED && !it.start.isAfter(onset) && onset.isBefore(it.end) }
        if (inBedIdx < 0) return segments
        // Fast-onset only: the envelope opens exactly at onset and nothing awake precedes it.
        if (segments[inBedIdx].start != onset || segments.any { it.stage == SleepStage.AWAKE && it.start.isBefore(onset) }) return segments
        val bedStart = preOnsetBedStart(records, onset, epoch, tuning) ?: return segments
        if (!bedStart.isBefore(onset)) return segments
        val out = segments.toMutableList()
        out[inBedIdx] = SleepSegment(bedStart, segments[inBedIdx].end, SleepStage.IN_BED)
        out += SleepSegment(bedStart, onset, SleepStage.AWAKE)
        return out.sortedBy { it.start }
    }

    /**
     * The earliest time that can honestly be called "in bed" before [onset]: walks worn epochs
     * backward from onset, bridging short data gaps (or a longer one only while HR stays at sleep
     * level on both sides), and widens ONLY if the run holds at least one genuinely awake epoch
     * (HR ≥ floor + wake margin). Null when no measured awake lead-in exists.
     */
    private fun preOnsetBedStart(records: List<BulkRecord>, onset: Instant, epoch: Long, tuning: Tuning): Instant? {
        val interval = BulkRecord.EPOCH_SECONDS.toLong()
        val reach = onset.minusSeconds(tuning.preOnsetBedtimeReachEpochs.toLong() * interval)
        // Sleeping floor from the first ~2 h after onset (the same low-percentile basis classify uses).
        val twoHours = onset.plusSeconds(2 * 3600)
        val sleepHR = records
            .filter { val t = it.date(epoch); !t.isBefore(onset) && t.isBefore(twoHours) }
            .mapNotNull { it.heartRate }.map { it.toDouble() }
        if (sleepHR.size < 4) return null
        val floor = percentile(sleepHR.sorted(), tuning.sleepFloorPercentile)
        val wakeThreshold = floor + tuning.wakeHRMarginBPM
        // HR at onset seeds the first gap's asleep-bridge test (the floor when no record sits there).
        val oneSecond = Duration.ofSeconds(1)
        val onsetHR = records.firstOrNull { Duration.between(onset, it.date(epoch)).abs() < oneSecond }
            ?.heartRate?.toDouble() ?: floor
        // Worn epochs strictly before onset within reach, nearest first.
        val pre = records
            .filter { r -> val t = r.date(epoch); t.isBefore(onset) && !t.isBefore(reach) && r.heartRate != null }
            .sortedByDescending { it.counter }
        if (pre.isEmpty()) return null
        val maxShortGap = Duration.ofSeconds(interval * maxOf(1, tuning.preOnsetBedtimeMaxGapEpochs))
        var bedStart: Instant? = null
        var sawAwake = false
        var prevTime = onset
        var prevHR = onsetHR
        for (r in pre) {
            val t = r.date(epoch)
            val hr = checkNotNull(r.heartRate) { "pre-onset records are filtered to those with a heart rate" }.toDouble()
            val contiguous = Duration.between(t, prevTime) <= maxShortGap
            val asleepBridge = hr <= wakeThreshold && prevHR <= wakeThreshold
            if (!(contiguous || asleepBridge)) break
            bedStart = t
            if (hr >= wakeThreshold) sawAwake = true
            prevTime = t
            prevHR = hr
        }
        return if (sawAwake) bedStart else null
    }

    /** One epoch row of a contiguous run; [vitals] is the RAW (not forward-filled) "carried sleep-vitals HRV" flag. */
    private class Row(val time: Instant, val hr: Int, val hrv: Int?, val motion: Int, val rr: Double?, val vitals: Boolean)

    /** Stage ONE contiguous record run (no internal data gaps) into in-bed + stage segments. */
    private fun classifyContiguous(
        records: List<BulkRecord>,
        temperatures: List<TemperatureSample>,
        epoch: Long,
        tuning: Tuning,
        baseline: PersonalBaseline?,
        motionPolicy: BulkSleep.MotionChannelPolicy,
    ): List<SleepSegment> {
        val block = BulkSleep.mainSleep(records, temperatures = temperatures, epoch = epoch, motionPolicy = motionPolicy)
            ?: return emptyList()

        // Epochs inside the in-bed window, forward-filling HR/HRV/RR across dropped reads.
        val inBlock = records
            .filter { val t = it.date(epoch); !t.isBefore(block.start) && !t.isAfter(block.end) }
            .sortedBy { it.counter }
        val rows = ArrayList<Row>(inBlock.size)
        // Parallel to rows: each row's record template — the ring's SpO2 duty cycle, read only by
        // the cadence wake locator.
        val rowLayouts = ArrayList<BulkRecord.Layout>(inBlock.size)
        // Motion is measured ABOVE a local rolling idle floor (Gen 2 idles ~1, Gen 3 ~15 and drifts).
        val times = inBlock.map { it.date(epoch) }
        val rawMotion = BulkSleep.motionMagnitudes(inBlock, policy = motionPolicy)
        val motionFloor = ActivityPeriod.rollingLowPercentile(
            rawMotion, times, ActivityPeriod.MOTION_FLOOR_WINDOW_STAGING, ActivityPeriod.MOTION_FLOOR_PERCENTILE,
        )
        var lastHR: Int? = null
        var lastHRV: Int? = null
        var lastRR: Double? = null
        for ((idx, r) in inBlock.withIndex()) {
            r.heartRate?.let { lastHR = it }
            r.hrvRMSSD?.let { lastHRV = it }
            r.respiratoryRate?.let { lastRR = it }
            val hr = lastHR ?: continue // skip until the first HR reading
            val motion = maxOf(0, (rawMotion[idx] - motionFloor[idx]).toInt())
            rows += Row(times[idx], hr, lastHRV, motion, lastRR, r.hrvRMSSD != null)
            rowLayouts += r.layout
        }
        if (rows.size < 2) return emptyList()
        val n = rows.size
        val vitals = rows.map { it.vitals }

        // Variability: rolling SD of HR, optionally fused with HRV and RR.
        val hr = rows.map { it.hr.toDouble() }
        val variability = rollingSD(hr, tuning.variabilityHalfWindow).toDoubleArray()
        if (tuning.hrvVarWeight > 0 && rows.any { it.hrv != null }) {
            val hrv = filledForward(rows.map { it.hrv }).map { (it ?: 0).toDouble() }
            val hrvVar = rollingSD(hrv, tuning.variabilityHalfWindow)
            for (i in variability.indices) variability[i] += tuning.hrvVarWeight * hrvVar[i]
        }
        if (tuning.rrVarWeight > 0 && rows.any { it.rr != null }) {
            val rr = filledForward(rows.map { it.rr }).map { it ?: 0.0 }
            val rrVar = rollingSD(rr, tuning.variabilityHalfWindow)
            for (i in variability.indices) variability[i] += tuning.rrVarWeight * rrVar[i]
        }

        // HR-aware AWAKE: motion OR sustained HR elevation above the night's sleeping floor.
        val sleepFloor = percentile(hr.sorted(), tuning.sleepFloorPercentile)
        val wakeThreshold = sleepFloor + tuning.wakeHRMarginBPM
        val smHR = rollingMedian(hr, tuning.hrWakeHalfWindow)
        // Motion-awake, softened for moving-but-asleep epochs ONLY across the morning tail (after the
        // last sustained asleep run), so an interior awakening is never absorbed as sleep.
        val motionAwakeStrict = rows.map { it.motion > tuning.awakeMotion }
        val awakeStrict = BooleanArray(n) { smHR[it] >= wakeThreshold || motionAwakeStrict[it] }
        val tailStart = if (tuning.motionAwakeVitalsHalfWindow > 0) {
            sleepSpan(awakeStrict, tuning.onsetSustainEpochs)?.let { it.second + 1 } ?: n
        } else {
            n
        }
        val vitalsNearby = windowedVitalsCoverage(vitals, tuning.motionAwakeVitalsHalfWindow)
        val motionAwake = List(n) { i ->
            val softenTail = i >= tailStart && vitalsNearby[i] && smHR[i] < wakeThreshold
            motionAwakeStrict[i] && !softenTail
        }
        val awake = BooleanArray(n) { smHR[it] >= wakeThreshold || motionAwake[it] }
        // Erode short HR-only awake runs (a transient REM-ish bump is not an awakening).
        erodeShortHRWake(awake, motionAwake, tuning.minHRWakeRunEpochs, tuning.protectsLeadingHRWake)
        // Rescue a long HR-only awake run that is a SECOND SLEEP BOUT; remember what it reclaimed, so
        // the offset passes cannot undo it.
        val beforeSecondBoutRescue = awake.copyOf()
        rescueSecondBoutHRWake(awake, smHR, motionAwake, vitals, sleepFloor, tuning)
        val lastRescuedIndex = awake.indices.lastOrNull { beforeSecondBoutRescue[it] && !awake[it] }

        // Leading edge: trim a quiet wind-down, then push onset past a pre-sleep wake block.
        markDescentOnsetAwake(awake, smHR, motionAwake, sleepFloor, tuning)
        markLeadInWakeOnset(awake, tuning)

        // Trailing edge: the HR point-of-no-return, held back by the rescue AND the vitals softening.
        val lastVitalsSoftened = (0 until n).lastOrNull { motionAwakeStrict[it] && !motionAwake[it] }
        val notBefore = listOfNotNull(lastRescuedIndex, lastVitalsSoftened).maxOrNull()
        markPointOfNoReturnOffset(
            awake, smHR, sleepFloor, resolvedOffsetMargin(hr, sleepFloor, tuning), vitals, notBefore, tuning,
        )
        // Then the SpO2-cadence locator, held back by the rescue ONLY (upstream measured that gating
        // it on the softening made the result oscillate with sync time).
        markCadenceWakeOffset(
            awake, cadenceSteps(rows.map { it.time }, rowLayouts), smHR, sleepFloor,
            resolvedOffsetMargin(hr, sleepFloor, tuning), lastRescuedIndex, tuning,
        )

        // ONSET / OFFSET: the kept window runs from the first to the last SUSTAINED asleep run.
        val (lo, hi) = sleepSpan(awake, tuning.onsetSustainEpochs) ?: return emptyList()
        val windowStart = rows[lo].time
        val windowEnd = if (hi + 1 < n) rows[hi + 1].time else block.end

        // Night-relative bands from the IN-WINDOW asleep distribution.
        val windowIdx = (lo..hi).toList()
        val asleepIdx = windowIdx.filter { !awake[it] }
        val pool = if (asleepIdx.size >= 4) asleepIdx else windowIdx
        val hrPool = pool.map { hr[it] }.sorted()
        val varPool = pool.map { variability[it] }.sorted()

        val deepHR = percentile(hrPool, tuning.deepHRPercentile)
        val remHR = percentile(hrPool, tuning.remHRPercentile)
        val deepVar = swiftMax(percentile(varPool, tuning.deepVarPercentile), tuning.deepVarFloor)
        val remVar = swiftMax(percentile(varPool, tuning.remVarPercentile), tuning.remVarFloor)

        // Per-epoch decision. With a personal baseline, Deep also needs HR near the person's deep HR;
        // a calm trough too elevated for this person is Light (not REM — it is flat).
        val deepCeiling = baseline?.let { it.deepSleepHR + tuning.deepBaselineMarginBPM }
        val stages = windowIdx.map { i ->
            when {
                awake[i] -> SleepStage.AWAKE
                hr[i] <= deepHR && variability[i] <= deepVar -> {
                    val nearPersonalDeep = deepCeiling?.let { hr[i] <= it } ?: true
                    if (nearPersonalDeep) SleepStage.ASLEEP_DEEP else SleepStage.ASLEEP_CORE
                }
                hr[i] >= remHR || variability[i] > remVar -> SleepStage.ASLEEP_REM
                else -> SleepStage.ASLEEP_CORE
            }
        }.toMutableList()
        smooth(stages, tuning)

        // Segments tiling the FULL in-bed window: [inBed] + [pre-awake?] + [onset→offset] + [post-awake?].
        val segs = mutableListOf(SleepSegment(block.start, block.end, SleepStage.IN_BED))
        if (windowStart.isAfter(block.start)) segs += SleepSegment(block.start, windowStart, SleepStage.AWAKE)
        var k = 0
        while (k < windowIdx.size) {
            var j = k
            while (j + 1 < windowIdx.size && stages[j + 1] == stages[k]) j++
            // Clamp the first segment's start to windowStart and the last one's end to windowEnd.
            val segStart = if (k == 0) windowStart else rows[windowIdx[k]].time
            val segEnd = if (j + 1 < windowIdx.size) rows[windowIdx[j + 1]].time else windowEnd
            segs += SleepSegment(segStart, if (windowEnd.isBefore(segEnd)) windowEnd else segEnd, stages[k])
            k = j + 1
        }
        if (block.end.isAfter(windowEnd)) segs += SleepSegment(windowEnd, block.end, SleepStage.AWAKE)
        return segs
    }

    /** Total time in each stage across the night; the overlapping in-bed span is excluded. */
    fun stageTotals(segments: List<SleepSegment>): Map<SleepStage, Duration> {
        val out = LinkedHashMap<SleepStage, Duration>()
        for (s in segments) {
            if (s.stage == SleepStage.IN_BED) continue
            out[s.stage] = (out[s.stage] ?: Duration.ZERO).plus(s.duration)
        }
        return out
    }

    /**
     * Roll the segments up into a [Summary]. In-bed is the sum of ALL in-bed segments (one per
     * stitched fragment; the gaps between fragments are not in bed), or the staged sum when that is
     * not positive.
     */
    fun summary(segments: List<SleepSegment>): Summary {
        val t = stageTotals(segments)
        val awake = t[SleepStage.AWAKE] ?: Duration.ZERO
        val light = t[SleepStage.ASLEEP_CORE] ?: Duration.ZERO
        val deep = t[SleepStage.ASLEEP_DEEP] ?: Duration.ZERO
        val rem = t[SleepStage.ASLEEP_REM] ?: Duration.ZERO
        val staged = awake.plus(light).plus(deep).plus(rem)
        val inBedSum = segments.filter { it.stage == SleepStage.IN_BED }.fold(Duration.ZERO) { acc, s -> acc.plus(s.duration) }
        val inBed = if (inBedSum > Duration.ZERO) inBedSum else staged
        return Summary(inBed, awake, light, deep, rem)
    }

    /** Total time asleep (Light + Deep + REM). */
    fun totalAsleep(segments: List<SleepSegment>): Duration {
        val t = stageTotals(segments)
        return (t[SleepStage.ASLEEP_CORE] ?: Duration.ZERO).plus(t[SleepStage.ASLEEP_DEEP] ?: Duration.ZERO)
            .plus(t[SleepStage.ASLEEP_REM] ?: Duration.ZERO)
    }

    /**
     * The actual SLEEP window: the earliest asleep start (real onset) to the latest asleep end (final
     * wake). Distinct from the in-bed window; the gap between in-bed start and onset is the sleep
     * latency. Null when nothing is asleep.
     */
    fun sleepWindow(segments: List<SleepSegment>): SleepInterval? {
        val asleep = segments.filter { it.stage in ASLEEP }
        val onset = asleep.minOfOrNull { it.start } ?: return null
        val wake = asleep.maxOf { it.end }
        return SleepInterval(onset, wake)
    }

    // Helpers

    /** Relabel sub-minimum Deep/REM/Awake runs to Light, so stages don't flap epoch to epoch. */
    private fun smooth(stages: MutableList<SleepStage>, t: Tuning) {
        val n = stages.size
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && stages[j + 1] == stages[i]) j++
            val run = j - i + 1
            val minRun = when (stages[i]) {
                SleepStage.ASLEEP_DEEP -> t.minDeepRunEpochs
                SleepStage.ASLEEP_REM -> t.minREMRunEpochs
                SleepStage.AWAKE -> t.minAwakeRunEpochs
                else -> null
            }
            if (minRun != null && run < minRun) for (k in i..j) stages[k] = SleepStage.ASLEEP_CORE
            i = j + 1
        }
    }

    /** First index of the ±[half] window around [i] (`max(0, i − half)`); [half] ≥ 0, never overflows. */
    private fun windowFirst(i: Int, half: Int): Int = if (half >= i) 0 else i - half

    /** Last index of the ±[half] window around [i] in [n] (`min(n − 1, i + half)`); never overflows. */
    private fun windowLast(i: Int, half: Int, n: Int): Int = if (half >= n - 1 - i) n - 1 else i + half

    /** Centred rolling MEDIAN over a ±[half]-epoch window (the upper middle for an even window, as upstream). */
    private fun rollingMedian(xs: List<Double>, half: Int): List<Double> {
        val n = xs.size
        return List(n) { i ->
            val w = xs.subList(windowFirst(i, half), windowLast(i, half, n) + 1).sorted()
            w[w.size / 2]
        }
    }

    /**
     * Per-epoch "the ring is still emitting sleep-vitals nearby": a sleep-vitals epoch within
     * [halfWindow] epochs on either side. All false when [halfWindow] ≤ 0.
     */
    private fun windowedVitalsCoverage(hasVitals: List<Boolean>, halfWindow: Int): BooleanArray {
        val n = hasVitals.size
        if (n == 0 || halfWindow <= 0) return BooleanArray(n)
        return BooleanArray(n) { i -> (windowFirst(i, halfWindow)..windowLast(i, halfWindow, n)).any { hasVitals[it] } }
    }

    /**
     * Relabel awake runs driven ONLY by HR (no motion-awake epoch inside) and shorter than [minRun]
     * back to asleep. With [protectsLeading], the run that OPENS the block is exempt: no sleep
     * precedes it, so there is no hole to repair, and eroding it would declare the pre-sleep wind-down
     * sleep. Mutates [awake] in place (upstream's `inout`); [motionAwake] must be as long as [awake].
     */
    internal fun erodeShortHRWake(awake: BooleanArray, motionAwake: List<Boolean>, minRun: Int, protectsLeading: Boolean) {
        val n = awake.size
        var i = 0
        while (i < n) {
            if (!awake[i]) { i++; continue }
            var j = i
            while (j + 1 < n && awake[j + 1]) j++
            val run = j - i + 1
            val hasMotion = (i..j).any { motionAwake[it] }
            val opensTheBlock = protectsLeading && i == 0
            if (run < minRun && !hasMotion && !opensTheBlock) for (k in i..j) awake[k] = false
            i = j + 1
        }
    }

    /**
     * Relabel a LONG, motion-free, HR-only awake stretch back to asleep when it is a SECOND SLEEP BOUT
     * after a mid-night wake. Five conjunctive guards: the ceiling knob is positive; the candidate is
     * a maximal sub-run of awake epochs that are motion-free AND below `floor + ceiling`; it is at
     * least [Tuning.minHRWakeRunEpochs] long; a consolidated asleep run lies somewhere before it; and
     * enough of its epochs carry raw sleep-vitals. Only ever ADDS sleep; mutates [awake] in place.
     */
    private fun rescueSecondBoutHRWake(
        awake: BooleanArray,
        smHR: List<Double>,
        motionAwake: List<Boolean>,
        vitals: List<Boolean>,
        floor: Double,
        tuning: Tuning,
    ) {
        if (!(tuning.hrWakeRescueCeilingBPM > 0)) return
        val ceiling = floor + tuning.hrWakeRescueCeilingBPM
        val n = awake.size
        var i = 0
        while (i < n) {
            if (!(awake[i] && !motionAwake[i] && smHR[i] < ceiling)) { i++; continue }
            var j = i
            while (j + 1 < n && awake[j + 1] && !motionAwake[j + 1] && smHR[j + 1] < ceiling) j++
            val run = j - i + 1
            // Longest consolidated asleep run anywhere strictly before this sub-run.
            var longestPrior = 0
            var prior = 0
            for (k in 0 until i) {
                if (awake[k]) prior = 0 else { prior++; longestPrior = maxOf(longestPrior, prior) }
            }
            val hasSleepBehind = longestPrior >= tuning.minConsolidatedSleepEpochs
            val vitalsCount = (i..j).count { vitals[it] }
            val vitalsFraction = vitalsCount.toDouble() / run
            if (run >= tuning.minHRWakeRunEpochs && hasSleepBehind && vitalsFraction >= tuning.hrWakeRescueVitalsFraction) {
                for (k in i..j) awake[k] = false
            }
            i = j + 1
        }
    }

    /**
     * Indices spanning real sleep: from the start of the FIRST asleep run of length ≥ [sustain] to the
     * end of the LAST such run. Null when no run is long enough.
     */
    private fun sleepSpan(awake: BooleanArray, sustain: Int): Pair<Int, Int>? {
        val n = awake.size
        var first: Int? = null
        var last: Int? = null
        var i = 0
        while (i < n) {
            if (awake[i]) { i++; continue }
            var j = i
            while (j + 1 < n && !awake[j + 1]) j++
            if (j - i + 1 >= sustain) {
                if (first == null) first = i
                last = j
            }
            i = j + 1
        }
        return if (first != null && last != null) Pair(first, last) else null
    }

    /**
     * Mark the leading pre-sleep WIND-DOWN as awake: everything before the start of the first
     * sustained run of smoothed HR at/below a descent-relative band, sought only within the first
     * [Tuning.onsetSearchEpochs]. A no-op without a real evening→floor descent or an early settle.
     */
    private fun markDescentOnsetAwake(awake: BooleanArray, smHR: List<Double>, motionAwake: List<Boolean>, floor: Double, tuning: Tuning) {
        val n = smHR.size
        if (!(tuning.onsetScanEpochs >= 1 && n > tuning.onsetScanEpochs)) return
        // Evening level = median of the first few in-bed epochs.
        val evening = percentile(smHR.subList(0, tuning.onsetScanEpochs).sorted(), 0.5)
        val descent = evening - floor
        if (!(descent >= tuning.onsetMinDescentBPM)) return
        val band = floor + tuning.onsetSettleFraction * descent
        val limit = minOf(tuning.onsetSearchEpochs, n)
        var i = 0
        var onset: Int? = null
        while (i < limit) {
            if (!(smHR[i] <= band && !motionAwake[i])) { i++; continue }
            var j = i
            while (j + 1 < n && smHR[j + 1] <= band && !motionAwake[j + 1]) j++
            if (j - i + 1 >= tuning.onsetSustainEpochs) { onset = i; break }
            i = j + 1
        }
        if (onset != null && onset > 0) for (k in 0 until onset) awake[k] = true
    }

    /**
     * Push onset past a clear pre-sleep wake episode: if a SUSTAINED awake run still begins within the
     * search window and no consolidated asleep run preceded it, mark everything up to the END of the
     * last such run awake. Only ever moves onset past epochs already judged awake.
     */
    private fun markLeadInWakeOnset(awake: BooleanArray, tuning: Tuning) {
        val n = awake.size
        val limit = minOf(tuning.onsetSearchEpochs, n)
        if (limit <= 0) return
        var blockStart: Int? = null
        var blockEnd: Int? = null
        var i = 0
        while (i < limit) {
            if (!awake[i]) { i++; continue }
            var j = i
            while (j + 1 < n && awake[j + 1]) j++
            if (j - i + 1 >= tuning.onsetSustainEpochs) { blockStart = i; blockEnd = j }
            i = j + 1
        }
        if (blockStart == null || blockEnd == null) return
        var longest = 0
        var run = 0
        for (k in 0 until blockStart) {
            if (awake[k]) run = 0 else { run++; longest = maxOf(longest, run) }
        }
        if (longest >= tuning.minConsolidatedSleepEpochs) return
        for (k in 0..blockEnd) awake[k] = true
    }

    /** Test seam for the percentile helper: [percentile] of [xs] sorted. */
    internal fun percentileForTesting(xs: List<Double>, q: Double): Double = percentile(xs.sorted(), q)

    /** Test seam for the consolidated-run helper. */
    internal fun sleepSpanForTesting(awake: List<Boolean>, sustain: Int): Pair<Int, Int>? = sleepSpan(awake.toBooleanArray(), sustain)

    /**
     * The offset margin for a night, DERIVED from its own sleeping-HR spread: the fraction of
     * `median − floor`, floored at [Tuning.offsetNoReturnMinMarginBPM]. 0 (disabled) when the
     * fraction is not positive or [hr] is empty.
     */
    internal fun resolvedOffsetMargin(hr: List<Double>, floor: Double, tuning: Tuning): Double {
        if (!(tuning.offsetNoReturnSpreadFraction > 0) || hr.isEmpty()) return 0.0
        val spread = swiftMax(0.0, percentile(hr.sorted(), 0.50) - floor)
        return swiftMax(tuning.offsetNoReturnMinMarginBPM, tuning.offsetNoReturnSpreadFraction * spread)
    }

    /**
     * Mark the trailing "HR rose and never settled again" run as awake — the OFFSET counterpart to the
     * onset passes. Bounded five ways: the marked region is a SUFFIX; it starts after onset; after
     * [notBefore] (the last epoch a rescue or the vitals softening reclaimed); within the last
     * [Tuning.onsetSearchEpochs]; and is reverted unless a CONSOLIDATED asleep run survives. With
     * [vitals] as long as [awake], a terminal-REM guard also requires the suffix's sleep-vitals share
     * to be materially thinner than the sleep before it; empty [vitals] skips that guard (direct
     * tests only — production always passes it). A non-positive [margin] disables the pass. Mutates
     * [awake] in place (upstream's `inout`).
     */
    internal fun markPointOfNoReturnOffset(
        awake: BooleanArray,
        smHR: List<Double>,
        floor: Double,
        margin: Double,
        vitals: List<Boolean> = emptyList(),
        notBefore: Int? = null,
        tuning: Tuning,
    ) {
        if (!(margin > 0) || awake.isEmpty() || smHR.size != awake.size) return
        val (lo, _) = sleepSpan(awake, tuning.onsetSustainEpochs) ?: return
        val n = awake.size
        // Not at or before onset or a reclaimed epoch, nor further back than the onset passes reach.
        val searchFloor = n.toLong() - minOf(tuning.onsetSearchEpochs, n)
        val earliest = maxOf(lo.toLong(), (notBefore ?: lo).toLong(), searchFloor)
        val threshold = floor + margin
        // Earliest index whose ENTIRE suffix stays above the threshold (a suffix by construction).
        var start: Int? = null
        var suffixMin = Double.POSITIVE_INFINITY
        for (i in smHR.indices.reversed()) {
            suffixMin = swiftMin(suffixMin, smHR[i])
            if (suffixMin > threshold) start = i else break
        }
        val s = start ?: return
        if (s.toLong() <= earliest) return // the onset epoch (and the last reclaimed one) stay asleep

        // TERMINAL-REM GUARD: HR alone cannot tell a final REM period from quiet wake; the ring keeps
        // emitting sleep-vitals through REM and the stream thins at true wake.
        if (vitals.isNotEmpty() && vitals.size == n) {
            val suffix = vitals.subList(s, n)
            val body = vitals.subList(lo, s)
            val suffixShare = if (suffix.isEmpty()) 0.0 else suffix.count { it }.toDouble() / suffix.size
            val bodyShare = if (body.isEmpty()) 0.0 else body.count { it }.toDouble() / body.size
            if (!(bodyShare > 0)) return
            if (!(suffixShare <= bodyShare * tuning.hrWakeRescueVitalsFraction)) return
        }
        val candidate = awake.copyOf()
        for (i in s until n) candidate[i] = true
        // Only commit if a CONSOLIDATED asleep run survives.
        if (sleepSpan(candidate, tuning.minConsolidatedSleepEpochs) == null) return
        candidate.copyInto(awake)
    }

    // SpO2-cadence wake locator

    /**
     * What one epoch-to-epoch step says about the ring's SpO2 duty cycle (sleep-vitals and activity
     * templates alternate 1:1 while the ring runs its sleep-measurement program).
     */
    internal enum class CadenceStep {
        /** The template flipped exactly as the duty cycle demands (allowing for one bridged hole). */
        ALTERNATING,

        /** Two same-template epochs in a row: the duty cycle broke here. */
        VIOLATION,

        /** No cadence information: an idle epoch on either side, or more than one missing epoch. Never evidence of a wake. */
        UNKNOWN,
    }

    /**
     * Classify every epoch-to-epoch step against the SpO2 duty cycle. Exactly ONE missing epoch is
     * bridged (its template inferred from parity); longer holes are [CadenceStep.UNKNOWN]. The step
     * count is `round(dt / 150 s)` (half away from zero), at least 1, so timing jitter neither hides
     * nor invents a violation. Index 0 is always [CadenceStep.UNKNOWN]. Empty when the lists differ in
     * length or are empty.
     */
    internal fun cadenceSteps(times: List<Instant>, layouts: List<BulkRecord.Layout>): List<CadenceStep> {
        if (times.size != layouts.size || times.isEmpty()) return emptyList()
        val out = MutableList(times.size) { CadenceStep.UNKNOWN }
        for (i in 1 until times.size) {
            val previous = layouts[i - 1]
            val current = layouts[i]
            // An unworn epoch is outside the measurement program altogether; it is not a violation.
            if (previous == BulkRecord.Layout.IDLE || current == BulkRecord.Layout.IDLE) continue
            val dt = seconds(Duration.between(times[i - 1], times[i]))
            val steps = maxOf(1L, roundHalfAwayFromZero(dt / BulkRecord.EPOCH_SECONDS).toLong())
            if (steps > 2) continue // more than one epoch missing → no information
            val expectedSame = steps % 2 == 0L
            out[i] = if ((previous == current) == expectedSame) CadenceStep.ALTERNATING else CadenceStep.VIOLATION
        }
        return out
    }

    /**
     * Mark final wake where the ring LEFT its sleep-measurement program: the epoch after the end of
     * the LAST alternating run of at least [Tuning.cadenceWakeQuietEpochs]. Declines (never cuts
     * weaker) when the pass is off; no such run exists; the run reaches the data edge or a hole rather
     * than a violation; it is longer than [Tuning.cadenceWakeMaxQuietEpochs]; the cut would land at or
     * before onset, [notBefore], or further back than [Tuning.onsetSearchEpochs] from the tail; the
     * smoothed HR does not stay above `floor + margin` for the whole remainder; or no consolidated
     * asleep run would survive. Mutates [awake] in place (upstream's `inout`).
     */
    internal fun markCadenceWakeOffset(
        awake: BooleanArray,
        cadence: List<CadenceStep>,
        smHR: List<Double>,
        floor: Double,
        margin: Double,
        notBefore: Int? = null,
        tuning: Tuning,
    ) {
        if (!(tuning.cadenceWakeQuietEpochs > 0) || !(margin > 0) || awake.isEmpty() ||
            cadence.size != awake.size || smHR.size != awake.size
        ) {
            return
        }
        val (lo, _) = sleepSpan(awake, tuning.onsetSustainEpochs) ?: return
        val n = awake.size
        if (lo + 1 > n) return
        val searchFloor = n.toLong() - minOf(tuning.onsetSearchEpochs, n)
        val earliest = maxOf(lo.toLong(), (notBefore ?: lo).toLong(), searchFloor)

        // The LAST maximal alternating run of ≥ K epochs, and WHY it ended (`i == n` is the step past
        // the data — the "regime never exited" terminator).
        var trustedEnd = -1
        var trustedLength = 0
        var trustedTerminator: CadenceStep? = null
        var found = false
        var start = lo
        for (i in (lo + 1)..n) {
            val terminator: CadenceStep? = if (i < n) cadence[i] else null
            if (terminator == CadenceStep.ALTERNATING) continue
            val end = i - 1
            if (end - start + 1 >= tuning.cadenceWakeQuietEpochs) {
                trustedEnd = end; trustedLength = end - start + 1; trustedTerminator = terminator; found = true
            }
            start = i
        }
        if (!found) return // the cadence never held for a night
        if (trustedTerminator != CadenceStep.VIOLATION) return // data edge or hole: no wake observed
        if (trustedLength > tuning.cadenceWakeMaxQuietEpochs) return // not a night

        val s = trustedEnd + 1
        if (!(s < n && s.toLong() > earliest)) return
        // HR NO-RETURN CONFIRMATION — an independent witness on the located cut.
        val suffixMin = swiftSequenceMin(smHR.subList(s, n))
        if (!(suffixMin > floor + margin)) return

        val candidate = awake.copyOf()
        for (i in s until n) candidate[i] = true
        if (sleepSpan(candidate, tuning.minConsolidatedSleepEpochs) == null) return
        candidate.copyInto(awake)
    }

    /** Centred rolling standard deviation (population) over a ±[half]-epoch window. */
    private fun rollingSD(xs: List<Double>, half: Int): List<Double> {
        val n = xs.size
        return List(n) { i ->
            val s = windowFirst(i, half)
            val e = windowLast(i, half, n)
            var sum = 0.0
            for (k in s..e) sum += xs[k]
            val mean = sum / (e - s + 1)
            var sq = 0.0
            for (k in s..e) sq += (xs[k] - mean) * (xs[k] - mean)
            sqrt(sq / (e - s + 1))
        }
    }

    /** Forward-then-backward fill of null gaps, so a sparse channel has no artificial jumps. */
    private fun <T> filledForward(xs: List<T?>): List<T?> {
        val out = xs.toMutableList()
        var last: T? = null
        for (i in out.indices) {
            val v = out[i]
            if (v != null) last = v else out[i] = last
        }
        var next: T? = null
        for (i in out.indices.reversed()) {
            val v = out[i]
            if (v != null) next = v else out[i] = next
        }
        return out
    }

    /**
     * Value at quantile [q] of a pre-sorted list (nearest rank, index rounded half away from zero and
     * clamped). 0 if empty. A non-finite index — which upstream traps on — reads the first value
     * (NaN) or the clamped end.
     */
    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val r = roundHalfAwayFromZero(q * (sorted.size - 1))
        val idx = when {
            r.isNaN() || r <= 0.0 -> 0
            r >= sorted.size - 1 -> sorted.size - 1
            else -> r.toInt()
        }
        return sorted[idx]
    }

    /** A `Duration` as upstream's `TimeInterval` (seconds as `Double`). */
    internal fun seconds(d: Duration): Double = d.seconds + d.nano / 1e9
}
