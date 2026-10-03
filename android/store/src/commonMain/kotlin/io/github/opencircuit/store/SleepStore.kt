package io.github.opencircuit.store

import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepPersistOutcome
import io.github.opencircuit.ringkit.SleepProvenanceBreakdown
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.ringkit.SleepSummaryMerge
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// The store's sleep half: a staged night saved under its night key with upstream's merge rules,
// and read back. Port of upstream ios/OpenCircuit/Store/LocalStore.swift (@ b1c2fdd):
// `resolveSleepRow` (:1545-1552), `saveSleepSummary` (:1582-1802) — its insert, replace and
// kept-fuller branches — `applyExtras` (:1855-1910) and the sleep reads `latestSleepSummary`
// (:2042), `recentSleepSummaries` (:2051), `sleepSummaries(from:to:)` (:2056), `sleepSummary(night:)`
// (:2063), `hypnogram(night:)` (:2074) and `sleepSummaryOverlapping` (:2084).
//
// Not yet part of this file: the collision guard and the kept-manual-edit branch (:1639-1684), the
// clamp-window widening on a kept night (:1712-1753), moving stored nights onto their wake day before
// a save (:1589) and realigning a night resolved by its span (:1561-1573), and pruning the automatic
// naps a saved night covers (:1801).
//
// Differences, each deliberate (PORTING.md D-160 to D-163):
// - `now` and `zone` are parameters, one zone for every day boundary; every instant is cut to the
//   stored millisecond before it is compared. Upstream reads the wall clock and `Calendar.current`.
// - Each save is one transaction: a failed save leaves the stored night exactly as it was, where
//   SwiftData keeps the half-changed object for the next save of anything to commit.
// - A failing lookup fails the save; upstream's `try?` reads it as "no row" and goes on.
// - Stage minutes past `Int.MAX_VALUE` fail the save (upstream's `Int` is 64-bit; the columns here
//   are 32-bit, and a silent wrap would store a negative night).
// - A NaN or infinite skin temperature counts as not computed, so the stored one is kept (upstream
//   keeps it for NaN and stores +∞).
// - Two nights overlapping a span equally resolve to the earlier night; upstream keeps whichever its
//   unsorted fetch returned first.

/**
 * The sleep half of the on-device store, over the same [StoreDatabase] as [LocalStore]. Every write
 * is one transaction: it commits whole or not at all. Callers get [StoredNight] values, never rows.
 */
class SleepStore internal constructor(
    private val db: StoreDatabase,
    private val sleepDao: SleepDao,
) {
    constructor(db: StoreDatabase) : this(db, db.sleepDao())

    /**
     * Stores a staged night under the start of [night]'s day in [zone], or deliberately keeps the
     * stored one, and returns which happened.
     *
     * The stored row is found by in-bed overlap first, then by day: a night can produce two keys as
     * it grows (an evening bout finished before midnight is keyed to that day, the completed night to
     * the next), and the overlap keeps them one row. The stored night is replaced only when the new
     * staging is at least as complete ([SleepSummaryMerge.shouldReplace], asleep time in whole
     * minutes), or when both windows are the same coverage within one ring epoch — a
     * re-classification may then lower the minutes. A replacing save writes the minutes, the
     * windows (the onset and wake as given, the unknown defaults included), and [extras]: a zero
     * score or temperature keeps the stored value, a heart rate is taken for every stage present,
     * and the timeline is always the one the minutes came from, stored as the ring's own reading
     * too. It never writes the feel score, the apnea summary, the edited window or the widened
     * clamp window.
     *
     * Throws, writing nothing, when a lookup or the write fails, or when a stage total does not fit
     * an `Int` of minutes.
     */
    suspend fun saveSleepSummary(
        summary: SleepStaging.Summary,
        night: Instant,
        inBedStart: Instant,
        inBedEnd: Instant,
        sleepOnset: Instant = SleepEdit.DISTANT_PAST,
        sleepWake: Instant = SleepEdit.DISTANT_PAST,
        extras: SleepNightExtras = SleepNightExtras(),
        now: Instant,
        zone: ZoneId,
    ): SleepPersistOutcome {
        val minutes = StageMinutes.of(summary)
        val efficiency = summary.efficiency
        val start = inBedStart.toStoredMillis()
        val end = inBedEnd.toStoredMillis()
        val onset = sleepOnset.toStoredMillis()
        val wake = sleepWake.toStoredMillis()
        val at = now.toStoredMillis()
        val dayStart = startOfDay(night.toStoredMillis(), zone)
        val staged = StagedExtras.of(extras)
        return db.withWriteTransaction {
            val existing = resolveSleepRow(dayStart, start, end)
            if (existing == null) {
                sleepDao.insertSummary(
                    StoredSleepSummaryEntity(night = dayStart, efficiency = efficiency, updatedAt = at)
                        .withMinutes(minutes).withWindow(start, end, onset, wake).withExtras(staged),
                )
                return@withWriteTransaction SleepPersistOutcome.INSERTED
            }
            val storedSpan = span(existing.inBedStart, existing.inBedEnd)
            val newSpan = span(start, end)
            val sameCoverage = storedSpan > Duration.ZERO && newSpan > Duration.ZERO &&
                withinOneEpoch(existing.inBedStart, start) && withinOneEpoch(existing.inBedEnd, end)
            val replace = SleepSummaryMerge.shouldReplace(
                storedInBed = storedSpan,
                newInBed = newSpan,
                storedAsleep = Duration.ofMinutes(existing.asleepMin.toLong()),
                newAsleep = Duration.ofMinutes(minutes.asleep.toLong()),
                sameCoverage = sameCoverage,
            )
            if (!replace) return@withWriteTransaction SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT
            sleepDao.updateSummary(
                existing.copy(efficiency = efficiency, updatedAt = at).withMinutes(minutes).withWindow(start, end, onset, wake).withExtras(staged),
            )
            SleepPersistOutcome.UPDATED
        }
    }

    /** The night stored for the day of [night] in [zone], or null. */
    suspend fun sleepSummary(night: Instant, zone: ZoneId): StoredNight? = rowFor(night, zone)?.toStoredNight()

    /** The stored night with the latest key, or null when none is stored. */
    suspend fun latestSleepSummary(): StoredNight? = sleepDao.latestSummary()?.toStoredNight()

    /** At most [limit] stored nights, the latest key first. */
    suspend fun recentSleepSummaries(limit: Int = 40): List<StoredNight> = sleepDao.recentSummaries(limit).map { it.toStoredNight() }

    /** The stored nights keyed `from <= night < to`, oldest first. */
    suspend fun sleepSummaries(from: Instant, to: Instant): List<StoredNight> = sleepDao.summaries(from, to).map { it.toStoredNight() }

    /**
     * The timeline stored for the day of [night] in [zone]; no segments when no night is stored,
     * none was recorded, or the stored timeline cannot be read.
     */
    suspend fun hypnogram(night: Instant, zone: ZoneId): List<SleepSegment> =
        rowFor(night, zone)?.let { decodedTimeline(it.hypnogramData) } ?: emptyList()

    /**
     * The stored night whose recorded in-bed window shares the most time with `[start, end]`, or null
     * when none shares any (or `end` is not after `start`). Nights with no known recorded window are
     * never matched, and a wearer's edited window is not looked at. Of two nights sharing the same
     * time, the earlier one.
     */
    suspend fun sleepSummaryOverlapping(start: Instant, end: Instant): StoredNight? =
        overlappingRow(start.toStoredMillis(), end.toStoredMillis())?.toStoredNight()

    private suspend fun rowFor(night: Instant, zone: ZoneId): StoredSleepSummaryEntity? =
        sleepDao.summaryAt(startOfDay(night.toStoredMillis(), zone))

    /** The row this staging belongs to: by in-bed overlap first (identity), then by day (index). */
    private suspend fun resolveSleepRow(dayStart: Instant, start: Instant, end: Instant): StoredSleepSummaryEntity? {
        if (end > start) overlappingRow(start, end)?.let { return it }
        return sleepDao.summaryAt(dayStart)
    }

    private suspend fun overlappingRow(start: Instant, end: Instant): StoredSleepSummaryEntity? {
        if (end <= start) return null
        var best: StoredSleepSummaryEntity? = null
        var bestOverlap = Duration.ZERO
        // Oldest night first, so a tie keeps the earlier night.
        for (row in sleepDao.overlapCandidates(start, end)) {
            val overlap = Duration.between(maxOf(start, row.inBedStart), minOf(end, row.inBedEnd))
            if (overlap > bestOverlap) {
                best = row
                bestOverlap = overlap
            }
        }
        return best
    }

    /** The five stage totals in whole minutes, each required to fit an `Int` column. */
    private class StageMinutes(val asleep: Int, val deep: Int, val light: Int, val rem: Int, val awake: Int) {
        companion object {
            fun of(summary: SleepStaging.Summary): StageMinutes {
                val m = summary.minutes
                return StageMinutes(
                    asleep = Math.toIntExact(m.asleep), deep = Math.toIntExact(m.deep), light = Math.toIntExact(m.light),
                    rem = Math.toIntExact(m.rem), awake = Math.toIntExact(m.awake),
                )
            }
        }
    }

    /**
     * [SleepNightExtras] as this save stores it: the lists copied once, the timeline encoded and its
     * provenance split computed before the transaction opens.
     */
    private class StagedExtras(
        val skinTempC: Double,
        val skinTempWithheld: Boolean,
        val sleepScore: Int,
        val stressScore: Int,
        val hrByStage: Map<SleepStage, Int>,
        val movementLevels: List<Int>,
        val hypnogramData: ByteArray,
        val hasTimeline: Boolean,
        val breakdown: SleepProvenanceBreakdown,
    ) {
        companion object {
            fun of(extras: SleepNightExtras): StagedExtras {
                val hypnogram = extras.hypnogram.toList()
                return StagedExtras(
                    skinTempC = extras.skinTempC, skinTempWithheld = extras.skinTempWithheld, sleepScore = extras.sleepScore,
                    stressScore = extras.stressScore, hrByStage = extras.hrByStage.toMap(), movementLevels = extras.movementLevels.toList(),
                    hypnogramData = SleepHypnogramCodec.encode(hypnogram), hasTimeline = hypnogram.isNotEmpty(),
                    breakdown = SleepProvenanceBreakdown(hypnogram),
                )
            }
        }
    }

    private companion object {
        val ONE_EPOCH: Duration = Duration.ofSeconds(BulkRecord.EPOCH_SECONDS.toLong())

        /** A provenance figure that was not computed (the columns' default), distinct from a real 0. */
        const val NOT_COMPUTED = -1.0

        /** The window's length, or zero when the window is unknown or reversed. */
        fun span(start: Instant, end: Instant): Duration = if (end > start) Duration.between(start, end) else Duration.ZERO

        fun withinOneEpoch(a: Instant, b: Instant): Boolean = Duration.between(a, b).abs() <= ONE_EPOCH

        fun StoredSleepSummaryEntity.withMinutes(m: StageMinutes) =
            copy(asleepMin = m.asleep, deepMin = m.deep, lightMin = m.light, remMin = m.rem, awakeMin = m.awake)

        fun StoredSleepSummaryEntity.withWindow(start: Instant, end: Instant, onset: Instant, wake: Instant) =
            copy(inBedStart = start, inBedEnd = end, sleepOnset = onset, sleepWake = wake)

        /** Upstream `applyExtras` (:1855-1910), in its order. */
        fun StoredSleepSummaryEntity.withExtras(x: StagedExtras): StoredSleepSummaryEntity {
            // Zero means "not computed this pass"; a non-finite value is treated the same (D-163).
            val skin = when {
                x.skinTempC > 0 && x.skinTempC.isFinite() -> x.skinTempC
                x.skinTempWithheld && skinTempC > 0 -> 0.0
                else -> skinTempC
            }
            val b = x.breakdown
            return copy(
                skinTempC = skin,
                sleepScore = if (x.sleepScore > 0) x.sleepScore else sleepScore,
                stressScore = if (x.stressScore > 0) x.stressScore else stressScore,
                hrDeep = x.hrByStage[SleepStage.ASLEEP_DEEP] ?: hrDeep,
                hrLight = x.hrByStage[SleepStage.ASLEEP_CORE] ?: hrLight,
                hrRem = x.hrByStage[SleepStage.ASLEEP_REM] ?: hrRem,
                hrAwake = x.hrByStage[SleepStage.AWAKE] ?: hrAwake,
                movementLevels = x.movementLevels.ifEmpty { movementLevels },
                // Never keep-if-empty: the minutes just written came from this timeline.
                hypnogramData = x.hypnogramData,
                // The ring's own reading, kept where an edit cannot reach it.
                recordedHypnogramData = x.hypnogramData,
                measuredAsleepSeconds = b.measuredAsleep,
                assertedAsleepSeconds = b.assertedAsleep,
                coverageFraction = if (x.hasTimeline) b.coverageFraction else NOT_COMPUTED,
                longestGapSeconds = if (x.hasTimeline) b.longestUnmeasuredGap else NOT_COMPUTED,
                measuredEfficiency = if (x.hasTimeline) (b.efficiency ?: NOT_COMPUTED) else NOT_COMPUTED,
                sleepBasis = if (x.hasTimeline) SleepBasis.MEASURED_ONLY.rawValue else SleepBasis.UNKNOWN.rawValue,
            )
        }
    }
}
