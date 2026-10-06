package io.github.opencircuit.ble

import io.github.opencircuit.ble.FakeGatt.ConnectCall
import io.github.opencircuit.ble.FakeGatt.Operation
import io.github.opencircuit.ble.ReconnectPolicy.Failure
import io.github.opencircuit.ble.ReconnectPolicy.Plan
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the link reconnects (PORTING.md D-186): by address, direct first at the backoff delays
 * 1 / 5 / 30 s; once three reconnects have failed, or a connect failed in a way that means the
 * ring is out of reach (status 147, or 133 at least 20 s after the connect started), one standing
 * connection that waits for the ring with no timeout; a standing connection that fails before
 * connecting goes back to direct without forgetting the count.
 */
class ReconnectPolicyTest {

    private fun s(seconds: Long): Duration = Duration.ofSeconds(seconds)

    // The policy alone.

    @Test
    fun directReconnectsBackOffAtOneFiveAndThirtySecondsThenTheStandingConnectionTakesOver() {
        val plans = generateSequence(ReconnectPolicy.next(0, Failure.FAILED)) { ReconnectPolicy.next(it.attempt, Failure.FAILED) }
            .take(5).toList()
        assertEquals(
            listOf(
                Plan(attempt = 1, delay = s(1), standing = false),
                Plan(attempt = 2, delay = s(5), standing = false),
                Plan(attempt = 3, delay = s(30), standing = false),
                Plan(attempt = 4, delay = s(30), standing = true),
                Plan(attempt = 5, delay = s(30), standing = true),
            ),
            plans,
        )
    }

    @Test
    fun anUnreachableRingGetsTheStandingConnectionAtOnce() {
        assertEquals(Plan(attempt = 1, delay = s(1), standing = true), ReconnectPolicy.next(0, Failure.UNREACHABLE))
        assertEquals(Plan(attempt = 2, delay = s(5), standing = true), ReconnectPolicy.next(1, Failure.UNREACHABLE))
    }

    @Test
    fun aStandingConnectionThatFailsGoesBackToDirectWithTheCountIntact() {
        assertEquals(Plan(attempt = 4, delay = s(30), standing = false), ReconnectPolicy.next(4, Failure.STANDING_FAILED))
        assertEquals(Plan(attempt = 1, delay = s(1), standing = false), ReconnectPolicy.next(1, Failure.STANDING_FAILED))
        // The direct attempt after it fails as before, and the standing connection comes back.
        assertEquals(Plan(attempt = 5, delay = s(30), standing = true), ReconnectPolicy.next(4, Failure.FAILED))
    }

    @Test
    fun theDelaysAreTheForegroundOnesNeverTheBackgroundCap() {
        // The background cap would be 8 s; the link always runs in the foreground until background work exists.
        assertEquals(s(30), ReconnectPolicy.next(2, Failure.FAILED).delay)
        assertEquals(s(30), ReconnectPolicy.next(9, Failure.FAILED).delay)
    }

    @Test
    fun onlyAConnectionTimeoutOrALate133MeansTheRingIsOutOfReach() {
        assertEquals(Failure.UNREACHABLE, ReconnectPolicy.classifyOpenFailure(status = 147, late = false))
        assertEquals(Failure.UNREACHABLE, ReconnectPolicy.classifyOpenFailure(status = 147, late = true))
        assertEquals(Failure.UNREACHABLE, ReconnectPolicy.classifyOpenFailure(status = 133, late = true))
        assertEquals(Failure.FAILED, ReconnectPolicy.classifyOpenFailure(status = 133, late = false))
        assertEquals(Failure.FAILED, ReconnectPolicy.classifyOpenFailure(status = 8, late = true))
        assertEquals(Failure.FAILED, ReconnectPolicy.classifyOpenFailure(status = 0, late = true))
    }

    // Through the link.

    /** Connects to a ring whose first connect is answered with [status] after [millis] of virtual time. */
    private fun TestScope.connectFailingWith(status: Int, millis: Long): Pair<FakeGatt, RingLink> {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.failWith(Operation.CONNECT, status)
        ring.hold(Operation.CONNECT)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        link.connect()
        runCurrent()
        advance(millis)
        ring.release(Operation.CONNECT)
        ring.clearFailure(Operation.CONNECT)
        runCurrent()
        return ring to link
    }

    @Test
    fun a133TwentySecondsAfterTheConnectStartedArmsTheStandingConnection() = runTest {
        val (ring, link) = connectFailingWith(status = 133, millis = 20_000)

        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        ring.hold(Operation.CONNECT)
        advance(1_000)

        assertEquals(
            listOf(ConnectCall(Fixtures.RING_ADDRESS, autoConnect = false), ConnectCall(Fixtures.RING_ADDRESS, autoConnect = true)),
            ring.connects,
        )
        assertEquals(LinkState.WaitingForRing, link.state.value)
    }

    @Test
    fun a133OneMillisecondBeforeTwentySecondsRetriesDirect() = runTest {
        val (ring, link) = connectFailingWith(status = 133, millis = 19_999)

        assertEquals(reconnecting(attempt = 1, seconds = 1), link.state.value)
        ring.hold(Operation.CONNECT)
        advance(1_000)

        assertEquals(listOf(false, false), ring.connects.map { it.autoConnect })
        assertEquals(LinkState.Connecting, link.state.value)
    }

    @Test
    fun a147AtOnceArmsTheStandingConnection() = runTest {
        val (ring, link) = connectFailingWith(status = 147, millis = 0)

        ring.hold(Operation.CONNECT)
        advance(1_000)

        assertEquals(listOf(false, true), ring.connects.map { it.autoConnect })
        assertEquals(LinkState.WaitingForRing, link.state.value)
    }

    @Test
    fun aStandingConnectionFailingFastReturnsToDirectWithTheCountIntact() = runTest {
        val (ring, link) = connectFailingWith(status = 147, millis = 0)
        val states = recordStates(link)

        ring.failWith(Operation.CONNECT, 133) // the standing connection fails at once
        advance(1_000)
        ring.clearFailure(Operation.CONNECT)
        ring.hold(Operation.CONNECT)

        // Still attempt 1: a failed standing connection does not count as another attempt.
        assertEquals(listOf(reconnecting(1, 1), LinkState.WaitingForRing, reconnecting(1, 1)), states)
        advance(999)
        assertEquals(2, ring.connects.size)
        advance(1)
        assertEquals(listOf(false, true, false), ring.connects.map { it.autoConnect })
        assertEquals(LinkState.Connecting, link.state.value)
    }

    @Test
    fun theStandingConnectionTakesOverOnlyAfterThreeReconnectsFailed() = runTest {
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        ring.failWith(Operation.CONNECT, 133)
        val link = RingLink(Fixtures.ring, ring, backgroundScope)
        val states = recordStates(link)

        link.connect()
        runCurrent()
        advance(1_000)
        advance(5_000)
        advance(30_000)
        ring.hold(Operation.CONNECT)
        advance(30_000)

        assertEquals(listOf(false, false, false, false, true), ring.connects.map { it.autoConnect })
        assertEquals(
            listOf(
                LinkState.Idle,
                LinkState.Connecting, reconnecting(1, 1),
                LinkState.Connecting, reconnecting(2, 5),
                LinkState.Connecting, reconnecting(3, 30),
                LinkState.Connecting, reconnecting(4, 30),
                LinkState.WaitingForRing,
            ),
            states,
        )
    }
}
