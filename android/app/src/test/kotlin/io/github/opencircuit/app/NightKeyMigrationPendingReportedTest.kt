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
 * The store holds every night's save while its one-time move of stored nights onto their wake day
 * cannot complete (`NightKeyMigrationPending`) — a store-wide state, so the commit stops at the first
 * deferred night: the nights before it are saved, it and every later night wait, counted, and stay in
 * the archive (`stagedThrough` stops at the last night saved), to be staged by a later sync. Never
 * dropped, never skipped over.
 */
class NightKeyMigrationPendingReportedTest {

    private val everything = Instant.parse("2026-06-01T00:00:00Z") to Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun theFirstDeferredNightStopsTheCommitAndEveryLaterNightWaitsForTheNextSync() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val writer = CountingNightWriter(db).apply { savesBeforeDeferral = 3 }
            val store = StoreHistory({ db }, TEST_RING_ID, { BacklogPages.ZONE }, nights = { writer })
            store.journal(BacklogPages.pages())

            val first = store.commit(STAGING_NOW, CommitPlanner.Drained.Everything, evidence = SLEEP_COMPLETE)

            assertEquals(3, first.nightsStaged)
            assertEquals(4, first.nightsWaiting, "the deferred night and the three after it")
            assertEquals(1, writer.deferred, "the commit stopped at the first deferral — it did not try the later nights")
            val stored = SleepStore(db).sleepSummaries(everything.first, everything.second)
            assertEquals(BacklogPages.WAKE_DAYS.take(3), stored.map { it.night })
            val archive = BlobStore(db).loadEpochArchive(TEST_RING_ID)
            val stagedThrough = assertNotNull(archive.marks.stagedThrough)
            assertTrue(stagedThrough < BacklogPages.WAKE_DAYS[3], "staged through night 3 only")
            assertEquals(3654, archive.records.size, "the waiting nights are all still in the archive")

            // The move completes; the next sync brings one fresh page and stages the four that waited.
            writer.savesBeforeDeferral = null
            store.journal(BacklogPages.pages(recordsAfterTheBacklog(6)))
            val next = store.commit(STAGING_NOW, CommitPlanner.Drained.Everything, evidence = SyncEvidence(HistoryChannelOutcome.COMPLETE, sleepRecordsAdded = 6))

            assertEquals(4, next.nightsStaged)
            assertEquals(0, next.nightsWaiting)
            assertEquals(BacklogPages.WAKE_DAYS, SleepStore(db).sleepSummaries(everything.first, everything.second).map { it.night })
        } finally {
            db.close()
        }
    }
}
