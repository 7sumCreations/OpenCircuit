package io.github.opencircuit.ble

import kotlinx.coroutines.CompletableDeferred

/** One GATT operation the link waits on: it is done when the event that answers it arrives. */
internal sealed interface GattOp {
    /** True when [event] is this operation's answer (the session is checked by the link first). */
    fun isAnsweredBy(event: GattEvent): Boolean

    data object Connect : GattOp {
        override fun isAnsweredBy(event: GattEvent) = event is GattEvent.ConnectionChanged
    }

    data object DiscoverServices : GattOp {
        override fun isAnsweredBy(event: GattEvent) = event is GattEvent.ServicesDiscovered
    }

    data object RequestMtu : GattOp {
        override fun isAnsweredBy(event: GattEvent) = event is GattEvent.MtuChanged
    }

    /** Local notification switch plus the CCCD write `01 00`; done when the descriptor write is answered. */
    data object EnableNotifications : GattOp {
        override fun isAnsweredBy(event: GattEvent) =
            event is GattEvent.DescriptorWritten && event.characteristic == GattPort.NOTIFY && event.descriptor == GattPort.CCCD
    }

    data class Read(val characteristic: GattPort.Characteristic) : GattOp {
        override fun isAnsweredBy(event: GattEvent) =
            event is GattEvent.CharacteristicRead && event.characteristic == characteristic
    }

    /** A command write with response. [reply] is the caller waiting on a feature write, if any. */
    class Write(
        val value: ByteArray,
        val purpose: Purpose,
        val reply: CompletableDeferred<SendResult>? = null,
    ) : GattOp {
        override fun isAnsweredBy(event: GattEvent) =
            event is GattEvent.CharacteristicWritten && event.characteristic == GattPort.WRITE

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
