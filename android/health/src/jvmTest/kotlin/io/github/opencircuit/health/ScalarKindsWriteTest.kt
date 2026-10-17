package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Heart-rate variability (RMSSD), blood oxygen and breathing rate: one instant record per stored
 * sample, named `oc:<type>:<startEpochMilli>:<n>` (n counts the stored rows of the kind that share
 * the start, in row order), each type in calls of its own. Samples are shaped as the history
 * drain stores them (`BulkSleep.samples`: RMSSD in ms under the `hrvSDNN` kind, SpO2 as a fraction,
 * breaths per minute). Asserted on what [FakeHealthConnect] holds, its call log and the store's
 * marks.
 */
class ScalarKindsWriteTest {

    private val t0 = Instant.parse("2026-10-16T02:00:00Z")
    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val generations = MemoryGenerations()
    private val zone = ZoneId.of("Europe/Berlin")

    private val failures = InMemoryHealthFailures()

    private fun writer() = HealthWriter(hc, samples, WriteGeneration(generations), failures, clock::now, { zone }, { RingDevice("Gen 2") })

    private fun hrv(min: Long, ms: Double) = QuantitySample(MetricKind.HRV_SDNN, t0.plusSeconds(min * 60), value = ms)
    private fun spo2(min: Long, fraction: Double) = QuantitySample(MetricKind.SPO2, t0.plusSeconds(min * 60), value = fraction)
    private fun rr(min: Long, bpm: Double) = QuantitySample(MetricKind.RESPIRATORY_RATE, t0.plusSeconds(min * 60), value = bpm)

    private fun ms(min: Long) = t0.plusSeconds(min * 60).toEpochMilli()

    private class MemoryGenerations(var value: Long? = null) : WriteGenerationStore {
        override suspend fun load(): Long? = value
        override suspend fun save(generation: Long) { value = generation }
    }

    @Test
    fun eachKindIsWrittenAsItsOwnRecordTypeWithItsIdsValuesAndZoneAndEachMarkMoves() = runTest {
        samples.add(hrv(10, 42.0), hrv(15, 38.5), spo2(10, 0.97), rr(10, 14.5))

        val result = writer().flush()

        val hrvs = hc.records(RecordType.HEART_RATE_VARIABILITY_RMSSD).map { it as HeartRateVariabilityRmssd }
        assertEquals(listOf("oc:hrv:${ms(10)}:0", "oc:hrv:${ms(15)}:0"), hrvs.map { it.meta.clientRecordId })
        assertEquals(listOf(42.0, 38.5), hrvs.map { it.millis })
        val ox = hc.records(RecordType.OXYGEN_SATURATION).map { it as OxygenSaturation }.single()
        assertEquals("oc:spo2:${ms(10)}:0", ox.meta.clientRecordId)
        assertEquals(97.0, ox.percent, "the stored fraction as a percent")
        val breaths = hc.records(RecordType.RESPIRATORY_RATE).map { it as RespiratoryRate }.single()
        assertEquals("oc:rr:${ms(10)}:0", breaths.meta.clientRecordId)
        assertEquals(14.5, breaths.breathsPerMinute)
        // The phone's zone at each reading (Berlin is UTC+2 in October), the ring as the device, one version.
        assertEquals(setOf(zone.rules.getOffset(t0)), (hrvs + ox + breaths).map { it.zoneOffset }.toSet())
        assertEquals(setOf(Triple(1L, RecordingMethod.AUTOMATICALLY_RECORDED, RingDevice("Gen 2"))), (hrvs + ox + breaths).map { Triple(it.meta.version, it.meta.method, it.meta.device) }.toSet())

        assertEquals(mapOf(RecordType.HEART_RATE_VARIABILITY_RMSSD to 2, RecordType.OXYGEN_SATURATION to 1, RecordType.RESPIRATORY_RATE to 1), result.written)
        assertEquals(hrv(15, 0.0).start, samples.mark(MetricKind.HRV_SDNN))
        assertEquals(spo2(10, 0.0).start, samples.mark(MetricKind.SPO2))
        assertEquals(rr(10, 0.0).start, samples.mark(MetricKind.RESPIRATORY_RATE))
    }

    @Test
    fun everyCallHoldsOneTypeOnly() = runTest {
        samples.add(hrv(10, 42.0), spo2(10, 0.97), rr(10, 14.5), spo2(20, 0.95))

        writer().flush()

        assertEquals(
            listOf(RecordType.HEART_RATE_VARIABILITY_RMSSD to 1, RecordType.OXYGEN_SATURATION to 2, RecordType.RESPIRATORY_RATE to 1),
            hc.calls.map { it.type to it.count },
        )
    }

    @Test
    fun aDeniedKindIsSkippedNotFailedWhileTheOthersAreWrittenAndItCatchesUpOnceGranted() = runTest {
        hc.revoke(HealthPermission.OXYGEN_SATURATION)
        samples.add(hrv(10, 42.0), spo2(10, 0.97), rr(10, 14.5))

        val denied = writer().flush()

        assertEquals(setOf(RecordType.OXYGEN_SATURATION), denied.notGranted)
        assertEquals(emptyMap(), denied.failures)
        assertEquals(emptyList(), hc.records(RecordType.OXYGEN_SATURATION))
        assertEquals(1, hc.records(RecordType.HEART_RATE_VARIABILITY_RMSSD).size)
        assertEquals(1, hc.records(RecordType.RESPIRATORY_RATE).size)
        assertEquals(null, samples.mark(MetricKind.SPO2), "nothing of a denied kind is marked")
        assertTrue(hc.calls.none { it.type == RecordType.OXYGEN_SATURATION }, "no call is spent on a type without its grant")

        hc.grant(HealthPermission.OXYGEN_SATURATION)
        clock.advance(Duration.ofMinutes(1))
        val granted = writer().flush()

        assertEquals(mapOf(RecordType.OXYGEN_SATURATION to 1), granted.written)
        assertEquals(1, hc.records(RecordType.OXYGEN_SATURATION).size)
    }

    @Test
    fun nothingGrantedWritesNothingAndFailsNothing() = runTest {
        hc.revoke(*HealthPermission.entries.toTypedArray())
        samples.add(hrv(10, 42.0), spo2(10, 0.97), rr(10, 14.5))

        val result = writer().flush()

        assertEquals(setOf(RecordType.HEART_RATE_VARIABILITY_RMSSD, RecordType.OXYGEN_SATURATION, RecordType.RESPIRATORY_RATE), result.notGranted)
        assertEquals(emptyMap(), result.failures)
        assertEquals(emptyList(), hc.calls)
    }

    @Test
    fun twoStoredRowsOfOneKindAtOneStartBecomeTwoRecordsInRowOrder() = runTest {
        samples.add(rr(10, 14.0), rr(10, 15.0))

        writer().flush()

        val stored = hc.records(RecordType.RESPIRATORY_RATE).map { it as RespiratoryRate }
        assertEquals(listOf("oc:rr:${ms(10)}:0" to 14.0, "oc:rr:${ms(10)}:1" to 15.0), stored.map { it.meta.clientRecordId to it.breathsPerMinute })
    }

    @Test
    fun theOrdinalCountsEveryStoredRowAtTheStartSoAZeroRowKeepsItsPlace() = runTest {
        // A zero reading is never written, but it is a stored row: the ordinal of the row after it
        // must not depend on whether zeros are written.
        samples.add(rr(10, 0.0), rr(10, 15.0))

        writer().flush()

        assertEquals(listOf("oc:rr:${ms(10)}:1"), hc.records(RecordType.RESPIRATORY_RATE).map { it.meta.clientRecordId })
    }

    @Test
    fun writingTwiceWritesNothingTwice() = runTest {
        samples.add(hrv(10, 42.0), spo2(10, 0.97), rr(10, 14.5))
        writer().flush()
        val callsAfterFirst = hc.calls.size

        clock.advance(Duration.ofMinutes(1))
        val second = writer().flush()

        assertEquals(0, second.recordsWritten)
        assertEquals(callsAfterFirst, hc.calls.size, "no call when nothing is pending")
        assertEquals(listOf(1, 1, 1), listOf(RecordType.HEART_RATE_VARIABILITY_RMSSD, RecordType.OXYGEN_SATURATION, RecordType.RESPIRATORY_RATE).map { hc.records(it).size })
    }

    @Test
    fun aReadingOutsideThePlatformsRangeIsDroppedAndCountedAndNeverClamped() = runTest {
        // RMSSD above 200 ms; an SpO2 stored as a percent by an older path reads as 9 700 %.
        samples.add(hrv(10, 250.0), hrv(20, 40.0), spo2(10, 97.0), spo2(20, 0.96))

        val result = writer().flush()

        assertEquals(mapOf(RecordType.HEART_RATE_VARIABILITY_RMSSD to 1, RecordType.OXYGEN_SATURATION to 1), result.rejected)
        assertEquals(listOf(40.0), hc.records(RecordType.HEART_RATE_VARIABILITY_RMSSD).map { (it as HeartRateVariabilityRmssd).millis })
        assertEquals(listOf(96.0), hc.records(RecordType.OXYGEN_SATURATION).map { (it as OxygenSaturation).percent })
    }

    @Test
    fun theRmssdBoundsAreTheRangeTablesEdges() = runTest {
        // 1.0 and 200.0 ms are inside; 0.999 and 200.001 are not (the platform's own limits).
        samples.add(hrv(1, 0.999), hrv(2, 1.0), hrv(3, 200.0), hrv(4, 200.001))

        val result = writer().flush()

        assertEquals(listOf(1.0, 200.0), hc.records(RecordType.HEART_RATE_VARIABILITY_RMSSD).map { (it as HeartRateVariabilityRmssd).millis })
        assertEquals(mapOf(RecordType.HEART_RATE_VARIABILITY_RMSSD to 2), result.rejected)
    }

    @Test
    fun aReadingInTheFutureIsLeftPendingUntilItIsPast() = runTest {
        samples.add(rr(10, 14.0), QuantitySample(MetricKind.RESPIRATORY_RATE, clock.now().plusSeconds(600), value = 15.0))

        writer().flush()
        assertEquals(listOf(14.0), hc.records(RecordType.RESPIRATORY_RATE).map { (it as RespiratoryRate).breathsPerMinute })
        assertEquals(rr(10, 0.0).start, samples.mark(MetricKind.RESPIRATORY_RATE))

        clock.advance(Duration.ofMinutes(11))
        writer().flush()
        assertEquals(listOf(14.0, 15.0), hc.records(RecordType.RESPIRATORY_RATE).map { (it as RespiratoryRate).breathsPerMinute })
    }

    @Test
    fun aFailedKindKeepsItsMarkAndTheNextFlushReplacesNotDoubles() = runTest {
        samples.add(spo2(10, 0.97), rr(10, 14.5))
        hc.crashAfterAccept(RecordType.OXYGEN_SATURATION)

        val failed = writer().flush()

        assertEquals(setOf(RecordType.OXYGEN_SATURATION), failed.failures.keys)
        assertEquals(null, samples.mark(MetricKind.SPO2))
        assertEquals(1, hc.records(RecordType.RESPIRATORY_RATE).size, "the other kind is written")

        clock.advance(Duration.ofMinutes(1))
        writer().flush()

        assertEquals(1, hc.records(RecordType.OXYGEN_SATURATION).size, "replaced, not doubled")
        assertEquals(2L, hc.records(RecordType.OXYGEN_SATURATION).single().meta.version)
        assertEquals(spo2(10, 0.0).start, samples.mark(MetricKind.SPO2))
    }
}
