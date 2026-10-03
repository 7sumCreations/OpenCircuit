package io.github.opencircuit.store

import androidx.room3.ColumnTypeConverter
import java.time.Instant

/**
 * Every instant column is whole epoch milliseconds (`INTEGER`). Writing truncates: the
 * sub-millisecond part is dropped, toward the past (before 1970 too), never rounded. Ring times
 * are whole seconds, so nothing the ring sends loses precision; upstream keeps a `Double` of
 * seconds and never had to choose.
 */
internal class InstantColumnConverter {
    @ColumnTypeConverter
    fun toEpochMillis(instant: Instant): Long = instant.toEpochMilli()

    @ColumnTypeConverter
    fun fromEpochMillis(epochMillis: Long): Instant = Instant.ofEpochMilli(epochMillis)
}
