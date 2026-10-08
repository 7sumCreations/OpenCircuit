package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ring waits for an acknowledgement after each history page and each `0x11` heartbeat. The
 * link answers every heartbeat itself, exactly once, in arrival order; a history page is
 * acknowledged only when the app asks ([RingLink.acknowledge], once the page is stored —
 * PORTING.md D-261). Both kinds of acknowledgement, and the auth reply, go ahead of any feature
 * write still waiting, never cutting into the write already in flight (PORTING.md D-190).
 * Upstream acknowledged every page and heartbeat on receipt, RingSession.swift:5130-5249; it has
 * no queue to order them. Everything is observed from the scripted ring's write log and the
 * frames delivered.
 */
class AckLaneTest {

    private fun ack(hexBytes: String) = "write 8327ad98 $hexBytes"

    @Test
    fun everyHeartbeatIsAckedOnceInArrivalOrderAheadOfQueuedFeatureWritesAndPagesWaitForTheApp() = runTest {
        val (ring, link) = authenticatedLink()
        val delivered = recordFrames(link)
        ring.hold(Operation.WRITE)
        val inFlight = backgroundScope.async { link.send(hex("950000")) }
        runCurrent()
        val queued1 = backgroundScope.async { link.send(hex("d00000")) }
        val queued2 = backgroundScope.async { link.send(hex("070000")) }
        runCurrent()
        val burst = listOf(
            Fixtures.sleepPage4c, Fixtures.heartbeat(1), Fixtures.ppgPage47Truncated, Fixtures.sportPage4dShort,
            Fixtures.sleepPage4c, Fixtures.heartbeat(2), Fixtures.ppgPage47Truncated,
        )

        ring.notifyAll(burst)
        runCurrent()
        val whileInFlight = ring.log.last()
        ring.release(Operation.WRITE)
        runCurrent()

        assertEquals(ack("95 00 00"), whileInFlight, "the write in flight is never cut into")
        assertEquals(
            listOf(ack("95 00 00"), ack("91 00 00"), ack("91 00 00"), ack("d0 00 00"), ack("07 00 00")),
            ring.log.takeLast(5),
        )
        assertEquals(listOf(SendResult.Sent, SendResult.Sent, SendResult.Sent), listOf(inFlight.await(), queued1.await(), queued2.await()))
        assertEquals(listOf("15 00 08 0a b0 a7") + burst.map { it.hexString() }, delivered)
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun aPageThatFailsItsChecksumIsDeliveredAndAckedOnlyWhenTheAppAsks() = runTest {
        val (ring, link) = authenticatedLink()
        val delivered = recordFrames(link)
        val before = ring.log.size

        ring.notify(Fixtures.sleepPage4cBadXor)
        runCurrent()
        assertEquals(emptyList(), ring.log.drop(before), "not acked on receipt")
        val result = backgroundScope.async { link.acknowledge(Fixtures.sleepPage4cBadXor) }
        runCurrent()

        assertEquals(SendResult.Sent, result.await())
        assertEquals(listOf(ack("cc 00 00")), ring.log.drop(before))
        assertEquals(listOf("15 00 08 0a b0 a7", Fixtures.sleepPage4cBadXor.hexString()), delivered)
    }

    @Test
    fun aFrameOfAnyOtherOpcodeIsDeliveredAndNotAcked() = runTest {
        val (ring, link) = authenticatedLink()
        val delivered = recordFrames(link)
        val before = ring.log.size
        // A real 0x10 status descriptor, a 0x50 cursor report, an 0x81 01 status reply and a 2-byte 81 00.
        val others = listOf(Fixtures.descriptor10, hex("5000001204b904b8"), hex("8101aa"), hex("8100"))

        ring.notifyAll(others)
        runCurrent()

        assertEquals(emptyList(), ring.log.drop(before), "nothing written")
        assertEquals(listOf("15 00 08 0a b0 a7") + others.map { it.hexString() }, delivered)
    }

    @Test
    fun aOneByteHeartbeatIsStillAckedByItsOpcode() = runTest {
        val (ring, _) = authenticatedLink()
        val before = ring.log.size

        ring.notify(hex("11"))
        runCurrent()

        assertEquals(listOf(ack("91 00 00")), ring.log.drop(before))
    }

    @Test
    fun aHeartbeatDuringBringUpIsAckedBeforeTheBringUpStepsStillWaiting() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.hold(Operation.READ)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()
        assertEquals("read 00002a23", ring.log.last())

        ring.notify(Fixtures.heartbeat(1))
        runCurrent()
        ring.release(Operation.READ)
        runCurrent()

        val afterSystemId = ring.log.dropWhile { it != "read 00002a23" }.drop(1)
        assertEquals(ack("91 00 00"), afterSystemId.first(), "the ACK takes the next free slot")
        assertEquals(1, ring.log.count { it == ack("91 00 00") })
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun aChallengeInTheMiddleOfABurstIsAnsweredInArrivalOrderAheadOfQueuedFeatureWrites() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        ring.hold(Operation.WRITE)
        backgroundScope.async { link.send(hex("950000")) }
        runCurrent()
        backgroundScope.async { link.send(hex("d00000")) }
        runCurrent()

        ring.notifyAll(listOf(Fixtures.sleepPage4c, Fixtures.challengeFrame0f, Fixtures.heartbeat(1)))
        runCurrent()
        val pageAck = backgroundScope.async { link.acknowledge(Fixtures.sleepPage4c) }
        runCurrent()
        ring.release(Operation.WRITE)
        runCurrent()

        assertEquals(
            listOf(ack("95 00 00"), ack("01 01 4b cc e6 00"), ack("91 00 00"), ack("cc 00 00"), ack("d0 00 00")),
            ring.log.takeLast(5),
        )
        assertEquals(SendResult.Sent, pageAck.await())
    }
}
