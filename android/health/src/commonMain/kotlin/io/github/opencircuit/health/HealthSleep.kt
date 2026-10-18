package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepSegment
import java.time.Instant

/**
 * A stored night as the sleep write needs it. [key] is the stored row's own key text (the id's key,
 * moved with the row when the night moves to another key) and [night] the row's key; [timeline] is
 * the night's current segments as stored; [recordedEnd] the in-bed end the ring recorded.
 */
data class StoredSleepNight(
    val key: String,
    val night: Instant,
    val edited: Boolean,
    val timeline: List<SleepSegment>,
    val recordedEnd: Instant,
)

/** A wearer's edit of the stored [night] (its row key) waiting for its Health Connect write: the edited times and segments. */
data class QueuedNightEdit(
    val night: Instant,
    val inBedStart: Instant,
    val sleepOnset: Instant,
    val sleepWake: Instant,
    val segments: List<SleepSegment>,
)

/**
 * A stored nap as the sleep write needs it: keyed by [start] (the start the ring detected, kept by
 * an edit), shown over [shownStart, shownEnd]; [segments] its staging, null when coarse.
 */
data class StoredNapToWrite(
    val start: Instant,
    val shownStart: Instant,
    val shownEnd: Instant,
    val segments: List<SleepSegment>?,
    val manuallyAdded: Boolean,
    val healthWritten: Boolean,
)

/** What was last written for an unedited night: the signature of its staging and its span. */
data class NightMirror(val signature: String, val spanStart: Instant, val spanEnd: Instant)

/**
 * One stored night's marks, read in a batch: [mirror] and [recordIds] together, each answering exactly
 * as the single-night member answers it — [mirror] null when nothing was written or it cannot be read,
 * [recordIds] empty when none and null when the stored list cannot be read.
 */
data class NightMarks(val mirror: NightMirror?, val recordIds: List<String>?)

/** The queue of edited nights is stored in a form this build cannot read; it was left as stored. */
class UnreadableEditQueueException(message: String) : Exception(message)

/**
 * What the sleep write reads from the app's store and marks in it. The app implements it over its
 * `SleepStore` and `LocalStore`; `:health` never reaches into `:store`. Every night here is a stored
 * row's key.
 */
interface HealthSleepStore {
    /** Every stored night, oldest first. */
    suspend fun nights(): List<StoredSleepNight>

    /** What was last written for the unedited [night]; null when nothing was, or it cannot be read. */
    suspend fun mirror(night: Instant): NightMirror?

    suspend fun setMirror(night: Instant, mirror: NightMirror)

    /** The Health Connect record ids written for [night]: empty when none, null when the stored list cannot be read. */
    suspend fun recordIds(night: Instant): List<String>?

    /** Keeps [ids] as the record ids written for [night]. */
    suspend fun setRecordIds(night: Instant, ids: List<String>)

    /**
     * [mirror] and [recordIds] for every stored night `from <= night < until`, in one read: each night
     * of the range has an entry, and it answers exactly what the single-night members answer that
     * night — a night nothing is kept for answers a null mirror and no ids. The settled sleep write
     * reads its whole backlog this way instead of two reads per stored night.
     */
    suspend fun nightMarks(from: Instant, until: Instant): Map<Instant, NightMarks>

    /** After a settled night's [segments] were accepted: the sleep mark moves to their end and an edit they cover is marked covered. */
    suspend fun markNightWritten(segments: List<SleepSegment>)

    /** After an edited [night]'s [segments] were accepted: its edit marks and the sleep mark (at least [recordedEnd]) move from what was written. */
    suspend fun markEditWritten(night: Instant, segments: List<SleepSegment>, recordedEnd: Instant)

    /** The queued edits, oldest night first. Throws [UnreadableEditQueueException] when the queue cannot be read. */
    suspend fun queuedEdits(): List<QueuedNightEdit>

    /** Removes [edit] from the queue only when the stored item of its night is still [edit]; true when removed. */
    suspend fun clearQueuedEdit(edit: QueuedNightEdit): Boolean

    /** The naps not written yet, earliest first. */
    suspend fun pendingNaps(): List<StoredNapToWrite>

    /**
     * Marks the nap [nap] was read from written, only if it still shows what [nap] shows (its window
     * and staging): a nap changed while its record was sent stays pending, so the change is written.
     */
    suspend fun markNapWritten(nap: StoredNapToWrite)

    /** The naps the ring alone detected (never edited or added) sharing time with `[from, to]`. */
    suspend fun autoNaps(from: Instant, to: Instant): List<StoredNapToWrite>

    /** Deletes the naps stored at [starts]. */
    suspend fun deleteNaps(starts: List<Instant>)

    companion object {
        /** No sleep: a store that holds no night, no edit and no nap. */
        val NONE: HealthSleepStore = object : HealthSleepStore {
            override suspend fun nights(): List<StoredSleepNight> = emptyList()
            override suspend fun mirror(night: Instant): NightMirror? = null
            override suspend fun setMirror(night: Instant, mirror: NightMirror) = Unit
            override suspend fun recordIds(night: Instant): List<String> = emptyList()
            override suspend fun setRecordIds(night: Instant, ids: List<String>) = Unit
            override suspend fun nightMarks(from: Instant, until: Instant): Map<Instant, NightMarks> = emptyMap()
            override suspend fun markNightWritten(segments: List<SleepSegment>) = Unit
            override suspend fun markEditWritten(night: Instant, segments: List<SleepSegment>, recordedEnd: Instant) = Unit
            override suspend fun queuedEdits(): List<QueuedNightEdit> = emptyList()
            override suspend fun clearQueuedEdit(edit: QueuedNightEdit): Boolean = false
            override suspend fun pendingNaps(): List<StoredNapToWrite> = emptyList()
            override suspend fun markNapWritten(nap: StoredNapToWrite) = Unit
            override suspend fun autoNaps(from: Instant, to: Instant): List<StoredNapToWrite> = emptyList()
            override suspend fun deleteNaps(starts: List<Instant>) = Unit
        }
    }
}
