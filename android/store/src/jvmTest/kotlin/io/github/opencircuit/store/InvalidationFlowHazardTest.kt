package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Measures, before the dashboard relies on it, whether Room's invalidation flow fires on this
 * store's bundled SQLite driver when a write commits through a DIFFERENT store object and its own
 * transaction, on another coroutine — the way the history drain, live readings and launch repairs
 * write while the dashboard watches. The process opens one database and wraps it in many short-lived
 * `LocalStore` / `SleepStore` objects, so "another writer" means another wrapper over the same
 * database, not another database.
 *
 * Also recorded: a second database object opened over the same FILE is not seen by the first one's
 * tracker (no multi-instance invalidation on this driver), which is why the app must keep one
 * database per process.
 */
class InvalidationFlowHazardTest {

    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private val watched = arrayOf("stored_sample", "stored_daily", "stored_step_sample", "stored_daytime_temp", "stored_sleep_summary", "stored_nap")

    private fun hr(minute: Int, bpm: Double) = QuantitySample(MetricKind.HEART_RATE, now.minusSeconds(3_600L - minute * 60L), value = bpm)

    @Test
    fun aWriteThroughAnotherStoreObjectAndTransactionFiresTheFlow() = runBlocking<Unit> {
        val path = Files.createTempDirectory("e12-h3").resolve("store.db")
        val db = StoreFactory.openFile(path)
        try {
            val seen = Channel<Set<String>>(Channel.UNLIMITED)
            coroutineScope {
                val watcher = launch(Dispatchers.IO) {
                    db.invalidationTracker.createFlow(*watched, emitInitialState = true).collect { seen.send(it) }
                }
                // The initial emission proves the observer is registered before the write.
                assertNotNull(withTimeoutOrNull(5_000) { seen.receive() }, "no initial emission")

                val writer = async(Dispatchers.IO) { LocalStore(db).insertLiveSamples(listOf(hr(1, 61.0)), now) }
                assertEquals(1, writer.await().size)

                val change = withTimeoutOrNull(5_000) { seen.receive() }
                assertNotNull(change, "a committed write through another store object fired nothing within 5 s")
                assertTrue("stored_sample" in change, "the emission names the written table: $change")

                // A drain-shaped write (ingest: samples + cursor in one transaction) fires too.
                async(Dispatchers.IO) { LocalStore(db).ingest(listOf(hr(2, 62.0), hr(3, 63.0)), now, ZoneOffset.UTC) }.await()
                val drain = withTimeoutOrNull(5_000) { seen.receive() }
                assertNotNull(drain, "an ingest transaction fired nothing within 5 s")
                assertTrue("stored_sample" in drain)

                // A write to an unwatched table (the key-value store) does not fire.
                async(Dispatchers.IO) { BlobStore(db).saveHealthPermissionsRequested(now) }.await()
                assertNull(withTimeoutOrNull(1_500) { seen.receive() }, "a store_kv write must not fire the dashboard flow")

                watcher.cancel()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun aSecondDatabaseOverTheSameFileIsNotSeenByTheFirstTracker() = runBlocking<Unit> {
        val path = Files.createTempDirectory("e12-h3b").resolve("store.db")
        val first = StoreFactory.openFile(path)
        val second = StoreFactory.openFile(path)
        try {
            val seen = Channel<Set<String>>(Channel.UNLIMITED)
            coroutineScope {
                val watcher = launch(Dispatchers.IO) {
                    first.invalidationTracker.createFlow(*watched, emitInitialState = true).collect { seen.send(it) }
                }
                assertNotNull(withTimeoutOrNull(5_000) { seen.receive() }, "no initial emission")
                async(Dispatchers.IO) { LocalStore(second).insertLiveSamples(listOf(hr(4, 64.0)), now) }.await()
                assertNull(withTimeoutOrNull(2_000) { seen.receive() }, "measured: no cross-instance invalidation on the JVM driver")
                assertEquals(1, LocalStore(first).samples(MetricKind.HEART_RATE, now.minusSeconds(7_200), now).size, "the row is in the file")
                watcher.cancel()
            }
        } finally {
            second.close()
            first.close()
        }
    }
}
