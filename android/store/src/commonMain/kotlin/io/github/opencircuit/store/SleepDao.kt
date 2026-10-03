package io.github.opencircuit.store

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import java.time.Instant

/**
 * Reads and writes of `stored_sleep_summary`. [SleepStore] wraps each write in one transaction.
 *
 * Inserts and updates use the default ABORT conflict strategy: a row moved or added onto an
 * occupied `night` fails the write and keeps both rows. The replacing strategy is never used here,
 * because it deletes the occupying row — the silent night loss upstream measured in SwiftData
 * (ios/OpenCircuit/Store/LocalStore.swift:2575-2580 @ b1c2fdd). `night` is unique, so ties on it
 * cannot occur; `id` still breaks them, as in [UserEntryDao].
 */
@Dao
internal interface SleepDao {
    @Insert
    suspend fun insertSummary(row: StoredSleepSummaryEntity)

    @Update
    suspend fun updateSummary(row: StoredSleepSummaryEntity)

    @Query("SELECT * FROM stored_sleep_summary WHERE night = :night")
    suspend fun summaryAt(night: Instant): StoredSleepSummaryEntity?

    @Query("SELECT * FROM stored_sleep_summary ORDER BY night, id")
    suspend fun allSummaries(): List<StoredSleepSummaryEntity>

    @Query("SELECT * FROM stored_sleep_summary ORDER BY night DESC, id DESC LIMIT 1")
    suspend fun latestSummary(): StoredSleepSummaryEntity?

    /** At most [limit] rows, newest night first. */
    @Query("SELECT * FROM stored_sleep_summary ORDER BY night DESC, id DESC LIMIT :limit")
    suspend fun recentSummaries(limit: Int): List<StoredSleepSummaryEntity>

    /** Rows with `from <= night < to`, oldest night first. */
    @Query("SELECT * FROM stored_sleep_summary WHERE night >= :from AND night < :to ORDER BY night, id")
    suspend fun summaries(from: Instant, to: Instant): List<StoredSleepSummaryEntity>

    /**
     * Rows with a known recorded in-bed window (`in_bed_end > in_bed_start`) that window overlapping
     * `(start, end)` by more than zero, oldest night first. The caller measures each overlap.
     */
    @Query(
        "SELECT * FROM stored_sleep_summary WHERE in_bed_end > in_bed_start AND in_bed_end > :start AND in_bed_start < :end " +
            "ORDER BY night, id",
    )
    suspend fun overlapCandidates(start: Instant, end: Instant): List<StoredSleepSummaryEntity>
}
