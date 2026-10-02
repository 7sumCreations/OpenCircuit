package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepDetection.swift
// (@ b1c2fdd) `:61-615`: `Activity`, the `ActivityPeriod` value type, every detection threshold and
// the detector itself — sleep/active classification of a stillness timeline, ported upstream from
// openwhoop-algos `activity.rs`, plus RingConn's wear gate, HR gate and sleep-vitals rescue. The
// timeline sample types (`:17-59`) live in `SleepDetection.kt`.
//
// Two front-ends feed one core: `detectFromGravity` (a 3-axis gravity vector, openwhoop's original
// input) and `detectFromMotion` (RingConn's `0x4c` `[10:15]` per-30 s motion counts, PROTOCOL.md
// §5.3 — the real wiring). Finer Awake/Light/Deep/REM staging is not part of stillness detection.

import java.time.Duration
import java.time.Instant

/** Sleep or active (upstream `enum Activity`). */
enum class Activity { SLEEP, ACTIVE }

/**
 * One classified run of the activity timeline, `[start, end]`.
 *
 * A value: two periods with the same activity and span are equal, and [copy] makes a new one (Swift
 * `struct` semantics). Like upstream, [end] is not checked against [start]; a period that ends
 * before it starts has a negative [duration] and never qualifies as sleep.
 *
 * The companion holds every detection threshold — one home per number — and the detector.
 */
data class ActivityPeriod(val activity: Activity, val start: Instant, val end: Instant) {

    /** `end - start` (upstream `TimeInterval`). */
    val duration: Duration get() = Duration.between(start, end)

    val isActive: Boolean get() = activity == Activity.ACTIVE

    companion object {
        // Thresholds (from openwhoop, "notebook analysis").

        /** A run shorter than this is merged into its neighbours (upstream `activityChangeThreshold`). */
        internal val ACTIVITY_CHANGE_THRESHOLD: Duration = Duration.ofMinutes(15)

        /** Shortest block the detector will call a sleep (upstream `minSleepDuration`). */
        internal val MIN_SLEEP_DURATION: Duration = Duration.ofMinutes(60)

        /** Longest pause bridged inside one sleep (upstream `maxSleepPause`). */
        val MAX_SLEEP_PAUSE: Duration = Duration.ofMinutes(60)

        /** Gravity-delta stillness threshold, in g (upstream `gravityStillThreshold`). */
        internal const val GRAVITY_STILL_THRESHOLD: Float = 0.01f

        /** Span of the rolling stillness window (upstream `gravityWindowMinutes`). */
        internal const val GRAVITY_WINDOW_MINUTES: Int = 15

        /**
         * Share of a window's sub-samples that must read still for the window to count as still
         * (upstream `gravityStillFraction`). The raised-floor predicate in `BulkSleep` reuses it so it
         * and the detector it predicts cannot drift apart.
         */
        internal const val GRAVITY_STILL_FRACTION: Float = 0.70f

        /**
         * Widest gap between samples the detector treats as one run (upstream `gravityMaxGap`); also
         * `BulkSleep.contiguousFragments`' default split, so a fragment never holds a gap the detector
         * would itself split on.
         */
        val GRAVITY_MAX_GAP: Duration = Duration.ofMinutes(20)

        /**
         * Motion-count stillness threshold for the `0x4c` `[10:15]` channel (🟢 grounded: recovers
         * the captured night's in-bed window). Baseline `01` = still. The single home of this number.
         */
        const val MOTION_STILL_THRESHOLD: Float = 2f

        /**
         * Skin temperature (°C) at or above which a reading counts as WORN (🟢 ground-truthed upstream
         * against labelled charger captures: worn p05 28.4 °C, charger median 27.4 °C). The worn and
         * charger distributions OVERLAP, so no instantaneous threshold separates them; 28.0 sits at
         * the worn p05 and higher cut-offs were measured to be worse on both counts. It gates which
         * descriptor readings count as worn (`DeviceStatus.isWorn`) and the sleep wear gate.
         */
        const val WORN_MIN_TEMPERATURE_C: Double = 28.0

        /**
         * HR gate: a still sleep block whose MEDIAN heart rate exceeds the night's resting floor by
         * more than this is reclassified active — the motion detector is blind to a still-but-awake
         * evening, and the wear gate cannot catch it (the ring is worn). Conservative, so genuine
         * light/REM elevations are never rejected; like the wear gate it only REMOVES sleep.
         */
        const val AWAKE_HR_MARGIN_BPM: Int = 25

        /** Low percentile of the night's distinct HR levels used as its resting floor. */
        internal const val SLEEP_HR_FLOOR_PERCENTILE: Double = 0.10

        /** Minimum HR readings (globally, and inside a block) before the HR gate will act. */
        internal const val MIN_HR_SAMPLES_FOR_GATE: Int = 3

        /**
         * Sleep-vitals rescue, the symmetric counterpart of the HR gate: a moving-but-ASLEEP morning
         * reads as motion, so the night's tail is extended one epoch at a time while the trailing
         * window still looks like sleep-vitals sleep (HR near the floor AND the ring still emitting
         * sleep-vitals epochs). It only ever grows the tail from where motion found sleep.
         *
         * Trailing window the extension tests.
         */
        internal val RESCUE_WINDOW: Duration = Duration.ofMinutes(15)

        /** Minimum sleep-vitals epochs inside [RESCUE_WINDOW] for the tail to keep extending. */
        internal const val RESCUE_MIN_HRV_IN_WINDOW: Int = 2

        /** Step of the tail extension (the RingConn `0x4c` epoch is 150 s). */
        internal val RESCUE_EPOCH_STEP: Duration = Duration.ofSeconds(150)

        /**
         * How far past an epoch's START its LAST motion sub-sample sits. The motion timeline expands
         * each 150 s epoch into five samples at `start + k·30 s`, so the detector's sample-space gap
         * under-measures a real hole between RECORDS by exactly 120 s; the motion front-end subtracts
         * this from its gap budget so it breaks on every hole `BulkSleep.contiguousFragments` splits
         * on. At zero the correction (and the merge fence at holes) is off.
         */
        val MOTION_GAP_SUB_SAMPLE_CORRECTION: Duration = Duration.ofSeconds(120)

        /**
         * Target span of the rolling idle-floor window for DETECTION (upstream
         * `motionFloorWindowSeconds`, 30 min): long enough that a brief burst never lifts its own
         * floor.
         */
        internal val MOTION_FLOOR_WINDOW: Duration = Duration.ofMinutes(30)

        /**
         * Shorter floor window for per-epoch STAGING (upstream `motionFloorWindowSecondsStaging`): the
         * block is already established, so the floor should follow a posture-driven step quickly.
         */
        internal val MOTION_FLOOR_WINDOW_STAGING: Duration = Duration.ofMinutes(15)

        /** Low percentile used as the "idle" estimate inside the window (upstream `motionFloorPercentile`). */
        internal const val MOTION_FLOOR_PERCENTILE: Double = 0.10

        // Main-sleep selection

        /**
         * First sleep period longer than [MIN_SLEEP_DURATION], removed from [events] together with
         * everything before it — the caller's list is consumed exactly as Swift's `inout` array was.
         */
        fun findSleep(events: MutableList<ActivityPeriod>): ActivityPeriod? {
            // One scan, one bulk removal: draining with removeAt(0) would be quadratic on a long prefix.
            val at = events.indexOfFirst { it.activity == Activity.SLEEP && it.duration > MIN_SLEEP_DURATION }
            if (at < 0) {
                events.clear()
                return null
            }
            val event = events[at]
            events.subList(0, at + 1).clear()
            return event
        }

        /**
         * The main overnight sleep block as the CLUSTERED span of sleep periods: chain consecutive
         * sleep periods whose gap is shorter than [maxPause] (a brief awakening, not a true wake),
         * then return the longest cluster's `[firstStart, lastEnd]` when it spans more than
         * [MIN_SLEEP_DURATION]. Of equally long clusters the earliest wins. A clean single-block night
         * returns that block verbatim.
         */
        fun mainSleepBlock(periods: List<ActivityPeriod>, maxPause: Duration = MAX_SLEEP_PAUSE): ActivityPeriod? {
            val sleeps = periods.filter { it.activity == Activity.SLEEP }.sortedBy { it.start }
            if (sleeps.isEmpty()) return null
            val starts = ArrayList<Instant>()
            val ends = ArrayList<Instant>()
            for (s in sleeps) {
                if (ends.isNotEmpty() && Duration.between(ends.last(), s.start) < maxPause) {
                    ends[ends.lastIndex] = maxOf(ends.last(), s.end)
                } else {
                    starts += s.start
                    ends += s.end
                }
            }
            val best = starts.indices.maxByOrNull { Duration.between(starts[it], ends[it]) } ?: return null
            if (Duration.between(starts[best], ends[best]) <= MIN_SLEEP_DURATION) return null
            return ActivityPeriod(Activity.SLEEP, starts[best], ends[best])
        }

        // Front-ends

        /**
         * Sleep/active periods from a gravity-vector timeline. A missing vector reads as maximal
         * movement (active), as in openwhoop. Like upstream, the input is NOT sorted first.
         */
        fun detectFromGravity(history: List<GravitySample>): List<ActivityPeriod> {
            if (history.size < 2) return emptyList()
            val deltas = ArrayList<Float>(history.size)
            deltas += 0f
            for (i in 1 until history.size) {
                val a = history[i - 1].gravity
                val b = history[i].gravity
                deltas += if (a != null && b != null) {
                    val dx = a.x - b.x
                    val dy = a.y - b.y
                    val dz = a.z - b.z
                    kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                } else {
                    Float.MAX_VALUE
                }
            }
            return detect(history.map { it.time }, deltas, GRAVITY_STILL_THRESHOLD)
        }

        /**
         * Sleep/active periods from RingConn's per-30 s motion counts. Stillness is measured against
         * a rolling LOCAL idle floor ([motionAboveLocalFloor]), so a device that idles at `1` (Gen 2)
         * and one that idles at ~`15` and drifts (Gen 3) both read still at rest. Unworn samples
         * carry [Float.MAX_VALUE] (active). The timeline is sorted by time first.
         *
         * A NaN or -Inf sample reads as movement here; upstream reads it as still (Swift's
         * `max(0, NaN)` is 0) — see `PORTING.md`. No ring byte decodes to either value.
         */
        fun detectFromMotion(history: List<MotionSample>): List<ActivityPeriod> {
            if (history.size < 2) return emptyList()
            val sorted = history.sortedBy { it.time }
            val relative = motionAboveLocalFloor(sorted)
            return detect(
                sorted.map { it.time },
                relative,
                MOTION_STILL_THRESHOLD,
                maxGap = GRAVITY_MAX_GAP.minus(MOTION_GAP_SUB_SAMPLE_CORRECTION),
                fenceMergesAtHoles = MOTION_GAP_SUB_SAMPLE_CORRECTION > Duration.ZERO,
            )
        }

        /**
         * Motion detection with three post-passes, each conservative:
         *  • WEAR GATE — a sleep block whose median skin temperature reads below [wornMinC] (off-wrist
         *    or on the charger) becomes active. A block with no temperature coverage is kept.
         *  • HR GATE — a still block whose median HR runs more than [awakeHRMargin] above the night's
         *    resting floor (awake but still) becomes active. Too few readings: kept.
         *  • SLEEP-VITALS RESCUE — the main block's tail extends through a moving-but-asleep morning
         *    while HR stays near the floor and sleep-vitals epochs keep arriving ([sleepVitalTimes]).
         * The sample lists may be unordered and sparse; an empty list makes its pass a no-op.
         */
        fun detectFromMotion(
            history: List<MotionSample>,
            temperatureSamples: List<TemperatureSample>,
            heartRateSamples: List<HeartRateSample> = emptyList(),
            sleepVitalTimes: List<Instant> = emptyList(),
            wornMinC: Double = WORN_MIN_TEMPERATURE_C,
            awakeHRMargin: Int = AWAKE_HR_MARGIN_BPM,
        ): List<ActivityPeriod> {
            val base = detectFromMotion(history)
            val wearGated = wearGate(base, temperatureSamples, wornMinC)
            val hrGated = heartRateGate(wearGated, heartRateSamples, awakeHRMargin)
            return sleepVitalsRescue(hrGated, heartRateSamples, sleepVitalTimes, awakeHRMargin)
        }

        // Gates

        /**
         * The night's resting-floor HR: [SLEEP_HR_FLOOR_PERCENTILE] over the DISTINCT HR levels seen
         * (deduplicated so a long high-HR stretch cannot drag the floor up by count). Null with fewer
         * than [MIN_HR_SAMPLES_FOR_GATE] readings.
         */
        internal fun sleepHRFloor(hr: List<HeartRateSample>): Int? {
            if (hr.size < MIN_HR_SAMPLES_FOR_GATE) return null
            val levels = hr.map { it.bpm }.toSet().sorted()
            if (levels.isEmpty()) return null
            return levels[Math.round((levels.size - 1) * SLEEP_HR_FLOOR_PERCENTILE).toInt()]
        }

        private fun wearGate(periods: List<ActivityPeriod>, temperatureSamples: List<TemperatureSample>, wornMinC: Double): List<ActivityPeriod> {
            if (temperatureSamples.isEmpty()) return periods
            return periods.map { p ->
                if (p.activity != Activity.SLEEP) return@map p
                val inside = temperatureSamples
                    .filter { !it.time.isBefore(p.start) && !it.time.isAfter(p.end) }
                    .map { it.celsius }
                    .sorted()
                if (inside.isEmpty()) return@map p // no coverage → trust the motion verdict
                val median = inside[inside.size / 2]
                if (median < wornMinC) p.copy(activity = Activity.ACTIVE) else p
            }
        }

        private fun heartRateGate(periods: List<ActivityPeriod>, hr: List<HeartRateSample>, marginBPM: Int): List<ActivityPeriod> {
            val floor = sleepHRFloor(hr) ?: return periods
            // Long: floor + margin must not wrap (upstream's 64-bit Int would trap instead).
            val threshold = floor.toLong() + marginBPM
            return periods.map { p ->
                if (p.activity != Activity.SLEEP) return@map p
                val inside = hr.filter { !it.time.isBefore(p.start) && !it.time.isAfter(p.end) }.map { it.bpm }.sorted()
                if (inside.size < MIN_HR_SAMPLES_FOR_GATE) return@map p
                val median = inside[inside.size / 2]
                if (median > threshold) p.copy(activity = Activity.ACTIVE) else p
            }
        }

        private fun sleepVitalsRescue(
            periods: List<ActivityPeriod>,
            hr: List<HeartRateSample>,
            sleepVitalTimes: List<Instant>,
            marginBPM: Int,
        ): List<ActivityPeriod> {
            if (sleepVitalTimes.isEmpty()) return periods
            val floor = sleepHRFloor(hr) ?: return periods
            val block = mainSleepBlock(periods) ?: return periods
            val threshold = floor.toLong() + marginBPM // as in the HR gate: never wraps
            val hrv = sleepVitalTimes.sorted()

            // The active period that begins (within two epoch steps) at the main block's end.
            val step = RESCUE_EPOCH_STEP
            val tail = periods.firstOrNull {
                it.activity == Activity.ACTIVE && it.end.isAfter(block.end) &&
                    Duration.between(block.end, it.start).abs() <= step.multipliedBy(2)
            } ?: return periods

            fun windowIsSleeplike(endingAt: Instant): Boolean {
                val lo = endingAt.minus(RESCUE_WINDOW)
                val hrInWin = hr.filter { it.time.isAfter(lo) && !it.time.isAfter(endingAt) }.map { it.bpm }.sorted()
                if (hrInWin.size < MIN_HR_SAMPLES_FOR_GATE) return false
                val median = hrInWin[hrInWin.size / 2]
                val hrvInWin = hrv.count { it.isAfter(lo) && !it.isAfter(endingAt) }
                return median <= threshold && hrvInWin >= RESCUE_MIN_HRV_IN_WINDOW
            }

            var wakeEnd = block.end
            var t = block.end.plus(step)
            while (!t.isAfter(tail.end)) {
                if (windowIsSleeplike(t)) {
                    wakeEnd = t
                    t = t.plus(step)
                } else {
                    break
                }
            }
            if (!wakeEnd.isAfter(block.end)) return periods

            // Rewrite the tail: [tail.start, wakeEnd] becomes sleep; any remainder stays active.
            val out = ArrayList<ActivityPeriod>(periods.size + 1)
            for (p in periods) {
                if (p == tail) {
                    out += ActivityPeriod(Activity.SLEEP, p.start, wakeEnd)
                    if (p.end.isAfter(wakeEnd)) out += ActivityPeriod(Activity.ACTIVE, wakeEnd, p.end)
                } else {
                    out += p
                }
            }
            return out
        }

        // Core

        /** A run before merging; [afterHole] = it begins right after a data hole (upstream `Temp`). */
        private data class Run(val activity: Activity, val start: Instant, val end: Instant, val afterHole: Boolean = false) {
            val span: Duration get() = Duration.between(start, end)
        }

        /**
         * Classify a stillness-magnitude timeline into sleep/active runs (`deltas[i] <
         * stillThreshold` = still at sample i), faithful to openwhoop's `activity.rs`. [maxGap] is the
         * sample-space budget for a data hole; [fenceMergesAtHoles] marks each run that begins past a
         * hole so [filterMerge] cannot carry a boundary across it.
         */
        private fun detect(
            times: List<Instant>,
            deltas: List<Float>,
            stillThreshold: Float,
            maxGap: Duration = GRAVITY_MAX_GAP,
            fenceMergesAtHoles: Boolean = false,
        ): List<ActivityPeriod> {
            if (times.size != deltas.size || times.size < 2) return emptyList()

            // Median sample interval (whole seconds, truncated as Swift's Int(_:) does), bounded like openwhoop.
            val diffs = ArrayList<Long>()
            for (i in 1 until times.size) {
                val d = Duration.between(times[i - 1], times[i]).wholeSecondsTowardZero()
                if (d > 0 && d < 300) diffs += d
            }
            diffs.sort()
            val avgIntervalSecs = maxOf(1L, if (diffs.isEmpty()) 60L else diffs[diffs.size / 2])
            val windowSize = maxOf((GRAVITY_WINDOW_MINUTES * 60L) / avgIntervalSecs, 3L).toInt()

            // Rolling stillness classification (centred window).
            val n = deltas.size
            val isSleep = BooleanArray(n)
            val half = windowSize / 2
            for (i in 0 until n) {
                val start = if (i >= half) i - half else 0
                val end = minOf(i + half + 1, n)
                var still = 0
                for (j in start until end) if (deltas[j] < stillThreshold) still++
                isSleep[i] = still.toFloat() / (end - start).toFloat() >= GRAVITY_STILL_FRACTION
            }

            // Segment into runs; break on a class change or a data gap wider than maxGap.
            val runs = ArrayList<Run>()
            var runStart = 0
            var runFollowsHole = false
            for (i in 1..n) {
                val endOfData = i == n
                val classChange = !endOfData && isSleep[i] != isSleep[runStart]
                val gapBreak = !endOfData && Duration.between(times[i - 1], times[i]) > maxGap
                if (endOfData || classChange || gapBreak) {
                    runs += Run(if (isSleep[runStart]) Activity.SLEEP else Activity.ACTIVE, times[runStart], times[i - 1], runFollowsHole)
                    if (!endOfData) {
                        runStart = i
                        runFollowsHole = fenceMergesAtHoles && gapBreak
                    }
                }
            }
            return filterMerge(runs).map { ActivityPeriod(it.activity, it.start, it.end) }
        }

        /**
         * Merge runs shorter than [ACTIVITY_CHANGE_THRESHOLD] into their neighbours (openwhoop), with
         * one added rule: no merge carries a boundary across a data hole. A short run fenced by holes
         * on both sides is dropped. With no hole in the timeline the branch order is openwhoop's.
         */
        private fun filterMerge(input: List<Run>): List<Run> {
            if (input.isEmpty()) return emptyList()
            val activities = input.toMutableList()
            val merged = ArrayList<Run>()
            var i = 0
            while (i < activities.size) {
                val current = activities[i]
                if (current.span < ACTIVITY_CHANGE_THRESHOLD) {
                    val nextExists = i + 1 < activities.size
                    val acrossNextHole = nextExists && activities[i + 1].afterHole
                    val acrossPrevHole = current.afterHole
                    if (i > 0 && nextExists && !acrossNextHole && !acrossPrevHole &&
                        activities[i - 1].activity == activities[i + 1].activity && merged.isNotEmpty()
                    ) {
                        val prev = merged.removeAt(merged.lastIndex)
                        merged += Run(prev.activity, prev.start, activities[i + 1].end, prev.afterHole)
                        i += 1 // skip the next; it's merged
                    } else if (nextExists && !acrossNextHole) {
                        // The rewritten run now STARTS where `current` did, so it inherits its hole flag.
                        val next = activities[i + 1]
                        activities[i + 1] = Run(next.activity, current.start, next.end, current.afterHole || next.afterHole)
                    } else if (merged.isNotEmpty() && !acrossPrevHole) {
                        val prev = merged.removeAt(merged.lastIndex)
                        merged += Run(prev.activity, prev.start, current.end, prev.afterHole)
                    }
                } else {
                    merged += current
                }
                i += 1
            }
            return merged
        }

        // Rolling idle floor

        /**
         * Movement measured ABOVE a local, rolling idle floor.
         *
         * The motion channel idles at a device-dependent and even time-varying level (a still Gen-2
         * ring ~`1`, a still Gen-3 ring ~`15`, drifting 16→24→39 across a night as posture changes). At
         * each sample the low percentile of the motion inside a ~30-min window around it is subtracted:
         * a flat plateau at ANY level maps to ~0 (still), a genuine burst — short against the window —
         * stays above its floor. A constant-floor timeline maps to all-zero.
         */
        internal fun motionAboveLocalFloor(history: List<MotionSample>): List<Float> {
            val mags = history.map { it.movement }
            val floor = rollingLowPercentile(mags, history.map { it.time }, MOTION_FLOOR_WINDOW, MOTION_FLOOR_PERCENTILE)
            return mags.zip(floor) { m, f -> maxOf(0f, m - f) }
        }

        /**
         * Per-index low [percentile] over a window of [window] centred on each sample. [times] must be
         * sorted and exactly as long as [values] — a mismatch throws (upstream returned the values
         * unchanged, which would de-floor every sample to zero, i.e. read an unjudged timeline as still).
         *
         * Unworn [Float.MAX_VALUE] sentinels are excluded from the percentile (they would peg it high);
         * a window with NO worn sample returns a `0` floor, so a sentinel de-floors to itself (active)
         * rather than collapsing to still. The index is `(n − 1) · percentile` rounded half away from
         * zero, as Swift's `rounded()` does. O(n·w); n is one night of 30-s samples.
         */
        internal fun rollingLowPercentile(values: List<Float>, times: List<Instant>, window: Duration, percentile: Double): List<Float> {
            require(times.size == values.size) { "times (${times.size}) and values (${values.size}) must pair up" }
            val n = values.size
            if (n == 0) return emptyList()
            val half = window.dividedBy(2)
            val out = FloatArray(n)
            // Window bounds advance monotonically with i (times are sorted); each window is still
            // filtered and sorted, so the cost is O(n·w log w) — w is a few dozen 30-s samples.
            var lo = 0
            var hi = 0
            for (i in 0 until n) {
                val from = times[i].minus(half)
                val to = times[i].plus(half)
                while (lo < n && times[lo].isBefore(from)) lo++
                if (hi < lo) hi = lo
                while (hi < n && !times[hi].isAfter(to)) hi++
                val worn = values.subList(lo, hi).filter { it < Float.MAX_VALUE }.sorted()
                out[i] = if (worn.isEmpty()) 0f else worn[Math.round((worn.size - 1) * percentile).toInt()]
            }
            return out.toList()
        }
    }
}
