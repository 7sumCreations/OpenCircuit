package io.github.opencircuit.app.session

import io.github.opencircuit.app.live.AutoLive
import io.github.opencircuit.app.live.LiveMeasureController
import io.github.opencircuit.app.live.LiveReadings
import io.github.opencircuit.app.ring.DeviceStatusModel
import io.github.opencircuit.app.sync.HistoryDrainController
import io.github.opencircuit.app.sync.HistoryPages
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.app.sync.SyncLifecycle
import io.github.opencircuit.app.sync.SyncRecords
import io.github.opencircuit.app.sync.SyncTriggers
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RingLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The link's torn-down connections as the app saw them. */
data class SessionTeardowns(
    /** How many connections were torn down since the app started collecting. */
    val count: Int = 0,
    /** The most recent one, or null if none yet. */
    val last: LinkTeardown? = null,
    /** Frames the link dropped at those teardowns, in all. */
    val undeliveredFrames: Int = 0,
)

/**
 * The app's session with one ring: the ONLY collector of the link's [RingLink.frames] and
 * [RingLink.teardowns]. Each flow takes one collector at a time (a second collection while one
 * runs fails), and this controller holds one collection of each for the whole session and never
 * starts another, so every feature reaches frames through [dispatcher] (registering a handler for
 * its opcode), never through the flow. Because nothing restarts a collection that ended, the
 * dispatcher contains a handler's exception instead of letting it end the collection.
 *
 * Routes: `0x10` / `0x87` descriptors → [deviceStatus] and [liveReadings]; `0x15` live samples and `0x86` mode
 * answers → [liveMeasure]; `0x11` heartbeats (the link already answered them) and `0x81` status
 * replies (the link answers the `81 00` challenge itself; what reaches the app is the ring's
 * `81 01 …` answer to the auth reply) are ignored; everything else is counted by the dispatcher.
 * A torn-down connection, or the link leaving [LinkState.Authenticated], stops a running live
 * measure. While the link is authenticated and idle, [keepalive] writes the status
 * query on its cadence and asks for a fresh status after each measure. Every collection runs in
 * [scope], which outlives any one screen.
 */
class RingSessionController(
    /** The link to the ring. */
    val link: RingLink,
    private val scope: CoroutineScope,
    /** Monotonic milliseconds (never jumps with the wall clock); virtual time in tests. */
    monotonicMillis: () -> Long,
    log: (String) -> Unit,
    /** Where the ring's history is kept; without one every page is refused and stays on the ring. */
    history: SessionHistory = SessionHistory.NONE,
    /** Today is on screen (resumed): only then does a sync's end start live heart rate ([autoLive]). */
    todayVisible: StateFlow<Boolean> = MutableStateFlow(false),
) {
    /** Routes every frame; register a handler here to receive an opcode. */
    val dispatcher = FrameDispatcher(log)

    /** The ring's battery and status, from its descriptors. */
    val deviceStatus = DeviceStatusModel(scope, monotonicMillis)

    /** Keeps what the ring measures live: each descriptor's steps and skin temperature, each measure's reading. */
    val liveReadings = LiveReadings(history.live, scope, history.wallClock, history.zone, log)

    /** The live heart-rate / SpO₂ measure: writes through the link, reads the `0x15` frames; none starts during a sync. */
    val liveMeasure: LiveMeasureController = LiveMeasureController(
        send = link::send,
        scope = scope,
        monotonicMillis = monotonicMillis,
        isSyncing = { sync.isSyncing.value },
        wallClock = history.wallClock,
        onMeasured = liveReadings::onMeasured,
    )

    /** Whether a status query waits for its answer: the drain's first open waits for it (or 2 s). */
    private val statusQueries = StatusQueries(monotonicMillis)

    /** The history frames: each page stored, then acknowledged; the sync answer and end report passed to the drain. */
    val historyPages = HistoryPages(history.store, link::acknowledge, scope, history.wallClock, log, monotonicMillis)

    /** The sync log and what is stored, kept across launches: read at the session's start, written after each sync. */
    val syncRecords = SyncRecords(
        store = history.store,
        zone = history.zone,
        firmware = { link.info.value.firmware.version.takeIf(String::isNotEmpty) },
        log = log,
    )

    /** Sync now: drains the ring's history into the store, then disconnects when the switch says so. Never during a measure. */
    val sync: HistoryDrainController = HistoryDrainController(
        link = link,
        connect = ::connect,
        pages = historyPages,
        store = history.store,
        wallClock = history.wallClock,
        monotonicMillis = monotonicMillis,
        disconnectAfterSync = history.disconnectAfterSync,
        scope = scope,
        log = log,
        isMeasuring = { liveMeasure.isMeasuring.value },
        awaitStatusQuiet = statusQueries::awaitQuiet,
        nightWindow = { triggers?.nightWindow()?.window },
        record = syncRecords::record,
        faults = history.faults,
        afterCommit = history.afterCommit,
    )

    /**
     * The automatic syncs — on link-up, on the cadence while the link is held, the catch-up after
     * the night — or null when the session was given nothing to drive them with (the E8 sessions).
     */
    val triggers: SyncTriggers? = history.triggers?.let { sources ->
        SyncTriggers(
            sync = sync,
            linkState = link.state,
            isMeasuring = liveMeasure.isMeasuring,
            sources = sources,
            wallClock = history.wallClock,
            disconnectAfterSync = history.disconnectAfterSync,
            scope = scope,
            log = log,
            disconnect = link::disconnect,
            takeUserLinkUp = ::takeUserLinkUp,
            autoMeasuring = { liveMeasure.state.value.let { it.mode != null && it.auto } },
            yieldAutoMeasure = liveMeasure::yieldAuto,
        )
    }

    /** Live heart rate on Today after each sync's end, never in the night; stands down for any sync. */
    val autoLive = AutoLive(
        measure = liveMeasure,
        sync = sync.state,
        todayVisible = todayVisible,
        nightQuiet = { triggers?.nightQuietNow() == true },
        scope = scope,
        log = log,
        linkState = link.state,
    )

    /** The idle `d0 00 00` keepalive and the status refresh after a measure; paused for a whole sync. */
    val keepalive = KeepaliveTicker(
        send = { command ->
            statusQueries.onQuery() // the keepalive writes only the status query
            link.send(command)
        },
        scope = scope,
        linkState = link.state,
        isMeasuring = liveMeasure.isMeasuring,
        batteryReadings = { deviceStatus.state.value.batteryReadings },
        log = log,
        isSyncing = sync.isSyncing,
    )

    /** Where the link stands. */
    val state: StateFlow<LinkState> get() = link.state

    /** The app's own timings of this link's connections. */
    val timer = ConnectionTimer(monotonicMillis)

    private val teardownsFlow = MutableStateFlow(SessionTeardowns())

    /** The torn-down connections seen so far. */
    val teardowns: StateFlow<SessionTeardowns> = teardownsFlow.asStateFlow()

    private val started = AtomicBoolean(false)

    /**
     * The connection attempt the user asked for ([connect]), or null: taken at that attempt's
     * link-up ([takeUserLinkUp]), and dropped when the attempt ends without one ([settleUserLinkUp]).
     */
    private val userLinkUp = AtomicReference<UserLinkUp?>(null)

    init {
        // Descriptors and 0x50 frames also answer a status query (PROTOCOL.md §4).
        // A descriptor's step bucket can confirm the wearer is up, ending the night's quiet.
        // Its steps and skin temperature are kept (liveReadings).
        dispatcher.register(DESCRIPTOR) { deviceStatus.onDescriptor(it); statusQueries.onAnswer(); triggers?.onDescriptor(it); liveReadings.onDescriptor(it) }
        dispatcher.register(DESCRIPTOR_RESPONSE) { deviceStatus.onDescriptor(it); statusQueries.onAnswer(); triggers?.onDescriptor(it); liveReadings.onDescriptor(it) }
        dispatcher.register(LIVE_SAMPLE, liveMeasure::onFrame)
        dispatcher.register(MODE_REPLY, liveMeasure::onModeReply)
        dispatcher.ignore(HEARTBEAT)
        dispatcher.ignore(STATUS_REPLY)
        // Here, at construction: routes are fixed by the first frame dispatched.
        HistoryPages.OPCODES.forEach { opcode ->
            if (opcode == END_OF_HISTORY) {
                dispatcher.register(opcode) { historyPages.onFrame(it); statusQueries.onAnswer() }
            } else {
                dispatcher.register(opcode, historyPages::onFrame)
            }
        }
    }

    /** Starts the collections. Calling it again does nothing: the session collects each flow once. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { link.frames.collect { dispatcher.dispatch(it) } }
        scope.launch { syncRecords.load() }
        keepalive.start()
        triggers?.start()
        autoLive.start()
        scope.launch {
            link.teardowns.collect { teardown ->
                teardownsFlow.update {
                    SessionTeardowns(count = it.count + 1, last = teardown, undeliveredFrames = it.undeliveredFrames + teardown.undeliveredFrames)
                }
                sync.onTeardown(teardown)
                liveMeasure.onLinkLost()
            }
        }
        // `state` is a StateFlow, so this is not a second collector of anything single-collector.
        scope.launch {
            link.state.collect {
                timer.onState(it)
                settleUserLinkUp(it)
                // The link writes its own d0 right after its auth reply (PORTING.md D-257).
                if (it == LinkState.Authenticated) statusQueries.onQuery()
                if (it != LinkState.Authenticated) liveMeasure.onLinkLost()
            }
        }
    }

    /**
     * The user left the app ([SyncLifecycle.disconnectOnStop]): with "Disconnect after syncing"
     * [switchOn], an idle held link closes — a running measure is stopped first, as the user's own
     * Disconnect does. A running sync is left to its pause; its end closes the link.
     */
    fun onAppLeft(switchOn: Boolean, changingConfigurations: Boolean) {
        val linkUp = link.state.value == LinkState.Authenticated
        if (!SyncLifecycle.disconnectOnStop(switchOn, linkUp, sync.isSyncing.value, changingConfigurations)) return
        liveMeasure.stop()
        link.disconnect()
    }

    /**
     * The user asked for the link (Scan & connect, Try again, Sync now): starts collecting if not
     * yet started, then asks the link to connect. The link-up this brings is marked as the user's
     * ([takeUserLinkUp]), so the automatic syncs never close it on the throttle.
     */
    fun connect() {
        // An authenticated link brings no new link-up: nothing to mark.
        val now = link.state.value
        if (now != LinkState.Authenticated) userLinkUp.set(UserLinkUp(requestedIn = now))
        connectLink()
    }

    /**
     * The remembered ring's automatic reconnect (the screen opening at launch): as [connect], but
     * the link-up is nobody's request, so it drops any earlier user mark.
     */
    fun reconnect() {
        userLinkUp.set(null)
        connectLink()
    }

    /**
     * Whether the link-up happening now is one the user asked for; the mark is taken, so a later
     * link-up the link makes on its own (after a drop) is automatic again. Read by the automatic
     * syncs at each link-up.
     */
    fun takeUserLinkUp(): Boolean = userLinkUp.getAndSet(null) != null

    /**
     * Ends the user's mark with the attempt it was set for (every exit releases what the start
     * set): the link back at rest — idle, reconnecting, pairing failed, Bluetooth off — after the
     * attempt got under way, or at a resting state other than the one [connect] was called in,
     * means it ended without a link-up the automatic syncs took (it failed, dropped, or its
     * `Authenticated` was conflated away before they saw it). The link's own next reconnect is then
     * automatic. The state [connect] was called in, seen before the link moved, is not an end:
     * the link changes state on its own event loop, after [connect] returns.
     */
    private fun settleUserLinkUp(state: LinkState) {
        userLinkUp.updateAndGet { mark ->
            when {
                mark == null -> null
                state == LinkState.Authenticated -> mark // the automatic syncs take it at this link-up
                state.comingUp -> mark.copy(underway = true)
                mark.underway || state != mark.requestedIn -> null
                else -> mark
            }
        }
    }

    /** A connection attempt the user asked for: the state the link was in when asked, and whether it has moved on since. */
    private data class UserLinkUp(val requestedIn: LinkState, val underway: Boolean = false)

    /**
     * The link is opening a connection, or holds one the ring's first data frame can still move on
     * to [LinkState.Authenticated]. Every state is listed, so a new one must be placed here.
     */
    private val LinkState.comingUp: Boolean
        get() = when (this) {
            LinkState.Connecting, LinkState.Discovering, LinkState.PairingNeeded,
            LinkState.Preparing, LinkState.Authenticating, LinkState.NotStreaming -> true
            LinkState.Idle, is LinkState.PairingFailed, is LinkState.Reconnecting, LinkState.WaitingForRing,
            LinkState.BondLostSuspected, LinkState.BluetoothOff, LinkState.Authenticated -> false
        }

    private fun connectLink() {
        start()
        timer.onConnect()
        link.connect()
    }

    private companion object {
        /** Device-status descriptor, sent by the ring on its own (PROTOCOL.md §5.4). */
        const val DESCRIPTOR = 0x10

        /** The same descriptor as a response (PROTOCOL.md §5.4). */
        const val DESCRIPTOR_RESPONSE = 0x87

        /** Live sample, the response to the `95 00 00` poll. */
        const val LIVE_SAMPLE = 0x15

        /** The ring's heartbeat; `:ble` answers it with `91 00 00`. */
        const val HEARTBEAT = 0x11

        /** The answer to a `06 xx 00` mode write, `86 <status> <xor>` (PROTOCOL.md §4). */
        const val MODE_REPLY = 0x86

        /** Status replies, `81 …` (PROTOCOL.md §5.7); the link keeps the `81 00` challenge to itself. */
        const val STATUS_REPLY = 0x81

        /** The ring's `0x50` frame: the end of a history channel, or an answer to `d0 00 00` (PROTOCOL.md §4). */
        const val END_OF_HISTORY = 0x50
    }
}
