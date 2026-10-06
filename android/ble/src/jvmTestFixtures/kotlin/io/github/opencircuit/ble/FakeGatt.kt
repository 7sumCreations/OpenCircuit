package io.github.opencircuit.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A scripted ring behind a [GattPort], for tests on the plain JVM.
 *
 * Records every call in [log] and answers each GATT operation with its callback, delivered later
 * on [scope] as Android does. Like Android, it allows ONE outstanding operation: an operation
 * submitted before the previous one's callback is delivered is recorded in [violations] and
 * throws [AssertionError], failing the test. A test can [hold] the callbacks of an operation
 * (the ring never answers), [release] them, make an operation [failWith] a GATT status,
 * [dropConnection] with a status, or [notify] any bytes. [connects], [sessions] and
 * [maxOpenConnections] record how the link opened and closed its connections. The bond starts as
 * [Script.bondState]; [changeBondState] moves it and tells every [onBondStateChanged] listener,
 * [refuseBondRequests] makes `createBond` fail, and [createBondCalls] counts the requests.
 *
 * The ring itself answers `01 00 00` with [Script.challengeFrame], and answers a write equal to
 * [Script.acceptedAuthReply] with [Script.firstFrameAfterAuth]; any other reply gets nothing,
 * as a real ring drops a wrong one without a word.
 *
 * Callbacks are tasks on [scope]. Under `runTest` with `backgroundScope`, drive them with
 * `runCurrent()` or `advanceTimeBy(…)`: `advanceUntilIdle()` stops once no foreground task is
 * left and never runs them.
 */
class FakeGatt(private val scope: CoroutineScope, private val script: Script) : GattPort {

    /** What the scripted ring has and how it answers. Every value the fake sends is a copy. */
    data class Script(
        /** The Device Information characteristics the ring has, with their values. */
        val deviceInformation: Map<GattPort.Characteristic, ByteArray> = emptyMap(),
        /** The notification the ring sends after `01 00 00` (an `81 00 <challenge> <xor>` frame), or none. */
        val challengeFrame: ByteArray? = null,
        /** The auth reply the ring accepts, or null when it accepts none. */
        val acceptedAuthReply: ByteArray? = null,
        /** The notification the ring sends once it accepted the auth reply, or none. */
        val firstFrameAfterAuth: ByteArray? = null,
        /** The ATT MTU the exchange settles on. */
        val mtuGrant: Int = 247,
        /** The phone's bond state with the ring. */
        val bondState: GattPort.BondState = GattPort.BondState.BONDED,
    )

    /** The GATT operations the fake tracks: each has exactly one callback. */
    enum class Operation {
        /** `connect`. */
        CONNECT,

        /** `discoverServices`. */
        DISCOVER_SERVICES,

        /** `requestMtu`. */
        REQUEST_MTU,

        /** `writeDescriptor`. */
        WRITE_DESCRIPTOR,

        /** `read`. */
        READ,

        /** `write`. */
        WRITE,
    }

    /** One `connect` call: the ring's address and whether it was a standing (`autoConnect`) connection. */
    data class ConnectCall(
        /** The address the connection was opened to. */
        val address: String,
        /** True for a standing connection that waits for the ring with no timeout. */
        val autoConnect: Boolean,
    )

    private val calls = mutableListOf<String>()
    private val problems = mutableListOf<String>()
    private val held = mutableSetOf<Operation>()
    private val heldCallbacks = mutableListOf<Pair<Operation, () -> Unit>>()
    private val failures = mutableMapOf<Operation, Int>()
    private val connectCalls = mutableListOf<ConnectCall>()
    private val tokens = mutableListOf<SessionToken>()
    private var outstanding: Operation? = null
    private var sink: GattPort.EventSink? = null
    private var current: SessionToken? = null
    private var generation = 0
    private var open = 0
    private var mostOpen = 0
    private var bond = script.bondState
    private var bondRequests = 0
    private var bondRequestsAccepted = true
    private val bondListeners = mutableListOf<(GattPort.BondState) -> Unit>()

    /** Every call the link made, in order, one line each (UUIDs shortened to their first group). */
    val log: List<String> get() = calls.toList()

    /** Every broken GATT rule, in order. Empty when the link kept the rules. */
    val violations: List<String> get() = problems.toList()

    /** Every `connect` call, in order, with the address it was made to. */
    val connects: List<ConnectCall> get() = connectCalls.toList()

    /** The session token of every `connect` call, in order: the last one is the current connection's. */
    val sessions: List<SessionToken> get() = tokens.toList()

    /**
     * The most connections that were ever open at once: a connection is open from its `connect`
     * until its `close`, even after it dropped (Android keeps the client until it is closed).
     */
    val maxOpenConnections: Int get() = mostOpen

    /** Holds the callbacks of [operation]: it stays outstanding until [release]. */
    fun hold(operation: Operation) {
        held += operation
    }

    /** Stops holding [operation] and delivers its held callbacks. */
    fun release(operation: Operation) {
        held -= operation
        val ready = heldCallbacks.filter { it.first == operation }
        heldCallbacks.removeAll(ready)
        ready.forEach { it.second() }
    }

    /** Answers every later [operation] with [status] instead of success. */
    fun failWith(operation: Operation, status: Int) {
        failures[operation] = status
    }

    /** Answers every later [operation] with success again (undoes [failWith]). */
    fun clearFailure(operation: Operation) {
        failures -= operation
    }

    /**
     * The ring drops the current connection: Android reports a disconnect with [status] (an HCI
     * reason such as 8, supervision timeout) and the callbacks of any operation still outstanding
     * never come. The connection stays open, as on Android, until the link closes it.
     */
    fun dropConnection(status: Int) {
        val session = current ?: return
        generation++
        heldCallbacks.clear()
        sink?.deliver(GattEvent.ConnectionChanged(session, status, connected = false))
    }

    /** The ring sends [value] on its notify characteristic, on the latest connection. */
    fun notify(value: ByteArray) {
        val session = current ?: return
        sink?.deliver(GattEvent.Notification(session, GattPort.NOTIFY, value))
    }

    /** Delivers [event] as if Android had called back with it (for example, a callback of an older connection). */
    fun deliver(event: GattEvent) {
        sink?.deliver(event)
    }

    override fun connect(session: SessionToken, ring: RememberedRing, autoConnect: Boolean, events: GattPort.EventSink): Boolean {
        sink = events
        current = session
        tokens += session
        connectCalls += ConnectCall(ring.address, autoConnect)
        open++
        mostOpen = maxOf(mostOpen, open)
        return begin(Operation.CONNECT, "connect autoConnect=$autoConnect") { status ->
            GattEvent.ConnectionChanged(session, status, connected = status == GattPort.GATT_SUCCESS)
        }
    }

    override fun discoverServices(): Boolean = begin(Operation.DISCOVER_SERVICES, "discoverServices") { status ->
        GattEvent.ServicesDiscovered(session(), status, setOf(GattPort.NOTIFY, GattPort.WRITE) + script.deviceInformation.keys)
    }

    override fun requestMtu(mtu: Int): Boolean = begin(Operation.REQUEST_MTU, "requestMtu $mtu") { status ->
        GattEvent.MtuChanged(session(), script.mtuGrant, status)
    }

    override fun setNotifications(characteristic: GattPort.Characteristic, enabled: Boolean): Boolean {
        calls += "setNotifications ${short(characteristic.uuid)} ${if (enabled) "on" else "off"}"
        return true
    }

    override fun writeDescriptor(characteristic: GattPort.Characteristic, descriptor: String, value: ByteArray): Boolean =
        begin(Operation.WRITE_DESCRIPTOR, "writeDescriptor ${short(characteristic.uuid)}/${short(descriptor)} ${hex(value)}") { status ->
            GattEvent.DescriptorWritten(session(), characteristic, descriptor, status)
        }

    override fun read(characteristic: GattPort.Characteristic): Boolean {
        val value = script.deviceInformation[characteristic]
        if (value == null) {
            // Android refuses to read a characteristic the connection does not have.
            calls += "read ${short(characteristic.uuid)} refused"
            return false
        }
        return begin(Operation.READ, "read ${short(characteristic.uuid)}") { status ->
            GattEvent.CharacteristicRead(session(), characteristic, value, status)
        }
    }

    override fun write(characteristic: GattPort.Characteristic, value: ByteArray): Boolean {
        val bytes = value.copyOf()
        return begin(
            Operation.WRITE,
            "write ${short(characteristic.uuid)} ${hex(bytes)}",
            then = { ringAnswers(bytes) },
        ) { status -> GattEvent.CharacteristicWritten(session(), characteristic, status) }
    }

    override fun bondState(): GattPort.BondState = bond

    override fun createBond(): Boolean {
        calls += "createBond"
        bondRequests++
        return bondRequestsAccepted
    }

    /** How many times the link called `createBond`. */
    val createBondCalls: Int get() = bondRequests

    /** Makes every later `createBond` return false: Android would not start the bond. */
    fun refuseBondRequests() {
        bondRequestsAccepted = false
    }

    /**
     * Sends every later bond-state change to [listener], as Android's bond-state broadcast
     * reaches the link. Bond changes are not GATT callbacks: they belong to the device, not to
     * one connection, so they carry no session token.
     */
    fun onBondStateChanged(listener: (GattPort.BondState) -> Unit) {
        bondListeners += listener
    }

    /**
     * The phone's bond state with the ring becomes [state] (the user confirmed or declined the
     * pairing prompt, the ring asked to pair, the bond was removed in Settings): `bondState()`
     * answers [state] at once, and every listener hears it later on the fake's scope, as a
     * broadcast arrives. `createBond` changes nothing by itself; the test drives each change.
     */
    fun changeBondState(state: GattPort.BondState) {
        bond = state
        val listeners = bondListeners.toList()
        scope.launch { listeners.forEach { it(state) } }
    }

    override fun close() {
        calls += "close"
        if (open > 0) open--
        outstanding = null
        heldCallbacks.clear()
        generation++ // callbacks already scheduled for the closed connection are dropped
    }

    private fun session(): SessionToken = checkNotNull(current) { "no connection" }

    /**
     * Records [line] and starts [operation], failing the test if another is outstanding. The
     * callback built by [callback] (and then [then], on success) is delivered later on [scope].
     */
    private fun begin(
        operation: Operation,
        line: String,
        then: () -> Unit = {},
        callback: (status: Int) -> GattEvent,
    ): Boolean {
        calls += line
        val busy = outstanding
        if (busy != null) {
            val problem = "${line.substringBefore(' ')} submitted while $busy is outstanding"
            problems += problem
            throw AssertionError(problem)
        }
        outstanding = operation
        val status = failures[operation] ?: GattPort.GATT_SUCCESS
        val target = sink
        val forGeneration = generation
        val deliver = {
            if (forGeneration == generation) {
                outstanding = null
                target?.deliver(callback(status))
                if (status == GattPort.GATT_SUCCESS) then()
            }
        }
        if (operation in held) heldCallbacks += operation to deliver else scope.launch { deliver() }
        return true
    }

    private fun ringAnswers(written: ByteArray) {
        val status0 = byteArrayOf(0x01, 0x00, 0x00)
        val accepted = script.acceptedAuthReply
        when {
            written.contentEquals(status0) -> script.challengeFrame?.let(::notify)
            accepted != null && written.contentEquals(accepted) -> script.firstFrameAfterAuth?.let(::notify)
        }
    }

    private fun short(uuid: String): String = uuid.substringBefore('-')

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { b -> "${DIGITS[(b.toInt() shr 4) and 0xF]}${DIGITS[b.toInt() and 0xF]}" }

    private companion object {
        const val DIGITS = "0123456789abcdef"
    }
}
