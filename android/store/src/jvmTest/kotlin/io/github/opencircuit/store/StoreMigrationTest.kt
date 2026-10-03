package io.github.opencircuit.store

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.opencircuit.ringkit.RingAlarm
import io.github.opencircuit.ringkit.VibrationPattern
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
import kotlin.test.assertTrue

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

    /**
     * Version 1, every table: a row in each of the eleven tables, among them the rows a person made
     * by hand and that no sync can bring back (an edited night, an edited nap, a nap added by hand,
     * a period, two headaches, a frozen risk day, the alarm). After the reopen every row of every
     * table is still there with every cell unchanged, and the hand-made rows read back as values.
     */
    @Test
    fun version1EveryTableAndEveryHandMadeRowReopenThroughTheFactoryIntact() = runBlocking<Unit> {
        val v1 = helper().createDatabase(1)
        val before = try {
            insertOneOfEverything(v1)
            TableSnapshot.of(v1)
        } finally {
            v1.close()
        }
        assertTrue(before.size >= 11, "only ${before.size} tables in version 1")
        assertEquals(emptySet(), before.filterValues { it.isEmpty() }.keys, "a table with no row in the fixture proves nothing")

        val db = StoreFactory.openFile(dbPath)
        try {
            val after = TableSnapshot.of(db)
            before.forEach { (table, rows) ->
                assertTrue((after[table]?.size ?: 0) >= rows.size, "$table: ${after[table]?.size} rows after, ${rows.size} before")
            }
            assertEquals(emptyList(), TableSnapshot.lostRows(before, after))

            val store = LocalStore(db)
            assertEquals(
                listOf(
                    PeriodEntry(
                        start = ms(1781222400000), end = null, flowLevelRaw = 3, symptoms = listOf("cramps", "fatigue"),
                        notes = "Started at work — \"heavy\" day", healthWritten = true,
                        hkSampleUUIDs = listOf("0C7D2E5A-6B1F-4C3D-9E8A-1F2B3C4D5E6F", "7A8B9C0D-1E2F-4A5B-8C7D-6E5F4A3B2C1D"),
                        updatedAt = ms(1781230000000),
                    ),
                ),
                store.allPeriodEntries(),
            )
            assertEquals(
                listOf(
                    HeadacheEntry(
                        onset = ms(1781190000000), end = null, severityRaw = 1, symptoms = emptyList(), customSymptoms = emptyList(),
                        factors = emptyList(), notes = "", sourceRaw = "healthImport",
                        importedHKUUID = "1B4E28BA-2FA1-11D2-883F-0016D3CCA427", healthWritten = false, hkSampleUUIDs = emptyList(),
                        updatedAt = ms(1781190500000),
                    ),
                    HeadacheEntry(
                        onset = ms(1781280000000), end = ms(1781294400000), severityRaw = 2, symptoms = listOf("nausea"),
                        customSymptoms = listOf("neck stiffness"), factors = listOf("poor sleep", "screen time"),
                        notes = "Better after rest", sourceRaw = "user", importedHKUUID = null, healthWritten = true,
                        hkSampleUUIDs = listOf("5D6E7F80-9A1B-4C2D-8E3F-405162738495"), updatedAt = ms(1781295000000),
                    ),
                ),
                store.allHeadacheEntries(),
            )
            assertEquals(
                HeadacheRiskDay(
                    day = ms(1781308800000), nightKey = ms(1781308800000), index = 61.5, bandRaw = 2, ringFeatureCount = 5,
                    coverageFraction = 0.875, contributionsJSON = """{"hrv":-0.42,"skinTemp":0.31}""", absentJSON = """["spo2"]""",
                    computedAt = ms(1781331000000), sleepUpdatedAt = ms(1781330000000), sleepRestaged = true, alerted = true,
                    postUnlock = false, updatedAt = ms(1781333000000),
                ),
                store.riskRow(ms(1781308800000)),
            )
            assertEquals(
                StoredAlarm.Readable(
                    RingAlarm(
                        isEnabled = true, hour = 6, minute = 45, weekdays = setOf(2, 3, 4, 5, 6), pattern = VibrationPattern.LONG,
                        burstCount = 4, burstSpacing = 6.5, backupNotification = false,
                    ),
                ),
                BlobStore(db, db.kvDao()).loadAlarm(),
            )
            // The two tables whose reads belong to the sleep write flow: the hand-made facts, column by column.
            assertEquals(
                listOf("1781308800000|0|-62135769600000|-62135769600000", "1781395200000|1|1781388000000|1781416800000"),
                db.queryRaw("SELECT night, is_manually_edited, edited_in_bed_start, edited_in_bed_end FROM stored_sleep_summary ORDER BY night"),
            )
            assertEquals(
                listOf("1781352000000|1|0|1781352300000|1781355300000", "1781438400000|0|1|null|null"),
                db.queryRaw(
                    "SELECT start, is_manually_edited, is_manually_added, ifnull(edited_start, 'null'), ifnull(edited_end, 'null') " +
                        "FROM stored_nap ORDER BY start",
                ),
            )
        } finally {
            db.close()
        }
    }

    /** One realistic or hand-made row (or more) in every table of version 1, by raw SQL. */
    private fun insertOneOfEverything(v1: SQLiteConnection) {
        v1.execSQL(
            "INSERT INTO stored_sample (id, kind_raw, start, `end`, value, raw_value, is_delta, daily_total) VALUES " +
                "(1, 'heartRate', 1781389739000, 1781389739000, 85.0, NULL, 0, NULL), " +
                "(2, 'steps', 1781390039000, 1781390099000, 50.0, 150.0, 1, 150.0)",
        )
        v1.execSQL("INSERT INTO stored_cursor (kind_raw, last) VALUES ('heartRate', 1781389739000), ('hk:heartRate', 1781300000000)")
        v1.execSQL(
            "INSERT INTO stored_daily (id, day, steps, updated_at, health_written_steps) VALUES (1, 1781308800000, 8423, 1781390099000, 8000)",
        )
        v1.execSQL(
            "INSERT INTO stored_step_sample (id, start, `end`, delta, health_written) VALUES (1, 1781390039000, 1781390099000, 150, 1)",
        )
        v1.execSQL("INSERT INTO stored_daytime_temp (id, time, celsius) VALUES (1, 1781380000000, 34.25)")
        // A night the ring recorded (most columns left to their defaults) and a night the person edited.
        v1.execSQL(
            "INSERT INTO stored_sleep_summary (id, night, asleep_min, deep_min, light_min, rem_min, awake_min, efficiency, " +
                "in_bed_start, in_bed_end, sleep_onset, sleep_wake, updated_at, skin_temp_c, sleep_score, hr_deep, " +
                "movement_levels, hypnogram_data, measured_asleep_seconds, sleep_basis) VALUES " +
                "(1, 1781308800000, 412, 78, 230, 104, 31, 0.93, 1781302500000, 1781329200000, 1781303400000, " +
                "1781328600000, 1781330000000, 33.6, 84, 52, '[0,1,3,0]', x'0102020301', 24720.0, 'measured')",
        )
        v1.execSQL(
            "INSERT INTO stored_sleep_summary (id, night, asleep_min, in_bed_start, in_bed_end, updated_at, " +
                "edited_in_bed_start, edited_in_bed_end, is_manually_edited, widened_recorded_in_bed_start, " +
                "widened_recorded_in_bed_end, recorded_hypnogram_data, sleep_basis) VALUES " +
                "(2, 1781395200000, 395, 1781389800000, 1781415000000, 1781417000000, 1781388000000, 1781416800000, 1, " +
                "1781389800000, 1781415000000, x'0303', 'asserted')",
        )
        // A nap the person edited, and a nap the person added by hand.
        v1.execSQL(
            "INSERT INTO stored_nap (id, start, `end`, asleep_min, is_long_nap, health_written, updated_at, is_manually_edited, " +
                "nap_segments_data, edited_start, edited_end, recorded_nap_segments_data, health_written_start, health_written_end) " +
                "VALUES (1, 1781352000000, 1781355600000, 52, 0, 1, 1781356000000, 1, x'01', 1781352300000, 1781355300000, " +
                "x'0102', 1781352000000, 1781355600000)",
        )
        v1.execSQL(
            "INSERT INTO stored_nap (id, start, `end`, asleep_min, updated_at, is_manually_added) " +
                "VALUES (2, 1781438400000, 1781442000000, 45, 1781442100000, 1)",
        )
        v1.execSQL(
            "INSERT INTO stored_period_entry (id, start, `end`, flow_level_raw, symptoms, notes, health_written, hk_sample_uuids, " +
                "updated_at) VALUES (1, 1781222400000, NULL, 3, '[\"cramps\",\"fatigue\"]', 'Started at work — \"heavy\" day', 1, " +
                "'[\"0C7D2E5A-6B1F-4C3D-9E8A-1F2B3C4D5E6F\",\"7A8B9C0D-1E2F-4A5B-8C7D-6E5F4A3B2C1D\"]', 1781230000000)",
        )
        v1.execSQL(
            "INSERT INTO stored_headache_entry (id, onset, `end`, severity_raw, symptoms, custom_symptoms, factors, notes, " +
                "source_raw, imported_hk_uuid, health_written, hk_sample_uuids, updated_at) VALUES " +
                "(1, 1781280000000, 1781294400000, 2, '[\"nausea\"]', '[\"neck stiffness\"]', '[\"poor sleep\",\"screen time\"]', " +
                "'Better after rest', 'user', NULL, 1, '[\"5D6E7F80-9A1B-4C2D-8E3F-405162738495\"]', 1781295000000), " +
                "(2, 1781190000000, NULL, 1, '[]', '[]', '[]', '', 'healthImport', '1B4E28BA-2FA1-11D2-883F-0016D3CCA427', 0, '[]', " +
                "1781190500000)",
        )
        v1.execSQL(
            "INSERT INTO stored_headache_risk (id, day, night_key, `index`, band_raw, ring_feature_count, coverage_fraction, " +
                "contributions_json, absent_json, computed_at, sleep_updated_at, sleep_restaged, alerted, post_unlock, updated_at) " +
                "VALUES (1, 1781308800000, 1781308800000, 61.5, 2, 5, 0.875, '{\"hrv\":-0.42,\"skinTemp\":0.31}', '[\"spo2\"]', " +
                "1781331000000, 1781330000000, 1, 1, 0, 1781333000000)",
        )
        v1.execSQL(
            "INSERT INTO store_kv (`key`, value, updated_at) VALUES " +
                "('alarm.ring.config', '{\"backupNotification\":false,\"burstCount\":4,\"burstSpacing\":6.5,\"hour\":6," +
                "\"isEnabled\":true,\"minute\":45,\"pattern\":2,\"weekdays\":[2,3,4,5,6]}', 1781200000000), " +
                // A key this build does not know: kept as stored.
                "('aFutureKey.v9', '{\"x\":1}', 1781200000001)",
        )
    }

    private fun ms(epochMillis: Long): Instant = Instant.ofEpochMilli(epochMillis)

    private fun schemaDirectory(): Path {
        val storeDir = assertNotNull(System.getProperty("opencircuit.storeDir"), "opencircuit.storeDir is not set — see store/build.gradle.kts")
        return Paths.get(storeDir, "schemas")
    }
}
