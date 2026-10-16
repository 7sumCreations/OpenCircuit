package io.github.opencircuit.store

import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.codec.Decoded
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store's homes for the Health Connect writer beyond the watermark: stored samples with their
 * row ids (two rows of one kind may share a start, and each needs its own record id), a night's
 * key text, the per-type write-failure map (upstream `hk.failures.byMetric`,
 * ios/OpenCircuit/Health/HealthKitWriter.swift :377-396) and the "permission screen shown once"
 * flag. Damaged values are planted on the raw path (SQL).
 */
class HealthStoreHomesTest {

    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val utc = ZoneOffset.UTC

    private fun at(t: Long): Instant = Instant.ofEpochSecond(Command.SYNC_EPOCH + t)

    private suspend fun StoreDatabase.plantSample(kindRaw: String, t: Long, value: Double) {
        val ms = at(t).toEpochMilli()
        execRaw("INSERT INTO stored_sample(kind_raw, start, `end`, value, raw_value, is_delta, daily_total) VALUES ('$kindRaw', $ms, $ms, $value, NULL, 0, NULL)")
    }

    private suspend fun StoreDatabase.ids(kindRaw: String): List<Long> =
        queryRaw("SELECT id FROM stored_sample WHERE kind_raw = '$kindRaw' ORDER BY start, id").map { it.toLong() }

    private suspend fun StoreDatabase.plantKv(key: String, value: String) =
        execRaw("INSERT OR REPLACE INTO store_kv(`key`, value, updated_at) VALUES ('$key', '$value', 1)")

    // ---- rows with their ids ----

    @Test
    fun pendingRowsCarryTheirRowIdsInRowOrderAndFollowThePendingRules() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.plantSample("hrvSDNN", 100, 40.0)
            db.plantSample("hrvSDNN", 100, 42.0)
            db.plantSample("hrvSDNN", 200, 0.0) // zero: never pending
            db.plantSample("hrvSDNN", 300, 45.0)
            val ids = db.ids("hrvSDNN")
            val store = LocalStore(db)

            val pending = store.pendingHealthSampleRows(MetricKind.HRV_SDNN)

            assertEquals(listOf(ids[0], ids[1], ids[3]), pending.map { it.id })
            assertEquals(listOf(40.0, 42.0, 45.0), pending.map { it.sample.value })
            assertEquals(pending.map { it.sample }, store.pendingHealthSamples(MetricKind.HRV_SDNN), "the same rows as the plain read")

            store.markHealthWritten(listOf(pending[1].sample))
            assertEquals(listOf(ids[3]), store.pendingHealthSampleRows(MetricKind.HRV_SDNN).map { it.id })
        }
    }

    @Test
    fun pendingRowsOfAKindNotWrittenFromTheStoreAreNone() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.plantSample("steps", 100, 12.0)
            assertEquals(emptyList(), LocalStore(db).pendingHealthSampleRows(MetricKind.STEPS))
        }
    }

    @Test
    fun sampleRowsReadEveryValueOfOneKindInTheSpanWithTheirIds() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.plantSample("spo2", 100, 0.97)
            db.plantSample("spo2", 100, 0.0)
            db.plantSample("spo2", 200, 0.95)
            db.plantSample("respiratoryRate", 100, 14.0)
            val ids = db.ids("spo2")

            val rows = LocalStore(db).sampleRows(MetricKind.SPO2, at(100), at(200))

            assertEquals(listOf(ids[0], ids[1]), rows.map { it.id }, "from inclusive, until exclusive, zeros kept")
            assertEquals(listOf(0.97, 0.0), rows.map { it.sample.value })
        }
    }

    // ---- a night's key text ----

    @Test
    fun aStoredNightsKeyTextIsItsWholeEpochSecondsAsUpstreamPrintsThem() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val night = Instant.parse("2025-06-14T22:00:00Z")
            db.execRaw("INSERT INTO stored_sleep_summary(night, in_bed_start, in_bed_end) VALUES (${night.toEpochMilli()}, ${night.toEpochMilli()}, ${night.plusSeconds(28_800).toEpochMilli()})")

            val stored = SleepStore(db).sleepSummaries(night, night.plusSeconds(1)).single()

            assertEquals("1749938400.0", stored.keyText)
        }
    }

    // ---- the write-failure map ----

    @Test
    fun theFailureMapReadsBackWhatWasSavedAndAnEmptyMapRemovesIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            assertNull(blobs.loadHealthWriteFailures())

            blobs.saveHealthWriteFailures(mapOf("SLEEP_SESSION" to Instant.ofEpochMilli(2_000), "OXYGEN_SATURATION" to Instant.ofEpochMilli(1_000)), now)

            assertEquals(Decoded.Readable(mapOf("OXYGEN_SATURATION" to Instant.ofEpochMilli(1_000), "SLEEP_SESSION" to Instant.ofEpochMilli(2_000))), blobs.loadHealthWriteFailures())
            assertEquals(
                listOf("OXYGEN_SATURATION=1000;SLEEP_SESSION=2000|${now.toEpochMilli()}"),
                db.queryRaw("SELECT value, updated_at FROM store_kv WHERE `key` = 'hc.writeFailures'"),
                "sorted by name, ASCII digits",
            )

            blobs.saveHealthWriteFailures(emptyMap(), now)
            assertNull(blobs.loadHealthWriteFailures())
            assertEquals(emptyList(), db.queryRaw("SELECT value FROM store_kv WHERE `key` = 'hc.writeFailures'"))
        }
    }

    @Test
    fun aDamagedFailureMapIsReportedNotReadAsEmpty() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            for (damaged in listOf("SLEEP_SESSION", "SLEEP_SESSION=", "sleep=1", "SLEEP_SESSION=1;;HEART_RATE=2", "SLEEP_SESSION=１", "SLEEP_SESSION=-5", "")) {
                db.plantKv("hc.writeFailures", damaged)
                assertIs<Decoded.Unreadable>(blobs.loadHealthWriteFailures(), "\"$damaged\"")
            }
        }
    }

    @Test
    fun aFailureMapNameMustBeAnUpperCaseConstant() = runBlocking<Unit> {
        withInMemoryStore { db ->
            assertFailsWith<IllegalArgumentException> { BlobStore(db).saveHealthWriteFailures(mapOf("a=b" to now), now) }
            assertEquals(emptyList(), db.queryRaw("SELECT value FROM store_kv WHERE `key` = 'hc.writeFailures'"))
        }
    }

    // ---- the permission screen shown once ----

    @Test
    fun thePermissionScreenFlagIsUnsetUntilSavedAndThenKept() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            assertFalse(blobs.loadHealthPermissionsRequested())

            blobs.saveHealthPermissionsRequested(now)

            assertTrue(blobs.loadHealthPermissionsRequested())
            assertEquals(listOf("1|${now.toEpochMilli()}"), db.queryRaw("SELECT value, updated_at FROM store_kv WHERE `key` = 'hc.permissionsRequested'"))
        }
    }

    @Test
    fun aDamagedPermissionScreenFlagReadsAsShownSoTheCardNeverLoopsOnTheRequest() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.plantKv("hc.permissionsRequested", "yes")
            assertTrue(BlobStore(db).loadHealthPermissionsRequested())
        }
    }

    @Test
    fun theHealthHomesAreNotIngestRows() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(QuantitySample(MetricKind.SPO2, at(100), value = 0.97)), now, utc)
            BlobStore(db).saveHealthWriteFailures(mapOf("SLEEP_SESSION" to now), now)
            BlobStore(db).saveHealthPermissionsRequested(now)

            assertEquals(1, store.pendingHealthSampleRows(MetricKind.SPO2).size)
            assertEquals(at(100), store.loadCursor().last(MetricKind.SPO2))
        }
    }
}
