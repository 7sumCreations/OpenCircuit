package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitTests/SyncCursorPlausibilityTests.swift (@ b1c2fdd): an
 * implausible sample must not poison the sync cursor, and a cursor already stuck in the future is
 * repaired.
 *
 * Upstream reads the wall clock (`Date()`); here one fixed `now` is passed to every call. Swift's
 * `.distantPast` / `.distantFuture` are the years 0001 and 4001. Upstream pokes the cursor rows
 * through its SwiftData context; here through the cursor DAO.
 */
class SyncCursorPlausibilityTest {

    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val utc = ZoneOffset.UTC
    private val distantPast = Instant.parse("0001-01-01T00:00:00Z")
    private val distantFuture = Instant.parse("4001-01-01T00:00:00Z")
    private val tenYears = 10L * 365 * 24 * 3600

    private fun hr(value: Double, date: Instant) = QuantitySample(MetricKind.HEART_RATE, start = date, value = value)

    /** Upstream `testFutureDatedSampleDoesNotPoisonCursor` (`:27`). */
    @Test
    fun futureDatedSampleDoesNotPoisonCursor() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val corrupted = hr(83.0, Instant.ofEpochSecond(3_155_760_000)) // ~2069

            store.ingest(listOf(corrupted), now, utc)
            assertTrue(store.samples(MetricKind.HEART_RATE, from = distantPast, to = distantFuture).isEmpty())

            val legit = hr(72.0, now)
            val ingested = store.ingest(listOf(legit), now, utc)
            assertEquals(listOf(72.0), ingested.map { it.value })
            assertEquals(listOf(72.0), store.samples(MetricKind.HEART_RATE, from = distantPast, to = distantFuture).map { it.value })
        }
    }

    /** Upstream `testOutOfBandHeartRateDoesNotPoisonCursor` (`:43`). */
    @Test
    fun outOfBandHeartRateDoesNotPoisonCursor() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val garbageValue = hr(0.0, now.minusSeconds(60)) // earlier, but value 0 is implausible

            store.ingest(listOf(garbageValue), now, utc)
            val legit = hr(72.0, now)
            val ingested = store.ingest(listOf(legit), now, utc)
            assertEquals(listOf(72.0), ingested.map { it.value })
        }
    }

    /** Upstream `testRepairResetsStuckCursorToLatestPlausibleSample` (`:57`). */
    @Test
    fun repairResetsStuckCursorToLatestPlausibleSample() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val validPast = now.minusSeconds(3600)

            store.ingest(listOf(hr(70.0, validPast)), now, utc)
            val rows = db.sampleDao().allCursors()
            assertEquals(1, rows.size)
            db.sampleDao().upsertCursors(listOf(rows[0].copy(last = now.plusSeconds(tenYears))))

            val repaired = store.repairFutureSyncCursors(now)
            assertEquals(1, repaired)

            assertEquals(validPast, db.sampleDao().allCursors().firstOrNull()?.last)

            val ingested = store.ingest(listOf(hr(75.0, now)), now, utc)
            assertEquals(listOf(75.0), ingested.map { it.value })
        }
    }

    /** Upstream `testRepairRemovesCursorWithNoPlausibleSamples` (`:86`). */
    @Test
    fun repairRemovesCursorWithNoPlausibleSamples() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity(kindRaw = "heartRate", last = now.plusSeconds(tenYears))))

            val repaired = store.repairFutureSyncCursors(now)
            assertEquals(1, repaired)
            assertTrue(db.sampleDao().allCursors().isEmpty())

            val ingested = store.ingest(listOf(hr(72.0, now)), now, utc)
            assertEquals(listOf(72.0), ingested.map { it.value })
        }
    }

    /** Upstream `testRepairCoversHealthKitMirrorCursor` (`:104`). */
    @Test
    fun repairCoversHealthMirrorCursor() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            db.sampleDao().upsertCursors(listOf(StoredCursorEntity(kindRaw = "hk:heartRate", last = now.plusSeconds(tenYears))))

            val repaired = store.repairFutureSyncCursors(now)
            assertEquals(1, repaired)
            assertTrue(db.sampleDao().allCursors().isEmpty())
        }
    }

    /** Upstream `testRepairIsNoOpWhenNothingIsStuck` (`:117`). */
    @Test
    fun repairIsNoOpWhenNothingIsStuck() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(70.0, now)), now, utc)
            assertEquals(0, store.repairFutureSyncCursors(now))
        }
    }
}
