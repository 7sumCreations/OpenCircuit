package io.github.opencircuit.app.health

import io.github.opencircuit.health.HealthSampleStore
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
