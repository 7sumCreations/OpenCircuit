package io.github.opencircuit.app

import io.github.opencircuit.app.session.FrameDispatcher
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The frame dispatcher routes each frame by its opcode byte to the one handler registered for it.
 * A frame nobody handles is counted per opcode and logged (without its bytes), never dropped
 * silently; a handler that throws is counted and logged, and the next frame is still routed.
 */
class FrameDispatcherTest {

    private val logLines = mutableListOf<String>()
    private val dispatcher = FrameDispatcher(log = { logLines += it })

    @Test
    fun aFrameReachesTheHandlerRegisteredForItsOpcodeOnly() {
        val status = mutableListOf<ByteArray>()
        val live = mutableListOf<ByteArray>()
        dispatcher.register(0x10) { status += it }
        dispatcher.register(0x87) { status += it }
        dispatcher.register(0x15) { live += it }

        dispatcher.dispatch(TestFrames.wornDescriptor)
        dispatcher.dispatch(TestFrames.chargingResponseDescriptor)
        dispatcher.dispatch(TestFrames.liveHeartRate)

        assertEquals(2, status.size)
        assertContentEquals(TestFrames.wornDescriptor, status[0])
        assertContentEquals(TestFrames.chargingResponseDescriptor, status[1])
        assertEquals(1, live.size)
        assertContentEquals(TestFrames.liveHeartRate, live[0])
        assertEquals(emptyMap(), dispatcher.counts.value.unhandled)
        assertEquals(emptyList(), logLines)
    }

    @Test
    fun historyPagesAndUnknownOpcodesAreCountedPerOpcodeAndLoggedWithoutTheirBytes() {
        dispatcher.register(0x10) {}

        val strays = listOf(
            TestFrames.historyPage4c,
            TestFrames.historyPage4c,
            TestFrames.historyPage47,
            TestFrames.historyPage4d,
            TestFrames.unknown,
        )
        strays.forEach(dispatcher::dispatch)

        assertEquals(mapOf(0x4c to 2, 0x47 to 1, 0x4d to 1, 0xee to 1), dispatcher.counts.value.unhandled)
        assertEquals(5, logLines.size, "one log line per stray frame: $logLines")
        assertTrue(logLines[1].contains("0x4c"), "the line names the opcode: ${logLines[1]}")
        strays.forEach { frame ->
            val body = frame.copyOfRange(1, frame.size).toPlainHex()
            logLines.forEach { line ->
                assertFalse(line.lowercase().replace(" ", "").contains(body), "log carries frame bytes: $line")
            }
        }
    }

    @Test
    fun anIgnoredOpcodeIsNeitherHandledNorCountedAsUnhandled() {
        dispatcher.ignore(0x11)

        dispatcher.dispatch(TestFrames.heartbeat)

        assertEquals(emptyMap(), dispatcher.counts.value.unhandled)
        assertEquals(1, dispatcher.counts.value.ignored)
        assertEquals(emptyList(), logLines)
    }

    @Test
    fun anEmptyFrameIsCountedNotDropped() {
        dispatcher.dispatch(ByteArray(0))

        assertEquals(1, dispatcher.counts.value.empty)
        assertEquals(1, logLines.size)
    }

    @Test
    fun aHandlerThatThrowsIsCountedAndLoggedAndTheNextFrameIsStillRouted() {
        val routed = mutableListOf<ByteArray>()
        var calls = 0
        dispatcher.register(0x10) {
            calls++
            if (calls == 1) throw IllegalStateException("bad descriptor")
            routed += it
        }

        dispatcher.dispatch(TestFrames.wornDescriptor)
        dispatcher.dispatch(TestFrames.chargingDescriptor)

        assertEquals(1, routed.size, "the frame after the failure still reached the handler")
        assertContentEquals(TestFrames.chargingDescriptor, routed[0])
        assertEquals(mapOf(0x10 to 1), dispatcher.counts.value.handlerFailures)
        assertEquals(emptyMap(), dispatcher.counts.value.unhandled, "a failed frame is not 'unhandled'")
        assertEquals(1, logLines.size)
        assertTrue(logLines[0].contains("0x10") && logLines[0].contains("IllegalStateException"), logLines[0])
        assertFalse(logLines[0].contains(TestFrames.wornDescriptor.toPlainHex()), logLines[0])
    }

    @Test
    fun aHandlerFailureIsCountedForItsOwnOpcodeOnly() {
        dispatcher.register(0x10) { throw IllegalArgumentException("x") }
        dispatcher.register(0x87) { throw IllegalArgumentException("y") }
        dispatcher.register(0x15) {}

        dispatcher.dispatch(TestFrames.wornDescriptor)
        dispatcher.dispatch(TestFrames.wornDescriptor)
        dispatcher.dispatch(TestFrames.chargingResponseDescriptor)
        dispatcher.dispatch(TestFrames.liveHeartRate)

        assertEquals(mapOf(0x10 to 2, 0x87 to 1), dispatcher.counts.value.handlerFailures)
    }

    @Test
    fun cancellationThrownByAHandlerIsNotSwallowed() {
        dispatcher.register(0x15) { throw CancellationException("collector cancelled") }

        assertFailsWith<CancellationException> { dispatcher.dispatch(TestFrames.liveHeartRate) }
        assertEquals(emptyMap(), dispatcher.counts.value.handlerFailures)
    }

    @Test
    fun anOpcodeTakesOneHandlerAndOnlyByteValues() {
        dispatcher.register(0x10) {}

        assertFailsWith<IllegalArgumentException> { dispatcher.register(0x10) {} }
        assertFailsWith<IllegalArgumentException> { dispatcher.ignore(0x10) }
        assertFailsWith<IllegalArgumentException> { dispatcher.register(0x100) {} }
        assertFailsWith<IllegalArgumentException> { dispatcher.register(-1) {} }
    }
}
