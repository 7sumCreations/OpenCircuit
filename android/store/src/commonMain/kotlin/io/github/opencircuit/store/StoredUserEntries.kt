package io.github.opencircuit.store

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.opencircuit.ringkit.SleepEdit
import java.time.Instant

// What the user logs and the frozen daily headache-risk rows. Port of upstream
// ios/OpenCircuit/CycleStore.swift `StoredPeriodEntry` (:15-69) and
// ios/OpenCircuit/Store/HeadacheStore.swift `StoredHeadacheEntry` (:30-88) and `StoredHeadacheRisk`
// (:126-191) (@ b1c2fdd). Column rules as in StoredSleepSummary.kt; `[String]` is a JSON array in
// text ([ListColumnConverter]). Upstream's `updatedAt = Date()` / `computedAt = Date()` read the
// clock; here they default to `distantPast` and every write states its time.
//
// The Health columns (`health_written`, `hk_sample_uuids`, `imported_hk_uuid`) keep upstream's
// names: the Health Connect epic stores its record ids in them. Nothing here writes them except
// the save rules ported with the entries (a clinical change clears `health_written`; a move carries
// the displaced row's ids).

/** One logged period, keyed by [start]. */
@Entity(tableName = "stored_period_entry", indices = [Index(value = ["start"], unique = true)])
internal data class StoredPeriodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = DISTANT_PAST_MS) val start: Instant = SleepEdit.DISTANT_PAST,
    val end: Instant? = null,
    /** 1 = light, 2 = medium, 3 = heavy. */
    @ColumnInfo(name = "flow_level_raw", defaultValue = "2") val flowLevelRaw: Int = 2,
    @ColumnInfo(defaultValue = "'[]'") val symptoms: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "''") val notes: String = "",
    @ColumnInfo(name = "health_written", defaultValue = "0") val healthWritten: Boolean = false,
    @ColumnInfo(name = "hk_sample_uuids", defaultValue = "'[]'") val hkSampleUUIDs: List<String> = emptyList(),
    @ColumnInfo(name = "updated_at", defaultValue = DISTANT_PAST_MS) val updatedAt: Instant = SleepEdit.DISTANT_PAST,
)

/** One logged headache, keyed by [onset]. */
@Entity(tableName = "stored_headache_entry", indices = [Index(value = ["onset"], unique = true)])
internal data class StoredHeadacheEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = DISTANT_PAST_MS) val onset: Instant = SleepEdit.DISTANT_PAST,
    val end: Instant? = null,
    /** 0 = unspecified, 1 = not present, 2 = mild, 3 = moderate, 4 = severe (HealthKit's raw values). */
    @ColumnInfo(name = "severity_raw", defaultValue = "0") val severityRaw: Int = 0,
    @ColumnInfo(defaultValue = "'[]'") val symptoms: List<String> = emptyList(),
    @ColumnInfo(name = "custom_symptoms", defaultValue = "'[]'") val customSymptoms: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "'[]'") val factors: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "''") val notes: String = "",
    @ColumnInfo(name = "source_raw", defaultValue = "'user'") val sourceRaw: String = HeadacheSource.USER.rawValue,
    @ColumnInfo(name = "imported_hk_uuid") val importedHKUUID: String? = null,
    @ColumnInfo(name = "health_written", defaultValue = "0") val healthWritten: Boolean = false,
    @ColumnInfo(name = "hk_sample_uuids", defaultValue = "'[]'") val hkSampleUUIDs: List<String> = emptyList(),
    @ColumnInfo(name = "updated_at", defaultValue = DISTANT_PAST_MS) val updatedAt: Instant = SleepEdit.DISTANT_PAST,
)

/** One day's frozen headache-risk score, keyed by [day]. Written once, never rescored. */
@Entity(tableName = "stored_headache_risk", indices = [Index(value = ["day"], unique = true)])
internal data class StoredHeadacheRiskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = DISTANT_PAST_MS) val day: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "night_key", defaultValue = DISTANT_PAST_MS) val nightKey: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(defaultValue = "0") val index: Double = 0.0,
    @ColumnInfo(name = "band_raw", defaultValue = "0") val bandRaw: Int = 0,
    @ColumnInfo(name = "ring_feature_count", defaultValue = "0") val ringFeatureCount: Int = 0,
    @ColumnInfo(name = "coverage_fraction", defaultValue = "0") val coverageFraction: Double = 0.0,
    @ColumnInfo(name = "contributions_json", defaultValue = "''") val contributionsJSON: String = "",
    @ColumnInfo(name = "absent_json", defaultValue = "''") val absentJSON: String = "",
    @ColumnInfo(name = "computed_at", defaultValue = DISTANT_PAST_MS) val computedAt: Instant = SleepEdit.DISTANT_PAST,
    @ColumnInfo(name = "sleep_updated_at") val sleepUpdatedAt: Instant? = null,
    @ColumnInfo(name = "sleep_restaged", defaultValue = "0") val sleepRestaged: Boolean = false,
    @ColumnInfo(defaultValue = "0") val alerted: Boolean = false,
    @ColumnInfo(name = "post_unlock", defaultValue = "0") val postUnlock: Boolean = false,
    @ColumnInfo(name = "updated_at", defaultValue = DISTANT_PAST_MS) val updatedAt: Instant = SleepEdit.DISTANT_PAST,
)
