package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMeasureController
import io.github.opencircuit.app.live.LiveMode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exact writes of a live measure and when they happen, on virtual time: `d0 00 00`, the mode
 * (`06 01 00` heart rate, `06 02 00` SpO₂) and `07 00 00`, 250 ms apart; then, after the ring's
 * settle, `95 00 00` every 2 s and never faster (PROTOCOL.md §5.1: polling faster pins the heart
 * rate at its warm-up value).
 *
 * Where the settle starts was read from upstream (`ios/OpenCircuit/BLE/RingSession.swift:1122-1145`
 * @ b1c2fdd): each entry write is followed by a 250 ms sleep, the `07 00 00` too, and the 2 s sleep
 * comes after that, so the first poll is 2 750 ms after the `d0 00 00`.
 */
class LiveMeasureSequenceTest {

    private fun TestScope.controllerOn(link: TimedRingLink) =
        LiveMeasureController(send = link::send, scope = backgroundScope, monotonicMillis = { testScheduler.currentTime })

    @Test
    fun heartRateWritesTheEntry250MsApartThenTheFirstPollAt2750Ms() = runTest {
        val link = timedLink()
        val live = controllerOn(link)

        live.start(LiveMode.HEART_RATE)
        testScheduler.runCurrent()
        assertEquals(listOf(TimedWrite(0, Wire.STATUS_QUERY)), link.writes, "d0 00 00 at once")

        advanceTo(249)
        assertEquals(1, link.writes.size, "nothing at 249 ms")
        advanceTo(250)
        assertEquals(TimedWrite(250, Wire.HR_MODE), link.writes.last())

        advanceTo(499)
        assertEquals(2, link.writes.size, "nothing at 499 ms")
        advanceTo(500)
        assertEquals(TimedWrite(500, Wire.FETCH), link.writes.last())

        advanceTo(FIRST_POLL_AT - 1)
        assertEquals(3, link.writes.size, "no poll before the settle has passed")
        advanceTo(FIRST_POLL_AT)
        assertEquals(TimedWrite(FIRST_POLL_AT, Wire.POLL), link.writes.last())
        assertTrue(live.isMeasuring.value)
    }

    @Test
    fun spo2WritesTheSpo2ModeInTheSameEntry() = runTest {
        val link = timedLink()
        val live = controllerOn(link)

        live.start(LiveMode.SPO2)
        advanceTo(FIRST_POLL_AT)

        assertEquals(
            listOf(
                TimedWrite(0, Wire.STATUS_QUERY),
                TimedWrite(250, Wire.SPO2_MODE),
                TimedWrite(500, Wire.FETCH),
                TimedWrite(FIRST_POLL_AT, Wire.POLL),
            ),
            link.writes,
        )
    }

    @Test
    fun neverPollsFasterThanEvery2Seconds() = runTest {
        val link = timedLink()
        val live = controllerOn(link)

        live.start(LiveMode.HEART_RATE)
        // Step through every millisecond boundary a poll could land on, 30 s in all.
        for (t in 0L..30_000L step 250) advanceTo(t)

        val polls = link.timesOf(Wire.POLL)
        assertTrue(polls.size >= 10, "enough polls to judge the cadence: $polls")
        assertEquals(FIRST_POLL_AT, polls.first())
        polls.zipWithNext().forEach { (a, b) -> assertEquals(2_000L, b - a, "polls at $a and $b") }
    }

    @Test
    fun noPollIsWrittenOneMillisecondEarly() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        live.start(LiveMode.HEART_RATE)
        advanceTo(FIRST_POLL_AT)

        for (n in 1..5) {
            val due = FIRST_POLL_AT + n * 2_000L
            advanceTo(due - 1)
            assertEquals(n, link.timesOf(Wire.POLL).size, "poll ${n + 1} not yet at ${due - 1} ms")
            advanceTo(due)
            assertEquals(n + 1, link.timesOf(Wire.POLL).size, "poll ${n + 1} at $due ms")
        }
    }

    @Test
    fun theEntryIsWrittenOnceAndOnlyPollsFollowIt() = runTest {
        val link = timedLink()
        val live = controllerOn(link)
        live.start(LiveMode.HEART_RATE)

        advanceTo(20_000)

        val afterEntry = link.writes.drop(3).map { it.hex }.toSet()
        assertEquals(setOf(Wire.POLL), afterEntry, "no d0 00 00 inside the poll loop (it restarts the warm-up)")
    }

    private companion object {
        /** First poll, in ms after the `d0 00 00`: 3 × 250 ms entry sleeps + the 2 s settle. */
        const val FIRST_POLL_AT = 2_750L
    }
}
