package io.github.opencircuit.store

import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

/**
 * The on-device database. Open it only through [StoreFactory].
 *
 * Every schema version's exported JSON is committed under `store/schemas/`. Changing an entity
 * means a new version, a migration and a migration test; there is no destructive fallback.
 */
@Database(
    entities = [
        StoredSampleEntity::class,
        StoredCursorEntity::class,
        StoredDailyEntity::class,
        StoredStepSampleEntity::class,
        StoredDaytimeTempEntity::class,
        StoreKvEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ColumnTypeConverters(InstantColumnConverter::class)
@ConstructedBy(StoreDatabaseConstructor::class)
abstract class StoreDatabase : RoomDatabase() {
    internal abstract fun sampleDao(): SampleDao

    internal abstract fun dailyDao(): DailyDao

    internal abstract fun kvDao(): KvDao
}

/** Room generates the `actual` for each target. */
@Suppress("KotlinNoActualForExpect")
expect object StoreDatabaseConstructor : RoomDatabaseConstructor<StoreDatabase> {
    override fun initialize(): StoreDatabase
}
