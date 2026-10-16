package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The public consumer of the queue of edited nights waiting for their Health write (upstream
 * ios/OpenCircuit/Store/LocalStore.swift `setPendingSleepReconcile` / `pendingSleepReconciles` /
 * `clearPendingSleepReconcileIfUnchanged` :1312-1334 @ b1c2fdd): read in night order, cleared only
 * when the stored item is still the one that was written (a newer edit of the same night queued
 * meanwhile is kept), and a queue this build cannot read is surfaced, never read as empty.
 */
class ReconcileQueueConsumerTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2026-06-30T09:00:00Z")
    private fun day(d: Int): Instant = Instant.parse("2026-06-%02dT00:00:00Z".format(d))

    private fun item(d: Int, wakeHour: Int = 7) = PendingSleepReconcile(
        night = day(d),
        inBedStart = day(d).minusSeconds(7_200),
        sleepOnset = day(d).minusSeconds(3_600),
        sleepWake = day(d).plusSeconds(wakeHour * 3_600L),
        segments = listOf(
            SleepSegment(day(d).minusSeconds(7_200), day(d).plusSeconds(wakeHour * 3_600L), SleepStage.IN_BED),
            SleepSegment(day(d).minusSeconds(3_600), day(d).plusSeconds(wakeHour * 3_600L), SleepStage.ASLEEP_CORE),
        ),
    )

    @Test
    fun theQueueIsReadInNightOrder() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.setPendingSleepReconcile(item(28), zone, now)
            store.setPendingSleepReconcile(item(26), zone, now)
            store.setPendingSleepReconcile(item(27), zone, now)

            assertEquals(listOf(day(26), day(27), day(28)), store.pendingSleepReconciles().map { it.night })
        }
    }

    @Test
    fun anItemStillAsReadIsClearedAndOnlyItsDay() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.setPendingSleepReconcile(item(26), zone, now)
            store.setPendingSleepReconcile(item(27), zone, now)
            val read = store.pendingSleepReconciles().first()

            assertTrue(store.clearPendingSleepReconcileIfUnchanged(read, zone, now))

            assertEquals(listOf(day(27)), store.pendingSleepReconciles().map { it.night })
        }
    }

    @Test
    fun aNewerEditOfTheSameNightQueuedWhileTheWriteRanIsKept() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.setPendingSleepReconcile(item(26), zone, now)
            val read = store.pendingSleepReconciles().single()
            // The wearer edits the same night again while its Health write is running.
            store.setPendingSleepReconcile(item(26, wakeHour = 8), zone, now)

            assertFalse(store.clearPendingSleepReconcileIfUnchanged(read, zone, now))

            assertEquals(listOf(item(26, wakeHour = 8)), store.pendingSleepReconciles(), "the lost-update guard keeps the newer edit")
        }
    }

    @Test
    fun anUnreadableQueueIsSurfacedAndNeverWrittenOver() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            db.kvDao().upsert(StoreKvEntity(PendingSleepReconciles.KEY, "{not a queue", now))

            assertFailsWith<SleepStoreException.UnreadablePendingReconcile> { store.pendingSleepReconciles() }
            assertFailsWith<SleepStoreException.UnreadablePendingReconcile> { store.clearPendingSleepReconcileIfUnchanged(item(26), zone, now) }
            assertFailsWith<SleepStoreException.UnreadablePendingReconcile> { store.setPendingSleepReconcile(item(26), zone, now) }
            assertEquals("{not a queue", db.kvDao().get(PendingSleepReconciles.KEY)?.value)
        }
    }
}
