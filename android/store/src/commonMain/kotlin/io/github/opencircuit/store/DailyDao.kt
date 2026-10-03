package io.github.opencircuit.store

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import java.time.Instant

/**
 * Reads and writes of `stored_daily`, `stored_step_sample` and `stored_daytime_temp`. Every call
 * runs on its own unless the caller holds a transaction ([LocalStore] wraps each multi-step write
 * in one). Ties on the sort column are broken by insertion order (`id`), as in [SampleDao].
 */
@Dao
internal interface DailyDao {
    @Insert
    suspend fun insertDaily(daily: StoredDailyEntity)

    @Update
    suspend fun updateDaily(daily: StoredDailyEntity)

    @Query("SELECT * FROM stored_daily WHERE day = :day")
    suspend fun dailyOn(day: Instant): StoredDailyEntity?

    @Query("SELECT * FROM stored_daily ORDER BY day, id")
    suspend fun allDailies(): List<StoredDailyEntity>

    /** Newest day first, at most [limit] rows (upstream `recentDailiesDescriptor`). */
    @Query("SELECT * FROM stored_daily ORDER BY day DESC, id DESC LIMIT :limit")
    suspend fun recentDailies(limit: Int): List<StoredDailyEntity>

    /** Rows with `from <= day < to`, oldest first. */
    @Query("SELECT * FROM stored_daily WHERE day >= :from AND day < :to ORDER BY day, id")
    suspend fun dailies(from: Instant, to: Instant): List<StoredDailyEntity>

    @Insert
    suspend fun insertStepSample(sample: StoredStepSampleEntity)

    /** Step deltas with `from <= start < to`, oldest first (upstream `stepSamplesDescriptor`). */
    @Query("SELECT * FROM stored_step_sample WHERE start >= :from AND start < :to ORDER BY start, id")
    suspend fun stepSamples(from: Instant, to: Instant): List<StoredStepSampleEntity>

    @Insert
    suspend fun insertDaytimeTemp(reading: StoredDaytimeTempEntity)

    /** Readings with `from <= time < to`, oldest first (upstream `daytimeTemperaturesDescriptor`). */
    @Query("SELECT * FROM stored_daytime_temp WHERE time >= :from AND time < :to ORDER BY time, id")
    suspend fun daytimeTemps(from: Instant, to: Instant): List<StoredDaytimeTempEntity>
}
