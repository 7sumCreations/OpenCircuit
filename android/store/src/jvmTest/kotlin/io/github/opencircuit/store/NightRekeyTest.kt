package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepNightRekeyPlan
import io.github.opencircuit.store.codec.Decoded
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Everything a stored night carries under its key, moved with it to a new key: upstream
 * `canRenameNightScopedOverlays` / `renameNightScopedOverlays` (ios/OpenCircuit/Store/
 * LocalStore.swift:2474-2505 @ b1c2fdd), the cursor rename (:2558-2571), the headache night key
 * (:2542-2545) and the export watermark (:2527-2534).
 *
 * All or nothing per night: any destination already holding a value refuses the whole move —
 * it is the destination that matters, not the source (upstream :362-372), since a night with
 * nothing of its own would otherwise inherit an orphan.
 */
class NightRekeyTest {

    private val now = Instant.parse("2025-06-20T08:00:00Z")
    private val earlier = Instant.parse("2025-06-19T08:00:00Z")
    private val utc = ZoneOffset.UTC
    private val from = Instant.parse("2025-06-14T00:00:00Z") // 1749859200
    private val to = Instant.parse("2025-06-15T00:00:00Z") // 1749945600

    private val kvKeys = listOf("sleep.edit.onset", "sleep.edit.hkuuids", "sleep.edit.priorTimes", "sleep.mirror.night")
    private val cursorKeys = listOf("hk:sleep-edit-leading:", "hk:sleep-edit-leading-asleep:")

    private fun risk(day: String, nightKey: Instant) =
        StoredHeadacheRiskEntity(day = Instant.parse(day), nightKey = nightKey, index = 0.4)

    private suspend fun seedEverythingAt(db: StoreDatabase, night: Instant) {
        val kv = db.kvDao()
        kv.upsert(StoreKvEntity("sleep.edit.onset.${night.epochSecond}.0", "1749880800000", earlier))
        kv.upsert(StoreKvEntity("sleep.edit.hkuuids.${night.epochSecond}.0", "[\"A-1\",\"B-2\"]", earlier))
        kv.upsert(StoreKvEntity("sleep.edit.priorTimes.${night.epochSecond}.0", "[{\"inBedStart\":1,\"sleepOnset\":2,\"sleepWake\":3}]", earlier))
        kv.upsert(StoreKvEntity("sleep.mirror.night.${night.epochSecond}.0", "{opaque mirror text", earlier))
        db.sleepDao().insertCursor(StoredCursorEntity("hk:sleep-edit-leading:${night.epochSecond}.0", Instant.parse("2025-06-14T22:00:00Z")))
        db.sleepDao().insertCursor(StoredCursorEntity("hk:sleep-edit-leading-asleep:${night.epochSecond}.0", Instant.parse("2025-06-14T22:30:00Z")))
        PendingSleepReconciles(kv).upsert(PendingSleepReconcile(night, night, night, night.plusSeconds(3_600), emptyList()), utc, earlier)
    }

    @Test
    fun aRenameMovesEveryValueCursorRiskRowAndQueuedItemOfTheNightAsStoredAndLeavesTheOthers() = runBlocking<Unit> {
        withInMemoryStore { db ->
            seedEverythingAt(db, from)
            val other = Instant.parse("2025-06-17T00:00:00Z")
            db.kvDao().upsert(StoreKvEntity("sleep.edit.onset.${other.epochSecond}.0", "1750140000000", earlier))
            db.sleepDao().insertCursor(StoredCursorEntity("1", Instant.parse("2025-06-14T05:00:00Z")))
            db.userEntryDao().insertRisk(risk("2025-06-14T00:00:00Z", from))
            db.userEntryDao().insertRisk(risk("2025-06-13T00:00:00Z", from))
            db.userEntryDao().insertRisk(risk("2025-06-17T00:00:00Z", other))
            val rekey = NightRekey(db.sleepDao(), db.kvDao())

            assertTrue(rekey.canRename(from, to, utc))
            rekey.rename(from, to, utc, now)

            val kv = db.kvDao()
            assertEquals(
                listOf("1749880800000", "[\"A-1\",\"B-2\"]", "[{\"inBedStart\":1,\"sleepOnset\":2,\"sleepWake\":3}]", "{opaque mirror text"),
                kvKeys.map { kv.get("$it.1749945600.0")?.value },
            )
            assertEquals(listOf(null, null, null, null), kvKeys.map { kv.get("$it.1749859200.0") })
            assertEquals("1750140000000", kv.get("sleep.edit.onset.${other.epochSecond}.0")?.value)
            assertEquals(
                listOf(Instant.parse("2025-06-14T22:00:00Z"), Instant.parse("2025-06-14T22:30:00Z")),
                cursorKeys.map { db.sleepDao().cursorAt("${it}1749945600.0")?.last },
            )
            assertEquals(listOf(null, null), cursorKeys.map { db.sleepDao().cursorAt("${it}1749859200.0") })
            assertEquals(Instant.parse("2025-06-14T05:00:00Z"), db.sleepDao().cursorAt("1")?.last)
            assertEquals(
                listOf(to, to, other),
                listOf("2025-06-13T00:00:00Z", "2025-06-14T00:00:00Z", "2025-06-17T00:00:00Z")
                    .map { db.userEntryDao().riskOn(Instant.parse(it))?.nightKey },
            )
            assertEquals(listOf(to), assertIs<Decoded.Readable<List<PendingSleepReconcile>>>(PendingSleepReconciles(kv).all()).value.map { it.night })
        }
    }

    @Test
    fun aRenameOfANightWithNothingUnderItsKeyWritesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val rekey = NightRekey(db.sleepDao(), db.kvDao())
            assertTrue(rekey.canRename(from, to, utc))
            rekey.rename(from, to, utc, now)

            assertEquals(listOf("0"), db.queryRaw("SELECT COUNT(*) FROM store_kv"))
            assertEquals(listOf("0"), db.queryRaw("SELECT COUNT(*) FROM stored_cursor"))
        }
    }

    @Test
    fun aValueAlreadyUnderTheNewKeyRefusesTheMoveEvenWhenTheNightHasNoneOfItsOwn() = runBlocking<Unit> {
        for (prefix in kvKeys) {
            withInMemoryStore { db ->
                db.kvDao().upsert(StoreKvEntity("$prefix.${to.epochSecond}.0", "an orphan", earlier))
                assertFalse(NightRekey(db.sleepDao(), db.kvDao()).canRename(from, to, utc), prefix)
            }
        }
    }

    @Test
    fun aWatermarkAlreadyUnderTheNewKeyRefusesTheMoveOnlyWhenTheNightHasOneToMove() = runBlocking<Unit> {
        for (prefix in cursorKeys) {
            withInMemoryStore { db ->
                val dao = db.sleepDao()
                dao.insertCursor(StoredCursorEntity("$prefix${to.epochSecond}.0", Instant.parse("2025-06-15T22:00:00Z")))
                val rekey = NightRekey(dao, db.kvDao())
                assertTrue(rekey.canRename(from, to, utc), "$prefix: nothing of its own to move")

                dao.insertCursor(StoredCursorEntity("$prefix${from.epochSecond}.0", Instant.parse("2025-06-14T22:00:00Z")))
                assertFalse(rekey.canRename(from, to, utc), "$prefix: two watermarks for one night")
            }
        }
    }

    @Test
    fun aQueuedItemOnBothDaysOrAQueueThisBuildCannotReadRefusesTheMove() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val queue = PendingSleepReconciles(db.kvDao())
            val rekey = NightRekey(db.sleepDao(), db.kvDao())
            queue.upsert(PendingSleepReconcile(from, from, from, from, emptyList()), utc, earlier)
            assertTrue(rekey.canRename(from, to, utc))
            queue.upsert(PendingSleepReconcile(to, to, to, to, emptyList()), utc, earlier)
            assertFalse(rekey.canRename(from, to, utc))

            db.kvDao().upsert(StoreKvEntity(PendingSleepReconciles.KEY, "not a queue", earlier))
            assertFalse(rekey.canRename(from, to, utc))
        }
    }

    /** A move would leave two frozen scores claiming one night, which the score's own guard forbids. */
    @Test
    fun riskRowsAlreadyNamingBothNightsRefuseTheMove() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val rekey = NightRekey(db.sleepDao(), db.kvDao())
            db.userEntryDao().insertRisk(risk("2025-06-15T00:00:00Z", to))
            assertTrue(rekey.canRename(from, to, utc), "only the new night is scored")

            db.userEntryDao().insertRisk(risk("2025-06-14T00:00:00Z", from))
            assertFalse(rekey.canRename(from, to, utc))
        }
    }

    @Test
    fun aFailingReadFailsTheCheckRatherThanAnsweringIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val failingCursor = object : SleepDao by db.sleepDao() {
                override suspend fun cursorAt(kindRaw: String): StoredCursorEntity? = error("injected read failure")
            }
            assertFailsWith<IllegalStateException> { NightRekey(failingCursor, db.kvDao()).canRename(from, to, utc) }

            val failingKv = object : KvDao by db.kvDao() {
                override suspend fun get(key: String): StoreKvEntity? = error("injected read failure")
            }
            assertFailsWith<IllegalStateException> { NightRekey(db.sleepDao(), failingKv).canRename(from, to, utc) }
        }
    }

    @Test
    fun theExportWatermarkFollowsOnlyAMovedNightItNames() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val dao = db.sleepDao()
            val rekey = NightRekey(dao, db.kvDao())
            rekey.advanceExportWatermark(listOf(SleepNightRekeyPlan.Move(from, to)))
            assertNull(dao.cursorAt("export:sleepSessions"), "no watermark is created")

            val older = Instant.parse("2025-06-12T00:00:00Z")
            dao.insertCursor(StoredCursorEntity("export:sleepSessions", older))
            rekey.advanceExportWatermark(listOf(SleepNightRekeyPlan.Move(from, to)))
            assertEquals(older, dao.cursorAt("export:sleepSessions")?.last)

            rekey.advanceExportWatermark(listOf(SleepNightRekeyPlan.Move(from, to), SleepNightRekeyPlan.Move(older, older.plusSeconds(86_400))))
            assertEquals(Instant.parse("2025-06-13T00:00:00Z"), dao.cursorAt("export:sleepSessions")?.last)
        }
    }
}
