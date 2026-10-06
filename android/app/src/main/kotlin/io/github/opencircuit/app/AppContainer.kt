package io.github.opencircuit.app

import android.content.Context
import android.util.Log
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Wall-clock time in milliseconds; a seam so tests can supply virtual time. */
fun interface Clock {
    fun nowMillis(): Long
}

/** Builds the link to a remembered ring; a seam so tests and debug builds can supply another. */
fun interface RingLinkFactory {
    fun create(ring: RememberedRing): RingLink
}

/**
 * The app's object graph, built once in [OpenCircuitApp.onCreate] and kept for the process
 * lifetime (no dependency-injection framework).
 *
 * The session controller lives here, not in a screen: it is the one collector of its link's
 * frames, and a link's frames can be collected only once, so it must outlive any activity.
 */
class AppContainer(context: Context) {

    /** The phone's clock. */
    val clock: Clock = Clock { System.currentTimeMillis() }

    /** Links to a real ring over the phone's Bluetooth (`:ble`'s Android factory). */
    val ringLinkFactory: RingLinkFactory = RingLinkFactory { ring -> RingLink(context.applicationContext, ring) }

    /** Process-lifetime scope for the session's frame and teardown collections. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One line to the system log. Callers never pass frame bytes or an address. */
    val log: (String) -> Unit = { Log.i(LOG_TAG, it) }

    /**
     * The session with the ring, or null when there is no ring to talk to yet. Debug builds use
     * the demo ring; choosing and remembering a real ring is not built yet.
     */
    val ringSession: RingSessionController? by lazy {
        VariantLinks.demoLink?.invoke()?.let { RingSessionController(it, appScope, log) }
    }

    /** The Ring screen's title; debug builds say when the ring is the demo. */
    val ringTitle: String = "Ring" + VariantLinks.titleSuffix

    private companion object {
        const val LOG_TAG = "OpenCircuit"
    }
}
