package io.github.opencircuit.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The link to one remembered ring: connects, bonds, authenticates, keeps the data flowing and
 * reconnects. One instance per [RememberedRing]; reused across every connection to that ring.
 */
interface RingLink {
    /** The ring this link talks to. */
    val ring: RememberedRing

    /** Where the link stands. */
    val state: StateFlow<LinkState>

    /** What the link learned about the ring on the current connection. */
    val info: StateFlow<LinkInfo>

    /**
     * Every frame the ring sends except the `81 00` auth challenge the link answers itself, in
     * arrival order. EXACTLY ONE collector: a second collection fails with an exception. Frames
     * wait here while nobody collects.
     */
    val frames: Flow<ByteArray>

    /** One [LinkTeardown] per torn-down connection. EXACTLY ONE collector, as for [frames]. */
    val teardowns: Flow<LinkTeardown>

    /**
     * Writes [command] to the ring once the link is [LinkState.Authenticated]. Never throws:
     * a refusal or a failure is returned as a [SendResult].
     */
    suspend fun send(command: ByteArray): SendResult

    /** Starts connecting (no scan: by the ring's address). Does nothing while already connecting or connected. */
    fun connect()

    /** Closes the connection and stays [LinkState.Idle] until [connect] is called again. */
    fun disconnect()
}

/**
 * Builds a [RingLink] over any [GattPort]: the seam the tests use with the scripted fake ring.
 * The link's event loop runs in [scope], one event at a time; cancelling [scope] ends the link.
 * The app uses the Android factory, which supplies the Android GATT port.
 */
fun RingLink(ring: RememberedRing, port: GattPort, scope: CoroutineScope): RingLink =
    LinkCore(ring, port, scope)
