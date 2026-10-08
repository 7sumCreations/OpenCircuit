package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nothing but the drain talks to the ring while it runs: over the app's writes from the sync's
 * first open to its commit there is no `01 00 00` / `01 01 …` (the auth is the link's), no
 * `d0 00 00` (the keepalive is paused for the whole sync, though a tick falls due) and no live
 * measure command (`06 0x 00`, `95 00 00`) — a Measure tap is refused and its card says why.
 * The first open waits for an outstanding status query's answer, or 2 s. Sync now is refused
 * while a measure runs. Each assertion goes red when its guard is removed.
 */
class DrainInterlockTest {

    private val forbidden = listOf("010000", "d00000", "950000")

    private fun forbiddenIn(writes: List<TimedWrite>) =
        writes.filter { it.hex in forbidden || it.hex.startsWith("0101") || (it.hex.startsWith("06") && it.hex.length == 6) }

    @Test
    fun aWholeSyncAcrossAKeepaliveTickAndAMeasureTapWritesNothingButTheDrain() = runTest {
        // Pages every 5 s on both channels: the sync runs past the keepalive tick due at 180 500.
        val w = syncWorld { scope, now ->
            RingFake(
                scope, now,
                mapOf(
                    Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(40),
                    Command.SYNC_CHANNEL_ALL_DAY to HistoryTestPages.allDayBacklog(2),
                ),
                pageGapsMillis = longArrayOf(5_000),
            )
        }
        try {
            advanceTo(1_000)
            assertEquals(listOf(500L), w.ring.writes.filter { it.hex == "d00000" }.map { it.atMillis }, "the keepalive runs before the sync")
            w.viewModel.onAction(RingAction.SetDisconnectAfterSync(false))
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()

            advanceTo(30_000)
            w.viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
            runCurrent()
            val card = w.viewModel.uiState.value.measure!!.heartRate
            assertFalse(card.enabled, "Measure is disabled while syncing")
            assertEquals("Wait for the sync to finish", card.caption)
            assertEquals(null, w.session.liveMeasure.state.value.mode, "the tap started no measure")

            advanceTo(400_000)
            val report = w.session.sync.state.value.last!!
            assertEquals(SyncOutcome.COMPLETE, report.outcome)
            val events = w.ring.events
            val openIndex = events.indexOfFirst { it.what.startsWith("write 02") }
            val commitIndex = events.indexOfFirst { it.what == "commit returned" }
            val firstOpen = events[openIndex].atMillis
            val committed = events[commitIndex].atMillis
            assertTrue(committed > 180_500, "the sync spanned the keepalive tick due at 180 500")
            // Every write from the first open to the commit, in the ring's own order.
            val duringSync = events.subList(openIndex, commitIndex).filter { it.what.startsWith("write ") }
                .map { TimedWrite(it.atMillis, it.what.removePrefix("write ")) }
            assertEquals(emptyList(), forbiddenIn(duringSync))

            // The keepalive resumes once the sync is over (the switch is off: the link is held):
            // its d0 comes after the commit, at the same instant.
            assertEquals(listOf(committed), w.ring.writes.filter { it.hex == "d00000" && it.atMillis >= firstOpen }.map { it.atMillis })
            assertTrue(events.indexOfFirst { it.what == "write d00000" && it.atMillis >= firstOpen } > commitIndex)
            assertTrue(w.viewModel.uiState.value.measure!!.heartRate.enabled, "Measure is back")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theFirstOpenWaitsTwoSecondsForAStatusQueryTheRingNeverAnswers() = runTest {
        // The keepalive's d0 at 500 (authentication) goes unanswered.
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(1)), postAuthReply = null, statusReply = null)
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(2_499)
            assertEquals(emptyList(), w.ring.writes.filter { it.hex.startsWith("02") }, "no open 1 999 ms after the query")
            advanceTo(2_500)
            assertEquals(listOf(2_500L), w.ring.writes.filter { it.hex.startsWith("02") }.map { it.atMillis })
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theFirstOpenGoesAsSoonAsTheOutstandingQueryIsAnswered() = runTest {
        // Authenticated at 500; the ring's answer to the link's d0 comes 300 ms later, at 800.
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(1)), postAuthReplyMillis = 300, statusReply = null)
        }
        try {
            advanceTo(600)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(799)
            assertEquals(emptyList(), w.ring.writes.filter { it.hex.startsWith("02") })
            advanceTo(800)
            assertEquals(listOf(800L), w.ring.writes.filter { it.hex.startsWith("02") }.map { it.atMillis })
        } finally {
            w.db.close()
        }
    }

    @Test
    fun syncNowIsRefusedWhileAMeasureRuns() = runTest {
        val w = syncWorld(HistoryTestPages.backlog(1))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.Measure(LiveMode.HEART_RATE))
            runCurrent()
            assertEquals(LiveMode.HEART_RATE, w.session.liveMeasure.state.value.mode)
            assertFalse(w.viewModel.uiState.value.ringData.syncEnabled, "Sync now is disabled while measuring")
            assertEquals("Stop measuring to sync", w.viewModel.uiState.value.ringData.syncBlockedBy)

            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(20_000)

            assertEquals(emptyList(), w.ring.writes.filter { it.hex.startsWith("02") }, "no open while measuring")
            assertEquals(null, w.session.sync.state.value.last)
            assertFalse(w.session.sync.state.value.syncing)
        } finally {
            w.db.close()
        }
    }
}
