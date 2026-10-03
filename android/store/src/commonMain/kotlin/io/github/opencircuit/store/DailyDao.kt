package io.github.opencircuit.store

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

@Dao
internal interface DailyDao {
    @Insert
    suspend fun insertDaily(daily: StoredDailyEntity)

    @Query("SELECT * FROM stored_daily ORDER BY day, id")
    suspend fun allDailies(): List<StoredDailyEntity>
}
