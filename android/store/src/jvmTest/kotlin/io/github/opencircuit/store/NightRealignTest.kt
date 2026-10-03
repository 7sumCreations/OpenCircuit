package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepPersistOutcome
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.codec.Decoded
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A night found by its span may still be filed under the key its first slice produced: an evening
 * drain stages the part before midnight under that day, and the completed night belongs to the next.
 * When the completed staging replaces it, the night moves to the completed staging's key with
 * everything kept under its key — upstream `realignNightKey` (ios/OpenCircuit/Store/
 * LocalStore.swift:1561-1573 @ b1c2fdd), called only on the replacing path (:1755-1763).
 *
 * Refused, the night staying on its key while the save still replaces it, when another night holds
 * the new key or something is already kept under it. The move is part of the save's one
 * transaction (PORTING D-170); a failing occupancy read fails the save (upstream reads it as free).
 */
class NightRealignTest {

    private val utc = ZoneOffset.UTC
    private val now1 = Instant.parse("2025-06-14T23:45:00Z")
    private val now2 = Instant.parse("2025-06-15T07:00:00Z")
    private val earlier = Instant.parse("2025-06-14T23:50:00Z")
    private val evening = Instant.parse("2025-06-14T00:00:00Z") // the evening part's key
    private val morning = Instant.parse("2025-06-15T00:00:00Z") // the completed night's key
    private val bed = Instant.parse("2025-06-14T20:00:00Z")
    private val completed = Instant.parse("2025-06-15T06:30:00Z")

    private fun summary(inBedMin: Long, asleepMin: Long) = SleepStaging.Summary(
        inBed = Duration.ofMinutes(inBedMin), awake = Duration.ofMinutes(inBedMin - asleepMin),
        light = Duration.ofMinutes(asleepMin), deep = Duration.ZERO, rem = Duration.ZERO,
    )

    /** A staging of `[bed, end]`, keyed — as every staging — by the day it ends on. */
    private suspend fun SleepStore.stage(end: Instant, asleepMin: Long, now: Instant = now2): SleepPersistOutcome {
        val inBed = Duration.between(bed, end).toMinutes()
        return saveSleepSummary(summary(inBed, asleepMin), night = end, inBedStart = bed, inBedEnd = end, now = now, zone = utc)
    }

    /** The evening part, stored before midnight under its day: 20:00–23:30, 180 min asleep. */
    private suspend fun SleepStore.eveningPart() =
        assertEquals(SleepPersistOutcome.INSERTED, stage(Instant.parse("2025-06-14T23:30:00Z"), 180, now = now1))

    /** Health's sample ids and mirror, both watermarks, a risk row and a queued item under [night]. */
    private suspend fun seedValuesAt(db: StoreDatabase, night: Instant) {
        val s = night.epochSecond
        db.kvDao().upsert(StoreKvEntity("sleep.edit.hkuuids.$s.0", "[\"U-1\"]", earlier))
        db.kvDao().upsert(StoreKvEntity("sleep.mirror.night.$s.0", "mirror", earlier))
        db.sleepDao().insertCursor(StoredCursorEntity("hk:sleep-edit-leading:$s.0", bed))
        db.sleepDao().insertCursor(StoredCursorEntity("hk:sleep-edit-leading-asleep:$s.0", bed.plusSeconds(900)))
        db.userEntryDao().insertRisk(StoredHeadacheRiskEntity(day = morning, nightKey = night, index = 0.3))
        PendingSleepReconciles(db.kvDao()).upsert(PendingSleepReconcile(night, bed, bed, completed, emptyList()), utc, earlier)
    }

    @Test
    fun aNightFoundByItsSpanMovesToTheKeyOfTheStagingThatReplacesItWithEverythingKeptUnderItsKey() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.eveningPart()
            seedValuesAt(db, evening)

            assertEquals(SleepPersistOutcome.UPDATED, store.stage(completed, 560))

            assertEquals(listOf(morning to 560), db.sleepDao().allSummaries().map { it.night to it.asleepMin })
            val s = morning.epochSecond
            assertEquals("[\"U-1\"]", db.kvDao().get("sleep.edit.hkuuids.$s.0")?.value)
            assertEquals("mirror", db.kvDao().get("sleep.mirror.night.$s.0")?.value)
            assertEquals(bed, db.sleepDao().cursorAt("hk:sleep-edit-leading:$s.0")?.last)
            assertEquals(bed.plusSeconds(900), db.sleepDao().cursorAt("hk:sleep-edit-leading-asleep:$s.0")?.last)
            assertEquals(morning, db.userEntryDao().riskOn(morning)?.nightKey)
            assertEquals(
                listOf(morning),
                assertIs<Decoded.Readable<List<PendingSleepReconcile>>>(PendingSleepReconciles(db.kvDao()).all()).value.map { it.night },
            )
            assertEquals(listOf("0"), db.queryRaw("SELECT COUNT(*) FROM store_kv WHERE `key` LIKE '%.${evening.epochSecond}.0'"))
        }
    }

    @Test
    fun aKeyHeldByAnotherNightLeavesTheNightOnItsKeyAndTheSaveStillReplacesIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.eveningPart()
            // A short sleep keyed to the morning, sharing no time with the night being completed.
            db.sleepDao().insertSummary(
                StoredSleepSummaryEntity(
                    night = morning, asleepMin = 50, inBedStart = Instant.parse("2025-06-15T10:00:00Z"), inBedEnd = Instant.parse("2025-06-15T11:00:00Z"),
                ),
            )

            assertEquals(SleepPersistOutcome.UPDATED, store.stage(completed, 560))

            assertEquals(listOf(evening to 560, morning to 50), db.sleepDao().allSummaries().map { it.night to it.asleepMin })
        }
    }

    @Test
    fun aValueAlreadyUnderTheNewKeyLeavesTheNightAndItsValuesOnItsKeyAndTheSaveStillReplacesIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.eveningPart()
            db.kvDao().upsert(StoreKvEntity("sleep.edit.hkuuids.${evening.epochSecond}.0", "[\"mine\"]", earlier))
            db.kvDao().upsert(StoreKvEntity("sleep.mirror.night.${morning.epochSecond}.0", "an orphan", earlier))

            assertEquals(SleepPersistOutcome.UPDATED, store.stage(completed, 560))

            assertEquals(listOf(evening to 560), db.sleepDao().allSummaries().map { it.night to it.asleepMin })
            assertEquals("[\"mine\"]", db.kvDao().get("sleep.edit.hkuuids.${evening.epochSecond}.0")?.value)
            assertEquals("an orphan", db.kvDao().get("sleep.mirror.night.${morning.epochSecond}.0")?.value)
        }
    }

    /** The queue is never rewritten while unreadable, so the night it may belong to keeps its key. */
    @Test
    fun aPendingQueueThisBuildCannotReadLeavesTheNightOnItsKeyAndTheSaveStillReplacesIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.eveningPart()
            db.kvDao().upsert(StoreKvEntity(PendingSleepReconciles.KEY, "{oops", earlier))

            assertEquals(SleepPersistOutcome.UPDATED, store.stage(completed, 560))

            assertEquals(listOf(evening to 560), db.sleepDao().allSummaries().map { it.night to it.asleepMin })
            assertEquals(StoreKvEntity(PendingSleepReconciles.KEY, "{oops", earlier), db.kvDao().get(PendingSleepReconciles.KEY))
        }
    }

    @Test
    fun aStagingThatDoesNotReplaceTheNightDoesNotMoveIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.eveningPart()

            // Ends after midnight (so keys to the morning) but holds less sleep: the stored night is kept.
            assertEquals(SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT, store.stage(Instant.parse("2025-06-15T00:30:00Z"), 120))

            assertEquals(listOf(evening to 180), db.sleepDao().allSummaries().map { it.night to it.asleepMin })
        }
    }

    @Test
    fun aSaveThatFailsAfterTheMoveLeavesTheNightItsKeyAndEverythingUnderItAsTheyWere() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.eveningPart()
            seedValuesAt(db, evening)
            assertTrue(store.ensureNightKeyMigrated(utc, now2))
            val before = TableSnapshot.of(db)
            val real = db.sleepDao()
            var renamed = false
            val failing = object : SleepDao by real {
                override suspend fun updateSummary(row: StoredSleepSummaryEntity) {
                    // Inside the save's transaction: the values have already moved.
                    renamed = real.cursorAt("hk:sleep-edit-leading:${morning.epochSecond}.0") != null
                    error("injected failure")
                }
            }

            assertFailsWith<IllegalStateException> { SleepStore(db, failing).stage(completed, 560) }

            assertTrue(renamed, "the failure came after the move")
            assertEquals(before, TableSnapshot.of(db))
        }
    }

    @Test
    fun aFailingReadOfTheNewKeyFailsTheSaveRatherThanTakingTheKeyAsFree() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.eveningPart()
            assertTrue(store.ensureNightKeyMigrated(utc, now2))
            val before = TableSnapshot.of(db)
            val real = db.sleepDao()
            val failing = object : SleepDao by real {
                override suspend fun summaryAt(night: Instant): StoredSleepSummaryEntity? = error("injected read failure")
            }

            assertFailsWith<IllegalStateException> { SleepStore(db, failing).stage(completed, 560) }

            assertEquals(before, TableSnapshot.of(db))
        }
    }
}
