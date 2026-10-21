package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the store holds, as counts only — rows per reading table, samples per kind, the first and
 * last stored sample — for Connection details' Storage line. Every reading is kept for good, so this
 * is how the owner sees the history grow on the phone. Counts, never a value.
 */
class StoreFootprintTest {

    private val now = Instant.parse("2026-10-21T09:00:00Z")
    private val zone = ZoneOffset.UTC

    @Test
    fun anEmptyStoreCountsZeroEverywhereAndHasNoFirstOrLastSample() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val footprint = StoreFootprints(db).read()

            assertEquals(
                mapOf(
                    "stored_sample" to 0L, "stored_daily" to 0L, "stored_step_sample" to 0L,
                    "stored_daytime_temp" to 0L, "stored_sleep_summary" to 0L, "stored_nap" to 0L,
                ),
                footprint.rowsPerTable,
            )
            assertEquals(emptyMap(), footprint.samplesPerKind)
            assertEquals(null, footprint.firstSample)
            assertEquals(null, footprint.lastSample)
        }
    }

    @Test
    fun everyReadingTableIsCountedAndSamplesByKindWithTheFirstAndLastSample() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val first = Instant.parse("2026-03-02T04:00:00Z")
            val last = Instant.parse("2026-10-21T08:15:00Z")
            store.ingest(
                listOf(
                    QuantitySample(MetricKind.STEPS, first, value = 40.0),
                    QuantitySample(MetricKind.HEART_RATE, first.plusSeconds(150), value = 58.0),
                    QuantitySample(MetricKind.HEART_RATE, first.plusSeconds(300), value = 59.0),
                    QuantitySample(MetricKind.SPO2, last, value = 0.97),
                ),
                now, zone,
            )
            store.addDailySteps(delta = 300, day = now.minusSeconds(600), now = now, zone = zone)
            store.addDailySteps(delta = 200, day = now.minusSeconds(300), now = now, zone = zone)
            store.recordDaytimeTemperature(33.1, now.minusSeconds(900))
            SleepStore(db).saveNap(start = now.minusSeconds(7_200), end = now.minusSeconds(5_400), asleepMin = 25, isLongNap = false, segments = emptyList(), now = now, zone = zone)

            val footprint = StoreFootprints(db).read()

            assertEquals(
                mapOf(
                    "stored_sample" to 4L, "stored_daily" to 1L, "stored_step_sample" to 2L,
                    "stored_daytime_temp" to 1L, "stored_sleep_summary" to 0L, "stored_nap" to 1L,
                ),
                footprint.rowsPerTable,
            )
            assertEquals(mapOf("heartRate" to 2L, "spo2" to 1L, "steps" to 1L), footprint.samplesPerKind)
            assertEquals(first, footprint.firstSample)
            assertEquals(last, footprint.lastSample)
        }
    }
}
