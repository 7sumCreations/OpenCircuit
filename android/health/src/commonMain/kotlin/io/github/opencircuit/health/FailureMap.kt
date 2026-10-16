package io.github.opencircuit.health

import java.time.Instant

/**
 * Where the per-type write-failure map is kept (the app keeps it in its store's key-value table),
 * so every flush entry point — after a sync, "Write to Health Connect now", a later background
 * write — reads and updates the same one.
 */
interface HealthFailureStore {
    /** Each type whose last write failed, with when; empty when none. */
    suspend fun load(): Map<RecordType, Instant>

    /** Replaces the stored map; an empty map removes it. */
    suspend fun save(failures: Map<RecordType, Instant>)
}

/**
 * The per-type write-failure map (upstream `recordFlushOutcome`,
 * ios/OpenCircuit/Health/HealthKitWriter.swift :377-396): a type whose write failed is stamped
 * with when, and a type that wrote is cleared, so "nothing pending" and "writes failing" stay
 * apart and a type the user turns back on heals by itself. Not granted and "call again later" are
 * neither: a skipped type keeps whatever it had.
 */
object FailureMap {
    /**
     * [current] after one flush that wrote [written] and failed [failed] at [now]. A type in both
     * (a call accepted, a later one failed) stays failing: part of it is still waiting. Upstream
     * clears it (it applies the writes after the failures).
     */
    fun merge(current: Map<RecordType, Instant>, written: Set<RecordType>, failed: Set<RecordType>, now: Instant): Map<RecordType, Instant> {
        val out = LinkedHashMap(current)
        written.forEach { out.remove(it) }
        failed.forEach { out[it] = now }
        return out
    }
}
