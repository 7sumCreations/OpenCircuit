package io.github.opencircuit.app

import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.app.sync.SyncEvidence
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An edited night's provenance is re-derived against the archive once per commit that saved a night
 * (upstream runs it after each staged save, `RingSession.swift:2075` @ b1c2fdd, when the archive is
 * at its widest) — once for seven nights, not seven times, and not at all when no night was saved.
 */
class RederiveCalledOnceTest {

    @Test
    fun onceForACommitThatSavedNightsAndNeverForOneThatSavedNone() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val writer = CountingNightWriter(db)
            val store = StoreHistory({ db }, TEST_RING_ID, { BacklogPages.ZONE }, nights = { writer })
            store.journal(BacklogPages.pages())

            assertEquals(7, store.commit(STAGING_NOW, CommitPlanner.Drained.Everything, evidence = SLEEP_COMPLETE).nightsStaged)
            assertEquals(1, writer.rederiveCalls, "seven nights saved, one re-derivation")

            // A sync with nothing the gate accepts: no staging, no re-derivation.
            store.journal(BacklogPages.pages(recordsAfterTheBacklog(6)))
            store.commit(STAGING_NOW, CommitPlanner.Drained.Everything, evidence = SyncEvidence.NONE)
            assertEquals(1, writer.rederiveCalls)

            // A complete sync with fresh records but no night left to stage: none either.
            store.journal(BacklogPages.pages(recordsAfterTheBacklog(12).drop(6)))
            val idle = store.commit(STAGING_NOW, CommitPlanner.Drained.Everything, evidence = SyncEvidence(HistoryChannelOutcome.COMPLETE, sleepRecordsAdded = 6))
            assertEquals(0, idle.nightsStaged)
            assertEquals(1, writer.rederiveCalls)
        } finally {
            db.close()
        }
    }

    @Test
    fun aCommitWhoseFirstNightIsDeferredDoesNotReDerive() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val writer = CountingNightWriter(db).apply { savesBeforeDeferral = 0 }
            val store = StoreHistory({ db }, TEST_RING_ID, { BacklogPages.ZONE }, nights = { writer })
            store.journal(BacklogPages.pages())

            val result = store.commit(STAGING_NOW, CommitPlanner.Drained.Everything, evidence = SLEEP_COMPLETE)

            assertEquals(0, result.nightsStaged)
            assertEquals(7, result.nightsWaiting)
            assertEquals(0, writer.rederiveCalls)
        } finally {
            db.close()
        }
    }
}
