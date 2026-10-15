package io.github.opencircuit.app

import androidx.compose.runtime.Composable
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.debug.SyncCutArming
import io.github.opencircuit.app.debug.SyncCutControls
import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.demo.DemoRingScanner
import io.github.opencircuit.app.sync.SyncFaultInjector
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.RingScanner
import kotlinx.coroutines.CoroutineScope

/**
 * Debug build: the app finds and talks to the demo ring (the emulator has no Bluetooth ring), and
 * Connection details carries the sync test action ("Cut the next sync after N pages"). The release
 * build has its own `VariantLinks` without either, so release code never contains them.
 */
internal object VariantLinks {
    /** The demo link for the demo ring (its periodic descriptors run in [scope]); null for any other ring. */
    fun demoLinkFor(ring: RememberedRing, scope: CoroutineScope): RingLink? =
        if (RingAddress.same(ring.address, DemoRingLink.RING.address)) DemoRingLink(scope) else null

    /** Builds a scanner that finds and selects the demo ring, as one ring nearby would. */
    val demoScanner: (() -> RingScanner)? = { DemoRingScanner(DemoRingLink.RING) }

    /** Added to the Ring screen's title so a demo ring is never mistaken for a real one. */
    const val titleSuffix: String = " · Demo ring"

    /** The sync test action's state, one for the process: every session's drain takes its armed cut. */
    private val syncCut = SyncCutArming()

    /** What each session's drain asks whether to cut its sync. */
    val syncFaults: SyncFaultInjector = syncCut

    /** Drawn at the foot of Connection details: the sync test action. */
    val detailsTools: (@Composable () -> Unit)? = { SyncCutControls(syncCut) }
}
