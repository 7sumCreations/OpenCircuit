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
 *
 * When the app is done with the link for good (the ring is forgotten or replaced), it ends it with
 * `(link as? AutoCloseable)?.close()`: the connection is closed, both receivers are unregistered
 * and the link does nothing more. A link that is dropped without `close()` keeps its receivers
 * registered for the life of the process.
 */
fun RingLink(context: Context, ring: RememberedRing): RingLink =
    androidRingLink(context.applicationContext, ring, CoroutineScope(SupervisorJob() + Dispatchers.Default))

/**
 * The Android link in [scope] (which must hold a [Job]): the link core over the Android GATT port,
 * with the adapter-state and bond receivers registered before the first `connect()` and
 * unregistered when [scope] ends, which `close()` on the returned link does.
 */
internal fun androidRingLink(context: Context, ring: RememberedRing, scope: CoroutineScope): RingLink {
    val owner = checkNotNull(scope.coroutineContext[Job]) { "the link's scope needs a Job to end it" }
    val core = LinkCore(ring, AndroidGattPort(context), scope)
    val broadcasts = AndroidLinkBroadcasts(context, ring, core)
    broadcasts.start()
    owner.invokeOnCompletion { broadcasts.stop() }
    return OwnedRingLink(core, owner)
}
