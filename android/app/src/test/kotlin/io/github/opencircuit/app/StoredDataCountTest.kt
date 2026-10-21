package io.github.opencircuit.app

import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.app.sync.StoredData
import io.github.opencircuit.app.sync.StoredLastNight
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * What the Ring data card says is stored — the oldest and newest reading, the nights, the last
 * night — is read without loading every night: readings are kept for good, so a read that grows
 * with the history would slow every sync and every session start for ever.
 */
class StoredDataCountTest {

    private val now = Instant.parse("2026-10-21T09:00:00Z")
    private val zone = ZoneOffset.UTC

    private fun summary(asleepMin: Long) = SleepStaging.Summary(
        inBed = Duration.ofMinutes(480), awake = Duration.ofMinutes(480 - asleepMin), light = Duration.ofMinutes(asleepMin),
        deep = Duration.ZERO, rem = Duration.ZERO,
    )

    @Test
    fun theStoredRangeNightsAndLastNightAreReadFromTheStore() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val history = StoreHistory({ db }, TEST_RING_ID, { zone })
            assertNull(history.storedData(), "nothing stored")

            val oldest = Instant.parse("2026-03-02T04:00:00Z")
            val newest = Instant.parse("2026-10-20T22:15:00Z")
            LocalStore(db).ingest(
                listOf(
                    QuantitySample(MetricKind.SPO2, oldest, value = 0.97),
                    QuantitySample(MetricKind.HEART_RATE, Instant.parse("2026-06-01T03:00:00Z"), value = 58.0),
                    QuantitySample(MetricKind.RESPIRATORY_RATE, newest, value = 14.0),
                    // Steps are not one of the card's kinds: an older one does not move the range.
                    QuantitySample(MetricKind.STEPS, oldest.minusSeconds(86_400), value = 40.0),
                ),
                now, zone,
            )
            val sleep = SleepStore(db)
            for (wake in listOf("2026-10-19T06:00:00Z", "2026-03-03T06:00:00Z", "2026-10-20T06:30:00Z").map(Instant::parse)) {
                sleep.saveSleepSummary(
                    summary(400), night = wake, inBedStart = wake.minusSeconds(8 * 3_600), inBedEnd = wake,
                    sleepOnset = wake.minusSeconds(7 * 3_600), sleepWake = wake.minusSeconds(600), now = now, zone = zone,
                )
            }
            val last = Instant.parse("2026-10-20T06:30:00Z")

            assertEquals(
                StoredData(oldest, newest, nights = 3, lastNight = StoredLastNight(last.minusSeconds(7 * 3_600), last.minusSeconds(600), asleepMinutes = 400)),
                history.storedData(),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun theNightsAreCountedNotLoaded() {
        val source = File("src/main/kotlin/io/github/opencircuit/app/sync/StoreHistory.kt").readText()
        val body = source.substringAfter("override suspend fun storedData()").substringBefore("\n    }\n")
        assertFalse("sleepSummaries(" in body, "storedData loads the nights to count them:\n$body")
        assertFalse("allSummaries(" in body, "storedData loads the nights to count them:\n$body")
        assertEquals(1, Regex("""nightCount\(""").findAll(body).count(), body)
    }
}
