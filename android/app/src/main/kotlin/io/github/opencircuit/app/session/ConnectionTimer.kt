package io.github.opencircuit.app.session

import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's own measurements of one ring's connections, for Connection details. */
data class ConnectionTimings(
    /** From the last `connect()` to [LinkState.Authenticated]; null until one finished. */
    val connectToAuthenticatedMillis: Long? = null,
    /** The link asked for the pairing prompt at least once. */
    val pairingNeededSeen: Boolean = false,
    /** The link is waiting for the pairing prompt now. */
    val pairingNeededNow: Boolean = false,
    /** How long the last pairing prompt waited; null while it waits or before any. */
    val pairingNeededMillis: Long? = null,
    /** From the last loss of an authenticated connection until it was authenticated again; null until one. */
    val reconnectWaitMillis: Long? = null,
)

/**
 * Times the link's steps on a monotonic clock: connect → authenticated, how long the pairing
 * prompt waited, and how long the last reconnect took. Fed by the session (its `connect()` and
 * every state the link publishes). Only one session feeds it, from one collection.
 */
class ConnectionTimer(private val monotonicMillis: () -> Long) {

    private val timingsFlow = MutableStateFlow(ConnectionTimings())
    private var connectAt: Long? = null
    private var lostAt: Long? = null
    private var pairingSince: Long? = null
    private var last: LinkState = LinkState.Idle

    /** What was measured so far. */
    val timings: StateFlow<ConnectionTimings> = timingsFlow.asStateFlow()

    /** `connect()` was asked for; times it unless the link is already authenticated. */
    @Synchronized
    fun onConnect() {
        if (last != LinkState.Authenticated && connectAt == null) connectAt = monotonicMillis()
    }

    /** The link published [state]. */
    @Synchronized
    fun onState(state: LinkState) {
        val now = monotonicMillis()
        val previous = last
        last = state
        var timings = timingsFlow.value
        if (state == LinkState.PairingNeeded && previous != LinkState.PairingNeeded) {
            pairingSince = now
            timings = timings.copy(pairingNeededSeen = true, pairingNeededNow = true, pairingNeededMillis = null)
        } else if (state != LinkState.PairingNeeded && previous == LinkState.PairingNeeded) {
            timings = timings.copy(pairingNeededNow = false, pairingNeededMillis = pairingSince?.let { now - it })
            pairingSince = null
        }
        when {
            state == LinkState.Authenticated -> {
                connectAt?.let { timings = timings.copy(connectToAuthenticatedMillis = now - it) }
                lostAt?.let { timings = timings.copy(reconnectWaitMillis = now - it) }
                connectAt = null
                lostAt = null
            }
            // The user's own disconnect: nothing in flight is measured any more. (The first Idle the
            // collection sees is the link's starting state, which may arrive after connect() was asked.)
            state == LinkState.Idle && previous != LinkState.Idle -> {
                connectAt = null
                lostAt = null
            }
            previous == LinkState.Authenticated && state != LinkState.NotStreaming -> lostAt = now
        }
        timingsFlow.value = timings
    }
}
