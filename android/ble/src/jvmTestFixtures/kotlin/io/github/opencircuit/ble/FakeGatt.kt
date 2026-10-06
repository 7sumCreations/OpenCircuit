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
 * (the ring never answers), [release] them, make an operation [failWith] a GATT status, or
 * [notify] any bytes.
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

    private val calls = mutableListOf<String>()
    private val problems = mutableListOf<String>()
    private val held = mutableSetOf<Operation>()
    private val heldCallbacks = mutableListOf<Pair<Operation, () -> Unit>>()
    private val failures = mutableMapOf<Operation, Int>()
    private var outstanding: Operation? = null
    private var sink: GattPort.EventSink? = null
    private var current: SessionToken? = null
    private var generation = 0

    /** Every call the link made, in order, one line each (UUIDs shortened to their first group). */
    val log: List<String> get() = calls.toList()

    /** Every broken GATT rule, in order. Empty when the link kept the rules. */
    val violations: List<String> get() = problems.toList()

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

    override fun bondState(): GattPort.BondState = script.bondState

    override fun createBond(): Boolean {
        calls += "createBond"
        return true
    }

    override fun close() {
        calls += "close"
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
