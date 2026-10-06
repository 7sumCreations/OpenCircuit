package io.github.opencircuit.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * The two system broadcasts a link needs, fed into its event loop: the adapter's state
 * (`BluetoothAdapter.ACTION_STATE_CHANGED`) and the bond with the link's ring
 * (`BluetoothDevice.ACTION_BOND_STATE_CHANGED`, other devices' bonds ignored). A mechanical
 * mapping with no link logic; compiled, not exercised, on the JVM.
 *
 * Both receivers are registered at run time with `RECEIVER_NOT_EXPORTED` (no other app can send
 * them anything) and run on the main thread, where they only hand the value on. The reason
 * Android gives for a lost bond is not read: it is not public in the SDK, and the link never
 * acts on it.
 */
internal class AndroidLinkBroadcasts(
    private val context: Context,
    private val ring: RememberedRing,
    private val core: LinkCore,
) {
    private val handler = Handler(Looper.getMainLooper())

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            adapterStateOf(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
                ?.let(core::onAdapterState)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (!device.address.equals(ring.address, ignoreCase = true)) return
            val code = if (intent.hasExtra(BluetoothDevice.EXTRA_BOND_STATE)) intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0) else null
            bondStateFromCodeOrNull(code)?.let(core::onBondState)
        }
    }

    /**
     * Registers both receivers, then tells the link the adapter's state now (the link takes it to
     * be on until told), and once more from the receivers' own thread so a change that raced the
     * first read is never left behind it.
     */
    fun start() {
        register(adapterReceiver, BluetoothAdapter.ACTION_STATE_CHANGED)
        register(bondReceiver, BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        core.onAdapterState(currentAdapterState())
        handler.post { core.onAdapterState(currentAdapterState()) }
    }

    /** Unregisters both receivers. Safe to call more than once. */
    fun stop() {
        unregister(adapterReceiver)
        unregister(bondReceiver)
    }

    /** The adapter's state now; no Bluetooth at all reads as off. */
    private fun currentAdapterState(): AdapterState {
        val state = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.state
        } catch (_: SecurityException) {
            null
        }
        return state?.let(::adapterStateOf) ?: AdapterState.OFF
    }

    private fun register(receiver: BroadcastReceiver, action: String) {
        try {
            context.registerReceiver(receiver, IntentFilter(action), null, handler, Context.RECEIVER_NOT_EXPORTED)
        } catch (_: SecurityException) {
            // Without the permission no broadcast arrives; the link still reads the bond itself.
        }
    }

    private fun unregister(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Not registered (registration was refused, or already unregistered).
        }
    }
}

/** `BluetoothAdapter.STATE_*` → [AdapterState]; any other value is no news. */
private fun adapterStateOf(state: Int): AdapterState? = when (state) {
    BluetoothAdapter.STATE_ON -> AdapterState.ON
    BluetoothAdapter.STATE_OFF -> AdapterState.OFF
    BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
    BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
    else -> null
}
