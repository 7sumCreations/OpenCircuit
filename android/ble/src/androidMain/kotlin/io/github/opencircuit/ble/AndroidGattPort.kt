package io.github.opencircuit.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import java.util.UUID

/**
 * [GattPort] over Android's `BluetoothGatt`. A mechanical mapping with no link logic: each call
 * is one framework call, and each callback only copies its arguments into a [GattEvent] and hands
 * it on. The link's tests run against the scripted fake ring instead; this class is compiled,
 * not exercised, on the JVM.
 *
 * Uses the API 33 forms (`writeCharacteristic(c, value, type)`, `writeDescriptor(d, value)`, the
 * value-carrying read and notification callbacks). Every write is a write with response.
 * The app holds `BLUETOOTH_CONNECT`; when it is revoked a call throws [SecurityException], which
 * is caught and reported as a refused operation. The bond state read is the exception: it has no
 * "refused" answer, so its [SecurityException] reaches the link, which counts it as a failed read.
 */
@SuppressLint("MissingPermission") // the caller holds BLUETOOTH_CONNECT; a revocation is caught below or by the link
internal class AndroidGattPort(private val context: Context) : GattPort {

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null

    override fun connect(session: SessionToken, ring: RememberedRing, autoConnect: Boolean, events: GattPort.EventSink): Boolean =
        refusedOnError {
            val remote = adapter?.getRemoteLeDevice(ring.address, ring.addressType.toAndroid()) ?: return@refusedOnError false
            device = remote
            gatt = remote.connectGatt(context, autoConnect, Callback(session, events), BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }

    override fun discoverServices(): Boolean = refusedOnError { gatt?.discoverServices() == true }

    override fun requestMtu(mtu: Int): Boolean = refusedOnError { gatt?.requestMtu(mtu) == true }

    override fun setNotifications(characteristic: GattPort.Characteristic, enabled: Boolean): Boolean = refusedOnError {
        val target = find(characteristic) ?: return@refusedOnError false
        gatt?.setCharacteristicNotification(target, enabled) == true
    }

    override fun writeDescriptor(characteristic: GattPort.Characteristic, descriptor: String, value: ByteArray): Boolean =
        refusedOnError {
            val target = find(characteristic)?.getDescriptor(UUID.fromString(descriptor)) ?: return@refusedOnError false
            gatt?.writeDescriptor(target, value.copyOf()) == BluetoothStatusCodes.SUCCESS
        }

    override fun read(characteristic: GattPort.Characteristic): Boolean = refusedOnError {
        val target = find(characteristic) ?: return@refusedOnError false
        gatt?.readCharacteristic(target) == true
    }

    override fun write(characteristic: GattPort.Characteristic, value: ByteArray): Boolean = refusedOnError {
        val target = find(characteristic) ?: return@refusedOnError false
        gatt?.writeCharacteristic(target, value.copyOf(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            BluetoothStatusCodes.SUCCESS
    }

    /**
     * Throws when the state cannot be read (no device, a revoked permission, a code Android does
     * not define): the link counts that as a failed operation and reconnects (PORTING.md D-187).
     * Reading it as "not bonded" would ask Android to bond and show a pairing failure instead.
     */
    override fun bondState(): GattPort.BondState = bondStateFromCode(device?.bondState)

    override fun createBond(): Boolean = refusedOnError { device?.createBond() == true }

    override fun close() {
        try {
            gatt?.close()
        } catch (_: SecurityException) {
            // Nothing more to release: the connection object is dropped below either way.
        }
        gatt = null
        device = null
    }

    private fun find(characteristic: GattPort.Characteristic): BluetoothGattCharacteristic? =
        gatt?.getService(UUID.fromString(characteristic.service))?.getCharacteristic(UUID.fromString(characteristic.uuid))

    /** Runs one framework call; a revoked permission or a malformed address / UUID refuses the operation. */
    private inline fun refusedOnError(call: () -> Boolean): Boolean = try {
        call()
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Copies each callback into a [GattEvent] for [session] and hands it to [events]; nothing else. */
    private class Callback(
        private val session: SessionToken,
        private val events: GattPort.EventSink,
    ) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            events.deliver(GattEvent.ConnectionChanged(session, status, connected = newState == BluetoothProfile.STATE_CONNECTED))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val found = gatt.services.flatMap { service -> service.characteristics.map { it.id() } }.toSet()
            events.deliver(GattEvent.ServicesDiscovered(session, status, found))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            events.deliver(GattEvent.MtuChanged(session, mtu, status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            events.deliver(GattEvent.DescriptorWritten(session, descriptor.characteristic.id(), descriptor.uuid.toString(), status))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            events.deliver(GattEvent.CharacteristicRead(session, characteristic.id(), value, status))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            events.deliver(GattEvent.CharacteristicWritten(session, characteristic.id(), status))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            events.deliver(GattEvent.Notification(session, characteristic.id(), value))
        }
    }
}

private fun BluetoothGattCharacteristic.id(): GattPort.Characteristic =
    GattPort.Characteristic(service?.uuid?.toString().orEmpty(), uuid.toString())

private fun AddressType.toAndroid(): Int = when (this) {
    AddressType.PUBLIC -> BluetoothDevice.ADDRESS_TYPE_PUBLIC
    AddressType.RANDOM -> BluetoothDevice.ADDRESS_TYPE_RANDOM
}
