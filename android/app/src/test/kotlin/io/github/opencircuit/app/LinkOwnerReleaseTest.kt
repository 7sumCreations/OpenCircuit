package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMeasureController
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.session.LinkOwner
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link's one claim is let go on every way a measure or a sync can end — or the other side
 * would be held off for the rest of the session — and only by the claim that holds it.
 */
class LinkOwnerReleaseTest {

    @Test
    fun aClaimFreesTheLinkOnlyWhileItHoldsIt() {
        val owner = LinkOwner()
        val first = assertNotNull(owner.claim(LinkOwner.Kind.MEASURE_AUTO))
        assertNull(owner.claim(LinkOwner.Kind.SYNC), "held: no second claim")
        val handed = assertNotNull(owner.transfer(first, LinkOwner.Kind.MEASURE_USER))
        assertNull(owner.transfer(first, LinkOwner.Kind.MEASURE_USER), "an old claim hands nothing over")
        assertFalse(owner.release(first), "an old claim frees nothing")
        assertEquals(LinkOwner.Kind.MEASURE_USER, owner.current?.kind)
        assertTrue(owner.release(handed))
        assertFalse(owner.release(handed), "released once; a second release changes nothing")
        assertNotNull(owner.claim(LinkOwner.Kind.SYNC), "free again")
    }

    private fun TestScope.measure(owner: LinkOwner, link: TimedRingLink = timedLink()) =
        LiveMeasureController(send = link::send, scope = backgroundScope, monotonicMillis = { testScheduler.currentTime }, owner = owner)

    @Test
    fun everyWayAMeasureEndsFreesTheLink() = runTest {
        val owner = LinkOwner()
        val link = timedLink()
        val measure = measure(owner, link)
        val ends: List<Pair<String, () -> Unit>> = listOf(
            "Stop" to { measure.stop() },
            "the link lost" to { measure.onLinkLost() },
            "a yield" to { measure.yieldAuto() },
            "the budget" to { advanceTo(testScheduler.currentTime + 100_000) },
            "a refused write" to {
                link.fake.answerSendsWith(SendResult.Refused(RefusalReason.NOT_BONDED))
                advanceTo(testScheduler.currentTime + 3_000)
            },
        )
        for ((how, end) in ends) {
            assertTrue(measure.startAuto(LiveMode.HEART_RATE), "starts before $how")
            runCurrent()
            assertEquals(LinkOwner.Kind.MEASURE_AUTO, owner.current?.kind, "held before $how")
            end()
            runCurrent()
            assertFalse(measure.isMeasuring.value, "ended by $how")
            assertNull(owner.current, "$how frees the link")
        }
    }

    @Test
    fun theUsersTapTakesTheAutomaticMeasuresClaimOverAndASyncCannotSlipIn() = runTest {
        val owner = LinkOwner()
        val measure = measure(owner)
        assertTrue(measure.startAuto(LiveMode.HEART_RATE))
        measure.start(LiveMode.SPO2)
        assertEquals(LinkOwner.Kind.MEASURE_USER, owner.current?.kind)
        assertNull(measure.yieldAutoThen { owner.claim(LinkOwner.Kind.SYNC) }, "the user's measure keeps the link from a sync")
        assertEquals(LiveMode.SPO2, measure.state.value.mode)
        measure.stop()
        assertNull(owner.current)
    }

    @Test
    fun aSyncHoldingTheLinkRefusesBothMeasureStarts() = runTest {
        val owner = LinkOwner()
        val measure = measure(owner)
        val sync = assertNotNull(owner.claim(LinkOwner.Kind.SYNC))
        assertFalse(measure.startAuto(LiveMode.HEART_RATE))
        measure.start(LiveMode.HEART_RATE)
        assertNull(measure.state.value.mode, "the user's tap starts nothing during a sync")
        assertEquals(sync, owner.current)
    }

    @Test
    fun aSyncCancelledBeforeItRanFreesTheLinkAndNoLongerSaysSyncing() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val session = RingSessionController(FakeRingLink(testRing), scope, monotonicMillis = { testScheduler.currentTime }, log = {})
        assertTrue(session.sync.syncNow())
        // The session's scope ends before the sync's coroutine ever ran.
        scope.cancel()
        runCurrent()
        // The claim and the flags agree: no "syncing" left behind to hold the keepalive, the button and the next sync off.
        assertFalse(session.sync.isSyncing.value, "isSyncing is released")
        assertFalse(session.sync.state.value.syncing, "the card no longer says syncing")
        assertTrue(session.liveMeasure.startAuto(LiveMode.HEART_RATE), "the link is free for a measure")
    }
}
