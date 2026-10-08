package io.github.opencircuit.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.opencircuit.app.connect.AndroidAdapterStateSource
import io.github.opencircuit.app.connect.AndroidCompanionPort
import io.github.opencircuit.app.connect.CompanionPairing
import io.github.opencircuit.app.connect.RingScannerFactory
import io.github.opencircuit.app.data.AppPrefs
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.data.RememberedRingStore
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.details.DetailsSources
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.session.RingSessions
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.app.sync.SyncTriggerSources
import io.github.opencircuit.app.sync.learningNights
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.RingScanner
import io.github.opencircuit.ble.ScanDiagnostics
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.open
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.time.Instant
import java.time.ZoneId

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
 * The ring sessions live here, not in a screen: a session controller is the one collector of its
 * link's frames, a link's frames take one collector at a time, and the session holds its
 * collection for as long as it lives, so it must outlive any activity.
 */
class AppContainer(context: Context) {

    /** The phone's clock. */
    val clock: Clock = Clock { System.currentTimeMillis() }

    /** Milliseconds since boot, counting sleep; never jumps when the user changes the clock. */
    val monotonicMillis: () -> Long = { SystemClock.elapsedRealtime() }

    /** Process-lifetime scope for the sessions' frame and teardown collections. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The on-device database: one for the app, opened on first use and kept for the process. If
     * it cannot be opened, every history page is refused (never acknowledged), so the ring keeps
     * them until a later run can store them.
     */
    private val database: Deferred<StoreDatabase> =
        appScope.async(start = CoroutineStart.LAZY) { StoreFactory.open(context.applicationContext) }

    /**
     * Links to a ring over the phone's Bluetooth (`:ble`'s Android factory). In debug builds the
     * demo ring gets the demo link instead.
     */
    val ringLinkFactory: RingLinkFactory = RingLinkFactory { ring ->
        VariantLinks.demoLinkFor(ring, appScope) ?: RingLink(context.applicationContext, ring)
    }

    /** One line to the system log. Callers never pass frame bytes or an address. */
    val log: (String) -> Unit = { Log.i(LOG_TAG, it) }

    /** The app's private preferences file: the remembered ring and two flags. */
    private val keyValues = SharedPreferencesKeyValues(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

    /** Onboarding done, Nearby devices asked. */
    val appPrefs: AppPrefs = PrefsAppPrefs(keyValues)

    /** The ring the app reconnects to. */
    val rememberedRings: RememberedRingStore = PrefsRememberedRingStore(keyValues)

    /** Android's companion-device manager: the pairing sheet before the first bond, and forgetting. */
    val companionPairing: CompanionPairing = CompanionPairing(AndroidCompanionPort(context), log)

    /**
     * The sessions with the remembered ring: one link per ring, kept for the process (the Android
     * link listens for Bluetooth and bond changes from the moment it is built). A pairing hands its
     * ring here; launch reconnects the saved one without a scan; Stop reconnecting forgets it.
     */
    val ringSessions: RingSessions = RingSessions(
        links = ringLinkFactory,
        rings = rememberedRings,
        companion = companionPairing,
        scope = appScope,
        newSession = { link, scope -> RingSessionController(link, scope, monotonicMillis, log, history = historyFor(link)) },
        log = log,
    )

    /** The Bluetooth adapter's state; the activity starts and stops it as it shows and hides. */
    val adapterStates: AndroidAdapterStateSource = AndroidAdapterStateSource(context, log)

    /**
     * The scanner every Scan & connect uses: `:ble`'s Android scanner (it needs Nearby devices;
     * without it, or with Bluetooth off, the scan fails at once), or in debug builds one that
     * finds the demo ring. One for the process, so the last ring it matched is kept for
     * Connection details across scans.
     */
    val ringScanner: RingScanner by lazy { VariantLinks.demoScanner?.invoke() ?: RingScanner(context.applicationContext) }

    /** Hands each Scan & connect the one scanner. */
    val ringScannerFactory: RingScannerFactory = RingScannerFactory { ringScanner }

    /** What Connection details reads beyond the session: the pairing sheet's outcome, the scanner's last match. */
    val detailsSources: DetailsSources by lazy {
        DetailsSources(pairingOutcome = companionPairing.lastOutcome, scanMatch = (ringScanner as? ScanDiagnostics)?.lastMatch)
    }

    /** The Ring screen's title; debug builds say when the ring is the demo. */
    val ringTitle: String = "Ring" + VariantLinks.titleSuffix

    /**
     * Where [link]'s session keeps the ring's history: the app's one database, the ring keyed by
     * its address, the phone's wall clock and time zone, and the "Disconnect after syncing" switch.
     */
    private fun historyFor(link: RingLink): SessionHistory {
        val ringId = RingAddress.normalized(link.ring.address) ?: link.ring.address
        return SessionHistory(
            store = StoreHistory(
                database = { database.await() },
                ringId = ringId,
                zone = { ZoneId.systemDefault() },
            ),
            wallClock = { Instant.ofEpochMilli(clock.nowMillis()) },
            disconnectAfterSync = { appPrefs.disconnectAfterSync },
            // The link-up sync, the cadence while the link is held and the catch-up after the night.
            triggers = SyncTriggerSources(
                zone = { ZoneId.systemDefault() },
                storedNights = { learningNights(database.await()) },
                marks = PrefsSyncMarks(keyValues, ringId),
            ),
        )
    }

    private companion object {
        const val LOG_TAG = "OpenCircuit"
        const val PREFS_FILE = "opencircuit"
    }
}
