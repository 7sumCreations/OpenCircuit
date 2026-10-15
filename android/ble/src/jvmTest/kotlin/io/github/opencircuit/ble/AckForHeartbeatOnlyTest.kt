package io.github.opencircuit.ble

import io.github.opencircuit.ble.Fixtures.hex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The link answers only the `0x11` heartbeat on its own. A history page (`0x47` / `0x4c` /
 * `0x4d`) is delivered to the app UNACKNOWLEDGED: a page the ring has an acknowledgement for is
 * gone from the ring for good, so only the app, once the page is stored, may acknowledge it
 * ([RingLink.acknowledge], PORTING.md D-261). Observed from the scripted ring's write log.
 */
class AckForHeartbeatOnlyTest {

    private fun write(hexBytes: String) = "write 8327ad98 $hexBytes"

    @Test
    fun pagesAreDeliveredWithNoAckAndOnlyTheHeartbeatIsAnsweredByTheLink() = runTest {
        val (ring, link) = authenticatedLink()
        val delivered = recordFrames(link)
        val before = ring.log.size
        val burst = listOf(
            Fixtures.sleepPage4c, Fixtures.heartbeat(1), Fixtures.ppgPage47Truncated, Fixtures.sportPage4dShort,
            Fixtures.sleepPage4cBadXor, Fixtures.heartbeat(2),
        )

        ring.notifyAll(burst)
        runCurrent()

        assertEquals(listOf(write("91 00 00"), write("91 00 00")), ring.log.drop(before), "one 91 00 00 per heartbeat, nothing for a page")
        assertEquals(listOf("15 00 08 0a b0 a7") + burst.map { it.hexString() }, delivered)
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun oneBytePagesGetNoAckWhileAOneByteHeartbeatStillDoes() = runTest {
        val (ring, _) = authenticatedLink()
        val before = ring.log.size

        ring.notifyAll(listOf(hex("4c"), hex("47"), hex("4d"), hex("11")))
        runCurrent()

        assertEquals(listOf(write("91 00 00")), ring.log.drop(before))
    }
}
