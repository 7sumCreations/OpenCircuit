package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PORTING.md D-43, kept unchanged: a channel whose pages stop with no `0x50` end report is
 * PARTIAL, never COMPLETE — what came is stored, and the card says the sync was partial. Only the
 * ring's `0x50` after its `0x82` completes a channel; a `0x47` sensor-page preamble changes nothing.
 */
class D43OutcomeTest {

    @Test
    fun pagesThenQuietWithNoEndReportIsPartialAndEveryRecordIsStillCommitted() = runTest {
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(2)), endOfHistoryBy = mapOf(0x00 to null))
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            val report = w.session.sync.state.value.last!!
            assertEquals(HistoryChannelOutcome.PARTIAL, report.channels.first().verdict)
            assertEquals(SyncOutcome.PARTIAL, report.outcome)
            assertEquals(12, w.blobs.loadEpochArchive(TEST_RING_ID).records.size)
            assertEquals("12 records · partial — data kept, will retry", w.viewModel.uiState.value.ringData.lastSync)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun theEndReportAfterPagesCompletesTheChannel() = runTest {
        val w = syncWorld(HistoryTestPages.backlog(2))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val sleep = w.session.sync.state.value.last!!.channels.first()
            assertEquals(HistoryChannelOutcome.COMPLETE, sleep.verdict)
            assertEquals(1, sleep.rounds.single().endMarkerCount)
        } finally {
            w.db.close()
        }
    }

    @Test
    fun aSensorPagePreambleIsAcknowledgedAndCountedAndTheChannelStillCompletes() = runTest {
        val sensor = HistoryTestPages.PPG_PAGE
        val sleep = listOf(sensor) + HistoryTestPages.backlog(2)
        val w = syncWorld { scope, now -> RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to sleep)) }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(sleep.map { it.toPlainHex() }, w.ring.acknowledgedPages.map { it.toPlainHex() })
            val report = w.session.sync.state.value.last!!
            val round = report.channels.first().rounds.single()
            assertEquals(1, round.page47Count)
            assertEquals(2, round.page4CCount)
            assertEquals(HistoryChannelOutcome.COMPLETE, report.channels.first().verdict)
            assertEquals(1, report.commit!!.pagesNotKept, "the sensor page is counted, not decoded")
            assertEquals(12, w.blobs.loadEpochArchive(TEST_RING_ID).records.size)
        } finally {
            w.db.close()
        }
    }
}
