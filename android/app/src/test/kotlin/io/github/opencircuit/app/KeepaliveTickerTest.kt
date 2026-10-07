package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.session.KeepaliveProblem
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendFailure
import io.github.opencircuit.ble.SendResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The idle keepalive: while the ring is authenticated and no live measure runs, the app writes
 * `d0 00 00` — and only that — when the link becomes authenticated and every 180 s after; never
 * while a measure runs. When a measure stops it asks for a fresh status at once with one
 * `d0 00 00`, and if no descriptor answers within 750 ms it asks once more at the next tick,
 * 30 s later. Upstream's keepalive and refresh write `01 00 00` and `07 00 00`; this one does not
 * (PORTING.md D-232, D-233, D-240).
 *
 * The cadences are typed here from `ringkit/.../KeepaliveCadence.kt` (180 s by day, 30 s while a
 * live read runs), never read back from production code.
 */
class KeepaliveTickerTest {

    private val logLines = mutableListOf<String>()

    private fun TestScope.session(link: TimedRingLink): RingSessionController {
        val controller = RingSessionController(link, backgroundScope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
        controller.start()
        testScheduler.runCurrent()
        return controller
    }

    private fun TimedRingLink.statusQueryTimes() = timesOf(Wire.STATUS_QUERY)

    @Test
    fun whileIdleAndAuthenticatedItWritesOnlyD0AtOnceAndEvery180Seconds() = runTest {
        val link = timedLink()
        session(link)
        advanceTo(1_000)
        link.fake.setState(LinkState.Authenticated)
        runCurrentAt(1_000)
        assertEquals(listOf(1_000L), link.statusQueryTimes(), "one d0 as the link becomes authenticated")

        advanceTo(1_000 + IDLE - 1)
        assertEquals(listOf(1_000L), link.statusQueryTimes(), "nothing 1 ms before 180 s")
        advanceTo(1_000 + IDLE)
        advanceTo(1_000 + 3 * IDLE)

        assertEquals(listOf(1_000L, 1_000 + IDLE, 1_000 + 2 * IDLE, 1_000 + 3 * IDLE), link.statusQueryTimes())
        assertEquals(setOf(Wire.STATUS_QUERY), link.writes.map { it.hex }.toSet(), "d0 00 00 and nothing else")
    }

    @Test
    fun nothingIsWrittenWhileTheLinkIsNotAuthenticated() = runTest {
        val link = timedLink()
        session(link)
        for (state in listOf(
            LinkState.Idle, LinkState.Connecting, LinkState.Discovering, LinkState.PairingNeeded, LinkState.Preparing,
            LinkState.Authenticating, LinkState.NotStreaming, LinkState.Reconnecting(attempt = 2, delay = Duration.ofSeconds(5)),
            LinkState.WaitingForRing, LinkState.BondLostSuspected, LinkState.BluetoothOff,
        )) {
            link.fake.setState(state)
            advanceTo(testScheduler.currentTime + 2 * IDLE)
        }

        assertEquals(emptyList(), link.writes)
    }

    @Test
    fun leavingAuthenticatedStopsTheTicksAndComingBackStartsThemAgain() = runTest {
        val link = timedLink()
        session(link)
        link.fake.setState(LinkState.Authenticated)
        advanceTo(IDLE)
        link.fake.setState(LinkState.Reconnecting(attempt = 1, delay = Duration.ofSeconds(1)))
        advanceTo(IDLE + 10_000)
        advanceTo(5 * IDLE)
        assertEquals(listOf(0L, IDLE), link.statusQueryTimes(), "no tick while reconnecting")

        link.fake.setState(LinkState.Authenticated)
        advanceTo(5 * IDLE)
        advanceTo(6 * IDLE)

        assertEquals(listOf(0L, IDLE, 5 * IDLE, 6 * IDLE), link.statusQueryTimes(), "the same ticker runs again after the reconnect")
    }

    @Test
    fun noKeepaliveIsWrittenWhileAMeasureRuns() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        link.fake.setState(LinkState.Authenticated)
        advanceTo(IDLE - 20_000)
        live.start(LiveMode.HEART_RATE)
        advanceTo(IDLE - 20_000)

        advanceTo(IDLE + 40_000) // the 180 s tick falls inside the measure
        assertEquals(1, link.statusQueryTimes().count { it == IDLE - 20_000 }, "the entry's own d0, and no keepalive beside it")
        val keepalives = link.statusQueryTimes().filter { it != IDLE - 20_000 } // the entry's own d0
        assertEquals(listOf(0L), keepalives, "no d0 but the entry's while measuring")
        assertTrue(link.timesOf(Wire.POLL).isNotEmpty(), "the measure was polling")
    }

    @Test
    fun afterAStopOneD0AndNoRetryWhenADescriptorAnswersWithin750Milliseconds() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        link.fake.setState(LinkState.Authenticated)
        advanceTo(10_000)
        live.start(LiveMode.HEART_RATE)
        advanceTo(40_000)
        val before = link.writes.size

        live.stop()
        advanceTo(40_000)
        advanceTo(40_749)
        link.fake.emitFrame(TestFrames.wornDescriptor)
        advanceTo(40_000 + IDLE - 1)

        assertEquals(listOf(TimedWrite(40_000, Wire.STATUS_QUERY)), link.writes.drop(before), "one d0 at the stop, no retry")
        advanceTo(40_000 + IDLE)
        assertEquals(40_000 + IDLE, link.statusQueryTimes().last(), "the idle cadence runs from the refresh")
    }

    @Test
    fun afterAStopWithNoDescriptorWithin750MillisecondsItAsksOnceMoreAtTheNextTick() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        link.fake.setState(LinkState.Authenticated)
        advanceTo(10_000)
        live.start(LiveMode.SPO2)
        advanceTo(40_000)
        val before = link.writes.size

        live.stop()
        advanceTo(40_751)
        link.fake.emitFrame(TestFrames.wornDescriptor) // too late: the 750 ms are over
        advanceTo(40_000 + RETRY - 1)
        assertEquals(listOf(TimedWrite(40_000, Wire.STATUS_QUERY)), link.writes.drop(before))

        advanceTo(40_000 + RETRY)
        advanceTo(40_000 + RETRY + IDLE - 1)
        assertEquals(
            listOf(TimedWrite(40_000, Wire.STATUS_QUERY), TimedWrite(40_000 + RETRY, Wire.STATUS_QUERY)),
            link.writes.drop(before),
            "exactly one retry, then nothing until the idle cadence",
        )
        advanceTo(40_000 + RETRY + IDLE)
        assertEquals(40_000 + RETRY + IDLE, link.statusQueryTimes().last())
    }

    @Test
    fun aMeasureEndedByItsBudgetIsFollowedByTheRefreshToo() = runTest {
        val link = timedLink()
        val live = session(link).liveMeasure
        link.fake.setState(LinkState.Authenticated)
        advanceTo(10_000)
        live.start(LiveMode.SPO2) // budget 45 s from the arm at 10 750 ms
        advanceTo(10_000 + 750 + 45_000)

        assertEquals(10_000 + 750 + 45_000, link.statusQueryTimes().last(), "the refresh d0 at the budget's end")
    }

    @Test
    fun aRefusedD0IsShownAndLoggedAndTheNextSentOneClearsIt() = runTest {
        val link = timedLink()
        val controller = session(link)
        link.fake.answerSendsWith(SendResult.Refused(RefusalReason.NOT_BONDED))
        link.fake.setState(LinkState.Authenticated)
        runCurrentAt(0)

        assertEquals(KeepaliveProblem.Refused(RefusalReason.NOT_BONDED), controller.keepalive.problem.value)
        assertEquals(1, logLines.count { "refused" in it }, "one log line: $logLines")

        advanceTo(IDLE)
        assertNull(controller.keepalive.problem.value, "the next d0 went through")
    }

    @Test
    fun aProblemIsClearedWhenTheLinkLeavesAuthenticated() = runTest {
        val link = timedLink()
        val controller = session(link)
        link.fake.answerSendsWith(SendResult.Refused(RefusalReason.NOT_BONDED))
        link.fake.setState(LinkState.Authenticated)
        runCurrentAt(0)
        assertEquals(KeepaliveProblem.Refused(RefusalReason.NOT_BONDED), controller.keepalive.problem.value)

        link.fake.setState(LinkState.WaitingForRing)
        testScheduler.runCurrent()

        assertNull(controller.keepalive.problem.value, "the link's own words say why; no stale status-query line beside them")
    }

    @Test
    fun aFailedD0IsShown() = runTest {
        val link = timedLink()
        val controller = session(link)
        link.fake.answerSendsWith(SendResult.Sent, SendResult.Failed(SendFailure.TIMED_OUT))
        link.fake.setState(LinkState.Authenticated)
        advanceTo(IDLE)

        assertEquals(KeepaliveProblem.Failed(SendFailure.TIMED_OUT), controller.keepalive.problem.value)
        assertTrue(logLines.any { "failed" in it }, "logged: $logLines")
    }

    private fun TestScope.runCurrentAt(atMillis: Long) {
        assertEquals(atMillis, testScheduler.currentTime)
        testScheduler.runCurrent()
    }

    private companion object {
        /** `KeepaliveCadence.interval(isNight = false, activeMeasurement = false, batterySaver = false)`. */
        const val IDLE = 180_000L

        /** `KeepaliveCadence.interval(isNight = false, activeMeasurement = true, batterySaver = false)`. */
        const val RETRY = 30_000L
    }
}
