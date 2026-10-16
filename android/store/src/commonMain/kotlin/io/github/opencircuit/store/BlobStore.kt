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
import io.github.opencircuit.ringkit.SleepSegment
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
import io.github.opencircuit.store.codec.SleepSegmentCodec
import io.github.opencircuit.store.codec.SportSampleListCodec
import io.github.opencircuit.store.codec.StrandedCountersCodec
import io.github.opencircuit.store.codec.WorkoutSessionSnapshotCodec
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Collections

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
// - The pending sleep segments' two views are written and cleared in one transaction, and a view
//   that cannot be read is empty while the other is kept (upstream decodes each with `try?`).
// Not given a home: the history-sync evidence list (its wrapper type belongs to the sync epic; the
// trace codec is here), and `UnattributedPageBuffer` (in memory upstream, RingSession.swift:499;
// the history journal's rows with no drain replace it).

/** The value kept for an active-energy bucket written while it was still running ([BlobStore.saveHealthEnergyWritten]). */
const val ENERGY_WRITTEN_RUNNING = -1.0

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

    // Health Connect write generation: one number; an unreadable value is reported, never read as absent.

    /**
     * The last Health Connect write generation handed out: null when none ever was,
     * [Decoded.Unreadable] when what is stored is not a whole number of at least 1. A caller must
     * not start again from 1 over an unreadable value: every record already written carries a
     * higher version, so each later write would be ignored.
     */
    suspend fun loadHealthWriteGeneration(): Decoded<Long>? {
        val raw = kv.get(HEALTH_WRITE_GENERATION)?.value ?: return null
        // ASCII digits only: Kotlin's toLongOrNull also reads other scripts' digits ("９").
        val value = if (ASCII_DIGITS.matches(raw)) raw.toLongOrNull() else null
        return if (value != null && value >= 1) Decoded.Readable(value) else Decoded.Unreadable(raw, "not a whole number of at least 1")
    }

    /** Saves [generation] (at least 1) as the last write generation handed out. */
    suspend fun saveHealthWriteGeneration(generation: Long, now: Instant) {
        require(generation >= 1) { "a write generation is at least 1, was $generation" }
        put(HEALTH_WRITE_GENERATION, generation.toString(), now)
    }

    // Health Connect write failures (upstream `hk.failures.byMetric`, HealthKitWriter.swift
    // :377-396): record type name → last failure time. An unreadable value is reported.

    /**
     * The types whose last Health Connect write failed, by name, with when: null when none,
     * [Decoded.Unreadable] when what is stored is not `NAME=epochMillis` pairs joined by `;`.
     */
    suspend fun loadHealthWriteFailures(): Decoded<Map<String, Instant>>? {
        val raw = kv.get(HEALTH_WRITE_FAILURES)?.value ?: return null
        val out = sortedMapOf<String, Instant>()
        for (pair in raw.split(';')) {
            val parts = pair.split('=')
            val name = parts.getOrNull(0)
            val millis = parts.getOrNull(1)
            if (parts.size != 2 || name == null || millis == null || !FAILURE_NAME.matches(name) || !ASCII_DIGITS.matches(millis)) {
                return Decoded.Unreadable(raw, "not NAME=epochMillis pairs")
            }
            val ms = millis.toLongOrNull() ?: return Decoded.Unreadable(raw, "a time out of range")
            out[name] = Instant.ofEpochMilli(ms)
        }
        return Decoded.Readable(out.toMap())
    }

    /**
     * Saves [failures] (type name → last failure time, at or after 1970), replacing what was
     * stored; an empty map removes the value. A name is an upper-case constant (`SLEEP_SESSION`).
     */
    suspend fun saveHealthWriteFailures(failures: Map<String, Instant>, now: Instant) {
        failures.forEach { (name, at) ->
            require(FAILURE_NAME.matches(name)) { "a failure's name must be an upper-case constant, was \"$name\"" }
            require(!at.isBefore(Instant.EPOCH)) { "a failure time must be at or after 1970, was $at" }
        }
        if (failures.isEmpty()) {
            kv.delete(HEALTH_WRITE_FAILURES)
            return
        }
        put(HEALTH_WRITE_FAILURES, failures.toSortedMap().entries.joinToString(";") { (name, at) -> "$name=${at.toEpochMilli()}" }, now)
    }

    // Health Connect's permission screen shown once: after it, a "Not connected" card offers
    // Health Connect's settings instead of asking again (Android shows the screen only so often).

    /** Whether the permission screen was shown. Any stored value counts: a damaged one must not make the card ask again in a loop. */
    suspend fun loadHealthPermissionsRequested(): Boolean = kv.get(HEALTH_PERMISSIONS_REQUESTED) != null

    /** Records that the permission screen was shown and answered. */
    suspend fun saveHealthPermissionsRequested(now: Instant) = put(HEALTH_PERMISSIONS_REQUESTED, "1", now)

    // The live step fold's baseline (upstream keeps it in UserDefaults, RingSession.swift :2239-2253):
    // `raw;dayStartMillis;sampleAtMillis`. Unreadable is none: the next reading is then credited whole.

    /** The last raw step counter folded, its local day and when it arrived; null when none or unreadable. */
    suspend fun loadStepBaseline(): StepBaseline? {
        val parts = kv.get(STEP_BASELINE)?.value?.split(';') ?: return null
        if (parts.size != 3 || parts.any { !ASCII_DIGITS.matches(it) }) return null
        val raw = parts[0].toIntOrNull()?.takeIf { it in 0..StepBaseline.MAX_RAW } ?: return null
        val day = parts[1].toLongOrNull() ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        return StepBaseline(raw, Instant.ofEpochMilli(day), Instant.ofEpochMilli(at))
    }

    /** Saves the step fold's baseline, replacing the last one. */
    suspend fun saveStepBaseline(baseline: StepBaseline, now: Instant) {
        require(baseline.raw in 0..StepBaseline.MAX_RAW) { "a raw step counter is 0..${StepBaseline.MAX_RAW}, was ${baseline.raw}" }
        require(!baseline.day.isBefore(Instant.EPOCH) && !baseline.sampleAt.isBefore(Instant.EPOCH)) { "a step baseline is dated at or after 1970" }
        put(STEP_BASELINE, "${baseline.raw};${baseline.day.toEpochMilli()};${baseline.sampleAt.toEpochMilli()}", now)
    }

    // Health Connect day marks (upstream `hk.restingHR.lastDay`): per record type name, the last local
    // day written. Unreadable is none: the writer then rewrites its lookback, under the same ids.

    /** The last local day written for [type] (a record type's name), or null when none or unreadable. */
    suspend fun loadHealthDayMark(type: String): LocalDate? {
        val raw = kv.get(dayMarkKey(type))?.value ?: return null
        if (!ISO_DATE.matches(raw)) return null
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Saves [day] as the last local day written for [type]. */
    suspend fun saveHealthDayMark(type: String, day: LocalDate, now: Instant) = put(dayMarkKey(type), day.toString(), now)

    // Health Connect active energy: per local day, the kilocalories written per bucket start
    // (`epochMillis=kcal;…`), so a bucket that falls is written lower, down to zero. A bucket
    // written while it was still running is kept as [ENERGY_WRITTEN_RUNNING] (`-1`): it is written
    // again over its whole span once it closes, whatever its total.

    /** The kilocalories written per bucket start of [day]; empty when none or unreadable. */
    suspend fun loadHealthEnergyWritten(day: LocalDate): Map<Instant, Double> {
        val raw = kv.get(healthEnergyKey(day))?.value ?: return emptyMap()
        val out = HashMap<Instant, Double>()
        for (pair in raw.split(';')) {
            val parts = pair.split('=')
            if (parts.size != 2 || !ASCII_DIGITS.matches(parts[0])) return emptyMap()
            val kcal = if (parts[1] == ENERGY_WRITTEN_RUNNING.toString()) {
                ENERGY_WRITTEN_RUNNING
            } else {
                if (!DECIMAL.matches(parts[1])) return emptyMap()
                parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return emptyMap()
            }
            val millis = parts[0].toLongOrNull() ?: return emptyMap()
            out[Instant.ofEpochMilli(millis)] = kcal
        }
        return out
    }

    /** Saves the kilocalories written per bucket start of [day], replacing what was stored; an empty map removes it. */
    suspend fun saveHealthEnergyWritten(day: LocalDate, written: Map<Instant, Double>, now: Instant) {
        written.forEach { (start, kcal) ->
            require(!start.isBefore(Instant.EPOCH)) { "a bucket start is at or after 1970, was $start" }
            require(kcal == ENERGY_WRITTEN_RUNNING || (kcal.isFinite() && kcal >= 0)) {
                "written kilocalories are a finite number at least 0 (or the running marker), was $kcal"
            }
        }
        if (written.isEmpty()) {
            kv.delete(healthEnergyKey(day))
            return
        }
        put(healthEnergyKey(day), written.toSortedMap().entries.joinToString(";") { (start, kcal) -> "${start.toEpochMilli()}=$kcal" }, now)
    }

    /** Forgets the energy written for every day before [day], in one transaction. */
    suspend fun forgetHealthEnergyWrittenBefore(day: LocalDate) {
        db.withWriteTransaction {
            kv.range("$HEALTH_ENERGY/", healthEnergyKey(day)).forEach { kv.delete(it.key) }
        }
    }

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

    // Pending sleep segments (upstream EpochArchiveStore.swift:207-226, PORTING.md D-180): the last
    // night a commit staged, as two views published together, kept until the health store took them.

    /**
     * One published night: [coarse] (the wear-gated motion segments) and [staged] (the staged
     * hypnogram), each empty when that view staged nothing. Both lists are read-only copies.
     */
    class PendingSleepSegments(coarse: List<SleepSegment>, staged: List<SleepSegment>) {
        val coarse: List<SleepSegment> = Collections.unmodifiableList(ArrayList(coarse))
        val staged: List<SleepSegment> = Collections.unmodifiableList(ArrayList(staged))

        /**
         * What the health-store write takes (upstream `ContentView.flushHealth()`'s choice): [staged]
         * when both views are non-empty, else [coarse] — so a staged view with no coarse view is not
         * taken, as upstream.
         */
        val preferred: List<SleepSegment> get() = if (staged.isNotEmpty() && coarse.isNotEmpty()) staged else coarse
    }

    /**
     * Publishes one night's two views as a pair, replacing the pair stored for [ringId] in one
     * transaction, so the two can never come from different commits. A pass that staged nothing in
     * either view writes nothing: the published pair stays (an empty pass is not an instruction to
     * forget it — only [clearPendingSleepSegments] is).
     */
    suspend fun savePendingSleepSegments(ringId: String, coarse: List<SleepSegment>, staged: List<SleepSegment>, now: Instant) {
        if (coarse.isEmpty() && staged.isEmpty()) return
        val coarseKey = perRing(PENDING_COARSE, ringId)
        val stagedKey = perRing(PENDING_STAGED, ringId)
        db.withWriteTransaction {
            put(coarseKey, SleepSegmentCodec.encode(coarse), now)
            put(stagedKey, SleepSegmentCodec.encode(staged), now)
        }
    }

    /** The pair stored for [ringId]; a view that is absent or cannot be read is empty, the other is kept. */
    suspend fun loadPendingSleepSegments(ringId: String): PendingSleepSegments = PendingSleepSegments(
        coarse = load(perRing(PENDING_COARSE, ringId), SleepSegmentCodec::decode) ?: emptyList(),
        staged = load(perRing(PENDING_STAGED, ringId), SleepSegmentCodec::decode) ?: emptyList(),
    )

    /** Forgets the pair stored for [ringId] (after the health store confirmed its write). */
    suspend fun clearPendingSleepSegments(ringId: String) {
        val coarseKey = perRing(PENDING_COARSE, ringId)
        val stagedKey = perRing(PENDING_STAGED, ringId)
        db.withWriteTransaction {
            kv.delete(coarseKey)
            kv.delete(stagedKey)
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
        const val PENDING_COARSE = "sleep.pendingCoarseSegments"
        const val PENDING_STAGED = "sleep.pendingStagedSegments"
        const val ENERGY_LEDGER = "hk.activeEnergy"
        const val HEALTH_WRITE_GENERATION = "hc.writeGeneration"
        const val HEALTH_WRITE_FAILURES = "hc.writeFailures"
        const val HEALTH_PERMISSIONS_REQUESTED = "hc.permissionsRequested"
        const val STEP_BASELINE = "steps.baseline"
        const val HEALTH_DAY_MARK = "hc.lastDay"
        const val HEALTH_ENERGY = "hc.activeEnergy"
        val ASCII_DIGITS = Regex("[0-9]+")
        val ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

        /** A plain decimal as Kotlin prints a Double (`12.5`, `0.0`, `1.0E-4`); no sign, no NaN. */
        val DECIMAL = Regex("[0-9]+(\\.[0-9]+)?(E-?[0-9]+)?")

        fun dayMarkKey(type: String): String {
            require(FAILURE_NAME.matches(type)) { "a record type's name is an upper-case constant, was \"$type\"" }
            return "$HEALTH_DAY_MARK/$type"
        }

        /** `hc.activeEnergy/2026-10-16`: ISO digits whatever the locale. */
        fun healthEnergyKey(day: LocalDate): String = "$HEALTH_ENERGY/$day"
        val FAILURE_NAME = Regex("[A-Z][A-Z0-9_]*")

        fun perRing(name: String, ringId: String): String {
            require(ringId.isNotEmpty() && '/' !in ringId) { "a ring id must be non-empty and contain no '/': \"$ringId\"" }
            return "$name/$ringId"
        }

        /** `hk.activeEnergy/2026-10-03`: ISO digits whatever the locale. */
        fun energyKey(date: LocalDate): String = "$ENERGY_LEDGER/$date"
    }
}
