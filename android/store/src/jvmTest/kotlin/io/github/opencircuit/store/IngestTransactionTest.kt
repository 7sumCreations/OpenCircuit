package io.github.opencircuit.store

import io.github.opencircuit.ringkit.BulkSleep
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the ingest promises beyond the ported upstream tests: all or nothing, cumulative counters
 * cut at the local midnight of the zone it is given, and the millisecond cut made before the
 * cursor sees a sample.
 */
class IngestTransactionTest {

    private val now = Instant.parse("2026-10-03T00:00:00Z")

    // The same real `0x4c` page as CaptureToStoreEndToEndTest, decoded from its raw bytes.
    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    @Test
    fun aFailureAfterTheSampleInsertsLeavesNoSamplesAndNoCursorThenARetryStoresThemAll() = runBlocking {
        withInMemoryStore { db ->
            val samples = BulkSleep.samples(BulkSleep.recordsFromPage(hex(realPage)))
            val real = db.sampleDao()
            // The cursor upsert runs after every sample insert of the batch: failing there is the
            // "crash between the samples and the cursor" case.
            val failing = object : SampleDao by real {
                override suspend fun upsertCursors(cursors: List<StoredCursorEntity>) = error("injected failure")
            }

            assertFails { LocalStore(db, failing).ingest(samples, now, ZoneOffset.UTC) }

            assertEquals(emptyList(), real.allSamples())
            assertEquals(emptyList(), real.allCursors())

            val retried = LocalStore(db).ingest(samples, now, ZoneOffset.UTC)
            assertEquals(samples.size, retried.size)
            assertEquals(samples.size, real.allSamples().size)
            assertEquals(samples.filter { it.kind == MetricKind.HEART_RATE }.maxOf { it.start }, LocalStore(db).loadCursor().last(MetricKind.HEART_RATE))
        }
    }

    @Test
    fun aStepsCounterCrossingLocalMidnightInOneBatchRestartsTheDayTotalInTheGivenZone() = runBlocking {
        // Kolkata is UTC+05:30: 18:20Z is 23:50 on the 2nd, 18:40Z is 00:10 on the 3rd.
        val before = steps("2026-10-02T18:20:00Z", 100.0)
        val after = steps("2026-10-02T18:40:00Z", 150.0)

        val kolkata = ingestAndRead(listOf(before, after), ZoneId.of("Asia/Kolkata"))
        assertEquals(listOf(100.0, 50.0), kolkata.returned.map { it.value })
        assertEquals(listOf(100.0, 50.0), kolkata.rows.map { it.dailyTotal })
        assertEquals(listOf(100.0, 150.0), kolkata.rows.map { it.rawValue })
        assertTrue(kolkata.rows.all { it.isDelta })

        // The same two instants are one day in UTC: the total keeps running.
        val utc = ingestAndRead(listOf(before, after), ZoneOffset.UTC)
        assertEquals(listOf(100.0, 50.0), utc.returned.map { it.value })
        assertEquals(listOf(100.0, 150.0), utc.rows.map { it.dailyTotal })
    }

    @Test
    fun aLaterBatchContinuesTheStoredDayTotalAndANewDayStartsFromZero() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(steps("2026-10-01T10:00:00Z", 100.0)), now, ZoneOffset.UTC)

            val sameDay = store.ingest(listOf(steps("2026-10-01T11:00:00Z", 130.0)), now, ZoneOffset.UTC)
            val nextDay = store.ingest(listOf(steps("2026-10-02T01:00:00Z", 160.0)), now, ZoneOffset.UTC)

            assertEquals(listOf(30.0), sameDay.map { it.value })
            assertEquals(listOf(30.0), nextDay.map { it.value })
            assertEquals(listOf(100.0, 130.0, 30.0), db.sampleDao().allSamples().map { it.dailyTotal })
        }
    }

    @Test
    fun reIngestingASubMillisecondSampleAddsNothing() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val sample = QuantitySample(MetricKind.HEART_RATE, start = Instant.parse("2026-01-01T00:00:00.123456789Z"), value = 60.0)

            val first = store.ingest(listOf(sample), now, ZoneOffset.UTC)
            val second = store.ingest(listOf(sample), now, ZoneOffset.UTC)

            assertEquals(listOf(Instant.parse("2026-01-01T00:00:00.123Z")), first.map { it.start })
            assertEquals(emptyList(), second)
            assertEquals(1, db.sampleDao().allSamples().size)
        }
    }

    @Test
    fun healthAndExportWatermarksAreNeitherReadAsTheCursorNorMovedByIngest() = runBlocking {
        withInMemoryStore { db ->
            val far = Instant.parse("2030-01-01T00:00:00Z")
            val watermarks = listOf(StoredCursorEntity("hk:heartRate", far), StoredCursorEntity("export:samples", far))
            db.sampleDao().upsertCursors(watermarks)
            val store = LocalStore(db)

            assertNull(store.loadCursor().last(MetricKind.HEART_RATE))
            val stored = store.ingest(listOf(QuantitySample(MetricKind.HEART_RATE, start = Instant.parse("2026-01-01T00:00:00Z"), value = 60.0)), now, ZoneOffset.UTC)

            assertEquals(1, stored.size)
            val rows = db.sampleDao().allCursors().sortedBy { it.kindRaw }
            assertEquals(
                listOf(
                    StoredCursorEntity("export:samples", far),
                    StoredCursorEntity("heartRate", Instant.parse("2026-01-01T00:00:00Z")),
                    StoredCursorEntity("hk:heartRate", far),
                ),
                rows,
            )
        }
    }

    private class Ingested(val returned: List<QuantitySample>, val rows: List<StoredSampleEntity>)

    private suspend fun ingestAndRead(samples: List<QuantitySample>, zone: ZoneId): Ingested =
        withInMemoryStore { db ->
            val returned = LocalStore(db).ingest(samples, now, zone)
            Ingested(returned, db.sampleDao().allSamples())
        }

    private fun steps(at: String, raw: Double) = QuantitySample(MetricKind.STEPS, start = Instant.parse(at), value = raw)
}
