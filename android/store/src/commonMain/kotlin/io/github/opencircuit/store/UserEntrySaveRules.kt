package io.github.opencircuit.store

import java.time.Instant

// The save rules the period log and the headache log share. Upstream writes them out twice,
// line for line: ios/OpenCircuit/CycleStore.swift `savePeriodEntry` (:81-135) and
// ios/OpenCircuit/Store/HeadacheStore.swift `saveHeadacheEntry` (:206-268) (@ b1c2fdd). Here they
// are written once, over a table that knows its own key and Health columns.
//
// Difference, deliberate: upstream deletes the row at the destination of a move BEFORE it looks for
// the original, and when the original has vanished (a stale edit screen) falls through to a plain
// save, so the destination row and the Health sample ids it held are lost. Here the destination row
// is only folded away when the original is there to take its ids; otherwise the plain save edits
// it in place.

/** One user-entry table, as the shared save rules see it. */
internal interface UserEntryTable<R : Any> {
    suspend fun find(key: Instant): R?

    suspend fun insert(row: R)

    suspend fun update(row: R)

    suspend fun delete(row: R)

    /** The ids of the Health samples last written for [row]. */
    fun writtenIds(row: R): List<String>

    /** [row] at [key], naming [ids] as its written samples, its Health mirror out of date. */
    fun moved(row: R, key: Instant, ids: List<String>): R

    /** [row] with its Health mirror out of date (the written ids are kept, to be deleted). */
    fun mirrorOutOfDate(row: R): R
}

/**
 * Saves one entry at [key] (already cut to the stored millisecond, as is [originalKey]); the caller
 * holds the transaction.
 *
 * - A move ([originalKey] given, different from [key], and its row present): the original row is
 *   relocated to [key] and [edited]; a row already at [key] is deleted first and its written ids are
 *   appended to the relocated row's; the Health mirror is out of date.
 * - Otherwise, a row at [key] is [edited], and its mirror goes out of date only when
 *   [isClinicalChange] says the edit changes what the health store shows; with no row, [fresh] is
 *   inserted.
 */
internal suspend fun <R : Any> UserEntryTable<R>.saveEntry(
    key: Instant,
    originalKey: Instant?,
    edited: (R) -> R,
    isClinicalChange: (R) -> Boolean,
    fresh: () -> R,
) {
    if (originalKey != null && originalKey != key) {
        val original = find(originalKey)
        if (original != null) {
            val displaced = find(key)
            if (displaced != null) delete(displaced)
            val inherited = displaced?.let(::writtenIds).orEmpty()
            update(moved(edited(original), key, writtenIds(original) + inherited))
            return
        }
    }
    val existing = find(key)
    when {
        existing == null -> insert(fresh())
        isClinicalChange(existing) -> update(mirrorOutOfDate(edited(existing)))
        else -> update(edited(existing))
    }
}

/** The period log's table: keyed by `start`. */
internal class PeriodEntryTable(private val dao: UserEntryDao) : UserEntryTable<StoredPeriodEntryEntity> {
    override suspend fun find(key: Instant) = dao.periodAt(key)

    override suspend fun insert(row: StoredPeriodEntryEntity) = dao.insertPeriod(row)

    override suspend fun update(row: StoredPeriodEntryEntity) = dao.updatePeriod(row)

    override suspend fun delete(row: StoredPeriodEntryEntity) = dao.deletePeriod(row)

    override fun writtenIds(row: StoredPeriodEntryEntity) = row.hkSampleUUIDs

    override fun moved(row: StoredPeriodEntryEntity, key: Instant, ids: List<String>) =
        row.copy(start = key, hkSampleUUIDs = ids, healthWritten = false)

    override fun mirrorOutOfDate(row: StoredPeriodEntryEntity) = row.copy(healthWritten = false)
}

/** The headache log's table: keyed by `onset`. */
internal class HeadacheEntryTable(private val dao: UserEntryDao) : UserEntryTable<StoredHeadacheEntryEntity> {
    override suspend fun find(key: Instant) = dao.headacheAt(key)

    override suspend fun insert(row: StoredHeadacheEntryEntity) = dao.insertHeadache(row)

    override suspend fun update(row: StoredHeadacheEntryEntity) = dao.updateHeadache(row)

    override suspend fun delete(row: StoredHeadacheEntryEntity) = dao.deleteHeadache(row)

    override fun writtenIds(row: StoredHeadacheEntryEntity) = row.hkSampleUUIDs

    override fun moved(row: StoredHeadacheEntryEntity, key: Instant, ids: List<String>) =
        row.copy(onset = key, hkSampleUUIDs = ids, healthWritten = false)

    override fun mirrorOutOfDate(row: StoredHeadacheEntryEntity) = row.copy(healthWritten = false)
}
