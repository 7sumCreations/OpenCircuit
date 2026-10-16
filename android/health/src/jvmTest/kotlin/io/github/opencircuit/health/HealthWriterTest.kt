package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The writer's heart-rate path against [FakeHealthConnect] (the platform's rules) and an in-memory
 * store with the store's own pending / mark rules. Asserted on what Health Connect holds, on the
 * calls it saw, and on the store's marks — never on the writer's own result alone.
 */
class HealthWriterTest {

    private val h8 = Instant.parse("2026-10-16T08:00:00Z")
    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val generations = MemoryGenerations()
    private val ring = RingDevice("Gen 2")

    private fun writer(port: HealthConnectPort = hc) =
        HealthWriter(port, samples, WriteGeneration(generations), clock::now, { ZoneOffset.UTC }, { ring })

    private fun hr(minutes: Long, bpm: Double) = QuantitySample(MetricKind.HEART_RATE, h8.plusSeconds(minutes * 60), value = bpm)

    private fun hcHours(): List<HeartRateSeries> = hc.records(RecordType.HEART_RATE).map { it as HeartRateSeries }

    private class MemoryGenerations(var value: Long? = null) : WriteGenerationStore {
        override suspend fun load(): Long? = value
        override suspend fun save(generation: Long) { value = generation }
    }

    @Test
    fun pendingHeartRateIsWrittenAsHourRecordsAndMarkedAndASecondFlushWritesNothing() = runTest {
        samples.add(hr(10, 60.0), hr(50, 62.0), hr(70, 64.0))

        val first = writer().flush()

        assertEquals(listOf("oc:hr:${h8.toEpochMilli()}", "oc:hr:${h8.plusSeconds(3600).toEpochMilli()}"), hcHours().map { it.meta.clientRecordId })
        assertEquals(listOf(listOf(60L, 62L), listOf(64L)), hcHours().map { h -> h.samples.map { it.beatsPerMinute } })
        assertEquals(hr(70, 64.0).start, samples.mark(MetricKind.HEART_RATE))
        assertEquals(mapOf(RecordType.HEART_RATE to 2), first.written)
        assertEquals(1, hc.calls.size, "both hours in one call")

        val second = writer().flush()

        assertEquals(1, hc.calls.size, "nothing pending, nothing sent")
        assertEquals(0, second.recordsWritten)
        assertEquals(1L, generations.value, "no generation is spent on a flush that writes nothing")
    }

    @Test
    fun aLateSampleRewritesItsWholeHourUnderTheSameIdWithAHigherVersion() = runTest {
        samples.add(hr(10, 60.0))
        writer().flush()

        samples.add(hr(40, 70.0))
        clock.advance(Duration.ofMinutes(5))
        writer().flush()

        val hours = hcHours()
        assertEquals(1, hours.size, "replaced, not doubled")
        assertEquals(listOf(60L, 70L), hours.single().samples.map { it.beatsPerMinute })
        assertEquals(2L, hours.single().meta.version)
    }

    @Test
    fun nothingPendingMakesNoCallAndSpendsNoGeneration() = runTest {
        val result = writer().flush()

        assertEquals(emptyList(), hc.calls)
        assertEquals(null, generations.value)
        assertEquals(0, result.recordsWritten)
        assertTrue(result.available)
    }

    @Test
    fun withoutTheHeartRateGrantNothingIsSentAndNothingMarkedUntilItIsGranted() = runTest {
        hc.revoke(HealthPermission.HEART_RATE)
        samples.add(hr(10, 60.0))

        val denied = writer().flush()

        assertEquals(emptyList(), hc.calls)
        assertEquals(setOf(RecordType.HEART_RATE), denied.notGranted)
        assertEquals(emptyMap(), denied.failures, "a type the user did not allow is skipped, never failed")
        assertEquals(null, samples.mark(MetricKind.HEART_RATE))

        hc.grant(HealthPermission.HEART_RATE)
        writer().flush()
        assertEquals(1, hcHours().size)
    }

    @Test
    fun anInsertThatFailsAfterHealthConnectStoredItLeavesTheMarkAndTheNextFlushReplacesNotDoubles() = runTest {
        samples.add(hr(10, 60.0))
        hc.crashAfterAccept(RecordType.HEART_RATE)

        val failed = writer().flush()

        assertEquals(setOf(RecordType.HEART_RATE), failed.failures.keys)
        assertEquals(null, samples.mark(MetricKind.HEART_RATE), "a write that did not return is not marked")
        assertEquals(1, hcHours().size, "Health Connect did store it")

        clock.advance(Duration.ofMinutes(1))
        val retried = writer().flush()

        assertEquals(emptyMap(), retried.failures)
        assertEquals(1, hcHours().size, "the same id again: replaced, not doubled")
        assertEquals(2L, hcHours().single().meta.version)
        assertEquals(hr(10, 60.0).start, samples.mark(MetricKind.HEART_RATE))
    }

    @Test
    fun aBusyHealthConnectStopsTheFlushAsCatchingUpNotAsAFailure() = runTest {
        samples.add(hr(10, 60.0))
        hc.busy = RetryReason.DATA_SYNC_IN_PROGRESS

        val result = writer().flush()

        assertTrue(result.catchingUp)
        assertEquals(emptyMap(), result.failures)
        assertEquals(null, samples.mark(MetricKind.HEART_RATE))
        assertEquals(1, samples.pendingSamples(MetricKind.HEART_RATE).size, "still pending for the next flush")
    }

    @Test
    fun anUnavailableHealthConnectIsReportedAndNothingIsTried() = runTest {
        hc.status = HealthSdkStatus.UNAVAILABLE
        samples.add(hr(10, 60.0))

        val result = writer().flush()

        assertFalse(result.available)
        assertEquals(emptyList(), hc.calls)
        assertEquals(null, generations.value)
    }

    @Test
    fun aRecordBreakingThePlatformRulesIsDroppedAndCountedAndTheRestIsWritten() = runTest {
        // 400 bpm, as a store row planted before the ingest band guard existed.
        samples.add(hr(10, 400.0), hr(70, 64.0))

        val result = writer().flush()

        assertEquals(mapOf(RecordType.HEART_RATE to 1), result.rejected)
        assertEquals(listOf(listOf(64L)), hcHours().map { h -> h.samples.map { it.beatsPerMinute } })
        assertEquals(hr(70, 64.0).start, samples.mark(MetricKind.HEART_RATE))
    }

    @Test
    fun aBacklogIsSentInChunksOfAtMostAThousandRecordsMarkedChunkByChunk() = runTest {
        // 1001 hours, one reading each, oldest first; the clock after the last.
        val start = Instant.parse("2026-08-01T00:00:00Z")
        repeat(1001) { samples.add(QuantitySample(MetricKind.HEART_RATE, start.plusSeconds(3600L * it + 60), value = 60.0)) }
        clock.set(start.plusSeconds(3600L * 1001))
        // The second call fails once: the first chunk stays written and marked.
        val failing = object : HealthConnectPort by hc {
            var inserts = 0
            override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
                if (++inserts == 2) throw HealthCallFailedException("the connection broke")
                return hc.insert(type, records)
            }
        }

        val first = writer(failing).flush()

        assertEquals(listOf(1000), hc.calls.map { it.count })
        assertEquals(mapOf(RecordType.HEART_RATE to 1000), first.written)
        assertEquals(start.plusSeconds(3600L * 999 + 60), samples.mark(MetricKind.HEART_RATE))

        writer(failing).flush()
        assertEquals(listOf(1000, 1), hc.calls.map { it.count })
        assertEquals(1001, hcHours().size)
    }

    @Test
    fun twoFlushesOnOneWriterNeverOverlap() = runTest {
        samples.add(hr(10, 60.0))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val gated = object : HealthConnectPort by hc {
            override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
                events += "insert ${records.size}"
                entered.complete(Unit)
                release.await()
                return hc.insert(type, records).also { events += "inserted" }
            }
        }
        val w = writer(gated)

        val a = async { w.flush() }
        entered.await()
        samples.add(hr(20, 61.0))
        val b = async { w.flush() }
        runCurrent()
        assertEquals(listOf("insert 1"), events, "the second flush waits for the first")

        release.complete(Unit)
        a.await()
        b.await()

        assertEquals(listOf("insert 1", "inserted", "insert 1", "inserted"), events)
        assertEquals(listOf(60L, 61L), hcHours().single().samples.map { it.beatsPerMinute })
    }
}
