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
import kotlin.test.assertTrue

/**
 * Upstream's `ActiveEnergyDayRolloverTests` (HealthKitShareTypesTests.swift:396-503 @ b1c2fdd),
 * re-expressed over the absolute per-bucket model (PORTING.md D-295). Upstream keeps one running
 * "written today" total and must reset it at the local day's turn, persist the reset even when a
 * flush writes nothing, and not mistake travel for a new day. Here there is no running total: each
 * bucket carries its own absolute kilocalories under an id named by its instant, and what was written
 * is kept per local day. Each upstream case becomes the property that remains.
 */
class DayRolloverTest {
    private val newYork = ZoneId.of("America/New_York")
    private val london = ZoneId.of("Europe/London")
    private var zone: ZoneId = newYork
    private val clock = TestClock(Instant.parse("2025-07-28T16:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val activity = InMemoryHealthActivity(newYork, samples)
    private val generations = InMemoryWriteGenerations()

    private fun writer() = HealthWriter(
        hc, samples, WriteGeneration(generations), InMemoryHealthFailures(), clock::now, { zone }, { RingDevice("Gen 2") },
        activity = activity,
    )

    /** 130 bpm, one reading per 150 s ring epoch, the epochs that fit in [from, from + minutes). */
    private fun walk(from: Instant, minutes: Long) {
        val epochs = minutes * 60 / BulkRecord.EPOCH_SECONDS
        samples.add(*(0 until epochs).map { QuantitySample(MetricKind.HEART_RATE, from.plusSeconds(it * BulkRecord.EPOCH_SECONDS), value = 130.0) }.toTypedArray())
    }

    private fun energy() = hc.records(RecordType.ACTIVE_CALORIES_BURNED).map { it as ActiveCaloriesBurned }

    /**
     * :426 `testRolloverResetSurvivesAFlushThatWritesNothing`. Yesterday's buckets are written; the
     * new day's first flush writes nothing new; the day's first activity is then written in full —
     * nothing of yesterday is carried into it.
     */
    @Test
    fun rolloverResetSurvivesAFlushThatWritesNothing() = runTest {
        walk(Instant.parse("2025-07-28T14:00:00Z"), 30) // 10:00 in New York
        writer().flush()
        val yesterday = energy().toSet()

        clock.set(Instant.parse("2025-07-29T09:00:00Z")) // 05:00 the next New York day
        val quiet = writer().flush()
        assertEquals(emptyMap(), quiet.written, "nothing new to write")

        walk(Instant.parse("2025-07-29T12:00:00Z"), 30)
        clock.set(Instant.parse("2025-07-29T13:00:00Z"))
        writer().flush()

        val today = energy().filter { it.start >= Instant.parse("2025-07-29T04:00:00Z") }
        assertEquals(yesterday.map { it.kilocalories }.sorted(), today.map { it.kilocalories }.sorted(), "the same walk, the same buckets: nothing subtracted")
        assertEquals(yesterday, energy().filter { it.start < Instant.parse("2025-07-29T04:00:00Z") }.toSet(), "yesterday untouched")
    }

    /** :449 `testSameDayFlushPreservesEverything`: a second flush the same day keeps what was written and writes nothing. */
    @Test
    fun sameDayFlushPreservesEverything() = runTest {
        walk(Instant.parse("2025-07-28T14:00:00Z"), 30)
        writer().flush()
        val kept = activity.energyWritten(LocalDate.of(2025, 7, 28))
        val calls = hc.calls.size

        writer().flush()

        assertEquals(kept, activity.energyWritten(LocalDate.of(2025, 7, 28)))
        assertEquals(calls, hc.calls.size)
    }

    /**
     * :466 `testTravellingWestIsNotTreatedAsANewDay`. Upstream must not re-pay the morning after a
     * flight; here the buckets of both days' grids start on the same quarter hours (whole-hour and
     * half-hour offsets keep a 15-minute grid), so the instants already written keep their ids: no
     * record is added for an instant Health Connect already holds, and the total is not paid twice.
     */
    @Test
    fun travellingWestIsNotTreatedAsANewDay() = runTest {
        zone = london
        walk(Instant.parse("2025-07-28T14:00:00Z"), 30)
        writer().flush()
        val before = energy()

        zone = newYork
        writer().flush()

        assertEquals(before.map { it.meta.clientRecordId }.toSet(), energy().map { it.meta.clientRecordId }.toSet())
        assertEquals(before.sumOf { it.kilocalories }, energy().sumOf { it.kilocalories }, 1e-9)
    }

    /** :492 `testFirstEverRunResetsCleanly`: with nothing kept, the first flush writes the day's buckets whole. */
    @Test
    fun firstEverRunResetsCleanly() = runTest {
        walk(Instant.parse("2025-07-28T14:00:00Z"), 30)

        writer().flush()

        assertEquals(2, energy().size)
        assertTrue(energy().all { it.kilocalories > 0 })
    }
}
