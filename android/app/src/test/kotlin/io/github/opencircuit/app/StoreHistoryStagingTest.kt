package io.github.opencircuit.app

import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.app.sync.SyncEvidence
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which nights a commit stages: only nights the ring is done with (PORTING D-269), and only from
 * records the commit released — never a night whose records are still held back in the journal
 * (D-267). A night left out is staged by a later commit, whole.
 */
class StoreHistoryStagingTest {

    private val everything = Instant.parse("2026-06-01T00:00:00Z") to Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun aNightStillUnderWayWhenTheSyncRanIsStagedByTheNextSyncWhole() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val store = StoreHistory({ db }, TEST_RING_ID, { BacklogPages.ZONE })
            // A sync at 04:10 on the last night's wake day: the ring has written up to 04:00.
            val cut = Instant.parse("2026-06-14T04:00:00Z")
            val (before, after) = BacklogPages.records.partition { !BacklogPages.date(it).isAfter(cut) }
            store.journal(BacklogPages.pages(before))

            val first = store.commit(Instant.parse("2026-06-14T04:10:00Z"), CommitPlanner.Drained.Everything, evidence = SLEEP_COMPLETE)

            assertEquals(6, first.nightsStaged)
            assertEquals(0, first.nightsWaiting, "a night under way is not waiting: it is not complete yet")
            assertEquals(BacklogPages.WAKE_DAYS.take(6), SleepStore(db).sleepSummaries(everything.first, everything.second).map { it.night })

            // The next sync brings the rest of the night and the morning after it.
            store.journal(BacklogPages.pages(after))
            val next = store.commit(STAGING_NOW, CommitPlanner.Drained.Everything, evidence = SyncEvidence(HistoryChannelOutcome.COMPLETE, sleepRecordsAdded = after.size))

            assertEquals(1, next.nightsStaged)
            val last = SleepStore(db).sleepSummaries(everything.first, everything.second).last()
            assertEquals(BacklogPages.WAKE_DAYS.last(), last.night)
            assertTrue(last.inBedEnd > cut, "staged whole, past where the first sync stopped")
        } finally {
            db.close()
        }
    }

    @Test
    fun aNightWhoseRecordsAreHeldBackIsNotStagedUntilTheyAreReleased() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val store = StoreHistory({ db }, TEST_RING_ID, { BacklogPages.ZONE })
            store.journal(BacklogPages.pages())
            // The all-day channel was cut in the middle of the fifth night: nothing past it is released.
            val bound = BacklogPages.records.last { !BacklogPages.date(it).isAfter(Instant.parse("2026-06-12T03:00:00Z")) }

            val first = store.commit(STAGING_NOW, CommitPlanner.Drained.Through(BacklogPages.counter(bound)), evidence = SLEEP_COMPLETE)

            assertTrue(first.heldBack > 0)
            assertEquals(4, first.nightsStaged)
            assertEquals(BacklogPages.WAKE_DAYS.take(4), SleepStore(db).sleepSummaries(everything.first, everything.second).map { it.night })
            val stagedThrough = assertNotNull(BlobStore(db).loadEpochArchive(TEST_RING_ID).marks.stagedThrough)
            assertTrue(stagedThrough < BacklogPages.WAKE_DAYS[4])

            val next = store.commit(STAGING_NOW, CommitPlanner.Drained.Everything, evidence = SLEEP_COMPLETE)

            assertEquals(3, next.nightsStaged)
            assertEquals(BacklogPages.WAKE_DAYS, SleepStore(db).sleepSummaries(everything.first, everything.second).map { it.night })
        } finally {
            db.close()
        }
    }
}
