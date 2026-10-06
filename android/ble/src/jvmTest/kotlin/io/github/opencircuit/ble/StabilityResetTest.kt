package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.Operation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The failed-attempt count is forgotten only when a connection both lasted 6 s and delivered a
 * frame, checked once, 6 s after it connected (upstream `armConnectStabilityReset`,
 * `ios/OpenCircuit/BLE/RingScanner.swift:977-986` @ b1c2fdd). A ring on its charger that accepts
 * a connection and drops it, or a connection that never delivers a frame, keeps the backoff
 * growing. "Forgotten" shows as the next drop waiting 1 s (attempt 1) instead of 5 s (attempt 2).
 */
class StabilityResetTest {

    /**
     * A link whose first connect failed (so one reconnect is counted) and whose reconnect then
     * succeeded at 1 s; the ring sends nothing unless the test makes it. Returns at the moment
     * the second connection connected.
     */
    private fun TestScope.reconnectedOnce(): Pair<FakeGatt, RingLink> {
        val ring = FakeGatt(backgroundScope, quietRing())
        ring.failWith(Operation.CONNECT, 133)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()
        ring.clearFailure(Operation.CONNECT)
        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        advance(1_000)
        assertEquals(LinkState.Authenticating, link.state.value, "the second connection is up")
        return ring to link
    }

    @Test
    fun sixSecondsConnectedWithoutAFrameKeepTheCount() = runTest {
        val (ring, link) = reconnectedOnce()

        advance(6_000)
        ring.dropConnection(8)
        runCurrent()

        assertEquals(reconnecting(attempt = 2, seconds = 5), link.state.value)
    }

    @Test
    fun aFrameAndSixSecondsConnectedForgetTheCount() = runTest {
        val (ring, link) = reconnectedOnce()

        ring.notify(Fixtures.firstDataFrame)
        advance(6_000)
        ring.dropConnection(8)
        runCurrent()

        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
    }

    @Test
    fun aFrameButADropOneMillisecondBeforeSixSecondsKeepsTheCount() = runTest {
        val (ring, link) = reconnectedOnce()

        advance(5_900)
        ring.notify(Fixtures.firstDataFrame)
        advance(99)
        ring.dropConnection(8)
        runCurrent()

        assertEquals(reconnecting(attempt = 2, seconds = 5), link.state.value)
    }

    @Test
    fun aFrameThatArrivesAfterTheSixSecondCheckDoesNotForgetTheCount() = runTest {
        // Upstream checks once, 6 s after connecting; a later frame does not reopen the check.
        val (ring, link) = reconnectedOnce()

        advance(6_500)
        ring.notify(Fixtures.firstDataFrame)
        advance(3_500)
        ring.dropConnection(8)
        runCurrent()

        assertEquals(reconnecting(attempt = 2, seconds = 5), link.state.value)
    }

    @Test
    fun theCheckBelongsToItsOwnConnection() = runTest {
        // A connection that dropped before its check cannot be vouched for by the next one.
        val (ring, link) = reconnectedOnce()
        ring.notify(Fixtures.firstDataFrame)
        advance(3_000)
        ring.dropConnection(8)
        runCurrent()
        assertEquals(reconnecting(attempt = 2, seconds = 5), link.state.value)

        advance(5_000) // the third connection is up at 9 s; the dropped one's check would have run at 7 s
        advance(6_000) // the third connection's own check: it delivered no frame
        ring.dropConnection(8)
        runCurrent()

        assertEquals(reconnecting(attempt = 3, seconds = 30), link.state.value)
    }
}
