package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.CumulativeMetricAccumulator
import io.github.opencircuit.ringkit.CumulativeMetricState
import io.github.opencircuit.ringkit.LiveHR
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SyncCursor
import io.github.opencircuit.ringkit.isCumulativeCounter
import java.time.Instant
import java.time.ZoneId

// The store's ingest path: ring samples and the per-metric sync cursor, committed together.
// Port of upstream ios/OpenCircuit/Store/LocalStore.swift (@ b1c2fdd): `IngestPreview` (:878),
// `storeCursorRows` (:891), `previewIngest` (:899), `loadCursor` (:929), `ingest` (:941-1028),
// `isPlausible` (:1039), `samples` (:1177), `latestSample(kind:)` (:1192), `upsertCursor` (:3126)
// and `cumulativeState` (:3136); the other sample reads (`latestSample(kind:before:)` :813,
// `earliestSample` :831 / :842, `recentSamples` :1184); daily steps and step samples
// (`addDailySteps` :3058, `todaySteps` :3073, `stepSamples` :3100, `latestDaily` :3105,
// `recentDailies` :3114, `dailies` :3119); daytime temperatures (:1073, :1079); retention and
// repairs (`sampleRetentionDays` :1051, `pruneExpiredSamples` :1058, `purgeImplausibleHeartRate`
// :1090, `purgeImplausibleTimestamps` :1110, `repairFutureSyncCursors` :1144), with the purges'
// one-time latches from ios/OpenCircuit/App.swift (:624, :638); the period log
// (ios/OpenCircuit/CycleStore.swift :73-156), the headache log and the frozen headache-risk rows
// (ios/OpenCircuit/Store/HeadacheStore.swift :195-315, :332-406), without their health-write calls
// (`periodEntriesNeedingHealthMirror`, `recordPeriodEntryHK`, `pendingHeadacheEntries`,
// `recordHeadacheEntryHK`), which belong to the health-store port.
//
// Differences, each deliberate:
// - `now` and `zone` are parameters. Upstream reads the wall clock and `Calendar.current`; the
//   result is the same for the same clock and zone.
// - Every sample's times are cut to whole milliseconds before the cursor sees them, because that
//   is what the store keeps; otherwise a sub-millisecond sample would look newer than its own
//   stored cursor and be stored again on every re-sync.
// - A heart rate that is NaN or infinite is dropped as implausible; upstream's `Int(value)` traps.
//   A NaN or infinite value of any other kind is dropped too (a NaN cannot be stored here).
// - Step totals are `Long` (Swift's `Int` is 64-bit) and an overflowing total fails the write, as
//   Swift's `+=` traps; a failed day lookup fails the write (upstream's `try?` inserts a new row).
// - The two one-time purges keep their latch in this database and write it in the purge's own
//   transaction; upstream sets a UserDefaults flag after the purge has saved.
// - The period and headache logs share one implementation of upstream's save rules
//   (UserEntrySaveRules.kt), and a move whose original has vanished keeps the row already at the
//   destination (upstream deletes it). A failed fetch fails the write (upstream's `try?` treats it
//   as "no row").

/**
 * The on-device store over one [StoreDatabase]. Every write is one transaction: it commits whole
 * or not at all.
 */
class LocalStore internal constructor(
    private val db: StoreDatabase,
    private val sampleDao: SampleDao = db.sampleDao(),
    private val dailyDao: DailyDao = db.dailyDao(),
    private val kvDao: KvDao = db.kvDao(),
    private val userEntryDao: UserEntryDao = db.userEntryDao(),
) {
    constructor(db: StoreDatabase) : this(db, db.sampleDao(), db.dailyDao(), db.kvDao(), db.userEntryDao())

    private val periodTable = PeriodEntryTable(userEntryDao)

    private val headacheTable = HeadacheEntryTable(userEntryDao)

    // Period log (ios/OpenCircuit/CycleStore.swift :73-156). Every instant is cut to the stored
    // millisecond first, so comparisons and lookups see what the store keeps.

    /**
     * Saves the period starting at [start], in one transaction. With [originalStart] (an edit that
     * moved the start), the original entry moves to [start] and takes over the Health sample ids of
     * any entry already there. A change of flow, end or symptoms marks the entry for re-writing to
     * the health store, keeping its written ids so the stale samples can be deleted; a notes-only
     * edit does not.
     */
    suspend fun savePeriodEntry(
        start: Instant,
        end: Instant?,
        flowLevelRaw: Int,
        symptoms: List<String>,
        notes: String,
        now: Instant,
        originalStart: Instant? = null,
    ) {
        val key = start.toStoredMillis()
        val endMs = end?.toStoredMillis()
        val at = now.toStoredMillis()
        val tags = symptoms.toList()
        db.withWriteTransaction {
            periodTable.saveEntry(
                key = key,
                originalKey = originalStart?.toStoredMillis(),
                edited = { it.copy(end = endMs, flowLevelRaw = flowLevelRaw, symptoms = tags, notes = notes, updatedAt = at) },
                isClinicalChange = { it.flowLevelRaw != flowLevelRaw || it.end != endMs || it.symptoms != tags },
                fresh = {
                    StoredPeriodEntryEntity(start = key, end = endMs, flowLevelRaw = flowLevelRaw, symptoms = tags, notes = notes, updatedAt = at)
                },
            )
        }
    }

    /**
     * Deletes the period starting at [start] and returns the ids of the Health samples written for
     * it, for the caller to delete there; an empty list when there was no such entry.
     */
    suspend fun deletePeriodEntry(start: Instant): List<String> =
        db.withWriteTransaction {
            val row = userEntryDao.periodAt(start.toStoredMillis()) ?: return@withWriteTransaction emptyList()
            userEntryDao.deletePeriod(row)
            row.hkSampleUUIDs
        }

    /** Every logged period, oldest start first. */
    suspend fun allPeriodEntries(): List<PeriodEntry> = userEntryDao.allPeriods().map { it.toPeriodEntry() }

    // Headache log (ios/OpenCircuit/Store/HeadacheStore.swift :195-315).

    /**
     * Saves the headache with [onset], in one transaction, under the period log's rules: a move
     * ([originalOnset]) relocates the original entry and takes over the ids of any entry at
     * [onset]; a change of severity, end or symptoms marks it for re-writing; notes, custom
     * symptoms and possible triggers do not. [source] and [importedHKUUID] are taken by a new entry
     * only; an edit keeps the entry's own.
     */
    suspend fun saveHeadacheEntry(
        onset: Instant,
        end: Instant?,
        severityRaw: Int,
        symptoms: List<String>,
        customSymptoms: List<String> = emptyList(),
        factors: List<String> = emptyList(),
        notes: String = "",
        source: HeadacheSource = HeadacheSource.USER,
        importedHKUUID: String? = null,
        originalOnset: Instant? = null,
        now: Instant,
    ) {
        val key = onset.toStoredMillis()
        val endMs = end?.toStoredMillis()
        val at = now.toStoredMillis()
        val tags = symptoms.toList()
        val custom = customSymptoms.toList()
        val triggers = factors.toList()
        db.withWriteTransaction {
            headacheTable.saveEntry(
                key = key,
                originalKey = originalOnset?.toStoredMillis(),
                edited = {
                    it.copy(
                        end = endMs, severityRaw = severityRaw, symptoms = tags, customSymptoms = custom,
                        factors = triggers, notes = notes, updatedAt = at,
                    )
                },
                isClinicalChange = { it.severityRaw != severityRaw || it.end != endMs || it.symptoms != tags },
                fresh = {
                    StoredHeadacheEntryEntity(
                        onset = key, end = endMs, severityRaw = severityRaw, symptoms = tags, customSymptoms = custom,
                        factors = triggers, notes = notes, sourceRaw = source.rawValue, importedHKUUID = importedHKUUID,
                        updatedAt = at,
                    )
                },
            )
        }
    }

    /**
     * Deletes the headache with [onset] and returns the ids of the Health samples written for it;
     * an empty list when there was no such entry.
     */
    suspend fun deleteHeadacheEntry(onset: Instant): List<String> =
        db.withWriteTransaction {
            val row = userEntryDao.headacheAt(onset.toStoredMillis()) ?: return@withWriteTransaction emptyList()
            userEntryDao.deleteHeadache(row)
            row.hkSampleUUIDs
        }

    /** Every logged headache, oldest onset first. */
    suspend fun allHeadacheEntries(): List<HeadacheEntry> = userEntryDao.allHeadaches().map { it.toHeadacheEntry() }

    /** Headaches with `from <= onset < to`, oldest first. */
    suspend fun headacheEntries(from: Instant, to: Instant): List<HeadacheEntry> =
        userEntryDao.headaches(from, to).map { it.toHeadacheEntry() }

    /** The health-store sample ids already imported as headaches, so a repeated import adds nothing. */
    suspend fun importedHeadacheHKUUIDs(): Set<String> = userEntryDao.importedHeadacheUUIDs().toSet()

    // Frozen risk rows (ios/OpenCircuit/Store/HeadacheStore.swift :332-406).

    /**
     * Stores [row] unless its day is already scored, or its night is (a time-zone change moves the
     * day key; a night key never moves). Returns true when it was stored. A day's score is never
     * replaced: there is no update path, by design. One transaction. Throws
     * [IllegalArgumentException], writing nothing, when [HeadacheRiskDay.index] or
     * [HeadacheRiskDay.coverageFraction] is NaN or ±∞ (SQLite would bind NaN as NULL; ±∞ would be
     * frozen as the day's score for good).
     */
    suspend fun insertRiskDayIfAbsent(row: HeadacheRiskDay): Boolean {
        require(row.index.isFinite()) { "risk index ${row.index} is not a finite number" }
        require(row.coverageFraction.isFinite()) { "risk coverage ${row.coverageFraction} is not a finite number" }
        val stored = row.toEntity()
        return db.withWriteTransaction {
            if (userEntryDao.riskOn(stored.day) != null) return@withWriteTransaction false
            if (stored.nightKey != SleepEdit.DISTANT_PAST && userEntryDao.riskForNight(stored.nightKey) != null) {
                return@withWriteTransaction false
            }
            userEntryDao.insertRisk(stored)
            true
        }
    }

    /** The frozen row scoring the night [nightKey]; null for [SleepEdit.DISTANT_PAST] or none. */
    suspend fun riskRow(nightKey: Instant): HeadacheRiskDay? {
        val key = nightKey.toStoredMillis()
        if (key == SleepEdit.DISTANT_PAST) return null
        return userEntryDao.riskForNight(key)?.toRiskDay()
    }

    /** Frozen rows with `from <= day < to`, oldest first. */
    suspend fun riskDays(from: Instant, to: Instant): List<HeadacheRiskDay> = userEntryDao.riskDays(from, to).map { it.toRiskDay() }

    /**
     * Records, once, that [day]'s night was re-staged after its score was frozen ([sleepUpdatedAt]:
     * the summary's new update time). The score is untouched; a later call changes nothing, and
     * nothing happens when [day] has no row.
     */
    suspend fun markRiskRestaged(day: Instant, sleepUpdatedAt: Instant, now: Instant) {
        db.withWriteTransaction {
            val row = userEntryDao.riskOn(day.toStoredMillis()) ?: return@withWriteTransaction
            if (row.sleepRestaged) return@withWriteTransaction
            userEntryDao.updateRisk(
                row.copy(sleepRestaged = true, sleepUpdatedAt = sleepUpdatedAt.toStoredMillis(), updatedAt = now.toStoredMillis()),
            )
        }
    }

    /** Records that an alert fired for [day]; nothing happens when [day] has no row. */
    suspend fun markRiskAlerted(day: Instant, now: Instant) {
        db.withWriteTransaction {
            val row = userEntryDao.riskOn(day.toStoredMillis()) ?: return@withWriteTransaction
            userEntryDao.updateRisk(row.copy(alerted = true, updatedAt = now.toStoredMillis()))
        }
    }

    /** What [ingest] would do with a batch, without writing (upstream `IngestPreview`). */
    data class IngestPreview(
        val inputCount: Int = 0,
        val plausibleCount: Int = 0,
        val freshCount: Int = 0,
        val duplicateCount: Int = 0,
        val invalidTimestampCount: Int = 0,
        val invalidHeartRateCount: Int = 0,
        /** Samples of any kind whose value is NaN or infinite. */
        val invalidValueCount: Int = 0,
    )

    /**
     * Dry run of [ingest] for logging: how many samples are implausible, new or already stored.
     * Writes nothing.
     */
    suspend fun previewIngest(samples: List<QuantitySample>, now: Instant): IngestPreview {
        val cursor = cursorOf(storeCursorRows())
        var invalidTimestamp = 0
        var invalidHeartRate = 0
        var invalidValue = 0
        val plausible = ArrayList<QuantitySample>(samples.size)
        for (s in samples.map(::toStoredPrecision)) {
            when (plausibility(s, now)) {
                Plausibility.BAD_TIMESTAMP -> invalidTimestamp++
                Plausibility.BAD_HEART_RATE -> invalidHeartRate++
                Plausibility.BAD_VALUE -> invalidValue++
                Plausibility.PLAUSIBLE -> plausible += s
            }
        }
        val fresh = cursor.selectNewStaged(plausible).fresh.size
        return IngestPreview(
            inputCount = samples.size,
            plausibleCount = plausible.size,
            freshCount = fresh,
            duplicateCount = maxOf(plausible.size - fresh, 0),
            invalidTimestampCount = invalidTimestamp,
            invalidHeartRateCount = invalidHeartRate,
            invalidValueCount = invalidValue,
        )
    }

    /** The ingest cursor, rebuilt from the stored rows (health and export watermarks excluded). */
    suspend fun loadCursor(): SyncCursor = cursorOf(storeCursorRows())

    /**
     * Stores the plausible samples newer than the cursor and advances the cursor past them, in one
     * transaction: if anything fails, neither the samples nor the cursor move, and the same
     * samples are taken again on the next ingest.
     *
     * Cumulative counters (steps, active energy) are stored as per-epoch deltas with their raw
     * reading and the running day total; the day total restarts at local midnight in [zone].
     * Returns what was stored — for a cumulative counter, the delta sample.
     */
    suspend fun ingest(samples: List<QuantitySample>, now: Instant, zone: ZoneId): List<QuantitySample> =
        db.withWriteTransaction {
            val rows = storeCursorRows()
            val cursor = cursorOf(rows)

            // Plausibility before the cursor sees anything: a cursor only moves forward, so one
            // corrupt far-future sample would block every later real sample of its kind.
            val plausible = samples.map(::toStoredPrecision).filter { plausibility(it, now) == Plausibility.PLAUSIBLE }

            // Stage the advance; the cursor rows are written in this same transaction.
            val staged = cursor.selectNewStaged(plausible)
            if (staged.fresh.isEmpty()) return@withWriteTransaction emptyList()

            val toInsert = ArrayList<StoredSampleEntity>(staged.fresh.size)
            val ingested = ArrayList<QuantitySample>(staged.fresh.size)
            // One stored-state lookup per cumulative kind per batch; later samples of that kind in
            // the batch carry the state forward, restarting the day total at a new local day.
            val states = HashMap<MetricKind, CumulativeMetricState>()
            val stateDays = HashMap<MetricKind, Instant>()

            for (s in staged.fresh) {
                if (!s.kind.isCumulativeCounter) {
                    toInsert += StoredSampleEntity.of(s)
                    ingested += s
                    continue
                }
                val dayStart = startOfDay(s.start, zone)
                val existing = states[s.kind]
                val state = when {
                    existing == null -> cumulativeState(s.kind, before = s.start, zone = zone)
                    stateDays[s.kind] == dayStart -> existing
                    else -> CumulativeMetricState(previousRawValue = existing.previousRawValue, dailyTotal = 0.0)
                }
                val result = CumulativeMetricAccumulator.accumulate(s, state)
                val delta = s.copy(value = result.deltaValue)
                toInsert += StoredSampleEntity.of(delta, rawValue = result.rawValue, isDelta = true, dailyTotal = result.dailyTotal)
                states[s.kind] = CumulativeMetricState(previousRawValue = result.rawValue, dailyTotal = result.dailyTotal)
                stateDays[s.kind] = dayStart
                // The delta, not the running total: the health store sums cumulative types.
                ingested += delta
            }
            sampleDao.insertSamples(toInsert)

            // Only the kinds whose cursor moved.
            val advanced = staged.advanced
            sampleDao.upsertCursors(
                advanced.advancedKinds(since = cursor).mapNotNull { kind ->
                    advanced.last(kind)?.let { StoredCursorEntity(kindRaw = kind.rawValue, last = it) }
                },
            )
            ingested
        }

    /** Stored samples of [kind] with `from <= start < to`, oldest first. */
    suspend fun samples(kind: MetricKind, from: Instant, to: Instant): List<QuantitySample> =
        sampleDao.samples(kind.rawValue, from, to).mapNotNull { it.toSample() }

    /** The newest stored sample of [kind], or null (the launch screen's last known heart rate). */
    suspend fun latestSample(kind: MetricKind): QuantitySample? = sampleDao.latestSample(kind.rawValue)?.toSample()

    /** The newest stored sample of [kind] strictly before [before], or null. */
    suspend fun latestSample(kind: MetricKind, before: Instant): QuantitySample? =
        sampleDao.latestSampleBefore(kind.rawValue, before)?.toSample()

    /** The oldest stored sample of [kind] strictly after [after], or null. */
    suspend fun earliestSample(kind: MetricKind, after: Instant): QuantitySample? =
        sampleDao.earliestSampleAfter(kind.rawValue, after)?.toSample()

    /** The oldest stored sample of [kind], or null when none is kept. */
    suspend fun earliestSample(kind: MetricKind): QuantitySample? = sampleDao.earliestSample(kind.rawValue)?.toSample()

    /** Stored samples of [kind] with `start >= since` and a value above zero, oldest first. */
    suspend fun recentSamples(kind: MetricKind, since: Instant): List<QuantitySample> =
        sampleDao.recentSamples(kind.rawValue, since).mapNotNull { it.toSample() }

    /**
     * Adds a step [delta] read off the ring at [day] to that local day's total in [zone], and keeps
     * the delta with its window — `[windowStart, day]`, or the whole day so far when [windowStart]
     * is null — in one transaction. A delta of zero or less writes nothing. A total that would
     * overflow fails the write.
     */
    suspend fun addDailySteps(delta: Long, day: Instant, windowStart: Instant? = null, now: Instant, zone: ZoneId) {
        if (delta <= 0) return
        val dayStart = startOfDay(day, zone)
        db.withWriteTransaction {
            val existing = dailyDao.dailyOn(dayStart)
            if (existing != null) {
                dailyDao.updateDaily(existing.copy(steps = Math.addExact(existing.steps, delta), updatedAt = now))
            } else {
                dailyDao.insertDaily(StoredDailyEntity(day = dayStart, steps = delta, updatedAt = now))
            }
            dailyDao.insertStepSample(StoredStepSampleEntity(start = windowStart ?: dayStart, end = day, delta = delta))
        }
    }

    /** The step total of [day]'s local day in [zone]; 0 when nothing is stored for it. */
    suspend fun todaySteps(day: Instant, zone: ZoneId): Long = dailyDao.dailyOn(startOfDay(day, zone))?.steps ?: 0

    /** Step deltas with `from <= start < to`, oldest first. */
    suspend fun stepSamples(from: Instant, to: Instant): List<StepSample> = dailyDao.stepSamples(from, to).map { it.toStepSample() }

    /** The newest day's step total, or null. */
    suspend fun latestDaily(): DailySteps? = dailyDao.recentDailies(limit = 1).firstOrNull()?.toDailySteps()

    /** At most [limit] day totals, newest day first. */
    suspend fun recentDailies(limit: Int = 14): List<DailySteps> = dailyDao.recentDailies(limit).map { it.toDailySteps() }

    /** Day totals whose day starts in `[from, to)`, oldest first. */
    suspend fun dailies(from: Instant, to: Instant): List<DailySteps> = dailyDao.dailies(from, to).map { it.toDailySteps() }

    /**
     * Keeps one daytime skin-temperature reading (a plain append: readings are timestamped).
     * Throws [IllegalArgumentException], writing nothing, for NaN or ±∞ (SQLite would bind NaN
     * as NULL and fail the write; ±∞ is no temperature).
     */
    suspend fun recordDaytimeTemperature(celsius: Double, at: Instant) {
        require(celsius.isFinite()) { "daytime temperature $celsius is not a finite number" }
        dailyDao.insertDaytimeTemp(StoredDaytimeTempEntity(time = at, celsius = celsius))
    }

    /**
     * Deletes raw samples, step deltas and daytime readings older than [olderThanDays] calendar
     * days before [now] in [zone] (`time < cutoff`; a row exactly at the cutoff is kept), in one
     * transaction. Day totals and cursors are never pruned. Meant for once a launch, not per write.
     */
    suspend fun pruneExpiredSamples(now: Instant, zone: ZoneId, olderThanDays: Long = SAMPLE_RETENTION_DAYS) {
        // Calendar days in the zone, as Foundation's `date(byAdding: .day, value: -days, to:)`.
        val cutoff = now.atZone(zone).minusDays(olderThanDays).toInstant()
        db.withWriteTransaction {
            sampleDao.deleteSamplesBefore(cutoff)
            dailyDao.deleteDaytimeTempsBefore(cutoff)
            dailyDao.deleteStepSamplesBefore(cutoff)
        }
    }

    /**
     * Once per store: deletes heart-rate samples whose value is below 30 or above 220 bpm (stored
     * before the decoder's band guard) and returns how many; null when it already ran. The purge
     * and its latch commit together, so a failed purge leaves no latch and runs again next time.
     * The stored value is compared as is (220.5 is deleted, though ingest keeps it), as upstream.
     */
    suspend fun purgeImplausibleHeartRateOnce(now: Instant): Int? =
        purgeOnce(HEART_RATE_PURGE_LATCH, now) {
            sampleDao.deleteHeartRatesOutside(
                MetricKind.HEART_RATE.rawValue,
                lowest = LiveHR.MIN_VALID_BPM.toDouble(),
                highest = LiveHR.MAX_VALID_BPM.toDouble(),
            )
        }

    /**
     * Once per store: deletes samples of any kind whose start is outside ingest's plausible window
     * for [now] (before the ring's counter epoch, or more than one day ahead) and returns how many;
     * null when it already ran. Latched like [purgeImplausibleHeartRateOnce].
     */
    suspend fun purgeImplausibleTimestampsOnce(now: Instant): Int? =
        purgeOnce(TIMESTAMP_PURGE_LATCH, now) {
            sampleDao.deleteSamplesOutside(floor = SYNC_EPOCH_INSTANT, ceiling = futureCeiling(now))
        }

    /**
     * Pulls back every cursor row stuck more than one day after [now] — the ingest cursor and the
     * health (`hk:`) watermark alike — to the newest stored sample of its kind at or before [now],
     * or deletes it when there is none, so the next sync takes the backlog again. Runs on every
     * launch (not latched, as upstream); returns how many rows it changed. One transaction.
     *
     * Only the `hk:` prefix is stripped before the sample lookup, as upstream: any other watermark
     * row stuck in the future finds no sample of its key and is deleted.
     */
    suspend fun repairFutureSyncCursors(now: Instant): Int =
        db.withWriteTransaction {
            val stuck = sampleDao.allCursors().filter { it.last > futureCeiling(now) }
            for (row in stuck) {
                val bareKind = row.kindRaw.removePrefix(HEALTH_CURSOR_PREFIX)
                val latest = sampleDao.latestSampleAtOrBefore(bareKind, now)
                if (latest != null) {
                    sampleDao.upsertCursors(listOf(row.copy(last = latest.start)))
                } else {
                    sampleDao.deleteCursor(row.kindRaw)
                }
            }
            stuck.size
        }

    /**
     * Runs [purge] and writes its latch in one transaction, unless the latch is already set. A
     * latch holding anything but its set value counts as unset: the purges are idempotent, so
     * running one again costs a scan, never data.
     */
    private suspend fun purgeOnce(latch: String, now: Instant, purge: suspend () -> Int): Int? =
        db.withWriteTransaction {
            if (kvDao.get(latch)?.value == LATCH_SET) return@withWriteTransaction null
            val purged = purge()
            kvDao.upsert(StoreKvEntity(key = latch, value = LATCH_SET, updatedAt = now))
            purged
        }

    /** Daytime readings with `from <= time < to`, oldest first. */
    suspend fun daytimeTemperatures(from: Instant, to: Instant): List<DaytimeTemperature> =
        dailyDao.daytimeTemps(from, to).map { it.toDaytimeTemperature() }

    private suspend fun storeCursorRows(): List<StoredCursorEntity> =
        sampleDao.allCursors().filter { !it.kindRaw.startsWith(HEALTH_CURSOR_PREFIX) && !it.kindRaw.startsWith(EXPORT_CURSOR_PREFIX) }

    private fun cursorOf(rows: List<StoredCursorEntity>) = SyncCursor(rows.associate { it.kindRaw to it.last })

    /**
     * The counter state just before [before]: the previous raw reading of [kind] (any day), and the
     * day total of the newest earlier sample on the same local day (upstream `cumulativeState`).
     */
    private suspend fun cumulativeState(kind: MetricKind, before: Instant, zone: ZoneId): CumulativeMetricState {
        val previous = sampleDao.latestSampleBefore(kind.rawValue, before)
        val previousRaw = previous?.let { it.rawValue ?: it.value }
        val dayStart = startOfDay(before, zone)
        val nextDay = before.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val latestToday = sampleDao.latestSampleWithin(kind.rawValue, dayStart, nextDay, before)
        if (latestToday != null) {
            latestToday.dailyTotal?.let { return CumulativeMetricState(previousRawValue = previousRaw, dailyTotal = it) }
            if (!latestToday.isDelta) {
                return CumulativeMetricState(previousRawValue = previousRaw, dailyTotal = latestToday.rawValue ?: latestToday.value)
            }
        }
        return CumulativeMetricState(previousRawValue = previousRaw)
    }

    private enum class Plausibility { PLAUSIBLE, BAD_TIMESTAMP, BAD_HEART_RATE, BAD_VALUE }

    /**
     * The one plausibility check behind [previewIngest] and [ingest] (upstream `isPlausible`):
     * `start` no earlier than the ring's counter epoch and no later than one day after [now]; a
     * heart rate whose truncated value is in [LiveHR.VALID_BPM]. A NaN or infinite heart rate is
     * implausible (it truncates to 0 or an `Int` bound here; upstream traps). A NaN or infinite
     * value of any other kind is implausible too: SQLite binds NaN as NULL, which the value
     * column refuses, so one such sample would fail the whole batch on every sync.
     */
    private fun plausibility(s: QuantitySample, now: Instant): Plausibility {
        if (s.start < SYNC_EPOCH_INSTANT || s.start > futureCeiling(now)) return Plausibility.BAD_TIMESTAMP
        if (s.kind == MetricKind.HEART_RATE && s.value.toInt() !in LiveHR.VALID_BPM) return Plausibility.BAD_HEART_RATE
        if (!s.value.isFinite()) return Plausibility.BAD_VALUE
        return Plausibility.PLAUSIBLE
    }

    companion object {
        /** Days of raw samples, step deltas and daytime readings kept on the phone (upstream `sampleRetentionDays`). */
        const val SAMPLE_RETENTION_DAYS = 30L

        /** Cursor rows of the health-store writer share the table; they are not the ingest cursor. */
        private const val HEALTH_CURSOR_PREFIX = "hk:"

        /** Cursor rows of the export watermark share the table; they are not the ingest cursor. */
        private const val EXPORT_CURSOR_PREFIX = "export:"

        /** Clock-skew tolerance: a sample up to one day in the future is kept. */
        private const val FUTURE_TOLERANCE_SECONDS = 86_400L

        private val SYNC_EPOCH_INSTANT: Instant = Instant.ofEpochSecond(Command.SYNC_EPOCH)

        /** One-time latch of the heart-rate purge, in `store_kv` (upstream's UserDefaults key). */
        private const val HEART_RATE_PURGE_LATCH = "store.purgedImplausibleHR.v1"

        /** One-time latch of the timestamp purge, in `store_kv` (upstream's UserDefaults key). */
        private const val TIMESTAMP_PURGE_LATCH = "store.purgedImplausibleTimestamps.v1"

        /** A latch's stored value once set (a JSON `true`, as every `store_kv` value is JSON text). */
        private const val LATCH_SET = "true"

        /**
         * The latest plausible sample start for [now], one day ahead (clock skew): shared by the
         * ingest check, the timestamp purge and the cursor repair.
         */
        private fun futureCeiling(now: Instant): Instant = now.plusSeconds(FUTURE_TOLERANCE_SECONDS)

        /** The sample with both times cut to the whole milliseconds the store keeps. */
        private fun toStoredPrecision(s: QuantitySample): QuantitySample {
            val start = s.start.toStoredMillis()
            val end = s.end.toStoredMillis()
            return if (start == s.start && end == s.end) s else s.copy(start = start, end = end)
        }
    }
}

/**
 * The first instant of [t]'s local day in [zone] (Foundation's `startOfDay(for:)`): on a day whose
 * midnight the zone skips, the first instant that exists. Shared by [LocalStore] and [SleepStore].
 */
internal fun startOfDay(t: Instant, zone: ZoneId): Instant = t.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
