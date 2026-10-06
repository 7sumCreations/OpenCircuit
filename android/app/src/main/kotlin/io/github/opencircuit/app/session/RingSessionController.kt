package io.github.opencircuit.app.session

import io.github.opencircuit.app.live.LiveMeasureController
import io.github.opencircuit.app.ring.DeviceStatusModel
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
)

/**
 * The app's session with one ring: the ONLY collector of the link's [RingLink.frames] and
 * [RingLink.teardowns]. Each flow takes one collector at a time (a second collection while one
 * runs fails), and this controller holds one collection of each for the whole session and never
 * starts another, so every feature reaches frames through [dispatcher] (registering a handler for
 * its opcode), never through the flow. Because nothing restarts a collection that ended, the
 * dispatcher contains a handler's exception instead of letting it end the collection.
 *
 * Routes: `0x10` / `0x87` descriptors → [deviceStatus]; `0x15` live samples → [liveMeasure];
 * `0x11` heartbeats are ignored (the link already answered them); everything else is counted by
 * the dispatcher. A torn-down connection, or the link leaving [LinkState.Authenticated], stops a
 * running live measure. While the link is authenticated and idle, [keepalive] writes the status
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
) {
    /** Routes every frame; register a handler here to receive an opcode. */
    val dispatcher = FrameDispatcher(log)

    /** The ring's battery and status, from its descriptors. */
    val deviceStatus = DeviceStatusModel(scope, monotonicMillis)

    /** The live heart-rate / SpO₂ measure: writes through the link, reads the `0x15` frames. */
    val liveMeasure = LiveMeasureController(send = link::send, scope = scope, monotonicMillis = monotonicMillis)

    /** The idle `d0 00 00` keepalive and the status refresh after a measure. */
    val keepalive = KeepaliveTicker(
        send = link::send,
        scope = scope,
        linkState = link.state,
        isMeasuring = liveMeasure.isMeasuring,
        batteryReadings = { deviceStatus.state.value.batteryReadings },
        log = log,
    )

    /** Where the link stands. */
    val state: StateFlow<LinkState> get() = link.state

    private val teardownsFlow = MutableStateFlow(SessionTeardowns())

    /** The torn-down connections seen so far. */
    val teardowns: StateFlow<SessionTeardowns> = teardownsFlow.asStateFlow()

    private val started = AtomicBoolean(false)

    init {
        dispatcher.register(DESCRIPTOR, deviceStatus::onDescriptor)
        dispatcher.register(DESCRIPTOR_RESPONSE, deviceStatus::onDescriptor)
        dispatcher.register(LIVE_SAMPLE, liveMeasure::onFrame)
        dispatcher.ignore(HEARTBEAT)
    }

    /** Starts the collections. Calling it again does nothing: the session collects each flow once. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { link.frames.collect { dispatcher.dispatch(it) } }
        keepalive.start()
        scope.launch {
            link.teardowns.collect { teardown ->
                teardownsFlow.update { SessionTeardowns(count = it.count + 1, last = teardown) }
                liveMeasure.onLinkLost()
            }
        }
        // `state` is a StateFlow, so this is not a second collector of anything single-collector.
        scope.launch {
            link.state.collect { if (it != LinkState.Authenticated) liveMeasure.onLinkLost() }
        }
    }

    /** Starts collecting if not yet started, then asks the link to connect. */
    fun connect() {
        start()
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
    }
}
