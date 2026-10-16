package io.github.opencircuit.health

/**
 * A generous estimate of how many bytes a record takes in a Health Connect call. The platform
 * refuses a call above 5 MB; the writer keeps each call well under that by this estimate, so the
 * estimate errs high: a fixed share per record, more per sample, delta or stage, and two bytes per
 * character of text.
 */
object RecordSize {
    const val PER_RECORD_BYTES = 512L
    const val PER_POINT_BYTES = 64L

    fun estimateBytes(record: HealthRecord): Long {
        val points = when (record) {
            is HeartRateSeries -> record.samples.size
            is SkinTemperature -> record.deltas.size
            is SleepSession -> record.stages.size
            else -> 0
        }
        val text = record.meta.clientRecordId.length + (record.meta.device?.model?.length ?: 0) +
            ((record as? SleepSession)?.let { (it.title?.length ?: 0) + (it.notes?.length ?: 0) } ?: 0)
        return PER_RECORD_BYTES + PER_POINT_BYTES * points + 2L * text
    }

    fun estimateBytes(records: List<HealthRecord>): Long = records.sumOf { estimateBytes(it) }
}
