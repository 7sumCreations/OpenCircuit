package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Across a whole session — connect, idle, measure, re-tap, switch, stop, a reconnect, measure
 * again, a teardown — the app writes only the live-measure commands and never:
 * `01 00 00` or any `01 01 …` (the link's own auth exchange — PORTING.md D-232), a `02 …` history
 * sync open (it would move the ring's one shared history pointer), or a `07 00 00` anywhere but as
 * the third write of a live entry (PORTING.md D-233). Polls are never closer than 2 s. The idle
 * keepalive and the status refresh after each measure write `d0 00 00` only, and never while a
 * measure runs (PORTING.md D-240).
 */
class WholeSessionForbiddenWritesTest {

    private val logLines = mutableListOf<String>()

    @Test
    fun aWholeSessionNeverSends010000Or02OrAStray07AndNeverPollsFasterThanEvery2Seconds() = runTest {
        val link = timedLink()
        val session = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        val live = session.liveMeasure

        session.connect()
        link.fake.setState(LinkState.Authenticated)
        advanceTo(300_000) // five idle minutes

        live.start(LiveMode.HEART_RATE)
        advanceTo(303_000)
        TestFrames.realHeartRateRead.forEach(link.fake::emitFrame)
        advanceTo(330_000)
        live.start(LiveMode.HEART_RATE) // re-tap
        advanceTo(340_100)
        live.start(LiveMode.SPO2) // switch
        advanceTo(350_000)
        TestFrames.realSpO2Read.forEach(link.fake::emitFrame)
        advanceTo(360_000)
        live.stop()
        advanceTo(600_000)

        live.start(LiveMode.SPO2)
        advanceTo(620_000)
        link.fake.setState(LinkState.Reconnecting(attempt = 1, delay = Duration.ofSeconds(1)))
        advanceTo(625_000)
        link.fake.setState(LinkState.Authenticated)
        advanceTo(1_200_000)

        live.start(LiveMode.HEART_RATE)
        advanceTo(1_230_000)
        link.fake.emitTeardown(LinkTeardown(TeardownReason.LINK_DROPPED, undeliveredFrames = 0))
        advanceTo(1_500_000)

        val writes = link.writes
        assertTrue(writes.size > 40, "the session wrote enough to judge: ${writes.size}")
        assertEquals(
            setOf(Wire.STATUS_QUERY, Wire.HR_MODE, Wire.SPO2_MODE, Wire.FETCH, Wire.POLL),
            writes.map { it.hex }.toSet(),
            "only live-measure commands, each of them used",
        )
        writes.forEach { w ->
            assertTrue(w.hex != "010000" && !w.hex.startsWith("0101"), "an auth command at ${w.atMillis}")
            assertTrue(!w.hex.startsWith("02"), "a sync open at ${w.atMillis}")
        }
        // Every 07 00 00 is the third write of an entry: its mode 250 ms before, its d0 500 ms before.
        val fetches = writes.filter { it.hex == Wire.FETCH }
        assertEquals(5, fetches.size, "start, re-tap, switch, start, start")
        fetches.forEach { f ->
            val before = writes.filter { it.atMillis < f.atMillis && it.hex != Wire.POLL }
            val mode = before.last()
            val query = before[before.size - 2]
            assertTrue(mode.hex == Wire.HR_MODE || mode.hex == Wire.SPO2_MODE, "07 at ${f.atMillis} follows ${mode.hex}")
            assertEquals(f.atMillis - 250, mode.atMillis, "the mode 250 ms before the 07 at ${f.atMillis}")
            assertEquals(TimedWrite(f.atMillis - 500, Wire.STATUS_QUERY), query, "the d0 500 ms before the 07 at ${f.atMillis}")
        }
        link.timesOf(Wire.POLL).zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= 2_000, "polls at $a and $b are ${b - a} ms apart")
        }

        // The idle keepalive ran in the quiet stretches, with d0 00 00 alone: at authentication
        // and every 180 s in the first five idle minutes.
        assertEquals(listOf(0L, 180_000L), link.timesOf(Wire.STATUS_QUERY).filter { it < 300_000 })
        // Every d0 that is not the first write of an entry is a keepalive or a status refresh:
        // none while a measure ran, and never two closer than the 30 s retry.
        val entryQueries = fetches.map { it.atMillis - 500 }.toSet()
        entryQueries.forEach { t ->
            assertEquals(1, link.timesOf(Wire.STATUS_QUERY).count { it == t }, "one d0 at the entry at $t, no keepalive beside it")
        }
        val keepalives = link.timesOf(Wire.STATUS_QUERY).filter { it !in entryQueries }
        assertTrue(keepalives.size >= 8, "keepalive ticks across the session: $keepalives")
        keepalives.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a >= 30_000, "keepalive d0s at $a and $b are ${b - a} ms apart")
        }
        val measuring = listOf(300_000L until 360_000L, 600_000L until 620_000L, 1_200_000L until 1_230_000L)
        keepalives.forEach { t -> assertTrue(measuring.none { t in it }, "a keepalive d0 at $t while a measure ran") }
    }
}
