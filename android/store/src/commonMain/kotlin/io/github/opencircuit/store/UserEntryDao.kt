package io.github.opencircuit.store

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import java.time.Instant

/**
 * Reads and writes of `stored_period_entry`, `stored_headache_entry` and `stored_headache_risk`.
 * [LocalStore] wraps each multi-step write in one transaction. Ties on the sort column cannot occur
 * (each table is unique on it); `id` still breaks them, as in [SampleDao].
 */
@Dao
internal interface UserEntryDao {
    @Insert
    suspend fun insertPeriod(row: StoredPeriodEntryEntity)

    @Update
    suspend fun updatePeriod(row: StoredPeriodEntryEntity)

    @Delete
    suspend fun deletePeriod(row: StoredPeriodEntryEntity)

    @Query("SELECT * FROM stored_period_entry WHERE start = :start")
    suspend fun periodAt(start: Instant): StoredPeriodEntryEntity?

    @Query("SELECT * FROM stored_period_entry ORDER BY start, id")
    suspend fun allPeriods(): List<StoredPeriodEntryEntity>

    @Insert
    suspend fun insertHeadache(row: StoredHeadacheEntryEntity)

    @Update
    suspend fun updateHeadache(row: StoredHeadacheEntryEntity)

    @Delete
    suspend fun deleteHeadache(row: StoredHeadacheEntryEntity)

    @Query("SELECT * FROM stored_headache_entry WHERE onset = :onset")
    suspend fun headacheAt(onset: Instant): StoredHeadacheEntryEntity?

    @Query("SELECT * FROM stored_headache_entry ORDER BY onset, id")
    suspend fun allHeadaches(): List<StoredHeadacheEntryEntity>

    /** Entries with `from <= onset < to`, oldest first. */
    @Query("SELECT * FROM stored_headache_entry WHERE onset >= :from AND onset < :to ORDER BY onset, id")
    suspend fun headaches(from: Instant, to: Instant): List<StoredHeadacheEntryEntity>

    @Query("SELECT imported_hk_uuid FROM stored_headache_entry WHERE imported_hk_uuid IS NOT NULL")
    suspend fun importedHeadacheUUIDs(): List<String>

    @Insert
    suspend fun insertRisk(row: StoredHeadacheRiskEntity)

    @Update
    suspend fun updateRisk(row: StoredHeadacheRiskEntity)

    @Query("SELECT * FROM stored_headache_risk WHERE day = :day")
    suspend fun riskOn(day: Instant): StoredHeadacheRiskEntity?

    /** The first row (by insertion) scoring [nightKey]; upstream takes the fetch's first match. */
    @Query("SELECT * FROM stored_headache_risk WHERE night_key = :nightKey ORDER BY id LIMIT 1")
    suspend fun riskForNight(nightKey: Instant): StoredHeadacheRiskEntity?

    /** Rows with `from <= day < to`, oldest first. */
    @Query("SELECT * FROM stored_headache_risk WHERE day >= :from AND day < :to ORDER BY day, id")
    suspend fun riskDays(from: Instant, to: Instant): List<StoredHeadacheRiskEntity>
}
