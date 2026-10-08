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

    /** Every row with `from <= key < until`, in key order (the primary key's index serves both bounds). */
    @Query("SELECT * FROM store_kv WHERE `key` >= :from AND `key` < :until ORDER BY `key`")
    suspend fun range(from: String, until: String): List<StoreKvEntity>

    /** Deletes every row with `from <= key < until`; returns how many. */
    @Query("DELETE FROM store_kv WHERE `key` >= :from AND `key` < :until")
    suspend fun deleteRange(from: String, until: String): Int
}
