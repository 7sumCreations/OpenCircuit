package io.github.opencircuit.app.session

import io.github.opencircuit.app.live.LiveMeasureController
import io.github.opencircuit.app.ring.DeviceStatusModel
import io.github.opencircuit.app.sync.HistoryDrainController
import io.github.opencircuit.app.sync.HistoryPages
import io.github.opencircuit.app.sync.SessionHistory
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
 * Routes: `0x10` / `0x87` descriptors → [deviceStatus]; `0x15` live samples and `0x86` mode
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
) {
    /** Routes every frame; register a handler here to receive an opcode. */
    val dispatcher = FrameDispatcher(log)

    /** The ring's battery and status, from its descriptors. */
    val deviceStatus = DeviceStatusModel(scope, monotonicMillis)

    /** The live heart-rate / SpO₂ measure: writes through the link, reads the `0x15` frames; none starts during a sync. */
    val liveMeasure: LiveMeasureController = LiveMeasureController(
        send = link::send,
        scope = scope,
        monotonicMillis = monotonicMillis,
        isSyncing = { sync.isSyncing.value },
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
        )
    }

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

    init {
        // Descriptors and 0x50 frames also answer a status query (PROTOCOL.md §4).
        // A descriptor's step bucket can confirm the wearer is up, ending the night's quiet.
        dispatcher.register(DESCRIPTOR) { deviceStatus.onDescriptor(it); statusQueries.onAnswer(); triggers?.onDescriptor(it) }
        dispatcher.register(DESCRIPTOR_RESPONSE) { deviceStatus.onDescriptor(it); statusQueries.onAnswer(); triggers?.onDescriptor(it) }
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
                // The link writes its own d0 right after its auth reply (PORTING.md D-257).
                if (it == LinkState.Authenticated) statusQueries.onQuery()
                if (it != LinkState.Authenticated) liveMeasure.onLinkLost()
            }
        }
    }

    /** Starts collecting if not yet started, then asks the link to connect. */
    fun connect() {
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
