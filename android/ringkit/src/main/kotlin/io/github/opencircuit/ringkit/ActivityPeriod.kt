package io.github.opencircuit.ringkit

// PARTIAL port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepDetection.swift
// (@ b1c2fdd): the wear-gate constant at `:128`, which `DeviceStatus.isWorn` needs; the detection
// constants at `:79`, `:80`, `:83`, `:84`, `:87`, `:305`, `:313`; and the rolling idle floor
// `motionAboveLocalFloor` / `rollingLowPercentile` at `:326-361`, which the motion-channel
// selection in `BulkSleep` measures itself against.

import java.time.Duration
import java.time.Instant

/**
 * Home of the sleep-detection thresholds the history decoder and `DeviceStatus` read, and of the
 * rolling idle floor. Each number lives here once; nothing else restates it.
 *
 * The rest of upstream's `struct ActivityPeriod` (`SleepDetection.swift:63`: `activity`, `start`,
 * `end`) and the detector itself port with sleep detection. If that turns this into a
 * `data class`, these members move into its `companion object` and every call site
 * (`ActivityPeriod.WORN_MIN_TEMPERATURE_C`, …) stays unchanged.
 */
object ActivityPeriod {
    /**
     * Skin temperature (°C) at or above which a descriptor reading is treated as "worn" (🟡 proxy).
     * A worn Gen-2 ring reads ~30–34 °C; off-wrist / on the charger it falls toward room ambient.
     */
    const val WORN_MIN_TEMPERATURE_C: Double = 28.0

    /** Shortest block the detector will call a sleep (upstream `minSleepDuration`, from openwhoop). */
    internal val MIN_SLEEP_DURATION: Duration = Duration.ofMinutes(60)

    /** Longest pause the detector bridges inside one sleep (upstream `maxSleepPause`). */
    val MAX_SLEEP_PAUSE: Duration = Duration.ofMinutes(60)

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
     * Target span of the rolling idle-floor window for DETECTION (upstream
     * `motionFloorWindowSeconds`, 30 min): long enough that a brief burst never lifts its own
     * floor.
     */
    internal val MOTION_FLOOR_WINDOW: Duration = Duration.ofMinutes(30)

    /** Low percentile used as the "idle" estimate inside the window (upstream `motionFloorPercentile`). */
    internal const val MOTION_FLOOR_PERCENTILE: Double = 0.10

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
        // Window bounds advance monotonically with i (times are sorted), so this is ~O(n) amortised.
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
