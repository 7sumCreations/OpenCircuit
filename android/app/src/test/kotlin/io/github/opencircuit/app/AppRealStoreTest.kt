package io.github.opencircuit.app

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's JVM tests reach the REAL store: the Android variant of `:store` (Room with the bundled
 * SQLite driver and its host natives), opened in memory, with no stand-in. Every sync test that
 * asserts "the record is in the store" stands on this.
 */
class AppRealStoreTest {

    @Test
    fun aSampleIngestedThroughTheAppsStoreIsReadBack() = runTest {
        val db = StoreFactory.openInMemory()
        try {
            val store = LocalStore(db)
            val at = Instant.parse("2026-10-07T03:12:30Z")
            val now = Instant.parse("2026-10-08T09:00:00Z")

            store.ingest(listOf(QuantitySample(MetricKind.HEART_RATE, start = at, value = 58.0)), now, ZoneOffset.UTC)

            assertEquals(
                listOf(58.0),
                store.samples(MetricKind.HEART_RATE, at, at.plusSeconds(1)).map { it.value },
            )
        } finally {
            db.close()
        }
    }
}
