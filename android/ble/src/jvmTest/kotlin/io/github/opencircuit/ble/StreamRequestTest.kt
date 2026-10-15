package io.github.opencircuit.ble

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After answering the ring's challenge, the link asks the ring for one data frame with
 * `d0 00 00` (`docs/PROTOCOL.md` §4: answered `0x10` or `0x50`), so the first data frame (which
 * is what authenticates the link) comes at once instead of whenever the ring's own telemetry
 * timer next fires (`0x10` on its own ~40/110 s timer, §5.8). Upstream writes a keepalive tick
 * (`07 00 00` by day, `d0 00 00` in its sleep window) 250 ms after `01 00 00` at every connect
 * (`ios/OpenCircuit/BLE/RingSession.swift:1220-1256` @ b1c2fdd); here the app may not write
 * before the link is authenticated, so the link asks itself (PORTING.md D-257).
 *
 * On a real Gen 2 a reconnect reached the auth reply in under 2 s, then showed "not streaming"
 * and only authenticated about 72 s later, when the ring next streamed on its own.
 */
class StreamRequestTest {

    /** A ring that accepts the auth reply but streams nothing on its own; it answers `d0 00 00` with a descriptor. */
    private val reconnectedRing = Fixtures.acceptingRing().copy(
        firstFrameAfterAuth = null,
        statusQueryReply = Fixtures.descriptor10,
    )

    private val authReplyLine = "write 8327ad98 01 01 31 82 67 00"
    private val statusQueryLine = "write 8327ad98 d0 00 00"

    @Test
    fun aRingThatStreamsNothingOnItsOwnIsAuthenticatedByTheAnswerToTheStatusQuery() = runTest {
        val (_, link) = linkTo(reconnectedRing)
        val states = recordStates(link)
        val frames = recordFrames(link)

        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)

        advance(10 * 60_000)
        assertTrue(LinkState.NotStreaming !in states, "never not streaming: $states")
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(listOf(Fixtures.descriptor10.hexString()), frames, "the answer reaches the app")
    }

    @Test
    fun theStatusQueryIsWrittenOnceRightAfterTheAuthReply() = runTest {
        val (ring, link) = linkTo(reconnectedRing)
        link.connect()
        runCurrent()

        val writes = ring.log.filter { it.startsWith("write ") }
        assertEquals(listOf("write 8327ad98 01 00 00", authReplyLine, statusQueryLine), writes)
    }

    @Test
    fun aRingThatStreamsOnItsOwnStillGetsTheOneStatusQuery() = runTest {
        val (ring, _) = authenticatedLink()

        assertEquals(1, ring.log.count { it == statusQueryLine })
        assertTrue(ring.log.indexOf(statusQueryLine) > ring.log.indexOf(authReplyLine))
    }

    @Test
    fun aLaterChallengeIsAnsweredWithoutASecondStatusQuery() = runTest {
        val (ring, link) = authenticatedLink(reconnectedRing)

        ring.notify(Fixtures.challengeFrame)
        runCurrent()

        assertEquals(2, ring.log.count { it == authReplyLine }, "the challenge is answered")
        assertEquals(1, ring.log.count { it == statusQueryLine })
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun everyConnectionAsksOnce() = runTest {
        val (ring, link) = authenticatedLink(reconnectedRing)

        ring.dropConnection(8)
        runCurrent()
        advance(1_000) // the first reconnect waits 1 s
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(2, ring.log.count { it == statusQueryLine })
    }

    @Test
    fun aRingThatAnswersNeitherStillShowsNotStreamingAfter10Seconds() = runTest {
        val (ring, link) = linkTo(reconnectedRing.copy(statusQueryReply = null))
        link.connect()
        runCurrent()
        assertEquals(1, ring.log.count { it == statusQueryLine })
        assertEquals(LinkState.Authenticating, link.state.value)

        advance(9_999)
        assertEquals(LinkState.Authenticating, link.state.value)
        advance(1)
        assertEquals(LinkState.NotStreaming, link.state.value)
    }

    /**
     * The owner's Gen 2 on FR02.022: it accepts the auth reply but answers no `d0 00 00`, and
     * sends its first frame only on its own timer 40 s later; it answers `07 00 00` with a `0x87`
     * descriptor (`docs/PROTOCOL.md` §4). On the phone that link showed "not streaming" 11.4 s
     * after connecting and authenticated only at 38.9 s; a sync waiting 30 s for it gave up.
     */
    private val silentRing = Fixtures.acceptingRing().copy(
        firstFrameAfterAuth = null,
        statusQueryReply = null,
        fetchReply = Fixtures.descriptor87,
        ownTimerFrame = Fixtures.descriptor10,
        ownTimerAfterMillis = 40_000,
    )

    private val fetchLine = "write 8327ad98 07 00 00"

    @Test
    fun aRingThatIgnoresTheStatusQueryIsAuthenticatedByItsAnswerToOneFetchWithin3Seconds() = runTest {
        val (ring, link) = linkTo(silentRing)
        val states = recordStates(link)
        val frames = recordFrames(link)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticating, link.state.value, "the auth reply and the d0 are written, nothing answered yet")

        advance(3_000)
        assertEquals(LinkState.Authenticated, link.state.value, "authenticated by the 0x87, ${ring.log}")
        assertTrue(LinkState.NotStreaming !in states, "never not streaming: $states")
        assertEquals(listOf(Fixtures.descriptor87.hexString()), frames, "the 0x87 reaches the app like any frame")
        val writes = ring.log.filter { it.startsWith("write ") }
        assertEquals(listOf("write 8327ad98 01 00 00", authReplyLine, statusQueryLine, fetchLine), writes)
    }

    @Test
    fun theFetchWaitsTwoSecondsForTheStatusQuerysAnswerAndIsWrittenOncePerConnection() = runTest {
        val (ring, link) = linkTo(silentRing)
        link.connect()
        runCurrent()

        advance(1_999)
        assertEquals(0, ring.log.count { it == fetchLine }, "not before 2 s")
        advance(1)
        assertEquals(1, ring.log.count { it == fetchLine })

        advance(10 * 60_000) // the ring's own 0x10 at 40 s, and ten minutes of nothing more
        assertEquals(1, ring.log.count { it == fetchLine }, "once per connection")
        assertEquals(LinkState.Authenticated, link.state.value)

        ring.dropConnection(8)
        runCurrent()
        advance(1_000) // the first reconnect waits 1 s
        advance(2_000)
        assertEquals(2, ring.log.count { it == fetchLine }, "the next connection asks once again")
        assertEquals(LinkState.Authenticated, link.state.value)
    }

    @Test
    fun aRingThatAnswersTheStatusQueryGetsNoFetch() = runTest {
        val (ring, link) = linkTo(reconnectedRing.copy(fetchReply = Fixtures.descriptor87))
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)

        advance(60_000)
        assertTrue(ring.log.none { it == fetchLine }, ring.log.joinToString("\n"))
    }

    @Test
    fun aRingThatStreamsOnItsOwnGetsNoFetch() = runTest {
        val (ring, _) = authenticatedLink()
        advance(60_000)
        assertTrue(ring.log.none { it == fetchLine }, ring.log.joinToString("\n"))
    }

    @Test
    fun aRingThatAnswersNeitherIsNotStreamingAt10SecondsAndAuthenticatedByItsOwnFrame() = runTest {
        val (ring, link) = linkTo(silentRing.copy(fetchReply = null))
        link.connect()
        runCurrent()

        advance(10_000)
        assertEquals(1, ring.log.count { it == fetchLine })
        assertEquals(LinkState.NotStreaming, link.state.value)
        advance(30_000)
        assertEquals(LinkState.Authenticated, link.state.value, "the ring's own 0x10 at 40 s")
        assertEquals(1, ring.log.count { it == fetchLine })
    }

    /** A connection that closes before the fetch is due writes nothing on the next one for it. */
    @Test
    fun aDisconnectBeforeTheFetchIsDueWritesNoFetch() = runTest {
        val (ring, link) = linkTo(silentRing)
        link.connect()
        runCurrent()
        advance(1_000)

        link.disconnect()
        runCurrent()
        advance(60_000)
        assertTrue(ring.log.none { it == fetchLine }, ring.log.joinToString("\n"))
        assertEquals(LinkState.Idle, link.state.value)
    }

    /** No MAC: the challenge is never answered, so nothing is asked of a ring that was never authenticated. */
    @Test
    fun noStatusQueryWithoutAnAuthReply() = runTest {
        val noSystemId = reconnectedRing.copy(deviceInformation = Fixtures.deviceInformation - GattPort.SYSTEM_ID)
        val (ring, link) = linkTo(noSystemId, RememberedRing("not-an-address", AddressType.RANDOM, "RingConn Gen2-03AD"))

        link.connect()
        runCurrent()
        advance(60_000)

        assertTrue(ring.log.none { it == statusQueryLine }, ring.log.joinToString("\n"))
        assertTrue(ring.log.none { it == fetchLine }, ring.log.joinToString("\n"))
        assertEquals(LinkState.NotStreaming, link.state.value)
    }
}
