package io.github.opencircuit.ringkit

// Observability / alerting policy. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/SyncObservability.swift:11-114 (@ b1c2fdd).
//
// As an always-on tracker the app can fail silently — a throttled background task, a revoked
// Health grant, a ring left off the charger. This holds the PURE decision logic for when to warn the
// user (staleness + low-battery thresholds and the per-condition debounce) plus a bounded
// ring-buffer helper. Persistence and notification glue live in the app, not here.

import java.time.Duration
import java.time.Instant

/**
 * The silent-failure conditions worth a local notification. [rawValue] is upstream's raw string —
 * it keys the stored last-fired ledger, so it never changes. [alertsToFire][SyncAlertPolicy.alertsToFire]
 * returns survivors in declaration order: new cases are APPENDED at the end, never inserted.
 */
enum class SyncAlert(val rawValue: String) {
    /** No successful ring sync in more than [SyncAlertPolicy.staleSyncThreshold]. */
    NOT_SYNCED("notSynced"),

    /** Ring battery at or under [SyncAlertPolicy.lowBatteryThreshold]. */
    LOW_BATTERY("lowBattery"),

    /** Health access was granted before and is now off. */
    HEALTH_AUTH_LOST("healthAuthLost"),

    /**
     * The ring is connected and answering but has written no `0x4c` epoch history for hours — so
     * heart rate, SpO₂, HRV, respiratory rate and SLEEP are all being lost right now. 🟢 Added
     * upstream after this state ran 19 h 56 m and cost a whole night with no warning of any kind.
     */
    NOT_RECORDING("notRecording"),
}

/**
 * Thresholds + debounce for the silent-failure alerts. An immutable value: the app feeds it the
 * current observed state and the last time each alert fired, and it returns which alerts should fire
 * now — never the same condition twice inside [renotifyInterval]. Change a threshold with `copy()`.
 *
 * The three defaults are iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:35-37`):
 * 6 h stale, 15 % battery, 6 h re-notify.
 */
data class SyncAlertPolicy(
    /** No successful sync in this long → [SyncAlert.NOT_SYNCED]. */
    val staleSyncThreshold: Duration = Duration.ofHours(6),
    /** Ring battery at or under this percent → [SyncAlert.LOW_BATTERY]. */
    val lowBatteryThreshold: Int = 15,
    /** Minimum spacing between repeat notifications of the SAME condition (anti-spam). */
    val renotifyInterval: Duration = Duration.ofHours(6),
) {

    /**
     * The conditions currently true, ignoring debounce.
     * - `lastSuccessfulSync == null` is "no baseline yet": a brand-new user who has never synced is
     *   NOT nagged — staleness only fires relative to a prior success.
     * - `batteryPercent == null` (e.g. the session is torn down) skips the battery check rather than
     *   firing a false low-battery alert.
     * - [SyncAlert.HEALTH_AUTH_LOST] only fires when Health was authorized before and is now off.
     * - [SyncAlert.NOT_RECORDING] is decided by [EpochRecordingHealth] and passed in already
     *   classified; the default [EpochRecordingHealth.Status.Recording] can never raise it.
     *
     * Returns a new set on every call.
     */
    fun activeConditions(
        now: Instant,
        lastSuccessfulSync: Instant?,
        batteryPercent: Int?,
        healthAuthorized: Boolean,
        healthEverAuthorized: Boolean,
        recording: EpochRecordingHealth.Status = EpochRecordingHealth.Status.Recording,
    ): Set<SyncAlert> {
        val conditions = mutableSetOf<SyncAlert>()
        if (lastSuccessfulSync != null && Duration.between(lastSuccessfulSync, now) > staleSyncThreshold) {
            conditions += SyncAlert.NOT_SYNCED
        }
        if (batteryPercent != null && batteryPercent <= lowBatteryThreshold) {
            conditions += SyncAlert.LOW_BATTERY
        }
        if (healthEverAuthorized && !healthAuthorized) {
            conditions += SyncAlert.HEALTH_AUTH_LOST
        }
        if (recording.isStalled) {
            conditions += SyncAlert.NOT_RECORDING
        }
        return conditions
    }

    /**
     * Which alerts to actually post now: an active condition whose last notification is at least
     * [renotifyInterval] old (or never sent). Returned in [SyncAlert] declaration order.
     */
    fun alertsToFire(
        now: Instant,
        lastSuccessfulSync: Instant?,
        batteryPercent: Int?,
        healthAuthorized: Boolean,
        healthEverAuthorized: Boolean,
        lastFired: Map<SyncAlert, Instant>,
        recording: EpochRecordingHealth.Status = EpochRecordingHealth.Status.Recording,
    ): List<SyncAlert> {
        val active = activeConditions(now, lastSuccessfulSync, batteryPercent, healthAuthorized, healthEverAuthorized, recording)
        return SyncAlert.entries.filter { alert ->
            if (alert !in active) return@filter false
            val fired = lastFired[alert]
            fired == null || Duration.between(fired, now) >= renotifyInterval
        }
    }
}

/**
 * Append-and-cap helper for a fixed-size ring buffer (the background-task outcome log). Trims from
 * the FRONT so the newest [limit] entries survive. Never changes [to]; always returns a new list.
 */
object BoundedLog {
    fun <E> appendCapped(element: E, to: List<E>, limit: Int): List<E> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<E>(to.size + 1)
        out.addAll(to)
        out.add(element)
        return if (out.size > limit) ArrayList(out.subList(out.size - limit, out.size)) else out
    }
}
