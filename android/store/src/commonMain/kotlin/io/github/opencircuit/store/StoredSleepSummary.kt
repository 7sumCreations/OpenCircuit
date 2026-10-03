package io.github.opencircuit.store

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.opencircuit.ringkit.SleepEdit
import java.time.Instant

// The nightly sleep summary and the nap tables: every column of upstream's live models, so the
// schema holds them from the first version. Port of upstream ios/OpenCircuit/Store/LocalStore.swift
// `StoredSleepSummary` (:86-230, 41 stored properties) and `StoredNap` (:695-746, 14) (@ b1c2fdd).
// Nothing writes these tables yet: the sleep and nap write flow (merge, manual edits, provenance)
// is a later port; the columns are here so that port needs no schema change.
//
// Column rules shared by every user-data table: a non-optional Swift `Date` is NOT NULL epoch
// milliseconds defaulting to `Date.distantPast` ([SleepEdit.DISTANT_PAST]); an optional one is
// nullable; `Bool` is 0 / 1; `[Int]` is a JSON array in text; `Data` is a blob. Every SQL default
// is upstream's property default. Upstream's `Int` is 64-bit; a minute count, score or heart rate
// fits an `Int`.

/** One night's summary, keyed by [night] (the night's start-of-day key). Columns only for now. */
@Entity(tableName = "stored_sleep_summary", indices = [Index(value = ["night"], unique = true)])
@Suppress("ArrayInDataClass") // never compared: the sleep write flow will map rows to values.
internal data class StoredSleepSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = DISTANT_PAST_MS) val night: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "asleep_min", defaultValue = "0") val asleepMin: Int = 0,
    @ColumnInfo(name = "deep_min", defaultValue = "0") val deepMin: Int = 0,
    @ColumnInfo(name = "light_min", defaultValue = "0") val lightMin: Int = 0,
    @ColumnInfo(name = "rem_min", defaultValue = "0") val remMin: Int = 0,
    @ColumnInfo(name = "awake_min", defaultValue = "0") val awakeMin: Int = 0,
    @ColumnInfo(defaultValue = "0") val efficiency: Double = 0.0,
    @ColumnInfo(name = "in_bed_start", defaultValue = DISTANT_PAST_MS) val inBedStart: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "in_bed_end", defaultValue = DISTANT_PAST_MS) val inBedEnd: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "sleep_onset", defaultValue = DISTANT_PAST_MS) val sleepOnset: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "sleep_wake", defaultValue = DISTANT_PAST_MS) val sleepWake: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "updated_at", defaultValue = DISTANT_PAST_MS) val updatedAt: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "skin_temp_c", defaultValue = "0") val skinTempC: Double = 0.0,
    @ColumnInfo(name = "sleep_score", defaultValue = "0") val sleepScore: Int = 0,
    @ColumnInfo(name = "stress_score", defaultValue = "0") val stressScore: Int = 0,
    @ColumnInfo(name = "feel_score", defaultValue = "0") val feelScore: Int = 0,
    @ColumnInfo(name = "hr_deep", defaultValue = "0") val hrDeep: Int = 0,
    @ColumnInfo(name = "hr_light", defaultValue = "0") val hrLight: Int = 0,
    @ColumnInfo(name = "hr_rem", defaultValue = "0") val hrRem: Int = 0,
    @ColumnInfo(name = "hr_awake", defaultValue = "0") val hrAwake: Int = 0,
    @ColumnInfo(name = "movement_levels", defaultValue = "'[]'") val movementLevels: List<Int> = emptyList(),
    @ColumnInfo(name = "hypnogram_data", defaultValue = "x''") val hypnogramData: ByteArray = ByteArray(0),
    @ColumnInfo(name = "osa_avg_spo2", defaultValue = "0") val osaAvgSpO2: Double = 0.0,
    @ColumnInfo(name = "osa_min_spo2", defaultValue = "0") val osaMinSpO2: Double = 0.0,
    @ColumnInfo(name = "osa_time_below_90_sec", defaultValue = "0") val osaTimeBelow90Sec: Double = 0.0,
    @ColumnInfo(name = "osa_odi", defaultValue = "0") val osaODI: Double = 0.0,
    @ColumnInfo(name = "osa_valid_windows", defaultValue = "0") val osaValidWindows: Int = 0,
    @ColumnInfo(name = "edited_in_bed_start", defaultValue = DISTANT_PAST_MS) val editedInBedStart: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "edited_in_bed_end", defaultValue = DISTANT_PAST_MS) val editedInBedEnd: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "is_manually_edited", defaultValue = "0") val isManuallyEdited: Boolean = false,
    @ColumnInfo(name = "widened_recorded_in_bed_start", defaultValue = DISTANT_PAST_MS)
    val widenedRecordedInBedStart: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "widened_recorded_in_bed_end", defaultValue = DISTANT_PAST_MS)
    val widenedRecordedInBedEnd: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "widened_recorded_onset", defaultValue = DISTANT_PAST_MS)
    val widenedRecordedOnset: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "widened_recorded_wake", defaultValue = DISTANT_PAST_MS)
    val widenedRecordedWake: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "recorded_hypnogram_data", defaultValue = "x''") val recordedHypnogramData: ByteArray = ByteArray(0),
    @ColumnInfo(name = "measured_asleep_seconds", defaultValue = "-1") val measuredAsleepSeconds: Double = -1.0,
    @ColumnInfo(name = "asserted_asleep_seconds", defaultValue = "-1") val assertedAsleepSeconds: Double = -1.0,
    @ColumnInfo(name = "coverage_fraction", defaultValue = "-1") val coverageFraction: Double = -1.0,
    @ColumnInfo(name = "longest_gap_seconds", defaultValue = "-1") val longestGapSeconds: Double = -1.0,
    @ColumnInfo(name = "measured_efficiency", defaultValue = "-1") val measuredEfficiency: Double = -1.0,
    /** `""` = unknown, `"measuredOnly"`, `"assertedTagged"` (upstream `SleepBasis`). */
    @ColumnInfo(name = "sleep_basis", defaultValue = "''") val sleepBasis: String = "",
)

/** One nap, keyed by [start]. Columns only for now. */
@Entity(tableName = "stored_nap", indices = [Index(value = ["start"], unique = true)])
@Suppress("ArrayInDataClass") // never compared: the nap write flow will map rows to values.
internal data class StoredNapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = DISTANT_PAST_MS) val start: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(defaultValue = DISTANT_PAST_MS) val end: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "asleep_min", defaultValue = "0") val asleepMin: Int = 0,
    @ColumnInfo(name = "is_long_nap", defaultValue = "0") val isLongNap: Boolean = false,
    @ColumnInfo(name = "health_written", defaultValue = "0") val healthWritten: Boolean = false,
    @ColumnInfo(name = "updated_at", defaultValue = DISTANT_PAST_MS) val updatedAt: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "is_manually_edited", defaultValue = "0") val isManuallyEdited: Boolean = false,
    @ColumnInfo(name = "is_manually_added", defaultValue = "0") val isManuallyAdded: Boolean = false,
    @ColumnInfo(name = "nap_segments_data") val napSegmentsData: ByteArray? = null,
    @ColumnInfo(name = "edited_start") val editedStart: Instant? = null,
    @ColumnInfo(name = "edited_end") val editedEnd: Instant? = null,
    @ColumnInfo(name = "recorded_nap_segments_data") val recordedNapSegmentsData: ByteArray? = null,
    @ColumnInfo(name = "health_written_start", defaultValue = DISTANT_PAST_MS) val healthWrittenStart: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "health_written_end", defaultValue = DISTANT_PAST_MS) val healthWrittenEnd: Instant = SleepEdit.DISTANT_PAST,
)

/**
 * [SleepEdit.DISTANT_PAST] in epoch milliseconds, as the SQL default of a non-optional date column
 * (an annotation needs a constant; `StoredUserTablesColumnsTest` pins it to the `Instant`).
 */
internal const val DISTANT_PAST_MS = "-62135769600000"
