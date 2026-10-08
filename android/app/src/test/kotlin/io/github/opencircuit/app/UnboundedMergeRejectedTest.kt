package io.github.opencircuit.app

import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PORTING D-44 on the staging path: one record with a garbage far-future counter (the 1-byte XOR
 * trailer lets ~1 in 256 garbage frames through) is dropped by the merge's bound and counted — it
 * neither becomes the archive's newest record, from which the 14-day window would be measured and
 * the week's nights pruned, nor makes the week's last night look finished.
 */
class UnboundedMergeRejectedTest {

    @Test
    fun aFarFutureRecordIsDroppedCountedAndPrunesNoNight() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val store = StoreHistory({ db }, TEST_RING_ID, { BacklogPages.ZONE })
            // The backlog's last record with its counter moved to 2090-01-01 (raw path).
            val far = Instant.parse("2090-01-01T00:00:00Z").epochSecond - Command.SYNC_EPOCH
            val garbage = BacklogPages.records.last().copyOf().also { r ->
                r[0] = (far ushr 24).toByte(); r[1] = (far ushr 16).toByte(); r[2] = (far ushr 8).toByte(); r[3] = far.toByte()
            }
            store.journal(BacklogPages.pages(BacklogPages.records + listOf(garbage)))

            val result = store.commit(STAGING_NOW, CommitPlanner.Drained.Everything, evidence = SLEEP_COMPLETE)

            assertEquals(1, result.droppedAfterBound)
            assertEquals(7, result.nightsStaged)
            val archive = BlobStore(db).loadEpochArchive(TEST_RING_ID).records
            assertEquals(3654, archive.size)
            assertEquals(BacklogPages.counter(BacklogPages.records.last()), archive.last().counter)
            assertEquals(
                BacklogPages.WAKE_DAYS,
                SleepStore(db).sleepSummaries(Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z")).map { it.night },
            )
        } finally {
            db.close()
        }
    }
}
