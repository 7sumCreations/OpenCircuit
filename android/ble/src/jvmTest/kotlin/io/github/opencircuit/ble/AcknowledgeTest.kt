package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RingLink.acknowledge]: the app's way to tell the ring a history page is stored, so the ring
 * moves on to the next one (PORTING.md D-261). The link writes the page's acknowledgement
 * (`c7` / `cc` / `cd 00 00` by its opcode) once, on the lane that goes ahead of waiting feature
 * writes without cutting into the write in flight.
 *
 * The acknowledgement carries no page identity, so the link writes it only for a page THIS
 * connection delivered and nobody acknowledged yet, matched by its bytes: an acknowledgement for
 * a page of a torn-down connection would otherwise acknowledge whatever page the ring offers on
 * the new one, which nobody has stored. A re-offered page with the same bytes is the same stored
 * page and may be acknowledged. Every outcome is read from the scripted ring's write log.
 */
class AcknowledgeTest {

    private fun write(hexBytes: String) = "write 8327ad98 $hexBytes"

    // A second, different 0x4c page (the real page's first record moved one epoch on), XOR fixed.
    private val otherSleepPage: ByteArray = Fixtures.sleepPage4c.copyOf().also { page ->
        page[6] = (page[6] + 1).toByte()
        page[page.size - 1] = page.dropLast(1).fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }.toByte()
    }

    @Test
    fun eachPageOpcodeGetsItsOwnAckWrittenOnceWhenAcknowledged() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        val pages = listOf(Fixtures.sleepPage4c, Fixtures.ppgPage47Truncated, Fixtures.sportPage4dShort)

        ring.notifyAll(pages)
        runCurrent()
        val before = ring.log.size
        val results = pages.map { page -> backgroundScope.async { link.acknowledge(page) } }
        runCurrent()

        assertEquals(listOf(SendResult.Sent, SendResult.Sent, SendResult.Sent), results.map { it.await() })
        assertEquals(listOf(write("cc 00 00"), write("c7 00 00"), write("cd 00 00")), ring.log.drop(before))
    }

    @Test
    fun aFrameThatIsNotAPageIsRefusedAndNothingIsWritten() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        val notPages = listOf(Fixtures.descriptor10, hex("82000082"), hex("50000012"), Fixtures.heartbeat(1), hex("8101aa"), ByteArray(0))
        ring.notifyAll(notPages.filter { it.isNotEmpty() })
        runCurrent()
        val before = ring.log.size

        val results = notPages.map { link.acknowledge(it) }
        runCurrent()

        results.forEach { assertEquals(SendResult.Refused(RefusalReason.NOT_A_PAGE), it) }
        assertEquals(emptyList(), ring.log.drop(before))
    }

    @Test
    fun theAckGoesAheadOfQueuedFeatureWritesWithoutCuttingIntoTheOneInFlight() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        ring.notify(Fixtures.sleepPage4c)
        runCurrent()
        ring.hold(Operation.WRITE)
        val inFlight = backgroundScope.async { link.send(hex("950000")) }
        runCurrent()
        val queued1 = backgroundScope.async { link.send(hex("d00000")) }
        val queued2 = backgroundScope.async { link.send(hex("070000")) }
        runCurrent()

        val ack = backgroundScope.async { link.acknowledge(Fixtures.sleepPage4c) }
        runCurrent()
        val whileInFlight = ring.log.last()
        ring.release(Operation.WRITE)
        runCurrent()

        assertEquals(write("95 00 00"), whileInFlight, "the write in flight is never cut into")
        assertEquals(listOf(write("95 00 00"), write("cc 00 00"), write("d0 00 00"), write("07 00 00")), ring.log.takeLast(4))
        assertEquals(listOf(SendResult.Sent, SendResult.Sent, SendResult.Sent, SendResult.Sent), listOf(inFlight, ack, queued1, queued2).map { it.await() })
    }

    @Test
    fun aPageIsAcknowledgedAtMostOnce() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        ring.notify(Fixtures.sleepPage4c)
        runCurrent()
        val before = ring.log.size

        val first = backgroundScope.async { link.acknowledge(Fixtures.sleepPage4c) }
        runCurrent()
        val second = link.acknowledge(Fixtures.sleepPage4c)
        runCurrent()

        assertEquals(SendResult.Sent, first.await())
        assertEquals(SendResult.Refused(RefusalReason.PAGE_NOT_PENDING), second)
        assertEquals(listOf(write("cc 00 00")), ring.log.drop(before))
    }

    @Test
    fun aPageNoConnectionDeliveredIsRefused() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        val before = ring.log.size

        assertEquals(SendResult.Refused(RefusalReason.PAGE_NOT_PENDING), link.acknowledge(Fixtures.sleepPage4c))
        runCurrent()
        assertEquals(emptyList(), ring.log.drop(before))
    }

    @Test
    fun aPageOfATornDownConnectionIsNeverAcknowledgedOnTheNextOne() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        ring.notify(Fixtures.sleepPage4c)
        runCurrent()

        ring.dropConnection(8)
        advance(1_000) // reconnect and authenticate again
        ring.notify(otherSleepPage) // the ring offers a page nobody stored
        runCurrent()
        val before = ring.log.size
        val stale = link.acknowledge(Fixtures.sleepPage4c)
        runCurrent()

        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(SendResult.Refused(RefusalReason.PAGE_NOT_PENDING), stale)
        assertEquals(emptyList(), ring.log.drop(before), "no ACK for the page the new connection offered")
    }

    @Test
    fun aReOfferedPageWithTheSameBytesIsAcknowledgedOnTheNewConnection() = runTest {
        val (ring, link) = authenticatedLink()
        recordFrames(link)
        ring.notify(Fixtures.sleepPage4c)
        runCurrent()

        ring.dropConnection(8)
        advance(1_000)
        ring.notify(Fixtures.sleepPage4c) // the first un-acknowledged page, offered again
        runCurrent()
        val before = ring.log.size
        val result = backgroundScope.async { link.acknowledge(Fixtures.sleepPage4c) }
        runCurrent()

        assertEquals(SendResult.Sent, result.await())
        assertEquals(listOf(write("cc 00 00")), ring.log.drop(before))
    }

    @Test
    fun aTeardownCountsThePagesNeverAcknowledgedApartFromTheFramesNeverCollected() = runTest {
        val (ring, link) = authenticatedLink()
        val teardowns = recordTeardowns(link)
        recordFrames(link)
        ring.notifyAll(listOf(Fixtures.sleepPage4c, otherSleepPage, Fixtures.ppgPage47Truncated))
        runCurrent()
        backgroundScope.async { link.acknowledge(otherSleepPage) }
        runCurrent()

        link.disconnect()
        runCurrent()

        assertEquals(listOf(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 0, pagesUnacknowledged = 2)), teardowns)
    }
}
