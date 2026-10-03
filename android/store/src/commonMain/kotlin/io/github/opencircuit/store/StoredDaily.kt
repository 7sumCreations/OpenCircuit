package io.github.opencircuit.store

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant

// The per-day step rollup, the timestamped step deltas behind it, and daytime skin-temperature
// readings. Port of upstream ios/OpenCircuit/Store/LocalStore.swift `StoredDaily` (:647-664),
// `StoredStepSample` (:674-688) and `StoredDaytimeTemp` (:760-768) (@ b1c2fdd).
//
// Upstream's `Int` is 64-bit, so the step columns are `Long`. The time columns the 30-day prune
// deletes by (`start`, `time`) are indexed; upstream's SwiftData store had no such index.

/**
 * One day's step total (the ring's onboard step count), keyed by the start of the local day and
 * updated in place. [healthWrittenSteps] is kept for the schema and never written: the health
 * write is tracked per [StoredStepSampleEntity] row (as upstream).
 */
@Entity(
    tableName = "stored_daily",
    indices = [Index(value = ["day"], unique = true)],
)
internal data class StoredDailyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Instant,
    val steps: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "health_written_steps") val healthWrittenSteps: Long = 0,
)

/** One step delta and the window it was counted over. Append-only; many rows per day. */
@Entity(
    tableName = "stored_step_sample",
    indices = [Index(value = ["start"])],
)
internal data class StoredStepSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val start: Instant,
    val end: Instant,
    val delta: Long,
    @ColumnInfo(name = "health_written") val healthWritten: Boolean = false,
)

/** One daytime skin-temperature reading, kept apart from the nightly value. Append-only. */
@Entity(
    tableName = "stored_daytime_temp",
    indices = [Index(value = ["time"])],
)
internal data class StoredDaytimeTempEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Instant,
    val celsius: Double,
)
