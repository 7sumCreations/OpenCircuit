package io.github.opencircuit.ble

import io.github.opencircuit.ringkit.Transport

/**
 * The commands the link gives the phone's Bluetooth stack: one GATT client for one ring.
 *
 * The link calls these from its event loop only, one GATT operation at a time, and waits for the
 * matching [GattEvent] before the next. Every operation returns `true` when Android accepted it
 * and `false` when it refused (including a revoked Bluetooth permission); a refusal fails the
 * operation. Android implements it over `BluetoothGatt`; the tests use a scripted fake ring.
 *
 * Not part of the interface the app builds against: it is public only so the test doubles of
 * other modules can implement it.
 */
interface GattPort {
    /**
     * Opens a GATT connection to [ring] over LE. Every callback of this connection is passed to
     * [events] carrying [session], so callbacks of an older connection can be told apart.
     */
    fun connect(session: SessionToken, ring: RememberedRing, autoConnect: Boolean, events: EventSink): Boolean

    /** Starts service discovery; answered by [GattEvent.ServicesDiscovered]. */
    fun discoverServices(): Boolean

    /** Starts the ATT MTU exchange asking for [mtu]; answered by [GattEvent.MtuChanged]. */
    fun requestMtu(mtu: Int): Boolean

    /** Turns local notification delivery for [characteristic] on or off. Local only: no event follows. */
    fun setNotifications(characteristic: Characteristic, enabled: Boolean): Boolean

    /** Writes [value] to the [descriptor] of [characteristic]; answered by [GattEvent.DescriptorWritten]. */
    fun writeDescriptor(characteristic: Characteristic, descriptor: String, value: ByteArray): Boolean

    /** Reads [characteristic]; answered by [GattEvent.CharacteristicRead]. */
    fun read(characteristic: Characteristic): Boolean

    /** Writes [value] to [characteristic] with response; answered by [GattEvent.CharacteristicWritten]. */
    fun write(characteristic: Characteristic, value: ByteArray): Boolean

    /** The phone's bond state with the ring. */
    fun bondState(): BondState

    /** Asks Android to bond with the ring. */
    fun createBond(): Boolean

    /** Closes the connection. No event follows a close. */
    fun close()

    /** A characteristic, by its service UUID and its own UUID, both lower-case 128-bit strings. */
    data class Characteristic(
        /** The service UUID. */
        val service: String,
        /** The characteristic UUID. */
        val uuid: String,
    )

    /** Where a connection's callbacks go. Called from any thread; must only hand the event on. */
    fun interface EventSink {
        /** Hands [event] to the link. */
        fun deliver(event: GattEvent)
    }

    /** The phone's bond state with the ring (`BluetoothDevice.getBondState()`). */
    enum class BondState {
        /** Not bonded. */
        NONE,

        /** A bond is being made. */
        BONDING,

        /** Bonded. */
        BONDED,
    }

    /** The ring's GATT layout (`docs/PROTOCOL.md` §1) and the standard descriptors the link uses. */
    companion object {
        /** The status Android reports for a successful operation (`BluetoothGatt.GATT_SUCCESS`). */
        const val GATT_SUCCESS: Int = 0

        /** The ATT MTU the link asks for. */
        const val REQUESTED_MTU: Int = 517

        /** The Client Characteristic Configuration descriptor. */
        const val CCCD: String = "00002902-0000-1000-8000-00805f9b34fb"

        /** The ring's notify characteristic (every response and data frame). */
        val NOTIFY: Characteristic = Characteristic(Transport.DATA_SERVICE_UUID, Transport.NOTIFY_CHAR_UUID)

        /** The ring's command characteristic (every command). */
        val WRITE: Characteristic = Characteristic(Transport.DATA_SERVICE_UUID, Transport.WRITE_CHAR_UUID)

        private const val DEVICE_INFORMATION = "0000180a-0000-1000-8000-00805f9b34fb"

        /** Device Information: System ID (`0x2a23`), the ring's MAC. */
        val SYSTEM_ID: Characteristic = Characteristic(DEVICE_INFORMATION, "00002a23-0000-1000-8000-00805f9b34fb")

        /** Device Information: Firmware Revision String (`0x2a26`). */
        val FIRMWARE_REVISION: Characteristic = Characteristic(DEVICE_INFORMATION, "00002a26-0000-1000-8000-00805f9b34fb")

        /** Device Information: Manufacturer Name String (`0x2a29`). */
        val MANUFACTURER_NAME: Characteristic = Characteristic(DEVICE_INFORMATION, "00002a29-0000-1000-8000-00805f9b34fb")

        /** Device Information: Hardware Revision String (`0x2a27`). */
        val HARDWARE_REVISION: Characteristic = Characteristic(DEVICE_INFORMATION, "00002a27-0000-1000-8000-00805f9b34fb")
    }
}
