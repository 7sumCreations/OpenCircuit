package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepSegment
import java.time.Instant

/**
 * The store's sleep in memory, as the writer sees it (the app keeps it in its `SleepStore`). Tests
 * set its nights, queued edits and naps directly, and read back what the writer marked.
 */
class InMemoryHealthSleep : HealthSleepStore {
    private val lock = Any()
    private val nightList = mutableListOf<StoredSleepNight>()
    private val mirrors = mutableMapOf<Instant, NightMirror>()
    private val ids = mutableMapOf<Instant, List<String>>()
    private val unreadableIds = mutableSetOf<Instant>()
    private val queue = mutableListOf<QueuedNightEdit>()
    private val napList = mutableListOf<Nap>()

    /** One stored nap; [manuallyEdited] keeps it out of [autoNaps]. */
    data class Nap(val nap: StoredNapToWrite, val manuallyEdited: Boolean = false)

    /** When set, reading the queue fails as an unreadable stored queue does. */
    @Volatile var queueUnreadable: Boolean = false

    /** The sleep mark (the store's `sleep` cursor). */
    @Volatile var sleepMark: Instant? = null
        private set

    /** Every `markNightWritten` call's segments, in order. */
    val nightsMarked = mutableListOf<List<SleepSegment>>()

    /** Every `markEditWritten` call: the night and the segments. */
    val editsMarked = mutableListOf<Pair<Instant, List<SleepSegment>>>()

    fun putNight(night: StoredSleepNight) = synchronized(lock) {
        nightList.removeAll { it.night == night.night }
        nightList += night
        nightList.sortBy { it.night }
    }

    /** Moves the night keyed [from] to [to] with its mirror and ids, as the store's re-key does. */
    fun rekey(from: Instant, to: Instant, newKey: String) = synchronized(lock) {
        val n = nightList.single { it.night == from }
        nightList.remove(n)
        nightList += n.copy(night = to, key = newKey)
        nightList.sortBy { it.night }
        mirrors.remove(from)?.let { mirrors[to] = it }
        ids.remove(from)?.let { ids[to] = it }
    }

    fun queue(edit: QueuedNightEdit) = synchronized(lock) {
        queue.removeAll { it.night == edit.night }
        queue += edit
    }

    fun queued(): List<QueuedNightEdit> = synchronized(lock) { queue.toList() }

    fun putNap(nap: StoredNapToWrite, manuallyEdited: Boolean = false) = synchronized(lock) {
        napList.removeAll { it.nap.start == nap.start }
        napList += Nap(nap, manuallyEdited)
        napList.sortBy { it.nap.start }
    }

    fun naps(): List<StoredNapToWrite> = synchronized(lock) { napList.map { it.nap } }

    fun idsOf(night: Instant): List<String>? = synchronized(lock) { if (night in unreadableIds) null else ids[night].orEmpty() }

    fun mirrorOf(night: Instant): NightMirror? = synchronized(lock) { mirrors[night] }

    fun makeIdsUnreadable(night: Instant) = synchronized(lock) { unreadableIds += night }

    override suspend fun nights(): List<StoredSleepNight> = synchronized(lock) { nightList.toList() }

    override suspend fun mirror(night: Instant): NightMirror? = synchronized(lock) { mirrors[night] }

    override suspend fun setMirror(night: Instant, mirror: NightMirror) = synchronized(lock) { mirrors[night] = mirror }

    override suspend fun recordIds(night: Instant): List<String>? = idsOf(night)

    override suspend fun setRecordIds(night: Instant, ids: List<String>) = synchronized(lock) {
        unreadableIds -= night
        if (ids.isEmpty()) this.ids.remove(night) else this.ids[night] = ids.distinct()
        Unit
    }

    /** As the store: every stored night of the range, each answered as [mirror] and [recordIds] answer it. */
    override suspend fun nightMarks(from: Instant, until: Instant): Map<Instant, NightMarks> = synchronized(lock) {
        nightList.filter { !it.night.isBefore(from) && it.night.isBefore(until) }
            .associate { it.night to NightMarks(mirrors[it.night], idsOf(it.night)) }
    }

    override suspend fun markNightWritten(segments: List<SleepSegment>) = synchronized(lock) {
        nightsMarked += segments
        segments.maxOfOrNull { it.end }?.let(::advance)
        Unit
    }

    override suspend fun markEditWritten(night: Instant, segments: List<SleepSegment>, recordedEnd: Instant) = synchronized(lock) {
        editsMarked += night to segments
        advance(maxOf(recordedEnd, segments.maxOfOrNull { it.end } ?: recordedEnd))
    }

    override suspend fun queuedEdits(): List<QueuedNightEdit> = synchronized(lock) {
        if (queueUnreadable) throw UnreadableEditQueueException("the queue of sleep edits waiting for Health cannot be read")
        queue.sortedBy { it.night }
    }

    override suspend fun clearQueuedEdit(edit: QueuedNightEdit): Boolean = synchronized(lock) {
        if (queueUnreadable) throw UnreadableEditQueueException("the queue of sleep edits waiting for Health cannot be read")
        val current = queue.firstOrNull { it.night == edit.night }
        if (current != edit) return@synchronized false
        queue.remove(current)
        true
    }

    override suspend fun pendingNaps(): List<StoredNapToWrite> = synchronized(lock) { napList.map { it.nap }.filter { !it.healthWritten } }

    /** As the store: marked only while the nap still shows what was sent. */
    override suspend fun markNapWritten(nap: StoredNapToWrite) = synchronized(lock) {
        val i = napList.indexOfFirst { it.nap.start == nap.start }
        val current = napList.getOrNull(i)?.nap ?: return@synchronized
        if (current.shownStart == nap.shownStart && current.shownEnd == nap.shownEnd && current.segments == nap.segments) {
            napList[i] = napList[i].copy(nap = current.copy(healthWritten = true))
        }
    }

    override suspend fun autoNaps(from: Instant, to: Instant): List<StoredNapToWrite> = synchronized(lock) {
        napList.filter { !it.nap.manuallyAdded && !it.manuallyEdited }.map { it.nap }.filter { nap ->
            val lo = minOf(nap.start, nap.shownStart)
            val hi = nap.shownEnd
            hi > lo && lo < to && hi > from
        }
    }

    override suspend fun deleteNaps(starts: List<Instant>) = synchronized(lock) {
        napList.removeAll { it.nap.start in starts }
        Unit
    }

    private fun advance(to: Instant) {
        val mark = sleepMark
        if (mark == null || to > mark) sleepMark = to
    }
}
