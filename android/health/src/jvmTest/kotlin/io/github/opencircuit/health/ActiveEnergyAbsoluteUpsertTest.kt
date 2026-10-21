package io.github.opencircuit.health

import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Active energy, written as the absolute kilocalories of each 15-minute bucket the day's estimate
 * attributes (`Calories.dailyEstimate`'s buckets), one record per bucket `oc:ae:<bucket start>`
 * (PORTING.md D-295). Health Connect counts only the newest of overlapping records from one app
 * (measured on the API 36 emulator), so iOS's delta ledger would under-count there: here each flush
 * rebuilds the last seven days' buckets and rewrites every bucket whose kilocalories changed — a
 * lowered estimate lowers Health Connect, a bucket that falls to nothing after it was written is
 * written at 0, and no other bucket is ever written at 0.
 */
class ActiveEnergyAbsoluteUpsertTest {
    private val zone = ZoneId.of("UTC")
    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val activity = InMemoryHealthActivity(zone, samples)
    private val failures = InMemoryHealthFailures()
    private val generations = InMemoryWriteGenerations()

    private fun writer() = HealthWriter(
        hc, samples, WriteGeneration(generations), failures, clock::now, { zone }, { RingDevice("Gen 2") },
        activity = activity,
    )

    private fun at(text: String): Instant = Instant.parse("2026-10-16T${text}Z")

    /**
     * A brisk walk: heart rate at 130 bpm, one reading per ring epoch (150 s, as the history stores
     * it; each reading then counts for its epoch), the epochs that fit in [from, from + minutes).
     */
    private fun walk(from: Instant, minutes: Long) {
        val epochs = minutes * 60 / BulkRecord.EPOCH_SECONDS
        samples.add(*(0 until epochs).map { QuantitySample(MetricKind.HEART_RATE, from.plusSeconds(it * BulkRecord.EPOCH_SECONDS), value = 130.0) }.toTypedArray())
    }

    private fun energy() = hc.records(RecordType.ACTIVE_CALORIES_BURNED).map { it as ActiveCaloriesBurned }.sortedBy { it.start }

    @Test
    fun eachAttributedBucketIsOneRecordWithItsKilocaloriesAndNoTwoOverlap() = runTest {
        walk(at("09:00:00"), 30)

        val result = writer().flush()

        val records = energy()
        assertEquals(listOf(at("09:00:00"), at("09:15:00")), records.map { it.start })
        assertEquals(listOf(at("09:15:00"), at("09:30:00")), records.map { it.end })
        assertEquals(records.map { "oc:ae:${it.start.toEpochMilli()}" }, records.map { it.meta.clientRecordId })
        assertTrue(records.all { it.kilocalories > 0 })
        assertTrue(records.zipWithNext().all { (a, b) -> !a.end.isAfter(b.start) }, "no two records overlap")
        assertEquals(mapOf(at("09:00:00") to records[0].kilocalories, at("09:15:00") to records[1].kilocalories), activity.energyWritten(LocalDate.of(2026, 10, 16)))
        assertEquals(2, result.written[RecordType.ACTIVE_CALORIES_BURNED])
    }

    @Test
    fun anUnchangedEstimateWritesNothingAgain() = runTest {
        walk(at("09:00:00"), 30)
        writer().flush()
        val calls = hc.calls.size

        clock.advance(Duration.ofMinutes(30))
        writer().flush()

        assertEquals(calls, hc.calls.size)
    }

    /** More heart rate in a bucket raises it in place: the same id, the new total, never a second record. */
    @Test
    fun aRaisedBucketIsRewrittenUnderItsIdWithTheNewTotal() = runTest {
        walk(at("09:00:00"), 5)
        writer().flush()
        val before = energy().single().kilocalories

        walk(at("09:05:00"), 5)
        writer().flush()

        val after = energy().single()
        assertEquals(at("09:00:00"), after.start)
        assertTrue(after.kilocalories > before, "$before -> ${after.kilocalories}")
        assertEquals(2L, after.meta.version)
    }

    /** A night stored later covers the walk: its heart rate no longer counts (sleep is excluded), so both buckets fall to 0. */
    @Test
    fun aLoweredEstimateLowersHealthConnectDownToZero() = runTest {
        walk(at("09:00:00"), 30)
        writer().flush()

        samples.addNights(NightWindow("1792108800.0", at("00:00:00"), at("08:30:00"), at("09:40:00"), 0.0))
        writer().flush()

        assertEquals(listOf(0.0, 0.0), energy().map { it.kilocalories })
        assertEquals(listOf(at("09:00:00"), at("09:15:00")), energy().map { it.start })
    }

    /** A bucket never written is never written at 0: only the buckets that had energy are. */
    @Test
    fun noBucketIsWrittenAtZeroUnlessItIsLoweringOne() = runTest {
        walk(at("09:00:00"), 10)
        walk(at("11:00:00"), 10)

        writer().flush()

        assertTrue(energy().all { it.kilocalories > 0 }, "${energy().map { it.kilocalories }}")
        assertEquals(listOf(at("09:00:00"), at("11:00:00")), energy().map { it.start })
    }

    /** The bucket still running ends at the clock, and grows under the same id. */
    @Test
    fun theRunningBucketEndsAtTheClockAndGrowsUnderItsId() = runTest {
        clock.set(at("09:10:00"))
        walk(at("09:00:00"), 10)

        writer().flush()
        assertEquals(listOf(at("09:10:00")), energy().map { it.end })

        clock.set(at("09:20:00"))
        walk(at("09:10:00"), 8)
        writer().flush()

        assertEquals(listOf(at("09:15:00"), at("09:20:00")), energy().map { it.end })
        assertEquals(listOf("oc:ae:${at("09:00:00").toEpochMilli()}", "oc:ae:${at("09:15:00").toEpochMilli()}"), energy().map { it.meta.clientRecordId })
    }

    /** A bucket written while running, whose total does not change, still gets its full span once it closes. */
    @Test
    fun aRunningBucketIsWrittenAgainOverItsWholeSpanOnceItCloses() = runTest {
        clock.set(at("09:10:00"))
        walk(at("09:00:00"), 5)
        writer().flush()
        assertEquals(listOf(at("09:10:00")), energy().map { it.end })

        clock.set(at("09:40:00"))
        writer().flush()

        assertEquals(listOf(at("09:15:00")), energy().map { it.end })
        val calls = hc.calls.size
        writer().flush()
        assertEquals(calls, hc.calls.size, "a closed bucket is not written again")
    }

    @Test
    fun notAllowedWritesNothingAndTheNextAllowedFlushCatchesUp() = runTest {
        hc.revoke(HealthPermission.ACTIVE_CALORIES_BURNED)
        walk(at("09:00:00"), 30)

        val result = writer().flush()

        assertEquals(setOf(RecordType.ACTIVE_CALORIES_BURNED), result.notGranted)
        assertEquals(emptyMap(), activity.energyWritten(LocalDate.of(2026, 10, 16)))
        hc.grant(HealthPermission.ACTIVE_CALORIES_BURNED)
        writer().flush()
        assertEquals(2, energy().size)
    }

    /** Steps alone (no elevated heart rate) earn walking energy in the buckets their windows cover. */
    @Test
    fun stepsAloneEarnEnergyInTheirBuckets() = runTest {
        activity.addSteps(at("10:00:00"), at("10:10:00"), 900)

        writer().flush()

        assertEquals(listOf(at("10:00:00")), energy().map { it.start })
        assertTrue(energy().single().kilocalories > 0)
    }

    /** What was written for days older than the seven rebuilt is forgotten. */
    @Test
    fun daysOlderThanTheLookbackAreForgotten() = runTest {
        activity.setEnergyWritten(LocalDate.of(2026, 10, 9), mapOf(Instant.parse("2026-10-09T09:00:00Z") to 3.0))
        activity.setEnergyWritten(LocalDate.of(2026, 10, 10), mapOf(Instant.parse("2026-10-10T09:00:00Z") to 4.0))

        writer().flush()

        assertTrue(LocalDate.of(2026, 10, 9) !in activity.energyDays())
        assertTrue(LocalDate.of(2026, 10, 10) in activity.energyDays())
    }

    /** A crash after Health Connect stored the call: the kept map did not move, the replay upserts the same ids. */
    @Test
    fun aCrashAfterAcceptReplaysUnderTheSameIds() = runTest {
        walk(at("09:00:00"), 30)
        hc.crashAfterAccept(RecordType.ACTIVE_CALORIES_BURNED)
        writer().flush()
        hc.clearFailures()

        writer().flush()

        assertEquals(2, energy().size)
        assertEquals(2, activity.energyWritten(LocalDate.of(2026, 10, 16)).size)
    }
}
