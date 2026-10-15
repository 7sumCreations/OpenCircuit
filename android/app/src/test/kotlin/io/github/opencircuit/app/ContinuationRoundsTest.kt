package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "half-night" ring (upstream `DrainContinuation`, `ios/OpenCircuitKit/Sources/OpenCircuitKit/DrainContinuation.swift`
 * @ b1c2fdd): a channel that delivers a few pages per open and then goes quiet with no `0x50`,
 * though it holds more, is nudged once and then reopened in the same sync while each round adds
 * records — at most 12 reopens per channel — so the whole backlog lands in one sync. A sync cut by
 * a lost link is finished by the next sync on the same session.
 */
class ContinuationRoundsTest {

    private fun sleepOpens(w: SyncWorld) = w.ring.writes.filter { it.hex.startsWith("02") && it.hex.endsWith("000100") }.map { it.atMillis }

    @Test
    fun aRingThatStopsAfterTwoPagesPerOpenIsReopenedUntilARoundAddsNothing() = runTest {
        val pages = HistoryTestPages.backlog(6)
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to pages), pagesPerOpen = 2, endOfHistoryBy = mapOf(0x00 to null))
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            // Round 0: pages 2 700, 5 300; nudge 11 300; ends 17 300. Round 1: pages 19 600, 20 800;
            // ends 32 800. Round 2: pages 35 900, 37 700; ends 49 700. Round 3: answered, nothing, cut at 69 700.
            assertEquals(listOf(1_000L, 17_300L, 32_800L, 49_700L), sleepOpens(w))
            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() })
            val sleep = w.session.sync.state.value.last!!.channels.first()
            assertEquals(listOf(0, 1, 2, 3), sleep.rounds.map { it.reopenRound })
            assertEquals(
                listOf(
                    HistoryChannelExitReason.QUIET_AFTER_PAGES, HistoryChannelExitReason.QUIET_AFTER_PAGES,
                    HistoryChannelExitReason.QUIET_AFTER_PAGES, HistoryChannelExitReason.QUIET_NO_PAGES,
                ),
                sleep.rounds.map { it.exitReason },
            )
            assertEquals(listOf(12, 12, 12, 0), sleep.rounds.map { it.recordsAdded })
            assertEquals(36, sleep.records)
            // The ring never said it was done: still PARTIAL (D-43), with every record stored.
            assertEquals(HistoryChannelOutcome.PARTIAL, sleep.verdict)
            assertEquals(36, w.blobs.loadEpochArchive(TEST_RING_ID).records.size)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aChannelIsReopenedAtMostTwelveTimesInOneSync() = runTest {
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(20)), pagesPerOpen = 1, endOfHistoryBy = mapOf(0x00 to null))
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(600_000)

            val sleep = w.session.sync.state.value.last!!.channels.first()
            assertEquals((0..12).toList(), sleep.rounds.map { it.reopenRound }, "the first open and 12 reopens")
            assertEquals(13, sleep.rounds.count { it.exitReason == HistoryChannelExitReason.QUIET_AFTER_PAGES })
            assertEquals(13, sleepOpens(w).size)
            assertEquals(13, w.ring.acknowledgedPages.size)
            assertEquals(7, w.ring.stillHeld(Command.SYNC_CHANNEL_SLEEP).size, "the rest waits for the next sync")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSyncCutByALostLinkIsFinishedByTheNextSyncOnTheSameSession() = runTest {
        val pages = HistoryTestPages.backlog(3)
        val w = syncWorld(pages)
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(4_000) // page 1 stored at 2 700; page 2 is due at 5 300
            w.ring.dropLink()
            advanceTo(10_000)
            assertEquals(SyncOutcome.PARTIAL, w.session.sync.state.value.last!!.outcome)

            w.viewModel.onAction(RingAction.SyncNow) // reconnects, then drains what is left
            runCurrent()
            advanceTo(60_000)

            assertEquals(pages.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() }, "each page acknowledged once")
            assertEquals((0 until 3).flatMap { HistoryTestPages.counters(it) }, w.blobs.loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            val second = w.session.sync.state.value.last!!
            assertEquals(SyncOutcome.COMPLETE, second.outcome)
            assertEquals(12, second.channels.first().records)
        } finally {
            w.db.close()
        }
    }
}
