package io.github.opencircuit.ble

import io.github.opencircuit.ble.GattOp.Write.Purpose
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.FirmwareInfo
import io.github.opencircuit.ringkit.HistoryDrainPlan.TeardownReason
import io.github.opencircuit.ringkit.Opcode
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Duration
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.TimeSource

/**
 * The link's state machine. Every GATT callback, every API call, every timer of its own and
 * every Bluetooth adapter change becomes a [LinkEvent] in one unbounded channel, taken one at a
 * time by a single coroutine; only that coroutine touches the fields below, so they need no locks.
 *
 * Cold bring-up: connect → discover → the bond (PORTING.md D-193) → MTU exchange → notifications
 * enabled (the CCCD write CONFIRMED) → Device Information reads → `01 00 00` → answer
 * `81 00 <challenge>` with `RingAuth.authCommand` → the first frame other than `0x81` means the
 * ring's data path is open.
 * Auth starts only after the descriptor write is confirmed (PORTING.md D-183); every GATT
 * operation waits for the previous one's answer (PORTING.md D-184), for at most its own timeout
 * (PORTING.md D-185).
 *
 * Once data flows: every `0x47` / `0x4c` / `0x4d` page and `0x11` heartbeat is acknowledged once,
 * in arrival order, on the link lane, ahead of any waiting feature write (PORTING.md D-190);
 * every `81 00` challenge is answered, held until the MAC is settled (PORTING.md D-188); every
 * frame but that challenge waits for the one collector of [frames] in a per-connection buffer
 * whose leftovers a teardown counts (PORTING.md D-189); and [send] refuses what the ring must not get
 * (PORTING.md D-191).
 *
 * Any failure (a timeout, an error status, a refused or throwing call, a drop) fails the
 * operation in flight, closes the connection and schedules the next attempt by address
 * (PORTING.md D-186, D-187). Each connection has its own [SessionToken]; a callback carrying any
 * other token changes nothing. An exception thrown while an event is handled is treated as such a
 * failure, and the loop goes on with the next event. A `send` caller cancelled while its write
 * still waits takes the write with it: it is never written.
 *
 * Every bring-up step, bond change, failure and close is also noted in [diagnostics] (the last
 * 64, never an address, a MAC or frame bytes; PORTING.md D-196).
 */
internal class LinkCore(
    override val ring: RememberedRing,
    private val port: GattPort,
    private val scope: CoroutineScope,
    /** The clock of [diagnostics]: the scheduler's virtual time in tests. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : RingLink, LinkDiagnostics {

    private val inbox = Channel<LinkEvent>(Channel.UNLIMITED) { undelivered ->
        if (undelivered is LinkEvent.Send) undelivered.reply.complete(SendResult.Failed(SendFailure.LINK_LOST))
    }
    private val loopDispatcher = loopDispatcherFor(scope)
    private val stateFlow = MutableStateFlow<LinkState>(LinkState.Idle)
    private val infoFlow = MutableStateFlow(LinkInfo())
    private val frameBuffer = SingleCollectorBuffer<ByteArray>("frames")
    private val teardownBuffer = SingleCollectorBuffer<LinkTeardown>("teardowns")
    private val sink = GattPort.EventSink { inbox.trySend(LinkEvent.Gatt(it)) }

    // Owned by the event loop.
    private val queue = OpQueue()
    private val timers = HashMap<LinkTimer, Pair<Any, Job>>()
    private var session: SessionToken? = null
    private var sessionsOpened = 0L
    private var discovered: Set<GattPort.Characteristic> = emptySet()

    /** The MAC the device address names, if it is six pairs of hex digits (PORTING.md D-11). */
    private var addressMac: ByteArray? = null

    /** The MAC auth uses; set once settled: the System ID's when the ring has one, else the address's. */
    private var mac: ByteArray? = null
    private var macSettled = false

    /** Challenges that arrived before the MAC was settled, in arrival order (PORTING.md D-188). */
    private val heldChallenges = mutableListOf<Int>()

    /** The user asked for a connection: `connect()` and no `disconnect()` since. */
    private var wanted = false
    private var adapterOn = true

    /** Reconnect attempts scheduled since the link last proved stable (upstream `reconnectAttempts`). */
    private var attempts = 0

    /** Whether the scheduled attempt is the standing connection. */
    private var nextIsStanding = false

    /**
     * Connections in a row to a bonded ring that Android disconnected early (before discovery was
     * done or within [EARLY_DROP_WINDOW] of connecting), across connections; a healthy one resets it.
     */
    private var earlyDrops = 0

    // Per connection, reset by open().
    private var standing = false
    private var connected = false
    private var frameSeen = false
    private var connectIsLate = false

    /** Discovery is done and the bring-up waits for the bond before the MTU exchange. */
    private var awaitingBond = false

    /** While waiting: the bond has started ("bonding" was seen), so a "not bonded" now means it failed. */
    private var bondStarted = false

    /** `01 00 00` was written on this connection. */
    private var authStarted = false

    /** This connection's service discovery was answered. */
    private var discoveryDone = false

    /** This connection has been up [EARLY_DROP_WINDOW]. */
    private var pastEarlyWindow = false

    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val info: StateFlow<LinkInfo> = infoFlow.asStateFlow()
    override val frames: Flow<ByteArray> = frameBuffer.flow
    override val teardowns: Flow<LinkTeardown> = teardownBuffer.flow
    private val diagnosticLog = DiagnosticLog()
    override val diagnostics: StateFlow<List<LinkDiagnostic>> = diagnosticLog.flow

    /** When the current connection attempt started: the zero of every diagnostic's time. */
    private var attemptStart = timeSource.markNow()

    /** The bond state last read or announced, for the diagnostics' "before → after". */
    private var lastBond: GattPort.BondState? = null

    /** This connection asked Android for the bond (`createBond`). */
    private var bondRequested = false

    init {
        val loop = scope.launch(loopDispatcher) {
            for (event in inbox) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recoverFrom(e)
                }
            }
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
        return try {
            reply.await()
        } catch (e: CancellationException) {
            // The caller is gone: a write still waiting in the queue is dropped, never written.
            reply.cancel()
            throw e
        }
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

    /** The phone's bond state with the ring changed; called from any thread. */
    fun onBondState(state: GattPort.BondState) {
        inbox.trySend(LinkEvent.BondChanged(state))
    }

    private fun handle(event: LinkEvent) {
        when (event) {
            LinkEvent.Connect -> onConnectAsked()
            LinkEvent.Disconnect -> onDisconnectAsked()
            is LinkEvent.Send -> sendFeature(event)
            is LinkEvent.Gatt -> onGatt(event.event)
            is LinkEvent.TimerFired -> onTimer(event)
            is LinkEvent.AdapterChanged -> onAdapter(event.state)
            is LinkEvent.BondChanged -> onBond(event.state)
        }
        pump()
    }

    /**
     * [fault] was thrown while an event was being handled: a defect, not a GATT answer. It counts
     * as a failure of the connection open at the time (the operation in flight fails with
     * `GATT_ERROR`, the waiting ones with `LINK_LOST`, the connection is closed and the next
     * attempt scheduled), and the loop goes on with the next event, so the link neither stops
     * answering nor takes the app down. The diagnostic names the exception's class only: its
     * message may hold anything. If the recovery throws too, the connection is closed without
     * diagnostics and the link stays `Idle` until `connect()` is asked again.
     */
    private fun recoverFrom(fault: Exception) {
        try {
            note("unexpected error", fault::class.simpleName.orEmpty())
            dropLink(SendFailure.GATT_ERROR)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            stopAfterFault()
        }
    }

    /** The last resort of [recoverFrom]: closes the connection without noting it and stops wanting one. */
    private fun stopAfterFault() {
        wanted = false
        timers.keys.toList().forEach(::cancelTimer)
        closeSession(SendFailure.GATT_ERROR, TeardownReason.LINK_DROPPED, noted = false)
        stateFlow.value = LinkState.Idle
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
        attemptStart = timeSource.markNow()
        session = SessionToken(++sessionsOpened)
        standing = standingConnection
        bondRequested = false
        connected = false
        frameSeen = false
        connectIsLate = false
        awaitingBond = false
        authStarted = false
        discoveryDone = false
        pastEarlyWindow = false
        discovered = emptySet()
        addressMac = macFromAddress(ring.address)
        mac = null
        macSettled = false
        heldChallenges.clear()
        // The model name is the ring's advertised name, as upstream RingSession.swift:930.
        infoFlow.value = LinkInfo(firmware = FirmwareInfo(modelName = ring.name.orEmpty()), mac = addressMac?.let(::formatMac))
        stateFlow.value = if (standingConnection) LinkState.WaitingForRing else LinkState.Connecting
        queue.add(GattOp.Connect(autoConnect = standingConnection))
    }

    private fun sendFeature(event: LinkEvent.Send) {
        val refusal = refusalOf(event.command, stateFlow.value == LinkState.Authenticated, infoFlow.value)
        if (refusal != null) {
            event.reply.complete(SendResult.Refused(refusal))
            return
        }
        queue.add(GattOp.Write(event.command, Purpose.FEATURE, event.reply))
    }

    /** Submits waiting operations; the queue hands out the next one only when none is in flight. */
    private fun pump() {
        while (true) {
            val op = queue.startNext() ?: return
            if (!submit(op)) {
                note("refused", op.stepName)
                dropLink(SendFailure.GATT_ERROR)
                return
            }
            if (op.isBringUpStep) note("${op.stepName} started", startDetail(op))
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
                    if (op.purpose == Purpose.AUTH_START) {
                        authStarted = true
                        // A ring that has not streamed for 10 s stays NotStreaming until it does.
                        if (stateFlow.value != LinkState.NotStreaming) stateFlow.value = LinkState.Authenticating
                    }
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
                note("failed", "${op.stepName}, status ${statusOf(event)}")
                val failure = if (op is GattOp.Connect) {
                    ReconnectPolicy.classifyOpenFailure((event as GattEvent.ConnectionChanged).status, connectIsLate)
                } else {
                    ReconnectPolicy.Failure.FAILED
                }
                dropLink(SendFailure.GATT_ERROR, failure)
                return
            }
            queue.finish()
            if (op.isBringUpStep) note("${op.stepName} finished", finishDetail(op, event))
            onAnswered(op, event)
            return
        }
        when (event) {
            is GattEvent.Notification -> onNotification(event)
            is GattEvent.ConnectionChanged -> if (!event.connected) {
                note("disconnected", "status ${event.status}")
                dropLink(SendFailure.LINK_LOST, disconnected = true)
            }
            else -> Unit // an answer no operation is waiting for
        }
    }

    /** Adds a diagnostic stamped with the time since the current connection attempt started. */
    private fun note(event: String, detail: String = "") {
        diagnosticLog.add(LinkDiagnostic(attemptStart.elapsedNow().inWholeMilliseconds, event, detail))
    }

    private fun startDetail(op: GattOp): String = when (op) {
        is GattOp.Connect -> if (op.autoConnect) "standing" else "direct"
        GattOp.RequestMtu -> "asked ${GattPort.REQUESTED_MTU}"
        is GattOp.Read -> op.characteristic.shortName
        else -> ""
    }

    private fun finishDetail(op: GattOp, event: GattEvent): String = when (op) {
        GattOp.DiscoverServices -> "${(event as GattEvent.ServicesDiscovered).characteristics.size} characteristics"
        GattOp.RequestMtu -> "granted ${(event as GattEvent.MtuChanged).mtu}"
        is GattOp.Read -> op.characteristic.shortName
        else -> ""
    }

    private fun statusOf(event: GattEvent): Int = when (event) {
        is GattEvent.ConnectionChanged -> event.status
        is GattEvent.ServicesDiscovered -> event.status
        is GattEvent.MtuChanged -> event.status
        is GattEvent.DescriptorWritten -> event.status
        is GattEvent.CharacteristicRead -> event.status
        is GattEvent.CharacteristicWritten -> event.status
        is GattEvent.Notification -> GattPort.GATT_SUCCESS
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
                startTimer(LinkTimer.EARLY_DROP, EARLY_DROP_WINDOW)
                stateFlow.value = LinkState.Discovering
                queue.add(GattOp.DiscoverServices)
            }
            GattOp.DiscoverServices -> {
                discoveryDone = true
                if (pastEarlyWindow) earlyDrops = 0 // a healthy connection
                discovered = (event as GattEvent.ServicesDiscovered).characteristics
                if (GattPort.NOTIFY !in discovered || GattPort.WRITE !in discovered) {
                    dropLink(SendFailure.GATT_ERROR) // not the ring's GATT layout
                    return
                }
                // With no System ID to read, the device address is the MAC.
                if (GattPort.SYSTEM_ID !in discovered) {
                    note("MAC source", if (addressMac != null) "device address" else "none")
                    settleMac(addressMac)
                }
                startBondStep()
            }
            GattOp.RequestMtu -> {
                val mtu = (event as GattEvent.MtuChanged).mtu
                infoFlow.update { it.copy(attMtu = mtu, historySafe = mtu >= HISTORY_SAFE_MTU) }
                queue.add(GattOp.EnableNotifications)
            }
            GattOp.EnableNotifications -> {
                // Notifications are confirmed on: only now can the challenge be received (D-183),
                // and only from now can the ring be expected to stream (PORTING.md D-194).
                startTimer(LinkTimer.NOT_STREAMING, NOT_STREAMING_AFTER)
                DEVICE_INFORMATION_READS.filter { it in discovered }.forEach { queue.add(GattOp.Read(it)) }
                queue.add(GattOp.Write(Command.status0, Purpose.AUTH_START))
            }
            is GattOp.Read -> applyDeviceInformation(op.characteristic, (event as GattEvent.CharacteristicRead).value)
            is GattOp.Write -> op.reply?.complete(SendResult.Sent)
        }
    }

    /**
     * The bond step, after discovery and before the MTU exchange: on the connection already open,
     * so pairing needs no second connection, and late enough to see a bond the ring started
     * itself before deciding to ask. Bonded: on with the bring-up. Being bonded already (the ring
     * asked to pair, or a bond left over): wait, never ask again, which would collide with the
     * bond in progress. Not bonded: ask once and wait.
     */
    private fun startBondStep() {
        val bond = readBondState()
        note("bond started", bond?.let { "read $it" } ?: "read failed")
        if (bond == null) return dropLink(SendFailure.GATT_ERROR)
        when (bond) {
            GattPort.BondState.BONDED -> continueAfterBond()
            GattPort.BondState.BONDING -> awaitBond(alreadyBonding = true)
            GattPort.BondState.NONE -> {
                bondRequested = true
                val accepted = failsClosed { port.createBond() }
                note("bond requested", if (accepted) "createBond accepted" else "createBond refused")
                if (!accepted) return pairingFailed(PairingFailure.BOND_REQUEST_REJECTED)
                awaitBond(alreadyBonding = false)
            }
        }
    }

    /**
     * Waits at most [LinkTimeouts.BOND] for the bond, showing `PairingNeeded`: Android is asking
     * the user to confirm the pairing. A "not bonded" counts as the end of the bond only once
     * the bond has started ([alreadyBonding], or a "bonding" since): before that it is a
     * broadcast left over from earlier.
     */
    private fun awaitBond(alreadyBonding: Boolean) {
        awaitingBond = true
        bondStarted = alreadyBonding
        infoFlow.update { it.copy(bonded = false) }
        stateFlow.value = LinkState.PairingNeeded
        startTimer(LinkTimer.BOND, LinkTimeouts.BOND)
    }

    private fun continueAfterBond() {
        note("bond finished", "bonded")
        awaitingBond = false
        cancelTimer(LinkTimer.BOND)
        infoFlow.update { it.copy(bonded = true) }
        stateFlow.value = LinkState.Preparing
        queue.add(GattOp.RequestMtu)
    }

    /**
     * A bond-state change. On a live connection it keeps `LinkInfo.bonded` current (a bond
     * removed in Settings refuses data again at once); while the bring-up waits, it ends the wait.
     */
    private fun onBond(state: GattPort.BondState) {
        val before = lastBond
        lastBond = state
        // Whether the ring started bonding before the link asked tells a ring Security Request apart.
        val asked = if (bondRequested) "after createBond" else "before createBond"
        note("bond state", "${before?.let { "$it → " }.orEmpty()}$state, $asked")
        if (session == null) return
        if (!awaitingBond) {
            infoFlow.update { it.copy(bonded = state == GattPort.BondState.BONDED) }
            return
        }
        when (state) {
            GattPort.BondState.BONDED -> continueAfterBond()
            GattPort.BondState.BONDING -> bondStarted = true
            GattPort.BondState.NONE -> if (bondStarted) pairingFailed(PairingFailure.BOND_NOT_COMPLETED)
        }
    }

    /**
     * The bond could not be made: the connection is closed and the link stays in `PairingFailed`
     * until `connect()` is called again. No retry of its own, which would put the same pairing
     * prompt in front of the user again.
     */
    private fun pairingFailed(reason: PairingFailure) {
        note("pairing failed", reason.name)
        wanted = false
        cancelTimer(LinkTimer.RECONNECT)
        closeSession(SendFailure.LINK_LOST, TeardownReason.LINK_DROPPED)
        stateFlow.value = LinkState.PairingFailed(reason)
    }

    /** The phone's bond state with the ring; null when it cannot be read (PORTING.md D-187). */
    private fun readBondState(): GattPort.BondState? = try {
        port.bondState().also { lastBond = it }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
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
        // Acknowledged by its opcode alone, whatever it holds (a page that fails its checksum
        // too): the ring waits for this before its next page (RingSession.swift:5130-5249).
        ackFor(frame.u8(0))?.let { queue.add(GattOp.Write(it, Purpose.ACK)) }
        if (frame.u8(0) != 0x81) onDataFrame()
        frameBuffer.add(frame)
    }

    /**
     * A frame other than `0x81`: the ring's data path is open. It ends the wait for data and
     * authenticates a link that wrote `01 00 00`, from `NotStreaming` too: a ring that starts to
     * stream late is streaming (upstream clears `notStreaming` on its first data frame,
     * RingSession.swift:4884-4888).
     */
    private fun onDataFrame() {
        cancelTimer(LinkTimer.NOT_STREAMING)
        val next = when (stateFlow.value) {
            LinkState.Authenticating -> LinkState.Authenticated
            LinkState.NotStreaming -> if (authStarted) LinkState.Authenticated else LinkState.Preparing
            else -> return
        }
        stateFlow.value = next
        note(if (next == LinkState.Authenticated) "Authenticated" else "streaming before auth")
    }

    /**
     * Answers [challenge] on the link lane, every time one arrives. Until the MAC is settled the
     * challenge waits (PORTING.md D-188); with no MAC at all it is never answered: no fixed or
     * guessed reply (PORTING.md D-192).
     */
    private fun answerChallenge(challenge: Int) {
        if (!macSettled) {
            heldChallenges += challenge
            return
        }
        val key = mac ?: return
        queue.add(GattOp.Write(RingAuth.authCommand(challenge, key), Purpose.AUTH_REPLY))
    }

    /** The MAC auth uses is now known (or known to be missing); answers the challenges that waited for it. */
    private fun settleMac(settled: ByteArray?) {
        mac = settled
        macSettled = true
        val waiting = heldChallenges.toList()
        heldChallenges.clear()
        waiting.forEach(::answerChallenge)
    }

    private fun applyDeviceInformation(characteristic: GattPort.Characteristic, value: ByteArray) {
        when (characteristic) {
            GattPort.SYSTEM_ID -> {
                // The System ID wins over the device address when they disagree.
                val fromSystemId = RingAuth.macFromSystemID(value)
                if (fromSystemId != null) {
                    val text = formatMac(fromSystemId)
                    val mismatch = addressMac?.contentEquals(fromSystemId) == false
                    infoFlow.update { it.copy(mac = text, macMismatch = mismatch, firmware = it.firmware.copy(mac = text)) }
                    note("MAC source", if (mismatch) "System ID, differs from the device address" else "System ID")
                } else {
                    note("MAC source", if (addressMac != null) "device address, System ID unreadable" else "none")
                }
                settleMac(fromSystemId ?: addressMac)
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
            LinkTimer.OPERATION -> {
                queue.inFlight?.let { note("timed out", it.stepName) }
                dropLink(SendFailure.TIMED_OUT)
            }
            LinkTimer.LATE_CONNECT -> connectIsLate = true
            // Checked once, as upstream RingScanner.swift:977-986: a later frame does not reopen it.
            LinkTimer.STABILITY -> if (frameSeen) attempts = 0
            LinkTimer.RECONNECT -> open(standingConnection = nextIsStanding)
            LinkTimer.BOND -> if (awaitingBond) pairingFailed(PairingFailure.BOND_TIMED_OUT)
            LinkTimer.NOT_STREAMING -> {
                stateFlow.value = LinkState.NotStreaming
                note("not streaming", "no data frame ${NOT_STREAMING_AFTER.toMillis()} ms after the CCCD")
            }
            LinkTimer.EARLY_DROP -> {
                pastEarlyWindow = true
                if (discoveryDone) earlyDrops = 0 // a healthy connection
            }
        }
    }

    /**
     * The connection failed or dropped: the operation in flight fails with [inFlight], the
     * connection is closed and the next attempt is scheduled by address.
     */
    private fun dropLink(
        inFlight: SendFailure,
        failure: ReconnectPolicy.Failure = ReconnectPolicy.Failure.FAILED,
        disconnected: Boolean = false,
    ) {
        if (session == null) return
        val ended = if (standing && !connected) ReconnectPolicy.Failure.STANDING_FAILED else failure
        val bondLost = disconnected && countEarlyDrop()
        closeSession(inFlight, TeardownReason.LINK_DROPPED)
        val plan = ReconnectPolicy.next(attempts, ended)
        attempts = plan.attempt
        nextIsStanding = plan.standing
        // Reconnecting goes on either way: the bond may be fine and the drops something else.
        stateFlow.value = if (bondLost) LinkState.BondLostSuspected else LinkState.Reconnecting(plan.attempt, plan.delay)
        if (bondLost) note("bond lost suspected", "$earlyDrops early drops in a row")
        note("reconnect scheduled", "attempt ${plan.attempt} in ${plan.delay.toMillis()} ms")
        startTimer(LinkTimer.RECONNECT, plan.delay)
    }

    /**
     * Counts the connection Android just reported disconnected if it dropped early: it had
     * connected, and it either never finished discovery or was up less than [EARLY_DROP_WINDOW].
     * Only a ring Android still reports bonded counts; any other early drop starts the count
     * again. True when this drop makes [BOND_LOST_AFTER] in a row: Android 16 keeps a bond the
     * ring has lost and disconnects every connection at once, so this is all the link can see of
     * it. The link's own timeouts and error statuses are not drops: a slow ring is no lost bond.
     * The bond is never removed here.
     */
    private fun countEarlyDrop(): Boolean {
        if (!connected || (discoveryDone && pastEarlyWindow)) return false
        if (readBondState() != GattPort.BondState.BONDED) {
            earlyDrops = 0
            return false
        }
        earlyDrops++
        return earlyDrops >= BOND_LOST_AFTER
    }

    /**
     * Ends the current connection: fails its operations (the one in flight with [inFlight], the
     * waiting ones with `LINK_LOST`), stops its timers, closes it and retires its token. No
     * callback follows a close, so nothing waits for one. A connection that had connected
     * publishes its teardown. [noted] false leaves the close out of the diagnostics.
     */
    private fun closeSession(inFlight: SendFailure, reason: TeardownReason, noted: Boolean = true) {
        if (session == null) return
        val current = queue.inFlight
        queue.clear().forEach { op ->
            (op as? GattOp.Write)?.reply?.complete(SendResult.Failed(if (op === current) inFlight else SendFailure.LINK_LOST))
        }
        cancelTimer(LinkTimer.OPERATION)
        cancelTimer(LinkTimer.LATE_CONNECT)
        cancelTimer(LinkTimer.STABILITY)
        cancelTimer(LinkTimer.BOND)
        cancelTimer(LinkTimer.NOT_STREAMING)
        cancelTimer(LinkTimer.EARLY_DROP)
        closePort()
        session = null
        // The buffer is per connection: what its collector never took is dropped and counted.
        val undelivered = frameBuffer.clear()
        if (connected) teardownBuffer.add(LinkTeardown(reason, undelivered))
        if (noted) note("closed", if (connected) "${reason.rawValue}, $undelivered undelivered frames" else reason.rawValue)
        connected = false
        awaitingBond = false
    }

    /**
     * The loop ended (its scope was cancelled): close what is open, answer every waiting caller and
     * show `Idle`, so nobody watching sees a live link that is gone. Runs once the loop has
     * finished, so it is the only code touching these fields.
     */
    private fun abandon() {
        if (session != null) {
            closePort()
            session = null
            queue.clear().forEach { (it as? GattOp.Write)?.reply?.complete(SendResult.Failed(SendFailure.LINK_LOST)) }
        }
        stateFlow.value = LinkState.Idle
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
        /**
         * The smallest ATT MTU that carries a 243-byte history frame (`0x50`, `docs/PROTOCOL.md`
         * §5.5.1) whole: 243 bytes of value + 3 bytes of ATT header.
         */
        const val HISTORY_SAFE_MTU = 246

        /**
         * How long after notifications are confirmed a ring that sends no data frame shows
         * `NotStreaming` (upstream `firstFrameTimeout`, RingSession.swift:264).
         */
        val NOT_STREAMING_AFTER: Duration = Duration.ofSeconds(10)

        /** A connection that drops this soon after connecting (or before discovery) dropped early. */
        val EARLY_DROP_WINDOW: Duration = Duration.ofSeconds(2)

        /** Early drops in a row of a bonded ring that show `BondLostSuspected`. */
        const val BOND_LOST_AFTER = 3

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

/**
 * Why `send` must not write [command], or null when it may (PORTING.md D-191). The rules, in
 * order: nothing before the link is authenticated; never the link's own auth commands `01 00 00`
 * and `01 01 …`; no data command (outside the `0x01` status family) without a bond, since the
 * ring ignores data from an unbonded phone; no history sync open (`0x02 …`) while the ATT MTU is
 * too small for a whole history frame.
 */
internal fun refusalOf(command: ByteArray, authenticated: Boolean, info: LinkInfo): RefusalReason? {
    val opcode = if (command.isEmpty()) -1 else command.u8(0)
    val statusFamily = opcode == Opcode.SESSION_SETUP
    val authCommand = statusFamily && command.size >= 2 &&
        (command.u8(1) == 0x01 || command.contentEquals(Command.status0))
    return when {
        !authenticated -> RefusalReason.NOT_AUTHENTICATED
        authCommand -> RefusalReason.AUTH_COMMAND_RESERVED
        !statusFamily && !info.bonded -> RefusalReason.NOT_BONDED
        opcode == Opcode.SYNC_OPEN && !info.historySafe -> RefusalReason.HISTORY_UNSAFE
        else -> null
    }
}

/** The operation's name in the diagnostics. */
private val GattOp.stepName: String
    get() = when (this) {
        is GattOp.Connect -> "connect"
        GattOp.DiscoverServices -> "discover"
        GattOp.RequestMtu -> "MTU"
        GattOp.EnableNotifications -> "CCCD"
        is GattOp.Read -> "DIS read"
        is GattOp.Write -> when (purpose) {
            Purpose.AUTH_START -> "auth"
            Purpose.AUTH_REPLY -> "auth reply"
            Purpose.ACK -> "ack"
            Purpose.FEATURE -> "write"
        }
    }

/**
 * Whether the diagnostics record the operation's start and finish: every bring-up step does;
 * acknowledgements and feature writes, thousands per drain, would push them out of the 64 kept,
 * so only their failures are recorded.
 */
private val GattOp.isBringUpStep: Boolean
    get() = !(this is GattOp.Write && (purpose == Purpose.ACK || purpose == Purpose.FEATURE))

/** `00002a23-…` → `2a23`: the characteristic's number, never its value. */
private val GattPort.Characteristic.shortName: String get() = uuid.substringBefore('-').takeLast(4)

/** The acknowledgement the ring waits for after a frame with [opcode], or null when it waits for none. */
private fun ackFor(opcode: Int): ByteArray? = when (opcode) {
    0x47 -> Command.pageAck47
    0x4C -> Command.pageAck4C
    0x4D -> Command.pageAck4D
    0x11 -> Command.heartbeatAck
    else -> null
}
