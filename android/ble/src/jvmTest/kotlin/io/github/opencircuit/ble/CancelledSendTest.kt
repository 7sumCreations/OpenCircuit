package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.Fixtures.hex
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A caller of `send` that is cancelled while its command still waits in the queue takes the
 * command with it: it is never written to the ring. A command already written cannot be recalled:
 * it completes, and the queue moves on when the ring answers it, as for any other write.
 */
class CancelledSendTest {

    /** The commands written to the ring's write characteristic so far, as hex. */
    private fun writes(ring: FakeGatt): List<String> =
        ring.log.filter { it.startsWith(WRITE_PREFIX) }.map { it.removePrefix(WRITE_PREFIX) }

    @Test
    fun aQueuedWriteWhoseCallerWasCancelledIsNeverWritten() = runTest {
        val (ring, link) = authenticatedLink()
        val before = writes(ring)
        ring.hold(Operation.WRITE)
        val first = backgroundScope.async { link.send(hex("950000")) }
        runCurrent()
        val second = backgroundScope.async { link.send(hex("960000")) }
        val third = backgroundScope.async { link.send(hex("970000")) }
        runCurrent()

        second.cancel()
        runCurrent()
        ring.release(Operation.WRITE)
        runCurrent()

        assertEquals(before + listOf("95 00 00", "97 00 00"), writes(ring))
        assertEquals(SendResult.Sent, first.getCompleted())
        assertEquals(SendResult.Sent, third.getCompleted())
        assertTrue(second.isCancelled)
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(emptyList(), ring.violations)
    }

    @Test
    fun aWriteAlreadyInFlightWhenItsCallerIsCancelledCompletesAndTheQueueMovesOn() = runTest {
        val (ring, link) = authenticatedLink()
        val before = writes(ring)
        ring.hold(Operation.WRITE)
        val first = backgroundScope.async { link.send(hex("950000")) }
        runCurrent()
        val second = backgroundScope.async { link.send(hex("960000")) }
        runCurrent()

        first.cancel()
        runCurrent()
        ring.release(Operation.WRITE)
        runCurrent()

        assertEquals(before + listOf("95 00 00", "96 00 00"), writes(ring))
        assertEquals(SendResult.Sent, second.getCompleted())
        assertEquals(LinkState.Authenticated, link.state.value)
        assertEquals(emptyList(), ring.violations)
    }

    private companion object {
        const val WRITE_PREFIX = "write 8327ad98 "
    }
}
