package io.github.opencircuit.ble

import io.github.opencircuit.ble.Fixtures.hex
import io.github.opencircuit.ringkit.HistoryDrainPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The fake link other modules test against must keep the contract of the real one: one collector
 * for `frames` and for `teardowns`, frames kept in order while nobody collects, and `send` that
 * answers with a value instead of throwing.
 */
class FakeRingLinkTest {

    @Test
    fun framesEmittedBeforeAnyCollectorArriveInOrder() = runTest {
        val link = FakeRingLink(Fixtures.ring)
        link.emitFrame(Fixtures.challengeFrame)
        link.emitFrame(Fixtures.firstDataFrame)

        val got = link.frames.take(2).toList()

        assertContentEquals(Fixtures.challengeFrame, got[0])
        assertContentEquals(Fixtures.firstDataFrame, got[1])
    }

    @Test
    fun aSecondCollectorOfFramesFailsLoudly() = runTest {
        val link = FakeRingLink(Fixtures.ring)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.frames.collect {} }

        assertFailsWith<IllegalStateException> { link.frames.first() }
    }

    @Test
    fun aSecondCollectorOfTeardownsFailsLoudly() = runTest {
        val link = FakeRingLink(Fixtures.ring)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.teardowns.collect {} }

        assertFailsWith<IllegalStateException> { link.teardowns.first() }
    }

    @Test
    fun teardownsAreDeliveredAsEmitted() = runTest {
        val link = FakeRingLink(Fixtures.ring)
        val teardown = LinkTeardown(HistoryDrainPlan.TeardownReason.LINK_DROPPED, undeliveredFrames = 2)
        link.emitTeardown(teardown)

        assertEquals(teardown, link.teardowns.first())
    }

    @Test
    fun anEmittedFrameIsACopyTheEmitterCannotChange() = runTest {
        val link = FakeRingLink(Fixtures.ring)
        val bytes = Fixtures.firstDataFrame
        link.emitFrame(bytes)
        bytes[0] = 0

        assertContentEquals(Fixtures.firstDataFrame, link.frames.first())
    }

    @Test
    fun sendRecordsACopyAndAnswersTheScriptedResultsInOrderThenSent() = runTest {
        val link = FakeRingLink(Fixtures.ring)
        link.answerSendsWith(
            SendResult.Refused(RefusalReason.HISTORY_UNSAFE),
            SendResult.Failed(SendFailure.TIMED_OUT),
        )
        val command = hex("950000")

        val results = listOf(link.send(command), link.send(command), link.send(command))
        command[0] = 0

        assertEquals(
            listOf(SendResult.Refused(RefusalReason.HISTORY_UNSAFE), SendResult.Failed(SendFailure.TIMED_OUT), SendResult.Sent),
            results,
        )
        assertEquals(3, link.sent.size)
        link.sent.forEach { assertContentEquals(hex("950000"), it) }
    }

    @Test
    fun stateInfoAndCallsAreScriptableAndObservable() = runTest {
        val link = FakeRingLink(Fixtures.ring)
        assertEquals(LinkState.Idle, link.state.value)

        link.setState(LinkState.Reconnecting(attempt = 2, delay = Duration.ofSeconds(5)))
        link.setInfo(LinkInfo(attMtu = 247))
        link.connect()
        link.connect()
        link.disconnect()

        assertEquals(LinkState.Reconnecting(2, Duration.ofSeconds(5)), link.state.value)
        assertEquals(247, link.info.value.attMtu)
        assertEquals(2, link.connectCalls)
        assertEquals(1, link.disconnectCalls)
    }
}
