package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The nap reads and the nap delete, read from upstream's source (ios/OpenCircuit/Store/LocalStore.swift
 * `naps(on:)` :2998-3005, `naps(from:to:)` :3008-3013, `autoNaps(overlapping:to:)` :1809-1818,
 * `deleteNaps` :1822-1829 @ b1c2fdd), which has no store test of any. Fixtures are rows inserted
 * directly, so each read is tested apart from the writes.
 */
class NapReadsTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val later = Instant.parse("2025-06-20T12:00:00Z")

    private fun at(text: String): Instant = Instant.parse(text)

    private suspend fun StoreDatabase.nap(start: String, end: String, block: (StoredNapEntity) -> StoredNapEntity = { it }) {
        sleepDao().insertNap(block(StoredNapEntity(start = at(start), end = at(end), asleepMin = 60, updatedAt = at(start))))
    }

    private suspend fun StoreDatabase.snapshot(): List<String> = queryRaw("SELECT * FROM stored_nap ORDER BY id")

    /** Upstream `naps(on:)`: `[startOfDay, +1 day)` on the stored (detected) start, latest first. */
    @Test
    fun theNapsOfADayAreThoseDetectedToStartThatDayLatestFirst() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.nap("2025-06-14T23:59:59.999Z", "2025-06-15T00:30:00Z")
            db.nap("2025-06-15T00:00:00Z", "2025-06-15T00:40:00Z")
            db.nap("2025-06-15T14:00:00Z", "2025-06-15T15:00:00Z")
            // Edited to start the day before, but listed by the start it was detected at.
            db.nap("2025-06-15T09:00:00Z", "2025-06-15T10:00:00Z") {
                it.copy(editedStart = at("2025-06-14T22:00:00Z"), editedEnd = at("2025-06-14T23:00:00Z"), isManuallyEdited = true)
            }
            db.nap("2025-06-16T00:00:00Z", "2025-06-16T00:30:00Z")

            val starts = SleepStore(db).naps(on = at("2025-06-15T12:00:00Z"), zone = utc).map { it.start }

            assertEquals(listOf(at("2025-06-15T14:00:00Z"), at("2025-06-15T09:00:00Z"), at("2025-06-15T00:00:00Z")), starts)
        }
    }

    /** The day is cut in the zone given: in Kolkata (UTC+5:30) the 15th begins at 18:30Z on the 14th. */
    @Test
    fun theDayOfANapsReadIsTheDayInTheZoneGiven() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.nap("2025-06-14T18:29:59Z", "2025-06-14T19:00:00Z")
            db.nap("2025-06-14T18:30:00Z", "2025-06-14T19:00:00Z")
            db.nap("2025-06-15T18:29:00Z", "2025-06-15T19:00:00Z")
            db.nap("2025-06-15T18:30:00Z", "2025-06-15T19:00:00Z")

            val starts = SleepStore(db).naps(on = at("2025-06-15T06:00:00Z"), zone = ZoneId.of("Asia/Kolkata")).map { it.start }

            assertEquals(listOf(at("2025-06-15T18:29:00Z"), at("2025-06-14T18:30:00Z")), starts)
        }
    }

    /** Upstream `naps(from:to:)`: `from <= start < to` on the detected start, earliest first. */
    @Test
    fun theNapsOfARangeAreThoseDetectedToStartInItEarliestFirst() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.nap("2025-06-15T16:00:00Z", "2025-06-15T17:00:00Z")
            db.nap("2025-06-15T10:00:00Z", "2025-06-15T11:00:00Z")
            db.nap("2025-06-15T09:59:59.999Z", "2025-06-15T10:30:00Z")
            db.nap("2025-06-15T18:00:00Z", "2025-06-15T19:00:00Z")
            db.nap("2025-06-15T12:00:00Z", "2025-06-15T13:00:00Z") {
                it.copy(editedStart = at("2025-06-15T08:00:00Z"), editedEnd = at("2025-06-15T09:00:00Z"), isManuallyEdited = true)
            }

            val store = SleepStore(db)
            val starts = store.naps(from = at("2025-06-15T10:00:00Z"), to = at("2025-06-15T18:00:00Z")).map { it.start }

            assertEquals(listOf(at("2025-06-15T10:00:00Z"), at("2025-06-15T12:00:00Z"), at("2025-06-15T16:00:00Z")), starts)
            assertEquals(emptyList(), store.naps(from = at("2025-06-15T18:00:00Z"), to = at("2025-06-15T10:00:00Z")))
        }
    }

    /**
     * Upstream `autoNaps`: naps the ring detected and nobody edited or added, whose detected-or-effective
     * span overlaps `[start, end]` by more than an instant; none when `end` is not after `start`.
     */
    @Test
    fun theAutomaticNapsOverlappingASpanAreTheUneditedOnesThatShareTimeWithIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.nap("2025-06-15T13:00:00Z", "2025-06-15T14:00:00Z") // inside
            db.nap("2025-06-15T11:00:00Z", "2025-06-15T12:00:00Z") // ends where the span starts
            db.nap("2025-06-15T18:00:00Z", "2025-06-15T19:00:00Z") // starts where the span ends
            db.nap("2025-06-15T11:30:00Z", "2025-06-15T12:00:00.001Z") // one millisecond inside
            db.nap("2025-06-15T14:30:00Z", "2025-06-15T15:00:00Z") { it.copy(isManuallyAdded = true) }
            db.nap("2025-06-15T15:30:00Z", "2025-06-15T16:00:00Z") { it.copy(isManuallyEdited = true) }
            // Edit columns without the flag: the span is the union of both windows, as upstream's `min` / `max`.
            db.nap("2025-06-15T09:00:00Z", "2025-06-15T10:00:00Z") { it.copy(editedEnd = at("2025-06-15T12:30:00Z")) }

            val store = SleepStore(db)
            val starts = store.autoNaps(start = at("2025-06-15T12:00:00Z"), end = at("2025-06-15T18:00:00Z")).map { it.start }

            assertEquals(listOf(at("2025-06-15T09:00:00Z"), at("2025-06-15T11:30:00Z"), at("2025-06-15T13:00:00Z")), starts)
            assertEquals(emptyList(), store.autoNaps(start = at("2025-06-15T18:00:00Z"), end = at("2025-06-15T12:00:00Z")))
            assertEquals(emptyList(), store.autoNaps(start = at("2025-06-15T13:30:00Z"), end = at("2025-06-15T13:30:00Z")))
        }
    }

    @Test
    fun deletingNapsRemovesThoseAndReportsHowManyWentAndAnEmptyListDeletesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.nap("2025-06-15T09:00:00Z", "2025-06-15T10:00:00Z")
            db.nap("2025-06-15T13:00:00Z", "2025-06-15T14:00:00Z")
            db.nap("2025-06-15T16:00:00Z", "2025-06-15T17:00:00Z")
            val store = SleepStore(db)
            val all = store.naps(from = at("2025-06-15T00:00:00Z"), to = at("2025-06-16T00:00:00Z"))
            val before = db.snapshot()

            assertEquals(0, store.deleteNaps(emptyList()))
            assertEquals(before, db.snapshot())

            assertEquals(2, store.deleteNaps(listOf(all[0], all[2])))
            assertEquals(listOf(at("2025-06-15T13:00:00Z")), store.naps(from = at("2025-06-15T00:00:00Z"), to = at("2025-06-16T00:00:00Z")).map { it.start })

            // A nap already gone is not counted again.
            assertEquals(0, store.deleteNaps(listOf(all[0])))
        }
    }

    /** Upstream rolls a failed delete back and returns silently (:1825). Here the failure is thrown, nothing deleted. */
    @Test
    fun aFailedDeleteThrowsAndDeletesNone() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.nap("2025-06-15T09:00:00Z", "2025-06-15T10:00:00Z")
            db.nap("2025-06-15T13:00:00Z", "2025-06-15T14:00:00Z")
            val all = SleepStore(db).naps(from = at("2025-06-15T00:00:00Z"), to = at("2025-06-16T00:00:00Z"))
            val before = db.snapshot()
            val real = db.sleepDao()
            var calls = 0
            val failing = object : SleepDao by real {
                override suspend fun deleteNapAt(start: Instant): Int = if (++calls == 2) error("injected failure") else real.deleteNapAt(start)
            }

            assertFailsWith<IllegalStateException> { SleepStore(db, failing).deleteNaps(all) }

            assertEquals(2, calls, "the first delete ran inside the failing transaction")
            assertEquals(before, db.snapshot())
            assertEquals(all, SleepStore(db).naps(from = at("2025-06-15T00:00:00Z"), to = at("2025-06-16T00:00:00Z")))
        }
    }

    @Test
    fun aNapReadsBackAsItsRow() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.nap("2025-06-15T13:00:00Z", "2025-06-15T14:30:00Z") {
                it.copy(asleepMin = 85, isLongNap = true, healthWritten = true, updatedAt = later, napSegmentsData = "[]".toByteArray())
            }

            assertEquals(
                StoredNapEntity(
                    start = at("2025-06-15T13:00:00Z"), end = at("2025-06-15T14:30:00Z"), asleepMin = 85, isLongNap = true,
                    healthWritten = true, updatedAt = later, napSegmentsData = "[]".toByteArray(),
                ).toStoredNapRecord(),
                SleepStore(db).naps(on = at("2025-06-15T00:00:00Z"), zone = utc).single(),
            )
        }
    }
}
