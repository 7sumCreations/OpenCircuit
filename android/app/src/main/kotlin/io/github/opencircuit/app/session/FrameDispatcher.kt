package io.github.opencircuit.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlin.coroutines.cancellation.CancellationException

/** What the [FrameDispatcher] could not hand to a handler, counted (never a list of frames). */
data class DispatchCounts(
    /** Frames no handler is registered for, per opcode (history pages, unknown opcodes). */
    val unhandled: Map<Int, Int> = emptyMap(),
    /** Frames whose handler threw, per opcode. */
    val handlerFailures: Map<Int, Int> = emptyMap(),
    /** Frames of an opcode the app deliberately ignores (the heartbeat `:ble` already answered, the `81 01` auth answer). */
    val ignored: Int = 0,
    /** Frames with no bytes at all. */
    val empty: Int = 0,
)

/**
 * Routes each frame the ring sends to the one handler registered for its opcode (byte 0).
 *
 * Upstream runs every decoder on every frame and lets a frame nobody decodes fall through
 * (`ios/OpenCircuit/BLE/RingSession.swift:4910-4920, 5334-5357` @ b1c2fdd). Here a frame is never
 * lost without a trace: an opcode with no handler is counted per opcode and logged, and a handler
 * that throws is counted and logged while the next frame is still routed (PORTING.md D-230, D-231).
 * Log lines name the opcode only; they never carry a frame's bytes, and a flood of one opcode is
 * logged at its 1st, 2nd, 4th, 8th … occurrence only, while every frame is still counted.
 *
 * Routes are fixed by the first [dispatch]: registering or ignoring an opcode after it throws, so
 * the table the collector reads never changes under it. [dispatch] is called by the one collector
 * of the link's frames.
 */
class FrameDispatcher(private val log: (String) -> Unit) {

    private val handlers = HashMap<Int, (ByteArray) -> Unit>()
    private val ignoredOpcodes = HashSet<Int>()
    private val countsFlow = MutableStateFlow(DispatchCounts())

    @Volatile
    private var routesFixed = false

    /** Everything that did not reach a handler, or whose handler failed. */
    val counts: StateFlow<DispatchCounts> = countsFlow.asStateFlow()

    /** Routes frames whose first byte is [opcode] (0…255) to [handler]. One handler per opcode. */
    fun register(opcode: Int, handler: (ByteArray) -> Unit) {
        requireFreeOpcode(opcode)
        handlers[opcode] = handler
    }

    /**
     * Drops frames of [opcode] on purpose, counting them in [DispatchCounts.ignored] — for frames
     * another layer has already dealt with.
     */
    fun ignore(opcode: Int) {
        requireFreeOpcode(opcode)
        ignoredOpcodes += opcode
    }

    /** Hands [frame] to its opcode's handler, or counts and logs why it could not. */
    fun dispatch(frame: ByteArray) {
        routesFixed = true
        if (frame.isEmpty()) {
            val seen = countsFlow.updateAndGet { it.copy(empty = it.empty + 1) }.empty
            if (worthLogging(seen)) log("Empty frame from the ring (seen $seen)")
            return
        }
        val opcode = frame[0].toInt() and 0xFF
        val handler = handlers[opcode]
        when {
            handler != null -> runHandler(opcode, handler, frame)
            opcode in ignoredOpcodes -> countsFlow.update { it.copy(ignored = it.ignored + 1) }
            else -> {
                val seen = countsFlow.updateAndGet { it.copy(unhandled = it.unhandled.incremented(opcode)) }.unhandled.getValue(opcode)
                if (worthLogging(seen)) log("Unhandled frame opcode ${opcode.hex()} (seen $seen)")
            }
        }
    }

    private fun runHandler(opcode: Int, handler: (ByteArray) -> Unit, frame: ByteArray) {
        try {
            handler(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Contained so one bad frame cannot end the session's one collection of the link's
            // frames, which nothing restarts; counted and logged so the failure stays visible.
            val failures = countsFlow.updateAndGet { it.copy(handlerFailures = it.handlerFailures.incremented(opcode)) }
                .handlerFailures.getValue(opcode)
            if (worthLogging(failures)) log("Handler for frame opcode ${opcode.hex()} failed: ${e::class.java.simpleName} (failures $failures)")
        }
    }

    /** The 1st, 2nd, 4th, 8th … occurrence: a flood stays visible in the log without filling it. */
    private fun worthLogging(seen: Int): Boolean = seen and (seen - 1) == 0

    private fun requireFreeOpcode(opcode: Int) {
        check(!routesFixed) { "routes are fixed once the first frame was dispatched" }
        require(opcode in 0..0xFF) { "opcode must be a byte value 0..255: $opcode" }
        require(opcode !in handlers && opcode !in ignoredOpcodes) { "opcode ${opcode.hex()} already has a route" }
    }

    private fun Map<Int, Int>.incremented(key: Int): Map<Int, Int> = this + (key to (this[key] ?: 0) + 1)

    private fun Int.hex(): String = "0x%02x".format(java.util.Locale.ROOT, this)
}
