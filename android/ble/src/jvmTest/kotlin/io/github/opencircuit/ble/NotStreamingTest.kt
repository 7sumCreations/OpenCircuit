package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A ring that takes the connection but sends no data frame (nothing, or only `0x81` auth
 * frames) for 10 s after notifications were confirmed on shows `NotStreaming`: the symptom of a
 * ring that did not accept this phone (`docs/PROTOCOL.md` §0), as upstream's stream watchdog
 * (`ios/OpenCircuit/BLE/RingSession.swift:1190-1205`, `firstFrameTimeout = 10` at `:264`
 * @ b1c2fdd). The 10 s start when the descriptor write is confirmed, not at connect. A data
 * frame later still clears it, as upstream clears `notStreaming` on the first data frame
 * (`:4884-4888`).
 */
class NotStreamingTest {

    /** A ring that sends its challenge but never accepts the reply: only `0x81` frames, ever. */
    private val silentRing = Fixtures.acceptingRing().copy(acceptedAuthReply = null)

    /** A link whose notification descriptor write is confirmed at 4 s after connect; returns at that moment. */
    private fun TestScope.confirmedAtFourSeconds(script: FakeGatt.Script = silentRing): Pair<FakeGatt, RingLink> {
        val (ring, link) = linkTo(script)
        ring.hold(Operation.WRITE_DESCRIPTOR)
        link.connect()
        runCurrent()
        advance(4_000)
        ring.release(Operation.WRITE_DESCRIPTOR)
        runCurrent()
        assertEquals(LinkState.Authenticating, link.state.value)
        return ring to link
    }

    @Test
    fun onlyChallengeFramesFor10SecondsAfterTheConfirmationMeanNotStreaming() = runTest {
        val (ring, link) = confirmedAtFourSeconds()
        val states = recordStates(link)

        repeat(3) {
            advance(3_000)
            ring.notify(Fixtures.challengeFrame) // 0x81 frames are no sign of data
            runCurrent()
        }
        advance(999) // 9 999 ms after the confirmation, 13 999 ms after connect
        assertEquals(LinkState.Authenticating, link.state.value, "not yet, 1 ms before the 10 s")

        advance(1)
        assertEquals(LinkState.NotStreaming, link.state.value)
        assertEquals(listOf(LinkState.Authenticating, LinkState.NotStreaming), states)
    }

    @Test
    fun aDataFrameAt9990MillisecondsKeepsTheLinkFromNotStreaming() = runTest {
        val (ring, link) = confirmedAtFourSeconds()

        advance(9_990)
        ring.notify(Fixtures.firstDataFrame)
        runCurrent()
        advance(60_000)

        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun aDataFrameAfterNotStreamingAuthenticatesTheLinkAndCommandsGoThrough() = runTest {
        val (ring, link) = confirmedAtFourSeconds()
        advance(10_000)
        assertEquals(LinkState.NotStreaming, link.state.value)
        assertEquals(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED), link.send(hex("950000")))

        ring.notify(Fixtures.firstDataFrame)
        runCurrent()

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(SendResult.Sent, link.send(hex("950000")))
    }

    /** No MAC from either source: the challenge is never answered, so not even a `0x81` frame follows it. */
    @Test
    fun aLinkWhoseMacNeverSettlesShowsNotStreamingAfter10Seconds() = runTest {
        val noSystemId = Fixtures.acceptingRing(deviceInformation = Fixtures.deviceInformation - GattPort.SYSTEM_ID)
        val (_, link) = linkTo(noSystemId, RememberedRing("not-an-address", AddressType.RANDOM, "RingConn Gen2-03AD"))

        link.connect()
        runCurrent()
        advance(9_999)
        assertEquals(LinkState.Authenticating, link.state.value)

        advance(1)
        assertEquals(LinkState.NotStreaming, link.state.value)
    }

    @Test
    fun aRingThatStreamsNeverShowsNotStreaming() = runTest {
        val (_, link) = authenticatedLink()
        val states = recordStates(link)

        advance(10 * 60_000)

        assertEquals(listOf<LinkState>(LinkState.Authenticated), states)
    }

    @Test
    fun theTenSecondsBelongToTheirOwnConnection() = runTest {
        val (ring, link) = linkTo(silentRing)
        link.connect()
        runCurrent() // confirmed at 0
        advance(5_000)
        ring.dropConnection(8)
        runCurrent()
        advance(1_000) // the next connection's notifications are confirmed at 6 s

        advance(9_999) // 15 999 ms: past the dropped connection's 10 s, 1 ms short of this one's
        assertEquals(LinkState.Authenticating, link.state.value)
        advance(1)
        assertEquals(LinkState.NotStreaming, link.state.value)
    }

    /**
     * Device Information reads that take 12 s in all (each under its 5 s timeout): the 10 s run
     * out before `01 00 00` is written, and writing it does not hide that nothing has streamed.
     */
    private fun TestScope.notStreamingWhileReading(script: FakeGatt.Script): Pair<FakeGatt, RingLink> {
        val (ring, link) = linkTo(script)
        ring.hold(Operation.READ)
        link.connect()
        runCurrent() // confirmed at 0; the System ID read waits
        repeat(3) {
            advance(4_000)
            ring.release(Operation.READ)
            ring.hold(Operation.READ)
            runCurrent()
        }
        assertEquals(LinkState.NotStreaming, link.state.value)
        return ring to link
    }

    @Test
    fun startingAuthDoesNotClearNotStreamingButTheFirstDataFrameAfterItAuthenticates() = runTest {
        val (ring, link) = notStreamingWhileReading(silentRing)

        ring.release(Operation.READ)
        runCurrent()
        assertEquals(
            listOf("write 8327ad98 01 01 31 82 67 00", "write 8327ad98 d0 00 00"),
            ring.log.takeLast(2),
            "auth ran, and the ring was asked for a frame",
        )
        assertEquals(LinkState.NotStreaming, link.state.value)

        ring.notify(Fixtures.firstDataFrame)
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun aDataFrameBeforeAuthStartedTakesTheLinkBackToPreparing() = runTest {
        val (ring, link) = notStreamingWhileReading(Fixtures.acceptingRing())

        ring.notify(Fixtures.descriptor10) // the ring streams before `01 00 00` was written
        runCurrent()
        assertEquals(LinkState.Preparing, link.state.value)

        ring.release(Operation.READ)
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun aDroppedConnectionsTenSecondsNeverReachTheNextConnectionStillBeingMade() = runTest {
        val (ring, link) = linkTo(silentRing)
        link.connect()
        runCurrent() // confirmed at 0
        advance(5_000)
        ring.dropConnection(8)
        ring.hold(Operation.DISCOVER_SERVICES) // the next connection stays in discovery
        runCurrent()
        advance(1_000)
        assertEquals(LinkState.Discovering, link.state.value)

        advance(5_000) // 11 s: the dropped connection's 10 s have passed

        assertEquals(LinkState.Discovering, link.state.value)
    }

    @Test
    fun aDropWhileNotStreamingReconnectsAsUsual() = runTest {
        val (ring, link) = confirmedAtFourSeconds()
        advance(10_000)

        ring.dropConnection(8)
        runCurrent()

        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
    }
}
