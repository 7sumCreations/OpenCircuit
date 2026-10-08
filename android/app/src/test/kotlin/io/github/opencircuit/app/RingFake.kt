package io.github.opencircuit.app

import io.github.opencircuit.ble.FakeRingLink
import io.github.opencircuit.ble.LinkInfo
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A ring that behaves like the real one where the history drain depends on it — never an
 * idealised one (the E8 fakes hid every fault the real phone showed). Built on [FakeRingLink], so
 * `frames` and `teardowns` keep the real link's one-collector contract.
 *
 * - [connect] authenticates after [connectMillis] of virtual time; [disconnect] tears the
 *   connection down (one user-disconnected teardown) and goes idle.
 * - A sync open `02 00 <cursor> <channel> 01 00` followed by `07 00 00` makes the ring answer
 *   [syncAck] ([SYNC_ACK_PAGES] by default) after [ackDelayMillis], then send the channel's FIRST
 *   page it still holds after the next page gap.
 * - The ring sends the NEXT page only after the app acknowledges the current one, after a gap of
 *   1–3 s ([pageGapsMillis], cycled). An acknowledged page is gone from the ring for good.
 * - After the last page is acknowledged it sends [endOfHistory] (a `0x50` report) after the next
 *   gap, or nothing when that is null.
 * - A page sent and not acknowledged stays the ring's next page: after a teardown, the next open
 *   offers it again with the same bytes.
 * - [sendStray] sends a page with no drain open (as after a live measure's `07 00 00`); it too
 *   waits for its acknowledgement.
 * - [acknowledge] answers as the real link: `NOT_A_PAGE` for another opcode, `PAGE_NOT_PENDING`
 *   for a page it is not waiting on. [beforeAck] runs inside an acknowledgement, at the moment the
 *   ring receives it (for "was the page stored first?").
 * - The app's `01 00 00` / `01 01 …` is refused, as the real link does, and recorded.
 *
 * Every write, acknowledgement and connection event is recorded with its virtual time.
 */
internal class RingFake(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** The pages the ring holds, per channel, oldest first. */
    channels: Map<Int, List<ByteArray>>,
    private val syncAck: ByteArray = SYNC_ACK_PAGES,
    private val endOfHistory: ByteArray? = END_OF_HISTORY,
    private val connectMillis: Long = 500,
    private val ackDelayMillis: Long = 200,
    private val pageGapsMillis: LongArray = longArrayOf(1_200, 2_600, 1_800),
    val fake: FakeRingLink = FakeRingLink(testRing),
) : RingLink by fake {

    /** One thing the app or the ring did, at virtual time [atMillis]. */
    data class Event(val atMillis: Long, val what: String)

    private val held: Map<Int, ArrayDeque<ByteArray>> = channels.mapValues { (_, pages) -> ArrayDeque(pages.map { it.copyOf() }) }
    private val log = mutableListOf<Event>()
    private val ackedPages = mutableListOf<ByteArray>()
    private var openChannel: Int? = null
    private var waitingOn: ByteArray? = null
    private var waitingChannel: Int? = null
    private var gapIndex = 0
    private var streamJob: Job? = null

    /** Runs inside each acknowledgement the ring accepts, before it is recorded. */
    var beforeAck: suspend (ByteArray) -> Unit = {}

    /** Everything that happened, in order. */
    val events: List<Event> get() = synchronized(this) { log.toList() }

    /** The app's writes (plain hex), with their times. */
    val writes: List<TimedWrite> get() = events.filter { it.what.startsWith("write ") }.map { TimedWrite(it.atMillis, it.what.removePrefix("write ")) }

    /** The pages the ring got an acknowledgement for, in order (copies). */
    val acknowledgedPages: List<ByteArray> get() = synchronized(this) { ackedPages.map { it.copyOf() } }

    /** The pages the ring still holds on [channel], oldest first (copies). */
    fun stillHeld(channel: Int): List<ByteArray> = synchronized(this) { held[channel].orEmpty().map { it.copyOf() } }

    init {
        fake.setInfo(LinkInfo(bonded = true, attMtu = 247, historySafe = true))
    }

    override suspend fun send(command: ByteArray): SendResult {
        val hex = command.toPlainHex()
        record("write $hex")
        if (fake.state.value != LinkState.Authenticated) return SendResult.Refused(RefusalReason.NOT_AUTHENTICATED)
        if (hex == "010000" || hex.startsWith("0101")) return SendResult.Refused(RefusalReason.AUTH_COMMAND_RESERVED)
        synchronized(this) {
            when {
                command.size == 9 && command[0] == 0x02.toByte() && command[7] == 0x01.toByte() -> openChannel = command[6].toInt() and 0xFF
                hex == "070000" && openChannel != null -> startChannel(openChannel!!)
            }
        }
        return SendResult.Sent
    }

    override suspend fun acknowledge(page: ByteArray): SendResult {
        val opcode = if (page.isEmpty()) -1 else page[0].toInt() and 0xFF
        if (opcode !in PAGE_OPCODES) return SendResult.Refused(RefusalReason.NOT_A_PAGE)
        val expected = synchronized(this) { waitingOn }
        if (expected == null || !expected.contentEquals(page)) {
            record("ack refused ${page.toPlainHex().take(6)}")
            return SendResult.Refused(RefusalReason.PAGE_NOT_PENDING)
        }
        beforeAck(page.copyOf())
        synchronized(this) {
            record("ack ${page.toPlainHex().take(6)}")
            ackedPages += page.copyOf()
            waitingChannel?.let { held.getValue(it).removeFirst() }
            waitingOn = null
            val channel = waitingChannel
            waitingChannel = null
            if (channel != null) streamJob = scope.launch { sendAfterGap(channel) }
        }
        return SendResult.Sent
    }

    override fun connect() {
        record("connect")
        fake.connect()
        if (fake.state.value != LinkState.Idle) return
        fake.setState(LinkState.Connecting)
        scope.launch {
            delay(connectMillis)
            if (fake.state.value == LinkState.Connecting) {
                fake.setState(LinkState.Authenticated)
                record("authenticated")
            }
        }
    }

    override fun disconnect() {
        record("disconnect")
        fake.disconnect()
        tearDown(TeardownReason.USER_DISCONNECTED)
    }

    /** The connection drops (out of range, Bluetooth off): any page waiting stays on the ring. */
    fun dropLink() {
        record("dropped")
        tearDown(TeardownReason.LINK_DROPPED)
    }

    /** The ring sends [page] with no drain open; it waits for its acknowledgement like any page. */
    fun sendStray(page: ByteArray) {
        synchronized(this) {
            waitingOn = page.copyOf()
            waitingChannel = null
            record("page ${page.toPlainHex().take(6)} (stray)")
        }
        fake.emitFrame(page)
    }

    private fun tearDown(reason: TeardownReason) {
        val unacknowledged = synchronized(this) {
            streamJob?.cancel()
            streamJob = null
            openChannel = null
            val waiting = if (waitingOn != null) 1 else 0
            waitingOn = null
            waitingChannel = null
            waiting
        }
        if (fake.state.value == LinkState.Idle) return
        fake.setState(LinkState.Idle)
        fake.emitTeardown(LinkTeardown(reason, undeliveredFrames = 0, pagesUnacknowledged = unacknowledged))
    }

    /** Called with the lock held, on `07 00 00` after an open. */
    private fun startChannel(channel: Int) {
        streamJob?.cancel()
        streamJob = scope.launch {
            delay(ackDelayMillis)
            record("frame ${syncAck.toPlainHex()}")
            fake.emitFrame(syncAck)
            sendAfterGap(channel)
        }
    }

    private suspend fun sendAfterGap(channel: Int) {
        delay(nextGap())
        val next = synchronized(this) { held[channel]?.firstOrNull()?.copyOf() }
        if (next == null) {
            endOfHistory?.let {
                record("frame ${it.toPlainHex()}")
                fake.emitFrame(it)
            }
            return
        }
        synchronized(this) {
            waitingOn = next
            waitingChannel = channel
            record("page ${next.toPlainHex().take(6)}")
        }
        fake.emitFrame(next)
    }

    private fun nextGap(): Long = synchronized(this) { pageGapsMillis[gapIndex++ % pageGapsMillis.size] }

    /** Adds [what] to [events] from outside the ring (the store's "commit returned"), so both share one order. */
    fun note(what: String) = record(what)

    private fun record(what: String) = synchronized(this) { log += Event(now(), what) }

    companion object {
        /** The `0x82` answer that pages follow: `82 00 00 82` (PROTOCOL.md §3, 🟢). */
        val SYNC_ACK_PAGES: ByteArray = hex("82000082")

        /**
         * A `0x50` end-of-history report in its 12-byte form, the real frame of upstream's
         * `EpochSyncTests.swift:58-60` @ b1c2fdd (`50 00 00 12 0c 22 aa e4 0c 22 ac b5`).
         */
        val END_OF_HISTORY: ByteArray = hex("500000120c22aae40c22acb5")

        private val PAGE_OPCODES = setOf(0x47, 0x4C, 0x4D)
    }
}
