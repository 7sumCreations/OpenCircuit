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
 */
class WriteGeneration(private val store: WriteGenerationStore) {
    private val lock = Mutex()

    /**
     * Saves and returns the next generation (1 for the very first). Fails, handing nothing out,
     * when the save fails or the stored value is damaged (zero, negative, or the largest Long):
     * starting again from 1 would make every later write lose to the records already written.
     */
    suspend fun next(): Long = lock.withLock {
        val stored = store.load()
        check(stored == null || stored >= 1) { "the stored write generation $stored is damaged" }
        val last = stored ?: 0L
        check(last < Long.MAX_VALUE) { "the write generation has run out" }
        val next = last + 1
        store.save(next)
        next
    }
}
