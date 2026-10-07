package io.github.opencircuit.app

import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.RingScanner
import kotlinx.coroutines.CoroutineScope

/** Release build: no demo ring. The debug build's `VariantLinks` supplies one. */
internal object VariantLinks {
    /** No demo link in release: every ring gets the phone's Bluetooth link. */
    @Suppress("UNUSED_PARAMETER")
    fun demoLinkFor(ring: RememberedRing, scope: CoroutineScope): RingLink? = null

    /** No demo scanner in release: scans use the phone's Bluetooth. */
    val demoScanner: (() -> RingScanner)? = null

    /** Nothing added to the Ring screen's title. */
    const val titleSuffix: String = ""
}
