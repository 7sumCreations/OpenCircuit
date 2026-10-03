package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.containsAssertedTime
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.PendingSleepReconcileCodec
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Port of upstream ios/OpenCircuitTests/PendingSleepReconcileKeyTests.swift (@ b1c2fdd).
 *
 * Upstream's queue moved from `sleep.edit.pending-reconcile.v1` to `…v2` because a queue written
 * before segments carried their provenance decodes every minute as measured (:52), and the old
 * queue is never read (:64) and dropped on first access (:70). This build never wrote `…v1`, so the
 * two tests about it are adapted (PORTING D-171): a value under the old key is never read, and a
 * queue this build cannot read is reported — never read as empty — and kept as stored, byte for
 * byte, by every operation on the queue.
 */
class PendingSleepReconcileKeyTest {

    private val v1Key = "sleep.edit.pending-reconcile.v1"
    private val v2Key = "sleep.edit.pending-reconcile.v2"
    private val night = Instant.ofEpochSecond(1_750_000_000)
    private val now = Instant.parse("2025-06-16T08:00:00Z")
    private val utc = ZoneOffset.UTC

    /** Exactly what a pre-provenance build wrote: segments with no `provenance` key (in this store's form). */
    private val legacyQueue =
        """[{"night":1750000000000,"inBedStart":1750000000000,"sleepOnset":1750000000000,"sleepWake":1750028800000,""" +
            """"segments":[{"start":1750000000000,"end":1750028800000,"stage":"asleepCore"}]}]"""

    // upstream :52
    @Test
    fun theLegacyBlobReallyWouldDecodeAsFullyMeasured() {
        val decoded = assertIs<Decoded.Readable<List<PendingSleepReconcile>>>(PendingSleepReconcileCodec.decode(legacyQueue)).value
        assertEquals(SleepProvenance.MEASURED, decoded.first().segments.first().provenance)
        assertFalse(decoded.first().segments.containsAssertedTime, "8 hours of possibly-invented sleep, indistinguishable from measured")
    }

    // upstream :64, adapted
    @Test
    fun aQueueUnderTheOlderKeyIsNeverReadAndOneThisBuildCannotReadIsReportedNotReadAsEmpty() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val queue = PendingSleepReconciles(kv)
            kv.upsert(StoreKvEntity(v1Key, legacyQueue, now))
            assertEquals(Decoded.Readable(emptyList()), queue.all())

            kv.upsert(StoreKvEntity(v2Key, "{not a queue", now))
            assertEquals("{not a queue", assertIs<Decoded.Unreadable>(queue.all()).raw)
        }
    }

    // upstream :70, adapted
    @Test
    fun aQueueThisBuildCannotReadIsKeptAsStoredByEveryOperationOnTheQueue() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val queue = PendingSleepReconciles(kv)
            kv.upsert(StoreKvEntity(v1Key, legacyQueue, now))
            kv.upsert(StoreKvEntity(v2Key, "[{\"night\":1}]", now))
            val item = PendingSleepReconcile(night, night, night, night.plusSeconds(8 * 3_600), emptyList())

            queue.all()
            assertFalse(queue.canRekey(night, night.plusSeconds(86_400), utc))
            queue.rekey(night, night.plusSeconds(86_400), utc, now.plusSeconds(60))
            assertFails { queue.upsert(item, utc, now.plusSeconds(60)) }
            assertFails { queue.clear(night, utc, now.plusSeconds(60)) }

            assertEquals(StoreKvEntity(v2Key, "[{\"night\":1}]", now), kv.get(v2Key))
            assertEquals(StoreKvEntity(v1Key, legacyQueue, now), kv.get(v1Key))
        }
    }

    // upstream :77
    @Test
    fun thisBuildsOwnQueueStillRoundTripsUnderTheNewKey() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val queue = PendingSleepReconciles(kv)
            val item = PendingSleepReconcile(
                night = night, inBedStart = night, sleepOnset = night,
                sleepWake = night.plusSeconds(8 * 3_600),
                segments = listOf(SleepSegment(night, night.plusSeconds(3_600), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED)),
            )
            queue.upsert(item, utc, now)

            assertNotNull(kv.get(v2Key))
            assertEquals(Decoded.Readable(listOf(item)), queue.all())
            assertEquals(
                SleepProvenance.ASSERTED,
                queue.all().valueOrNull()?.first()?.segments?.first()?.provenance,
                "a queued item now carries its provenance across the flush boundary",
            )

            queue.clear(night, utc, now)
            assertEquals(Decoded.Readable(emptyList()), queue.all())
        }
    }
}
