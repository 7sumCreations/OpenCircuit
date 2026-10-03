package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The move of stored nights onto their wake day judges days in the zone it is given — every day, key
 * and value of one run in the same zone (upstream mixes the `calendar` it is passed with
 * `Calendar.current`, ios/OpenCircuit/Store/LocalStore.swift:2600-2709 @ b1c2fdd).
 */
class NightKeyMigrationZoneTest {

    private val now = Instant.parse("2025-06-20T08:00:00Z")

    /**
     * São Paulo skipped its midnight on 2018-11-04: that day starts at 01:00 local, 03:00Z. A night
     * stored under its bedtime's day (2018-11-03, 03:00Z) and ending that morning moves to 03:00Z on
     * the 4th, its edit's onset with it, and is found from any time of that day in that zone.
     */
    @Test
    fun aNightEndingOnADayWithoutAMidnightMovesToTheFirstInstantOfThatDayWithItsEditsOnset() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val saoPaulo = ZoneId.of("America/Sao_Paulo")
            val stored = Instant.parse("2018-11-03T03:00:00Z")
            val onset = Instant.parse("2018-11-04T01:30:00Z")
            db.sleepDao().insertSummary(
                StoredSleepSummaryEntity(
                    night = stored, asleepMin = 420, inBedStart = Instant.parse("2018-11-04T01:00:00Z"), inBedEnd = Instant.parse("2018-11-04T09:00:00Z"),
                    isManuallyEdited = true, editedInBedStart = Instant.parse("2018-11-04T00:50:00Z"), editedInBedEnd = Instant.parse("2018-11-04T09:00:00Z"),
                ),
            )
            db.kvDao().upsert(StoreKvEntity("sleep.edit.onset.1541214000.0", onset.toEpochMilli().toString(), now))
            val store = SleepStore(db)

            assertTrue(store.ensureNightKeyMigrated(saoPaulo, now))

            assertEquals(listOf(Instant.parse("2018-11-04T03:00:00Z")), db.sleepDao().allSummaries().map { it.night })
            assertEquals(onset.toEpochMilli().toString(), db.kvDao().get("sleep.edit.onset.1541300400.0")?.value)
            assertNull(db.kvDao().get("sleep.edit.onset.1541214000.0"))
            val found = assertNotNull(store.sleepSummary(Instant.parse("2018-11-05T01:00:00Z"), saoPaulo)) // 23:00 on the 4th, local
            assertEquals(onset, found.editedOnset)
        }
    }

    /**
     * A night stored under UTC midnight, 15:06Z–23:06Z on 2025-06-15: in UTC it ends on its own key day
     * and nothing moves; in Bangkok (UTC+7) it ends at 06:06 on the 16th and moves to that day's start,
     * 17:00Z on the 15th. Its values move from the key the row was stored under — not from the start
     * of its day in the zone now given, which would miss them (PORTING D-164).
     */
    @Test
    fun theSameStoredNightMovesOnlyInTheZoneWhereItEndsOnAnotherDayAndItsValuesComeFromItsOwnKey() = runBlocking<Unit> {
        val row = StoredSleepSummaryEntity(
            night = Instant.parse("2025-06-15T00:00:00Z"), asleepMin = 420,
            inBedStart = Instant.ofEpochSecond(1_750_000_000), inBedEnd = Instant.ofEpochSecond(1_750_000_000 + 8 * 3_600),
        )
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(row)
            assertEquals(NightRekeyOutcome(1, 0, 0), SleepStore(db).rekeySleepNightsToWakeDay(ZoneOffset.UTC, now))
        }
        withInMemoryStore { db ->
            db.sleepDao().insertSummary(row)
            db.kvDao().upsert(StoreKvEntity("sleep.mirror.night.1749945600.0", "mirror", now))

            assertEquals(NightRekeyOutcome(1, 1, 0), SleepStore(db).rekeySleepNightsToWakeDay(ZoneId.of("Asia/Bangkok"), now))

            assertEquals(listOf(Instant.parse("2025-06-15T17:00:00Z")), db.sleepDao().allSummaries().map { it.night })
            assertEquals("mirror", db.kvDao().get("sleep.mirror.night.1750006800.0")?.value)
            assertNull(db.kvDao().get("sleep.mirror.night.1749945600.0"))
        }
    }
}
