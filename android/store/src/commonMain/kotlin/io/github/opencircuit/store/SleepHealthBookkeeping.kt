package io.github.opencircuit.store

import io.github.opencircuit.ringkit.DateInterval
import io.github.opencircuit.ringkit.MeasuredCoverage
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.store.codec.MirroredNightCodec
import io.github.opencircuit.store.codec.RecordIdListCodec
import java.time.Instant

// The store's half of a night's Health Connect write. Port of upstream
// ios/OpenCircuit/Store/LocalStore.swift (@ b1c2fdd): `sleepEditHealthUUIDs` / `setSleepEditHealthUUIDs`
// (:1282-1290) over `SleepEditHealthSampleOverlay` (:486-520), `mirroredNight` / `setMirroredNight`
// (:1299-1308) over `MirroredNightOverlay` (:530-562), and the edit-extension trio
// `pendingSleepEditHealthWrites` (:2728-2789), `markSleepEditHealthWritten` (:2792-2830) and
// `markSleepEditHealthCovered` (:2834-2858) with their two leading watermark keys (:2454-2460).
//
// Differences, each deliberate (PORTING D-288):
// - The ids kept under `sleep.edit.hkuuids.<night>` are Health Connect clientRecordIds
//   (`oc:sleep:<night>`), not HealthKit sample UUIDs, and are kept for every night written, edited or
//   not: Health Connect replaces a record by its id, so what a later write must delete is only an id
//   the night no longer has (the night moved to another key). `appendSleepEditHealthUUIDs` is not
//   ported: nothing appends to a night that is one record.
// - Every key is the stored row's own key text (`NightOverlays.key`), which `NightRekey` moves with
//   the row; upstream recomputes the start of the day in the current zone.
// - A stored value this build cannot read is null to the caller (upstream: `?? []` / `try? … nil`).
// - The trio's night is the stored row's key, looked up exactly; upstream finds the row by the day of
//   any instant in it. A failing read fails the call (upstream's `try?` reads it as "no watermark").
// - Each mark is one transaction with the reads it depends on.

/** What was last written to Health Connect for an unedited night: its staging's signature and span. */
data class MirroredNight(val signature: String, val spanStart: Instant, val spanEnd: Instant)

/**
 * An edited night's extension Health Connect has not received yet ([night] is the stored row's key):
 * the time before the recorded bedtime and onset, and after what the sleep mark covers, as segments
 * tagged from the night's stored provenance labels.
 */
data class PendingSleepEditHealthWrite(val night: Instant, val segments: List<SleepSegment>)

/** The reads and writes behind [SleepStore]'s Health bookkeeping, inside the caller's transaction. */
internal class SleepHealthBookkeeping(private val sleepDao: SleepDao, private val kv: KvDao) {

    /** The ids kept for [night] (a stored row's key): empty when none, null when unreadable. */
    suspend fun recordIds(night: Instant): List<String>? {
        val stored = kv.get(NightOverlays.key(RECORD_IDS, night)) ?: return emptyList()
        return RecordIdListCodec.decode(stored.value).valueOrNull()
    }

    suspend fun setRecordIds(night: Instant, ids: List<String>, now: Instant) {
        val key = NightOverlays.key(RECORD_IDS, night)
        if (ids.isEmpty()) kv.delete(key) else kv.upsert(StoreKvEntity(key, RecordIdListCodec.encode(ids.distinct()), now))
    }

    /** What was last written for [night]; null when nothing was, or the stored value cannot be read. */
    suspend fun mirroredNight(night: Instant): MirroredNight? =
        kv.get(NightOverlays.key(MIRROR, night))?.let { MirroredNightCodec.decode(it.value).valueOrNull() }

    suspend fun setMirroredNight(night: Instant, record: MirroredNight, now: Instant) {
        val stored = MirroredNight(record.signature, record.spanStart.toStoredMillis(), record.spanEnd.toStoredMillis())
        kv.upsert(StoreKvEntity(NightOverlays.key(MIRROR, night), MirroredNightCodec.encode(stored), now))
    }

    /** Upstream `pendingSleepEditHealthWrites` (:2728-2789), over [nights] (every stored night, as values). */
    suspend fun pendingEditWrites(nights: List<StoredNight>): List<PendingSleepEditHealthWrite> {
        val sleepMark = sleepDao.cursorAt(SLEEP_MARK)?.last ?: return emptyList()
        return nights.mapNotNull { row ->
            if (!row.isManuallyEdited) return@mapNotNull null
            val recordedStart = row.inBedStart
            val recordedEnd = row.inBedEnd
            val recordedOnset = if (row.sleepOnset > SleepEdit.DISTANT_PAST) row.sleepOnset else recordedStart
            if (!(recordedEnd > recordedStart && sleepMark >= recordedEnd)) return@mapNotNull null
            val writtenStart = watermark(LEADING, row.night) ?: recordedStart
            val writtenOnset = watermark(LEADING_ASLEEP, row.night) ?: recordedOnset
            val writtenEnd = maxOf(recordedEnd, minOf(sleepMark, row.editedInBedEnd))

            // The coverage decision already stored on the hypnogram's labels, never a second one: null
            // when the labels carry no proven hole (fully covered, or written before provenance).
            val coverage = MeasuredCoverage.fromProvenanceLabels(row.hypnogram)
            fun fill(from: Instant, to: Instant, stage: SleepStage): List<SleepSegment> {
                if (to <= from) return emptyList()
                if (coverage == null) return listOf(SleepSegment(from, to, stage))
                return coverage.partition(DateInterval(from, to)).map {
                    SleepSegment(it.range.start, it.range.end, stage, SleepEdit.provenance(it.ground))
                }
            }

            val segments = ArrayList<SleepSegment>()
            if (row.editedInBedStart < writtenStart) segments += fill(row.editedInBedStart, writtenStart, SleepStage.IN_BED)
            val editedOnset = row.currentOnset
            if (editedOnset < writtenOnset) segments += fill(editedOnset, writtenOnset, SleepStage.ASLEEP_CORE)
            if (row.editedInBedEnd > writtenEnd) {
                segments += fill(writtenEnd, row.editedInBedEnd, SleepStage.IN_BED)
                segments += fill(writtenEnd, row.editedInBedEnd, SleepStage.ASLEEP_CORE)
            }
            if (segments.isEmpty()) null else PendingSleepEditHealthWrite(row.night, segments)
        }
    }

    /** Upstream `markSleepEditHealthWritten` (:2792-2830): [row] is the stored night [segments] were written for. */
    suspend fun markEditWritten(row: StoredNight, segments: List<SleepSegment>) {
        val last = segments.maxOfOrNull { it.end } ?: return
        val recordedStart = row.inBedStart
        val recordedOnset = if (row.sleepOnset > SleepEdit.DISTANT_PAST) row.sleepOnset else recordedStart
        segments.filter { it.stage == SleepStage.IN_BED }.minOfOrNull { it.start }?.let { first ->
            if (first < recordedStart) lowerWatermark(LEADING, row.night, first)
        }
        segments.filter { it.stage != SleepStage.IN_BED && it.stage != SleepStage.AWAKE }.minOfOrNull { it.start }?.let { first ->
            if (first < recordedOnset) lowerWatermark(LEADING_ASLEEP, row.night, first)
        }
        // A retry can carry the wake-side extension: the shared sleep mark moves too, never back.
        advanceSleepMark(last)
    }

    /** Upstream `markSleepEditHealthCovered` (:2834-2858), over [nights] (every stored night, as values). */
    suspend fun markEditCovered(nights: List<StoredNight>, segments: List<SleepSegment>) {
        val first = segments.minOfOrNull { it.start } ?: return
        val last = segments.maxOf { it.end }
        for (row in nights) {
            if (!row.isManuallyEdited) continue
            val recordedStart = row.inBedStart
            val recordedEnd = row.inBedEnd
            val recordedOnset = if (row.sleepOnset > SleepEdit.DISTANT_PAST) row.sleepOnset else recordedStart
            if (row.editedInBedStart < recordedStart && first <= row.editedInBedStart && last >= recordedEnd && watermark(LEADING, row.night) == null) {
                sleepDao.insertCursor(StoredCursorEntity(NightRekey.watermarkKey(LEADING, row.night), row.editedInBedStart.toStoredMillis()))
            }
            val editedOnset = row.currentOnset
            if (editedOnset < recordedOnset && first <= editedOnset && last >= recordedEnd && watermark(LEADING_ASLEEP, row.night) == null) {
                sleepDao.insertCursor(StoredCursorEntity(NightRekey.watermarkKey(LEADING_ASLEEP, row.night), editedOnset.toStoredMillis()))
            }
        }
    }

    private suspend fun watermark(prefix: String, night: Instant): Instant? = sleepDao.cursorAt(NightRekey.watermarkKey(prefix, night))?.last

    private suspend fun lowerWatermark(prefix: String, night: Instant, to: Instant) {
        val at = to.toStoredMillis()
        val key = NightRekey.watermarkKey(prefix, night)
        val stored = sleepDao.cursorAt(key)
        if (stored == null) sleepDao.insertCursor(StoredCursorEntity(key, at)) else if (at < stored.last) sleepDao.updateCursor(stored.copy(last = at))
    }

    private suspend fun advanceSleepMark(to: Instant) {
        val at = to.toStoredMillis()
        val stored = sleepDao.cursorAt(SLEEP_MARK)
        if (stored == null) sleepDao.insertCursor(StoredCursorEntity(SLEEP_MARK, at)) else if (at > stored.last) sleepDao.updateCursor(stored.copy(last = at))
    }

    companion object {
        /** The night's Health record ids (upstream's sample-UUID key, kept; NightRekey moves it). */
        const val RECORD_IDS = "sleep.edit.hkuuids"

        /** What was last written for an unedited night (NightRekey moves it). */
        const val MIRROR = "sleep.mirror.night"

        val LEADING: String = NightRekey.WATERMARK_PREFIXES[0]
        val LEADING_ASLEEP: String = NightRekey.WATERMARK_PREFIXES[1]

        /** The ingest-namespace sleep mark (LocalStore.forceSleepCursorAtLeast). */
        val SLEEP_MARK: String = MetricKind.SLEEP.rawValue
    }
}
