package io.github.opencircuit.health

import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.HRSample
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The energy writer prices each day from the inputs its activity port answers for that day
 * ([HealthActivityStore.dayInputs]) — the app answers with the store's one day reader, which
 * Today's Calories card reads too — and from nothing else: here the sample store holds no heart
 * rate at all, and the port alone carries a brisk walk.
 */
class ActiveEnergyDayInputsPortTest {
    private val zone = ZoneId.of("UTC")
    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val today = LocalDate.of(2026, 10, 16)
    private val asked = mutableListOf<Pair<LocalDate, ZoneId>>()

    /** 130 bpm, one reading per ring epoch, for 30 minutes from 09:00. */
    private val walk = (0 until 30 * 60 / BulkRecord.EPOCH_SECONDS).map {
        HRSample(130, Instant.parse("2026-10-16T09:00:00Z").plusSeconds(it * BulkRecord.EPOCH_SECONDS.toLong()))
    }

    private val base = InMemoryHealthActivity(zone)
    private val activity = object : HealthActivityStore by base {
        override suspend fun dayInputs(day: LocalDate, zone: ZoneId): ActiveEnergyDays.DayInputs {
            asked += day to zone
            return if (day == today) ActiveEnergyDays.DayInputs(walk, 0, emptyList(), null) else base.dayInputs(day, zone)
        }
    }

    private fun writer() = HealthWriter(
        hc, InMemoryHealthSamples(), WriteGeneration(InMemoryWriteGenerations()), InMemoryHealthFailures(), clock::now, { zone }, { RingDevice("Gen 2") },
        activity = activity,
    )

    @Test
    fun eachDayIsPricedFromThePortsDayInputs() = runTest {
        writer().flush()

        val records = hc.records(RecordType.ACTIVE_CALORIES_BURNED).map { it as ActiveCaloriesBurned }.sortedBy { it.start }
        assertEquals(listOf(Instant.parse("2026-10-16T09:00:00Z"), Instant.parse("2026-10-16T09:15:00Z")), records.map { it.start })
        assertTrue(records.all { it.kilocalories > 0 })
        assertEquals(
            (0 until ActiveEnergyDays.LOOKBACK_DAYS).map { today.minusDays(ActiveEnergyDays.LOOKBACK_DAYS - 1 - it) to zone },
            asked,
            "each day of the look-back asked once, oldest first, in the writer's zone",
        )
    }
}
