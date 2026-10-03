package io.github.opencircuit.store

import io.github.opencircuit.store.codec.Decoded
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The queue of sleep edits waiting to reach Health, one item per day: upstream
 * `PendingSleepReconcileStore` (ios/OpenCircuit/Store/LocalStore.swift:574-641 @ b1c2fdd) — `upsert`
 * replaces the item of the same day, `clear` removes it, `canRekey` refuses when both days hold an
 * item, `rekey` re-dates the old day's items. Days are compared in the zone given.
 *
 * A queue this build cannot read is never written over (PORTING D-172): `canRekey` is false, so a
 * night with such a queue is not moved, and `rekey` writes nothing.
 */
class PendingSleepReconcilesTest {

    private val now = Instant.parse("2025-06-20T08:00:00Z")
    private val utc = ZoneOffset.UTC
    private val paris = ZoneId.of("Europe/Paris")
    private val key = "sleep.edit.pending-reconcile.v2"

    private fun item(night: Instant, wakeHours: Long = 8) =
        PendingSleepReconcile(night, night, night, night.plusSeconds(wakeHours * 3_600), emptyList())

    /** Midnight UTC of June [day], 2025. */
    private fun d(day: Int): Instant = Instant.parse("2025-06-01T00:00:00Z").plusSeconds((day - 1) * 86_400L)

    private suspend fun PendingSleepReconciles.items() = assertIs<Decoded.Readable<List<PendingSleepReconcile>>>(all()).value

    @Test
    fun anUpsertReplacesTheItemOfTheSameDayInTheZoneGivenAndKeepsTheOthers() = runBlocking<Unit> {
        withInMemoryStore { db ->
            // 22:30Z on the 14th and 21:30Z on the 15th: two days in UTC, one (the 15th) in Paris.
            val late = Instant.parse("2025-06-14T22:30:00Z")
            val next = Instant.parse("2025-06-15T21:30:00Z")

            val queue = PendingSleepReconciles(db.kvDao())
            queue.upsert(item(late), utc, now)
            queue.upsert(item(next), utc, now)
            assertEquals(listOf(item(late), item(next)), queue.items())

            queue.clear(late, utc, now)
            queue.clear(next, utc, now)
            queue.upsert(item(late), paris, now)
            queue.upsert(item(next, wakeHours = 9), paris, now)
            assertEquals(listOf(item(next, wakeHours = 9)), queue.items())
        }
    }

    @Test
    fun aClearRemovesOnlyTheItemOfThatDayAndAClearOfAnEmptyQueueLeavesItEmpty() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val queue = PendingSleepReconciles(db.kvDao())
            queue.clear(d(14), utc, now)
            assertEquals(emptyList(), queue.items())

            queue.upsert(item(d(14)), utc, now)
            queue.upsert(item(d(15)), utc, now)
            queue.clear(d(14).plusSeconds(3_600), utc, now)

            assertEquals(listOf(item(d(15))), queue.items())
        }
    }

    @Test
    fun aRekeyIsRefusedOnlyWhenBothDaysHoldAnItemOrTheQueueCannotBeRead() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val queue = PendingSleepReconciles(db.kvDao())
            assertTrue(queue.canRekey(d(14), d(15), utc), "nothing queued")

            queue.upsert(item(d(14)), utc, now)
            assertTrue(queue.canRekey(d(14), d(15), utc), "only the old day")
            assertTrue(queue.canRekey(d(13), d(14), utc), "only the new day")

            queue.upsert(item(d(15)), utc, now)
            assertFalse(queue.canRekey(d(14), d(15), utc), "both days")

            db.kvDao().upsert(StoreKvEntity(key, "[]x", now))
            assertFalse(queue.canRekey(d(16), d(17), utc), "a queue this build cannot read")
        }
    }

    @Test
    fun aRekeyRedatesTheOldDaysItemToTheNewNightAndLeavesTheOthers() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val queue = PendingSleepReconciles(db.kvDao())
            queue.upsert(item(d(14)), utc, now)
            queue.upsert(item(d(17)), utc, now)

            queue.rekey(d(14).plusSeconds(60), d(15), utc, now)

            assertEquals(listOf(item(d(14)).copy(night = d(15)), item(d(17))), queue.items())
        }
    }

    @Test
    fun aRekeyWithNothingOnTheOldDayOrAQueueThisBuildCannotReadWritesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val queue = PendingSleepReconciles(kv)
            queue.rekey(d(14), d(15), utc, now)
            assertEquals(null, kv.get(key), "no queue is created")

            queue.upsert(item(d(17)), utc, now)
            val stored = kv.get(key)
            queue.rekey(d(14), d(15), utc, now.plusSeconds(60))
            assertEquals(stored, kv.get(key), "the stored queue and its time are untouched")

            kv.upsert(StoreKvEntity(key, "[{}]", now))
            queue.rekey(d(14), d(15), utc, now.plusSeconds(60))
            assertEquals(StoreKvEntity(key, "[{}]", now), kv.get(key))
        }
    }
}
