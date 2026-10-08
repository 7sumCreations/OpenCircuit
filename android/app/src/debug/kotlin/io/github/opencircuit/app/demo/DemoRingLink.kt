package io.github.opencircuit.app.demo

import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.LinkDiagnostic
import io.github.opencircuit.ble.LinkDiagnostics
import io.github.opencircuit.ble.LinkInfo
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.Frame
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * A pretend ring for debug builds: the emulator has no Bluetooth ring, so the app's screens and
 * the on-device tests run against this link instead. It exists only in the debug source set;
 * release builds do not contain it.
 *
 * It keeps the real link's contract where the app relies on it: [send] never throws and refuses
 * the auth commands the real link reserves (`01 00 00`, `01 01 …`), and a second collection of
 * [frames] or [teardowns] fails. It is stricter than the real link in one way the app never
 * meets: each flow takes one collection for the demo's lifetime (the real link lets a new
 * collector take over after one stops), and the app's session collects each flow once.
 * [connect] goes straight to [LinkState.Authenticated] and sends one device-status descriptor
 * with a battery of 72 %, then another every 30 s in [scope] and one in answer to each
 * `d0 00 00`, as the ring does; [disconnect] stops them, tears the connection down once and
 * goes [LinkState.Idle]. [close] does the same and retires the link, as the real link's does.
 *
 * It answers the live measure like a worn ring: after `06 01 00` / `06 02 00` and `07 00 00`,
 * each `95 00 00` poll gets one `0x15` frame. Heart rate sends the warm-up value 8 for the first
 * two polls after an entry, then made-up resting values; SpO₂ sends one frame without a valid
 * reading, then made-up values in the high 90s. A poll before any mode was chosen gets nothing.
 *
 * It answers a history sync like a ring: after `02 00 <cursor> <channel> 01 00` and `07 00 00`
 * it sends `82 00 00 82`, then three `0x4c` pages of made-up records ending at [wallClock], each
 * only once the previous one is acknowledged ([acknowledge]), then a `0x50` end report. A page
 * sent and not acknowledged is offered again by the next sync; once drained, the demo has
 * nothing new for later syncs.
 */
class DemoRingLink(
    private val scope: CoroutineScope,
    /** The phone's wall clock in milliseconds: the demo's history records end at it. */
    private val wallClock: () -> Long = System::currentTimeMillis,
) : RingLink, AutoCloseable, LinkDiagnostics {

    override val ring = RING

    private val diagnosticsFlow = MutableStateFlow<List<LinkDiagnostic>>(emptyList())

    /** What the demo "did", in the real link's words: connect, Authenticated, disconnected, closed. */
    override val diagnostics: StateFlow<List<LinkDiagnostic>> = diagnosticsFlow.asStateFlow()

    private fun note(event: String, detail: String = "") {
        diagnosticsFlow.value = (diagnosticsFlow.value + LinkDiagnostic(0, event, detail)).takeLast(DIAGNOSTICS_KEPT)
    }

    // Set by close(): like the real link, a closed demo link never connects again.
    private var closed = false

    private val stateFlow = MutableStateFlow<LinkState>(LinkState.Idle)
    private val infoFlow = MutableStateFlow(LinkInfo())
    private val frameChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val teardownChannel = Channel<LinkTeardown>(Channel.UNLIMITED)

    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val info: StateFlow<LinkInfo> = infoFlow.asStateFlow()
    override val frames: Flow<ByteArray> = frameChannel.consumeAsFlow()
    override val teardowns: Flow<LinkTeardown> = teardownChannel.consumeAsFlow()

    // The live measure the demo is answering, and the descriptor timer. Guarded by `this`.
    private var descriptorJob: Job? = null
    private var liveMode: Int? = null
    private var pollsSinceEntry = 0
    private var valueIndex = 0

    // History pages sent on this connection and not acknowledged yet. Guarded by `this`.
    private val pendingPages = ArrayList<ByteArray>()

    // The demo's history: made at the first sync, drained page by page, never refilled (a second
    // sync finds nothing new). A sync open waiting for its `07 00 00`, and the running sync. Guarded by `this`.
    private var backlog: ArrayDeque<ByteArray>? = null
    private var syncOpened = false
    private var syncJob: Job? = null

    override suspend fun send(command: ByteArray): SendResult = when {
        isReservedAuthCommand(command) -> SendResult.Refused(RefusalReason.AUTH_COMMAND_RESERVED)
        stateFlow.value != LinkState.Authenticated -> SendResult.Refused(RefusalReason.NOT_AUTHENTICATED)
        else -> {
            answerLive(command)
            SendResult.Sent
        }
    }

    /**
     * As the real link: a frame that is not a history page is refused with `NOT_A_PAGE`; a page
     * this connection did not send, or already acknowledged, with `PAGE_NOT_PENDING`.
     */
    override suspend fun acknowledge(page: ByteArray): SendResult = synchronized(this) {
        val opcode = if (page.isEmpty()) -1 else page[0].toInt() and 0xFF
        if (opcode !in PAGE_OPCODES) return SendResult.Refused(RefusalReason.NOT_A_PAGE)
        val index = pendingPages.indexOfFirst { it.contentEquals(page) }
        if (index < 0) return SendResult.Refused(RefusalReason.PAGE_NOT_PENDING)
        pendingPages.removeAt(index)
        // The ring drops an acknowledged page and sends its next one.
        val drained = backlog
        if (drained != null && drained.firstOrNull()?.contentEquals(page) == true) {
            drained.removeFirst()
            syncJob = scope.launch { sendNextPage() }
        }
        SendResult.Sent
    }

    /** As the real link: the demo runs no auth, so a re-auth on a connected demo ring simply succeeds. */
    override suspend fun reauthenticate(): SendResult =
        if (stateFlow.value == LinkState.Authenticated) SendResult.Sent else SendResult.Refused(RefusalReason.NOT_AUTHENTICATED)

    /** `07 00 00` after a sync open: answer `82 00 00 82`, then the first page still held. With the lock held. */
    private fun startSync() {
        if (backlog == null) backlog = ArrayDeque(demoBacklog(wallClock()))
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(SYNC_ANSWER_MILLIS)
            frameChannel.trySend(SYNC_ACK)
            sendNextPage()
        }
    }

    /** After a page gap: the next page held (it waits for its acknowledgement), or the end report when none is left. */
    private suspend fun sendNextPage() {
        delay(PAGE_GAP_MILLIS)
        synchronized(this) {
            if (stateFlow.value != LinkState.Authenticated) return
            val next = backlog?.firstOrNull()
            if (next == null) {
                frameChannel.trySend(END_OF_HISTORY)
            } else {
                pendingPages += next.copyOf()
                frameChannel.trySend(next.copyOf())
            }
        }
    }

    @Synchronized
    private fun answerLive(command: ByteArray) {
        if (command.size < 2) return
        val opcode = command[0].toInt() and 0xFF
        val sub = command[1].toInt() and 0xFF
        when {
            // The ring answers the status query with its descriptor (PROTOCOL.md §5.4).
            opcode == 0xd0 -> frameChannel.trySend(demoDescriptor())
            opcode == 0x06 && (sub == HEART_RATE_MODE || sub == SPO2_MODE) -> liveMode = sub
            // A sync open `02 00 <cursor> <channel> 01 00`; the ring answers after the `07 00 00`.
            opcode == 0x02 && command.size == SYNC_OPEN_LENGTH -> syncOpened = true
            opcode == 0x07 -> {
                pollsSinceEntry = 0
                if (syncOpened) {
                    syncOpened = false
                    startSync()
                }
            }
            opcode == 0x95 -> liveMode?.let { mode ->
                frameChannel.trySend(if (mode == HEART_RATE_MODE) nextHeartRateFrame() else nextSpO2Frame())
                pollsSinceEntry++
            }
        }
    }

    private fun nextHeartRateFrame(): ByteArray {
        val bpm = if (pollsSinceEntry < WARM_UP_POLLS) WARM_UP_VALUE else DEMO_HEART_RATES[valueIndex++ % DEMO_HEART_RATES.size]
        return withTrailer(byteArrayOf(0x15, 0x00, bpm.toByte(), 0x0a, 0xb0.toByte()))
    }

    private fun nextSpO2Frame(): ByteArray {
        // Byte 14 carries the value; 0 is outside 70…100, i.e. no reading yet.
        val spo2 = if (pollsSinceEntry < 1) 0 else DEMO_SPO2[valueIndex++ % DEMO_SPO2.size]
        val body = ByteArray(16)
        body[0] = 0x15
        body[1] = 0x01
        body[14] = spo2.toByte()
        return withTrailer(body)
    }

    private fun withTrailer(body: ByteArray): ByteArray = body + Frame.xorTrailer(body).toByte()

    @Synchronized
    override fun connect() {
        if (closed || stateFlow.value != LinkState.Idle) return
        note("connect started")
        infoFlow.value = LinkInfo(bonded = true)
        stateFlow.value = LinkState.Authenticated
        note("Authenticated", "demo ring")
        frameChannel.trySend(demoDescriptor())
        // The ring also sends its descriptor on its own every 30–60 s (PROTOCOL.md §5.4).
        descriptorJob = scope.launch {
            while (true) {
                delay(DESCRIPTOR_EVERY_MILLIS)
                frameChannel.trySend(demoDescriptor())
            }
        }
    }

    @Synchronized
    override fun disconnect() {
        if (stateFlow.value == LinkState.Idle) return
        descriptorJob?.cancel()
        descriptorJob = null
        // A page sent and not acknowledged stays the backlog's first: the next sync offers it again.
        syncJob?.cancel()
        syncJob = null
        syncOpened = false
        // A new connection starts with no live mode chosen, as a real ring's does.
        liveMode = null
        pollsSinceEntry = 0
        stateFlow.value = LinkState.Idle
        infoFlow.value = LinkInfo()
        val unacknowledged = pendingPages.size
        pendingPages.clear()
        teardownChannel.trySend(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 0, pagesUnacknowledged = unacknowledged))
        note("disconnected", "by the user")
    }

    /** Ends the demo link for good: disconnects (one user-disconnected teardown if connected) and never connects again. */
    @Synchronized
    override fun close() {
        disconnect()
        closed = true
        note("closed")
    }

    private fun isReservedAuthCommand(command: ByteArray): Boolean =
        command.size >= 2 && command[0] == 0x01.toByte() && (command[1] == 0x00.toByte() || command[1] == 0x01.toByte())

    companion object {
        /** The demo ring: a placeholder address that names no real device. */
        val RING = RememberedRing("AA:BB:CC:DD:EE:00", AddressType.RANDOM, "Demo ring")

        /** How often the demo sends its descriptor unasked while connected. */
        private const val DESCRIPTOR_EVERY_MILLIS = 30_000L

        /** As many diagnostics as the real link keeps. */
        private const val DIAGNOSTICS_KEPT = 64

        private const val HEART_RATE_MODE = 0x01
        private const val SPO2_MODE = 0x02

        /** The history pages the ring waits on an acknowledgement for. */
        private val PAGE_OPCODES = setOf(0x47, 0x4C, 0x4D)

        /** `02 00 <cursor, 4 bytes> <channel> 01 00`. */
        private const val SYNC_OPEN_LENGTH = 9

        /** From `07 00 00` to the `0x82` answer, and between pages. */
        private const val SYNC_ANSWER_MILLIS = 200L
        private const val PAGE_GAP_MILLIS = 800L

        /** The ring's answer to a sync open that pages follow (PROTOCOL.md §3). */
        private val SYNC_ACK = byteArrayOf(0x82.toByte(), 0x00, 0x00, 0x82.toByte())

        /** The end-of-history report's 12-byte form (PROTOCOL.md §5.5.1); its cursors are not read by the app. */
        private val END_OF_HISTORY = byteArrayOf(0x50, 0x00, 0x00, 0x12, 0x0c, 0x22, 0xaa.toByte(), 0xe4.toByte(), 0x0c, 0x22, 0xac.toByte(), 0xb5.toByte())

        private const val PAGES = 3
        private const val RECORDS_PER_PAGE = 6
        private const val EPOCH_SECONDS = 150L

        /**
         * The body (bytes after the four-byte counter) of the six records of upstream's public
         * overnight test page (`RingKitVerify/main.swift:305-309` @ b1c2fdd): a well-formed shape
         * for the demo's made-up history, given new counters.
         */
        private val RECORD_BODIES: List<String> = listOf(
            "55210a7d120a01010101010000040240040000",
            "55000300120a010101010100003c00000d0120",
            "540001005f0a010101010100001101b00f0044",
            "6027077b120a010101010100402501c02235a0",
            "51260577120b010101010108a0100000040130",
            "502d0378120a01010101010160200000040ff0",
        )

        /**
         * Three `0x4c` pages of six records each, one epoch (150 s) apart, the last at [nowMillis]
         * rounded down to an epoch, oldest first; each with its countdown and XOR trailer.
         */
        private fun demoBacklog(nowMillis: Long): List<ByteArray> {
            val last = (nowMillis / 1_000 - Command.SYNC_EPOCH) / EPOCH_SECONDS * EPOCH_SECONDS
            val first = last - (PAGES * RECORDS_PER_PAGE - 1) * EPOCH_SECONDS
            return (0 until PAGES).map { page ->
                val queuedAfter = (PAGES - 1 - page) * RECORDS_PER_PAGE
                val body = ArrayList<Byte>()
                body += 0x4C.toByte()
                body += ((queuedAfter ushr 8) and 0xFF).toByte()
                body += (queuedAfter and 0xFF).toByte()
                for (r in 0 until RECORDS_PER_PAGE) {
                    val counter = first + (page * RECORDS_PER_PAGE + r) * EPOCH_SECONDS
                    body += listOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())
                    body += RECORD_BODIES[r].chunked(2).map { it.toInt(16).toByte() }
                }
                val bytes = body.toByteArray()
                bytes + Frame.xorTrailer(bytes).toByte()
            }
        }

        /** Polls answered with the warm-up sentinel after each entry (PROTOCOL.md §5.1). */
        private const val WARM_UP_POLLS = 2
        private const val WARM_UP_VALUE = 8

        /** Made-up resting heart rates, cycled. */
        private val DEMO_HEART_RATES = intArrayOf(64, 66, 65, 68, 63, 62, 64, 67, 65, 61)

        /** Made-up SpO₂ values, cycled. */
        private val DEMO_SPO2 = intArrayOf(97, 96, 97, 98, 97)

        /**
         * A made-up `0x10` descriptor in the layout of PROTOCOL.md §5.4: battery 72 % (`[1]`),
         * worn and idle (`[2]` = 0x02), skin temperature 30.0 °C on both channels, 4000 mV,
         * not in the charging case (`[17]` = 0xff).
         */
        private fun demoDescriptor(): ByteArray = byteArrayOf(
            0x10, 0x48, 0x02, 0x00, 0x00, 0x00, 0x01, 0x2c, 0x01, 0x2c,
            0x00, 0x00, 0x00, 0x00, 0x0f, 0xa0.toByte(), 0x00, 0xff.toByte(), 0x00,
        )
    }
}
