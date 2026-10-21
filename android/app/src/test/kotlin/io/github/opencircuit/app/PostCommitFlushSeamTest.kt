package io.github.opencircuit.app

import io.github.opencircuit.app.health.HealthFlush
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.health.FlushResult
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.LocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the Health Connect write starts: after a sync's commit returned, its report is recorded
 * and published, and (switch on) the link is closed — never inside the drain, never holding the
 * link, and never under the session or the sync's pause. The trigger runs one flush at a time and
 * loses no request that comes while one runs.
 */
class PostCommitFlushSeamTest {

    @Test
    fun theFlushIsRequestedAfterTheReportIsRecordedAndPublishedAndAfterTheDisconnect() = runTest {
        val h = healthWorld()
        lateinit var w: SyncWorld
        val seenAtHook = mutableListOf<String>()
        w = syncWorld(reuseDb = h.db, afterCommit = { report ->
            w.ring.note("health flush requested")
            val published = w.session.sync.state.value
            seenAtHook += "published=${published.last === report} syncing=${published.syncing} isSyncing=${w.session.sync.isSyncing.value}"
            seenAtHook += "logged=${w.session.syncRecords.entries.value.size}"
            h.flush.request()
        }, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val order = w.ring.events.map { it.what }.filter { it == "commit returned" || it == "disconnect" || it == "health flush requested" }
            assertEquals(listOf("commit returned", "disconnect", "health flush requested"), order)
            assertEquals(listOf("published=true syncing=false isSyncing=false", "logged=1"), seenAtHook)
            assertTrue(h.hc.records(RecordType.HEART_RATE).isNotEmpty(), "the flush wrote the synced heart rate")
        } finally {
            h.appScope.cancel()
            w.db.close()
        }
    }

    @Test
    fun aSyncWhoseCommitFailedRequestsNoFlush() = runTest {
        var requests = 0
        val w = syncWorld(insideChunk = { throw IllegalStateException("disk full") }, afterCommit = { requests++ }, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(SyncOutcome.COMMIT_FAILED, w.session.sync.state.value.last?.outcome)
            assertEquals(0, requests)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theSyncNeverWaitsForTheFlushAndAPauseNeverReachesIt() = runTest {
        val h = healthWorld()
        h.gate.close() // Health Connect takes the call and does not answer yet.
        val w = syncWorld(reuseDb = h.db, afterCommit = { h.flush.request() }, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(1, h.gate.entered.size, "the flush is inside its insert")
            assertFalse(w.session.sync.isSyncing.value, "the sync finished without waiting for it")
            assertNotNull(w.session.sync.state.value.last)
            assertTrue(h.flush.state.value.running)

            // The user leaves, then a new sync starts and is paused: the flush is not the sync's to stop.
            w.session.sync.onAppStopped(changingConfigurations = false)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(61_000)
            w.session.sync.pause()
            advanceTo(62_000)
            h.gate.open()
            advanceTo(63_000)

            assertFalse(h.flush.state.value.running)
            assertTrue(h.hc.records(RecordType.HEART_RATE).isNotEmpty())
            assertEquals(emptyList(), LocalStore(h.db).pendingHealthSamples(MetricKind.HEART_RATE), "accepted and marked")
        } finally {
            h.appScope.cancel()
            w.db.close()
        }
    }

    @Test
    fun theFlushRunsOnItsOwnScopeSoEndingTheCallersScopeDoesNotStopIt() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var finished = false
        val flush = HealthFlush({
            started.complete(Unit)
            release.await()
            finished = true
            FlushResult(Instant.EPOCH)
        }, appScope, log = {}, enabled = { true })
        val session = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        session.launch { flush.request() }
        runCurrent()
        started.await()

        session.cancel() // the session ends (Stop reconnecting) while the write runs
        release.complete(Unit)
        runCurrent()

        assertTrue(finished)
        assertFalse(flush.state.value.running)
        appScope.cancel()
    }

    @Test
    fun twoTriggersNeverOverlapAndARequestDuringAFlushRunsOnceMoreAfterIt() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val events = mutableListOf<String>()
        var runs = 0
        val release = CompletableDeferred<Unit>()
        val flush = HealthFlush({
            val n = ++runs
            events += "start $n"
            if (n == 1) release.await()
            events += "end $n"
            FlushResult(Instant.EPOCH, written = mapOf(RecordType.HEART_RATE to n))
        }, appScope, log = {}, enabled = { true })

        flush.request() // the commit trigger
        runCurrent()
        flush.request() // the manual action while the first runs
        flush.request() // and again: still one more pass, not two
        runCurrent()
        assertEquals(listOf("start 1"), events)

        release.complete(Unit)
        runCurrent()

        assertEquals(listOf("start 1", "end 1", "start 2", "end 2"), events)
        assertEquals(2, flush.state.value.last?.recordsWritten)
        assertFalse(flush.state.value.running)
        appScope.cancel()
    }

    @Test
    fun aFlushThatThrowsIsReportedAndReleasesTheTriggerForTheNextRequest() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var fail = true
        val logs = mutableListOf<String>()
        val flush = HealthFlush({
            if (fail) throw IllegalStateException("the stored write generation cannot be read")
            FlushResult(Instant.EPOCH)
        }, appScope, log = { logs += it }, enabled = { true })

        flush.request()
        runCurrent()

        assertEquals("IllegalStateException", flush.state.value.error)
        assertFalse(flush.state.value.running)
        assertNull(flush.state.value.last)
        assertTrue(logs.any { "IllegalStateException" in it })

        fail = false
        flush.request()
        runCurrent()
        assertNull(flush.state.value.error)
        assertNotNull(flush.state.value.last)
        appScope.cancel()
    }

    /**
     * The seam the other way: the hook is a trigger, not part of the sync. A trigger that throws —
     * the store's generation cannot be read, the writer cannot be built — leaves the sync's report
     * standing and its outcome intact, with the failure logged as its kind only. Without the hook's
     * own catch a broken Health Connect trigger would take the sync's tail down with it.
     */
    @Test
    fun aFlushRequestThatThrowsNeverBreaksTheSync() = runTest {
        var requests = 0
        val w = syncWorld(
            afterCommit = { requests++; throw IllegalStateException("the write could not be started") },
            makeRing = ringWith(HistoryTestPages.backlog(3)),
        )
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(1, requests, "the after-commit hook still ran")
            val report = assertNotNull(w.session.sync.state.value.last)
            assertEquals(SyncOutcome.COMPLETE, report.outcome, "the sync completed: a trigger that threw is not the sync's failure")
            assertNotNull(report.commit, "the commit stands, with its report")
            assertFalse(w.session.sync.isSyncing.value, "the sync ended as it always does")
            assertTrue(w.logs.any { "The Health Connect write could not be started: IllegalStateException" in it }, "the failure is logged, as its kind only")
        } finally {
            w.db.close()
        }
    }
}
