package io.github.opencircuit.app.session

import io.github.opencircuit.app.RingLinkFactory
import io.github.opencircuit.app.connect.RingConnector
import io.github.opencircuit.app.data.RememberedRingStore
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Removes the companion-device (CDM) associations the app holds for a ring's address. */
fun interface Disassociator {
    fun disassociate(address: String)
}

/** The session the Ring screen shows, and what the screen can do with the saved ring. */
interface SessionHost {
    /** The session with the remembered ring, or null when there is none. */
    val current: StateFlow<RingSessionController?>

    /** The screen opened: connect the remembered ring by its address, with no scan. */
    fun reconnectRemembered()

    /** Forget the ring: stop reconnecting to it, for good. */
    fun stopReconnecting()
}

/**
 * The app's sessions with its remembered ring, one link per ring, kept (upstream remembered its
 * peripheral and reconnected it, `ios/OpenCircuit/BLE/RingScanner.swift:96-164`, and forgot it in
 * `forgetActiveRing`, `:615-630` @ b1c2fdd).
 *
 * The Android link listens for Bluetooth and bond changes from the moment it is built until it is
 * closed, so a second link for the same ring would leak those listeners. One [RingLink] (and the
 * one [RingSessionController] collecting it) is therefore built per ring and reused: reopening the
 * app, Try again, and pairing the same ring again all connect the same link. Pairing a different
 * ring retires the old link first (disconnect, then `close()`).
 *
 * Each session runs in its own child of [scope], cancelled when its link is retired.
 * Main-thread only.
 */
class RingSessions(
    private val links: RingLinkFactory,
    private val rings: RememberedRingStore,
    private val companion: Disassociator,
    private val scope: CoroutineScope,
    /** Builds the session for a new link, in that session's own scope. */
    private val newSession: (RingLink, CoroutineScope) -> RingSessionController,
    private val log: (String) -> Unit,
) : SessionHost, RingConnector {

    private val currentFlow = MutableStateFlow<RingSessionController?>(null)
    private var sessionScope: CoroutineScope? = null

    override val current: StateFlow<RingSessionController?> = currentFlow.asStateFlow()

    override fun reconnectRemembered() {
        val session = currentFlow.value ?: rings.load()?.let(::open) ?: return
        session.connect()
    }

    /**
     * A ring was paired (or the pairing sheet could not help and the app connects anyway):
     * remember it and connect. The same ring as the current session keeps its link.
     */
    override fun connect(ring: RememberedRing) {
        if (!rings.save(ring)) log("Could not save the ring; it connects now but will not reconnect after a restart")
        val existing = currentFlow.value
        val session = if (existing != null && RingAddress.same(existing.link.ring.address, ring.address)) {
            existing
        } else {
            existing?.let(::retire)
            open(ring)
        }
        session.connect()
    }

    /**
     * Stop reconnecting: a running measure stops first (so the user's own action is never shown
     * as a lost ring), then the link is disconnected and closed, the saved ring is forgotten and
     * its CDM associations are removed. The phone's bond with the ring is left alone.
     */
    override fun stopReconnecting() {
        val session = currentFlow.value
        val address = session?.link?.ring?.address ?: rings.load()?.address
        session?.let(::retire)
        if (!rings.clear()) log("Could not forget the saved ring; it may reconnect after a restart")
        address?.let(companion::disassociate)
    }

    private fun open(ring: RememberedRing): RingSessionController {
        val childScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        val session = newSession(links.create(ring), childScope)
        sessionScope = childScope
        currentFlow.value = session
        return session
    }

    /** Ends [session] for good: no measure, no connection, no listeners, nothing collecting. */
    private fun retire(session: RingSessionController) {
        currentFlow.value = null
        session.liveMeasure.stop()
        session.link.disconnect()
        (session.link as? AutoCloseable)?.close()
        sessionScope?.cancel()
        sessionScope = null
    }
}
