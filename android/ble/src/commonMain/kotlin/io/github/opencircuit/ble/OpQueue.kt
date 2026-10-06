package io.github.opencircuit.ble

import kotlinx.coroutines.CompletableDeferred
import java.time.Duration

/**
 * One GATT operation the link waits on: it is done when the event that answers it arrives, and
 * failed when none has arrived within its [timeout] (PORTING.md D-185).
 */
internal sealed interface GattOp {
    /** True when [event] is this operation's answer (the session is checked by the link first). */
    fun isAnsweredBy(event: GattEvent): Boolean

    /** How long the link waits for the answer; null for no limit. */
    val timeout: Duration?

    /** Opens the connection: direct, or a standing ([autoConnect]) connection that waits for the ring. */
    data class Connect(val autoConnect: Boolean) : GattOp {
        override fun isAnsweredBy(event: GattEvent) = event is GattEvent.ConnectionChanged
        override val timeout: Duration? get() = if (autoConnect) LinkTimeouts.STANDING_CONNECT else LinkTimeouts.DIRECT_CONNECT
    }

    data object DiscoverServices : GattOp {
        override fun isAnsweredBy(event: GattEvent) = event is GattEvent.ServicesDiscovered
        override val timeout: Duration get() = LinkTimeouts.DISCOVER
    }

    data object RequestMtu : GattOp {
        override fun isAnsweredBy(event: GattEvent) = event is GattEvent.MtuChanged
        override val timeout: Duration get() = LinkTimeouts.MTU
    }

    /** Local notification switch plus the CCCD write `01 00`; done when the descriptor write is answered. */
    data object EnableNotifications : GattOp {
        override fun isAnsweredBy(event: GattEvent) =
            event is GattEvent.DescriptorWritten && event.characteristic == GattPort.NOTIFY && event.descriptor == GattPort.CCCD
        override val timeout: Duration get() = LinkTimeouts.DESCRIPTOR_WRITE
    }

    data class Read(val characteristic: GattPort.Characteristic) : GattOp {
        override fun isAnsweredBy(event: GattEvent) =
            event is GattEvent.CharacteristicRead && event.characteristic == characteristic
        override val timeout: Duration get() = LinkTimeouts.READ
    }

    /** A command write with response. [reply] is the caller waiting on a feature write, if any. */
    class Write(
        val value: ByteArray,
        val purpose: Purpose,
        val reply: CompletableDeferred<SendResult>? = null,
    ) : GattOp {
        override fun isAnsweredBy(event: GattEvent) =
            event is GattEvent.CharacteristicWritten && event.characteristic == GattPort.WRITE

        override val timeout: Duration get() = LinkTimeouts.WRITE

        enum class Purpose { AUTH_START, AUTH_REPLY, FEATURE }
    }
}

/**
 * The GATT operations waiting to run, at most one of them in flight: Android allows one
 * outstanding operation per connection, so the next starts only after the previous was answered.
 * Owned by the link's event loop; not thread-safe.
 */
internal class OpQueue {
    private val waiting = ArrayDeque<GattOp>()

    /** The operation submitted and not yet answered, if any. */
    var inFlight: GattOp? = null
        private set

    fun add(op: GattOp) {
        waiting.addLast(op)
    }

    /** Takes the next operation and marks it in flight; null while one is in flight or none waits. */
    fun startNext(): GattOp? {
        if (inFlight != null) return null
        val next = waiting.removeFirstOrNull() ?: return null
        inFlight = next
        return next
    }

    /** The in-flight operation was answered. */
    fun finish() {
        inFlight = null
    }

    /** Empties the queue; returns every operation it held, the in-flight one first. */
    fun clear(): List<GattOp> {
        val all = listOfNotNull(inFlight) + waiting
        inFlight = null
        waiting.clear()
        return all
    }
}
