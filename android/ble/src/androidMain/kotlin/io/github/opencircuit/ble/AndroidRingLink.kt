package io.github.opencircuit.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Builds the [RingLink] for [ring] over the phone's Bluetooth stack. Build one per remembered
 * ring and keep it: it is reused across every connection to that ring, and it listens for
 * Bluetooth turning on and off and for bond changes for as long as it lives. The app must hold
 * `BLUETOOTH_CONNECT` before calling [RingLink.connect].
 *
 * The device is rebuilt from the ring's address and address type on every connection
 * (`BluetoothAdapter.getRemoteLeDevice`), so reconnecting never needs a scan. The link also offers
 * [LinkDiagnostics]: `(link as? LinkDiagnostics)?.diagnostics`.
 */
fun RingLink(context: Context, ring: RememberedRing): RingLink =
    androidRingLink(context.applicationContext, ring, CoroutineScope(SupervisorJob() + Dispatchers.Default))

/**
 * The Android link in [scope]: the link core over the Android GATT port, with the adapter-state
 * and bond receivers registered before the first `connect()` and unregistered when [scope] ends.
 */
internal fun androidRingLink(context: Context, ring: RememberedRing, scope: CoroutineScope): RingLink {
    val core = LinkCore(ring, AndroidGattPort(context), scope)
    val broadcasts = AndroidLinkBroadcasts(context, ring, core)
    broadcasts.start()
    scope.coroutineContext[Job]?.invokeOnCompletion { broadcasts.stop() }
    return core
}
