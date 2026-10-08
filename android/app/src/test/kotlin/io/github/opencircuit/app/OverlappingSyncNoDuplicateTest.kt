package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.HistoryJournal
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A record the store already holds, offered again, is never stored twice: the epoch archive keeps
 * one record per counter, and the sample store keeps a sample only when it is newer than its
 * kind's cursor. Two ways a record comes back: the ring re-offers a page it already sent (its
 * pointer stepped back, or an acknowledgement was cut by a teardown after the page was stored),
 * and a page that straddled a commit's hold-back bound is kept whole and read again by the next
 * commit (PORTING.md D-267). Asserted on the store's own rows, never on the planner's output.
 */
class OverlappingSyncNoDuplicateTest {

    private suspend fun heartRates(db: StoreDatabase): List<Instant> =
        LocalStore(db).samples(MetricKind.HEART_RATE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")).map { it.start }

    private fun dates(counters: List<Long>) = counters.map { Instant.ofEpochSecond(it + Command.SYNC_EPOCH) }

    private suspend fun LocalStore.samplesOfEveryKind() =
        MetricKind.entries.flatMap { samples(it, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")) }

    @Test
    fun aPageTheRingOffersAgainAfterItWasCommittedAddsNoSecondSample() = runTest {
        val sleep = HistoryTestPages.backlog(3)
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to sleep.take(2), Command.SYNC_CHANNEL_ALL_DAY to emptyList()))
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            assertEquals(dates((0 until 2).flatMap { HistoryTestPages.counters(it) }), heartRates(w.db))

            // The ring offers its second page again, then one new page.
            w.ring.holdAgain(Command.SYNC_CHANNEL_SLEEP, listOf(sleep[1], sleep[2]))
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()
            advanceTo(120_000)

            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            val expected = dates((0 until 3).flatMap { HistoryTestPages.counters(it) })
            assertEquals(expected, heartRates(w.db), "18 samples, each once")
            assertEquals((0 until 3).flatMap { HistoryTestPages.counters(it) }, w.blobs.loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aPageStraddlingTheBoundIsCommittedInTwoGoesWithEachSampleOnce() = runTest {
        val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
        try {
            val now = Instant.parse("2026-10-08T09:00:00Z")
            val store = StoreHistory({ db }, TEST_RING_ID, { ZoneOffset.UTC })
            val page = HistoryTestPages.sleepPage(0, 1)
            val counters = HistoryTestPages.counters(0)
            store.append(page, now, drainId = 1)
            // The same page again, as a re-offer after a cut acknowledgement.
            store.append(page, now, drainId = 2)

            val first = store.commit(now, CommitPlanner.Drained.Through(counters[2]))
            assertEquals(3, first.records)
            assertEquals(3, first.heldBack)
            assertEquals(2, first.pagesKept)
            assertEquals(dates(counters.take(3)), heartRates(db))
            assertEquals(2, HistoryJournal(db).read(TEST_RING_ID).entries.size, "both copies kept whole")

            val second = store.commit(now, CommitPlanner.Drained.Everything)
            assertEquals(6, second.records)
            assertEquals(dates(counters), heartRates(db))
            // Every kind: the second commit stored exactly what the first did not, for the last three records.
            val all = LocalStore(db).samplesOfEveryKind()
            assertEquals(all.size, all.distinctBy { it.kind to it.start }.size, "no sample stored twice")
            assertEquals(first.samplesStored + second.samplesStored, all.size)
            assertEquals(counters, BlobStore(db).loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            assertEquals(emptyList(), HistoryJournal(db).read(TEST_RING_ID).entries)
        } finally {
            db.close()
        }
    }
}
