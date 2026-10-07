package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.session.RingSessions
import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.SendResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A session task that throws (here the keepalive's write, through a link that breaks its
 * never-throws contract) must not take the session's one collection of the link's frames down
 * with it, nor reach the app's scope: it is logged and the frames keep flowing.
 */
class SessionContainmentTest {

    private val ring = RememberedRing("AA:BB:CC:DD:EE:FF", AddressType.RANDOM, "Test ring")

    /** A link whose writes throw instead of returning a [SendResult]. */
    private class ThrowingSendLink(val fake: FakeRingLink) : RingLink by fake {
        override suspend fun send(command: ByteArray): SendResult = throw IllegalStateException("broken link")
    }

    @Test
    fun aThrowingSessionTaskIsLoggedAndTheFramesKeepFlowing() = runTest {
        val logLines = mutableListOf<String>()
        val fake = FakeRingLink(ring)
        var controller: RingSessionController? = null
        val sessions = RingSessions(
            links = { ThrowingSendLink(fake) },
            rings = PrefsRememberedRingStore(InMemoryKeyValues()),
            companion = {},
            scope = backgroundScope,
            newSession = { link, scope ->
                RingSessionController(link, scope, monotonicMillis = { testScheduler.currentTime }, log = { logLines += it })
                    .also { controller = it }
            },
            log = { logLines += it },
        )
        sessions.connect(ring)
        runCurrent()

        fake.setState(LinkState.Authenticated) // the keepalive writes `d0 00 00` at once: it throws
        runCurrent()
        fake.emitFrame(TestFrames.wornDescriptor)
        runCurrent()

        assertEquals(66, controller!!.deviceStatus.state.value.batteryPercent, "frames still reach their handler")
        assertTrue(logLines.any { "IllegalStateException" in it }, "the failure is logged: $logLines")
    }
}
