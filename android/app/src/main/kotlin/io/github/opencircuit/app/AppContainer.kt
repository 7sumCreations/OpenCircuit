package io.github.opencircuit.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.opencircuit.app.connect.AndroidAdapterStateSource
import io.github.opencircuit.app.connect.AndroidCompanionPort
import io.github.opencircuit.app.connect.CompanionPairing
import io.github.opencircuit.app.connect.RingScannerFactory
import io.github.opencircuit.app.data.AppPrefs
import io.github.opencircuit.app.data.LaunchHousekeeping
import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.PrefsRememberedRingStore
import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.data.RingAddress
import io.github.opencircuit.app.data.RememberedRingStore
import io.github.opencircuit.app.data.SharedPreferencesKeyValues
import io.github.opencircuit.app.details.DetailsSources
import io.github.opencircuit.app.details.StorageSource
import io.github.opencircuit.app.details.storeFileBytes
import io.github.opencircuit.app.health.HealthAccess
import io.github.opencircuit.app.health.HealthFlush
import io.github.opencircuit.app.health.StoreHealthAccessMemory
import io.github.opencircuit.app.health.StoreHealthActivity
import io.github.opencircuit.app.health.StoreHealthFailures
import io.github.opencircuit.app.health.StoreHealthPeriods
import io.github.opencircuit.app.health.StoreHealthSamples
import io.github.opencircuit.app.health.StoreHealthSleep
import io.github.opencircuit.app.health.StoreWriteGenerations
import io.github.opencircuit.app.live.StoreLiveReadings
import io.github.opencircuit.health.HealthWriter
import io.github.opencircuit.health.JetpackHealthConnect
import io.github.opencircuit.health.RingDevice
import io.github.opencircuit.health.WriteGeneration
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.session.RingSessions
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.app.today.TodayRepository
import io.github.opencircuit.app.today.TodaySource
import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.app.sync.SyncLifecycle
import io.github.opencircuit.app.sync.SyncTriggerSources
import io.github.opencircuit.app.sync.learningNights
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.RingScanner
import io.github.opencircuit.ble.ScanDiagnostics
import io.github.opencircuit.store.STORE_DATABASE_NAME
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.open
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** What the Today screen reads, over the app's one database (so every writer's changes reach it). */
    val todaySource: TodaySource = TodayRepository({ database.await() })

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

    /** The phone's wall clock as an instant. */
    private val wallNow: () -> Instant = { Instant.ofEpochMilli(clock.nowMillis()) }

    /** Health Connect on this phone (the platform's, Android 14 and later); the one client of the app. */
    val healthConnect: JetpackHealthConnect = JetpackHealthConnect(context)

    /** The Health Connect write-failure map in the store, shared by every write and the card. */
    private val healthFailures = StoreHealthFailures({ database.await() }, wallNow, log)

    /**
     * Writes what the store holds and Health Connect lacks. The ring's model comes from the current
     * session's firmware revision, when one has been read. A store with no write generation yet
     * (a first install, a reinstall, cleared data) starts it above the wall clock in milliseconds,
     * above any version an earlier install left in Health Connect.
     */
    private val healthWriter = HealthWriter(
        port = healthConnect,
        store = StoreHealthSamples { database.await() },
        generation = WriteGeneration(StoreWriteGenerations({ database.await() }, wallNow)) { clock.nowMillis() },
        failureStore = healthFailures,
        clock = wallNow,
        zone = ZoneId::systemDefault,
        device = { RingDevice.ofFirmware(ringSessions.current.value?.let { it.link.info.value.firmware.version }) },
        sleep = StoreHealthSleep({ database.await() }, wallNow, ZoneId::systemDefault),
        activity = StoreHealthActivity({ database.await() }, wallNow, ZoneId::systemDefault),
        periods = StoreHealthPeriods { database.await() },
    )

    /**
     * The store's launch repairs (the purges, the night move, the backfill, the heal, the cursor
     * repair), once per process, off the main thread, after the store opened. None deletes a reading
     * for its age.
     */
    private val launchRepairs = LaunchHousekeeping({ database.await() }, wallNow, ZoneId::systemDefault, log).start(appScope)

    /**
     * Starts the Health Connect writes — after each committed sync and on "Write to Health Connect
     * now" — on the process scope, so neither a sync's pause nor a session's end cuts one. Each
     * write waits for the launch repairs: the night move re-keys the nights a write keeps its marks
     * under.
     */
    val healthFlush: HealthFlush = HealthFlush({ launchRepairs.join(); healthWriter.flush() }, appScope, log)

    /** Whether Health Connect is there and which WRITE permissions are granted; read now, on each resume and after the permission screen. */
    val healthAccess: HealthAccess =
        HealthAccess(healthConnect, StoreHealthAccessMemory({ database.await() }, wallNow, healthFailures), appScope, log).also { it.refresh() }

    /** Android's companion-device manager: the pairing sheet before the first bond, and forgetting. */
    val companionPairing: CompanionPairing = CompanionPairing(AndroidCompanionPort(context), log)

    /** The app's activity is started (on screen); set by the activity, kept through a rotation. */
    val appInForeground: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Today is on screen and resumed; set by the Today screen. Live heart rate after a sync runs only then. */
    val todayVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)

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
        newSession = { link, scope -> RingSessionController(link, scope, monotonicMillis, log, history = historyFor(link), todayVisible = todayVisible) },
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

    /**
     * What Connection details reads beyond the session: the pairing sheet's outcome, the scanner's
     * last match, and the store's size and counts (the Storage line) over the app's one database.
     */
    val detailsSources: DetailsSources by lazy {
        val databaseFile = context.applicationContext.getDatabasePath(STORE_DATABASE_NAME)
        DetailsSources(
            pairingOutcome = companionPairing.lastOutcome,
            scanMatch = (ringScanner as? ScanDiagnostics)?.lastMatch,
            storage = StorageSource({ database.await() }, fileBytes = { storeFileBytes(databaseFile) }, log = log).reports(),
        )
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
            // The switch acts only once the user has left the app; while it is in front the link stays up.
            disconnectAfterSync = { SyncLifecycle.disconnectAfterSync(appPrefs.disconnectAfterSync, appInForeground.value) },
            // The switch itself: a night catch-up it made owed still connects once the app is opened.
            disconnectSwitch = { appPrefs.disconnectAfterSync },
            // The link-up sync, the cadence while the link is held and the catch-up after the night.
            triggers = SyncTriggerSources(
                zone = { ZoneId.systemDefault() },
                storedNights = { learningNights(database.await()) },
                marks = PrefsSyncMarks(keyValues, ringId),
            ),
            zone = { ZoneId.systemDefault() },
            // The debug build's sync test action; the release build's never cuts.
            faults = VariantLinks.syncFaults,
            // Each committed sync's data goes on to Health Connect.
            afterCommit = { healthFlush.request() },
            // What the ring measures live while connected: steps, nightly skin temperature, a measure's result.
            live = StoreLiveReadings({ database.await() }, wallNow, { ZoneId.systemDefault() }),
        )
    }

    private companion object {
        const val LOG_TAG = "OpenCircuit"
        const val PREFS_FILE = "opencircuit"
    }
}
