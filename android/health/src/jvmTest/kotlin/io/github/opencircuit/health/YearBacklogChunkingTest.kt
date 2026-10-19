package io.github.opencircuit.health

import io.github.opencircuit.health.FakeHealthConnect.Call
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A year of stored readings written to a Health Connect that enforces the platform's call rules
 * (1000 calls per 15 minutes on an injected clock, the 5 MB call limit, one type per call): the
 * writer sends calls of at most 1000 records and the estimated byte bound, at most 300 calls a
 * flush, stops quietly as catching up, and the next flush goes on from the marks, so every record
 * lands exactly once. A synthetic year: heart rate and breathing rate every 2 minutes, HRV every 10
 * minutes, SpO2 every hour.
 */
class YearBacklogChunkingTest {
    private val clock = TestClock(Instant.parse("2026-10-17T12:00:00Z"))
    private val yearStart: Instant = clock.now().minus(Duration.ofDays(365)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
    private val yearEnd: Instant = yearStart.plus(Duration.ofDays(365))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val generations = InMemoryWriteGenerations()
    private fun writer() = HealthWriter(
        hc, samples, WriteGeneration(generations), InMemoryHealthFailures(), clock::now, { ZoneOffset.UTC }, { RingDevice("Gen 2") },
    )

    /** One reading of [kind] every [every] over the year, with a value inside the platform's range. */
    private fun every(kind: MetricKind, every: Duration, value: Double): Int {
        var t = yearStart
        val batch = mutableListOf<QuantitySample>()
        while (t.isBefore(yearEnd)) {
            batch += QuantitySample(kind, t, value = value)
            t = t.plus(every)
        }
        samples.add(*batch.toTypedArray())
        return batch.size
    }

    private val expected = LinkedHashMap<RecordType, Int>()

    private fun storeTheYear() {
        every(MetricKind.HEART_RATE, Duration.ofMinutes(2), 62.0)
        expected[RecordType.HEART_RATE] = 365 * 24 // one record per UTC hour
        expected[RecordType.RESPIRATORY_RATE] = every(MetricKind.RESPIRATORY_RATE, Duration.ofMinutes(2), 14.0)
        expected[RecordType.HEART_RATE_VARIABILITY_RMSSD] = every(MetricKind.HRV_SDNN, Duration.ofMinutes(10), 41.0)
        expected[RecordType.OXYGEN_SATURATION] = every(MetricKind.SPO2, Duration.ofHours(1), 0.97)
    }

    private fun assertEveryCallWithinTheLimits() {
        for (call in hc.calls.filter { it.kind == Call.Kind.INSERT }) {
            assertTrue(call.count <= 1000, "a call of ${call.count} records")
        }
        // The byte bound, on what Health Connect holds (each call's records, by its ids).
        val byId = expected.keys.flatMap { type -> hc.records(type) }.associateBy { it.type to it.meta.clientRecordId }
        for (call in hc.calls.filter { it.kind == Call.Kind.INSERT && it.outcome == Call.Outcome.OK }) {
            val bytes = call.ids.sumOf { RecordSize.estimateBytes(byId.getValue(call.type to it)) }
            assertTrue(bytes <= 4L * 1024 * 1024, "a call estimated at $bytes bytes")
        }
    }

    @Test
    fun aYearGoesInBoundedCallsOverTwoFlushesAndEveryRecordLandsOnce() = runBlocking<Unit> {
        storeTheYear()

        val first = writer().flush()

        assertTrue(first.catchingUp, "more than one flush's budget of calls")
        assertEquals(300, hc.calls.size)
        assertTrue(first.failures.isEmpty() && first.rejected.isEmpty())

        val second = writer().flush()

        assertFalse(second.catchingUp)
        assertEquals(334, hc.calls.size, "9 heart-rate + 263 breathing-rate + 53 HRV + 9 SpO2 calls, none sent twice")
        for ((type, count) in expected) assertEquals(count, hc.records(type).size, "$type")
        assertEquals(expected.values.sum(), hc.calls.filter { it.outcome == Call.Outcome.OK }.sumOf { it.count }, "no record was sent twice")
        assertTrue(hc.calls.all { it.outcome == Call.Outcome.OK })
        assertEveryCallWithinTheLimits()
        for (kind in listOf(MetricKind.HEART_RATE, MetricKind.RESPIRATORY_RATE, MetricKind.HRV_SDNN, MetricKind.SPO2)) {
            assertEquals(emptyList(), samples.pendingSamples(kind), "every $kind reading marked")
        }

        writer().flush()
        assertEquals(334, hc.calls.size, "nothing left: nothing sent")
    }

    @Test
    fun theQuotaStopsAFlushQuietlyAndTheNextWindowFinishesIt() = runBlocking<Unit> {
        storeTheYear()
        // Something else of the app spent 990 calls of this 15-minute window.
        repeat(990) { hc.deleteByClientIds(RecordType.STEPS, listOf("oc:steps:none")) }
        val before = hc.calls.size

        val stopped = writer().flush()

        assertTrue(stopped.catchingUp)
        assertTrue(stopped.failures.isEmpty(), "the quota is catching up, never a failure")
        val made = hc.calls.drop(before)
        assertEquals(11, made.size, "10 accepted, then the 11th refused for the quota")
        assertEquals(Call.Outcome.RETRY_LATER, made.last().outcome)

        val stillRefused = writer().flush()
        assertTrue(stillRefused.catchingUp, "the same window: still refused")

        clock.advance(Duration.ofMinutes(15))
        writer().flush()
        writer().flush()

        for ((type, count) in expected) assertEquals(count, hc.records(type).size, "$type")
        assertEquals(expected.values.sum(), hc.calls.filter { it.kind == Call.Kind.INSERT && it.outcome == Call.Outcome.OK }.sumOf { it.count })
        assertEveryCallWithinTheLimits()
    }
}
