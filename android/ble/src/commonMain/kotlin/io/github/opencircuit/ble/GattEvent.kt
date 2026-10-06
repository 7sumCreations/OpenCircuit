package io.github.opencircuit.ble

/**
 * Identifies one GATT connection. The link makes a new one for every connection it opens; an
 * event carrying any other token comes from a connection that is already gone and is ignored.
 * Compared by identity.
 */
class SessionToken internal constructor(private val serial: Long) {
    /** For logs: the connection's serial number on its link. */
    override fun toString(): String = "SessionToken($serial)"
}

/**
 * A GATT callback, copied out of Android's callback into an immutable value. Every event carries
 * the [SessionToken] of the connection that produced it. Byte values are copied on the way in
 * and on every read, so no holder can change another's bytes.
 */
sealed interface GattEvent {
    /** The connection that produced this event. */
    val session: SessionToken

    /** `onConnectionStateChange`. */
    class ConnectionChanged(
        override val session: SessionToken,
        /** The GATT status ([GattPort.GATT_SUCCESS] when the change was clean). */
        val status: Int,
        /** True when the link is now connected, false when it is now disconnected. */
        val connected: Boolean,
    ) : GattEvent {
        /** For logs. */
        override fun toString(): String = "ConnectionChanged(status=$status, connected=$connected)"
    }

    /** `onServicesDiscovered`, with every characteristic the discovery found. */
    class ServicesDiscovered(
        override val session: SessionToken,
        /** The GATT status. */
        val status: Int,
        characteristics: Set<GattPort.Characteristic>,
    ) : GattEvent {
        /** Every characteristic discovered (a copy). */
        val characteristics: Set<GattPort.Characteristic> = characteristics.toSet()

        /** For logs. */
        override fun toString(): String = "ServicesDiscovered(status=$status, ${characteristics.size} characteristics)"
    }

    /** `onMtuChanged`. */
    class MtuChanged(
        override val session: SessionToken,
        /** The ATT MTU now in force. */
        val mtu: Int,
        /** The GATT status. */
        val status: Int,
    ) : GattEvent {
        /** For logs. */
        override fun toString(): String = "MtuChanged(mtu=$mtu, status=$status)"
    }

    /** `onDescriptorWrite`. */
    class DescriptorWritten(
        override val session: SessionToken,
        /** The characteristic the descriptor belongs to. */
        val characteristic: GattPort.Characteristic,
        /** The descriptor UUID. */
        val descriptor: String,
        /** The GATT status. */
        val status: Int,
    ) : GattEvent {
        /** For logs. */
        override fun toString(): String = "DescriptorWritten(${characteristic.uuid}/$descriptor, status=$status)"
    }

    /** `onCharacteristicRead`. */
    class CharacteristicRead(
        override val session: SessionToken,
        /** The characteristic read. */
        val characteristic: GattPort.Characteristic,
        value: ByteArray,
        /** The GATT status. */
        val status: Int,
    ) : GattEvent {
        private val bytes = value.copyOf()

        /** The value read (a fresh copy on every access). */
        val value: ByteArray get() = bytes.copyOf()

        /** For logs: never the value, which can hold the ring's MAC. */
        override fun toString(): String = "CharacteristicRead(${characteristic.uuid}, ${bytes.size} bytes, status=$status)"
    }

    /** `onCharacteristicWrite`. */
    class CharacteristicWritten(
        override val session: SessionToken,
        /** The characteristic written. */
        val characteristic: GattPort.Characteristic,
        /** The GATT status. */
        val status: Int,
    ) : GattEvent {
        /** For logs. */
        override fun toString(): String = "CharacteristicWritten(${characteristic.uuid}, status=$status)"
    }

    /** `onCharacteristicChanged`: a notification from the ring. */
    class Notification(
        override val session: SessionToken,
        /** The characteristic that notified. */
        val characteristic: GattPort.Characteristic,
        value: ByteArray,
    ) : GattEvent {
        private val bytes = value.copyOf()

        /** The notified bytes (a fresh copy on every access). */
        val value: ByteArray get() = bytes.copyOf()

        /** For logs: never the bytes, which are health data. */
        override fun toString(): String = "Notification(${characteristic.uuid}, ${bytes.size} bytes)"
    }
}
