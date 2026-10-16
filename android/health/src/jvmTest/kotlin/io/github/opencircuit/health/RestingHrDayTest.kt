package io.github.opencircuit.health

import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Resting heart rate, one record per finalized local day (upstream `flushRestingHR`,
 * HealthKitWriter.swift:1389-1437 @ b1c2fdd): a day is final once its start is 12 hours past
 * (so last night's value lands by midday), each day after the last one written within a 7-day
 * lookback gets `oc:rhr:<date>` at the day's start, and the day mark moves only past what Health
 * Connect accepted. The day's value is `RestingHR.dailyValues`: the mean asleep heart rate when the
 * store holds the night's stages, else the lowest sustained 5-minute window.
 */
class RestingHrDayTest {
    private val zone = ZoneId.of("UTC")
    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val sleep = InMemoryHealthSleep()
    private val activity = InMemoryHealthActivity(zone)

    private fun writer() = HealthWriter(
        hc, samples, WriteGeneration(InMemoryWriteGenerations()), InMemoryHealthFailures(), clock::now, { zone }, { RingDevice("Gen 2") },
        sleep = sleep, activity = activity,
    )

    /** One reading per 150 s ring epoch at [bpm] over [from, from + hours). */
    private fun heartRate(from: Instant, hours: Long, bpm: Double) {
        val epochs = hours * 3_600 / BulkRecord.EPOCH_SECONDS
        samples.add(*(0 until epochs).map { QuantitySample(MetricKind.HEART_RATE, from.plusSeconds(it * BulkRecord.EPOCH_SECONDS), value = bpm) }.toTypedArray())
    }

    /** A day of readings: 50 bpm from 00:00 to 06:00, 80 from 06:00 to 10:00. */
    private fun day(date: String) {
        heartRate(Instant.parse("${date}T00:00:00Z"), 6, 50.0)
        heartRate(Instant.parse("${date}T06:00:00Z"), 4, 80.0)
    }

    private fun resting() = hc.records(RecordType.RESTING_HEART_RATE).map { it as RestingHeartRate }.sortedBy { it.time }

    /** Every stored night is read once per flush: sleep and resting heart rate share the read. */
    @Test
    fun theStoredNightsAreReadOncePerFlush() = runTest {
        day("2026-10-15")
        var reads = 0
        val counting = object : HealthSleepStore by sleep {
            override suspend fun nights(): List<StoredSleepNight> = sleep.nights().also { reads++ }
        }

        HealthWriter(
            hc, samples, WriteGeneration(InMemoryWriteGenerations()), InMemoryHealthFailures(), clock::now, { zone }, { RingDevice("Gen 2") },
            sleep = counting, activity = activity,
        ).flush()

        assertEquals(1, reads)
        assertTrue(resting().isNotEmpty())
    }

    /** The edge exactly: at the day's start + 12 h − 1 ms it is not final; at + 12 h it is. */
    @Test
    fun aDayIsWrittenOnceTwelveHoursPastItsStartAndNotAMillisecondBefore() = runTest {
        day("2026-10-16")
        clock.set(Instant.parse("2026-10-16T11:59:59.999Z"))
        writer().flush()
        assertEquals(emptyList(), resting().filter { it.time == Instant.parse("2026-10-16T00:00:00Z") })

        clock.set(Instant.parse("2026-10-16T12:00:00Z"))
        writer().flush()

        val record = resting().single()
        assertEquals("oc:rhr:2026-10-16", record.meta.clientRecordId)
        assertEquals(Instant.parse("2026-10-16T00:00:00Z"), record.time)
        assertEquals(50L, record.beatsPerMinute, "the lowest sustained five minutes")
        assertEquals(LocalDate.of(2026, 10, 16), activity.dayMark(RecordType.RESTING_HEART_RATE))
    }

    @Test
    fun aDayWrittenIsNotWrittenAgain() = runTest {
        day("2026-10-16")
        writer().flush()
        val calls = hc.calls.count { it.type == RecordType.RESTING_HEART_RATE }

        clock.set(Instant.parse("2026-10-16T20:00:00Z"))
        writer().flush()

        assertEquals(calls, hc.calls.count { it.type == RecordType.RESTING_HEART_RATE })
    }

    /** A first run backfills at most the last seven days before today, and today; days without readings get no record. */
    @Test
    fun aFirstRunLooksBackSevenDays() = runTest {
        for (d in 5..16) if (d != 12) day("2026-10-%02d".format(d))

        writer().flush()

        assertEquals(
            listOf(9, 10, 11, 13, 14, 15, 16).map { LocalDate.of(2026, 10, it).atStartOfDay(zone).toInstant() },
            resting().map { it.time },
        )
    }

    /** With the night's stages stored, the day's value is the mean asleep heart rate (58 here), not the lowest window (50). */
    @Test
    fun theNightsStagesMakeItTheMeanAsleepHeartRate() = runTest {
        heartRate(Instant.parse("2026-10-16T00:00:00Z"), 1, 50.0)
        heartRate(Instant.parse("2026-10-16T01:00:00Z"), 4, 58.0)
        heartRate(Instant.parse("2026-10-16T06:00:00Z"), 4, 80.0)
        val asleep = SleepSegment(Instant.parse("2026-10-16T01:00:00Z"), Instant.parse("2026-10-16T05:00:00Z"), SleepStage.ASLEEP_CORE)
        sleep.putNight(StoredSleepNight("1792108800.0", Instant.parse("2026-10-16T00:00:00Z"), false, listOf(asleep), asleep.end))

        writer().flush()

        assertEquals(listOf(58L), resting().map { it.beatsPerMinute })
    }

    @Test
    fun notAllowedWritesNothingAndMovesNoMark() = runTest {
        hc.revoke(HealthPermission.RESTING_HEART_RATE)
        day("2026-10-16")

        val result = writer().flush()

        assertTrue(RecordType.RESTING_HEART_RATE in result.notGranted)
        assertNull(activity.dayMark(RecordType.RESTING_HEART_RATE))
    }

    @Test
    fun aFailedWriteMovesNoMarkAndIsRetried() = runTest {
        day("2026-10-16")
        hc.failNext(RecordType.RESTING_HEART_RATE, IllegalStateException("boom"))

        val result = writer().flush()
        assertTrue(RecordType.RESTING_HEART_RATE in result.failures)
        assertNull(activity.dayMark(RecordType.RESTING_HEART_RATE))

        writer().flush()
        assertEquals(1, resting().size)
    }
}
