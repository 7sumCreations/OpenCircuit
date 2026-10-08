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
    private val pendingPages = ArrayList<ByteArray>()
    private val acknowledgedPages = CopyOnWriteArrayList<ByteArray>()
    private val refusedAcks = CopyOnWriteArrayList<RefusalReason>()
    private val scriptedAcks = ConcurrentLinkedQueue<SendResult>()

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

    /**
     * The ring sends [frame]: it is queued for the collector of [frames] (a copy). A history page
     * (`0x47` / `0x4c` / `0x4d`) waits for [acknowledge], as on the real link.
     */
    fun emitFrame(frame: ByteArray) {
        if (isPage(frame)) synchronized(pendingPages) { pendingPages += frame.copyOf() }
        frameBuffer.add(frame.copyOf())
    }

    /**
     * A connection is torn down: [teardown] is queued for the collector of [teardowns], and the
     * pages it delivered can no longer be acknowledged (the real link refuses them from now on).
     */
    fun emitTeardown(teardown: LinkTeardown) {
        synchronized(pendingPages) { pendingPages.clear() }
        teardownBuffer.add(teardown)
    }

    /** Every page passed to [acknowledge] and written, in order (copies); never mixed into [sent]. */
    val acknowledged: List<ByteArray> get() = acknowledgedPages.map { it.copyOf() }

    /** Every call to [acknowledge] that was refused, with its reason, in order. */
    val refusedAcknowledgements: List<RefusalReason> get() = refusedAcks.toList()

    /** The next calls to [acknowledge] of a pending page answer with [results], in order; after them, [SendResult.Sent]. */
    fun answerAcknowledgesWith(vararg results: SendResult) {
        scriptedAcks.addAll(results)
    }

    /**
     * As the real link: [SendResult.Refused] with [RefusalReason.NOT_A_PAGE] for any other opcode,
     * with [RefusalReason.PAGE_NOT_PENDING] for a page this connection did not deliver or already
     * acknowledged; otherwise the scripted answer, recorded in [acknowledged] when it is `Sent`.
     */
    override suspend fun acknowledge(page: ByteArray): SendResult {
        if (!isPage(page)) return refuse(RefusalReason.NOT_A_PAGE)
        val taken = synchronized(pendingPages) {
            val index = pendingPages.indexOfFirst { it.contentEquals(page) }
            if (index >= 0) pendingPages.removeAt(index)
            index >= 0
        }
        if (!taken) return refuse(RefusalReason.PAGE_NOT_PENDING)
        val result = scriptedAcks.poll() ?: SendResult.Sent
        if (result == SendResult.Sent) acknowledgedPages += page.copyOf()
        return result
    }

    private fun refuse(reason: RefusalReason): SendResult {
        refusedAcks += reason
        return SendResult.Refused(reason)
    }

    private fun isPage(frame: ByteArray): Boolean =
        frame.isNotEmpty() && (frame[0].toInt() and 0xFF) in PAGE_OPCODES

    private val reauthentications = AtomicInteger()
    private val scriptedReauths = ConcurrentLinkedQueue<SendResult>()

    /** How many times [reauthenticate] was called; never mixed into [sent]. */
    val reauthenticateCalls: Int get() = reauthentications.get()

    /** The next calls to [reauthenticate] answer with [results], in order; after them, [SendResult.Sent]. */
    fun answerReauthenticatesWith(vararg results: SendResult) {
        scriptedReauths.addAll(results)
    }

    /** Counted in [reauthenticateCalls]; answers the scripted result, else [SendResult.Sent]. Writes nothing to [sent]. */
    override suspend fun reauthenticate(): SendResult {
        reauthentications.incrementAndGet()
        return scriptedReauths.poll() ?: SendResult.Sent
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

    private companion object {
        /** The history pages the ring waits on an acknowledgement for. */
        val PAGE_OPCODES = setOf(0x47, 0x4C, 0x4D)
    }
}
