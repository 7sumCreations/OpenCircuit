package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much the on-disk store grows per worn day now that every reading is kept: a file-backed store
 * on the production driver is filled through the real write paths (ingest for the ring's history,
 * the live step fold, daytime temperatures) with days shaped as the ring delivers them, then
 * checkpointed (`PRAGMA wal_checkpoint(TRUNCATE)`) and measured as `page_count × page_size`.
 *
 * A worn day here: one epoch every 150 s (576 a day) with a heart rate; through an 8-hour night
 * (192 epochs) HRV, SpO₂ (a fraction), breathing rate and the nightly skin temperature as well;
 * HRV on every fourth daytime epoch; a step delta every 15 minutes and a daytime temperature
 * every 30 while connected. That is 1,440 samples, 96 step deltas, 48 daytime readings and one
 * day total — near the research estimate of about 1,500 sample rows a day.
 *
 * The bytes per day are written to `build/reports/storage-growth.txt` and compared, not asserted,
 * with the estimate (about 160–200 KB a worn day): the phone's own figure, read from Connection
 * details, is the one that counts. The test fails only on a figure no reading could explain (more
 * than 2 MB a day) or on any row missing.
 */
class StorageGrowthTest {

    private val zone = ZoneOffset.UTC
    private val firstDay = Instant.parse("2026-09-01T00:00:00Z")
    private val epoch = 150L
    private val epochsPerDay = 576
    private val nightEpochs = 192

    private fun dayStart(d: Int): Instant = firstDay.plusSeconds(d * 86_400L)

    private fun samplesFor(d: Int): List<QuantitySample> = (0 until epochsPerDay).flatMap { i ->
        val t = dayStart(d).plusSeconds(i * epoch)
        val night = i < nightEpochs
        buildList {
            add(QuantitySample(MetricKind.HEART_RATE, t, value = (if (night) 54 else 72) + (i % 9).toDouble()))
            if (night || i % 4 == 0) add(QuantitySample(MetricKind.HRV_SDNN, t, value = 30.0 + i % 40))
            if (night) {
                add(QuantitySample(MetricKind.SPO2, t, value = 0.94 + (i % 5) / 100.0))
                add(QuantitySample(MetricKind.RESPIRATORY_RATE, t, value = 12.5 + (i % 8) * 0.625))
                add(QuantitySample(MetricKind.TEMPERATURE, t, value = 33.0 + (i % 10) / 10.0))
            }
        }
    }

    private suspend fun writeDay(store: LocalStore, d: Int) {
        val end = dayStart(d + 1)
        store.ingest(samplesFor(d), end, zone)
        for (q in 0 until 96) {
            val at = dayStart(d).plusSeconds(q * 900L + 600)
            store.addDailySteps(delta = 20L + q % 30, day = at, windowStart = at.minusSeconds(900), now = end, zone = zone)
            if (q % 2 == 0) store.recordDaytimeTemperature(32.5 + (q % 7) / 10.0, at)
        }
    }

    private suspend fun StoreDatabase.bytesOnDisk(): Long {
        queryRaw("PRAGMA wal_checkpoint(TRUNCATE)")
        return queryRaw("PRAGMA page_count").single().toLong() * queryRaw("PRAGMA page_size").single().toLong()
    }

    @Test
    fun aWornDayAddsAMeasuredNumberOfBytesAndKeepsEveryRow() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("e12-storage")
        val db = StoreFactory.openFile(dir.resolve("store.db"))
        try {
            val store = LocalStore(db)
            // One day first, so the measured days do not carry the empty file's first pages.
            writeDay(store, 0)
            val before = db.bytesOnDisk()
            val days = 14
            for (d in 1..days) writeDay(store, d)
            val after = db.bytesOnDisk()

            val footprint = StoreFootprints(db).read()
            val perDay = (after - before) / days
            assertEquals(1_440L * (days + 1), footprint.rowsPerTable.getValue("stored_sample"), "every sample kept")
            assertEquals(96L * (days + 1), footprint.rowsPerTable.getValue("stored_step_sample"))
            assertEquals(48L * (days + 1), footprint.rowsPerTable.getValue("stored_daytime_temp"))
            assertEquals((days + 1).toLong(), footprint.rowsPerTable.getValue("stored_daily"))
            assertTrue(perDay in 1..2_000_000, "a worn day adds $perDay bytes")

            val report = String.format(
                Locale.ROOT,
                "Storage growth (JVM, file-backed, %d worn days after one warm-up day): %d bytes per worn day (%.1f KB); " +
                    "%d sample rows, %d step deltas, %d daytime readings per day; estimate 160-200 KB per worn day.%n",
                days, perDay, perDay / 1024.0, 1_440, 96, 48,
            )
            File(System.getProperty("opencircuit.storeDir") ?: ".", "build/reports").apply { mkdirs() }
                .resolve("storage-growth.txt").writeText(report)
            println(report)
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }
}
