package io.github.opencircuit.health

import java.time.Instant
import java.time.LocalDate

/**
 * Every record's `clientRecordId`: `oc:<type>:<key>`. The key comes only from what is stored —
 * a sample's start, a bucket's start, a night's own key text, a calendar day — never from the
 * time of the write, so writing the same stored fact again always names the same record and
 * Health Connect replaces it instead of adding a copy.
 *
 * Instants are written as epoch milliseconds and days as ISO dates (`2026-10-16`), digits that do
 * not depend on the phone's language. The type part repeats what Health Connect already scopes
 * by (an id is unique per app and per record type); it keeps ids readable in logs and tests.
 */
object RecordIds {
    /** A stored sample of one kind: its row id and its start. Several rows may share a start. */
    data class StoredSampleKey(val rowId: Long, val start: Instant)

    private const val MILLIS_PER_HOUR = 3_600_000L

    /** One heart-rate record per UTC clock hour: [hourStart] must be the start of a UTC hour. */
    fun heartRateHour(hourStart: Instant): String {
        val millis = hourStart.toEpochMilli()
        require(hourStart.nano % 1_000_000 == 0 && Math.floorMod(millis, MILLIS_PER_HOUR) == 0L) {
            "a heart-rate hour must start on a UTC hour, was $hourStart"
        }
        return "oc:hr:$millis"
    }

    /**
     * Ids for stored heart-rate-variability, oxygen-saturation or respiratory-rate samples:
     * `oc:<type>:<startEpochMilli>:<n>`, where n counts, from 0, the samples of [samples] that share
     * a start, in row-id order. The store keeps two samples of one kind at one instant, and each
     * gets its own record. Returns the id of each row id.
     */
    fun sampleIds(type: RecordType, samples: List<StoredSampleKey>): Map<Long, String> {
        val prefix = when (type) {
            RecordType.HEART_RATE_VARIABILITY_RMSSD -> "hrv"
            RecordType.OXYGEN_SATURATION -> "spo2"
            RecordType.RESPIRATORY_RATE -> "rr"
            else -> throw IllegalArgumentException("$type records are not keyed by stored sample")
        }
        require(samples.map { it.rowId }.toSet().size == samples.size) { "a row id appears twice" }
        val ids = LinkedHashMap<Long, String>()
        samples.groupBy { it.start }.forEach { (start, rows) ->
            rows.sortedBy { it.rowId }.forEachIndexed { n, row -> ids[row.rowId] = "oc:$prefix:${start.toEpochMilli()}:$n" }
        }
        return ids
    }

    fun restingHeartRate(day: LocalDate): String = "oc:rhr:$day"

    /** [night] is the stored night's own key text, so a re-keyed night keeps its record's id with it. */
    fun skinTemperature(night: String): String = "oc:skin:${nightKey(night)}"

    fun steps(bucketStart: Instant): String = "oc:steps:${bucketStart.toEpochMilli()}"

    fun distance(bucketStart: Instant): String = "oc:dist:${bucketStart.toEpochMilli()}"

    fun activeCalories(bucketStart: Instant): String = "oc:ae:${bucketStart.toEpochMilli()}"

    fun basalMetabolicRate(day: LocalDate): String = "oc:bmr:$day"

    /** [night] is the stored night's own key text. */
    fun sleepNight(night: String): String = "oc:sleep:${nightKey(night)}"

    /** [start] is the stored nap's start. */
    fun nap(start: Instant): String = "oc:nap:${start.toEpochMilli()}"

    fun menstruationFlow(day: LocalDate): String = "oc:flow:$day"

    /** [firstDay] is the logged period's first day. */
    fun menstruationPeriod(firstDay: LocalDate): String = "oc:period:$firstDay"

    private fun nightKey(night: String): String {
        require(night.isNotBlank()) { "a night key must not be blank" }
        return night
    }
}
