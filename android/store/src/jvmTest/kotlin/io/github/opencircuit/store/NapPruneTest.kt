package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepPersistOutcome
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A saved night removes the automatic naps it now covers, read from upstream's source
 * (ios/OpenCircuit/Store/LocalStore.swift `pruneAutoNaps` :1837-1853, called at :1801 @ b1c2fdd),
 * which has no test: a nap the ring detected inside the night is the same sleep counted twice, while
 * a nap the wearer added or edited is their word and stays. Upstream prunes after the night's own
 * save and swallows a failed prune; here the prune is part of the night's one transaction, so a
 * failure fails the night and leaves both the night and the naps as they were.
 */
class NapPruneTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-20T12:00:00Z")
    private val later = Instant.parse("2025-06-21T12:00:00Z")

    private fun at(text: String): Instant = Instant.parse(text)

    private fun hours(asleep: Long, awake: Long) = SleepStaging.Summary(
        inBed = Duration.ofHours(asleep + awake), awake = Duration.ofHours(awake), light = Duration.ofHours(asleep),
        deep = Duration.ZERO, rem = Duration.ZERO,
    )

    private suspend fun SleepStore.night(inBedStart: Instant, inBedEnd: Instant, summary: SleepStaging.Summary = hours(7, 1), at: Instant = now) =
        saveSleepSummary(summary, night = inBedEnd, inBedStart = inBedStart, inBedEnd = inBedEnd, now = at, zone = utc)

    private suspend fun SleepStore.napStarts(): List<Instant> = naps(from = at("2000-01-01T00:00:00Z"), to = at("2100-01-01T00:00:00Z")).map { it.start }

    private suspend fun StoreDatabase.naps(): List<String> = queryRaw("SELECT * FROM stored_nap ORDER BY id")

    private suspend fun StoreDatabase.nights(): List<String> = queryRaw("SELECT * FROM stored_sleep_summary ORDER BY id")

    /**
     * Naps stored before the night of 2025-06-15 22:00 to 06-16 07:00 that covers them: two of the
     * ring's inside it, the wearer's two inside it, two at its edges and one in the afternoon.
     */
    private suspend fun SleepStore.napsAroundTheNight() {
        saveNap(at("2025-06-15T23:00:00Z"), at("2025-06-15T23:30:00Z"), 25, false, now = now, zone = utc) // a waking gap
        saveNap(at("2025-06-16T05:00:00Z"), at("2025-06-16T06:30:00Z"), 80, false, now = now, zone = utc) // the night's tail
        saveNap(at("2025-06-15T21:00:00Z"), at("2025-06-15T22:00:00Z"), 55, false, now = now, zone = utc) // ends at the night's start
        saveNap(at("2025-06-16T07:00:00Z"), at("2025-06-16T08:00:00Z"), 55, false, now = now, zone = utc) // starts at its end
        saveNap(at("2025-06-16T13:00:00Z"), at("2025-06-16T14:00:00Z"), 55, false, now = now, zone = utc) // the afternoon
        check(addManualNap(at("2025-06-16T03:00:00Z"), at("2025-06-16T04:00:00Z"), now = now, zone = utc))
        saveNap(at("2025-06-16T01:00:00Z"), at("2025-06-16T02:00:00Z"), 55, false, now = now, zone = utc)
        check(editNap(at("2025-06-16T01:00:00Z"), at("2025-06-16T01:00:00Z"), at("2025-06-16T02:30:00Z"), now = now, zone = utc))
    }

    @Test
    fun aNewNightRemovesTheAutomaticNapsItCoversAndKeepsTheWearersAndThoseAtItsEdges() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.napsAroundTheNight()

            assertEquals(SleepPersistOutcome.INSERTED, store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T07:00:00Z")))

            assertEquals(
                listOf(
                    at("2025-06-15T21:00:00Z"), at("2025-06-16T01:00:00Z"), at("2025-06-16T03:00:00Z"),
                    at("2025-06-16T07:00:00Z"), at("2025-06-16T13:00:00Z"),
                ),
                store.napStarts(),
            )
        }
    }

    /** A fuller staging that grows the stored night over a nap removes it too (the replacing save, :1801). */
    @Test
    fun aNightThatGrowsOverAnAutomaticNapRemovesIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T05:00:00Z"), hours(6, 1))
            store.saveNap(at("2025-06-16T05:30:00Z"), at("2025-06-16T06:30:00Z"), 55, false, now = now, zone = utc)
            store.saveNap(at("2025-06-16T13:00:00Z"), at("2025-06-16T14:00:00Z"), 55, false, now = now, zone = utc)

            assertEquals(SleepPersistOutcome.UPDATED, store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T07:00:00Z"), hours(8, 1), at = later))

            assertEquals(listOf(at("2025-06-16T13:00:00Z")), store.napStarts())
        }
    }

    /** Upstream returns before its save on every kept or refused night, so the prune never runs there. */
    @Test
    fun aKeptOrRefusedNightRemovesNoNap() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T06:00:00Z"), hours(7, 1))
            store.night(at("2025-06-16T22:00:00Z"), at("2025-06-17T06:00:00Z"), hours(7, 1))
            check(
                store.applySleepEdit(
                    night = at("2025-06-17T06:00:00Z"),
                    times = SleepEdit.Times(at("2025-06-16T22:30:00Z"), at("2025-06-16T23:00:00Z"), at("2025-06-17T05:30:00Z")),
                    summary = hours(6, 1), now = now, zone = utc,
                ),
            )
            // Automatic naps the kept and refused stagings below would cover, stored past the nights' own windows.
            db.sleepDao().insertNap(StoredNapEntity(start = at("2025-06-16T06:00:00Z"), end = at("2025-06-16T07:00:00Z"), asleepMin = 55))
            db.sleepDao().insertNap(StoredNapEntity(start = at("2025-06-17T06:00:00Z"), end = at("2025-06-17T07:00:00Z"), asleepMin = 55))
            db.sleepDao().insertNap(StoredNapEntity(start = at("2025-06-17T19:00:00Z"), end = at("2025-06-17T20:00:00Z"), asleepMin = 55))
            val before = db.naps()

            // Thinner than the stored night: kept.
            assertEquals(SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT, store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T07:00:00Z"), hours(3, 1)))
            // The edited night: kept.
            assertEquals(SleepPersistOutcome.KEPT_MANUAL_EDIT, store.night(at("2025-06-16T22:00:00Z"), at("2025-06-17T07:00:00Z"), hours(8, 1)))
            // An evening bout keyed onto the night that ended this morning: refused.
            assertEquals(
                SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION,
                store.night(at("2025-06-17T18:30:00Z"), at("2025-06-17T20:30:00Z"), hours(1, 1)),
            )

            assertEquals(before, db.naps())
        }
    }

    /**
     * The night and its prune are one transaction: a prune that fails fails the night, and neither the
     * night nor any nap changes — for a replacing save and for a new night alike.
     */
    @Test
    fun aNightWhosePruneFailsIsNotSavedAndEveryNapStays() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.night(at("2025-06-14T22:00:00Z"), at("2025-06-15T05:00:00Z"), hours(6, 1))
            store.saveNap(at("2025-06-15T05:30:00Z"), at("2025-06-15T06:30:00Z"), 55, false, now = now, zone = utc)
            store.saveNap(at("2025-06-15T06:40:00Z"), at("2025-06-15T07:10:00Z"), 25, false, now = now, zone = utc)
            store.napsAroundTheNight()
            val nights = db.nights()
            val naps = db.naps()
            val real = db.sleepDao()
            var deletes = 0
            val failing = object : SleepDao by real {
                override suspend fun deleteNapAt(start: Instant): Int = if (++deletes == 2) error("injected failure") else real.deleteNapAt(start)
            }
            val failingStore = SleepStore(db, failing)

            // A replacing save over two naps: the second delete fails.
            assertFailsWith<IllegalStateException> {
                failingStore.night(at("2025-06-14T22:00:00Z"), at("2025-06-15T07:30:00Z"), hours(8, 1), at = later)
            }
            assertEquals(2, deletes, "the row was replaced and the first nap deleted inside the failing transaction")
            assertEquals(nights, db.nights())
            assertEquals(naps, db.naps())

            // A new night over two naps: the second delete fails.
            deletes = 0
            assertFailsWith<IllegalStateException> {
                failingStore.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T07:00:00Z"), at = later)
            }
            assertEquals(2, deletes, "the row was inserted and the first nap deleted inside the failing transaction")
            assertEquals(nights, db.nights())
            assertEquals(naps, db.naps())
        }
    }

    /** Upstream's guard (:1838): a night with no window known prunes nothing. */
    @Test
    fun aNightWithoutAKnownWindowRemovesNoNap() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNap(at("2025-06-16T05:00:00Z"), at("2025-06-16T06:30:00Z"), 80, false, now = now, zone = utc)
            val before = db.naps()

            // Reversed so that, read as a span without the guard, the nap would share time with it.
            store.saveSleepSummary(
                hours(7, 1), night = at("2025-06-16T05:30:00Z"), inBedStart = at("2025-06-16T06:00:00Z"), inBedEnd = at("2025-06-16T05:30:00Z"),
                now = now, zone = utc,
            )
            assertEquals(1, store.recentSleepSummaries().size)

            assertEquals(before, db.naps())
        }
    }
}
