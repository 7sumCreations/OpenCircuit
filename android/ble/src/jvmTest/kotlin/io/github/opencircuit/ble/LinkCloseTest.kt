package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A link whose life ended (its scope was cancelled) closes its connection and shows `Idle`: a
 * screen still watching it never shows a live link that is gone. A link that owns its scope (the
 * Android factory's) can be ended by its holder with `close()`, which is how the app releases a
 * ring it forgets or replaces, and with it the link's Bluetooth receivers.
 */
class LinkCloseTest {

    @Test
    fun aLinkWhoseScopeEndedClosesTheConnectionAndShowsIdle() = runTest {
        val linkScope = CoroutineScope(coroutineContext + Job())
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, linkScope)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)

        linkScope.cancel()
        runCurrent()

        assertEquals("close", ring.log.last())
        assertEquals(LinkState.Idle, link.state.value)
    }

    @Test
    fun closingALinkThatOwnsItsScopeClosesTheConnectionAndEndsTheLinkForGood() = runTest {
        val owner = Job()
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link: RingLink = OwnedRingLink(LinkCore(Fixtures.ring, ring, CoroutineScope(coroutineContext + owner)), owner)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)
        assertNotNull(link as? LinkDiagnostics, "the diagnostics stay reachable")

        (link as AutoCloseable).close()
        runCurrent()

        assertTrue(owner.isCancelled, "the link's scope ended, so the Android receivers are unregistered")
        assertEquals("close", ring.log.last())
        assertEquals(LinkState.Idle, link.state.value)

        link.connect()
        (link as AutoCloseable).close()
        runCurrent()
        assertEquals(1, ring.log.count { it.startsWith("connect") }, "a closed link never connects again")
        assertEquals(SendResult.Failed(SendFailure.LINK_LOST), link.send(Fixtures.hex("950000")))
    }

    @Test
    fun aLinkWhoseScopeEndedPublishesTheTeardownAndCountsTheFramesNobodyTook() = runTest {
        val linkScope = CoroutineScope(coroutineContext + Job())
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link = RingLink(Fixtures.ring, ring, linkScope)
        val teardowns = recordTeardowns(link)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)

        linkScope.cancel()
        runCurrent()

        // The frame that authenticated the connection was never collected.
        assertEquals(listOf(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 1)), teardowns)
    }

    @Test
    fun closingALinkThatOwnsItsScopePublishesExactlyOneTeardown() = runTest {
        val owner = Job()
        val ring = FakeGatt(backgroundScope, Fixtures.acceptingRing())
        val link: RingLink = OwnedRingLink(LinkCore(Fixtures.ring, ring, CoroutineScope(coroutineContext + owner)), owner)
        val teardowns = recordTeardowns(link)
        link.connect()
        runCurrent()
        assertEquals(LinkState.Authenticated, link.state.value)

        (link as AutoCloseable).close()
        runCurrent()

        assertEquals(listOf(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 1)), teardowns)
    }
}
