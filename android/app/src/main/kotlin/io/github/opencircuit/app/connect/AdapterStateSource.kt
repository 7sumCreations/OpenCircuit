package io.github.opencircuit.app.connect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.opencircuit.ble.AdapterState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's Bluetooth adapter state; null until it has been read. */
interface AdapterStateSource {
    val state: StateFlow<AdapterState?>
}

/**
 * [AdapterStateSource] from `BluetoothAdapter.getState()` and the `ACTION_STATE_CHANGED`
 * broadcast. Neither needs a permission, so the state is known before Nearby devices is granted.
 *
 * Listens only between [start] and [stop] (the activity calls them as it becomes visible and
 * hidden). [start] reads the state first, because the broadcast is not replayed. The receiver is
 * registered exported: a not-exported receiver hears only uid system, and the Bluetooth stack
 * (which sends some of its broadcasts as its own uid) is not uid system. Exporting opens nothing,
 * because `ACTION_STATE_CHANGED` is a protected broadcast only the system can send. Main thread
 * only.
 */
class AndroidAdapterStateSource(context: Context, private val log: (String) -> Unit) : AdapterStateSource {
    private val appContext = context.applicationContext
    private val stateFlow = MutableStateFlow<AdapterState?>(null)
    private var listening = false

    override val state: StateFlow<AdapterState?> = stateFlow.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val next = adapterStateOf(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, NO_STATE)) ?: return
            if (stateFlow.value != next) log("Bluetooth adapter is $next")
            stateFlow.value = next
        }
    }

    fun start() {
        if (listening) return
        appContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        listening = true
        stateFlow.value = readNow()
    }

    fun stop() {
        if (!listening) return
        appContext.unregisterReceiver(receiver)
        listening = false
    }

    /** The adapter's state now; a phone without Bluetooth reads as off. */
    private fun readNow(): AdapterState? {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return AdapterState.OFF
        return adapterStateOf(adapter.state)
    }

    private companion object {
        const val NO_STATE = -1
    }
}
