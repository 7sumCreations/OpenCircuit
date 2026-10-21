package io.github.opencircuit.app

import io.github.opencircuit.app.details.ConnectionDetailsPresenter
import io.github.opencircuit.app.details.DetailsInput
import io.github.opencircuit.app.details.StorageReport
import io.github.opencircuit.app.details.StorageSource
import io.github.opencircuit.app.details.storeFileBytes
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.StoreFootprint
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connection details' Storage line: every reading is kept for good, so the owner reads on the phone
 * how big the store is and what it holds — the database and its write-ahead log in bytes, rows per
 * table, samples per kind, the first and last stored day. Counts and days only, never a value;
 * Copy carries the same line.
 */
class StorageLineTest {

    private val footprint = StoreFootprint(
        rowsPerTable = linkedMapOf(
            "stored_sample" to 1_234_567L, "stored_daily" to 31L, "stored_step_sample" to 2_976L,
            "stored_daytime_temp" to 1_488L, "stored_sleep_summary" to 30L, "stored_nap" to 2L,
        ),
        samplesPerKind = linkedMapOf("heartRate" to 600_000L, "spo2" to 5_952L),
        firstSample = Instant.parse("2026-03-02T23:30:00Z"),
        lastSample = Instant.parse("2026-10-21T08:15:00Z"),
    )

    private fun storageRow(input: DetailsInput) = ConnectionDetailsPresenter.present(input).rows.single { it.label == "Storage" }.value

    @Test
    fun theLineNamesTheBytesTheDaysTheRowsPerTableAndTheSamplesPerKind() {
        // Helsinki is UTC+2 in March: the first sample, 23:30Z on the 2nd, is on the 3rd there.
        val value = storageRow(DetailsInput(storage = StorageReport(fileBytes = 13_002_342L, footprint = footprint), zone = ZoneId.of("Europe/Helsinki")))

        assertEquals(
            "12.4 MB on disk (database + log) · days 3 Mar 2026 – 21 Oct 2026 · " +
                "samples 1,234,567 (heartRate 600,000, spo2 5,952) · day totals 31 · step deltas 2,976 · " +
                "daytime temperatures 1,488 · nights 30 · naps 2",
            value,
        )
    }

    @Test
    fun anEmptyStoreAndAnUnreadFileSayWhatTheyAre() {
        val empty = StoreFootprint(rowsPerTable = footprint.rowsPerTable.mapValues { 0L }, samplesPerKind = emptyMap(), firstSample = null, lastSample = null)
        assertEquals(
            "size not read · no samples stored · samples 0 · day totals 0 · step deltas 0 · daytime temperatures 0 · nights 0 · naps 0",
            storageRow(DetailsInput(storage = StorageReport(fileBytes = null, footprint = empty))),
        )
        assertEquals("812 KB on disk (database + log) · days 21 Oct 2026 – 21 Oct 2026 · samples 1 (heartRate 1) · day totals 0 · step deltas 0 · daytime temperatures 0 · nights 0 · naps 0",
            storageRow(DetailsInput(storage = StorageReport(831_488L, empty.copy(rowsPerTable = empty.rowsPerTable + ("stored_sample" to 1L), samplesPerKind = mapOf("heartRate" to 1L), firstSample = footprint.lastSample, lastSample = footprint.lastSample)))))
        assertEquals("not available", storageRow(DetailsInput()))
    }

    @Test
    fun copyCarriesTheStorageLine() {
        val ui = ConnectionDetailsPresenter.present(DetailsInput(storage = StorageReport(fileBytes = 13_002_342L, footprint = footprint)))
        assertTrue("Storage: 12.4 MB on disk (database + log)" in ui.copyText, ui.copyText)
    }

    @Test
    fun theFileBytesAreTheDatabaseAndItsLogAndNullWithNoDatabase() {
        val dir = Files.createTempDirectory("e12-bytes").toFile()
        try {
            val db = File(dir, "opencircuit-store.db")
            assertNull(storeFileBytes(db), "no database file yet")
            db.writeBytes(ByteArray(4_096))
            assertEquals(4_096L, storeFileBytes(db), "no log yet")
            File(dir, "opencircuit-store.db-wal").writeBytes(ByteArray(8_192))
            File(dir, "opencircuit-store.db-shm").writeBytes(ByteArray(32_768))
            assertEquals(12_288L, storeFileBytes(db), "the database and its write-ahead log, not the shared-memory index")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theSourceReadsTheStoreAgainWhenAReadingIsWritten() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val db = StoreFactory.openInMemory(io)
        try {
            val source = StorageSource({ db }, fileBytes = { 4_096L }, io = io)
            val seen = mutableListOf<StorageReport>()
            val job = launch { source.reports().collect { seen += it } }
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(0L, seen.last().footprint.rowsPerTable.getValue("stored_sample"))

            LocalStore(db).insertLiveSamples(listOf(QuantitySample(MetricKind.HEART_RATE, Instant.parse("2026-10-21T08:00:00Z"), value = 61.0)), now = Instant.parse("2026-10-21T08:00:01Z"))
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(1L, seen.last().footprint.rowsPerTable.getValue("stored_sample"))
            assertEquals(4_096L, seen.last().fileBytes)
            job.cancel()
        } finally {
            db.close()
        }
    }
}
