package io.github.opencircuit.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Builds the [RingLink] for [ring] over the phone's Bluetooth stack. Build one per remembered
 * ring and keep it: it is reused across every connection to that ring. The app must hold
 * `BLUETOOTH_CONNECT` before calling [RingLink.connect].
 */
fun RingLink(context: Context, ring: RememberedRing): RingLink =
    RingLink(ring, AndroidGattPort(context.applicationContext), CoroutineScope(SupervisorJob() + Dispatchers.Default))
