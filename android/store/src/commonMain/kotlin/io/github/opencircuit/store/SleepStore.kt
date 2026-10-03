package io.github.opencircuit.store

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.OSASpO2
import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepHypnogramCodec
import io.github.opencircuit.ringkit.SleepNightKey
import io.github.opencircuit.ringkit.SleepPersistOutcome
import io.github.opencircuit.ringkit.SleepProvenanceBreakdown
import io.github.opencircuit.ringkit.SleepScore
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.ringkit.SleepSummaryMerge
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// The store's sleep half: a staged night saved under its night key with upstream's merge rules,
// and read back. Port of upstream ios/OpenCircuit/Store/LocalStore.swift (@ b1c2fdd):
// `resolveSleepRow` (:1545-1552), `saveSleepSummary` (:1582-1802) — its collision guard, kept
// edit, replace, insert and kept-fuller branches with the clamp widening — `applyExtras`
// (:1855-1910), the sleep reads `latestSleepSummary`
// (:2042), `recentSleepSummaries` (:2051), `sleepSummaries(from:to:)` (:2056), `sleepSummary(night:)`
// (:2063), `hypnogram(night:)` (:2074) and `sleepSummaryOverlapping` (:2084), the wearer's
// edit `applySleepEdit` in both forms (:2133-2257), `setFeelScore` (:2101-2109) and
// `applyOSASummary` (:2029-2039).
//
// Not yet part of this file: moving stored nights onto their wake day before a save (:1589) and
// realigning a night resolved by its span (:1561-1573), and pruning the automatic naps a saved
// night covers (:1801).
//
// Differences, each deliberate (PORTING.md D-160 to D-166):
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
// - An edit writes its row, the night's undo stack and its onset in one transaction; upstream writes
//   the two values to `UserDefaults` before a save that can still fail.
// - An apnea summary with a NaN or infinite figure is refused before anything is written (SQLite
//   would bind NaN as NULL, which the column refuses); upstream stores it. A failed apnea write is
//   thrown, where upstream swallows it and still reports the summary applied.

/**
 * The sleep half of the on-device store, over the same [StoreDatabase] as [LocalStore]. Every write
 * is one transaction: it commits whole or not at all. Callers get [StoredNight] values, never rows.
 */
class SleepStore internal constructor(
    private val db: StoreDatabase,
    private val sleepDao: SleepDao,
    kvDao: KvDao,
) {
    constructor(db: StoreDatabase) : this(db, db.sleepDao(), db.kvDao())

    internal constructor(db: StoreDatabase, sleepDao: SleepDao) : this(db, sleepDao, db.kvDao())

    private val overlays = NightOverlays(kvDao)

    /**
     * Stores a staged night under the start of [night]'s day in [zone], or deliberately keeps the
     * stored one, and returns which happened.
     *
     * The stored row is found by in-bed overlap first, then by day: a night can produce two keys as
     * it grows (an evening bout finished before midnight is keyed to that day, the completed night to
     * the next), and the overlap keeps them one row.
     *
     * A block found by day that does not overlap the stored night (one ring epoch of slack) and does
     * not end in the wake window, while the stored night does, is an unfinished evening bout:
     * refused, nothing written. A night the wearer edited is kept against every staging. A kept
     * night — edited, or fuller than the staging — still widens its clamp window outward to a
     * staging that reaches further (the widened-recorded columns and the update time only).
     *
     * Otherwise the stored night is replaced only when the new staging is at least as complete ([SleepSummaryMerge.shouldReplace], asleep time in whole
     * minutes), or when both windows are the same coverage within one ring epoch — a
     * re-classification may then lower the minutes. A replacing save writes the minutes, the
     * windows (the onset and wake as given, the unknown defaults included), and [extras]: a zero
     * score or temperature keeps the stored value, a heart rate is taken for every stage present,
     * and the timeline is always the one the minutes came from, stored as the ring's own reading
     * too. It never writes the feel score, the apnea summary, the edited window or the widened
     * clamp window. The wake window and every day boundary are read in [zone].
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
            if (isUnfinishedEveningBout(existing, start, end, zone)) return@withWriteTransaction SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION
            val incoming = SleepEdit.RecordedWindow(start, end, onset, wake)
            // A wearer's edit is kept against every later staging; only its clamp window widens.
            if (existing.isManuallyEdited) {
                widenClamp(existing, incoming, at)
                return@withWriteTransaction SleepPersistOutcome.KEPT_MANUAL_EDIT
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
            if (!replace) {
                // The minutes stay, but a staging that reaches further still widens the clamp window.
                widenClamp(existing, incoming, at)
                return@withWriteTransaction SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT
            }
            sleepDao.updateSummary(
                existing.copy(efficiency = efficiency, updatedAt = at).withMinutes(minutes).withWindow(start, end, onset, wake).withExtras(staged),
            )
            SleepPersistOutcome.UPDATED
        }
    }

    /**
     * Applies a wearer's edit to the night stored for the day of [night] in [zone]: the edited
     * bedtime and wake (the wake ends the in-bed window), the edited onset, and the minutes,
     * efficiency and score of [summary] — the score from its own seconds — with the provenance of
     * [hypnogram]. The night is then kept as edited by every later save. Returns false when no night
     * is stored for that day, and true, writing nothing, when the three times are the night's current
     * ones to the minute.
     *
     * [hypnogram] null leaves the stored timeline alone; an empty list clears it. On the first edit
     * of a night whose ring timeline has no copy of its own yet, the timeline is copied there first.
     * The edges being replaced are pushed onto the night's undo stack — unless the stored stack
     * cannot be read, which is then kept as it is. The recorded window and timeline, the widened clamp,
     * feel, the apnea summary, temperature, heart rates, stress and movement are left as stored.
     *
     * The row, the undo stack and the onset are one transaction: a failure leaves all three as they
     * were. Throws, writing nothing, when the lookup or the write fails, or when a stage total does not
     * fit an `Int` of minutes.
     */
    suspend fun applySleepEdit(
        night: Instant,
        times: SleepEdit.Times,
        summary: SleepStaging.Summary,
        hypnogram: List<SleepSegment>? = null,
        now: Instant,
        zone: ZoneId,
    ): Boolean {
        val minutes = StageMinutes.of(summary)
        val efficiency = summary.efficiency
        val score = SleepScore.composite(
            SleepScore.CompositeInput(
                totalAsleep = seconds(summary.totalAsleep), timeAwake = seconds(summary.awake), efficiency = efficiency,
                deep = seconds(summary.deep), light = seconds(summary.light), rem = seconds(summary.rem),
            ),
        ).score
        val timeline = hypnogram?.toList()
        val timelineData = timeline?.let(SleepHypnogramCodec::encode)
        val breakdown = timeline?.let(::SleepProvenanceBreakdown)
        val edit = SleepEdit.Times(times.inBedStart.toStoredMillis(), times.sleepOnset.toStoredMillis(), times.sleepWake.toStoredMillis())
        val at = now.toStoredMillis()
        val dayStart = startOfDay(night.toStoredMillis(), zone)
        return db.withWriteTransaction {
            val row = sleepDao.summaryAt(dayStart) ?: return@withWriteTransaction false
            val shown = value(row)
            if (SleepEdit.isSamePickerMinute(edit.inBedStart, shown.currentInBedStart, zone) &&
                SleepEdit.isSamePickerMinute(edit.sleepOnset, shown.currentOnset, zone) &&
                SleepEdit.isSamePickerMinute(edit.sleepWake, shown.currentWake, zone)
            ) {
                return@withWriteTransaction true
            }
            // Reversibility before the timeline is overwritten: only a still-unedited night's timeline
            // is the ring's reading.
            val ringsReading = row.recordedHypnogramData.isEmpty() && !row.isManuallyEdited && row.hypnogramData.isNotEmpty()
            val edited = row.copy(
                editedInBedStart = edit.inBedStart,
                editedInBedEnd = edit.inBedEnd,
                isManuallyEdited = true,
                efficiency = efficiency,
                hypnogramData = timelineData ?: row.hypnogramData,
                recordedHypnogramData = if (ringsReading) row.hypnogramData else row.recordedHypnogramData,
                sleepScore = score,
                updatedAt = at,
            ).withMinutes(minutes)
            sleepDao.updateSummary(if (breakdown != null) edited.withEditProvenance(breakdown) else edited)
            overlays.pushPriorTimes(row.night, SleepEdit.Times(shown.currentInBedStart, shown.currentOnset, shown.currentWake), at)
            overlays.saveOnset(row.night, edit.sleepOnset, at)
            true
        }
    }

    /**
     * The two-edge form of [applySleepEdit], kept from upstream: [editedWindow]'s start is the edited
     * bedtime and [sleepWake] the edited end — the window's own end is not used.
     */
    suspend fun applySleepEdit(
        night: Instant,
        editedWindow: SleepEdit.Window,
        summary: SleepStaging.Summary,
        sleepOnset: Instant,
        sleepWake: Instant,
        hypnogram: List<SleepSegment>? = null,
        now: Instant,
        zone: ZoneId,
    ): Boolean = applySleepEdit(night, SleepEdit.Times(editedWindow.inBedStart, sleepOnset, sleepWake), summary, hypnogram, now, zone)

    /**
     * Stores the wearer's own rating of the night stored for the day of [night] in [zone], clamped to
     * 0…9. Nothing is written when no night is stored for that day. Throws, writing nothing, when the
     * lookup or the write fails.
     */
    suspend fun setFeelScore(score: Int, night: Instant, now: Instant, zone: ZoneId) {
        val at = now.toStoredMillis()
        val dayStart = startOfDay(night.toStoredMillis(), zone)
        db.withWriteTransaction {
            val row = sleepDao.summaryAt(dayStart) ?: return@withWriteTransaction
            sleepDao.updateSummary(row.copy(feelScore = score.coerceIn(0, 9), updatedAt = at))
        }
    }

    /**
     * Attaches a night's apnea summary to the stored night with the latest key — whatever night the
     * burst was recorded in, as upstream. Returns false, writing nothing, when [osa] has no valid
     * window or no night is stored; true once written. Throws, writing nothing, when the lookup or
     * the write fails, and refuses with [IllegalArgumentException] a summary whose saturations, time
     * below 90 % or event rate is NaN or infinite.
     */
    suspend fun applyOSASummary(osa: OSASpO2.NightSummary, now: Instant): Boolean {
        if (osa.validWindows <= 0) return false
        require(osa.averageSpO2.isFinite() && osa.minSpO2.isFinite() && osa.timeBelow90Seconds.isFinite() && osa.odi.isFinite()) {
            "an apnea summary figure is not a number: $osa"
        }
        val at = now.toStoredMillis()
        return db.withWriteTransaction {
            val row = sleepDao.latestSummary() ?: return@withWriteTransaction false
            sleepDao.updateSummary(
                row.copy(
                    osaAvgSpO2 = osa.averageSpO2, osaMinSpO2 = osa.minSpO2, osaTimeBelow90Sec = osa.timeBelow90Seconds,
                    osaODI = osa.odi, osaValidWindows = osa.validWindows, updatedAt = at,
                ),
            )
            true
        }
    }

    // Every read of a night reads the onset saved with its edit in the same read transaction.

    /** The night stored for the day of [night] in [zone], or null. */
    suspend fun sleepSummary(night: Instant, zone: ZoneId): StoredNight? = db.withReadTransaction { rowFor(night, zone)?.let { value(it) } }

    /** The stored night with the latest key, or null when none is stored. */
    suspend fun latestSleepSummary(): StoredNight? = db.withReadTransaction { sleepDao.latestSummary()?.let { value(it) } }

    /** At most [limit] stored nights, the latest key first. */
    suspend fun recentSleepSummaries(limit: Int = 40): List<StoredNight> =
        db.withReadTransaction { sleepDao.recentSummaries(limit).map { value(it) } }

    /** The stored nights keyed `from <= night < to`, oldest first. */
    suspend fun sleepSummaries(from: Instant, to: Instant): List<StoredNight> =
        db.withReadTransaction { sleepDao.summaries(from, to).map { value(it) } }

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
        db.withReadTransaction { overlappingRow(start.toStoredMillis(), end.toStoredMillis())?.let { value(it) } }

    /** [row] as a value, with the onset saved with its edit; an unedited row's is not read. */
    private suspend fun value(row: StoredSleepSummaryEntity): StoredNight =
        row.toStoredNight(editedOnset = if (row.isManuallyEdited) overlays.onset(row.night) else null)

    private suspend fun rowFor(night: Instant, zone: ZoneId): StoredSleepSummaryEntity? =
        sleepDao.summaryAt(startOfDay(night.toStoredMillis(), zone))

    /**
     * Upstream's night-key collision guard (:1639-1651): a block that neither overlaps [existing]
     * (with one ring epoch of slack) nor ends in the wake window, against a stored night that does
     * end in it, is an evening drain's unfinished bout keyed onto the night that ended this morning.
     */
    private fun isUnfinishedEveningBout(existing: StoredSleepSummaryEntity, start: Instant, end: Instant, zone: ZoneId): Boolean {
        val bothWindowsKnown = existing.inBedEnd > existing.inBedStart && end > start
        if (!bothWindowsKnown) return false
        val overlaps = minOf(existing.inBedEnd, end).plus(ONE_EPOCH) >= maxOf(existing.inBedStart, start)
        return !overlaps && !SleepNightKey.endsInWakeWindow(end, zone) && SleepNightKey.endsInWakeWindow(existing.inBedEnd, zone)
    }

    /**
     * Widens [existing]'s clamp window outward by [incoming] in the widened-recorded columns, when it
     * reaches further (`SleepEdit.widenRecorded`); writes nothing otherwise. The recorded window the
     * save first stored is never moved.
     */
    private suspend fun widenClamp(existing: StoredSleepSummaryEntity, incoming: SleepEdit.RecordedWindow, at: Instant) {
        val clamp = clampWindowOf(existing.recordedWindow(), existing.widenedWindow())
        val w = SleepEdit.widenRecorded(stored = clamp, incoming = incoming) ?: return
        sleepDao.updateSummary(
            existing.copy(
                widenedRecordedInBedStart = w.inBedStart, widenedRecordedInBedEnd = w.inBedEnd,
                widenedRecordedOnset = w.sleepOnset, widenedRecordedWake = w.sleepWake, updatedAt = at,
            ),
        )
    }

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

        fun StoredSleepSummaryEntity.recordedWindow() = SleepEdit.RecordedWindow(inBedStart, inBedEnd, sleepOnset, sleepWake)

        fun StoredSleepSummaryEntity.widenedWindow() =
            SleepEdit.RecordedWindow(widenedRecordedInBedStart, widenedRecordedInBedEnd, widenedRecordedOnset, widenedRecordedWake)

        /** A duration in seconds, as Swift's `TimeInterval`. */
        fun seconds(d: Duration): Double = d.seconds + d.nano / 1e9

        /** Upstream `applySleepEdit`'s provenance columns (:2190-2197), from the edit's own timeline. */
        fun StoredSleepSummaryEntity.withEditProvenance(b: SleepProvenanceBreakdown) = copy(
            measuredAsleepSeconds = b.measuredAsleep,
            assertedAsleepSeconds = b.assertedAsleep,
            coverageFraction = b.coverageFraction,
            longestGapSeconds = b.longestUnmeasuredGap,
            measuredEfficiency = b.efficiency ?: NOT_COMPUTED,
            sleepBasis = (if (b.hasAssertedTime) SleepBasis.ASSERTED_TAGGED else SleepBasis.MEASURED_ONLY).rawValue,
        )

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
