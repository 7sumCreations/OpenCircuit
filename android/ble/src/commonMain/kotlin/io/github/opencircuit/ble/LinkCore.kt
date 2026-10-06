package io.github.opencircuit.ble

import io.github.opencircuit.ble.GattOp.Write.Purpose
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.FirmwareInfo
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import io.github.opencircuit.ringkit.RingAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import java.time.Duration
import kotlin.coroutines.ContinuationInterceptor

/**
 * The link's state machine. Every GATT callback, every API call, every timer of its own and
 * every Bluetooth adapter change becomes a [LinkEvent] in one unbounded channel, taken one at a
 * time by a single coroutine; only that coroutine touches the fields below, so they need no locks.
 *
 * Cold bring-up: connect → discover → MTU exchange → notifications enabled (the CCCD write
 * CONFIRMED) → Device Information reads → `01 00 00` → answer `81 00 <challenge>` with
 * `RingAuth.authCommand` → the first frame other than `0x81` means the ring's data path is open.
 * Auth starts only after the descriptor write is confirmed (PORTING.md D-183); every GATT
 * operation waits for the previous one's answer (PORTING.md D-184), for at most its own timeout
 * (PORTING.md D-185).
 *
 * Any failure (a timeout, an error status, a refused or throwing call, a drop) fails the
 * operation in flight, closes the connection and schedules the next attempt by address
 * (PORTING.md D-186, D-187). Each connection has its own [SessionToken]; a callback carrying any
 * other token changes nothing.
 */
internal class LinkCore(
    override val ring: RememberedRing,
    private val port: GattPort,
    private val scope: CoroutineScope,
) : RingLink {

    private val inbox = Channel<LinkEvent>(Channel.UNLIMITED) { undelivered ->
        if (undelivered is LinkEvent.Send) undelivered.reply.complete(SendResult.Failed(SendFailure.LINK_LOST))
    }
    private val loopDispatcher = loopDispatcherFor(scope)
    private val stateFlow = MutableStateFlow<LinkState>(LinkState.Idle)
    private val infoFlow = MutableStateFlow(LinkInfo())
    private val frameChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val teardownChannel = Channel<LinkTeardown>(Channel.UNLIMITED)
    private val sink = GattPort.EventSink { inbox.trySend(LinkEvent.Gatt(it)) }

    // Owned by the event loop.
    private val queue = OpQueue()
    private val timers = HashMap<LinkTimer, Pair<Any, Job>>()
    private var session: SessionToken? = null
    private var sessionsOpened = 0L
    private var discovered: Set<GattPort.Characteristic> = emptySet()
    private var mac: ByteArray? = null

    /** The user asked for a connection: `connect()` and no `disconnect()` since. */
    private var wanted = false
    private var adapterOn = true

    /** Reconnect attempts scheduled since the link last proved stable (upstream `reconnectAttempts`). */
    private var attempts = 0

    /** Whether the scheduled attempt is the standing connection. */
    private var nextIsStanding = false

    // Per connection, reset by open().
    private var standing = false
    private var connected = false
    private var frameSeen = false
    private var connectIsLate = false

    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val info: StateFlow<LinkInfo> = infoFlow.asStateFlow()
    override val frames: Flow<ByteArray> = frameChannel.consumeAsFlow()
    override val teardowns: Flow<LinkTeardown> = teardownChannel.consumeAsFlow()

    init {
        val loop = scope.launch(loopDispatcher) {
            for (event in inbox) handle(event)
        }
        loop.invokeOnCompletion {
            // The loop is gone: nothing else will answer a caller or close the connection.
            inbox.cancel()
            abandon()
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

    /** The phone's Bluetooth adapter changed state; called from any thread. */
    fun onAdapterState(state: AdapterState) {
        inbox.trySend(LinkEvent.AdapterChanged(state))
    }

    private fun handle(event: LinkEvent) {
        when (event) {
            LinkEvent.Connect -> onConnectAsked()
            LinkEvent.Disconnect -> onDisconnectAsked()
            is LinkEvent.Send -> sendFeature(event)
            is LinkEvent.Gatt -> onGatt(event.event)
            is LinkEvent.TimerFired -> onTimer(event)
            is LinkEvent.AdapterChanged -> onAdapter(event.state)
        }
        pump()
    }

    private fun onConnectAsked() {
        if (wanted) return // connecting, connected, or a reconnect is already scheduled
        wanted = true
        attempts = 0
        if (adapterOn) open(standingConnection = false) // else stays BluetoothOff until the adapter is on
    }

    private fun onDisconnectAsked() {
        wanted = false
        cancelTimer(LinkTimer.RECONNECT)
        closeSession(SendFailure.LINK_LOST, TeardownReason.USER_DISCONNECTED)
        stateFlow.value = LinkState.Idle // as RingLink.disconnect promises, whatever the adapter's state
    }

    private fun onAdapter(adapter: AdapterState) {
        when (adapter) {
            AdapterState.OFF, AdapterState.TURNING_OFF -> {
                if (!adapterOn) return
                adapterOn = false
                cancelTimer(LinkTimer.RECONNECT)
                closeSession(SendFailure.LINK_LOST, TeardownReason.LINK_DROPPED)
                stateFlow.value = LinkState.BluetoothOff
            }
            AdapterState.ON -> {
                if (adapterOn) return
                adapterOn = true
                if (wanted) open(standingConnection = false) else stateFlow.value = LinkState.Idle
            }
            AdapterState.TURNING_ON -> Unit // not usable until it is on
        }
    }

    private fun open(standingConnection: Boolean) {
        if (session != null) return
        session = SessionToken(++sessionsOpened)
        standing = standingConnection
        connected = false
        frameSeen = false
        connectIsLate = false
        discovered = emptySet()
        mac = macFromAddress(ring.address)
        // The model name is the ring's advertised name, as upstream RingSession.swift:930.
        infoFlow.value = LinkInfo(firmware = FirmwareInfo(modelName = ring.name.orEmpty()), mac = mac?.let(::formatMac))
        stateFlow.value = if (standingConnection) LinkState.WaitingForRing else LinkState.Connecting
        queue.add(GattOp.Connect(autoConnect = standingConnection))
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
                dropLink(SendFailure.GATT_ERROR)
                return
            }
            op.timeout?.let { startTimer(LinkTimer.OPERATION, it) }
            if (op is GattOp.Connect && !op.autoConnect) startTimer(LinkTimer.LATE_CONNECT, ReconnectPolicy.LATE_FAILURE_AFTER)
        }
    }

    private fun submit(op: GattOp): Boolean {
        val current = session ?: return false
        return failsClosed {
            when (op) {
                is GattOp.Connect -> port.connect(current, ring, op.autoConnect, sink)
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
    }

    private fun onGatt(event: GattEvent) {
        if (event.session !== session) return // a callback of a connection that is already closed
        val op = queue.inFlight
        if (op != null && op.isAnsweredBy(event)) {
            cancelTimer(LinkTimer.OPERATION)
            if (!succeeded(event)) {
                val failure = if (op is GattOp.Connect) {
                    ReconnectPolicy.classifyOpenFailure((event as GattEvent.ConnectionChanged).status, connectIsLate)
                } else {
                    ReconnectPolicy.Failure.FAILED
                }
                dropLink(SendFailure.GATT_ERROR, failure)
                return
            }
            queue.finish()
            onAnswered(op, event)
            return
        }
        when (event) {
            is GattEvent.Notification -> onNotification(event)
            is GattEvent.ConnectionChanged -> if (!event.connected) dropLink(SendFailure.LINK_LOST)
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
            is GattOp.Connect -> {
                connected = true
                cancelTimer(LinkTimer.LATE_CONNECT)
                startTimer(LinkTimer.STABILITY, ReconnectPolicy.STABLE_AFTER)
                stateFlow.value = LinkState.Discovering
                queue.add(GattOp.DiscoverServices)
            }
            GattOp.DiscoverServices -> {
                discovered = (event as GattEvent.ServicesDiscovered).characteristics
                if (GattPort.NOTIFY !in discovered || GattPort.WRITE !in discovered) {
                    dropLink(SendFailure.GATT_ERROR) // not the ring's GATT layout
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
        frameSeen = true // any frame, as upstream's lastFrameAt (RingSession.swift:4872)
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

    private fun onTimer(event: LinkEvent.TimerFired) {
        val running = timers[event.timer]
        if (running == null || running.first !== event.ticket) return // cancelled or replaced since
        timers.remove(event.timer)
        when (event.timer) {
            LinkTimer.OPERATION -> dropLink(SendFailure.TIMED_OUT)
            LinkTimer.LATE_CONNECT -> connectIsLate = true
            // Checked once, as upstream RingScanner.swift:977-986: a later frame does not reopen it.
            LinkTimer.STABILITY -> if (frameSeen) attempts = 0
            LinkTimer.RECONNECT -> open(standingConnection = nextIsStanding)
        }
    }

    /**
     * The connection failed or dropped: the operation in flight fails with [inFlight], the
     * connection is closed and the next attempt is scheduled by address.
     */
    private fun dropLink(inFlight: SendFailure, failure: ReconnectPolicy.Failure = ReconnectPolicy.Failure.FAILED) {
        if (session == null) return
        val ended = if (standing && !connected) ReconnectPolicy.Failure.STANDING_FAILED else failure
        closeSession(inFlight, TeardownReason.LINK_DROPPED)
        val plan = ReconnectPolicy.next(attempts, ended)
        attempts = plan.attempt
        nextIsStanding = plan.standing
        stateFlow.value = LinkState.Reconnecting(plan.attempt, plan.delay)
        startTimer(LinkTimer.RECONNECT, plan.delay)
    }

    /**
     * Ends the current connection: fails its operations (the one in flight with [inFlight], the
     * waiting ones with `LINK_LOST`), stops its timers, closes it and retires its token. No
     * callback follows a close, so nothing waits for one. A connection that had connected
     * publishes its teardown.
     */
    private fun closeSession(inFlight: SendFailure, reason: TeardownReason) {
        if (session == null) return
        val current = queue.inFlight
        queue.clear().forEach { op ->
            (op as? GattOp.Write)?.reply?.complete(SendResult.Failed(if (op === current) inFlight else SendFailure.LINK_LOST))
        }
        cancelTimer(LinkTimer.OPERATION)
        cancelTimer(LinkTimer.LATE_CONNECT)
        cancelTimer(LinkTimer.STABILITY)
        closePort()
        session = null
        // Frames are not dropped here: they stay queued for the one collector of `frames`.
        if (connected) teardownChannel.trySend(LinkTeardown(reason, undeliveredFrames = 0))
        connected = false
    }

    /** The loop ended (its scope was cancelled): close what is open and answer every waiting caller. */
    private fun abandon() {
        if (session == null) return
        closePort()
        session = null
        queue.clear().forEach { (it as? GattOp.Write)?.reply?.complete(SendResult.Failed(SendFailure.LINK_LOST)) }
    }

    private fun closePort() {
        // The connection is abandoned either way; a close that throws has nothing left to release.
        failsClosed {
            port.close()
            true
        }
    }

    private fun startTimer(timer: LinkTimer, after: Duration) {
        cancelTimer(timer)
        val ticket = Any()
        val job = scope.launch(loopDispatcher) {
            delay(after.toMillis())
            inbox.trySend(LinkEvent.TimerFired(timer, ticket))
        }
        timers[timer] = ticket to job
    }

    private fun cancelTimer(timer: LinkTimer) {
        timers.remove(timer)?.second?.cancel()
    }

    private companion object {
        /** Upstream reads these when the ring has them (RingSession.swift:4794-4813). */
        val DEVICE_INFORMATION_READS = listOf(
            GattPort.SYSTEM_ID, GattPort.FIRMWARE_REVISION, GattPort.MANUFACTURER_NAME, GattPort.HARDWARE_REVISION,
        )
    }
}

/**
 * Runs one call into the GATT port. Anything it throws (beyond what the Android adapter already
 * catches) counts as a refused call, so the operation fails closed instead of ending the event
 * loop (PORTING.md D-187). Errors, such as a test double's assertion, are not caught.
 */
private inline fun failsClosed(call: () -> Boolean): Boolean = try {
    call()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
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
