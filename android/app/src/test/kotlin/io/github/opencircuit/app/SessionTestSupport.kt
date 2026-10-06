package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.session.RingSessions
import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.test.TestScope

/**
 * The scripted fake link with the two things the Android link adds that the fake does not:
 * `close()` (it is [AutoCloseable]) and what `disconnect()` and `close()` publish. Like the real
 * link, both go [LinkState.Idle] and publish one user-disconnected teardown for a connection that
 * had connected; a closed link connects no more. Every call is appended to [events].
 */
internal class ClosableFakeLink(
    val fake: FakeRingLink,
    private val events: MutableList<String> = mutableListOf(),
) : RingLink by fake, AutoCloseable {
    var closed = false
        private set

    override fun connect() {
        events += "connect"
        if (!closed) fake.connect()
    }

    override fun disconnect() {
        events += "disconnect"
        fake.disconnect()
        endConnection()
    }

    override fun close() {
        events += "close"
        closed = true
        endConnection()
    }

    private fun endConnection() {
        if (fake.state.value == LinkState.Idle) return
        fake.setState(LinkState.Idle)
        fake.emitTeardown(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 0))
    }
}

/** A link factory that builds a [ClosableFakeLink] per call and keeps every link it built. */
internal class CountingLinkFactory(private val events: MutableList<String> = mutableListOf()) {
    val built = mutableListOf<ClosableFakeLink>()

    fun create(ring: RememberedRing): RingLink {
        events += "build"
        return ClosableFakeLink(FakeRingLink(ring), events).also { built += it }
    }
}

/** Every address passed to `disassociate`, in order. */
internal class RecordingDisassociator(private val events: MutableList<String> = mutableListOf()) {
    val addresses = mutableListOf<String>()

    fun disassociate(address: String) {
        events += "disassociate"
        addresses += address
    }
}

/** Ring sessions over in-memory preferences, a counting link factory and a recording CDM, on this test's virtual time. */
internal class SessionsUnderTest(
    val values: InMemoryKeyValues,
    val factory: CountingLinkFactory,
    val companion: RecordingDisassociator,
    val events: MutableList<String>,
    val sessions: RingSessions,
) {
    val store = PrefsRememberedRingStore(values)
}

internal fun TestScope.sessionsUnderTest(values: InMemoryKeyValues = InMemoryKeyValues()): SessionsUnderTest {
    val events = mutableListOf<String>()
    val factory = CountingLinkFactory(events)
    val companion = RecordingDisassociator(events)
    val sessions = RingSessions(
        links = factory::create,
        rings = PrefsRememberedRingStore(values),
        companion = companion::disassociate,
        scope = backgroundScope,
        newSession = { link, scope -> RingSessionController(link, scope, monotonicMillis = { testScheduler.currentTime }, log = {}) },
        log = {},
    )
    return SessionsUnderTest(values, factory, companion, events, sessions)
}

/**
 * Ring sessions that hold exactly [controller]: its ring is remembered, the factory hands out its
 * link, and opening the screen connects it, as the app does on launch with a remembered ring.
 */
internal fun TestScope.sessionsOf(controller: RingSessionController): RingSessions {
    val store = PrefsRememberedRingStore(InMemoryKeyValues())
    check(store.save(controller.link.ring))
    return RingSessions(
        links = { controller.link },
        rings = store,
        companion = {},
        scope = backgroundScope,
        newSession = { _, _ -> controller },
        log = {},
    )
}
