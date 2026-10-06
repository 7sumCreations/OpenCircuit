package io.github.opencircuit.app.demo

import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.LinkInfo
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.SendResult
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
 * goes [LinkState.Idle].
 *
 * It answers the live measure like a worn ring: after `06 01 00` / `06 02 00` and `07 00 00`,
 * each `95 00 00` poll gets one `0x15` frame. Heart rate sends the warm-up value 8 for the first
 * two polls after an entry, then made-up resting values; SpO₂ sends one frame without a valid
 * reading, then made-up values in the high 90s. A poll before any mode was chosen gets nothing.
 */
class DemoRingLink(private val scope: CoroutineScope) : RingLink {

    /** A placeholder address that names no real device. */
    override val ring = RememberedRing("AA:BB:CC:DD:EE:00", AddressType.RANDOM, "Demo ring")

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

    override suspend fun send(command: ByteArray): SendResult = when {
        isReservedAuthCommand(command) -> SendResult.Refused(RefusalReason.AUTH_COMMAND_RESERVED)
        stateFlow.value != LinkState.Authenticated -> SendResult.Refused(RefusalReason.NOT_AUTHENTICATED)
        else -> {
            answerLive(command)
            SendResult.Sent
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
            opcode == 0x07 -> pollsSinceEntry = 0
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
        if (stateFlow.value != LinkState.Idle) return
        infoFlow.value = LinkInfo(bonded = true)
        stateFlow.value = LinkState.Authenticated
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
        stateFlow.value = LinkState.Idle
        infoFlow.value = LinkInfo()
        teardownChannel.trySend(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 0))
    }

    private fun isReservedAuthCommand(command: ByteArray): Boolean =
        command.size >= 2 && command[0] == 0x01.toByte() && (command[1] == 0x00.toByte() || command[1] == 0x01.toByte())

    private companion object {
        /** How often the demo sends its descriptor unasked while connected. */
        const val DESCRIPTOR_EVERY_MILLIS = 30_000L

        const val HEART_RATE_MODE = 0x01
        const val SPO2_MODE = 0x02

        /** Polls answered with the warm-up sentinel after each entry (PROTOCOL.md §5.1). */
        const val WARM_UP_POLLS = 2
        const val WARM_UP_VALUE = 8

        /** Made-up resting heart rates, cycled. */
        val DEMO_HEART_RATES = intArrayOf(64, 66, 65, 68, 63, 62, 64, 67, 65, 61)

        /** Made-up SpO₂ values, cycled. */
        val DEMO_SPO2 = intArrayOf(97, 96, 97, 98, 97)

        /**
         * A made-up `0x10` descriptor in the layout of PROTOCOL.md §5.4: battery 72 % (`[1]`),
         * worn and idle (`[2]` = 0x02), skin temperature 30.0 °C on both channels, 4000 mV,
         * not in the charging case (`[17]` = 0xff).
         */
        fun demoDescriptor(): ByteArray = byteArrayOf(
            0x10, 0x48, 0x02, 0x00, 0x00, 0x00, 0x01, 0x2c, 0x01, 0x2c,
            0x00, 0x00, 0x00, 0x00, 0x0f, 0xa0.toByte(), 0x00, 0xff.toByte(), 0x00,
        )
    }
}
