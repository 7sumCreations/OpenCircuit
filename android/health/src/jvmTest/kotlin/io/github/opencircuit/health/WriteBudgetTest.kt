package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How one flush spends Health Connect's write quota (1000 calls per 15 minutes, 8000 per day, for
 * every app call alike): at most 300 write calls per flush, then a quiet stop as "catching up"
 * that the next flush resumes; the platform's own quota refusal stops it the same way; a call the
 * platform refuses for a bad record is retried once, record by record, so only the bad record is
 * dropped (and counted). Asserted on the fake's call log, what it holds and the store's marks.
 */
class WriteBudgetTest {

    private val t0 = Instant.parse("2026-10-15T00:00:00Z")
    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val generations = MemoryGenerations()

    private fun writer(port: HealthConnectPort = hc) =
        HealthWriter(port, samples, WriteGeneration(generations), InMemoryHealthFailures(), clock::now, { ZoneOffset.UTC }, { RingDevice("Gen 2") })

    private class MemoryGenerations(var value: Long? = null) : WriteGenerationStore {
        override suspend fun load(): Long? = value
        override suspend fun save(generation: Long) { value = generation }
    }

    private fun rr(i: Int, bpm: Double = 14.0) = QuantitySample(MetricKind.RESPIRATORY_RATE, t0.plusSeconds(60L * i), value = bpm)

    /** Refuses, as a bad record, every call that holds a record [bad] says is bad. */
    private inner class Refusing(private val bad: (HealthRecord) -> Boolean) : HealthConnectPort by hc {
        override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
            if (records.any(bad)) {
                hc.insert(type, emptyList()) // the call is spent, as on the phone
                throw HealthInvalidRecordException("a record breaks a platform rule")
            }
            return hc.insert(type, records)
        }
    }

    @Test
    fun aRefusedCallIsRetriedRecordByRecordOnceAndOnlyTheBadRecordIsDropped() = runTest {
        (1..5).forEach { samples.add(rr(it, 10.0 + it)) }
        val port = Refusing { (it as RespiratoryRate).breathsPerMinute == 13.0 }

        val result = writer(port).flush()

        assertEquals(listOf(0, 1, 1, 0, 1, 1), hc.calls.map { it.count }, "the refused call of five, then each record once (a refused call stores nothing)")
        assertEquals(listOf(11.0, 12.0, 14.0, 15.0), hc.records(RecordType.RESPIRATORY_RATE).map { (it as RespiratoryRate).breathsPerMinute })
        assertEquals(mapOf(RecordType.RESPIRATORY_RATE to 1), result.rejected)
        assertEquals(mapOf(RecordType.RESPIRATORY_RATE to 4), result.written)
        assertEquals(emptyMap(), result.failures, "a record the platform refuses is dropped, not a failure of the type")
        assertEquals(rr(5).start, samples.mark(MetricKind.RESPIRATORY_RATE))
    }

    @Test
    fun aRefusedSingleRecordCallIsNotTriedAgain() = runTest {
        samples.add(rr(1))

        val result = writer(Refusing { true }).flush()

        assertEquals(1, hc.calls.size)
        assertEquals(mapOf(RecordType.RESPIRATORY_RATE to 1), result.rejected)
        assertEquals(emptyMap(), result.written)
    }

    @Test
    fun aFlushMakesAtMostThreeHundredWriteCallsThenStopsQuietlyAndTheNextFlushGoesOn() = runTest {
        // 350 readings in one call of 350; the platform refuses any call of more than one, so after
        // the refused call each reading is a call of its own: 1 + 299 = 300 calls.
        (1..350).forEach { samples.add(rr(it)) }
        val onlySingles = object : HealthConnectPort by hc {
            override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
                if (records.size > 1) {
                    hc.insert(type, emptyList())
                    throw HealthInvalidRecordException("too many")
                }
                return hc.insert(type, records)
            }
        }

        val first = writer(onlySingles).flush()

        assertEquals(300, hc.calls.size)
        assertEquals(299, hc.records(RecordType.RESPIRATORY_RATE).size)
        assertTrue(first.catchingUp)
        assertEquals(emptyMap(), first.failures)
        assertEquals(emptyMap(), first.rejected, "nothing was refused as a record by the platform; the rest just waits")
        assertEquals(rr(299).start, samples.mark(MetricKind.RESPIRATORY_RATE))

        val second = writer(onlySingles).flush()

        assertFalse(second.catchingUp)
        assertEquals(350, hc.records(RecordType.RESPIRATORY_RATE).size)
        assertEquals(300 + 1 + 51, hc.calls.size)
        assertEquals(rr(350).start, samples.mark(MetricKind.RESPIRATORY_RATE))
    }

    @Test
    fun theBudgetIsSharedByEveryTypeOfTheFlush() = runTest {
        // Heart rate takes the first call; 300 single-reading calls would be one too many.
        samples.add(QuantitySample(MetricKind.HEART_RATE, t0, value = 60.0))
        (1..300).forEach { samples.add(rr(it)) }
        val onlySingles = object : HealthConnectPort by hc {
            override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
                if (type == RecordType.RESPIRATORY_RATE && records.size > 1) {
                    hc.insert(type, emptyList())
                    throw HealthInvalidRecordException("too many")
                }
                return hc.insert(type, records)
            }
        }

        val result = writer(onlySingles).flush()

        assertEquals(300, hc.calls.size)
        assertEquals(1 + 298, result.recordsWritten)
        assertTrue(result.catchingUp)
    }

    @Test
    fun thePlatformsQuotaStopsTheFlushQuietlyAndTheNextWindowResumesIt() = runTest {
        // 998 of the 1000 calls of this 15-minute window are already spent.
        repeat(998) { hc.insert(RecordType.STEPS, emptyList()) }
        samples.add(
            QuantitySample(MetricKind.HEART_RATE, t0, value = 60.0),
            QuantitySample(MetricKind.HRV_SDNN, t0, value = 40.0),
            QuantitySample(MetricKind.SPO2, t0, value = 0.97),
            rr(1),
        )

        val limited = writer().flush()

        assertTrue(limited.catchingUp)
        assertEquals(emptyMap(), limited.failures, "the quota is not a failure")
        assertEquals(setOf(RecordType.HEART_RATE, RecordType.HEART_RATE_VARIABILITY_RMSSD), limited.written.keys)
        assertEquals(1, hc.calls.count { it.outcome == FakeHealthConnect.Call.Outcome.RETRY_LATER }, "nothing more is tried after the refusal")
        assertEquals(null, samples.mark(MetricKind.SPO2))
        assertEquals(null, samples.mark(MetricKind.RESPIRATORY_RATE))

        clock.advance(Duration.ofMinutes(15))
        val resumed = writer().flush()

        assertFalse(resumed.catchingUp)
        assertEquals(setOf(RecordType.OXYGEN_SATURATION, RecordType.RESPIRATORY_RATE), resumed.written.keys)
    }

    @Test
    fun oneMillisecondBeforeTheWindowEndsTheQuotaStillHolds() = runTest {
        repeat(1000) { hc.insert(RecordType.STEPS, emptyList()) }
        samples.add(rr(1))

        clock.advance(Duration.ofMinutes(15).minusMillis(1))
        assertTrue(writer().flush().catchingUp)

        clock.advance(Duration.ofMillis(1))
        assertFalse(writer().flush().catchingUp)
        assertEquals(1, hc.records(RecordType.RESPIRATORY_RATE).size)
    }
}
