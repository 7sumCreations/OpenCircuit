package io.github.opencircuit.app.sync

import io.github.opencircuit.ringkit.MeasuredCoverage
import io.github.opencircuit.ringkit.SleepPersistOutcome
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.RederivedNight
import io.github.opencircuit.store.SleepNightExtras
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoredNapRecord
import io.github.opencircuit.store.StoredNight
import java.time.Instant
import java.time.ZoneId

/**
 * The sleep half of the store as the history commit uses it: the calls it makes on [SleepStore],
 * one for one. A seam because the store's own refusals — its one-time move of stored nights not
 * done, a failed write — cannot be set up from outside the store; the app's is [StoreNightWriter].
 */
interface NightWriter {
    /** At most [limit] stored nights, the latest key first. */
    suspend fun recentSleepSummaries(limit: Int): List<StoredNight>

    /** [SleepStore.saveSleepSummary]; throws `SleepStoreException.NightKeyMigrationPending`, writing nothing, while the move is not done. */
    suspend fun saveSleepSummary(
        summary: SleepStaging.Summary,
        night: Instant,
        inBedStart: Instant,
        inBedEnd: Instant,
        sleepOnset: Instant,
        sleepWake: Instant,
        extras: SleepNightExtras,
        now: Instant,
        zone: ZoneId,
    ): SleepPersistOutcome

    /** [SleepStore.rederiveEditedNightProvenance]. */
    suspend fun rederiveEditedNightProvenance(coverage: MeasuredCoverage, now: Instant, zone: ZoneId): List<RederivedNight>

    /** [SleepStore.naps] between two instants. */
    suspend fun naps(from: Instant, to: Instant): List<StoredNapRecord>

    /** [SleepStore.saveNap]. */
    suspend fun saveNap(start: Instant, end: Instant, asleepMin: Int, isLongNap: Boolean, segments: List<SleepSegment>, now: Instant, zone: ZoneId)
}

/** [NightWriter] over the on-device store. */
class StoreNightWriter(private val store: SleepStore) : NightWriter {
    override suspend fun recentSleepSummaries(limit: Int): List<StoredNight> = store.recentSleepSummaries(limit)

    override suspend fun saveSleepSummary(
        summary: SleepStaging.Summary,
        night: Instant,
        inBedStart: Instant,
        inBedEnd: Instant,
        sleepOnset: Instant,
        sleepWake: Instant,
        extras: SleepNightExtras,
        now: Instant,
        zone: ZoneId,
    ): SleepPersistOutcome = store.saveSleepSummary(summary, night, inBedStart, inBedEnd, sleepOnset, sleepWake, extras, now, zone)

    override suspend fun rederiveEditedNightProvenance(coverage: MeasuredCoverage, now: Instant, zone: ZoneId): List<RederivedNight> =
        store.rederiveEditedNightProvenance(coverage, now, zone)

    override suspend fun naps(from: Instant, to: Instant): List<StoredNapRecord> = store.naps(from, to)

    override suspend fun saveNap(start: Instant, end: Instant, asleepMin: Int, isLongNap: Boolean, segments: List<SleepSegment>, now: Instant, zone: ZoneId) =
        store.saveNap(start, end, asleepMin, isLongNap, segments, now, zone)
}
