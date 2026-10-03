package io.github.opencircuit.store

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Every schema version the store has ever shipped must reopen through the production
 * [StoreFactory] with every row intact. Each version gets its own test here, named after it: the
 * database is created exactly as that version's committed schema
 * (`store/schemas/io.github.opencircuit.store.StoreDatabase/N.json`) describes it, filled with
 * realistic rows by raw SQL, closed, then opened the way the app opens it — which runs every
 * migration from N to the current version and validates the result. A version with no migration
 * path, a migration that drops a row, or an unbumped schema change fails here.
 *
 * The comparison is of row contents, not counts.
 *
 * Room's JVM `MigrationTestHelper` is a JUnit 4 rule; under JUnit 5 it is driven by hand and every
 * connection it hands out is closed in the test.
 */
class StoreMigrationTest {

    private lateinit var dir: Path
    private lateinit var dbPath: Path

    @BeforeTest
    fun freshDirectory() {
        dir = createTempDirectory("store-migration")
        dbPath = dir.resolve("store.db")
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun removeDirectory() {
        dir.deleteRecursively()
        assertFalse(Files.exists(dir), "a test left its database behind")
    }

    private fun helper() = MigrationTestHelper(
        schemaDirectoryPath = schemaDirectory(),
        databasePath = dbPath,
        driver = BundledSQLiteDriver(),
        databaseClass = StoreDatabase::class,
    )

    /** Version 1, the baseline: the first schema the store ships. */
    @Test
    fun version1ReopensThroughTheFactoryWithEveryRowIntact() = runBlocking {
        val v1 = helper().createDatabase(1)
        try {
            v1.execSQL(
                "INSERT INTO stored_sample (id, kind_raw, start, `end`, value, raw_value, is_delta, daily_total) VALUES " +
                    "(1, 'heartRate', 1781389739000, 1781389739000, 85.0, NULL, 0, NULL), " +
                    "(2, 'spo2', 1781389739000, 1781389739000, 0.95, NULL, 0, NULL), " +
                    "(3, 'steps', 1781390039000, 1781390099000, 50.0, 150.0, 1, 150.0), " +
                    // A kind this build does not know: kept as stored, never dropped.
                    "(4, 'aFutureKind', 1781390099000, 1781390099000, 1.5, NULL, 0, NULL)",
            )
            v1.execSQL(
                "INSERT INTO stored_cursor (kind_raw, last) VALUES " +
                    "('heartRate', 1781389739000), ('steps', 1781390039000), " +
                    "('hk:heartRate', 1781300000000), ('export:samples', 1781200000000)",
            )
        } finally {
            v1.close()
        }

        val db = StoreFactory.openFile(dbPath)
        try {
            assertEquals(
                listOf(
                    StoredSampleEntity(1, "heartRate", ms(1781389739000), ms(1781389739000), 85.0, null, false, null),
                    StoredSampleEntity(2, "spo2", ms(1781389739000), ms(1781389739000), 0.95, null, false, null),
                    StoredSampleEntity(3, "steps", ms(1781390039000), ms(1781390099000), 50.0, 150.0, true, 150.0),
                    StoredSampleEntity(4, "aFutureKind", ms(1781390099000), ms(1781390099000), 1.5, null, false, null),
                ),
                db.sampleDao().allSamples(),
            )
            assertEquals(
                listOf(
                    StoredCursorEntity("export:samples", ms(1781200000000)),
                    StoredCursorEntity("heartRate", ms(1781389739000)),
                    StoredCursorEntity("hk:heartRate", ms(1781300000000)),
                    StoredCursorEntity("steps", ms(1781390039000)),
                ),
                db.sampleDao().allCursors().sortedBy { it.kindRaw },
            )
        } finally {
            db.close()
        }
    }

    /** Version 1, empty: a store created and never written reopens empty. */
    @Test
    fun version1EmptyStoreReopensThroughTheFactoryEmpty() = runBlocking {
        helper().createDatabase(1).close()

        val db = StoreFactory.openFile(dbPath)
        try {
            assertEquals(emptyList(), db.sampleDao().allSamples())
            assertEquals(emptyList(), db.sampleDao().allCursors())
        } finally {
            db.close()
        }
    }

    private fun ms(epochMillis: Long): Instant = Instant.ofEpochMilli(epochMillis)

    private fun schemaDirectory(): Path {
        val storeDir = assertNotNull(System.getProperty("opencircuit.storeDir"), "opencircuit.storeDir is not set — see store/build.gradle.kts")
        return Paths.get(storeDir, "schemas")
    }
}
