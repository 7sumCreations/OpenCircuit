package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SyncCursor
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sync cursor's stored form is its `stored_cursor` rows, not JSON: upstream production
 * persists the cursor as `StoredCursor` rows (ios/OpenCircuit/Store/LocalStore.swift:64, :929,
 * :3126 @ b1c2fdd), and its Codable conformance is only exercised by the kit test ported here.
 */
class SyncCursorStoredFormTest {

    /**
     * Upstream `testCursorRoundTripsThroughCodable` (ios/OpenCircuitKit/Tests/OpenCircuitKitTests/
     * SyncCursorTests.swift:76), through the store's rows instead of JSON: the cursor advanced to
     * t1 (1970 + 2000 s) is written as its rows and read back with `loadCursor`. The rows are
     * written through the cursor DAO because 1970 is before the ring's epoch, which the ingest
     * path would refuse.
     */
    @Test
    fun cursorRoundTripsThroughItsStoredRows() = runBlocking {
        withInMemoryStore { db ->
            val t1 = Instant.ofEpochSecond(2000)
            val c = SyncCursor()
            c.advance(MetricKind.STEPS, to = t1)
            db.sampleDao().upsertCursors(MetricKind.entries.mapNotNull { kind -> c.last(kind)?.let { StoredCursorEntity(kind.rawValue, it) } })

            val back = LocalStore(db).loadCursor()

            assertEquals(c, back)
            assertEquals(t1, back.last(MetricKind.STEPS))
        }
    }

    /**
     * Every metric's watermark survives a close and reopen of the database file, written by the
     * production ingest path, and a metric that never synced reads back as never synced.
     */
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun everyMetricsWatermarkSurvivesAReopenAndANeverSyncedMetricStaysUnset() = runBlocking {
        val dir = createTempDirectory("store-cursor")
        try {
            val path = dir.resolve("store.db")
            val now = Instant.parse("2026-10-03T12:00:00Z")
            val neverSynced = MetricKind.EXERCISE_MINUTES
            val synced = MetricKind.entries - neverSynced
            // One sample per metric, each at its own millisecond-exact time.
            val samples = synced.mapIndexed { i, kind ->
                QuantitySample(kind, start = now.minusSeconds(600L - i * 30).plusMillis(123), value = 70.0 + i)
            }
            val expected = SyncCursor()
            samples.forEach { expected.advance(it.kind, to = it.start) }

            val db = StoreFactory.openFile(path)
            try {
                assertEquals(samples.size, LocalStore(db).ingest(samples, now, ZoneOffset.UTC).size)
            } finally {
                db.close()
            }

            val reopened = StoreFactory.openFile(path)
            try {
                val back = LocalStore(reopened).loadCursor()
                assertEquals(expected, back)
                for (s in samples) assertEquals(s.start, back.last(s.kind), "watermark of ${s.kind}")
                assertNull(back.last(neverSynced))
            } finally {
                reopened.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
