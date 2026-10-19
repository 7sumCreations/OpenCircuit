package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

/** A stored sample and its row id in the app's store (rows of one kind may share a start). */
data class SampleRow(val rowId: Long, val sample: QuantitySample)

/**
 * A record and the stored samples it carries: once Health Connect accepts the record, they are
 * marked written, then [afterAccepted] runs (the marks a record that carries no sample moves).
 */
class WriteItem(val record: HealthRecord, val samples: List<QuantitySample>, val afterAccepted: (suspend () -> Unit)? = null)

/**
 * Heart-rate variability, blood oxygen and breathing rate: one instant record per stored sample,
 * at the sample's start, in the phone's zone at that instant.
 *
 * - Heart-rate variability is stored under the `hrvSDNN` kind but holds the ring's RMSSD in
 *   milliseconds (`BulkSleep.samples`), which is exactly Health Connect's RMSSD record: no tag.
 * - Blood oxygen is stored as a fraction (0.97); the record holds a percent (97), rounded to a
 *   hundredth of a percent so a fraction like 0.97 does not become 97.00000000000001.
 * - Breaths per minute as stored.
 *
 * Each record is named `oc:<type>:<startEpochMilli>:<n>` ([RecordIds.sampleIds]), where n counts
 * every stored row of the kind at that start, in row order — zeros included, so an id never moves
 * when what is written changes.
 */
object ScalarRecords {
    /** The record type each stored kind is written as. */
    val TYPES: Map<MetricKind, RecordType> = linkedMapOf(
        MetricKind.HRV_SDNN to RecordType.HEART_RATE_VARIABILITY_RMSSD,
        MetricKind.SPO2 to RecordType.OXYGEN_SATURATION,
        MetricKind.RESPIRATORY_RATE to RecordType.RESPIRATORY_RATE,
    )

    /**
     * The records of [pending] (rows of one kind, above zero), oldest first, leaving out any after
     * [now]: those stay pending until they are past. [rowsAtStarts] must hold every stored row of the
     * kind at each pending start (any value), so each id's ordinal counts them all.
     */
    fun items(
        kind: MetricKind,
        pending: List<SampleRow>,
        rowsAtStarts: List<SampleRow>,
        now: Instant,
        zone: ZoneId,
        device: RingDevice,
        version: Long,
    ): List<WriteItem> {
        val type = requireNotNull(TYPES[kind]) { "$kind is not written as a single-reading record" }
        val writable = pending.filter { it.sample.kind == kind && it.sample.value > 0 && !it.sample.start.isAfter(now) }
        if (writable.isEmpty()) return emptyList()
        val keys = (rowsAtStarts + writable).filter { it.sample.kind == kind }.distinctBy { it.rowId }
            .map { RecordIds.StoredSampleKey(it.rowId, it.sample.start) }
        val ids = RecordIds.sampleIds(type, keys)
        return writable.sortedWith(compareBy({ it.sample.start }, { it.rowId })).map { row ->
            val s = row.sample
            val meta = RecordMeta(ids.getValue(row.rowId), version, RecordingMethod.AUTOMATICALLY_RECORDED, device)
            val offset = zone.rules.getOffset(s.start)
            val record: HealthRecord = when (type) {
                RecordType.HEART_RATE_VARIABILITY_RMSSD -> HeartRateVariabilityRmssd(s.start, offset, s.value, meta)
                RecordType.OXYGEN_SATURATION -> OxygenSaturation(s.start, offset, percentOf(s.value), meta)
                RecordType.RESPIRATORY_RATE -> RespiratoryRate(s.start, offset, s.value, meta)
                else -> throw IllegalStateException("$type is not a single-reading record")
            }
            WriteItem(record, listOf(s))
        }
    }

    /** A stored fraction as a percent, to a hundredth of a percent. A value too large to round stays as it is ×100 (the range table refuses it). */
    fun percentOf(fraction: Double): Double =
        if (fraction.isFinite() && abs(fraction) < 1e12) (fraction * 10_000).roundToLong() / 100.0 else fraction * 100
}
