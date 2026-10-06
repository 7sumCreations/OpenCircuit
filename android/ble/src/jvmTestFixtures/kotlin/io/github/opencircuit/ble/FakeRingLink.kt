package io.github.opencircuit.ble

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [RingLink] a test drives by hand, for code that uses the link (the app) without a ring.
 *
 * It keeps the real link's contract: [frames] and [teardowns] each take exactly one collector (a
 * second collection throws [IllegalStateException]), frames wait in order while nobody collects,
 * every byte value is copied in and out, and [send] answers with a value and never throws.
 */
class FakeRingLink(override val ring: RememberedRing) : RingLink {

    private val stateFlow = MutableStateFlow<LinkState>(LinkState.Idle)
    private val infoFlow = MutableStateFlow(LinkInfo())
    private val frameChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val teardownChannel = Channel<LinkTeardown>(Channel.UNLIMITED)
    private val sentCommands = CopyOnWriteArrayList<ByteArray>()
    private val scriptedResults = ConcurrentLinkedQueue<SendResult>()
    private val connects = AtomicInteger()
    private val disconnects = AtomicInteger()

    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val info: StateFlow<LinkInfo> = infoFlow.asStateFlow()
    override val frames: Flow<ByteArray> = frameChannel.consumeAsFlow()
    override val teardowns: Flow<LinkTeardown> = teardownChannel.consumeAsFlow()

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
        frameChannel.trySend(frame.copyOf())
    }

    /** A connection is torn down: [teardown] is queued for the collector of [teardowns]. */
    fun emitTeardown(teardown: LinkTeardown) {
        teardownChannel.trySend(teardown)
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
