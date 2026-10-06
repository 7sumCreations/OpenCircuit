package io.github.opencircuit.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.opencircuit.app.connect.AndroidAdapterStateSource
import io.github.opencircuit.app.connect.RingConnector
import io.github.opencircuit.app.connect.RingScannerFactory
import io.github.opencircuit.app.data.AppPrefs
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.data.RememberedRingStore
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.RingScanner
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
 * frames, a link's frames take one collector at a time, and the session holds its collection for
 * as long as it lives, so it must outlive any activity.
 */
class AppContainer(context: Context) {

    /** The phone's clock. */
    val clock: Clock = Clock { System.currentTimeMillis() }

    /** Milliseconds since boot, counting sleep; never jumps when the user changes the clock. */
    val monotonicMillis: () -> Long = { SystemClock.elapsedRealtime() }

    /** Links to a real ring over the phone's Bluetooth (`:ble`'s Android factory). */
    val ringLinkFactory: RingLinkFactory = RingLinkFactory { ring -> RingLink(context.applicationContext, ring) }

    /** Process-lifetime scope for the session's frame and teardown collections. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One line to the system log. Callers never pass frame bytes or an address. */
    val log: (String) -> Unit = { Log.i(LOG_TAG, it) }

    /** The app's private preferences file: the remembered ring and two flags. */
    private val keyValues = SharedPreferencesKeyValues(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

    /** Onboarding done, Nearby devices asked. */
    val appPrefs: AppPrefs = PrefsAppPrefs(keyValues)

    /** The ring the app reconnects to. */
    val rememberedRings: RememberedRingStore = PrefsRememberedRingStore(keyValues)

    /**
     * The session with the ring, or null when there is no ring to talk to yet. Debug builds use
     * the demo ring; choosing and remembering a real ring is not built yet.
     */
    val ringSession: RingSessionController? by lazy {
        VariantLinks.demoLink?.invoke(appScope)?.let { RingSessionController(it, appScope, monotonicMillis, log) }
    }

    /** The Bluetooth adapter's state; the activity starts and stops it as it shows and hides. */
    val adapterStates: AndroidAdapterStateSource = AndroidAdapterStateSource(context, log)

    /**
     * Builds the scanner for a Scan & connect: `:ble`'s Android scanner (it needs Nearby devices;
     * without it, or with Bluetooth off, the scan fails at once), or in debug builds one that
     * finds the demo ring.
     */
    val ringScannerFactory: RingScannerFactory = RingScannerFactory {
        val demoRing = ringSession?.link?.ring
        if (demoRing != null) VariantLinks.demoScanner?.invoke(demoRing) ?: RingScanner(context.applicationContext)
        else RingScanner(context.applicationContext)
    }

    /**
     * Connects the ring a scan selected or the user picked. For now the only link the app holds is
     * the session's (the demo ring in debug builds), so a selection connects that session; a link
     * built for the chosen ring, remembered and reconnected on launch, replaces this.
     */
    val ringConnector: RingConnector = RingConnector { _ ->
        ringSession?.connect() ?: log("A ring was chosen, but this build has no link to connect it with yet")
    }

    /** The Ring screen's title; debug builds say when the ring is the demo. */
    val ringTitle: String = "Ring" + VariantLinks.titleSuffix

    private companion object {
        const val LOG_TAG = "OpenCircuit"
        const val PREFS_FILE = "opencircuit"
    }
}
