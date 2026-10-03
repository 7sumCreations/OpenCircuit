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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one-time move of stored nights from the day they started onto the day they ended: upstream
 * `rekeySleepNightsToWakeDay` (ios/OpenCircuit/Store/LocalStore.swift:2600-2709 @ b1c2fdd) and
 * `ensureNightKeyMigrated` (:1517-1541), run before every night save (:1589).
 *
 * The move is one transaction with its done-latch written inside it (PORTING D-170): any failure
 * leaves every row, value, watermark, risk key and the export watermark as they were, and no latch;
 * upstream reverses its `UserDefaults` moves by hand. The latch is written only when the store held
 * a night, and a save that finds the move unfinished throws, writing nothing.
 */
class NightKeyMigrationTest {

    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-20T08:00:00Z")
    private val earlier = Instant.parse("2025-06-19T08:00:00Z")
    private val latchKey = "store.rekeyedSleepNightsToWakeDay.v1"
    private val t0 = Instant.parse("2025-06-01T00:00:00Z")

    /** Midnight UTC of June [n], 2025. */
    private fun day(n: Int): Instant = t0.plusSeconds((n - 1) * 86_400L)

    /** A night stored under the day it started: in bed 22:00 on June [n] until 06:00 on the next day. */
    private fun bedtimeKeyed(n: Int) = StoredSleepSummaryEntity(
        night = day(n), asleepMin = 400 + n, inBedStart = day(n).plusSeconds(22 * 3_600L), inBedEnd = day(n + 1).plusSeconds(6 * 3_600L),
    )

    /** A night already under its wake day: in bed 23:00 on June [n] − 1 until 07:00 on June [n]. */
    private fun wakeKeyed(n: Int, night: Instant = day(n)) = StoredSleepSummaryEntity(
        night = night, asleepMin = 300 + n, inBedStart = day(n).minusSeconds(3_600), inBedEnd = day(n).plusSeconds(7 * 3_600L),
    )

    private fun summary(asleepMin: Long) = SleepStaging.Summary(
        inBed = Duration.ofMinutes(asleepMin + 30), awake = Duration.ofMinutes(30),
        light = Duration.ofMinutes(asleepMin), deep = Duration.ZERO, rem = Duration.ZERO,
    )

    /** An edited night's onset, undo stack, Health ids and mirror, both watermarks, a risk row and a queued item under [night]. */
    private suspend fun seedEverythingAt(db: StoreDatabase, night: Instant, riskDay: Instant) {
        val kv = db.kvDao()
        val s = night.epochSecond
        kv.upsert(StoreKvEntity("sleep.edit.onset.$s.0", night.plusSeconds(23 * 3_600L).toEpochMilli().toString(), earlier))
        kv.upsert(StoreKvEntity("sleep.edit.priorTimes.$s.0", "[{\"inBedStart\":1,\"sleepOnset\":2,\"sleepWake\":3}]", earlier))
        kv.upsert(StoreKvEntity("sleep.edit.hkuuids.$s.0", "[\"U-$s\"]", earlier))
        kv.upsert(StoreKvEntity("sleep.mirror.night.$s.0", "mirror of $s", earlier))
        db.sleepDao().insertCursor(StoredCursorEntity("hk:sleep-edit-leading:$s.0", night.plusSeconds(22 * 3_600L)))
        db.sleepDao().insertCursor(StoredCursorEntity("hk:sleep-edit-leading-asleep:$s.0", night.plusSeconds(23 * 3_600L)))
        db.userEntryDao().insertRisk(StoredHeadacheRiskEntity(day = riskDay, nightKey = night, index = 0.5))
        PendingSleepReconciles(kv).upsert(PendingSleepReconcile(night, night, night, night.plusSeconds(3_600), emptyList()), utc, earlier)
    }

    private suspend fun latch(db: StoreDatabase): String? = db.kvDao().get(latchKey)?.value

    @Test
    fun aBedtimeKeyedNightMovesToItsWakeDayWithEverythingKeptUnderItsKey() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(
                bedtimeKeyed(10).copy(
                    isManuallyEdited = true, editedInBedStart = day(10).plusSeconds(21 * 3_600L), editedInBedEnd = day(11).plusSeconds(7 * 3_600L),
                ),
            )
            seedEverythingAt(db, day(10), riskDay = day(11))
            db.sleepDao().insertCursor(StoredCursorEntity("export:sleepSessions", day(10)))
            val store = SleepStore(db)

            assertEquals(NightRekeyOutcome(examined = 1, moved = 1, skipped = 0), store.rekeySleepNightsToWakeDay(utc, now))

            assertNull(store.sleepSummary(day(10).plusSeconds(3_600), utc))
            val moved = assertNotNull(store.sleepSummary(day(11).plusSeconds(3_600), utc))
            assertEquals(410, moved.asleepMin)
            assertEquals(day(10).plusSeconds(23 * 3_600L), moved.editedOnset, "the edit's onset came with the night")
            val s = day(11).epochSecond
            val kv = db.kvDao()
            assertEquals(
                listOf("[{\"inBedStart\":1,\"sleepOnset\":2,\"sleepWake\":3}]", "[\"U-${day(10).epochSecond}\"]", "mirror of ${day(10).epochSecond}"),
                listOf("sleep.edit.priorTimes", "sleep.edit.hkuuids", "sleep.mirror.night").map { kv.get("$it.$s.0")?.value },
            )
            assertEquals(day(10).plusSeconds(22 * 3_600L), db.sleepDao().cursorAt("hk:sleep-edit-leading:$s.0")?.last)
            assertEquals(day(10).plusSeconds(23 * 3_600L), db.sleepDao().cursorAt("hk:sleep-edit-leading-asleep:$s.0")?.last)
            assertEquals(day(11), db.userEntryDao().riskOn(day(11))?.nightKey)
            assertEquals(listOf(day(11)), assertIs<Decoded.Readable<List<PendingSleepReconcile>>>(PendingSleepReconciles(kv).all()).value.map { it.night })
            assertEquals(day(11), db.sleepDao().cursorAt("export:sleepSessions")?.last)
            assertNull(latch(db), "the move on its own does not latch")
        }
    }

    @Test
    fun anEmptyStoreIsNotLatchedAndTheFirstStoreHoldingANightIsEvenWithNothingToMove() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            assertEquals(NightRekeyOutcome(0, 0, 0), store.rekeySleepNightsToWakeDay(utc, now))
            assertTrue(store.ensureNightKeyMigrated(utc, now))
            assertNull(latch(db))

            db.sleepDao().insertSummary(wakeKeyed(14))
            assertEquals(NightRekeyOutcome(1, 0, 0), store.rekeySleepNightsToWakeDay(utc, now))
            assertTrue(store.ensureNightKeyMigrated(utc, now))
            assertEquals("true", latch(db))
        }
    }

    @Test
    fun aLatchedStoreIsNotMovedAgainAndALatchHoldingAnythingElseCountsAsUnset() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(bedtimeKeyed(10))
            db.kvDao().upsert(StoreKvEntity(latchKey, "true", earlier))
            val store = SleepStore(db)

            assertTrue(store.ensureNightKeyMigrated(utc, now))
            assertEquals(listOf(day(10)), db.sleepDao().allSummaries().map { it.night })

            db.kvDao().upsert(StoreKvEntity(latchKey, "TRUE", earlier))
            assertTrue(store.ensureNightKeyMigrated(utc, now))
            assertEquals(listOf(day(11)), db.sleepDao().allSummaries().map { it.night })
            assertEquals("true", latch(db))
        }
    }

    @Test
    fun twoNightsBelongingToOneDayChangeNothingLatchNothingAndHoldEverySave() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(bedtimeKeyed(10))
            db.sleepDao().insertSummary(wakeKeyed(14))
            db.sleepDao().insertSummary(wakeKeyed(14, night = day(14).plusSeconds(5 * 3_600L)))
            val store = SleepStore(db)
            val before = TableSnapshot.of(db)

            assertFailsWith<SleepStoreException.NightKeyMigrationUnsafe> { store.rekeySleepNightsToWakeDay(utc, now) }
            assertFalse(store.ensureNightKeyMigrated(utc, now))
            assertFailsWith<SleepStoreException.NightKeyMigrationPending> {
                store.saveSleepSummary(summary(420), night = day(18), inBedStart = day(18).minusSeconds(3_600), inBedEnd = day(18).plusSeconds(7 * 3_600L), now = now, zone = utc)
            }

            assertEquals(before, TableSnapshot.of(db))
        }
    }

    @Test
    fun aNightWhoseWakeDayIsHeldByAnotherIsSkippedAndTheStoreStillLatched() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(bedtimeKeyed(10))
            db.sleepDao().insertSummary(wakeKeyed(11))
            val store = SleepStore(db)

            assertEquals(NightRekeyOutcome(2, 0, 1), store.rekeySleepNightsToWakeDay(utc, now))
            assertTrue(store.ensureNightKeyMigrated(utc, now))

            assertEquals(listOf(day(10) to 410, day(11) to 311), db.sleepDao().allSummaries().map { it.night to it.asleepMin })
            assertEquals("true", latch(db))
        }
    }

    @Test
    fun aNightWithAValueAlreadyUnderItsWakeDayStaysOnItsKeyWithItsOwnValues() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(bedtimeKeyed(10))
            db.kvDao().upsert(StoreKvEntity("sleep.edit.onset.${day(10).epochSecond}.0", "1", earlier))
            db.kvDao().upsert(StoreKvEntity("sleep.mirror.night.${day(11).epochSecond}.0", "an orphan", earlier))

            assertEquals(NightRekeyOutcome(1, 0, 1), SleepStore(db).rekeySleepNightsToWakeDay(utc, now))

            assertEquals(listOf(day(10)), db.sleepDao().allSummaries().map { it.night })
            assertEquals("1", db.kvDao().get("sleep.edit.onset.${day(10).epochSecond}.0")?.value)
            assertEquals("an orphan", db.kvDao().get("sleep.mirror.night.${day(11).epochSecond}.0")?.value)
        }
    }

    /**
     * Two bedtime-keyed nights back to back: the plan moves the newer first, freeing its day for the
     * older. When the newer is skipped (a value already under its wake day), its day stays held, and
     * the older night planned onto it is skipped too — never moved onto it.
     */
    @Test
    fun aNightLeftOnItsKeyStillHoldsItAgainstTheNightPlannedOntoIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(bedtimeKeyed(10))
            db.sleepDao().insertSummary(bedtimeKeyed(11))
            db.kvDao().upsert(StoreKvEntity("sleep.edit.onset.${day(12).epochSecond}.0", "an orphan", earlier))

            assertEquals(NightRekeyOutcome(2, 0, 2), SleepStore(db).rekeySleepNightsToWakeDay(utc, now))

            assertEquals(listOf(day(10) to 410, day(11) to 411), db.sleepDao().allSummaries().map { it.night to it.asleepMin })
        }
    }

    /** A queue this build cannot read is never rewritten, so the night it may belong to stays put. */
    @Test
    fun aPendingQueueThisBuildCannotReadKeepsTheNightOnItsKeyCountedSkippedAndTheStoreLatched() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(bedtimeKeyed(10))
            db.kvDao().upsert(StoreKvEntity(PendingSleepReconciles.KEY, "[{\"night\":", earlier))
            val store = SleepStore(db)

            assertEquals(NightRekeyOutcome(1, 0, 1), store.rekeySleepNightsToWakeDay(utc, now))
            assertTrue(store.ensureNightKeyMigrated(utc, now))

            assertEquals(listOf(day(10)), db.sleepDao().allSummaries().map { it.night })
            assertEquals(StoreKvEntity(PendingSleepReconciles.KEY, "[{\"night\":", earlier), db.kvDao().get(PendingSleepReconciles.KEY))
            assertEquals("true", latch(db))
        }
    }

    /**
     * Two nights, each with everything under its key, and the export watermark naming one. A failure
     * after the first night moved and the second's values moved — or at the export watermark, after
     * both — leaves every table exactly as it was, latches nothing, and the next run (the fault gone)
     * moves both.
     */
    @Test
    fun aFailureMidMoveRollsBackEveryRowValueWatermarkRiskKeyAndTheExportWatermark() = runBlocking<Unit> {
        val injections = listOf<(SleepDao) -> SleepDao>(
            { real ->
                var updates = 0
                object : SleepDao by real {
                    override suspend fun updateSummary(row: StoredSleepSummaryEntity) {
                        if (updates++ >= 1) error("injected failure at the second night's row")
                        real.updateSummary(row)
                    }
                }
            },
            { real ->
                object : SleepDao by real {
                    override suspend fun updateCursor(row: StoredCursorEntity) = error("injected failure at the export watermark")
                }
            },
        )
        for ((i, inject) in injections.withIndex()) {
            withInMemoryStore { db ->
                db.sleepDao().insertSummary(bedtimeKeyed(10).copy(isManuallyEdited = true))
                db.sleepDao().insertSummary(bedtimeKeyed(12).copy(isManuallyEdited = true))
                seedEverythingAt(db, day(10), riskDay = day(11))
                seedEverythingAt(db, day(12), riskDay = day(13))
                db.sleepDao().insertCursor(StoredCursorEntity("export:sleepSessions", day(12)))
                val failing = SleepStore(db, inject(db.sleepDao()), db.kvDao())
                val before = TableSnapshot.of(db)

                assertFailsWith<IllegalStateException>("injection $i") { failing.rekeySleepNightsToWakeDay(utc, now) }
                assertEquals(before, TableSnapshot.of(db), "injection $i: the rekey")
                assertFalse(failing.ensureNightKeyMigrated(utc, now), "injection $i")
                assertEquals(before, TableSnapshot.of(db), "injection $i: the latched run")

                assertTrue(SleepStore(db).ensureNightKeyMigrated(utc, now))
                assertEquals(listOf(day(11), day(13)), db.sleepDao().allSummaries().map { it.night })
                assertEquals(day(13), db.sleepDao().cursorAt("export:sleepSessions")?.last)
                assertEquals("true", latch(db))
            }
        }
    }

    @Test
    fun aSaveWhileTheMoveOfStoredNightsCannotCompleteThrowsPendingAndWritesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(bedtimeKeyed(10))
            val real = db.sleepDao()
            val failing = object : SleepDao by real {
                override suspend fun updateSummary(row: StoredSleepSummaryEntity) = error("injected failure")
            }
            val before = TableSnapshot.of(db)

            val thrown = assertFailsWith<SleepStoreException.NightKeyMigrationPending> {
                SleepStore(db, failing, db.kvDao()).saveSleepSummary(
                    summary(420), night = day(18), inBedStart = day(18).minusSeconds(3_600), inBedEnd = day(18).plusSeconds(7 * 3_600L),
                    now = now, zone = utc,
                )
            }

            assertEquals("injected failure", thrown.cause?.message)
            assertEquals(before, TableSnapshot.of(db))
        }
    }

    @Test
    fun aSaveMovesTheStoredNightsFirstThenStoresItsOwn() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(bedtimeKeyed(10))
            val store = SleepStore(db)

            val outcome = store.saveSleepSummary(
                summary(420), night = day(18), inBedStart = day(18).minusSeconds(3_600), inBedEnd = day(18).plusSeconds(7 * 3_600L),
                now = now, zone = utc,
            )

            assertEquals(SleepPersistOutcome.INSERTED, outcome)
            assertEquals(listOf(day(11), day(18)), db.sleepDao().allSummaries().map { it.night })
            assertEquals("true", latch(db))
        }
    }
}
