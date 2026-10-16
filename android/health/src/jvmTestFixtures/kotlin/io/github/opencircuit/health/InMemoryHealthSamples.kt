package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import java.time.Instant

/**
 * The store side of the writer, in memory, with the store's own rules (`:store` `LocalStore`
 * `pendingHealthSamples` / `markHealthWritten`): pending is every sample of the kind above zero and
 * newer than the kind's mark, oldest first (ties in insertion order); a mark only moves forward.
 * The app's tests use the real store; this one keeps `:health`'s tests free of `:store`.
 */
class InMemoryHealthSamples : HealthSampleStore {
    private val lock = Any()
    private val stored = mutableListOf<QuantitySample>()
    private val marks = mutableMapOf<MetricKind, Instant>()

    fun add(vararg samples: QuantitySample) = synchronized(lock) { stored += samples }

    fun mark(kind: MetricKind): Instant? = synchronized(lock) { marks[kind] }

    override suspend fun pendingSamples(kind: MetricKind): List<QuantitySample> = synchronized(lock) {
        val mark = marks[kind]
        stored.filter { it.kind == kind && it.value > 0 && (mark == null || it.start.isAfter(mark)) }.sortedBy { it.start }
    }

    override suspend fun samples(kind: MetricKind, from: Instant, until: Instant): List<QuantitySample> = synchronized(lock) {
        stored.filter { it.kind == kind && !it.start.isBefore(from) && it.start.isBefore(until) }.sortedBy { it.start }
    }

    override suspend fun markWritten(samples: List<QuantitySample>): Unit = synchronized(lock) {
        for ((kind, ofKind) in samples.groupBy { it.kind }) {
            val newest = ofKind.maxOf { it.start }
            val current = marks[kind]
            if (current == null || newest.isAfter(current)) marks[kind] = newest
        }
    }
}
