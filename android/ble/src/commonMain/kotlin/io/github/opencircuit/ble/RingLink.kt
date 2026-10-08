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
     * arrival order. EXACTLY ONE collector at a time: a collection started while another is
     * running throws [IllegalStateException]. Frames wait here while nobody collects; a collector
     * that stops or is cancelled leaves every frame it did not take for the next collector. Frames
     * still waiting when the connection is torn down are dropped and counted in
     * [LinkTeardown.undeliveredFrames].
     */
    val frames: Flow<ByteArray>

    /** One [LinkTeardown] per torn-down connection. EXACTLY ONE collector at a time, as for [frames]. */
    val teardowns: Flow<LinkTeardown>

    /**
     * Writes [command] to the ring once the link is [LinkState.Authenticated]. Never throws:
     * a refusal or a failure is returned as a [SendResult].
     */
    suspend fun send(command: ByteArray): SendResult

    /**
     * Tells the ring that [page], a history page (`0x47`, `0x4c` or `0x4d`) this link delivered on
     * [frames], is stored: the link writes its acknowledgement (`c7` / `cc` / `cd 00 00`), ahead
     * of waiting [send] writes, and the ring moves on to its next page. The link never
     * acknowledges a page by itself (only the `0x11` heartbeat): a page with an acknowledgement is
     * gone from the ring for good, so call this only once the page is durably stored.
     *
     * Written only for a page the CURRENT connection delivered and nobody acknowledged yet, matched
     * by its bytes: anything else is refused with [RefusalReason.PAGE_NOT_PENDING] and nothing is
     * written (an acknowledgement names no page, so one meant for a torn-down connection's page
     * would acknowledge whatever the ring offers now). A frame of any other opcode is refused with
     * [RefusalReason.NOT_A_PAGE]. Never throws.
     */
    suspend fun acknowledge(page: ByteArray): SendResult

    /**
     * Runs the ring's auth again on the open connection: the link writes `01 00 00` ahead of
     * waiting [send] writes and answers the ring's `81 00` challenge itself, as at bring-up. The
     * app may never write `01 00 00` ([send] refuses it): this is its one way to ask for a fresh
     * auth, for a ring that ignores a history sync open. [state] stays [LinkState.Authenticated]
     * throughout.
     *
     * Returns [SendResult.Sent] once the auth reply is written; [SendResult.Refused] with
     * [RefusalReason.NOT_AUTHENTICATED] when the link is not authenticated (nothing written);
     * [SendResult.Failed] with [SendFailure.TIMED_OUT] when the ring sends no challenge within
     * 5 s (the connection is kept), or with another failure when the connection goes. Never throws.
     */
    suspend fun reauthenticate(): SendResult

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
