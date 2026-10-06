package io.github.opencircuit.ble

import kotlinx.coroutines.CompletableDeferred

/**
 * Everything that can change the link, as one event type: GATT callbacks and calls to the
 * [RingLink] API. The link's single event loop takes them one at a time, in arrival order, so
 * its state needs no locks.
 */
internal sealed interface LinkEvent {
    class Gatt(val event: GattEvent) : LinkEvent

    data object Connect : LinkEvent

    data object Disconnect : LinkEvent

    /** A feature write; [command] is already the link's own copy. */
    class Send(val command: ByteArray, val reply: CompletableDeferred<SendResult>) : LinkEvent
}
