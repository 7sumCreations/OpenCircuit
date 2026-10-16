package io.github.opencircuit.health

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the last write generation is kept (the app keeps it in its store's key-value table). */
interface WriteGenerationStore {
    /** The last generation handed out, or null when none ever was. */
    suspend fun load(): Long?

    suspend fun save(generation: Long)
}

/**
 * The version every record of one write carries. Health Connect replaces a stored record only
 * when the new version is higher, so each write takes the next number of a persisted counter:
 * the latest write, built from what the store holds now, always wins. The counter is not a clock:
 * a phone clock set back cannot make a newer write lose.
 *
 * Only the very first generation of an empty store reads [firstFloor] and starts above it: Health
 * Connect keeps the app's records after an uninstall, so a reinstall (or cleared app data) counting
 * again from 1 would lose to every version an earlier install wrote, and each replacement would be
 * ignored. The app passes the wall clock in milliseconds, which an earlier install's count of
 * flushes never catches up with.
 */
class WriteGeneration(
    private val store: WriteGenerationStore,
    private val firstFloor: () -> Long = { 0L },
) {
    private val lock = Mutex()

    /**
     * Saves and returns the next generation (one above [firstFloor] for the very first). Fails,
     * handing nothing out, when the save fails or the stored value is damaged (zero, negative, or
     * the largest Long): starting again low would make every later write lose to the records
     * already written.
     */
    suspend fun next(): Long = lock.withLock {
        val stored = store.load()
        check(stored == null || stored >= 1) { "the stored write generation $stored is damaged" }
        val last = stored ?: firstFloor().coerceAtLeast(0L)
        check(last < Long.MAX_VALUE) { "the write generation has run out" }
        val next = last + 1
        store.save(next)
        next
    }
}
