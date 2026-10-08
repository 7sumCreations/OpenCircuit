package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each channel's trace (upstream `updateActiveDrainTrace`, `ios/OpenCircuit/BLE/RingSession.swift:4625-4665`
 * @ b1c2fdd): every `0x82` kept byte for byte, its byte 2 as the flag and byte 1 `ff` as "already
 * at the end"; the first and last page's 16-bit countdown; and one `history-drain` log row per
 * round that carries counts and flags, never frame bytes.
 */
class ChannelTraceFlagsTest {

    @Test
    fun theSyncAnswerIsKeptByteForByteWithItsFlagAndEmptySignalAndTheCountdownsAreTraced() = runTest {
        val pages = HistoryTestPages.backlog(3) // countdowns 12, 6, 0
        val w = syncWorld { scope, now ->
            RingFake(
                scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to pages),
                syncAck = hex("82000183"), emptyAck = RingFake.SYNC_ACK_EMPTY, endOfHistoryBy = mapOf(0x03 to null),
            )
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val (sleep, allDay) = w.session.sync.state.value.last!!.channels
            assertEquals(listOf("82000183"), sleep.syncAcks)
            assertEquals(1, sleep.rounds.single().syncAckFlag)
            assertEquals(false, sleep.rounds.single().sawEmptyHistorySignal)
            assertEquals(0x82, sleep.rounds.single().firstOpcode)
            assertEquals(0x50, sleep.rounds.single().lastOpcode)
            assertEquals(12, sleep.firstCountdown)
            assertEquals(0, sleep.lastCountdown)

            assertEquals(listOf("82ff007d"), allDay.syncAcks)
            assertEquals(0, allDay.rounds.single().syncAckFlag)
            assertEquals(true, allDay.rounds.single().sawEmptyHistorySignal)
            assertEquals(null, allDay.firstCountdown)

            val rows = w.logs.filter { it.startsWith("history-drain ") }
            assertEquals(2, rows.size, "one row per round")
            assertTrue(rows[0].contains("label=sleep") && rows[0].contains("outcome=complete") && rows[0].contains("flag=1") && rows[0].contains("4c=3") && rows[0].contains("50=1"), rows[0])
            assertTrue(rows[1].contains("label=all-day") && rows[1].contains("outcome=empty") && rows[1].contains("empty=true"), rows[1])
            val pageHex = pages.map { it.toPlainHex() }
            assertTrue(w.logs.none { line -> pageHex.any { line.contains(it.substring(6, 20)) } || line.contains("82000183") }, "no frame bytes in any log line")
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aFirstPageWithMoreThan255QueuedIsTracedWithItsSixteenBitCountdown() = runTest {
        // 717 records still queued after the first page: bytes 1–2 = 02 cd.
        val pages = listOf(HistoryTestPages.page(0, queuedAfter = 717, firstEpoch = 0), HistoryTestPages.page(1, queuedAfter = 711, firstEpoch = 0))
        val w = syncWorld { scope, now -> RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to pages)) }
        try {
            assertEquals("4c02cd", pages[0].toPlainHex().take(6))
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val sleep = w.session.sync.state.value.last!!.channels.first()
            assertEquals(717, sleep.firstCountdown)
            assertEquals(711, sleep.lastCountdown)
        } finally {
            w.db.close()
        }
    }
}
