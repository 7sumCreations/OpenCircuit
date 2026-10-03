package io.github.opencircuit.store

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import java.time.Instant

/**
 * Reads and writes of `stored_sample` and `stored_cursor`. Every call runs on its own unless the
 * caller holds a transaction ([LocalStore] wraps each multi-step write in one).
 *
 * Where upstream sorts by `start` alone, ties are broken by insertion order (`id`) so a read is
 * deterministic; SwiftData leaves the order of equal sort keys unspecified.
 */
@Dao
internal interface SampleDao {
    @Insert
    suspend fun insertSamples(samples: List<StoredSampleEntity>)

    @Upsert
    suspend fun upsertCursors(cursors: List<StoredCursorEntity>)

    @Query("SELECT * FROM stored_cursor")
    suspend fun allCursors(): List<StoredCursorEntity>

    @Query("SELECT * FROM stored_sample ORDER BY start, id")
    suspend fun allSamples(): List<StoredSampleEntity>

    /** Samples of one kind with `from <= start < to`, oldest first (upstream `samplesDescriptor`). */
    @Query("SELECT * FROM stored_sample WHERE kind_raw = :kindRaw AND start >= :from AND start < :to ORDER BY start, id")
    suspend fun samples(kindRaw: String, from: Instant, to: Instant): List<StoredSampleEntity>

    /** The newest sample of one kind (upstream `latestSample(kind:)`). */
    @Query("SELECT * FROM stored_sample WHERE kind_raw = :kindRaw ORDER BY start DESC, id DESC LIMIT 1")
    suspend fun latestSample(kindRaw: String): StoredSampleEntity?

    /** The newest sample of one kind strictly before [before]. */
    @Query("SELECT * FROM stored_sample WHERE kind_raw = :kindRaw AND start < :before ORDER BY start DESC, id DESC LIMIT 1")
    suspend fun latestSampleBefore(kindRaw: String, before: Instant): StoredSampleEntity?

    /** The oldest sample of one kind strictly after [after] (upstream `earliestSample(kind:after:)`). */
    @Query("SELECT * FROM stored_sample WHERE kind_raw = :kindRaw AND start > :after ORDER BY start, id LIMIT 1")
    suspend fun earliestSampleAfter(kindRaw: String, after: Instant): StoredSampleEntity?

    /** The oldest sample of one kind (upstream `earliestSample(kind:)`). */
    @Query("SELECT * FROM stored_sample WHERE kind_raw = :kindRaw ORDER BY start, id LIMIT 1")
    suspend fun earliestSample(kindRaw: String): StoredSampleEntity?

    /** Samples of one kind with `start >= since` and a positive value, oldest first (upstream `recentSamples`). */
    @Query("SELECT * FROM stored_sample WHERE kind_raw = :kindRaw AND start >= :since AND value > 0 ORDER BY start, id")
    suspend fun recentSamples(kindRaw: String, since: Instant): List<StoredSampleEntity>

    /** The newest sample of one kind with `from <= start < to` and `start < before`. */
    @Query(
        "SELECT * FROM stored_sample WHERE kind_raw = :kindRaw AND start >= :from AND start < :to " +
            "AND start < :before ORDER BY start DESC, id DESC LIMIT 1",
    )
    suspend fun latestSampleWithin(kindRaw: String, from: Instant, to: Instant, before: Instant): StoredSampleEntity?
}
