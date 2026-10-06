package io.github.opencircuit.app

import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.app.demo.DemoRingScanner
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.RingScanner
import kotlinx.coroutines.CoroutineScope

/**
 * Debug build: the app talks to the demo ring (the emulator has no Bluetooth ring). The release
 * build has its own `VariantLinks` without the demo, so release code never contains it.
 */
internal object VariantLinks {
    /** Builds the demo link; its periodic descriptors run in the given scope. */
    val demoLink: ((CoroutineScope) -> RingLink)? = { scope -> DemoRingLink(scope) }

    /** Builds a scanner that finds and selects the given ring (the demo's), as one ring nearby would. */
    val demoScanner: ((RememberedRing) -> RingScanner)? = { ring -> DemoRingScanner(ring) }

    /** Added to the Ring screen's title so a demo ring is never mistaken for a real one. */
    const val titleSuffix: String = " · Demo ring"
}
