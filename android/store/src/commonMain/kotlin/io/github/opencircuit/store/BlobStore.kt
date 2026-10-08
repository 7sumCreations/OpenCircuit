package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.BatteryTTE
import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.CursorSpan
import io.github.opencircuit.ringkit.EpochArchive
import io.github.opencircuit.ringkit.HealthNotification
import io.github.opencircuit.ringkit.HistoricalSportFrame
import io.github.opencircuit.ringkit.HistoryFrameCapture
import io.github.opencircuit.ringkit.RingActivityEventLedger
import io.github.opencircuit.ringkit.RingAlarm
import io.github.opencircuit.ringkit.SyncAlert
import io.github.opencircuit.ringkit.WorkoutSessionSnapshot
import io.github.opencircuit.store.codec.BatterySamplesCodec
import io.github.opencircuit.store.codec.CursorSpanListCodec
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.EnergyLedgerDayCodec
import io.github.opencircuit.store.codec.EpochArchiveCodec
import io.github.opencircuit.store.codec.HistoryFrameCaptureCodec
import io.github.opencircuit.store.codec.LastFiredLedgerCodec
import io.github.opencircuit.store.codec.RingActivityEventLedgerCodec
import io.github.opencircuit.store.codec.RingAlarmCodec
import io.github.opencircuit.store.codec.SportSampleListCodec
import io.github.opencircuit.store.codec.StrandedCountersCodec
import io.github.opencircuit.store.codec.WorkoutSessionSnapshotCodec
import java.time.Instant
import java.time.LocalDate

// Typed access to the values upstream keeps in `UserDefaults`, here in the `store_kv` table of the
// same database as the samples (ios/OpenCircuit/Store/EpochArchiveStore.swift,
// ObservabilityStore.swift, HealthNotificationCenter.swift, RingAlarmController.swift,
// WorkoutSessionManager.swift, BLE/RingSession.swift, Health/HealthKitWriter.swift @ b1c2fdd).
//
// Differences, each deliberate:
// - A per-ring value's key ends `/<ring id>` (upstream: `.<peripheral UUID>`).
// - A stored alarm this build cannot read is kept and reported, never reset to the default alarm.
// - The energy ledger is one value per day, and an unreadable day is reported as unreadable.
// - The epoch archive and its drain facts are one value; records reach it only through a merge
//   bounded by `notAfter`; a drain fact that cannot be read reads as upstream's default.
// - Every save takes `now` (stored as the row's update time) instead of reading the clock.
// Not given a home: the history-sync evidence list (its wrapper type belongs to the sync epic; the
// trace codec is here), the pending sleep segments (their publish-as-a-pair rule belongs to the
// sleep write flow; the segment codec is here), and `UnattributedPageBuffer` (in memory upstream,
// RingSession.swift:499).

/**
 * The stored values that are not sample rows: one key each, named as upstream names its
 * `UserDefaults` key. Each loads through its codec and fails closed as upstream does when the
 * stored text cannot be read — a ledger, capture, history or span list loads empty, a workout
 * snapshot loads as none — except the alarm and the energy-ledger day, which report the
 * unreadable value instead (see [loadAlarm], [loadEnergyLedgerDay]).
 *
 * Every save joins the caller's transaction when one is open (`db.withWriteTransaction { … }`), so
 * a value can commit or roll back with the samples and cursors written in it; outside one, each
 * save is its own transaction. A [ringId] must be non-empty and contain no `/`.
 */
class BlobStore internal constructor(private val db: StoreDatabase, private val kv: KvDao) {
    constructor(db: StoreDatabase) : this(db, db.kvDao())

    enum class BatteryHistory(internal val keyName: String) {
        DISCHARGE("battery.tteHistory.v1"),
        CHARGE("battery.chargeHistory.v1"),
    }

    /** The detected-workout spans the wearer already saved or dismissed, and those already notified. */
    enum class WorkoutSpans(internal val keyName: String) {
        RESOLVED("workout.automaticDetection.resolved.v2"),
        NOTIFIED("workout.automaticDetection.notified.v2"),
    }

    /** What a merge stored: the archive's records, and how many records were dated after the bound. */
    data class ArchiveMerge(val records: List<BulkRecord>, val droppedAfterBound: Int)

    // Activity-event ledger, frame capture, battery histories: empty when unreadable.

    suspend fun loadActivityEvents(): RingActivityEventLedger =
        load(ACTIVITY_EVENTS, RingActivityEventLedgerCodec::decode) ?: RingActivityEventLedger()

    suspend fun saveActivityEvents(ledger: RingActivityEventLedger, now: Instant) =
        put(ACTIVITY_EVENTS, RingActivityEventLedgerCodec.encode(ledger), now)

    suspend fun loadHistoryCapture(ringId: String): HistoryFrameCapture =
        load(perRing(HISTORY_CAPTURE, ringId), HistoryFrameCaptureCodec::decode) ?: HistoryFrameCapture()

    suspend fun saveHistoryCapture(ringId: String, capture: HistoryFrameCapture, now: Instant) =
        put(perRing(HISTORY_CAPTURE, ringId), HistoryFrameCaptureCodec.encode(capture), now)

    suspend fun loadBatteryHistory(ringId: String, history: BatteryHistory): List<BatteryTTE.Sample> =
        load(perRing(history.keyName, ringId), BatterySamplesCodec::decode) ?: emptyList()

    suspend fun saveBatteryHistory(ringId: String, history: BatteryHistory, samples: List<BatteryTTE.Sample>, now: Instant) =
        put(perRing(history.keyName, ringId), BatterySamplesCodec.encode(samples), now)

    // Last-fired ledgers: empty when unreadable (upstream's failed dictionary cast).

    suspend fun loadSyncAlertLastFired(): Map<SyncAlert, Instant> =
        load(SYNC_ALERT_LAST_FIRED) { LastFiredLedgerCodec.decode(it, SyncAlert.entries, SyncAlert::rawValue) } ?: emptyMap()

    suspend fun saveSyncAlertLastFired(ledger: Map<SyncAlert, Instant>, now: Instant) =
        put(SYNC_ALERT_LAST_FIRED, LastFiredLedgerCodec.encode(ledger, SyncAlert::rawValue), now)

    suspend fun loadHealthLastFired(): Map<HealthNotification, Instant> =
        load(HEALTH_LAST_FIRED) { LastFiredLedgerCodec.decode(it, HealthNotification.entries, HealthNotification::rawValue) }
            ?: emptyMap()

    suspend fun saveHealthLastFired(ledger: Map<HealthNotification, Instant>, now: Instant) =
        put(HEALTH_LAST_FIRED, LastFiredLedgerCodec.encode(ledger, HealthNotification::rawValue), now)

    suspend fun loadHealthLastNight(): Map<HealthNotification, Long> =
        load(HEALTH_LAST_NIGHT) { LastFiredLedgerCodec.decodeNights(it, HealthNotification.entries, HealthNotification::rawValue) }
            ?: emptyMap()

    suspend fun saveHealthLastNight(ledger: Map<HealthNotification, Long>, now: Instant) =
        put(HEALTH_LAST_NIGHT, LastFiredLedgerCodec.encodeNights(ledger, HealthNotification::rawValue), now)

    // Detected-workout bookkeeping and the stranded-epoch ledger: empty when unreadable.

    suspend fun loadWorkoutSpans(ringId: String, spans: WorkoutSpans): List<CursorSpan> =
        load(perRing(spans.keyName, ringId), CursorSpanListCodec::decode) ?: emptyList()

    suspend fun saveWorkoutSpans(ringId: String, spans: WorkoutSpans, ledger: List<CursorSpan>, now: Instant) =
        put(perRing(spans.keyName, ringId), CursorSpanListCodec.encode(ledger), now)

    suspend fun loadSportSamples(ringId: String): List<HistoricalSportFrame.Sample> =
        load(perRing(SPORT_SAMPLES, ringId), SportSampleListCodec::decode) ?: emptyList()

    suspend fun saveSportSamples(ringId: String, samples: List<HistoricalSportFrame.Sample>, now: Instant) =
        put(perRing(SPORT_SAMPLES, ringId), SportSampleListCodec.encode(samples), now)

    suspend fun loadStrandedCounters(ringId: String): Set<Long> =
        load(perRing(STRANDED_COUNTERS, ringId), StrandedCountersCodec::decode) ?: emptySet()

    suspend fun saveStrandedCounters(ringId: String, counters: Set<Long>, now: Instant) =
        put(perRing(STRANDED_COUNTERS, ringId), StrandedCountersCodec.encode(counters), now)

    // Workout snapshot: none when absent or unreadable (upstream `decoded(from:)`).

    suspend fun loadWorkoutSnapshot(): WorkoutSessionSnapshot? = load(WORKOUT_SNAPSHOT, WorkoutSessionSnapshotCodec::decode)

    /**
     * Saves [snapshot]; false, and the stored snapshot left as it was, when its energy is NaN or
     * infinite (upstream's `encoded()` is nil and the save is skipped).
     */
    suspend fun saveWorkoutSnapshot(snapshot: WorkoutSessionSnapshot, now: Instant): Boolean =
        putIfEncoded(WORKOUT_SNAPSHOT, WorkoutSessionSnapshotCodec.encode(snapshot), now)

    suspend fun clearWorkoutSnapshot() = kv.delete(WORKOUT_SNAPSHOT)

    // Alarm: an unreadable stored alarm is reported and kept.

    /** The stored alarm. Reading never writes, so an unreadable alarm stays as it was stored. */
    suspend fun loadAlarm(): StoredAlarm {
        val raw = kv.get(ALARM)?.value ?: return StoredAlarm.Absent
        return when (val d = RingAlarmCodec.decode(raw)) {
            is Decoded.Readable -> StoredAlarm.Readable(d.value)
            is Decoded.Unreadable -> StoredAlarm.Unreadable(d.raw, d.reason)
        }
    }

    /**
     * Saves [alarm], replacing whatever was stored, readable or not; false, and the stored alarm
     * left as it was, when its burst spacing is NaN or infinite (upstream's setter skips the save).
     */
    suspend fun saveAlarm(alarm: RingAlarm, now: Instant): Boolean = putIfEncoded(ALARM, RingAlarmCodec.encode(alarm), now)

    // Energy ledger: one value per local date; an unreadable day is reported, never read as absent.

    /**
     * The ledger stored for [date]: null when none is stored, [Decoded.Unreadable] when one is
     * stored but cannot be read — including a readable ledger whose own day is not [date]. A caller
     * must not plan an unreadable day as a fresh one: that would pay the whole day again.
     */
    suspend fun loadEnergyLedgerDay(date: LocalDate): Decoded<EnergyLedgerDay>? {
        val raw = kv.get(energyKey(date))?.value ?: return null
        val d = EnergyLedgerDayCodec.decode(raw)
        if (d is Decoded.Readable && d.value.localDate != date) {
            return Decoded.Unreadable(raw, "stored for ${d.value.localDate}, not $date")
        }
        return d
    }

    /** Saves [day] under its own local date; false, and nothing written, when a value could not be read back. */
    suspend fun saveEnergyLedgerDay(day: EnergyLedgerDay, now: Instant): Boolean =
        putIfEncoded(energyKey(day.localDate), EnergyLedgerDayCodec.encode(day), now)

    // Epoch archive: records only through the bounded merge.

    /** The stored archive and its drain facts; [StoredEpochArchive.EMPTY] when none or unreadable. */
    suspend fun loadEpochArchive(ringId: String): StoredEpochArchive =
        load(perRing(EPOCH_ARCHIVE, ringId), EpochArchiveCodec::decode) ?: StoredEpochArchive.EMPTY

    /**
     * Merges [incoming] into the stored archive and stores the result (`EpochArchive.merge`: dedup
     * by counter, the incoming copy wins, prune to the retention window), keeping the drain facts.
     * The window keeps every night not yet staged: from 30 h before the stored
     * [EpochArchiveMarks.stagedThrough], at least 30 h, at most 14 days, and the 14 days while
     * nothing is staged (`EpochArchive.mergeKeepingUnstaged`, PORTING.md D-270; upstream keeps a
     * flat 30 h). Every record dated after [notAfter] is dropped from the result and from the
     * retention anchor; pass the current time plus a clock-skew allowance. An unreadable stored
     * archive is replaced by the merge of [incoming] alone.
     */
    suspend fun mergeEpochArchive(ringId: String, incoming: List<BulkRecord>, notAfter: Instant, now: Instant): ArchiveMerge {
        val key = perRing(EPOCH_ARCHIVE, ringId)
        return db.withWriteTransaction {
            val stored = load(key, EpochArchiveCodec::decode) ?: StoredEpochArchive.EMPTY
            val merged = EpochArchive.mergeKeepingUnstaged(stored.records, incoming, stored.marks.stagedThrough, notAfter)
            // A record's date is its counter's, so one record per counter decides.
            val dropped = (stored.records + incoming).distinctBy { it.counter }.count { it.date().isAfter(notAfter) }
            put(key, EpochArchiveCodec.encode(StoredEpochArchive(merged, stored.marks)), now)
            ArchiveMerge(merged, dropped)
        }
    }

    /** Replaces the archive's drain facts, keeping its records. */
    suspend fun saveEpochArchiveMarks(ringId: String, marks: EpochArchiveMarks, now: Instant) {
        val key = perRing(EPOCH_ARCHIVE, ringId)
        db.withWriteTransaction {
            val stored = load(key, EpochArchiveCodec::decode) ?: StoredEpochArchive.EMPTY
            put(key, EpochArchiveCodec.encode(stored.copy(marks = marks)), now)
        }
    }

    // Plumbing.

    /** The stored value, or null when nothing is stored or what is stored cannot be read. */
    private suspend fun <T> load(key: String, decode: (String) -> Decoded<T>): T? = kv.get(key)?.value?.let { decode(it).valueOrNull() }

    private suspend fun put(key: String, value: String, now: Instant) = kv.upsert(StoreKvEntity(key = key, value = value, updatedAt = now))

    private suspend fun putIfEncoded(key: String, value: String?, now: Instant): Boolean {
        if (value == null) return false
        put(key, value, now)
        return true
    }

    private companion object {
        const val ACTIVITY_EVENTS = "ring.activityEvents.v2"
        const val SYNC_ALERT_LAST_FIRED = "obs.alertLastFired"
        const val HEALTH_LAST_FIRED = "alerts.health.lastFired"
        const val HEALTH_LAST_NIGHT = "alerts.health.lastNight"
        const val ALARM = "alarm.ring.config"
        const val WORKOUT_SNAPSHOT = "workout.sessionSnapshot"
        const val HISTORY_CAPTURE = "diagnostics.historyCapture.v1"
        const val SPORT_SAMPLES = "workout.automaticDetection.samples.v1"
        const val STRANDED_COUNTERS = "sleep.unpersistedEpochCounters"
        const val EPOCH_ARCHIVE = "sleep.epochArchive"
        const val ENERGY_LEDGER = "hk.activeEnergy"

        fun perRing(name: String, ringId: String): String {
            require(ringId.isNotEmpty() && '/' !in ringId) { "a ring id must be non-empty and contain no '/': \"$ringId\"" }
            return "$name/$ringId"
        }

        /** `hk.activeEnergy/2026-10-03`: ISO digits whatever the locale. */
        fun energyKey(date: LocalDate): String = "$ENERGY_LEDGER/$date"
    }
}
