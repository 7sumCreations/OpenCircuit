package io.github.opencircuit.store

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import java.time.Instant

/**
 * Reads and writes of `stored_sleep_summary` and `stored_nap`. [SleepStore] wraps each write in one
 * transaction.
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

    /** Rows keyed `lo <= night <= hi` (both ends included), oldest night first. */
    @Query("SELECT * FROM stored_sleep_summary WHERE night >= :lo AND night <= :hi ORDER BY night, id")
    suspend fun summariesKeyedBetween(lo: Instant, hi: Instant): List<StoredSleepSummaryEntity>

    // Naps, keyed by their unique `start`.

    @Insert
    suspend fun insertNap(row: StoredNapEntity)

    @Update
    suspend fun updateNap(row: StoredNapEntity)

    /** Deletes the nap keyed [start]; returns the rows deleted (0 or 1). */
    @Query("DELETE FROM stored_nap WHERE start = :start")
    suspend fun deleteNapAt(start: Instant): Int

    @Query("SELECT * FROM stored_nap WHERE start = :start")
    suspend fun napAt(start: Instant): StoredNapEntity?

    @Query("SELECT * FROM stored_nap ORDER BY start, id")
    suspend fun allNaps(): List<StoredNapEntity>

    /** Naps with `from <= start < to`, the earliest start first. */
    @Query("SELECT * FROM stored_nap WHERE start >= :from AND start < :to ORDER BY start, id")
    suspend fun napsStarting(from: Instant, to: Instant): List<StoredNapEntity>

    /** Naps with `from <= start < to`, the latest start first. */
    @Query("SELECT * FROM stored_nap WHERE start >= :from AND start < :to ORDER BY start DESC, id DESC")
    suspend fun napsStartingLatestFirst(from: Instant, to: Instant): List<StoredNapEntity>

    // What a night's move onto another key carries with it: the watermarks keyed by the night in
    // `stored_cursor` (keyed by `kind_raw`, so a rename is a delete and an insert) and the headache
    // risk rows naming the night.

    @Query("SELECT * FROM stored_cursor WHERE kind_raw = :kindRaw")
    suspend fun cursorAt(kindRaw: String): StoredCursorEntity?

    /** Refuses (ABORT) a key already stored: a watermark is never folded into another night's. */
    @Insert
    suspend fun insertCursor(row: StoredCursorEntity)

    @Update
    suspend fun updateCursor(row: StoredCursorEntity)

    @Query("DELETE FROM stored_cursor WHERE kind_raw = :kindRaw")
    suspend fun deleteCursorAt(kindRaw: String): Int

    /** How many headache risk rows name the night [nightKey]. */
    @Query("SELECT COUNT(*) FROM stored_headache_risk WHERE night_key = :nightKey")
    suspend fun riskRowsForNight(nightKey: Instant): Int

    /** Points every headache risk row naming the night [from] at [to]; returns how many. */
    @Query("UPDATE stored_headache_risk SET night_key = :to WHERE night_key = :from")
    suspend fun renameHeadacheNightKey(from: Instant, to: Instant): Int
}
