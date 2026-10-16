package io.github.opencircuit.store

import io.github.opencircuit.store.codec.Decoded
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The Health Connect write generation: one number in the key-value table, kept across a close and
 * reopen of the database, and reported (never read as absent) when what is stored is damaged.
 * Damaged fixtures are planted on the raw path (SQL).
 */
class HealthWriteGenerationStoreTest {

    private val now = Instant.parse("2026-10-16T08:00:00Z")

    private suspend fun StoreDatabase.plant(value: String) =
        execRaw("INSERT OR REPLACE INTO store_kv(`key`, value, updated_at) VALUES ('hc.writeGeneration', '$value', 1)")

    @Test
    fun nothingStoredReadsAsNone() = runBlocking {
        withInMemoryStore { db -> assertNull(BlobStore(db).loadHealthWriteGeneration()) }
    }

    @Test
    fun aSavedGenerationReadsBackUnderItsKey() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            blobs.saveHealthWriteGeneration(17, now)
            assertEquals(Decoded.Readable(17L), blobs.loadHealthWriteGeneration())
            assertEquals(listOf("17|${now.toEpochMilli()}"), db.queryRaw("SELECT value, updated_at FROM store_kv WHERE `key` = 'hc.writeGeneration'"))
            blobs.saveHealthWriteGeneration(18, now)
            assertEquals(Decoded.Readable(18L), blobs.loadHealthWriteGeneration())
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun theGenerationSurvivesAReopenOfTheDatabaseFile() = runBlocking<Unit> {
        val dir = createTempDirectory("store-generation")
        try {
            val path = dir.resolve("store.db")
            val db = StoreFactory.openFile(path)
            try {
                BlobStore(db).saveHealthWriteGeneration(9_000_000_000L, now)
            } finally {
                db.close()
            }
            val reopened = StoreFactory.openFile(path)
            try {
                assertEquals(Decoded.Readable(9_000_000_000L), BlobStore(reopened).loadHealthWriteGeneration())
            } finally {
                reopened.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aDamagedValueIsReportedNotReadAsNone() = runBlocking<Unit> {
        for (bad in listOf("0", "-3", "1.5", "", "12abc", "９", "99999999999999999999")) {
            withInMemoryStore { db ->
                db.plant(bad)
                assertIs<Decoded.Unreadable>(BlobStore(db).loadHealthWriteGeneration(), "stored '$bad'")
            }
        }
    }

    @Test
    fun aGenerationBelowOneIsNeverSaved() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            assertFailsWith<IllegalArgumentException> { blobs.saveHealthWriteGeneration(0, now) }
            assertNull(blobs.loadHealthWriteGeneration())
        }
    }
}
