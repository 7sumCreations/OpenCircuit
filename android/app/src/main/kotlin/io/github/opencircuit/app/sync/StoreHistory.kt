package io.github.opencircuit.app.sync

import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.BulkSleep
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.HistoryCommitGate
import io.github.opencircuit.ringkit.MeasuredCoverage
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.NapDetection
import io.github.opencircuit.ringkit.NightStager
import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepNightKey
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.ringkit.TemperatureSample
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.HistoryJournal
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepNightExtras
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.SleepStoreException
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.SyncLog
import io.github.opencircuit.store.SyncLogEntry
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * [HistoryStore] over the on-device database: pages go into the history journal, and a commit
 * moves them into the epoch archive and the sample tables, then stages the nights they complete.
 *
 * A commit reads the journal and plans it ([CommitPlanner], PORTING.md D-267): only records the
 * sync is drained through are released; they go oldest first by counter, across both channels, in
 * chunks of at most [chunkRecords]. Each chunk is ONE write transaction: it merges the chunk's
 * records into the epoch archive bounded by `now` plus one day (PORTING.md D-44; the same day the
 * store's ingest allows into the future), stores their samples in one ingest, and deletes exactly
 * the journal rows the chunk consumes. If a chunk throws, nothing of it is stored and its rows stay
 * in the journal; the chunks before it are kept. Pages holding a record held back stay for a later
 * commit, as does a page that arrives while the commit runs.
 *
 * Then nights (PORTING.md D-269, D-270): the sync's [SyncEvidence] and the released records of pages
 * stored outside any drain go through `HistoryCommitGate.decide` (D-43); on STAGE or
 * RESTAGE_FROM_ARCHIVE every complete night of the stored archive past its `stagedThrough` mark is
 * staged ([NightStager]) and saved, oldest first, so each night's baseline sees the nights before
 * it. The first night the store defers (its one-time move of stored nights not done, or a failed
 * save) stops the loop: it and every later night wait, counted, in the archive. `stagedThrough`
 * advances over the nights handled in sequence; the newest saved night is published as the pending
 * segments; an edited night's provenance is re-derived once if any night was saved; naps are found
 * in the records this commit released. Nothing of this runs once the commit is told to stop.
 *
 * [database] opens the store on first use (the app opens it once for the process); [ringId] names
 * the ring's journal and archive; [zone] is where a local day starts for the step totals and where
 * a night is judged overnight. [insideChunk] runs at the end of each chunk's transaction, with the
 * chunk's index: a seam for the tests that fail a chunk midway (it does nothing in the app).
 * [nights] is the store's sleep half as the staging uses it.
 */
class StoreHistory(
    private val database: suspend () -> StoreDatabase,
    private val ringId: String,
    private val zone: () -> ZoneId,
    private val chunkRecords: Int = CommitPlanner.CHUNK_RECORDS,
    private val insideChunk: suspend (index: Int) -> Unit = {},
    private val nights: (StoreDatabase) -> NightWriter = { StoreNightWriter(SleepStore(it)) },
) : HistoryStore {

    override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long =
        HistoryJournal(database()).append(ringId, page, receivedAt, drainId)

    override suspend fun commit(now: Instant, drained: CommitPlanner.Drained, keepGoing: () -> Boolean, evidence: SyncEvidence): CommitResult {
        val db = database()
        val journal = HistoryJournal(db)
        val blobs = BlobStore(db)
        val samples = LocalStore(db)
        val read = journal.read(ringId)
        val opcodes = read.entries.associate { it.seq to (it.page.firstOrNull()?.toInt()?.and(0xFF)) }
        val pages = read.entries.map { CommitPlanner.Page(it.seq, if (opcodes[it.seq] == PAGE_4C) BulkSleep.recordsFromPage(it.page) else emptyList()) }
        val plan = CommitPlanner.plan(pages, drained, read.unreadableSeqs, chunkRecords)

        var result = CommitResult(heldBack = plan.heldBackRecords, pagesKept = plan.kept.size)
        val released = ArrayList<BulkRecord>()
        for ((index, chunk) in plan.chunks.withIndex()) {
            if (index > 0 && !keepGoing()) {
                // Stopped between transactions: what is left stays in the journal for the next commit.
                return result.copy(chunksLeft = plan.chunks.size - index)
            }
            val stored = db.withWriteTransaction {
                val merge = blobs.mergeEpochArchive(ringId, chunk.records, notAfter = now.plus(NOT_AFTER_ALLOWANCE), now = now)
                // The HRV gate is calibrated on the whole archive, never on one chunk (BulkSleep.samples).
                val ingested = samples.ingest(BulkSleep.samples(chunk.records, calibratedBy = merge.records), now, zone())
                journal.delete(ringId, chunk.consumed)
                insideChunk(index)
                Pair(merge.droppedAfterBound, ingested.size)
            }
            released += chunk.records
            val consumedPages = chunk.consumed.filter { it in opcodes }
            result = result.copy(
                pages = result.pages + consumedPages.size,
                records = result.records + chunk.records.size,
                samplesStored = result.samplesStored + stored.second,
                droppedAfterBound = result.droppedAfterBound + stored.first,
                unreadablePages = result.unreadablePages + (chunk.consumed.size - consumedPages.size),
                pagesNotKept = result.pagesNotKept + consumedPages.count { opcodes[it] != PAGE_4C },
                chunks = result.chunks + 1,
            )
        }
        if (!keepGoing()) return result

        // Records of pages stored with no drain running, released now: the gate's adopted records
        // (the journal replaces upstream's unattributed-page buffer).
        val consumed = plan.chunks.flatMap { it.consumed }.toHashSet()
        val outsideDrain = read.entries.filter { it.drainId == null && it.seq in consumed }.map { it.seq }.toHashSet()
        val adopted = pages.filter { it.seq in outsideDrain }.flatMap { p -> p.records.map { it.counter } }.toHashSet().size
        val decision = HistoryCommitGate.decide(evidence.sleepOutcome, evidence.sleepRecordsAdded, adopted, evidence.nightRecordsOnOtherChannels)
        if (decision == HistoryCommitGate.Decision.SKIP) return result.copy(staging = decision)
        return stageNights(db, blobs, samples, now, drained, released, result.copy(staging = decision))
    }

    /** The staging half of [commit]: every complete night not yet staged, oldest first, then naps. */
    private suspend fun stageNights(
        db: StoreDatabase,
        blobs: BlobStore,
        samples: LocalStore,
        now: Instant,
        drained: CommitPlanner.Drained,
        released: List<BulkRecord>,
        result: CommitResult,
    ): CommitResult {
        val zone = zone()
        val store = nights(db)
        val archive = blobs.loadEpochArchive(ringId)
        // "Drained through" is a time only when every channel had nothing left: the commit's own.
        val drainedThrough = if (drained == CommitPlanner.Drained.Everything) now else null
        val toStage = CommitPlanner.nightsToStage(archive.records, zone, drainedThrough, archive.marks.stagedThrough)

        var stagedThrough = archive.marks.stagedThrough
        var staged = 0
        var waiting = 0
        var fault: String? = null
        var newest: Pair<List<SleepSegment>, List<SleepSegment>>? = null
        for ((index, night) in toStage.withIndex()) {
            val nightEnd = night.last().date()
            val temps = temperatures(samples, night.first().date(), nightEnd)
            val recent = store.recentSleepSummaries(RECENT_NIGHTS).map { NightStager.RecentNight(it.night, it.hrDeep, it.skinTempC) }
            val segs = NightStager.stage(night, archive.records, zone, temps, NightStager.personalBaseline(night, recent, zone))
            val start = segs.minOfOrNull { it.start }
            val end = segs.maxOfOrNull { it.end }
            val key = if (start != null && end != null) SleepNightKey.night(start, end, zone) else null
            if (start == null || end == null || key == null) {
                // Not an overnight night (or nowhere to file it): handled, nothing to save.
                stagedThrough = nightEnd
                continue
            }
            val summary = SleepStaging.summary(segs)
            val asleep = SleepStaging.sleepWindow(segs)
            val x = NightStager.extras(summary, segs, night, start, end, temperatures(samples, start, end), recent, zone)
            val extras = SleepNightExtras(
                skinTempC = x.skinTempC,
                skinTempWithheld = x.skinTempWithheld,
                sleepScore = x.sleepScore,
                stressScore = x.stressScore,
                hrByStage = x.hrByStage,
                movementLevels = x.movementLevels,
                hypnogram = segs,
            )
            try {
                store.saveSleepSummary(
                    summary, key, start, end,
                    asleep?.onset ?: SleepEdit.DISTANT_PAST, asleep?.wake ?: SleepEdit.DISTANT_PAST,
                    extras, now, zone,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // NightKeyMigrationPending holds every save in the store, so the loop stops here;
                // any other failed save stops it too. Both leave this night and the rest waiting.
                if (e !is SleepStoreException.NightKeyMigrationPending) fault = e::class.java.simpleName
                waiting = toStage.size - index
                break
            }
            staged++
            stagedThrough = nightEnd
            newest = BulkSleep.sleepSegments(night, temperatures = temps) to segs
        }
        if (stagedThrough != archive.marks.stagedThrough) {
            blobs.saveEpochArchiveMarks(ringId, archive.marks.copy(stagedThrough = stagedThrough), now)
        }
        newest?.let { (coarse, hypnogram) -> blobs.savePendingSleepSegments(ringId, coarse, hypnogram, now) }
        if (staged > 0) {
            try {
                // Upstream reads the whole archive raw here (RingSession.swift:2093-2109): presence only.
                store.rederiveEditedNightProvenance(MeasuredCoverage.ofRecords(blobs.loadEpochArchive(ringId).records), now, zone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fault = fault ?: e::class.java.simpleName
            }
        }
        fault = saveNaps(store, samples, released, now, zone) ?: fault
        return result.copy(nightsStaged = staged, nightsWaiting = waiting, stagingFault = fault)
    }

    /**
     * Naps in the records this commit released (upstream `persistNaps`, RingSession.swift:2189-2250):
     * the main sleep block excluded, never one sharing time with a nap the wearer added or edited.
     * The store refuses a nap that shares time with a stored night. Returns the failure, if any.
     */
    private suspend fun saveNaps(store: NightWriter, samples: LocalStore, released: List<BulkRecord>, now: Instant, zone: ZoneId): String? {
        if (released.isEmpty()) return null
        val temps = temperatures(samples, released.first().date(), released.last().date())
        val naps = NapDetection.naps(released, BulkSleep.mainSleep(released, temperatures = temps), zone, temps)
        return try {
            for (nap in naps) {
                val day = nap.start.atZone(zone).toLocalDate()
                val dayStart = day.atStartOfDay(zone).toInstant()
                val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
                val wearers = store.naps(dayStart, dayEnd).filter { it.isManuallyEdited || it.isManuallyAdded }
                if (wearers.any { nap.start < it.effectiveEnd && nap.end > it.effectiveStart }) continue
                // Whole minutes, half away from zero (Swift's `rounded()`); a nap's asleep time is never negative.
                val asleepMin = Math.round(nap.asleep.seconds / 60.0).toInt()
                store.saveNap(nap.start, nap.end, asleepMin, nap.isLongNap, nap.segments, now, zone)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e::class.java.simpleName
        }
    }

    override suspend fun syncLog(): List<SyncLogEntry> = SyncLog(database()).read(ringId).entries

    override suspend fun appendSyncLog(entry: SyncLogEntry, now: Instant) {
        SyncLog(database()).append(ringId, entry, now)
    }

    override suspend fun storedData(): StoredData? {
        val db = database()
        val samples = LocalStore(db)
        val oldest = HISTORY_KINDS.mapNotNull { samples.earliestSample(it)?.start }.minOrNull()
        val newest = HISTORY_KINDS.mapNotNull { samples.latestSample(it)?.start }.maxOrNull()
        val sleep = SleepStore(db)
        // Every night ever keyed.
        val nights = sleep.sleepSummaries(Instant.EPOCH, FAR_FUTURE).size
        val last = sleep.latestSleepSummary()?.let { StoredLastNight(it.currentOnset, it.currentWake, it.asleepMin) }
        if (oldest == null && nights == 0) return null
        return StoredData(oldest, newest, nights, last)
    }

    /** The skin temperatures stored between [from] and [to] (none until live readings are kept). */
    private suspend fun temperatures(samples: LocalStore, from: Instant, to: Instant): List<TemperatureSample> =
        samples.samples(MetricKind.TEMPERATURE, from, to).map { TemperatureSample(it.start, it.value) }

    private companion object {
        const val PAGE_4C = 0x4C

        /** How far past `now` a record may be dated and still be kept (the store's ingest allows the same). */
        val NOT_AFTER_ALLOWANCE: Duration = Duration.ofDays(1)

        /** Stored nights the baseline and the skin-temperature extras read (upstream reads 8 and 40). */
        const val RECENT_NIGHTS = 40

        /** The kinds a history record's samples are stored as: the card's stored range spans them. */
        val HISTORY_KINDS = listOf(MetricKind.HEART_RATE, MetricKind.HRV_SDNN, MetricKind.SPO2, MetricKind.RESPIRATORY_RATE)

        /** Past every night key. */
        val FAR_FUTURE: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)
    }
}
