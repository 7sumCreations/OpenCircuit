package io.github.opencircuit.store

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
internal interface KvDao {
    @Upsert
    suspend fun upsert(entry: StoreKvEntity)

    @Query("SELECT * FROM store_kv WHERE `key` = :key")
    suspend fun get(key: String): StoreKvEntity?

    @Query("DELETE FROM store_kv WHERE `key` = :key")
    suspend fun delete(key: String)
}
