package io.github.opencircuit.ringkit

// Local health-alert policy — the PURE decision layer shared by the high-HR / low-SpO₂ /
// elevated-HR-while-inactive alerts, the skin-temperature / fever notifications and the morning
// overnight-signals notification. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/HealthAlerts.swift (@ b1c2fdd), whole, plus the alert
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
     * intervals → the series unchanged (a copy: the caller editing its list later changes nothing here,
     * as with upstream's array). A NaN pad is 0 (upstream's NaN bound would swallow every later reading).
     */
    fun nonExercising(hr: List<HRSample>, activeIntervals: List<ActivityInterval>, pad: Double = RECOVERY_PAD): List<HRSample> {
        if (activeIntervals.isEmpty()) return hr.toList()
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

// MARK: - the morning overnight-signals notification

/**
 * The once-a-morning overnight-signals notification: a frozen [HeadacheSignals] verdict → at most one
 * notification candidate a day, and its copy.
 *
 * The copy is a MEASUREMENT, never a forecast: it reports which overnight signals drifted furthest from
 * the user's usual range, never a probability, a percentage or the word "headache". It carries no
 * quick-reply actions (buttons shown only on flagged mornings would collect labels conditioned on the
 * flag itself), and it fires at most once per calendar day ([freshForDay]), never on the 2 h backoff.
 */
object HeadacheSignsNotifications {

    /**
     * Membership, as its own set rather than a case added to [TempFeverNotifications.NOTIFICATION_SET]:
     * that set drives the per-NIGHT ledger and the disclaimer branch, and widening it by accident would
     * put this notification on the wrong ledger convention. Read-only.
     */
    val NOTIFICATION_SET: Set<HealthNotification> = Collections.unmodifiableSet(linkedSetOf(HealthNotification.HEADACHE_SIGNS))

    /** The notification category identifier, one constant for the registration and the posting site. It carries no actions. */
    const val CATEGORY_IDENTIFIER: String = "headache.signs"

    // Delivery window

    /**
     * The hard never-fire window, in minutes since local midnight: delivery only from [EARLIEST_MINUTES]
     * (inclusive) to [LATEST_MINUTES] (exclusive), whatever the user's quiet-hours setting — a summary of
     * a night that is already over has no business waking anyone. A verdict computed before the window
     * waits: the day ledger is still fresh, so the next pass inside the window delivers it. 🔴 PROVISIONAL.
     */
    const val EARLIEST_MINUTES: Int = 7 * 60
    const val LATEST_MINUTES: Int = 21 * 60

    /**
     * Whether [date]'s wall-clock time of day in [zone] (hour and minute; seconds ignored) is inside the
     * delivery window. An instant `java.time` cannot place in [zone] (the first and last year of
     * `Instant`'s range) is outside it: nothing is raised on a clock that cannot be read.
     */
    fun withinDeliveryWindow(date: Instant, zone: ZoneId): Boolean {
        val m = zonedOrNull { date.atZone(zone).let { it.hour * 60 + it.minute } } ?: return false
        return m >= EARLIEST_MINUTES && m < LATEST_MINUTES
    }

    // Per-DAY ledger

    /**
     * The timezone-stable `yyyymmdd` key of [day]'s calendar date in [zone] — the ONE implementation,
     * [TempFeverNotifications.dayKey] (null for an instant `java.time` cannot place in [zone]).
     */
    fun dayKey(day: Instant, zone: ZoneId): Long? = TempFeverNotifications.dayKey(day, zone)

    /**
     * Per-DAY de-dupe: the verdict describes ONE night, so once a day has been notified it must not
     * re-fire on any later sync that day (the shared 2 h backoff alone would re-raise it all day). The
     * per-night rule, [TempFeverNotifications.freshForNight], under its own name: candidates whose [day]
     * key is strictly newer than the one already notified pass; order and duplicates kept.
     */
    fun freshForDay(candidates: List<HealthNotification>, day: Long, lastNotifiedDay: Map<HealthNotification, Long>): List<HealthNotification> =
        TempFeverNotifications.freshForNight(candidates, night = day, lastNotifiedNight = lastNotifiedDay)

    // The decision

    /**
     * Whether this morning's FROZEN verdict may be raised as a notification candidate NOW: enabled, not
     * retired by the quality monitor, [band] flagged, not suppressed ([suppressedBy] withholds only the
     * notification — the score is still computed and shown), [frozenDayCount] (counted over the same
     * trailing band window) at least [HeadacheSignals.Tuning.minDaysForBanding] — the natural floor below
     * which no band exists — and [now] inside the delivery window in [zone]; then the per-day ledger.
     */
    fun candidates(
        enabled: Boolean,
        band: HeadacheSignals.Band?,
        suppressedBy: HeadacheSignals.Suppression?,
        frozenDayCount: Int,
        retired: Boolean,
        now: Instant,
        lastNotifiedDay: Map<HealthNotification, Long>,
        tuning: HeadacheSignals.Tuning = HeadacheSignals.Tuning(),
        zone: ZoneId,
    ): List<HealthNotification> {
        if (!enabled || retired || band != HeadacheSignals.Band.FLAGGED || suppressedBy != null) return emptyList()
        if (frozenDayCount < tuning.minDaysForBanding || !withinDeliveryWindow(now, zone)) return emptyList()
        val day = dayKey(now, zone) ?: return emptyList() // placeable: the window just read its wall clock
        return freshForDay(listOf(HealthNotification.HEADACHE_SIGNS), day, lastNotifiedDay)
    }

    // Copy

    /**
     * Plain words for one feature, for the "what drifted" sentence — lower-case (the copy builder
     * capitalises the first). They name the OBSERVABLE, never the analytic term.
     */
    fun plainName(feature: HeadacheSignals.Feature): String = when (feature) {
        HeadacheSignals.Feature.SLEEP_EFFICIENCY_DROP -> "sleep efficiency"
        HeadacheSignals.Feature.AROUSAL_LETDOWN -> "daytime heart rate"
        HeadacheSignals.Feature.HRV_DEVIATION -> "heart rate variability"
        HeadacheSignals.Feature.RESTING_HR_DEVIATION -> "resting heart rate"
        HeadacheSignals.Feature.SLEEP_FRAGMENTATION -> "time awake in bed"
        HeadacheSignals.Feature.SLEEP_DURATION_DEVIATION -> "sleep duration"
        HeadacheSignals.Feature.SCHEDULE_SHIFT -> "bedtime"
        HeadacheSignals.Feature.SKIN_TEMP_DEVIATION -> "skin temperature"
        HeadacheSignals.Feature.PERIMENSTRUAL -> "cycle phase"
    }

    /**
     * The RING-DERIVED features that drifted furthest, largest first, at most [limit] (below zero takes
     * none). [weighted] maps a feature to its WEIGHTED contribution — the share of the index it supplied.
     * Only shares above zero count (a NaN share never does); `perimenstrual` is never named (a calendar
     * lookup did not "drift"). Equal shares break by declaration order, so the same morning always words
     * itself the same way, whatever the map's order.
     */
    fun topSignals(weighted: Map<HeadacheSignals.Feature, Double>, limit: Int = 2): List<HeadacheSignals.Feature> =
        weighted.entries
            .filter { it.key.isRingDerived && it.value > 0.0 }
            .sortedWith { a, b ->
                when {
                    a.value == b.value -> a.key.ordinal.compareTo(b.key.ordinal)
                    a.value > b.value -> -1
                    else -> 1
                }
            }
            .take(maxOf(0, limit))
            .map { it.key }

    /**
     * The one phrase that means "overnight", shared so the title can ask whether every named signal is
     * nightly without a second copy of the string.
     */
    internal const val NIGHTLY_PHRASE: String = "last night"

    /**
     * The period [feature] was actually MEASURED over, as a trailing phrase: nightly for eight of the
     * nine; `arousalLetdown` compares yesterday's WAKING heart rate with the day before's.
     */
    fun timeframe(feature: HeadacheSignals.Feature): String =
        if (feature == HeadacheSignals.Feature.AROUSAL_LETDOWN) "over the past two days" else NIGHTLY_PHRASE

    /** A notification's title and body (upstream's `(title, body)` tuple). */
    data class Text(val title: String, val body: String)

    /**
     * The notification copy for the [topSignals] named (the first two; none gives the general sentence).
     * The timeframe FOLLOWS the signals named: two over the same period share one trailing phrase, a
     * mixed pair spells both out; the title says "recent" only when every named signal is daytime.
     * Display copy, verbatim from upstream; nothing here reads a locale.
     */
    fun copy(topSignals: List<HeadacheSignals.Feature>): Text {
        val drifted = " drifted furthest from your usual range"
        val subject = when (topSignals.size) {
            0 -> "Several of your overnight signals$drifted last night"
            1 -> sentenceCased(plainName(topSignals[0])) + drifted + " " + timeframe(topSignals[0])
            else -> {
                val first = topSignals[0]
                val second = topSignals[1]
                if (timeframe(first) == timeframe(second)) {
                    sentenceCased(plainName(first)) + " and " + plainName(second) + drifted + " " + timeframe(first)
                } else {
                    sentenceCased(plainName(first)) + " " + timeframe(first) + " and " + plainName(second) + " " + timeframe(second) + drifted
                }
            }
        }
        val allDaytime = topSignals.isNotEmpty() && topSignals.all { timeframe(it) != NIGHTLY_PHRASE }
        val title = if (allDaytime) "Your recent signals stood out" else "Last night was unusual for you"
        return Text(title, "$subject (estimate). That is what we measured — it is not a forecast.")
    }

    /**
     * [s] with its first character upper-cased, the rest unchanged. Locale-free, as Swift's
     * `uppercased()`: the machine's locale never changes the copy (a Turkish default would dot an `i`).
     */
    internal fun sentenceCased(s: String): String {
        if (s.isEmpty()) return s
        val n = Character.charCount(s.codePointAt(0))
        return s.substring(0, n).uppercase() + s.substring(n)
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
