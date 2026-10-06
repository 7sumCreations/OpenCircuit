package io.github.opencircuit.ble

import io.github.opencircuit.ble.GattOp.Write.Purpose
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.FirmwareInfo
import io.github.opencircuit.ringkit.RingAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.ContinuationInterceptor

/**
 * The link's state machine. Every GATT callback and every API call becomes a [LinkEvent] in one
 * unbounded channel, taken one at a time by a single coroutine; only that coroutine touches the
 * fields below, so they need no locks.
 *
 * Cold bring-up: connect → discover → MTU exchange → notifications enabled (the CCCD write
 * CONFIRMED) → Device Information reads → `01 00 00` → answer `81 00 <challenge>` with
 * `RingAuth.authCommand` → the first frame other than `0x81` means the ring's data path is open.
 * Auth starts only after the descriptor write is confirmed (PORTING.md D-183); every GATT
 * operation waits for the previous one's answer (PORTING.md D-184).
 */
internal class LinkCore(
    override val ring: RememberedRing,
    private val port: GattPort,
    scope: CoroutineScope,
) : RingLink {

    private val inbox = Channel<LinkEvent>(Channel.UNLIMITED) { undelivered ->
        if (undelivered is LinkEvent.Send) undelivered.reply.complete(SendResult.Failed(SendFailure.LINK_LOST))
    }
    private val stateFlow = MutableStateFlow<LinkState>(LinkState.Idle)
    private val infoFlow = MutableStateFlow(LinkInfo())
    private val frameChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val teardownChannel = Channel<LinkTeardown>(Channel.UNLIMITED)
    private val sink = GattPort.EventSink { inbox.trySend(LinkEvent.Gatt(it)) }

    // Owned by the event loop.
    private val queue = OpQueue()
    private var session: SessionToken? = null
    private var sessionsOpened = 0L
    private var discovered: Set<GattPort.Characteristic> = emptySet()
    private var mac: ByteArray? = null

    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val info: StateFlow<LinkInfo> = infoFlow.asStateFlow()
    override val frames: Flow<ByteArray> = frameChannel.consumeAsFlow()
    override val teardowns: Flow<LinkTeardown> = teardownChannel.consumeAsFlow()

    init {
        val loop = scope.launch(loopDispatcherFor(scope)) {
            for (event in inbox) handle(event)
        }
        loop.invokeOnCompletion {
            // The loop is gone: nothing else will answer a caller or close the connection.
            inbox.cancel()
            closeConnection()
        }
    }

    override suspend fun send(command: ByteArray): SendResult {
        val reply = CompletableDeferred<SendResult>()
        if (inbox.trySend(LinkEvent.Send(command.copyOf(), reply)).isFailure) {
            return SendResult.Failed(SendFailure.LINK_LOST)
        }
        return reply.await()
    }

    override fun connect() {
        inbox.trySend(LinkEvent.Connect)
    }

    override fun disconnect() {
        inbox.trySend(LinkEvent.Disconnect)
    }

    private fun handle(event: LinkEvent) {
        when (event) {
            LinkEvent.Connect -> open()
            LinkEvent.Disconnect -> {
                closeConnection()
                stateFlow.value = LinkState.Idle
            }
            is LinkEvent.Send -> sendFeature(event)
            is LinkEvent.Gatt -> onGatt(event.event)
        }
        pump()
    }

    private fun open() {
        if (session != null) return
        session = SessionToken(++sessionsOpened)
        discovered = emptySet()
        mac = macFromAddress(ring.address)
        // The model name is the ring's advertised name, as upstream RingSession.swift:930.
        infoFlow.value = LinkInfo(firmware = FirmwareInfo(modelName = ring.name.orEmpty()), mac = mac?.let(::formatMac))
        stateFlow.value = LinkState.Connecting
        queue.add(GattOp.Connect)
    }

    private fun sendFeature(event: LinkEvent.Send) {
        if (stateFlow.value != LinkState.Authenticated) {
            event.reply.complete(SendResult.Refused(RefusalReason.NOT_AUTHENTICATED))
            return
        }
        queue.add(GattOp.Write(event.command, Purpose.FEATURE, event.reply))
    }

    /** Submits waiting operations; the queue hands out the next one only when none is in flight. */
    private fun pump() {
        while (true) {
            val op = queue.startNext() ?: return
            if (!submit(op)) {
                fail(op)
                return
            }
        }
    }

    private fun submit(op: GattOp): Boolean {
        val current = session ?: return false
        return when (op) {
            GattOp.Connect -> port.connect(current, ring, autoConnect = false, events = sink)
            GattOp.DiscoverServices -> port.discoverServices()
            GattOp.RequestMtu -> port.requestMtu(GattPort.REQUESTED_MTU)
            GattOp.EnableNotifications ->
                port.setNotifications(GattPort.NOTIFY, enabled = true) &&
                    port.writeDescriptor(GattPort.NOTIFY, GattPort.CCCD, byteArrayOf(0x01, 0x00))
            is GattOp.Read -> port.read(op.characteristic)
            is GattOp.Write -> {
                if (op.purpose == Purpose.AUTH_START) stateFlow.value = LinkState.Authenticating
                port.write(GattPort.WRITE, op.value)
            }
        }
    }

    private fun onGatt(event: GattEvent) {
        if (event.session !== session) return // a callback of a connection that is already closed
        val op = queue.inFlight
        if (op != null && op.isAnsweredBy(event)) {
            queue.finish()
            if (succeeded(event)) onAnswered(op, event) else fail(op)
            return
        }
        when (event) {
            is GattEvent.Notification -> onNotification(event)
            is GattEvent.ConnectionChanged -> if (!event.connected) fail(null)
            else -> Unit // an answer no operation is waiting for
        }
    }

    private fun succeeded(event: GattEvent): Boolean = when (event) {
        is GattEvent.ConnectionChanged -> event.connected && event.status == GattPort.GATT_SUCCESS
        is GattEvent.ServicesDiscovered -> event.status == GattPort.GATT_SUCCESS
        is GattEvent.MtuChanged -> event.status == GattPort.GATT_SUCCESS
        is GattEvent.DescriptorWritten -> event.status == GattPort.GATT_SUCCESS
        is GattEvent.CharacteristicRead -> event.status == GattPort.GATT_SUCCESS
        is GattEvent.CharacteristicWritten -> event.status == GattPort.GATT_SUCCESS
        is GattEvent.Notification -> true
    }

    private fun onAnswered(op: GattOp, event: GattEvent) {
        when (op) {
            GattOp.Connect -> {
                stateFlow.value = LinkState.Discovering
                queue.add(GattOp.DiscoverServices)
            }
            GattOp.DiscoverServices -> {
                discovered = (event as GattEvent.ServicesDiscovered).characteristics
                if (GattPort.NOTIFY !in discovered || GattPort.WRITE !in discovered) {
                    fail(null) // not the ring's GATT layout
                    return
                }
                stateFlow.value = LinkState.Preparing
                queue.add(GattOp.RequestMtu)
            }
            GattOp.RequestMtu -> {
                val mtu = (event as GattEvent.MtuChanged).mtu
                infoFlow.update { it.copy(attMtu = mtu) }
                queue.add(GattOp.EnableNotifications)
            }
            GattOp.EnableNotifications -> {
                // Notifications are confirmed on: only now can the challenge be received (D-183).
                DEVICE_INFORMATION_READS.filter { it in discovered }.forEach { queue.add(GattOp.Read(it)) }
                queue.add(GattOp.Write(Command.status0, Purpose.AUTH_START))
            }
            is GattOp.Read -> applyDeviceInformation(op.characteristic, (event as GattEvent.CharacteristicRead).value)
            is GattOp.Write -> op.reply?.complete(SendResult.Sent)
        }
    }

    private fun onNotification(event: GattEvent.Notification) {
        if (event.characteristic != GattPort.NOTIFY) return
        val frame = event.value
        if (frame.isEmpty()) return
        if (frame.size >= 3 && frame.u8(0) == 0x81 && frame.u8(1) == 0x00) {
            answerChallenge(frame.u8(2)) // the link's own exchange: never delivered
            return
        }
        if (frame.u8(0) != 0x81 && stateFlow.value == LinkState.Authenticating) {
            stateFlow.value = LinkState.Authenticated
        }
        frameChannel.trySend(frame)
    }

    private fun answerChallenge(challenge: Int) {
        val key = mac ?: return
        queue.add(GattOp.Write(RingAuth.authCommand(challenge, key), Purpose.AUTH_REPLY))
    }

    private fun applyDeviceInformation(characteristic: GattPort.Characteristic, value: ByteArray) {
        when (characteristic) {
            GattPort.SYSTEM_ID -> RingAuth.macFromSystemID(value)?.let { fromSystemId ->
                mac = fromSystemId
                val text = formatMac(fromSystemId)
                infoFlow.update { it.copy(mac = text, firmware = it.firmware.copy(mac = text)) }
            }
            GattPort.FIRMWARE_REVISION -> strictUtf8(value)?.let { v -> infoFlow.update { it.copy(firmware = it.firmware.copy(version = v)) } }
            GattPort.MANUFACTURER_NAME -> strictUtf8(value)?.let { v -> infoFlow.update { it.copy(firmware = it.firmware.copy(manufacturer = v)) } }
            GattPort.HARDWARE_REVISION -> strictUtf8(value)?.let { v -> infoFlow.update { it.copy(firmware = it.firmware.copy(hardwareRevision = v)) } }
        }
    }

    /** A failed operation closes the connection; a waiting feature write learns why. */
    private fun fail(op: GattOp?) {
        (op as? GattOp.Write)?.reply?.complete(SendResult.Failed(SendFailure.GATT_ERROR))
        closeConnection()
        stateFlow.value = LinkState.Idle
    }

    /** Closes the GATT connection (no callback follows) and abandons every waiting operation. */
    private fun closeConnection() {
        if (session == null) return
        port.close()
        session = null
        queue.clear().forEach { (it as? GattOp.Write)?.reply?.complete(SendResult.Failed(SendFailure.LINK_LOST)) }
    }

    private companion object {
        /** Upstream reads these when the ring has them (RingSession.swift:4794-4813). */
        val DEVICE_INFORMATION_READS = listOf(
            GattPort.SYSTEM_ID, GattPort.FIRMWARE_REVISION, GattPort.MANUFACTURER_NAME, GattPort.HARDWARE_REVISION,
        )
    }
}

/**
 * The dispatcher the event loop runs on: the scope's own dispatcher (the test scheduler in tests,
 * so timers follow virtual time), limited to one task at a time.
 */
internal fun loopDispatcherFor(scope: CoroutineScope): CoroutineDispatcher {
    val base = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
    return base.limitedParallelism(1)
}

/** `AA:BB:CC:DD:EE:FF` → 6 bytes; null for anything else (ASCII hex digits only; PORTING.md D-11). */
internal fun macFromAddress(address: String): ByteArray? {
    val parts = address.split(':')
    if (parts.size != 6) return null
    val out = ByteArray(6)
    for ((i, part) in parts.withIndex()) {
        if (part.length != 2) return null
        val high = hexDigit(part[0])
        val low = hexDigit(part[1])
        if (high < 0 || low < 0) return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** 6 bytes → `AA:BB:CC:DD:EE:FF` (upper case, as upstream RingSession.swift:4838). */
internal fun formatMac(mac: ByteArray): String =
    mac.joinToString(":") { b -> "${UPPER_HEX[(b.toInt() shr 4) and 0xF]}${UPPER_HEX[b.toInt() and 0xF]}" }

private const val UPPER_HEX = "0123456789ABCDEF"

/** The bytes as UTF-8, or null when they are not valid UTF-8 (upstream `String(bytes:encoding:.utf8)`). */
private fun strictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
