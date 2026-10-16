package io.github.opencircuit.app

import io.github.opencircuit.app.health.StoreHealthFailures
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The write-failure map through the app's real store: kept by record type name, read back as
 * record types, and shared by every writer over the same database (each flush entry point builds
 * its own). A stored name this version does not know is left out of what is read.
 */
class StoreHealthFailuresTest {

    private val now = Instant.parse("2026-10-16T12:00:00Z")

    @Test
    fun theMapIsKeptByTypeNameAndReadBackByAnotherAdapterOverTheSameStore() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val failures = mapOf(RecordType.SLEEP_SESSION to now.minusSeconds(60), RecordType.OXYGEN_SATURATION to now)
            StoreHealthFailures({ db }, { now }, log = {}).save(failures)

            assertEquals(failures, StoreHealthFailures({ db }, { now }, log = {}).load())
            assertEquals(
                Decoded.Readable(mapOf("OXYGEN_SATURATION" to now, "SLEEP_SESSION" to now.minusSeconds(60))),
                BlobStore(db).loadHealthWriteFailures(),
            )

            StoreHealthFailures({ db }, { now }, log = {}).save(emptyMap())
            assertEquals(emptyMap(), StoreHealthFailures({ db }, { now }, log = {}).load())
        } finally {
            db.close()
        }
    }

    @Test
    fun aNameThisVersionDoesNotKnowIsLeftOut() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            BlobStore(db).saveHealthWriteFailures(mapOf("EXERCISE_SESSION" to now, "HEART_RATE" to now), now)

            assertEquals(mapOf(RecordType.HEART_RATE to now), StoreHealthFailures({ db }, { now }, log = {}).load())
        } finally {
            db.close()
        }
    }
}
