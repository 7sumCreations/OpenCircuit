package io.github.opencircuit.app

import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.SkinTemperature
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepNightExtras
import io.github.opencircuit.store.SleepStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Skin temperature through the app's real store: nights saved as the sync saves them (with their
 * published mean) and readings stored as samples reach Health Connect as one record per night,
 * named by the store's own night key text, with the prior nights' mean as its baseline; the
 * readings are marked once it is accepted.
 */
class StoreSkinTemperatureWriteTest {

    private val day = Instant.parse("2026-06-10T00:00:00Z")

    private suspend fun saveNight(sleep: SleepStore, key: Instant, mean: Double) {
        val summary = SleepStaging.Summary(
            inBed = Duration.ofHours(8), awake = Duration.ZERO, light = Duration.ofHours(8), deep = Duration.ZERO, rem = Duration.ZERO,
        )
        sleep.saveSleepSummary(
            summary, night = key, inBedStart = key.minusSeconds(3600), inBedEnd = key.plusSeconds(7 * 3600),
            extras = SleepNightExtras(skinTempC = mean), now = day.plusSeconds(30 * 86_400), zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun aStoredNightsReadingsBecomeOneRecordUnderTheStoresNightKey() = runTest {
        val h = healthWorld(wallStart = day.plusSeconds(10 * 86_400))
        try {
            val sleep = SleepStore(h.db)
            saveNight(sleep, day.minusSeconds(3 * 86_400), 33.0)
            saveNight(sleep, day.minusSeconds(2 * 86_400), 33.5)
            saveNight(sleep, day.minusSeconds(86_400), 34.0)
            saveNight(sleep, day, 33.9)
            val readings = listOf(QuantitySample(MetricKind.TEMPERATURE, day.minusSeconds(1800), value = 33.2), QuantitySample(MetricKind.TEMPERATURE, day.plusSeconds(1800), value = 34.1))
            LocalStore(h.db).ingest(readings, now = day.plusSeconds(86_400), zone = ZoneOffset.UTC)

            h.flush.request()
            testScheduler.advanceUntilIdle()

            val written = h.hc.records(RecordType.SKIN_TEMPERATURE).map { it as SkinTemperature }.single()
            assertEquals("oc:skin:${day.epochSecond}.0", written.meta.clientRecordId)
            assertEquals(33.5, written.baselineCelsius)
            assertEquals(readings.map { it.start }, written.deltas.map { it.time })
            assertEquals(emptyList(), LocalStore(h.db).pendingHealthSamples(MetricKind.TEMPERATURE), "the night's readings are marked")
        } finally {
            h.appScope.cancel()
            h.db.close()
        }
    }
}
