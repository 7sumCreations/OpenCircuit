package io.github.opencircuit.app.session

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
 * Receives the live-sample frames (`0x15`, the answer to the `95 00 00` poll) and, for now, only
 * counts them; the live measure takes them over when it is built.
 */
class LiveFrameCounter {
    private val receivedFlow = MutableStateFlow(0)

    /** How many live-sample frames arrived. */
    val received: StateFlow<Int> = receivedFlow.asStateFlow()

    /** Takes one live-sample frame. */
    fun onFrame(frame: ByteArray) {
        receivedFlow.update { it + 1 }
    }
}

/**
 * The app's session with one ring: the ONLY collector of the link's [RingLink.frames] and
 * [RingLink.teardowns]. Each flow takes exactly one collection for the link's lifetime, so every
 * feature reaches frames through [dispatcher] (registering a handler for its opcode), never
 * through the flow.
 *
 * Routes: `0x10` / `0x87` descriptors → [deviceStatus]; `0x15` live samples → [liveFrames];
 * `0x11` heartbeats are ignored (the link already answered them); everything else is counted by
 * the dispatcher. Both collections run in [scope], which outlives any one screen.
 */
class RingSessionController(
    /** The link to the ring. */
    val link: RingLink,
    private val scope: CoroutineScope,
    log: (String) -> Unit,
) {
    /** Routes every frame; register a handler here to receive an opcode. */
    val dispatcher = FrameDispatcher(log)

    /** The ring's battery and status, from its descriptors. */
    val deviceStatus = DeviceStatusModel()

    /** The live-sample frames. */
    val liveFrames = LiveFrameCounter()

    /** Where the link stands. */
    val state: StateFlow<LinkState> get() = link.state

    private val teardownsFlow = MutableStateFlow(SessionTeardowns())

    /** The torn-down connections seen so far. */
    val teardowns: StateFlow<SessionTeardowns> = teardownsFlow.asStateFlow()

    private val started = AtomicBoolean(false)

    init {
        dispatcher.register(DESCRIPTOR, deviceStatus::onDescriptor)
        dispatcher.register(DESCRIPTOR_RESPONSE, deviceStatus::onDescriptor)
        dispatcher.register(LIVE_SAMPLE, liveFrames::onFrame)
        dispatcher.ignore(HEARTBEAT)
    }

    /** Starts the two collections. Calling it again does nothing: a flow is collected once. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { link.frames.collect { dispatcher.dispatch(it) } }
        scope.launch {
            link.teardowns.collect { teardown ->
                teardownsFlow.update { SessionTeardowns(count = it.count + 1, last = teardown) }
            }
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
