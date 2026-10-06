package io.github.opencircuit.app.demo

import io.github.opencircuit.ble.AddressType
import io.github.opencircuit.ble.LinkInfo
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.LinkTeardown
import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import kotlinx.coroutines.channels.Channel
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
 * It keeps the real link's contract: [frames] and [teardowns] each take one collection, [send]
 * never throws and refuses the auth commands the real link reserves (`01 00 00`, `01 01 …`).
 * [connect] goes straight to [LinkState.Authenticated] and sends one device-status descriptor
 * with a battery of 72 %; [disconnect] tears the connection down once and goes [LinkState.Idle].
 */
class DemoRingLink : RingLink {

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

    override suspend fun send(command: ByteArray): SendResult = when {
        isReservedAuthCommand(command) -> SendResult.Refused(RefusalReason.AUTH_COMMAND_RESERVED)
        stateFlow.value != LinkState.Authenticated -> SendResult.Refused(RefusalReason.NOT_AUTHENTICATED)
        else -> SendResult.Sent
    }

    @Synchronized
    override fun connect() {
        if (stateFlow.value != LinkState.Idle) return
        infoFlow.value = LinkInfo(bonded = true)
        stateFlow.value = LinkState.Authenticated
        frameChannel.trySend(demoDescriptor())
    }

    @Synchronized
    override fun disconnect() {
        if (stateFlow.value == LinkState.Idle) return
        stateFlow.value = LinkState.Idle
        infoFlow.value = LinkInfo()
        teardownChannel.trySend(LinkTeardown(TeardownReason.USER_DISCONNECTED, undeliveredFrames = 0))
    }

    private fun isReservedAuthCommand(command: ByteArray): Boolean =
        command.size >= 2 && command[0] == 0x01.toByte() && (command[1] == 0x00.toByte() || command[1] == 0x01.toByte())

    private companion object {
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
