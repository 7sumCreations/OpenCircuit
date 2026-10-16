package io.github.opencircuit.app.health

import io.github.opencircuit.health.HealthActivityStore
import io.github.opencircuit.health.HealthFailureStore
import io.github.opencircuit.health.HealthSampleStore
import io.github.opencircuit.health.HealthSleepStore
import io.github.opencircuit.health.NightMirror
import io.github.opencircuit.health.NightWindow
import io.github.opencircuit.health.QueuedNightEdit
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.SampleRow
import io.github.opencircuit.health.StepRow
import io.github.opencircuit.health.StoredNapToWrite
import io.github.opencircuit.health.StoredSleepNight
import io.github.opencircuit.health.UnreadableEditQueueException
import io.github.opencircuit.health.WriteGenerationStore
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.MirroredNight
import io.github.opencircuit.store.PendingSleepReconcile
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.SleepStoreException
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoredNapRecord
import io.github.opencircuit.store.StoredStepRow
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.keyText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The writer's view of the app's store: pending samples, the samples of an hour, the Health Connect marks. */
class StoreHealthSamples(private val database: suspend () -> StoreDatabase) : HealthSampleStore {
    private suspend fun store() = LocalStore(database())

    override suspend fun pendingSamples(kind: MetricKind): List<QuantitySample> = store().pendingHealthSamples(kind)

    override suspend fun samples(kind: MetricKind, from: Instant, until: Instant): List<QuantitySample> = store().samples(kind, from, until)

    override suspend fun pendingRows(kind: MetricKind): List<SampleRow> =
        store().pendingHealthSampleRows(kind).map { SampleRow(it.id, it.sample) }

    override suspend fun rows(kind: MetricKind, from: Instant, until: Instant): List<SampleRow> =
        store().sampleRows(kind, from, until).map { SampleRow(it.id, it.sample) }

    override suspend fun nights(from: Instant, until: Instant): List<NightWindow> =
        SleepStore(database()).sleepSummaries(from, until).map { NightWindow(it.keyText, it.night, it.inBedStart, it.inBedEnd, it.skinTempC) }

    override suspend fun markWritten(samples: List<QuantitySample>) = store().markHealthWritten(samples)
}

/**
 * The writer's view of the store's step rows and of the marks it keeps for steps, energy and the
 * daily records (resting heart rate, basal metabolic rate) in the store's key-value table.
 */
class StoreHealthActivity(
    private val database: suspend () -> StoreDatabase,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) : HealthActivityStore {
    private suspend fun store() = LocalStore(database())

    private suspend fun blobs() = BlobStore(database())

    override suspend fun pendingSteps(): List<StepRow> = store().pendingStepSampleRows().map { it.toStepRow() }

    override suspend fun steps(from: Instant, until: Instant): List<StepRow> = store().stepSampleRows(from, until).map { it.toStepRow() }

    override suspend fun markStepsWritten(ids: List<Long>) = store().markStepSamplesWritten(ids)

    override suspend fun daySteps(day: LocalDate): Long {
        val zone = zone()
        return store().todaySteps(day.atStartOfDay(zone).toInstant(), zone)
    }

    override suspend fun dayMark(type: RecordType): LocalDate? = blobs().loadHealthDayMark(type.name)

    override suspend fun setDayMark(type: RecordType, day: LocalDate) = blobs().saveHealthDayMark(type.name, day, now())

    override suspend fun energyWritten(day: LocalDate): Map<Instant, Double> = blobs().loadHealthEnergyWritten(day)

    override suspend fun setEnergyWritten(day: LocalDate, written: Map<Instant, Double>) = blobs().saveHealthEnergyWritten(day, written, now())

    override suspend fun forgetEnergyBefore(day: LocalDate) = blobs().forgetHealthEnergyWrittenBefore(day)

    private fun StoredStepRow.toStepRow() = StepRow(id, sample.start, sample.end, sample.delta)
}

/**
 * The writer's view of the store's sleep: the stored nights with their timelines, the queued edits,
 * the naps, and the night marks (record ids, mirror, the sleep mark, the edit marks). An unreadable
 * queue of edits is passed on as [UnreadableEditQueueException], never read as empty.
 */
class StoreHealthSleep(
    private val database: suspend () -> StoreDatabase,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) : HealthSleepStore {
    private suspend fun sleep() = SleepStore(database())

    override suspend fun nights(): List<StoredSleepNight> =
        sleep().sleepSummaries(SleepEdit.DISTANT_PAST, LAST_INSTANT).map {
            StoredSleepNight(it.keyText, it.night, it.isManuallyEdited, it.hypnogram, it.inBedEnd)
        }

    override suspend fun mirror(night: Instant): NightMirror? =
        sleep().mirroredNight(night)?.let { NightMirror(it.signature, it.spanStart, it.spanEnd) }

    override suspend fun setMirror(night: Instant, mirror: NightMirror) =
        sleep().setMirroredNight(night, MirroredNight(mirror.signature, mirror.spanStart, mirror.spanEnd), now())

    override suspend fun recordIds(night: Instant): List<String>? = sleep().sleepHealthRecordIds(night)

    override suspend fun setRecordIds(night: Instant, ids: List<String>) = sleep().setSleepHealthRecordIds(night, ids, now())

    override suspend fun markNightWritten(segments: List<SleepSegment>) {
        LocalStore(database()).markSleepWritten(segments)
        sleep().markSleepEditHealthCovered(segments)
    }

    override suspend fun markEditWritten(night: Instant, segments: List<SleepSegment>, recordedEnd: Instant) {
        LocalStore(database()).forceSleepCursorAtLeast(maxOf(recordedEnd, segments.maxOfOrNull { it.end } ?: recordedEnd))
        sleep().markSleepEditHealthWritten(night, segments)
        sleep().markSleepEditHealthCovered(segments)
    }

    override suspend fun queuedEdits(): List<QueuedNightEdit> = unreadableAsQueueError {
        sleep().pendingSleepReconciles().map { QueuedNightEdit(it.night, it.inBedStart, it.sleepOnset, it.sleepWake, it.segments) }
    }

    override suspend fun clearQueuedEdit(edit: QueuedNightEdit): Boolean = unreadableAsQueueError {
        sleep().clearPendingSleepReconcileIfUnchanged(
            PendingSleepReconcile(edit.night, edit.inBedStart, edit.sleepOnset, edit.sleepWake, edit.segments), zone(), now(),
        )
    }

    override suspend fun pendingNaps(): List<StoredNapToWrite> = sleep().pendingNaps().map { it.toWrite() }

    override suspend fun markNapWritten(start: Instant) = sleep().markNapWritten(start)

    override suspend fun autoNaps(from: Instant, to: Instant): List<StoredNapToWrite> = sleep().autoNaps(from, to).map { it.toWrite() }

    override suspend fun deleteNaps(starts: List<Instant>) {
        if (starts.isEmpty()) return
        val store = sleep()
        val naps = store.naps(starts.min(), starts.max().plusMillis(1)).filter { it.start in starts }
        store.deleteNaps(naps)
    }

    private fun StoredNapRecord.toWrite() = StoredNapToWrite(start, effectiveStart, effectiveEnd, segments, isManuallyAdded, healthWritten)

    private suspend fun <T> unreadableAsQueueError(block: suspend () -> T): T =
        try {
            block()
        } catch (e: SleepStoreException.UnreadablePendingReconcile) {
            throw UnreadableEditQueueException(e.message ?: "the queue of sleep edits waiting for Health cannot be read")
        }

    private companion object {
        /** The latest instant the store can hold (whole epoch milliseconds). */
        val LAST_INSTANT: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)
    }
}

/**
 * The per-type write-failure map, kept in the store's key-value table by record type name. A
 * stored map that cannot be read is logged and read as empty: it only drives the card's warning,
 * and the next write's outcome replaces it. A name this version does not know is kept as it is
 * only until the next save.
 */
class StoreHealthFailures(
    private val database: suspend () -> StoreDatabase,
    private val now: () -> Instant,
    private val log: (String) -> Unit,
) : HealthFailureStore {
    override suspend fun load(): Map<RecordType, Instant> = when (val stored = BlobStore(database()).loadHealthWriteFailures()) {
        null -> emptyMap()
        is Decoded.Readable -> stored.value.mapNotNull { (name, at) -> RecordType.entries.firstOrNull { it.name == name }?.let { it to at } }.toMap()
        is Decoded.Unreadable -> {
            log("Health Connect: the stored write failures cannot be read (${stored.reason}); starting from none")
            emptyMap()
        }
    }

    override suspend fun save(failures: Map<RecordType, Instant>) =
        BlobStore(database()).saveHealthWriteFailures(failures.mapKeys { it.key.name }, now())
}

/** What the card's access state keeps in the store: the "permission screen shown" flag and the failure map. */
class StoreHealthAccessMemory(
    private val database: suspend () -> StoreDatabase,
    private val now: () -> Instant,
    private val failureStore: HealthFailureStore,
) : HealthAccessMemory {
    override suspend fun asked(): Boolean = BlobStore(database()).loadHealthPermissionsRequested()

    override suspend fun markAsked() = BlobStore(database()).saveHealthPermissionsRequested(now())

    override suspend fun failures(): Map<RecordType, Instant> = failureStore.load()
}

/**
 * The write generation, kept in the store's key-value table. A stored value that cannot be read
 * fails the flush instead of starting again from 1: every record already in Health Connect carries
 * a higher version, so each later write would be ignored.
 */
class StoreWriteGenerations(
    private val database: suspend () -> StoreDatabase,
    private val now: () -> Instant,
) : WriteGenerationStore {
    override suspend fun load(): Long? = when (val stored = BlobStore(database()).loadHealthWriteGeneration()) {
        null -> null
        is Decoded.Readable -> stored.value
        is Decoded.Unreadable -> throw IllegalStateException("the stored write generation cannot be read: ${stored.reason}")
    }

    override suspend fun save(generation: Long) = BlobStore(database()).saveHealthWriteGeneration(generation, now())
}
