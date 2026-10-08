package io.github.opencircuit.ble

import io.github.opencircuit.ble.Fixtures.hex
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RingLink.reauthenticate]: the history drain's one fallback when the ring ignores a sync open.
 * The ring's auth stays the link's own (the app may never write `01 00 00`): the link writes
 * `01 00 00`, answers the ring's `81 00` challenge exactly as it does at bring-up, and only then
 * reports `Sent`. The link stays `Authenticated` throughout: a flicker through `Authenticating`
 * would read as a lost link to the drain. Every outcome is read from the scripted ring's write log.
 */
class ReauthenticateTest {

    private fun write(hexBytes: String) = "write 8327ad98 $hexBytes"

    @Test
    fun itWritesTheAuthStartAndAnswersTheChallengeWithTheBringUpBytesThenReportsSent() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        val states = recordStates(link)
        runCurrent()
        val before = ring.log.size

        val result = backgroundScope.async { link.reauthenticate() }
        runCurrent()

        // The same reply bytes as the bring-up's (BringUpTest's cold log): challenge 0xb0 → 01 01 31 82 67 00.
        assertEquals(listOf(write("01 00 00"), write("01 01 31 82 67 00")), ring.log.drop(before))
        assertTrue(result.isCompleted)
        assertEquals(SendResult.Sent, result.await())
        assertEquals(listOf<LinkState>(LinkState.Authenticated), states.distinct(), "never left Authenticated")
    }

    @Test
    fun itReportsSentOnlyOnceTheAuthReplyIsWritten() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        runCurrent()
        ring.hold(FakeGatt.Operation.WRITE)

        val result = backgroundScope.async { link.reauthenticate() }
        runCurrent()
        assertEquals(write("01 00 00"), ring.log.last())
        assertFalse(result.isCompleted, "the auth start is still in flight")

        ring.release(FakeGatt.Operation.WRITE) // 01 00 00 answered; the ring sends its challenge
        ring.hold(FakeGatt.Operation.WRITE) // …and the auth reply's own answer is held
        runCurrent()
        assertEquals(write("01 01 31 82 67 00"), ring.log.last())
        assertFalse(result.isCompleted, "the reply is written but not yet answered")

        ring.release(FakeGatt.Operation.WRITE)
        runCurrent()
        assertEquals(SendResult.Sent, result.await())
    }

    @Test
    fun noChallengeWithinFiveSecondsFailsTimedOutAndKeepsTheLink() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        runCurrent()
        ring.answersAuthStart = false

        val result = backgroundScope.async { link.reauthenticate() }
        runCurrent()
        advance(4_999)
        assertFalse(result.isCompleted, "still waiting at 4 999 ms")
        advance(1)

        assertEquals(SendResult.Failed(SendFailure.TIMED_OUT), result.await())
        assertEquals(LinkState.Authenticated, link.state.value)
        assertFalse("close" in ring.log, "the link is kept")
    }

    @Test
    fun aLinkThatIsNotAuthenticatedRefusesAndWritesNothing() = runTest {
        val (ring, link) = linkTo()

        val result = link.reauthenticate()
        runCurrent()

        assertEquals(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED), result)
        assertEquals(emptyList(), ring.log)
    }

    @Test
    fun aDropWhileWaitingForTheChallengeFailsLinkLost() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        recordTeardowns(link)
        runCurrent()
        ring.answersAuthStart = false

        val result = backgroundScope.async { link.reauthenticate() }
        runCurrent()
        ring.dropConnection(status = 8)
        runCurrent()

        assertEquals(SendResult.Failed(SendFailure.LINK_LOST), result.await())
    }

    @Test
    fun theAppStillCannotWriteTheAuthStartItself() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        runCurrent()
        val before = ring.log.size

        assertEquals(SendResult.Refused(RefusalReason.AUTH_COMMAND_RESERVED), link.send(hex("010000")))
        runCurrent()
        assertEquals(emptyList(), ring.log.drop(before))
    }
}
