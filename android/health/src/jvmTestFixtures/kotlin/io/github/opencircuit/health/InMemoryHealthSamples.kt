package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import java.time.Instant

/**
 * The store side of the writer, in memory, with the store's own rules (`:store` `LocalStore`
 * `pendingHealthSamples` / `pendingHealthSampleRows` / `sampleRows` / `markHealthWritten`): pending
 * is every sample of the kind above zero and newer than the kind's mark, oldest first (ties in row
 * order); a mark only moves forward; each added sample gets the next row id, as the store's
 * autoincrement does. The app's tests use the real store; this one keeps `:health`'s tests free of
 * `:store`.
 */
class InMemoryHealthSamples : HealthSampleStore {
    private val lock = Any()
    private val stored = mutableListOf<SampleRow>()
    private val marks = mutableMapOf<MetricKind, Instant>()
    private var nextId = 1L
    private val nightList = mutableListOf<NightWindow>()

    fun add(vararg samples: QuantitySample) = synchronized(lock) { samples.forEach { stored += SampleRow(nextId++, it) } }

    fun mark(kind: MetricKind): Instant? = synchronized(lock) { marks[kind] }

    /** Stores nights (the app's sleep summaries), keyed by [NightWindow.night]. */
    fun addNights(vararg added: NightWindow) = synchronized(lock) { nightList += added }

    override suspend fun nights(from: Instant, until: Instant): List<NightWindow> = synchronized(lock) {
        nightList.filter { !it.night.isBefore(from) && it.night.isBefore(until) }.sortedBy { it.night }
    }

    override suspend fun pendingSamples(kind: MetricKind): List<QuantitySample> = pendingRows(kind).map { it.sample }

    override suspend fun samples(kind: MetricKind, from: Instant, until: Instant): List<QuantitySample> = rows(kind, from, until).map { it.sample }

    override suspend fun pendingRows(kind: MetricKind): List<SampleRow> = synchronized(lock) {
        val mark = marks[kind]
        stored.filter { it.sample.kind == kind && it.sample.value > 0 && (mark == null || it.sample.start.isAfter(mark)) }.sortedBy { it.sample.start }
    }

    override suspend fun rows(kind: MetricKind, from: Instant, until: Instant): List<SampleRow> = synchronized(lock) {
        stored.filter { it.sample.kind == kind && !it.sample.start.isBefore(from) && it.sample.start.isBefore(until) }.sortedBy { it.sample.start }
    }

    override suspend fun markWritten(samples: List<QuantitySample>): Unit = synchronized(lock) {
        for ((kind, ofKind) in samples.groupBy { it.kind }) {
            val newest = ofKind.maxOf { it.start }
            val current = marks[kind]
            if (current == null || newest.isAfter(current)) marks[kind] = newest
        }
    }
}
