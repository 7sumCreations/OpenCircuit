package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link coming up syncs on its own only when the last COMPLETE sync is at least 300 s old
 * (upstream `maybeAutoSyncOnReady`, `ios/OpenCircuit/ContentView.swift:924-941` @ b1c2fdd). The
 * mark survives a relaunch; a partial sync does not set it; a paused (unfinished) sync is not
 * throttled. Daytime, so the night's quiet plays no part: the ring links up at virtual 500 ms,
 * 14:00:00.500 UTC.
 */
class AutoSyncThrottleTest {

    private val wallStart = Instant.parse("2026-10-08T14:00:00Z")
    private val linkUp = wallStart.plusMillis(500)

    @Test
    fun aCompleteSync299999MillisecondsOldHoldsTheLinkUpSync() = runTest {
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusMillis(299_999))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(120_000)
            assertEquals(LinkState.Authenticated, w.session.state.value, "the link came up and stays")
            assertEquals(emptyList(), w.sleepOpens(), "no sync 299.999 s after the last complete one")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aCompleteSync300SecondsOldLetsTheLinkUpSyncRun() = runTest {
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusMillis(300_000))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(60_000)
            assertEquals(1, w.sleepOpens().size, "one sync, started by the link coming up")
            assertTrue(w.sleepOpens().single() < 3_000, "at the link-up, not later: ${w.sleepOpens()}")
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last?.outcome)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aCompleteSyncMarkedInTheFutureDoesNotHoldTheLinkUpSync() = runTest {
        // The phone's clock was stepped back after that sync: its mark reads a day ahead of now.
        val kv = InMemoryKeyValues()
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.plus(java.time.Duration.ofDays(1)))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(60_000)
            assertEquals(1, w.sleepOpens().size, "a mark ahead of the clock is no throttle: the link-up syncs")
            assertTrue(w.sleepOpens().single() < 3_000, "at the link-up, not later: ${w.sleepOpens()}")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aCompleteSyncSetsTheMarkAndTheNextLinkUpWithin300SecondsDoesNotSync() = runTest {
        val kv = InMemoryKeyValues()
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(60_000)
            val first = assertNotNull(w.session.sync.state.value.last)
            assertEquals(SyncOutcome.COMPLETE, first.outcome)
            assertEquals(first.finishedAt, PrefsSyncMarks(kv, TEST_RING_ID).lastCompleteSync, "the complete sync's finish is kept")
            assertEquals(LinkState.Idle, w.session.state.value, "the switch is on: disconnected after the sync")

            w.session.connect()
            advanceTo(120_000)
            assertEquals(LinkState.Authenticated, w.session.state.value)
            assertEquals(1, w.sleepOpens().size, "the second link-up, about a minute later, does not sync")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aPartialSyncDoesNotSetTheMarkSoTheNextLinkUpSyncsAgain() = runTest {
        val kv = InMemoryKeyValues()
        // No end report: each channel ends quiet after its pages, PARTIAL (D-43).
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3), endOfHistory = null))
        try {
            advanceTo(120_000)
            assertEquals(SyncOutcome.PARTIAL, w.session.sync.state.value.last?.outcome)
            assertNull(PrefsSyncMarks(kv, TEST_RING_ID).lastCompleteSync)
            val before = w.sleepOpens().size

            w.session.connect()
            advanceTo(240_000)
            assertEquals(before + 1, w.sleepOpens().size, "the next link-up syncs at once")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aRelaunchStillHonoursTheThrottle() = runTest {
        val kv = InMemoryKeyValues()
        val first = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(3)))
        val finished = try {
            advanceTo(60_000)
            assertNotNull(first.session.sync.state.value.last).finishedAt
        } finally {
            first.db.close()
        }
        // The app is started again 200 s after that sync: a new session and store, the same preferences file.
        val relaunched = syncWorld(
            triggers = true,
            keyValues = kv,
            wallStart = finished.plusSeconds(200).minusMillis(testScheduler.currentTime),
            makeRing = ringWith(HistoryTestPages.backlog(2)),
        )
        try {
            val from = testScheduler.currentTime
            advanceTo(from + 60_000)
            assertEquals(LinkState.Authenticated, relaunched.session.state.value)
            assertEquals(emptyList(), relaunched.sleepOpens(), "200 s after a complete sync: no sync on the relaunch")
        } finally {
            relaunched.db.close()
        }
    }

    @Test
    fun aPausedSyncIsNotThrottledWhenTheLinkComesBack() = runTest {
        val kv = InMemoryKeyValues()
        // A complete sync 60 s before the link-up: the link-up itself does not sync.
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(linkUp.minusSeconds(60))
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, makeRing = ringWith(HistoryTestPages.backlog(4)))
        try {
            advanceTo(1_000)
            assertEquals(emptyList(), w.sleepOpens())
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(6_000)
            w.session.sync.pause()
            advanceTo(20_000)
            assertTrue(assertNotNull(w.session.sync.state.value.last).paused)
            assertEquals(LinkState.Idle, w.session.state.value)
            val before = w.sleepOpens().size

            // The link comes back 80 s after the last complete sync: the unfinished sync resumes anyway.
            w.session.connect()
            advanceTo(60_000)
            assertEquals(before + 1, w.sleepOpens().size)
        } finally {
            w.db.close()
        }
    }
}
