package io.github.opencircuit.store

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.time.Instant

/**
 * One stored value by key: the store's home for what upstream keeps in `UserDefaults` (stored
 * blobs, one-time repair latches). Living in the same database as the samples, a value can be
 * written in the same transaction as the rows it belongs with. [value] is never NULL: a key is
 * either present with a value or absent.
 */
@Entity(tableName = "store_kv")
internal data class StoreKvEntity(
    @PrimaryKey val key: String,
    val value: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)
