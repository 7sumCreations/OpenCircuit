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
 * The store tells a reader when the readings it shows changed: [LocalStore.changes] emits once when
 * collected, then after every committed write to a table of readings, from any writer over the same
 * database — the history drain, a live reading, the step counter — and not for the store's own
 * bookkeeping (the key-value rows).
 */
class StoreChangesTest {

    private val now = Instant.parse("2026-10-21T12:00:00Z")

    private suspend fun <T> withFileStore(block: suspend (StoreDatabase) -> T): T {
        val db = StoreFactory.openFile(Files.createTempDirectory("store-changes").resolve("store.db"))
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun theWatchedTablesAreEveryTableOfReadings() {
        assertEquals(
            setOf("stored_sample", "stored_daily", "stored_step_sample", "stored_daytime_temp", "stored_sleep_summary", "stored_nap"),
            LocalStore.READING_TABLES.toSet(),
        )
    }

    @Test
    fun aReadingWrittenByAnotherWriterIsAnnouncedAndBookkeepingIsNot() = runBlocking<Unit> {
        withFileStore { db ->
            val seen = Channel<Set<String>>(Channel.UNLIMITED)
            coroutineScope {
                val watcher = launch(Dispatchers.IO) { LocalStore(db).changes().collect { seen.send(it) } }
                assertNotNull(withTimeoutOrNull(5_000) { seen.receive() }, "one emission when collected")

                async(Dispatchers.IO) {
                    LocalStore(db).insertLiveSamples(listOf(QuantitySample(MetricKind.HEART_RATE, now.minusSeconds(60), value = 61.0)), now)
                }.await()
                val live = assertNotNull(withTimeoutOrNull(5_000) { seen.receive() }, "a live reading was not announced")
                assertTrue("stored_sample" in live, "$live")

                async(Dispatchers.IO) {
                    LocalStore(db).addDailySteps(120, day = now.minusSeconds(600), windowStart = now.minusSeconds(900), now = now, zone = ZoneOffset.UTC)
                }.await()
                val steps = assertNotNull(withTimeoutOrNull(5_000) { seen.receive() }, "a step delta was not announced")
                assertTrue("stored_daily" in steps || "stored_step_sample" in steps, "$steps")

                async(Dispatchers.IO) { BlobStore(db).saveHealthPermissionsRequested(now) }.await()
                assertNull(withTimeoutOrNull(1_500) { seen.receive() }, "a key-value write must not be announced")
                watcher.cancel()
            }
        }
    }
}
