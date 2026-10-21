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
 * - [connect] authenticates after [connectMillis] of virtual time and, [postAuthReplyMillis]
 *   later, sends [postAuthReply] — the ring's answer to the `d0 00 00` the link writes after its
 *   auth reply (a `0x10` descriptor by default; null for a ring that does not answer it).
 *   [connectMillis] can be changed between connects: a Gen 2 on FR02.022 whose link
 *   authenticates only on the ring's own first frame took 38.8 s on a real phone, and up to
 *   ~110 s is possible on its timer (`docs/PROTOCOL.md` §5.8). With [notStreamingAfterMillis] a
 *   connection still waiting shows `NotStreaming` first, as the real link does. A connect while
 *   one is coming up changes nothing; [disconnect] while it comes up ends it (it never
 *   authenticates).
 *   [disconnect] tears the connection down (one user-disconnected teardown) and goes idle.
 *   [reconnectOnItsOwn] is the real link's own reconnect after a drop: it comes up with no
 *   `connect()` from the app (an automatic link-up nobody tapped for).
 * - Every `d0 00 00` the app writes is answered with [statusReply] after [statusReplyMillis]
 *   (a descriptor by default; a `0x50` frame models the ring's other answer, PROTOCOL.md §4).
 * - A sync open `02 00 <cursor> <channel> 01 00` followed by `07 00 00` makes the ring answer
 *   after [ackDelayMillis]: [syncAck] (`82 00 00 82`) when the channel holds pages, else
 *   [emptyAck] when set (`82 ff 00 7d`), else [syncAck]. Then it sends the channel's FIRST page
 *   it still holds after the next page gap.
 * - The ring sends the NEXT page only after the app acknowledges the current one, after a gap of
 *   1–3 s ([pageGapsMillis], cycled). An acknowledged page is gone from the ring for good.
 * - [pagesPerOpen]: after that many pages in one open the ring goes quiet with no end report,
 *   though it holds more (the "half-night" ring); only a new open sends the next ones.
 * - After the last page is acknowledged it sends the channel's end report ([endOfHistory], or
 *   [endOfHistoryBy] for that channel; null = none: the ring goes quiet after its last page). The fake
 *   sends the pages it was given byte for byte; the builders (`HistoryTestPages`, `BacklogPages`)
 *   write each `0x4c` countdown as the real ring does: records still queued after the page + 1,
 *   so a channel's last page reads 1 (a real FR02.022 drain of 652 records: 649 → 1).
 * - Page formats per firmware: a channel's pages may be `0x4c` / `0x47` / `0x4d` (FR02.018 and
 *   later, e.g. FR02.022) or `0x40` (older Gen 2 firmware, FR02.013 — the night arrives as `0x40`
 *   pages, each released only by its `c0` acknowledgement, ../docs/PROTOCOL.md §5.3a). The `0x40`
 *   layout was never captured, so test `0x40` pages carry arbitrary bodies.
 * - A `07 00 00` with no open before it (a fetch nudge) gets nothing.
 * - [ignoreOpensUntilReauth]: every open is ignored (no `0x82`, no page) until [reauthenticate];
 *   [ignoreAllOpens]: every open is ignored, re-auth or not.
 * - A page sent and not acknowledged stays the ring's next page: after a teardown, or a re-open,
 *   the ring offers it again with the same bytes.
 * - [sendStray] sends a page with no drain open (as after a live measure's `07 00 00`); it too
 *   waits for its acknowledgement.
 * - [acknowledge] answers as the real link: `NOT_A_PAGE` for another opcode, `PAGE_NOT_PENDING`
 *   for a page it is not waiting on. [beforeAck] runs inside an acknowledgement, at the moment the
 *   ring receives it (for "was the page stored first?").
 * - The app's `01 00 00` / `01 01 …` is refused, as the real link does, and recorded.
 *   [reauthenticate] is the link's own re-auth: recorded as `reauth`, never as a write.
 * - Live measure (PROTOCOL.md §4, §5.1): each mode write `06 01 00` (heart rate) / `06 02 00`
 *   (SpO₂) / `06 00 00` (idle) is answered `86 00 86` after [modeReplyMillis] and sets the ring's
 *   live mode. In a live mode each `95 00 00` poll is answered after [pollReplyMillis] with one
 *   `0x15` frame: in heart-rate mode the next of [liveHeartRateFrames] (a real read, warm-up
 *   sentinel first; its last frame repeats), in SpO₂ mode the next of [liveSpO2Frames] (cycled).
 *   With [worn] false the ring is off a finger: heart-rate polls get only the warm-up sentinel
 *   (it never locks) and SpO₂ polls get nothing. A poll with no live mode gets nothing; a
 *   teardown leaves the live mode.
 *
 * Every write, acknowledgement, re-auth and connection event is recorded with its virtual time.
 */
internal class RingFake(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** The pages the ring holds, per channel, oldest first. */
    channels: Map<Int, List<ByteArray>>,
    private val syncAck: ByteArray = SYNC_ACK_PAGES,
    private val emptyAck: ByteArray? = null,
    private val endOfHistory: ByteArray? = END_OF_HISTORY,
    private val endOfHistoryBy: Map<Int, ByteArray?> = emptyMap(),
    /** Settable between connects: a ring that authenticates fast once and late the next time. */
    var connectMillis: Long = 500,
    /** When set, a connection not yet authenticated this long after [connect] shows `NotStreaming` (the real link's 10 s after the CCCD). */
    private val notStreamingAfterMillis: Long? = null,
    private val ackDelayMillis: Long = 200,
    private val pageGapsMillis: LongArray = longArrayOf(1_200, 2_600, 1_800),
    private val pagesPerOpen: Int? = null,
    private val postAuthReply: ByteArray? = TestFrames.wornDescriptor,
    private val postAuthReplyMillis: Long = 0,
    private val statusReply: ByteArray? = TestFrames.wornDescriptor,
    private val statusReplyMillis: Long = 100,
    private val ignoreOpensUntilReauth: Boolean = false,
    private val ignoreAllOpens: Boolean = false,
    /** Frames the link reports it never delivered, at every teardown (a collector that fell behind). */
    private val undeliveredOnTeardown: Int = 0,
    private val modeReplyMillis: Long = 100,
    private val pollReplyMillis: Long = 100,
    private val liveHeartRateFrames: List<ByteArray> = TestFrames.realHeartRateRead,
    private val liveSpO2Frames: List<ByteArray> = TestFrames.realSpO2Read,
    /** Settable at any time: false takes the ring off the finger (live heart rate never locks). */
    @Volatile var worn: Boolean = true,
    val fake: FakeRingLink = FakeRingLink(testRing),
) : RingLink by fake {

    /** Settable at any time: live modes (`06 <mode> 00`) the ring refuses, answering `86 01 87` (PROTOCOL.md 4). */
    @Volatile var refusedModes: Set<Int> = emptySet()

    /** One thing the app or the ring did, at virtual time [atMillis]. */
    data class Event(val atMillis: Long, val what: String)

    private val held: Map<Int, ArrayDeque<ByteArray>> = channels.mapValues { (_, pages) -> ArrayDeque(pages.map { it.copyOf() }) }
    private val log = mutableListOf<Event>()
    private val ackedPages = mutableListOf<ByteArray>()
    private var openChannel: Int? = null
    private var openWaitingForFetch = false
    private var waitingOn: ByteArray? = null
    private var waitingChannel: Int? = null
    private var pagesThisOpen = 0
    private var reauthenticated = false
    private var gapIndex = 0
    private var streamJob: Job? = null

    /** The ring's live mode (`0x01` heart rate, `0x02` SpO₂), or null when idle; and the next frame of that mode's read. */
    private var liveMode: Int? = null
    private var liveFrameIndex = 0

    /** Counts connects that started a connection: a timer of an earlier connection changes nothing. */
    private var connectAttempt = 0

    /** Runs inside each acknowledgement the ring accepts, before it is recorded. */
    var beforeAck: suspend (ByteArray) -> Unit = {}

    /** Everything that happened, in order. */
    val events: List<Event> get() = synchronized(this) { log.toList() }

    /** The app's writes (plain hex), with their times. */
    val writes: List<TimedWrite> get() = events.filter { it.what.startsWith("write ") }.map { TimedWrite(it.atMillis, it.what.removePrefix("write ")) }

    /** When the link's re-auth was asked for, in order. */
    val reauths: List<Long> get() = events.filter { it.what == "reauth" }.map { it.atMillis }

    /** The pages the ring got an acknowledgement for, in order (copies). */
    val acknowledgedPages: List<ByteArray> get() = synchronized(this) { ackedPages.map { it.copyOf() } }

    /** The pages the ring still holds on [channel], oldest first (copies). */
    fun stillHeld(channel: Int): List<ByteArray> = synchronized(this) { held[channel].orEmpty().map { it.copyOf() } }

    /** The ring recorded more history on [channel] (a channel it was built with): the next sync drains [pages] too. */
    fun record(channel: Int, pages: List<ByteArray>) = synchronized(this) { held.getValue(channel).addAll(pages.map { it.copyOf() }) }

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
                command.size == 9 && command[0] == 0x02.toByte() && command[7] == 0x01.toByte() -> {
                    openChannel = command[6].toInt() and 0xFF
                    openWaitingForFetch = true
                }
                hex == "070000" && openWaitingForFetch -> {
                    openWaitingForFetch = false
                    if (!ignoreAllOpens && (!ignoreOpensUntilReauth || reauthenticated)) startChannel(openChannel!!)
                }
                hex == "d00000" -> statusReply?.let { reply -> scope.launch { delay(statusReplyMillis); emit(reply, "status reply") } }
                hex == "060000" || hex == "060100" || hex == "060200" -> {
                    val mode = command[1].toInt() and 0xFF
                    val refused = mode in refusedModes
                    liveMode = mode.takeIf { it != 0 && !refused }
                    liveFrameIndex = 0
                    scope.launch { delay(modeReplyMillis); emit(if (refused) MODE_REFUSED else MODE_ACCEPTED, "mode reply") }
                }
                hex == "950000" -> livePollReply()?.let { frame -> scope.launch { delay(pollReplyMillis); emit(frame, "live sample") } }
            }
        }
        return SendResult.Sent
    }

    override suspend fun reauthenticate(): SendResult {
        record("reauth")
        if (fake.state.value != LinkState.Authenticated) return SendResult.Refused(RefusalReason.NOT_AUTHENTICATED)
        synchronized(this) { reauthenticated = true }
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
            val quietNow = pagesPerOpen != null && pagesThisOpen >= pagesPerOpen && held[channel].orEmpty().isNotEmpty()
            if (channel != null && !quietNow) streamJob = scope.launch { sendAfterGap(channel) }
        }
        return SendResult.Sent
    }

    override fun connect() {
        record("connect")
        fake.connect()
        if (fake.state.value != LinkState.Idle) return
        fake.setState(LinkState.Connecting)
        val authAfter = connectMillis
        val attempt = synchronized(this) { ++connectAttempt }
        notStreamingAfterMillis?.takeIf { it < authAfter }?.let { after ->
            scope.launch {
                delay(after)
                if (fake.state.value == LinkState.Connecting && attempt == connectAttempt) {
                    fake.setState(LinkState.NotStreaming)
                    record("not streaming")
                }
            }
        }
        scope.launch {
            delay(authAfter)
            val stillComingUp = fake.state.value == LinkState.Connecting || fake.state.value == LinkState.NotStreaming
            if (stillComingUp && attempt == connectAttempt) {
                fake.setState(LinkState.Authenticated)
                record("authenticated")
                postAuthReply?.let { reply ->
                    delay(postAuthReplyMillis)
                    if (fake.state.value == LinkState.Authenticated) emit(reply, "status reply")
                }
            }
        }
    }

    override fun disconnect() {
        record("disconnect")
        fake.disconnect()
        tearDown(TeardownReason.USER_DISCONNECTED)
    }

    /**
     * The link comes back on its own, with no `connect()` from the app — as the real link does
     * after a drop (it keeps the remembered ring and reconnects when it is in range again):
     * connecting, then authenticated [connectMillis] later. Nothing happens unless the link is idle.
     */
    fun reconnectOnItsOwn() {
        if (fake.state.value != LinkState.Idle) return
        record("reconnecting on its own")
        fake.setState(LinkState.Connecting)
        val attempt = synchronized(this) { ++connectAttempt }
        scope.launch {
            delay(connectMillis)
            if (fake.state.value == LinkState.Connecting && attempt == connectAttempt) {
                fake.setState(LinkState.Authenticated)
                record("authenticated")
                postAuthReply?.let { reply ->
                    delay(postAuthReplyMillis)
                    if (fake.state.value == LinkState.Authenticated) emit(reply, "status reply")
                }
            }
        }
    }

    /** The connection drops (out of range, Bluetooth off): any page waiting stays on the ring. */
    fun dropLink() {
        record("dropped")
        tearDown(TeardownReason.LINK_DROPPED)
    }

    /**
     * The ring holds [pages] on [channel] again, ahead of what it still holds — a ring whose
     * pointer stepped back offers pages the app already stored (and acknowledged).
     */
    fun holdAgain(channel: Int, pages: List<ByteArray>) {
        synchronized(this) {
            val queue = (held as Map<Int, ArrayDeque<ByteArray>>)[channel] ?: error("the ring has no channel $channel")
            pages.reversed().forEach { queue.addFirst(it.copyOf()) }
        }
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

    /** The ring sends a `0x10` descriptor on its own (as it does every minute or so while worn). */
    fun sendDescriptor(frame: ByteArray) = emit(frame, "descriptor")

    private fun emit(frame: ByteArray, what: String) {
        record("frame ${frame.toPlainHex()} ($what)")
        fake.emitFrame(frame)
    }

    private fun tearDown(reason: TeardownReason) {
        val unacknowledged = synchronized(this) {
            streamJob?.cancel()
            streamJob = null
            openChannel = null
            openWaitingForFetch = false
            liveMode = null
            val waiting = if (waitingOn != null) 1 else 0
            waitingOn = null
            waitingChannel = null
            waiting
        }
        if (fake.state.value == LinkState.Idle) return
        fake.setState(LinkState.Idle)
        fake.emitTeardown(LinkTeardown(reason, undeliveredFrames = undeliveredOnTeardown, pagesUnacknowledged = unacknowledged))
    }

    /** Called with the lock held, on `07 00 00` after an open. A page sent and not acknowledged is offered again. */
    private fun startChannel(channel: Int) {
        streamJob?.cancel()
        waitingOn = null
        waitingChannel = null
        pagesThisOpen = 0
        val holds = held[channel].orEmpty().isNotEmpty()
        val answer = if (!holds && emptyAck != null) emptyAck else syncAck
        streamJob = scope.launch {
            delay(ackDelayMillis)
            record("frame ${answer.toPlainHex()}")
            fake.emitFrame(answer)
            sendAfterGap(channel)
        }
    }

    private suspend fun sendAfterGap(channel: Int) {
        delay(nextGap())
        val next = synchronized(this) { held[channel]?.firstOrNull()?.copyOf() }
        if (next == null) {
            val end = if (channel in endOfHistoryBy) endOfHistoryBy[channel] else endOfHistory
            end?.let {
                record("frame ${it.toPlainHex()}")
                fake.emitFrame(it)
            }
            return
        }
        synchronized(this) {
            waitingOn = next
            waitingChannel = channel
            pagesThisOpen++
            record("page ${next.toPlainHex().take(6)}")
        }
        fake.emitFrame(next)
    }

    private fun nextGap(): Long = synchronized(this) { pageGapsMillis[gapIndex++ % pageGapsMillis.size] }

    /** Called with the lock held, on `95 00 00`: the live mode's next frame, or null (no live mode, or SpO₂ off a finger). */
    private fun livePollReply(): ByteArray? = when (liveMode) {
        LIVE_HEART_RATE -> if (!worn) {
            TestFrames.heartRateWarmUp
        } else {
            liveHeartRateFrames[minOf(liveFrameIndex++, liveHeartRateFrames.lastIndex)].copyOf()
        }
        LIVE_SPO2 -> if (worn) liveSpO2Frames[liveFrameIndex++ % liveSpO2Frames.size].copyOf() else null
        else -> null
    }

    /** Adds [what] to [events] from outside the ring (the store's "commit returned"), so both share one order. */
    fun note(what: String) = record(what)

    private fun record(what: String) = synchronized(this) { log += Event(now(), what) }

    companion object {
        /** The `0x82` answer that pages follow: `82 00 00 82` (PROTOCOL.md §3, 🟢). */
        val SYNC_ACK_PAGES: ByteArray = hex("82000082")

        /** The `0x82` answer of a channel whose pointer is at its end: `82 ff 00 7d` (PROTOCOL.md §3, 🟡). */
        val SYNC_ACK_EMPTY: ByteArray = hex("82ff007d")

        /**
         * A `0x50` end-of-history report in its 12-byte form, the real frame of upstream's
         * `EpochSyncTests.swift:58-60` @ b1c2fdd (`50 00 00 12 0c 22 aa e4 0c 22 ac b5`).
         */
        val END_OF_HISTORY: ByteArray = hex("500000120c22aae40c22acb5")

        /** The ring's answer to a mode write it accepts: `86 00 86` (PROTOCOL.md §4). */
        val MODE_ACCEPTED: ByteArray = hex("860086")

        /** An answer to a mode write the ring refuses: a non-zero status, `86 01 87` (PROTOCOL.md §4, PORTING.md D-255). */
        val MODE_REFUSED: ByteArray = hex("860187")

        private const val LIVE_HEART_RATE = 0x01
        private const val LIVE_SPO2 = 0x02

        /** The pages that wait for an acknowledgement, as on the real link: `0x40` (older Gen 2 firmware, ACK `c0`), `0x47`, `0x4c`, `0x4d`. */
        private val PAGE_OPCODES = setOf(0x40, 0x47, 0x4C, 0x4D)
    }
}
