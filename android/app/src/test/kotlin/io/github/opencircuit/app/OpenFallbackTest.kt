package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.app.sync.OpenFallback
import io.github.opencircuit.app.sync.SyncOutcome
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The open's one fallback (PORTING.md D-262, D-264): no `0x82` and no page within 5 s of a
 * channel's open → the link's `reauthenticate()` once, then the same open again; never the app's
 * own `01 00 00`, and never twice in one sync. The re-auth is recorded apart from the app's writes.
 */
class OpenFallbackTest {

    private fun opensAndFetches(w: SyncWorld) = w.ring.writes.filter { it.hex.startsWith("02") || it.hex == "070000" }

    @Test
    fun aRingThatIgnoresTheFirstOpenIsReauthenticatedOnceAtFiveSecondsAndThenAnswers() = runTest {
        val w = syncWorld { scope, now ->
            RingFake(
                scope, now,
                mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(2), Command.SYNC_CHANNEL_ALL_DAY to HistoryTestPages.allDayBacklog(1)),
                ignoreOpensUntilReauth = true,
            )
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(5_999)
            assertEquals(emptyList(), w.ring.reauths, "no re-auth 4 999 ms after the open")
            advanceTo(6_000)
            assertEquals(listOf(6_000L), w.ring.reauths)
            advanceTo(60_000)

            assertEquals(
                listOf(
                    TimedWrite(1_000, "02000cbc2351000100"),
                    TimedWrite(1_300, "070000"),
                    TimedWrite(6_000, "02000cbc2356000100"),
                    TimedWrite(6_300, "070000"),
                    TimedWrite(12_100, "02000cbc235c030100"),
                    TimedWrite(12_400, "070000"),
                ),
                opensAndFetches(w),
            )
            assertEquals(listOf(6_000L), w.ring.reauths, "once per sync")
            assertTrue(w.ring.writes.none { it.hex == "010000" || it.hex.startsWith("0101") }, "the app never writes the auth start")

            val (sleep, allDay) = w.session.sync.state.value.last!!.channels
            assertEquals(OpenFallback.REAUTH, sleep.openFallback)
            assertEquals(true, sleep.fallbackHelped)
            assertEquals(OpenFallback.NONE, allDay.openFallback)
            assertEquals(HistoryChannelOutcome.COMPLETE, sleep.verdict)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
            assertTrue(w.logs.any { it.startsWith("history-drain ") && it.contains("label=sleep") && it.contains("fallback=reauth") })
        } finally {
            w.db.close()
        }
    }

    @Test
    fun anAnswerJustInsideFiveSecondsNeedsNoReauth() = runTest {
        // Open 1 000, fetch 1 300, 0x82 at 1 300 + 3 699 = 4 999.
        val w = syncWorld { scope, now ->
            RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to HistoryTestPages.backlog(1)), ackDelayMillis = 3_699)
        }
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            assertEquals(emptyList(), w.ring.reauths)
            assertEquals(OpenFallback.NONE, w.session.sync.state.value.last!!.channels.first().openFallback)
            assertEquals(SyncOutcome.COMPLETE, w.session.sync.state.value.last!!.outcome)
        } finally {
            w.db.close()
        }
    }
}
