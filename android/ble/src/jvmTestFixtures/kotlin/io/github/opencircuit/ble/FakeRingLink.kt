package io.github.opencircuit.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [RingLink] a test drives by hand, for code that uses the link (the app) without a ring.
 *
 * It keeps the real link's contract, with the same buffer behind it: [frames] and [teardowns]
 * each take ONE collector at a time (a second collection while one runs throws
 * [IllegalStateException]); items wait in order while nobody collects, and a collector that stops
 * or is cancelled leaves what it did not take for the next one. Every byte value is copied in and
 * out, and [send] answers with a value and never throws. Like the real link it offers
 * [LinkDiagnostics]; [emitDiagnostic] scripts the entries, of which the last 64 are kept.
 */
class FakeRingLink(override val ring: RememberedRing) : RingLink, LinkDiagnostics {

    private val stateFlow = MutableStateFlow<LinkState>(LinkState.Idle)
    private val infoFlow = MutableStateFlow(LinkInfo())
    private val frameBuffer = SingleCollectorBuffer<ByteArray>("frames")
    private val teardownBuffer = SingleCollectorBuffer<LinkTeardown>("teardowns")
    private val sentCommands = CopyOnWriteArrayList<ByteArray>()
    private val scriptedResults = ConcurrentLinkedQueue<SendResult>()
    private val connects = AtomicInteger()
    private val disconnects = AtomicInteger()

    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val info: StateFlow<LinkInfo> = infoFlow.asStateFlow()
    override val frames: Flow<ByteArray> = frameBuffer.flow
    override val teardowns: Flow<LinkTeardown> = teardownBuffer.flow
    private val diagnosticLog = DiagnosticLog()
    override val diagnostics: StateFlow<List<LinkDiagnostic>> = diagnosticLog.flow

    /** Adds [diagnostic] to [diagnostics], as the real link records a step; the last 64 are kept. */
    fun emitDiagnostic(diagnostic: LinkDiagnostic) {
        diagnosticLog.add(diagnostic)
    }

    /** Every command passed to [send], in order (copies). */
    val sent: List<ByteArray> get() = sentCommands.map { it.copyOf() }

    /** How many times [connect] was called. */
    val connectCalls: Int get() = connects.get()

    /** How many times [disconnect] was called. */
    val disconnectCalls: Int get() = disconnects.get()

    /** Sets the published [state]. */
    fun setState(value: LinkState) {
        stateFlow.value = value
    }

    /** Sets the published [info]. */
    fun setInfo(value: LinkInfo) {
        infoFlow.value = value
    }

    /** The ring sends [frame]: it is queued for the collector of [frames] (a copy). */
    fun emitFrame(frame: ByteArray) {
        frameBuffer.add(frame.copyOf())
    }

    /** A connection is torn down: [teardown] is queued for the collector of [teardowns]. */
    fun emitTeardown(teardown: LinkTeardown) {
        teardownBuffer.add(teardown)
    }

    /** The next calls to [send] answer with [results], in order; after them, [SendResult.Sent]. */
    fun answerSendsWith(vararg results: SendResult) {
        scriptedResults.addAll(results)
    }

    override suspend fun send(command: ByteArray): SendResult {
        sentCommands += command.copyOf()
        return scriptedResults.poll() ?: SendResult.Sent
    }

    override fun connect() {
        connects.incrementAndGet()
    }

    override fun disconnect() {
        disconnects.incrementAndGet()
    }
}
