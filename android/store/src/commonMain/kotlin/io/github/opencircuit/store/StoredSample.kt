package io.github.opencircuit.store

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import java.time.Instant

// The raw-sample table and the per-metric sync cursor. Port of upstream
// ios/OpenCircuit/Store/LocalStore.swift `StoredSample` (:10-62) and `StoredCursor` (:64-72)
// (@ b1c2fdd). The cursor's rules live in :ringkit's SyncCursor; these rows are its stored form.

/**
 * One ring sample. A cumulative counter (steps, active energy) is stored as its per-epoch delta in
 * [value], with the counter's raw reading in [rawValue] and the running day total in [dailyTotal].
 *
 * `(kind_raw, start)` is indexed but NOT unique, as upstream: two samples of one kind at one
 * instant are both kept. The sync cursor is what stops a re-delivered page from being stored
 * twice, and a unique index would silently drop the second of two same-start samples in one batch.
 */
@Entity(
    tableName = "stored_sample",
    indices = [Index(value = ["kind_raw", "start"])],
)
internal data class StoredSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "kind_raw") val kindRaw: String,
    val start: Instant,
    val end: Instant,
    val value: Double,
    @ColumnInfo(name = "raw_value") val rawValue: Double? = null,
    @ColumnInfo(name = "is_delta") val isDelta: Boolean = false,
    @ColumnInfo(name = "daily_total") val dailyTotal: Double? = null,
) {
    /** The domain sample, or null when [kindRaw] names no known metric (upstream `sample`). */
    fun toSample(): QuantitySample? =
        metricKindOf(kindRaw)?.let { QuantitySample(kind = it, start = start, end = end, value = value) }

    companion object {
        fun of(
            sample: QuantitySample,
            rawValue: Double? = null,
            isDelta: Boolean = false,
            dailyTotal: Double? = null,
        ) = StoredSampleEntity(
            kindRaw = sample.kind.rawValue,
            start = sample.start,
            end = sample.end,
            value = sample.value,
            rawValue = rawValue,
            isDelta = isDelta,
            dailyTotal = dailyTotal,
        )
    }
}

/**
 * The newest stored sample time of one metric. Keys are [MetricKind.rawValue]; rows whose key
 * starts with `hk:` or `export:` are other watermarks sharing the table and never feed the
 * ingest cursor.
 */
@Entity(tableName = "stored_cursor")
internal data class StoredCursorEntity(
    @PrimaryKey @ColumnInfo(name = "kind_raw") val kindRaw: String,
    val last: Instant,
)

/** Exact raw-value lookup, as Swift's `MetricKind(rawValue:)`. */
internal fun metricKindOf(raw: String): MetricKind? = MetricKind.entries.firstOrNull { it.rawValue == raw }
