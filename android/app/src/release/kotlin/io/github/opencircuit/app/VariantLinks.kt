package io.github.opencircuit.app

import androidx.compose.runtime.Composable
import io.github.opencircuit.app.sync.SyncFaultInjector
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.RingScanner
import kotlinx.coroutines.CoroutineScope

/** Release build: no demo ring and no sync test action. The debug build's `VariantLinks` supplies both. */
internal object VariantLinks {
    /** No demo link in release: every ring gets the phone's Bluetooth link. */
    @Suppress("UNUSED_PARAMETER")
    fun demoLinkFor(ring: RememberedRing, scope: CoroutineScope): RingLink? = null

    /** The system clock, directly: release has no settable clock. */
    val wallClock: Clock = Clock { System.currentTimeMillis() }

    /** No demo scanner in release: scans use the phone's Bluetooth. */
    val demoScanner: (() -> RingScanner)? = null

    /** Nothing added to the Ring screen's title. */
    const val titleSuffix: String = ""

    /** A release sync is never cut. */
    val syncFaults: SyncFaultInjector = SyncFaultInjector.NONE

    /** Nothing at the foot of Connection details. */
    val detailsTools: (@Composable () -> Unit)? = null
}
