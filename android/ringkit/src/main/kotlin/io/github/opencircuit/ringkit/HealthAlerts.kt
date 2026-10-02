package io.github.opencircuit.ringkit

// Local health-alert policy — the PURE decision layer shared by the high-HR / low-SpO₂ /
// elevated-HR-while-inactive alerts and the skin-temperature / fever notifications. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/HealthAlerts.swift (@ b1c2fdd), `:1-528`, plus the alert
// look-back from upstream's app (`HealthNotificationCenter.swift:207`, `:229-231`). `StepWindow`
// (`:182-189`), which the daily energy estimate also takes, keeps its place at the top.
//
// The ring has no vibration motor for these, so every alert is a phone notification. This file holds
// only the THRESHOLD + DE-DUPE + quiet-hours math; posting, persistence of the `lastFired` ledger and
// the settings store are the app's. Thresholds are user-configurable with sensible defaults.
//
// Shape notes: upstream's `TimeInterval` arguments are `Double` seconds here (a NaN or infinite one
// behaves as upstream's unless a KDoc says otherwise); `Calendar` arguments are a required `ZoneId`; a
// missing `lastFired` stamp is Foundation's `Date.distantPast` ([SleepEdit.DISTANT_PAST]), as upstream.
// Values compare their doubles by IEEE `==` (−0.0 equals 0.0, NaN never equals), as Swift's synthesized
// `Equatable` does.

import java.time.Instant
import java.time.ZoneId
import java.util.Collections

/**
 * One step-count snapshot's observation window and step delta, carrying the device's own timestamps.
 * A plain value, as upstream's struct: nothing here checks it (a window that ends before it starts
 * or a negative delta is accepted, and the energy estimate decides what it is worth).
 */
data class StepWindow(val start: Instant, val end: Instant, val delta: Int)

/**
 * Every user-facing health notification: HR / SpO₂, skin temperature and fever, the app-side
 * reminders, charging complete and the overnight-signals verdict. One enum = one de-dupe namespace.
 *
 * ORDER IS BEHAVIOUR: [NotificationGate.filter] returns survivors in declaration order, and the
 * de-dupe ledgers are keyed by [rawValue]. A new case is APPENDED with an explicit raw value, never
 * inserted, and no raw value is ever renamed.
 */
enum class HealthNotification(val rawValue: String) {
    // heart rate & blood oxygen
    HIGH_HR("highHR"),
    LOW_SPO2("lowSpO2"),
    ELEVATED_HR_INACTIVE("elevatedHRInactive"),

    // skin temperature (the four skin-temperature baseline flags) + fever
    SKIN_TEMP_RISE("skinTempRise"),
    SKIN_TEMP_DROP("skinTempDrop"),
    SKIN_TEMP_FLUCTUATION_RISE("skinTempFluctuationRise"),
    SKIN_TEMP_FLUCTUATION_DROP("skinTempFluctuationDrop"),
    FEVER("fever"),

    // app-side reminders
    SEDENTARY_REMINDER("reminder.sedentary"),
    WEAR_REMINDER("reminder.wear"),
    BEDTIME_REMINDER("reminder.bedtime"),

    // battery charging complete
    CHARGING_COMPLETE("battery.chargingComplete"),

    // the once-a-morning overnight-signals verdict — appended at the very end, never inserted
    HEADACHE_SIGNS("headache.signs"),
    ;

    companion object {
        /** The case whose raw value is exactly [rawValue] (Swift's `init?(rawValue:)`), else null. */
        fun fromRawValue(rawValue: String): HealthNotification? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

// MARK: - Quiet hours (shared DND window)

/**
 * A single nightly quiet-hours window, shared by every alert. Minutes are since local midnight, so a
 * window may wrap past midnight. Stored minutes are taken as they are (outside 0…1439 they compare as
 * plain numbers, as upstream). Immutable (upstream's `var`s change through `copy`).
 */
data class QuietHours(
    val enabled: Boolean = false,
    val startMinutes: Int = 22 * 60,
    val endMinutes: Int = 7 * 60,
) {
    /**
     * Whether [date] falls inside the quiet window, by its wall clock in [zone]. Disabled ⇒ never; a
     * zero-length window (start == end) is empty; a window may wrap past midnight; the end is exclusive.
     * An instant `java.time` cannot place in [zone] (the first and last year of `Instant`'s range lie
     * beyond the years a local date-time holds) is not quiet: the alert is not held on a clock that
     * cannot be read.
     */
    fun contains(date: Instant, zone: ZoneId): Boolean {
        if (!enabled || startMinutes == endMinutes) return false
        val m = zonedOrNull { date.atZone(zone).let { it.hour * 60 + it.minute } } ?: return false
        if (startMinutes < endMinutes) return m >= startMinutes && m < endMinutes // same-day window
        return m >= startMinutes || m < endMinutes // wraps past midnight
    }

    /**
     * How long the window suppresses for, in seconds — 0 when disabled or degenerate. Wrap-aware. The
     * difference is taken in 64 bits, as Swift's `Int`, so no pair of stored minutes wraps.
     *
     * Not decoration: a caller that derives its candidates from a rolling look-back must widen it by
     * this span, or everything the window suppresses ages out before the window reopens and is lost
     * rather than delayed — see [HealthAlertLookback.instantLookback].
     */
    val suppressedSpan: Double
        get() {
            if (!enabled || startMinutes == endMinutes) return 0.0
            val minutes = Math.floorMod(endMinutes.toLong() - startMinutes.toLong(), 1_440L)
            return (minutes * 60).toDouble()
        }
}

// MARK: - De-dupe / DND gate

/**
 * Decides whether a notification may fire NOW given quiet hours and an anti-spam backoff. Pure; the app
 * persists `lastFired` and posts the survivors. [renotifyInterval] is the minimum spacing, in seconds,
 * between repeats of the SAME notification.
 */
class NotificationGate(val renotifyInterval: Double = 2 * 3600.0) {

    /**
     * False inside quiet hours, and false while [n]'s last firing is less than [renotifyInterval]
     * before [now] — which includes a stamp in the FUTURE, as upstream: it holds until real time
     * passes it plus the backoff.
     */
    fun shouldFire(n: HealthNotification, now: Instant, lastFired: Map<HealthNotification, Instant>, quietHours: QuietHours, zone: ZoneId): Boolean {
        if (quietHours.contains(now, zone)) return false
        val fired = lastFired[n]
        if (fired != null && secondsBetween(fired, now) < renotifyInterval) return false
        return true
    }

    /** The subset of [candidates] allowed to fire now, once each, in [HealthNotification] declaration order. */
    fun filter(candidates: List<HealthNotification>, now: Instant, lastFired: Map<HealthNotification, Instant>, quietHours: QuietHours, zone: ZoneId): List<HealthNotification> {
        val set = candidates.toSet()
        return HealthNotification.entries.filter { it in set && shouldFire(it, now, lastFired, quietHours, zone) }
    }

    fun copy(renotifyInterval: Double = this.renotifyInterval): NotificationGate = NotificationGate(renotifyInterval)

    override fun equals(other: Any?): Boolean = other is NotificationGate && renotifyInterval == other.renotifyInterval

    override fun hashCode(): Int = ieeeHash(renotifyInterval)

    override fun toString(): String = "NotificationGate(renotifyInterval=$renotifyInterval)"
}

// MARK: - thresholds + evaluator

/** One blood-oxygen reading in whole percent with its time (callers convert from the stored 0…1 fraction). */
data class SpO2Reading(val percent: Int, val time: Instant)

/**
 * User-configurable thresholds for the HR / SpO₂ alerts. Defaults are conservative; each rule has its
 * own enable flag. Durations are seconds. Immutable (upstream's `var`s change through `copy`).
 *
 * [lowSpO2MinReadings]: how many readings at/below [lowSpO2Percent] must land inside [lowSpO2Window]
 * before the alert may fire (`1` is the kill-switch: the pre-persistence rule, byte for byte).
 * [lowSpO2MaxGap]: the widest spacing between two consecutive qualifying readings still counted as one
 * desaturation. [elevatedMaxGap]: the widest gap between readings still counted as one elevated run.
 */
class HealthAlertThresholds(
    val highHREnabled: Boolean = true,
    val highHRBpm: Int = 120,
    val lowSpO2Enabled: Boolean = true,
    val lowSpO2Percent: Int = 90,
    val lowSpO2MinReadings: Int = 2,
    val lowSpO2Window: Double = 30 * 60.0,
    val lowSpO2MaxGap: Double = 20 * 60.0,
    val elevatedHREnabled: Boolean = true,
    val elevatedHRBpm: Int = 100,
    val elevatedSustained: Double = 10 * 60.0,
    val elevatedMaxGap: Double = 5 * 60.0,
) {
    fun copy(
        highHREnabled: Boolean = this.highHREnabled,
        highHRBpm: Int = this.highHRBpm,
        lowSpO2Enabled: Boolean = this.lowSpO2Enabled,
        lowSpO2Percent: Int = this.lowSpO2Percent,
        lowSpO2MinReadings: Int = this.lowSpO2MinReadings,
        lowSpO2Window: Double = this.lowSpO2Window,
        lowSpO2MaxGap: Double = this.lowSpO2MaxGap,
        elevatedHREnabled: Boolean = this.elevatedHREnabled,
        elevatedHRBpm: Int = this.elevatedHRBpm,
        elevatedSustained: Double = this.elevatedSustained,
        elevatedMaxGap: Double = this.elevatedMaxGap,
    ): HealthAlertThresholds = HealthAlertThresholds(
        highHREnabled, highHRBpm, lowSpO2Enabled, lowSpO2Percent, lowSpO2MinReadings, lowSpO2Window, lowSpO2MaxGap,
        elevatedHREnabled, elevatedHRBpm, elevatedSustained, elevatedMaxGap,
    )

    private fun flags(): List<Any> = listOf(highHREnabled, highHRBpm, lowSpO2Enabled, lowSpO2Percent, lowSpO2MinReadings, elevatedHREnabled, elevatedHRBpm)

    private fun seconds(): DoubleArray = doubleArrayOf(lowSpO2Window, lowSpO2MaxGap, elevatedSustained, elevatedMaxGap)

    override fun equals(other: Any?): Boolean {
        if (other !is HealthAlertThresholds || flags() != other.flags()) return false
        val a = seconds()
        val b = other.seconds()
        return a.indices.all { a[it] == b[it] }
    }

    override fun hashCode(): Int = flags().hashCode() * 31 + seconds().map(::ieeeHash).hashCode()

    override fun toString(): String =
        "HealthAlertThresholds(highHREnabled=$highHREnabled, highHRBpm=$highHRBpm, lowSpO2Enabled=$lowSpO2Enabled, " +
            "lowSpO2Percent=$lowSpO2Percent, lowSpO2MinReadings=$lowSpO2MinReadings, lowSpO2Window=$lowSpO2Window, " +
            "lowSpO2MaxGap=$lowSpO2MaxGap, elevatedHREnabled=$elevatedHREnabled, elevatedHRBpm=$elevatedHRBpm, " +
            "elevatedSustained=$elevatedSustained, elevatedMaxGap=$elevatedMaxGap)"
}

/** One fired alert with the reading that triggered it: [value] is bpm for HR alerts, percent for SpO₂. */
class HealthAlertHit(val notification: HealthNotification, val value: Double, val time: Instant) {

    fun copy(notification: HealthNotification = this.notification, value: Double = this.value, time: Instant = this.time): HealthAlertHit =
        HealthAlertHit(notification, value, time)

    override fun equals(other: Any?): Boolean =
        other is HealthAlertHit && notification == other.notification && value == other.value && time == other.time

    override fun hashCode(): Int = (notification.hashCode() * 31 + ieeeHash(value)) * 31 + time.hashCode()

    override fun toString(): String = "HealthAlertHit(notification=$notification, value=$value, time=$time)"
}

// HR alerts intentionally have NO device-timestamp "freshness" gate: all-day HR reaches the phone via
// ~hourly background drains whose timestamps are routinely 30–60+ min old on arrival. De-dupe is the
// per-notification `lastFired` filter in `evaluate` (a crossing fires once on first sight and never
// replays), not the sample's age.

object HealthAlertEvaluator {

    /**
     * One activity interval for the HR gate, both ends inclusive — upstream's `(Date, Date)` tuple. A
     * reversed one (end before start) is accepted, as the tuple is, and covers no reading.
     */
    data class ActivityInterval(val start: Instant, val end: Instant)

    /** The worst (highest) HR reading at/above the threshold — the first of equal peaks — or null. */
    fun highHR(samples: List<HRSample>, thresholdBpm: Int): HRSample? =
        samples.filter { it.bpm >= thresholdBpm }.maxByOrNull { it.bpm }

    /**
     * The worst (lowest) reading across every PERSISTENT low-SpO₂ run, or null.
     *
     * A run is readings at/below [thresholdPercent] (above 0: 0 and below is the "no reading" sentinel)
     * joined while consecutive ones are at most [maxGap] seconds apart. It QUALIFIES when [minReadings]
     * of its readings fall inside one [window]-second span, and survives when it also carries a reading
     * strictly newer than [since] (null: Foundation's distant past). The depth is taken over every
     * surviving run, including readings older than [since] — the nadir, never a qualifying prefix.
     * [minReadings] ≤ 1 is the kill-switch: the worst fresh reading over the whole series. A negative
     * [window] qualifies no run (upstream traps; a span of negative length holds no reading).
     */
    fun lowSpO2(
        readings: List<SpO2Reading>,
        thresholdPercent: Int,
        minReadings: Int = 2,
        window: Double = 30 * 60.0,
        maxGap: Double = 20 * 60.0,
        since: Instant? = null,
    ): SpO2Reading? {
        val low = readings.filter { it.percent > 0 && it.percent <= thresholdPercent }.sortedBy { it.time }
        if (low.isEmpty()) return null
        val cut = since ?: SleepEdit.DISTANT_PAST
        if (minReadings <= 1) return lowest(low.filter { it.time > cut })

        // Split into runs of readings joined by <= maxGap.
        val runs = mutableListOf<List<SpO2Reading>>()
        var current = mutableListOf(low[0])
        for (i in 1 until low.size) {
            if (secondsBetween(low[i - 1].time, low[i].time) > maxGap) {
                runs += current
                current = mutableListOf()
            }
            current += low[i]
        }
        runs += current

        // A run QUALIFIES when `minReadings` of its readings fall inside one sliding `window`-long span.
        fun qualifies(run: List<SpO2Reading>): Boolean {
            if (run.size < minReadings) return false
            if (window < 0.0) return false // upstream walks past the run here (`HealthAlerts.swift:302`)
            var first = 0
            for (i in run.indices) {
                while (secondsBetween(run[first].time, run[i].time) > window) first += 1
                if (i - first + 1 >= minReadings) return true
            }
            return false
        }

        val live = runs.filter { run -> qualifies(run) && run.any { it.time > cut } }
        return lowest(live.flatten())
    }

    /** Lowest reading, ties broken by the EARLIEST time (then the first given); null for an empty series. */
    private fun lowest(readings: List<SpO2Reading>): SpO2Reading? =
        readings.minWithOrNull(compareBy<SpO2Reading> { it.percent }.thenBy { it.time })

    /**
     * The reading that COMPLETES a continuous run of HR ≥ [thresholdBpm] spanning ≥ [minDuration]
     * seconds, or null. Readings are taken in time order (a stable sort: same-instant readings keep
     * their given order); a reading below the threshold or a gap over [maxGap] seconds restarts the
     * run. The caller passes only non-exercising samples ([nonExercising]).
     */
    fun elevatedHRInactive(samples: List<HRSample>, thresholdBpm: Int, minDuration: Double, maxGap: Double = 5 * 60.0): HRSample? {
        val sorted = samples.sortedBy { it.start }
        var runStart: Instant? = null
        var prev: Instant? = null
        for (s in sorted) {
            if (s.bpm < thresholdBpm) {
                runStart = null
                prev = null
                continue
            }
            val p = prev
            if (p != null && secondsBetween(p, s.start) > maxGap) {
                runStart = s.start // gap too big — start a fresh run here
            } else if (runStart == null) {
                runStart = s.start
            }
            prev = s.start
            val rs = runStart
            if (rs != null && secondsBetween(rs, s.start) >= minDuration) return s
        }
        return null
    }

    /**
     * Default cap, in seconds, on a step snapshot's window width still treated as a discrete activity
     * burst. A WIDER window is the day-wide `[startOfDay, sampleDate]` fallback recorded on a fresh
     * baseline / day rollover, which must never become a gate interval.
     */
    const val MAX_ACTIVITY_WINDOW: Double = 30 * 60.0

    /**
     * The concurrent-activity intervals for the HR gate from step snapshots, dropping no-movement
     * windows (delta ≤ 0) and windows wider than [maxActivityWindow] seconds — SAFETY-CRITICAL: a
     * multi-hour fallback window would suppress every HR crossing since midnight.
     */
    fun activeStepIntervals(steps: List<StepWindow>, maxActivityWindow: Double = MAX_ACTIVITY_WINDOW): List<ActivityInterval> =
        steps.filter { it.delta > 0 && secondsBetween(it.start, it.end) <= maxActivityWindow }.map { ActivityInterval(it.start, it.end) }

    /**
     * How far BEFORE the ring's own activity-start marker its session is treated as exercise, in
     * seconds: the ring stamps the start when it has RECOGNISED the activity (≥ 10 min in), not when it
     * began.
     */
    const val RING_ACTIVITY_LEAD: Double = 10 * 60.0

    /**
     * Default recovery tail, in seconds, after an activity interval during which HR is still treated
     * as exercise (upstream's `nonExercising` default `pad`, named here so the look-back pin reads it).
     */
    const val RECOVERY_PAD: Double = 10 * 60.0

    /**
     * The ring's own activity sessions ([RingEventLog.activitySessions]) as activity intervals for
     * [nonExercising], each widened back by [lead] seconds. A NaN lead is 0 (upstream's NaN bound would
     * swallow every earlier reading — suppression on no evidence).
     */
    fun ringActivityIntervals(sessions: List<RingEventLog.ActivitySession>, lead: Double = RING_ACTIVITY_LEAD): List<ActivityInterval> {
        val back = if (lead.isNaN()) 0.0 else lead
        return sessions.map { ActivityInterval(checkNotNull(addingSeconds(it.start, -back)), it.end) }
    }

    /**
     * Drop HR samples whose DEVICE timestamp lies inside any activity interval `[start, end]` or within
     * [pad] seconds after its end (the recovery tail). Only ever SUPPRESSES on positive evidence: no
     * intervals → the series unchanged. A NaN pad is 0 (upstream's NaN bound would swallow every later
     * reading).
     */
    fun nonExercising(hr: List<HRSample>, activeIntervals: List<ActivityInterval>, pad: Double = RECOVERY_PAD): List<HRSample> {
        if (activeIntervals.isEmpty()) return hr
        val tail = if (pad.isNaN()) 0.0 else pad
        val spans = activeIntervals.map { it.start to checkNotNull(addingSeconds(it.end, tail)) }
        return hr.filter { sample -> spans.none { (from, to) -> sample.start >= from && sample.start <= to } }
    }

    /**
     * Evaluate the three rules and return the hits, in rule order (disabled rules are skipped).
     * [inactiveHR] is the series for the sustained-while-inactive rule; the instantaneous rules use [hr].
     * Each rule only sees readings strictly newer than its own `lastFired` stamp (missing: Foundation's
     * distant past); the SpO₂ rule takes the stamp as a cut applied after its runs are built, so a
     * desaturation straddling a previous fire is not split.
     */
    fun evaluate(
        hr: List<HRSample>,
        spo2: List<SpO2Reading>,
        inactiveHR: List<HRSample>,
        thresholds: HealthAlertThresholds,
        lastFired: Map<HealthNotification, Instant> = emptyMap(),
    ): List<HealthAlertHit> {
        val hits = mutableListOf<HealthAlertHit>()
        val highCut = lastFired[HealthNotification.HIGH_HR] ?: SleepEdit.DISTANT_PAST
        val elevatedCut = lastFired[HealthNotification.ELEVATED_HR_INACTIVE] ?: SleepEdit.DISTANT_PAST
        val freshHR = hr.filter { it.start > highCut }
        val freshInactiveHR = inactiveHR.filter { it.start > elevatedCut }

        if (thresholds.highHREnabled) {
            highHR(freshHR, thresholds.highHRBpm)?.let { hits += HealthAlertHit(HealthNotification.HIGH_HR, it.bpm.toDouble(), it.start) }
        }
        if (thresholds.lowSpO2Enabled) {
            lowSpO2(
                spo2, thresholds.lowSpO2Percent, thresholds.lowSpO2MinReadings, thresholds.lowSpO2Window, thresholds.lowSpO2MaxGap,
                since = lastFired[HealthNotification.LOW_SPO2],
            )?.let { hits += HealthAlertHit(HealthNotification.LOW_SPO2, it.percent.toDouble(), it.time) }
        }
        if (thresholds.elevatedHREnabled) {
            elevatedHRInactive(freshInactiveHR, thresholds.elevatedHRBpm, thresholds.elevatedSustained, thresholds.elevatedMaxGap)
                ?.let { hits += HealthAlertHit(HealthNotification.ELEVATED_HR_INACTIVE, it.bpm.toDouble(), it.start) }
        }
        return hits
    }
}

// MARK: - temperature / fever routing (skin-temperature flags + fever → notifications)

object TempFeverNotifications {

    /**
     * The skin-temperature / fever notifications — the ones that de-dupe per night. Single source of
     * truth for every classifier; adding a skin-temperature case means adding it here once. Read-only.
     */
    val NOTIFICATION_SET: Set<HealthNotification> = Collections.unmodifiableSet(
        linkedSetOf(
            HealthNotification.SKIN_TEMP_RISE, HealthNotification.SKIN_TEMP_DROP, HealthNotification.SKIN_TEMP_FLUCTUATION_RISE,
            HealthNotification.SKIN_TEMP_FLUCTUATION_DROP, HealthNotification.FEVER,
        ),
    )

    /**
     * Timezone-stable `yyyymmdd` day key for a night's start-of-day — `year * 10 000 + month * 100 +
     * day` of its calendar date in [zone] — used as the per-night ledger key instead of a raw instant,
     * which shifts under westward travel between two syncs of the same night. A `Long`, as Swift's
     * 64-bit `Int` (a year past 214 748 overflows 32 bits). The calendar is the proleptic Gregorian one
     * (Foundation's switches to the Julian before 1582-10-15). Null for an instant `java.time` cannot
     * place in [zone] (the first and last year of `Instant`'s range).
     */
    fun dayKey(night: Instant, zone: ZoneId): Long? {
        val d = CalendarDay.date(night, zone) ?: return null
        return d.year.toLong() * 10_000L + d.monthValue * 100L + d.dayOfMonth
    }

    /**
     * Map the four skin-temperature anomaly flags + the suspected-fever flag to the notifications they
     * raise, in declaration order. Pure routing; the de-dupe / quiet-hours gate and posting are the app's.
     */
    fun notifications(flags: SkinTempBaseline.AnomalyFlags, feverSuspected: Boolean): List<HealthNotification> {
        val out = mutableListOf<HealthNotification>()
        if (flags.abnormalRise) out += HealthNotification.SKIN_TEMP_RISE
        if (flags.abnormalDrop) out += HealthNotification.SKIN_TEMP_DROP
        if (flags.fluctuationRise) out += HealthNotification.SKIN_TEMP_FLUCTUATION_RISE
        if (flags.fluctuationDrop) out += HealthNotification.SKIN_TEMP_FLUCTUATION_DROP
        if (feverSuspected) out += HealthNotification.FEVER
        return out
    }

    /**
     * Per-NIGHT de-dupe: each of these flags pertains to ONE overnight summary, so once notified for a
     * night it must not re-fire on later syncs of that night (the 2 h backoff alone would re-raise it
     * all day). Keeps the candidates whose [night] key is strictly newer than the last night already
     * notified for that candidate (a candidate with no entry always passes); order and duplicates kept.
     */
    fun freshForNight(candidates: List<HealthNotification>, night: Long, lastNotifiedNight: Map<HealthNotification, Long>): List<HealthNotification> =
        candidates.filter { n ->
            val last = lastNotifiedNight[n]
            last == null || night > last
        }
}

// MARK: - the alert look-back (from upstream's app)

/**
 * How far back the instantaneous HR / SpO₂ alerts look for a threshold crossing. Port of upstream's
 * app code (`HealthNotificationCenter.swift:207`, `:229-231`), moved here so every consumer uses one
 * look-back — including the ring activity-event ledger, whose retention must outlast it.
 */
object HealthAlertLookback {

    /**
     * The floor, in seconds: 12 h, wide on purpose because background drains deliver timestamps 30–60+
     * min old. A FLOOR, not the window — see [instantLookback].
     */
    const val BASE_INSTANT_LOOKBACK: Double = 12 * 3600.0

    /**
     * The look-back actually used, in seconds: [BASE_INSTANT_LOOKBACK] widened by however long quiet
     * hours suppress for. Quiet hours DROP a candidate rather than queue it, so the oldest reading that
     * can be a candidate when the window closes (`quietStart − base`) must still be in the look-back at
     * `quietEnd = quietStart + span`. At most 12 h + 23 h 59 min.
     */
    fun instantLookback(quietHours: QuietHours): Double = BASE_INSTANT_LOOKBACK + quietHours.suppressedSpan
}
