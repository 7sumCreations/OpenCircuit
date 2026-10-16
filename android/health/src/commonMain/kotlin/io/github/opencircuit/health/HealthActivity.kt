package io.github.opencircuit.health

import io.github.opencircuit.ringkit.DistanceEstimate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One stored step delta: its row [id], the window `[start, end]` it was counted over and its [count]. */
data class StepRow(val id: Long, val start: Instant, val end: Instant, val count: Long)

/**
 * What the writer reads from the app's store and marks in it for steps, distance, active energy and
 * the daily records. The app implements it over its `LocalStore` (step rows) and `store_kv` (the
 * marks); `:health` never reaches into `:store`.
 */
interface HealthActivityStore {
    /** The step rows not yet written, every day, oldest first (ties in row order). */
    suspend fun pendingSteps(): List<StepRow>

    /** Every step row with `from <= start < until`, written or not, oldest first (ties in row order). */
    suspend fun steps(from: Instant, until: Instant): List<StepRow>

    /** Marks these step rows written (only rows Health Connect accepted, or dropped for good). */
    suspend fun markStepsWritten(ids: List<Long>)

    /** The step total of local [day]; 0 when none is stored. */
    suspend fun daySteps(day: LocalDate): Long

    /** The last local day written for [type] (a record written once per day), or null. */
    suspend fun dayMark(type: RecordType): LocalDate?

    /** Moves [type]'s last day written to [day]. */
    suspend fun setDayMark(type: RecordType, day: LocalDate)

    /** The kilocalories written per energy bucket start of local [day]. */
    suspend fun energyWritten(day: LocalDate): Map<Instant, Double>

    /** Replaces what is kept as written for [day]'s energy buckets. */
    suspend fun setEnergyWritten(day: LocalDate, written: Map<Instant, Double>)

    /** Forgets what was written for every day before [day]. */
    suspend fun forgetEnergyBefore(day: LocalDate)

    companion object {
        /** Holds nothing: a writer built without steps or energy. */
        val NONE: HealthActivityStore = object : HealthActivityStore {
            override suspend fun pendingSteps(): List<StepRow> = emptyList()
            override suspend fun steps(from: Instant, until: Instant): List<StepRow> = emptyList()
            override suspend fun markStepsWritten(ids: List<Long>) = Unit
            override suspend fun daySteps(day: LocalDate): Long = 0
            override suspend fun dayMark(type: RecordType): LocalDate? = null
            override suspend fun setDayMark(type: RecordType, day: LocalDate) = Unit
            override suspend fun energyWritten(day: LocalDate): Map<Instant, Double> = emptyMap()
            override suspend fun setEnergyWritten(day: LocalDate, written: Map<Instant, Double>) = Unit
            override suspend fun forgetEnergyBefore(day: LocalDate) = Unit
        }
    }
}

/** Steps and the distance estimated from them, as records (pure). */
object ActivityRecords {

    /** A steps record and the rows it holds. */
    class StepsItem(val record: Steps, val rowIds: List<Long>)

    /**
     * One steps record `oc:steps:<start>` per start among [pending]: the rows of [atStarts] (every
     * stored row at the pending starts, written ones included) that share it are summed, over to their
     * latest end — rows share a start only when the phone's clock was set back, and two records under
     * one id would replace each other. The record holds every row at its start; [StepsItem.rowIds]
     * names the pending ones.
     */
    fun stepItems(pending: List<StepRow>, atStarts: List<StepRow>, zone: ZoneId, device: RingDevice, version: Long): List<StepsItem> {
        val pendingStarts = pending.map { it.start }.toSet()
        val pendingIds = pending.map { it.id }.toSet()
        val all = (atStarts + pending).distinctBy { it.id }.filter { it.start in pendingStarts }
        return all.groupBy { it.start }.toSortedMap().map { (start, rows) ->
            val end = rows.maxOf { it.end }
            val record = Steps(
                start = start,
                startZoneOffset = zone.rules.getOffset(start),
                end = end,
                endZoneOffset = zone.rules.getOffset(end),
                count = rows.sumOf { it.count },
                meta = RecordMeta(RecordIds.steps(start), version, RecordingMethod.AUTOMATICALLY_RECORDED, device),
            )
            StepsItem(record, rows.map { it.id }.filter { it in pendingIds })
        }
    }

    /**
     * The distance over [steps]' window, `oc:dist:<start>`: the steps × RingConn's own 0.248 m per
     * step (`DistanceEstimate`, an estimate the official app makes too).
     */
    fun distanceOf(steps: Steps, version: Long): Distance = Distance(
        start = steps.start,
        startZoneOffset = steps.startZoneOffset,
        end = steps.end,
        endZoneOffset = steps.endZoneOffset,
        meters = DistanceEstimate.meters(steps.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
        meta = RecordMeta(RecordIds.distance(steps.start), version, RecordingMethod.AUTOMATICALLY_RECORDED, steps.meta.device),
    )

    /**
     * Whether distance may be written (upstream `distanceMayWrite`, HealthKitWriter.swift:1107-1109):
     * only when its steps were accepted and distance itself did not fail — distance has no mark of
     * its own and rides the step rows'.
     */
    fun distanceMayWrite(stepsFailed: Boolean, distanceFailed: Boolean): Boolean = !stepsFailed && !distanceFailed
}
