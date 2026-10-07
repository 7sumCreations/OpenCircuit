package io.github.opencircuit.app.connect

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import io.github.opencircuit.ble.RememberedRing
import java.util.Locale

/** Android's companion-device sheet: the activity launches [intentSender] for a result. */
class IntentSenderSheet(val intentSender: IntentSender) : PairingSheet

/**
 * [CompanionPort] over Android's `CompanionDeviceManager`. The request is one LE filter on the
 * ring's address ([CompanionFilterSpec]; no name pattern); no device profile, so
 * no permission or role comes with it. CDM's own scan runs in the system's companion-device app;
 * its answers arrive on the main thread.
 */
class AndroidCompanionPort(context: Context) : CompanionPort {

    private val app = context.applicationContext
    private val manager: CompanionDeviceManager? = app.getSystemService(CompanionDeviceManager::class.java)

    override val available: Boolean
        get() = manager != null && app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    override fun associations(): List<CompanionAssociation> =
        requireManager().myAssociations.map { CompanionAssociation(it.id, it.deviceMacAddress?.toString()) }

    override fun disassociate(id: Int) {
        requireManager().disassociate(id)
    }

    /** False when it cannot be read (no adapter, a malformed address, Nearby devices revoked). */
    override fun isBonded(address: String): Boolean {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return try {
            adapter.getRemoteDevice(address.uppercase(Locale.ROOT)).bondState == BluetoothDevice.BOND_BONDED
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun associate(ring: RememberedRing, callback: AssociationCallback) {
        val spec = CompanionFilter.specFor(ring)
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(spec.address).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        requireManager().associate(
            request,
            app.mainExecutor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    callback.onPending(IntentSenderSheet(intentSender))
                }

                override fun onFailure(error: CharSequence?) {
                    callback.onFailure(error)
                }
            },
        )
    }

    private fun requireManager(): CompanionDeviceManager =
        manager ?: throw IllegalStateException("no companion-device manager on this phone")
}
