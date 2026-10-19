package io.github.opencircuit.health

import io.github.opencircuit.ringkit.CyclePredictor
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One logged period as the writer sees it: [flowLevelRaw] 1 light, 2 medium, 3 heavy; [end] null
 * while it is open; [healthWritten] false when it was never written or was edited since; [writtenIds]
 * the clientRecordIds named for it (its flow days and its period record(s)).
 */
data class PeriodToWrite(
    val start: Instant,
    val end: Instant?,
    val flowLevelRaw: Int,
    val healthWritten: Boolean,
    val writtenIds: List<String>,
)

/**
 * What the writer reads and records for the logged periods. The app implements it over its
 * `LocalStore` (`periodEntriesNeedingHealthMirror`, `recordPeriodEntryHealthIds`); `:health` never
 * reaches into `:store`.
 */
interface HealthPeriodStore {
    /** The periods to look at: never written or edited since, still open, or ending on a day after [today]'s in [zone]. Oldest first. */
    suspend fun periodsNeedingMirror(today: Instant, zone: ZoneId): List<PeriodToWrite>

    /**
     * Names [ids] as the records of the period written from [entry], written up to date when
     * [finalized] and the period still has [entry]'s end and flow (one the wearer edited meanwhile
     * stays to be written). No period at its start: nothing.
     */
    suspend fun recordWrittenIds(entry: PeriodToWrite, ids: List<String>, finalized: Boolean)

    companion object {
        /** Holds nothing: a writer built without the period log. */
        val NONE: HealthPeriodStore = object : HealthPeriodStore {
            override suspend fun periodsNeedingMirror(today: Instant, zone: ZoneId): List<PeriodToWrite> = emptyList()
            override suspend fun recordWrittenIds(entry: PeriodToWrite, ids: List<String>, finalized: Boolean) = Unit
        }
    }
}

/**
 * A logged period as Health Connect records (pure). Upstream writes one flow sample per covered day
 * (HKW:803-843); Health Connect has a flow record per day AND a period record over the days, so both
 * are written. The covered days are `CyclePredictor`'s (an explicit end through today; an open
 * period through today or its 8th day, never fewer days than already written). Every record is a
 * manual entry with no device: the wearer typed it in.
 */
object PeriodRecords {
    private const val FLOW_ID_PREFIX = "oc:flow:"

    /** The flow records and the period record(s) of one period. */
    data class Built(val flows: List<MenstruationFlow>, val periods: List<MenstruationPeriod>) {
        /** Every id, flows first. */
        val ids: List<String> get() = flows.map { it.meta.clientRecordId } + periods.map { it.meta.clientRecordId }
    }

    /** Upstream's mapping (HKW:811-815): 1 light, 3 heavy, anything else medium. */
    fun flowLevel(raw: Int): MenstruationFlowLevel = when (raw) {
        1 -> MenstruationFlowLevel.LIGHT
        3 -> MenstruationFlowLevel.HEAVY
        else -> MenstruationFlowLevel.MEDIUM
    }

    /** Whether [id] names a flow day (the other ids a period names are its period records). */
    fun isFlowId(id: String): Boolean = id.startsWith(FLOW_ID_PREFIX)

    /**
     * The days already written: one flow id per covered day. The period record ids are not days,
     * so counting every id would never match and an open period would be rewritten on every flush.
     */
    fun coveredDays(entry: PeriodToWrite): Int = entry.writtenIds.count(::isFlowId)

    /** How many days [entry] covers at [now] in [zone]; 0 for a start after today. */
    fun daysToCover(entry: PeriodToWrite, now: Instant, zone: ZoneId): Long =
        CyclePredictor.periodMirrorDayCount(entry.start, entry.end, now, coveredDays(entry), zone)

    /**
     * Whether a written period's records already say what a rewrite at [now] would (upstream
     * `periodMirrorIsUpToDate`, read off the flow ids): only a new day can change an unedited one.
     */
    fun isUpToDate(entry: PeriodToWrite, now: Instant, zone: ZoneId): Boolean =
        entry.healthWritten && CyclePredictor.periodMirrorIsUpToDate(coveredDays(entry), entry.start, entry.end, now, zone)

    /**
     * The records of [entry] at [now] in [zone], with [version]: a flow record at the start of each
     * covered day (`oc:flow:<day>`), and the covered days as period records of at most
     * [HealthRanges.PERIOD_MAX_DAYS] each (`oc:period:<first day of the piece>`): the platform refuses
     * a longer period, and an explicit logged end is never shortened. A piece that would last longer
     * than that many 24-hour days (a clock change adds an hour) is one day shorter. Nothing for a
     * period that covers no day yet.
     */
    fun build(entry: PeriodToWrite, now: Instant, zone: ZoneId, version: Long): Built {
        val count = daysToCover(entry, now, zone)
        if (count <= 0) return Built(emptyList(), emptyList())
        val firstDay = entry.start.atZone(zone).toLocalDate()
        val afterLast = firstDay.plusDays(count)
        val level = flowLevel(entry.flowLevelRaw)
        val flows = (0 until count).map { offset ->
            val day = firstDay.plusDays(offset)
            val time = startOf(day, zone)
            MenstruationFlow(time, zone.rules.getOffset(time), level, manual(RecordIds.menstruationFlow(day), version))
        }
        val periods = mutableListOf<MenstruationPeriod>()
        var pieceFirst = firstDay
        while (pieceFirst.isBefore(afterLast)) {
            var pieceEnd = minOf(pieceFirst.plusDays(HealthRanges.PERIOD_MAX_DAYS), afterLast)
            if (Duration.between(startOf(pieceFirst, zone), startOf(pieceEnd, zone)) > MAX_PERIOD) pieceEnd = pieceEnd.minusDays(1)
            val start = startOf(pieceFirst, zone)
            val end = startOf(pieceEnd, zone)
            periods += MenstruationPeriod(start, zone.rules.getOffset(start), end, zone.rules.getOffset(end), manual(RecordIds.menstruationPeriod(pieceFirst), version))
            pieceFirst = pieceEnd
        }
        return Built(flows, periods)
    }

    private val MAX_PERIOD: Duration = Duration.ofDays(HealthRanges.PERIOD_MAX_DAYS)

    private fun startOf(day: LocalDate, zone: ZoneId): Instant = day.atStartOfDay(zone).toInstant()

    private fun manual(id: String, version: Long) = RecordMeta(id, version, RecordingMethod.MANUAL_ENTRY, null)
}
