package io.github.opencircuit.health

import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Basal metabolic rate, one record per finalized local day (PORTING.md D-296): upstream writes an
 * hourly basal-energy sample (HealthKitWriter.swift:1453-1483 @ b1c2fdd); Health Connect has a
 * basal-metabolic-RATE record and derives total calories from it, so one `oc:bmr:<date>` per day,
 * at the day's start, valued `Calories.basalKcalPerHour × 24` (Mifflin-St Jeor on the profile,
 * nudged by the day's resting heart rate against the prior days' baseline). Same finalization and
 * lookback as resting heart rate. A first run starts at today, as upstream starts its meter at the
 * current hour; after that every final day since the mark.
 */
class BmrDayTest {
    private val zone = ZoneId.of("UTC")
    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val activity = InMemoryHealthActivity(zone)

    private fun writer() = HealthWriter(
        hc, samples, WriteGeneration(InMemoryWriteGenerations()), InMemoryHealthFailures(), clock::now, { zone }, { RingDevice("Gen 2") },
        activity = activity,
    )

    /** Mifflin-St Jeor for 35 y, 70 kg, 170 cm, male: 10·70 + 6.25·170 − 5·35 + 5. */
    private val staticKcalPerDay = 1_592.5

    private fun bmr() = hc.records(RecordType.BASAL_METABOLIC_RATE).map { it as BasalMetabolicRate }.sortedBy { it.time }

    /** An hour of readings, one per 150 s ring epoch, from the day's 00:00. */
    private fun heartRate(date: String, bpm: Double) {
        val from = Instant.parse("${date}T00:00:00Z")
        val epochs = 3_600L / BulkRecord.EPOCH_SECONDS
        samples.add(*(0 until epochs).map { QuantitySample(MetricKind.HEART_RATE, from.plusSeconds(it * BulkRecord.EPOCH_SECONDS), value = bpm) }.toTypedArray())
    }

    @Test
    fun aFirstRunWritesTodayOnceItIsFinalWithTheStaticRateWithoutAHeartRateBaseline() = runTest {
        clock.set(Instant.parse("2026-10-16T11:59:59.999Z"))
        writer().flush()
        assertEquals(emptyList(), bmr())

        clock.set(Instant.parse("2026-10-16T12:00:00Z"))
        writer().flush()

        val record = bmr().single()
        assertEquals("oc:bmr:2026-10-16", record.meta.clientRecordId)
        assertEquals(Instant.parse("2026-10-16T00:00:00Z"), record.time)
        assertEquals(staticKcalPerDay, record.kilocaloriesPerDay, 1e-6)
        assertEquals(LocalDate.of(2026, 10, 16), activity.dayMark(RecordType.BASAL_METABOLIC_RATE))
    }

    @Test
    fun everyFinalDaySinceTheMarkIsWrittenWithinTheLookbackAndNoneTwice() = runTest {
        activity.setDayMark(RecordType.BASAL_METABOLIC_RATE, LocalDate.of(2026, 10, 1))

        writer().flush()
        writer().flush()

        assertEquals((9..16).map { "oc:bmr:2026-10-%02d".format(it) }, bmr().map { it.meta.clientRecordId })
        assertEquals(1, hc.calls.count { it.type == RecordType.BASAL_METABOLIC_RATE })
    }

    /** A resting heart rate above the prior days' baseline raises the day's rate (upstream's nudge, at most ±20 %). */
    @Test
    fun aRestingHeartRateAboveTheBaselineRaisesTheRate() = runTest {
        activity.setDayMark(RecordType.BASAL_METABOLIC_RATE, LocalDate.of(2026, 10, 15))
        for (d in 10..15) heartRate("2026-10-%02d".format(d), 55.0)
        heartRate("2026-10-16", 70.0)

        writer().flush()

        val rate = bmr().single().kilocaloriesPerDay
        assertTrue(rate > staticKcalPerDay && rate <= staticKcalPerDay * 1.2 + 1e-6, "$rate")
    }

    @Test
    fun notAllowedWritesNothingAndMovesNoMark() = runTest {
        hc.revoke(HealthPermission.BASAL_METABOLIC_RATE)

        val result = writer().flush()

        assertTrue(RecordType.BASAL_METABOLIC_RATE in result.notGranted)
        assertNull(activity.dayMark(RecordType.BASAL_METABOLIC_RATE))
    }
}
