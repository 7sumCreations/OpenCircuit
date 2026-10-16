package io.github.opencircuit.health

import java.time.Instant

/** The write generation in memory (the app keeps it in its store). */
class InMemoryWriteGenerations(@Volatile var value: Long? = null) : WriteGenerationStore {
    override suspend fun load(): Long? = value
    override suspend fun save(generation: Long) {
        value = generation
    }
}

/** The failure map in memory (the app keeps it in its store), counting its saves. */
class InMemoryHealthFailures : HealthFailureStore {
    @Volatile var stored: Map<RecordType, Instant> = emptyMap()
        private set
    @Volatile var saves: Int = 0
        private set

    override suspend fun load(): Map<RecordType, Instant> = stored

    override suspend fun save(failures: Map<RecordType, Instant>) {
        stored = failures.toMap()
        saves++
    }
}
