package io.github.opencircuit.app.health

import io.github.opencircuit.health.HealthFailureStore
import io.github.opencircuit.health.HealthSampleStore
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.NightWindow
import io.github.opencircuit.health.SampleRow
import io.github.opencircuit.health.WriteGenerationStore
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.keyText
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.codec.Decoded
import java.time.Instant

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
