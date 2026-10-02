package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/BatteryTTE.swift (@ b1c2fdd),
// whole.
//
// Port notes:
//  • Every `now: Date = Date()` is a required `Instant`: nothing here reads a clock. As upstream, the
//    time to empty and the time to full never read `now`; only the depletion date does (now + time).
//  • A battery percent outside 0…100 is not a reading. The two folds leave the history unchanged,
//    the estimates leave such a stored sample out, and only exactly 100 is "full". Upstream folds any
//    `Int` in: a 255 while not charging replaces the whole discharge history (measured on the pinned
//    build), and a stored 255 reads as "already full".
//  • A negative history cap keeps nothing, as a cap of 0 does; upstream traps on it (measured).
//  • Percent comparisons are 64-bit, as upstream's `Int`, so a corrupt stored percent never wraps.
//  • A depletion date past the end of `Instant`'s range saturates at `Instant.MAX` instead of
//    throwing; a Swift `Date` goes on (measured 2.94e18 s for samples 6e16 s apart).
//  • `maxAge` stays `Double` seconds, as upstream's `TimeInterval`: a NaN age keeps every sample (a
//    NaN cutoff compares false), and a cutoff past either end of `Instant`'s range saturates.
//  • Swift's `Codable` on `Sample` is not ported: the stored form belongs to the storage epic.

import java.time.Instant
import java.util.Collections

/**
 * Battery time-to-empty estimate from a rolling discharge history (#86).
 *
 * Algorithm (pure, no BLE):
 *   1. Sort samples by time and extract the strictly-DISCHARGING window (percent falls
 *      monotonically). Any rising run (charging) resets the window — we want a clean
 *      discharge slope. A flat run is skipped.
 *   2. Require drop ≥ 2 pp across the window (below that it's noise from the sensor's
 *      1 % granularity) and at least 2 samples.
 *   3. Compute rate = drop / elapsed_hours.
 *   4. Guard rate ≤ 50 %/hr — higher implies a charger was plugged/unplugged mid-window
 *      and the slope is garbage.
 *   5. TTE = last_percent / rate × 3600 seconds.
 *
 * All `now` parameters are explicit so tests are deterministic.
 */
object BatteryTTE {

    /** One battery reading: the ring's percent at an instant. Compares by value, as upstream's struct. */
    data class Sample(val percent: Int, val at: Instant)

    /** The readable percents; anything outside is not a reading. */
    internal val READABLE_PERCENT: IntRange = 0..100

    /** Upstream's defaults: keep the 60 most recent samples, 14 days of discharge, 6 hours of charge. */
    internal const val DEFAULT_CAP: Int = 60
    internal const val DISCHARGE_MAX_AGE_SECONDS: Double = 14.0 * 86_400
    internal const val CHARGE_MAX_AGE_SECONDS: Double = 6.0 * 3_600

    /** A rise (discharge fold) or drop (charge fold) of at least this many points restarts the history. */
    internal const val RESET_STEP: Long = 3

    /** The smallest change across a window that is a slope rather than 1 % sensor noise. */
    internal const val MIN_CHANGE: Double = 2.0

    /** Above these rates the slope is a charger event (discharge) or noise (charge). */
    internal const val MAX_DISCHARGE_PER_HOUR: Double = 50.0
    internal const val MAX_CHARGE_PER_HOUR: Double = 300.0

    // MARK: - Robust history accumulation (#86 robustness)

    /**
     * Fold one battery reading into a persisted discharge history so the estimate survives
     * reconnects/relaunches and stays clean (#86). Keeps the history a tidy, monotonically
     * non-increasing discharge run by using the DECODED charging byte (#61) to disambiguate a
     * real charge from sensor noise:
     *   • `charging` true → a charge invalidates any discharge slope: reset the baseline to `percent`.
     *   • lower `percent` → a genuine discharge step: append (first-seen time of each % = clean slope).
     *   • higher by ≥ 3 pp while NOT charging → a charge we missed between frames: reset baseline.
     *   • higher by 1–2 pp, or equal → sensor jitter at 1 % granularity: ignore (don't reset, don't grow).
     * Then prune by age and cap (keep the most recent). Pure + deterministic for tests.
     *
     * A [percent] outside 0…100 is not a reading: the history comes back unchanged.
     */
    fun record(
        history: List<Sample>,
        percent: Int,
        at: Instant,
        charging: Boolean,
        cap: Int = DEFAULT_CAP,
        maxAge: Double = DISCHARGE_MAX_AGE_SECONDS,
    ): List<Sample> {
        if (percent !in READABLE_PERCENT) return Collections.unmodifiableList(history.toList())
        val last = history.lastOrNull()
        val h: List<Sample> = when {
            charging -> listOf(Sample(percent, at))
            last == null -> listOf(Sample(percent, at))
            percent < last.percent -> history + Sample(percent, at)
            percent.toLong() - last.percent >= RESET_STEP -> listOf(Sample(percent, at))
            // small rise (1–2 pp) or equal while not charging → noise: leave history untouched.
            else -> history.toList()
        }
        return pruned(h, at, cap, maxAge)
    }

    // MARK: - Core estimate

    /**
     * Seconds until the battery reaches 0 %, or null when the window is too noisy /
     * too small / actively charging / implausible. A stored sample outside 0…100 is left out.
     * [now] is not read (upstream's signature keeps it for the depletion date).
     */
    fun timeToEmpty(samples: List<Sample>, @Suppress("UNUSED_PARAMETER") now: Instant): Double? {
        val readable = samples.filter { it.percent in READABLE_PERCENT }
        if (readable.size < 2) return null

        // Build the longest trailing strictly-discharging window. The sort is stable, as Swift's:
        // samples sharing an instant keep their input order (measured on the pinned build).
        val window = ArrayList<Sample>()
        for (s in readable.sortedBy { it.at }) {
            val prev = window.lastOrNull()
            if (prev == null) {
                window += s
            } else if (s.percent < prev.percent) {
                window += s
            } else if (s.percent > prev.percent) {
                // Rising sample (charging) — reset the window; start fresh at this point.
                window.clear()
                window += s
            }
            // Flat (equal) — skip; neither confirms discharge nor resets.
        }

        if (window.size < 2) return null
        val first = window.first()
        val last = window.last()
        val drop = (first.percent.toLong() - last.percent).toDouble()
        val elapsed = secondsBetween(first.at, last.at)
        if (!(drop >= MIN_CHANGE && elapsed > 0)) return null

        val ratePerHour = drop / (elapsed / 3_600)
        if (!(ratePerHour <= MAX_DISCHARGE_PER_HOUR)) return null // implausible — charger event

        val tte = last.percent.toDouble() / ratePerHour * 3_600
        return if (tte > 0) tte else null
    }

    /**
     * The estimated wall-clock time at which the battery reaches 0 %, or null. A date past the end of
     * `Instant`'s range saturates at [Instant.MAX].
     */
    fun estimatedDepletionDate(samples: List<Sample>, now: Instant): Instant? {
        val tte = timeToEmpty(samples, now) ?: return null
        return addingSeconds(now, tte) // tte is positive and finite, so never null
    }

    // MARK: - Time to FULL (charging, #61-enabled)

    /**
     * Seconds until the battery reaches [target] % (default 100), from a CHARGING history —
     * the mirror of [timeToEmpty]. Builds the longest trailing strictly-RISING window (a falling
     * sample resets it), requires a ≥ 2 pp rise over ≥ 2 samples, then extrapolates the slope.
     * Returns 0 when already at/above target, or null when too noisy / too small / implausibly
     * fast (charging is quick — guard a generous 300 %/hr). Feed it the dedicated charge history
     * from [recordCharge], not the discharge history. A stored sample outside 0…100 is left out;
     * [target] is the caller's and is not checked, as upstream. [now] is not read.
     */
    fun timeToFull(samples: List<Sample>, @Suppress("UNUSED_PARAMETER") now: Instant, target: Int = 100): Double? {
        val readable = samples.filter { it.percent in READABLE_PERCENT }
        if (readable.size < 2) return null
        val window = ArrayList<Sample>()
        for (s in readable.sortedBy { it.at }) {
            val prev = window.lastOrNull()
            if (prev == null) {
                window += s
            } else if (s.percent > prev.percent) {
                window += s
            } else if (s.percent < prev.percent) {
                window.clear() // falling (unplugged) — reset
                window += s
            }
            // flat — skip
        }
        if (window.size < 2) return null
        val first = window.first()
        val last = window.last()
        if (last.percent >= target) return 0.0
        val rise = (last.percent.toLong() - first.percent).toDouble()
        val elapsed = secondsBetween(first.at, last.at)
        if (!(rise >= MIN_CHANGE && elapsed > 0)) return null
        val ratePerHour = rise / (elapsed / 3_600)
        if (!(ratePerHour <= MAX_CHARGE_PER_HOUR)) return null // implausible — noise
        return (target.toLong() - last.percent).toDouble() / ratePerHour * 3_600
    }

    /**
     * Fold one reading into the CHARGING history (mirror of [record]). Only meaningful while the
     * decoded charging byte (#61) is set — when not charging it returns `[]`, so a stale charge
     * slope can't linger past unplugging. Appends genuine rises; a ≥ 3 pp drop resets the baseline
     * (brief contact loss); equal / tiny drops are ignored. Prunes by age (charges are short) + cap.
     *
     * A [percent] outside 0…100 while charging is not a reading: the history comes back unchanged.
     */
    fun recordCharge(
        history: List<Sample>,
        percent: Int,
        at: Instant,
        charging: Boolean,
        cap: Int = DEFAULT_CAP,
        maxAge: Double = CHARGE_MAX_AGE_SECONDS,
    ): List<Sample> {
        if (!charging) return emptyList()
        if (percent !in READABLE_PERCENT) return Collections.unmodifiableList(history.toList())
        val last = history.lastOrNull()
        val h: List<Sample> = when {
            last == null -> listOf(Sample(percent, at))
            percent > last.percent -> history + Sample(percent, at)
            last.percent.toLong() - percent >= RESET_STEP -> listOf(Sample(percent, at))
            // equal or tiny drop → ignore.
            else -> history.toList()
        }
        return pruned(h, at, cap, maxAge)
    }

    /**
     * True when the battery just reached 100 % WHILE the ring is inferred to be on the
     * charger AND we haven't already fired a "full" notification for this charge cycle.
     * Callers set `wasFull = false` when percent drops below 100 to re-arm. A percent above 100
     * is not a reading and never fires.
     */
    fun justReachedFull(percent: Int, inferredCharging: Boolean, wasFull: Boolean): Boolean =
        percent in READABLE_PERCENT && percent >= 100 && inferredCharging && !wasFull

    /**
     * Upstream's prune: drop samples older than [maxAge] before [at] (a NaN cutoff drops nothing, as a
     * NaN `Date` compares false), then keep the most recent [cap] (none for a cap of 0 or below).
     */
    private fun pruned(h: List<Sample>, at: Instant, cap: Int, maxAge: Double): List<Sample> {
        val cutoff = addingSeconds(at, -maxAge)
        val aged = if (cutoff == null) h else h.filter { !it.at.isBefore(cutoff) }
        val kept = if (aged.size > cap) aged.takeLast(maxOf(cap, 0)) else aged
        return Collections.unmodifiableList(kept.toList())
    }
}
