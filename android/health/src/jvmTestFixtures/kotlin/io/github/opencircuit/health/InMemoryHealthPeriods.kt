package io.github.opencircuit.health

import java.time.Instant
import java.time.ZoneId

/**
 * The logged periods in memory, with the store's own rules (`:store` `LocalStore`
 * `periodEntriesNeedingHealthMirror` / `recordPeriodEntryHealthIds`): a period is asked for when it
 * was never written (or edited since), is still open, or ends on a day after today's in the zone;
 * recording ids replaces the list and sets written only when finalized; recording for a period no
 * longer logged changes nothing. [recorded] keeps every recording, in order, for the tests that
 * check what was named before a call.
 */
class InMemoryHealthPeriods : HealthPeriodStore {
    private val lock = Any()
    private val rows = sortedMapOf<Instant, PeriodToWrite>()
    private val log = mutableListOf<Pair<Instant, List<String>>>()

    val recorded: List<Pair<Instant, List<String>>> get() = synchronized(lock) { log.toList() }

    /** Logs a period (or edits the one at its start: an edit of a clinical field clears written and keeps the ids, as the store's save does). */
    fun put(entry: PeriodToWrite) = synchronized(lock) { rows[entry.start] = entry }

    fun get(start: Instant): PeriodToWrite? = synchronized(lock) { rows[start] }

    override suspend fun periodsNeedingMirror(today: Instant, zone: ZoneId): List<PeriodToWrite> = synchronized(lock) {
        val todayDay = today.atZone(zone).toLocalDate()
        rows.values.filter { !it.healthWritten || it.end == null || it.end.atZone(zone).toLocalDate().isAfter(todayDay) }
    }

    override suspend fun recordWrittenIds(start: Instant, ids: List<String>, finalized: Boolean): Unit = synchronized(lock) {
        val row = rows[start] ?: return
        rows[start] = row.copy(writtenIds = ids.toList(), healthWritten = finalized)
        log += start to ids.toList()
    }
}
