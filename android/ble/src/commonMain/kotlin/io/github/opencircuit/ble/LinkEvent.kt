package io.github.opencircuit.ble

import kotlinx.coroutines.CompletableDeferred

/**
 * Everything that can change the link, as one event type: GATT callbacks, calls to the
 * [RingLink] API, the link's own timers and the Bluetooth adapter's state. The link's single
 * event loop takes them one at a time, in arrival order, so its state needs no locks.
 */
internal sealed interface LinkEvent {
    class Gatt(val event: GattEvent) : LinkEvent

    data object Connect : LinkEvent

    data object Disconnect : LinkEvent

    /** A feature write; [command] is already the link's own copy. */
    class Send(val command: ByteArray, val reply: CompletableDeferred<SendResult>) : LinkEvent

    /** A timer of the link ran out. [ticket] tells it from a timer that was cancelled or replaced since. */
    class TimerFired(val timer: LinkTimer, val ticket: Any) : LinkEvent

    /** The phone's Bluetooth adapter changed state. */
    class AdapterChanged(val state: AdapterState) : LinkEvent
}

/** The link's timers: at most one of each kind runs at a time. */
internal enum class LinkTimer {
    /** The in-flight operation's timeout. */
    OPERATION,

    /** Marks a direct connect as late enough that a 133 means "out of reach". */
    LATE_CONNECT,

    /** The one-time check, after a connection has been up a while, that it delivered a frame. */
    STABILITY,

    /** The wait before the next reconnect attempt. */
    RECONNECT,
}
