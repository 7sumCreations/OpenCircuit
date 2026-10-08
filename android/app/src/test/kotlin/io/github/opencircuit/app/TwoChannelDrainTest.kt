package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sync now drains BOTH history channels in the foreground order `[0x00 sleep, 0x03 all-day]`
 * (upstream `HistoryDrainPlan`, `ios/OpenCircuit/BLE/RingSession.swift:3611-3787` @ b1c2fdd),
 * each opened with `02 00 <now, 4 bytes BE> <channel> 01 00` and, exactly 300 ms later,
 * `07 00 00`, the second only once the first channel has ended; every page of both is stored,
 * acknowledged once and committed in one commit. Bytes are literals typed from PROTOCOL.md §3
 * and the tracer's cursor (`0cbc2351` at 09:00:01Z, one more per second), never built by `Command`.
 */
class TwoChannelDrainTest {

    private val sleepPages = HistoryTestPages.backlog(2)
    private val allDayPages = HistoryTestPages.allDayBacklog(2)

    private suspend fun TestScope.world(
        sleep: List<ByteArray> = sleepPages,
        allDay: List<ByteArray> = allDayPages,
    ) = syncWorld { scope, now ->
        RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to sleep, Command.SYNC_CHANNEL_ALL_DAY to allDay))
    }

    @Test
    fun bothChannelsAreOpenedInOrderEachWithItsFetchThreeHundredMillisecondsLater() = runTest {
        val w = world()
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            runCurrent()

            // Sleep: 0x82 at 1 500, pages at 2 700 and 5 300, its 0x50 at 7 100 — then the all-day open.
            advanceTo(7_099)
            assertEquals(emptyList(), w.ring.writes.filter { it.hex.startsWith("02") && it.hex.endsWith("030100") })
            advanceTo(7_399)
            assertEquals(listOf(1_300L), w.ring.writes.filter { it.hex == "070000" }.map { it.atMillis }, "no all-day fetch at 7 399")
            advanceTo(7_400)
            advanceTo(60_000)

            assertEquals(
                listOf(
                    TimedWrite(1_000, "02000cbc2351000100"),
                    TimedWrite(1_300, "070000"),
                    TimedWrite(7_100, "02000cbc2357030100"),
                    TimedWrite(7_400, "070000"),
                ),
                w.ring.writes.filter { it.hex.startsWith("02") || it.hex == "070000" },
            )
            // Every page of both channels acknowledged once, in the order the ring sent them.
            assertEquals((sleepPages + allDayPages).map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() })
            // One commit took both channels' records into the archive, and the journal is empty.
            val expected = (0 until 2).flatMap { HistoryTestPages.counters(it) } + (0 until 2).flatMap { HistoryTestPages.allDayCounters(it) }
            assertEquals(expected, w.blobs.loadEpochArchive(TEST_RING_ID).records.map { it.counter })
            assertEquals(1, w.ring.events.count { it.what == "commit returned" })
            assertEquals(emptyList(), w.journal.read(TEST_RING_ID).entries)

            val report = w.session.sync.state.value.last!!
            assertEquals(SyncOutcome.COMPLETE, report.outcome)
            assertEquals(listOf("sleep", "all-day"), report.channels.map { it.label })
            assertEquals(listOf(0x00, 0x03), report.channels.map { it.channel })
            assertEquals(listOf(HistoryChannelOutcome.COMPLETE, HistoryChannelOutcome.COMPLETE), report.channels.map { it.verdict })
            assertEquals(listOf(12, 12), report.channels.map { it.records })
            assertEquals("24 records · complete", w.viewModel.uiState.value.ringData.lastSync)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aChannelWithNothingToSendIsEmptyAndTheSyncIsStillComplete() = runTest {
        val w = world(allDay = emptyList())
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val report = w.session.sync.state.value.last!!
            assertEquals(listOf(HistoryChannelOutcome.COMPLETE, HistoryChannelOutcome.EMPTY), report.channels.map { it.verdict })
            assertEquals(SyncOutcome.COMPLETE, report.outcome)
            assertEquals(12, w.blobs.loadEpochArchive(TEST_RING_ID).records.size)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aLinkLostMidChannelEndsTheSyncThereCommitsWhatCameAndOpensNoOtherChannel() = runTest {
        val w = world(sleep = HistoryTestPages.backlog(3))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(4_000) // page 1 stored and acknowledged at 2 700; page 2 is due at 5 300
            w.ring.dropLink()
            runCurrent()

            assertEquals(listOf(4_000L), w.ring.events.filter { it.what == "commit returned" }.map { it.atMillis }, "committed at once")
            advanceTo(60_000)
            assertTrue(w.ring.writes.none { it.hex.startsWith("02") && it.hex.endsWith("030100") }, "no all-day open on a lost link")
            assertEquals(6, w.blobs.loadEpochArchive(TEST_RING_ID).records.size, "the stored page's records are kept")

            val report = w.session.sync.state.value.last!!
            val sleep = report.channels.single()
            assertEquals(HistoryChannelExitReason.LINK_UNUSABLE, sleep.rounds.single().exitReason)
            assertEquals(HistoryChannelOutcome.PARTIAL, sleep.verdict)
            assertEquals(SyncOutcome.PARTIAL, report.outcome)
            assertEquals(LinkState.Idle, w.session.state.value)
        } finally {
            w.db.close()
        }
    }
}
