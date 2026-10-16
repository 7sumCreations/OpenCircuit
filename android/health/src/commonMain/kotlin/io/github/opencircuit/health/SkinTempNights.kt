package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SkinTempBaseline
import java.time.Instant
import java.time.ZoneId

/**
 * A stored night as the skin-temperature record needs it: its [key] text (the store's own, so a
 * night that moves to another key moves its record's id with it), its key instant, the in-bed span
 * and the night's published mean skin temperature ([nightlyCelsius], 0 when none was published).
 */
data class NightWindow(
    val key: String,
    val night: Instant,
    val inBedStart: Instant,
    val inBedEnd: Instant,
    val nightlyCelsius: Double,
)

/**
 * Skin temperature is written as one `SkinTemperatureRecord` per night (`oc:skin:<night key>`),
 * measured at the FINGER: the night's readings inside its in-bed span as differences from the
 * rolling baseline of the PRIOR nights' published means ([SkinTempBaseline.baseline], at least
 * [SkinTempBaseline.MIN_BASELINE_NIGHTS] of them), from the first reading to one second after the
 * last (a difference must lie before the record's end). With no baseline yet there is no record:
 * a difference from nothing would mean nothing to a reader. A late reading rewrites its whole
 * night under the same id.
 */
object SkinTempNights {
    /** The nights among [nights] whose in-bed span (ends included) holds a [pending] reading that is not after [now]. */
    fun nightsToRebuild(pending: List<QuantitySample>, nights: List<NightWindow>, now: Instant): List<NightWindow> {
        val times = pending.filter { usable(it, now) }.map { it.start }
        return nights.filter { n -> times.any { !it.isBefore(n.inBedStart) && !it.isAfter(n.inBedEnd) } }.sortedBy { it.night }
    }

    /**
     * The record of [night] from the [stored] temperature readings (the night's own are picked:
     * inside its in-bed span, above zero, readable, not after [now]) and every stored night
     * ([nights]; the baseline reads the ones before [night] with a published mean). Null when the
     * night has no reading or no baseline.
     */
    fun item(
        night: NightWindow,
        stored: List<QuantitySample>,
        nights: List<NightWindow>,
        now: Instant,
        zone: ZoneId,
        device: RingDevice,
        version: Long,
    ): WriteItem? {
        val readings = stored
            .filter { usable(it, now) && !it.start.isBefore(night.inBedStart) && !it.start.isAfter(night.inBedEnd) }
            .sortedBy { it.start }
        if (readings.isEmpty()) return null
        val prior = nights
            .filter { it.night.isBefore(night.night) && it.nightlyCelsius > 0 && it.nightlyCelsius.isFinite() }
            .map { SkinTempBaseline.NightlyTemp(it.night, it.nightlyCelsius) }
        val baseline = SkinTempBaseline.baseline(prior) ?: return null
        val start = readings.first().start
        val end = readings.last().start.plusSeconds(1)
        val record = SkinTemperature(
            start = start,
            startZoneOffset = zone.rules.getOffset(start),
            end = end,
            endZoneOffset = zone.rules.getOffset(end),
            baselineCelsius = baseline,
            deltas = readings.map { SkinTemperatureDelta(it.start, it.value - baseline) },
            location = SkinTemperatureLocation.FINGER,
            meta = RecordMeta(RecordIds.skinTemperature(night.key), version, RecordingMethod.AUTOMATICALLY_RECORDED, device),
        )
        return WriteItem(record, readings)
    }

    private fun usable(s: QuantitySample, now: Instant): Boolean =
        s.kind == MetricKind.TEMPERATURE && s.value > 0 && s.value.isFinite() && !s.start.isAfter(now)
}
