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

        enum class Purpose {
            /** `01 00 00`, which starts the auth exchange (a bring-up step). */
            AUTH_START,

            /** The answer to the ring's `81 00` challenge (link lane). */
            AUTH_REPLY,

            /** `d0 00 00` after the first auth reply, asking for the data frame that authenticates the link (a bring-up step). */
            STREAM_REQUEST,

            /** The acknowledgement of a page or heartbeat (link lane). */
            ACK,

            /** A command written for a caller of `send`. */
            FEATURE,
        }

        /** True for the link's own urgent writes, which go on the link lane. */
        val onLinkLane: Boolean get() = purpose == Purpose.ACK || purpose == Purpose.AUTH_REPLY

        /** The caller of `send` this write was for was cancelled (the link's own writes have none). */
        val callerGone: Boolean get() = reply?.isCancelled == true
    }
}

/**
 * The GATT operations waiting to run, at most one of them in flight: Android allows one
 * outstanding operation per connection, so the next starts only after the previous was answered.
 *
 * Two lanes, each in arrival order. The link lane holds what the ring is waiting for (page and
 * heartbeat acknowledgements, the auth reply); the main lane holds the bring-up steps and the
 * feature writes. When the operation in flight is answered, the next one comes from the link lane
 * if it holds any; nothing ever cuts into the operation in flight (PORTING.md D-190).
 * Owned by the link's event loop; not thread-safe.
 */
internal class OpQueue {
    private val link = ArrayDeque<GattOp>()
    private val main = ArrayDeque<GattOp>()

    /** The operation submitted and not yet answered, if any. */
    var inFlight: GattOp? = null
        private set

    fun add(op: GattOp) {
        if (op is GattOp.Write && op.onLinkLane) link.addLast(op) else main.addLast(op)
    }

    /**
     * Takes the next operation and marks it in flight; null while one is in flight or none waits.
     * A feature write whose caller is gone is dropped on the way, never started.
     */
    fun startNext(): GattOp? {
        if (inFlight != null) return null
        while (true) {
            val next = link.removeFirstOrNull() ?: main.removeFirstOrNull() ?: return null
            if (next is GattOp.Write && next.callerGone) continue
            inFlight = next
            return next
        }
    }

    /** The in-flight operation was answered. */
    fun finish() {
        inFlight = null
    }

    /** Empties the queue; returns every operation it held, the in-flight one first. */
    fun clear(): List<GattOp> {
        val all = listOfNotNull(inFlight) + link + main
        inFlight = null
        link.clear()
        main.clear()
        return all
    }
}
