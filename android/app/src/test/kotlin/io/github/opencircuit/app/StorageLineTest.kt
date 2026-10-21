package io.github.opencircuit.app

import io.github.opencircuit.app.details.ConnectionDetailsPresenter
import io.github.opencircuit.app.details.DetailsInput
import io.github.opencircuit.app.details.DetailsSources
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.ring.RingViewModel
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
            "12.4 MB on disk (database + log) · samples from 3 Mar 2026 to 21 Oct 2026 · " +
                "samples 1,234,567 (heart rate 600,000, spo2 5,952) · day totals 31 · step deltas 2,976 · " +
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
        // An unknown stored kind name is shown as stored.
        assertEquals(
            "812 KB on disk (database + log) · samples from 21 Oct 2026 to 21 Oct 2026 · samples 2 (heart rate 1, someNewKind 1) · " +
                "day totals 0 · step deltas 0 · daytime temperatures 0 · nights 0 · naps 0",
            storageRow(
                DetailsInput(
                    storage = StorageReport(
                        831_488L,
                        empty.copy(
                            rowsPerTable = empty.rowsPerTable + ("stored_sample" to 2L),
                            samplesPerKind = linkedMapOf("heartRate" to 1L, "someNewKind" to 1L),
                            firstSample = footprint.lastSample, lastSample = footprint.lastSample,
                        ),
                    ),
                ),
            ),
        )
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

    @Test
    fun readingsWrittenSteadilyStillMoveTheLineOnAtLeastEveryTwoSeconds() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val db = StoreFactory.openInMemory(io)
        try {
            val source = StorageSource({ db }, fileBytes = { null }, io = io)
            val seen = mutableListOf<Pair<Long, Long>>()
            val job = launch { source.reports().collect { seen += testScheduler.currentTime to it.footprint.rowsPerTable.getValue("stored_sample") } }
            runCurrent()
            val store = LocalStore(db)
            val t0 = Instant.parse("2026-10-21T08:00:00Z")
            // A reading every 500 ms for 10 s: a quiet-period debounce would show nothing new until they stop.
            for (i in 1..20) {
                advanceTimeBy(500)
                store.insertLiveSamples(listOf(QuantitySample(MetricKind.HEART_RATE, t0.plusSeconds(i.toLong()), value = 60.0 + i)), now = t0.plusSeconds(30))
                runCurrent()
            }
            val during = seen.filter { (at, _) -> at in 1..10_000 }
            assertTrue(during.size >= 4, "the line moved on while readings kept coming: $seen")
            assertTrue(during.zipWithNext().all { (a, b) -> b.first - a.first >= 2_000 }, "never two counts within 2 s: $seen")
            job.cancel()
        } finally {
            db.close()
        }
    }

    @Test
    fun theStoreIsCountedOnlyWhileConnectionDetailsIsExpandedAndTheLastCountStays() = runTest {
        var reads = 0
        val report = StorageReport(fileBytes = 4_096L, footprint = footprint)
        val counted = kotlinx.coroutines.flow.flow { reads++; emit(report) }
        val vm = RingViewModel(null, "Ring", backgroundScope, details = DetailsSources(storage = counted), clockTicks = kotlinx.coroutines.flow.flowOf(Unit))
        runCurrent()
        fun row() = vm.uiState.value.details.rows.single { it.label == "Storage" }.value
        assertEquals(0, reads, "collapsed: not counted")
        assertEquals("not available", row())

        vm.onAction(RingAction.DetailsShown(true))
        runCurrent()
        assertEquals(1, reads)
        assertTrue(row().startsWith("4 KB on disk"), row())

        vm.onAction(RingAction.DetailsShown(false))
        runCurrent()
        assertEquals(1, reads, "collapsed again: no new count")
        assertTrue(row().startsWith("4 KB on disk"), "the last count stays: ${row()}")
    }

    @Test
    fun aFailedCountIsLoggedAndSkippedAndTheNextChangeCountsAgain() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val db = StoreFactory.openInMemory(io)
        try {
            var fail = true
            val logs = mutableListOf<String>()
            val source = StorageSource({ if (fail) error("the store is not open yet") else db }, fileBytes = { 1L }, io = io, log = { logs += it })
            val seen = mutableListOf<StorageReport>()
            // The watch itself opens the store: a store that cannot open ends the watch quietly, with a log line.
            val first = launch { source.reports().collect { seen += it } }
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(first.isCompleted, "the watch ended without taking the screen down")
            assertEquals(emptyList(), seen)
            assertEquals(listOf("Storage line: the store could not be watched (IllegalStateException)"), logs)

            fail = false
            val second = launch { source.reports().collect { seen += it } }
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, seen.size)
            second.cancel()
        } finally {
            db.close()
        }
    }
}
