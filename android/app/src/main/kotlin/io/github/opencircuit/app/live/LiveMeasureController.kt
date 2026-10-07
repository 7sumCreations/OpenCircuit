package io.github.opencircuit.app.live

import io.github.opencircuit.ble.RefusalReason
import io.github.opencircuit.ble.SendFailure
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.Frame
import io.github.opencircuit.ringkit.LiveHR
import io.github.opencircuit.ringkit.LiveMeasureOwnership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** What a live measure reads. */
enum class LiveMode {
    /** Heart rate, `06 01 00`: short `15 00 <bpm> …` frames. */
    HEART_RATE,

    /** SpO₂, `06 02 00`: long `15 01 …` frames, byte 14. An estimate (PROTOCOL.md §5.1, 🟡). */
    SPO2,
}

/** Why a live measure ended without the reading the user asked for. Shown on the measured row. */
sealed interface MeasureFailure {
    /** The budget ran out with nothing locked (no settled heart rate / no SpO₂ value); [evidence] is what the ring sent. */
    data class NoReading(val evidence: MeasureEvidence) : MeasureFailure

    /** The ring refused to switch to [mode] twice, answering the mode write with `86 <status> …` (PORTING.md D-255). */
    data class ModeRejected(val mode: LiveMode, val status: Int) : MeasureFailure

    /** The link refused one of the measure's writes. */
    data class CommandRefused(val reason: RefusalReason) : MeasureFailure

    /** One of the measure's writes failed. */
    data class CommandFailed(val reason: SendFailure) : MeasureFailure

    /** The connection was torn down, or the link left `Authenticated`, mid-measure. */
    data object RingDisconnected : MeasureFailure
}

/**
 * What the ring sent during one measure: counts and the ring's answers to the mode write, never a
 * reading. Kept after the measure ends, for Connection details (PORTING.md D-255).
 */
data class MeasureEvidence(
    val mode: LiveMode,
    /** `0x15` frames with a good trailer that arrived while this measure ran. */
    val liveFrames: Int = 0,
    /** Of those, the ones that gave no reading: heart rate not locked on (warm-up / out of band), or no SpO₂ value. */
    val unusableFrames: Int = 0,
    /** The status byte of each `86 <status> <xor>` answer to this measure's mode writes, in order (`0x00` accepted). */
    val modeReplies: List<Int> = emptyList(),
    /** How often the ring was put back to idle with `06 00 00` after refusing the mode. */
    val resets: Int = 0,
)

/** A Measure row's outcome, kept in memory for the session (live readings are not saved). */
data class MeasureResult(
    /** The last reading: the settled heart rate, or the last SpO₂ %; null if there never was one. */
    val lastValue: Int? = null,
    /** Why the latest measure of this row failed; cleared when a new one starts. */
    val failure: MeasureFailure? = null,
)

/** Everything the live readout and the Measure rows show. */
data class LiveMeasureState(
    /** The mode being measured, or null when no measure runs. */
    val mode: LiveMode? = null,
    /** The entry writes are still being made (before the settle). */
    val preparing: Boolean = false,
    /** Heart-rate frames arrive but none has locked on since the last locked one. */
    val warmingUp: Boolean = false,
    /** The newest valid reading of the measured mode, for the readout and the chart. */
    val newest: Int? = null,
    /** Median of the last 5 locked heart-rate frames; null before 5 (`LiveHR.settled`). */
    val settledHeartRate: Int? = null,
    /** No `0x15` frame for 30 s while measuring: the readout is out of date. */
    val stale: Boolean = false,
    /** The chart's points and the low–high so far. */
    val session: LiveSessionSnapshot = LiveSessionSnapshot(),
    /** The heart-rate row. */
    val heartRate: MeasureResult = MeasureResult(),
    /** The SpO₂ row. */
    val spo2: MeasureResult = MeasureResult(),
    /** `0x15` frames dropped: a bad XOR trailer, or none was asked for. Counted, never silent. */
    val framesNotUsed: Int = 0,
    /** What the ring sent during the running measure, or during the last one; null before the first. */
    val evidence: MeasureEvidence? = null,
)

/**
 * The on-demand live heart-rate / SpO₂ measure: upstream's quick user read
 * (`ios/OpenCircuit/BLE/RingSession.swift:1036-1186, 2267-2310, 3352-3477` @ b1c2fdd),
 * redesigned as a pure Kotlin state machine on an injected scope and monotonic clock.
 *
 * - **Entry:** `d0 00 00` → `06 01 00` (heart rate) / `06 02 00` (SpO₂) → `07 00 00`, each
 *   followed by 250 ms. No `01 00 00` first: the link runs its own auth and refuses it
 *   (PORTING.md D-232); `07 00 00` is written only here, as the third entry write (D-233).
 * - **Mode answer:** the ring answers each mode write with `86 <status> <xor>` ([onModeReply]);
 *   `00` and `fc` accept. The first refusal of a measure puts the ring back to idle with
 *   `06 00 00`, waits 1 s and writes the whole entry again; a second refusal ends the measure with
 *   [MeasureFailure.ModeRejected] (D-255). Each answer is kept in [MeasureEvidence].
 * - **Polling:** the budget is armed after the entry, then 2 s of settle, then `95 00 00` every
 *   2 s from the start of one poll to the start of the next — never faster (PROTOCOL.md §5.1).
 * - **Budget:** heart rate 90 s, SpO₂ 45 s, from the arm or the latest re-tap / mode switch; the
 *   measure stops at the budget itself (D-236). Running out with nothing locked is [MeasureFailure.NoReading].
 * - **Ownership:** a tap goes through [LiveMeasureOwnership.decide]: start, re-tap (re-writes the
 *   entry, the poll loop continues), or mode switch. A tap during the entry does not overlap a
 *   second entry with it (D-237).
 * - **Stop:** [stop], the budget, [onLinkLost], or any write that is refused or fails (shown on
 *   the row — D-238). Nothing resumes it. Readings are shown and kept in memory, never saved (D-234).
 *
 * Thread-safe: every public call may come from any thread.
 */
class LiveMeasureController(
    private val send: suspend (ByteArray) -> SendResult,
    private val scope: CoroutineScope,
    private val monotonicMillis: () -> Long,
) {
    private val lock = Any()
    private val stateFlow = MutableStateFlow(LiveMeasureState())
    private val measuringFlow = MutableStateFlow(false)

    // Guarded by `lock`.
    private var cycle = 0L
    private var runJob: Job? = null
    private var reEntryJob: Job? = null
    private var staleJob: Job? = null
    private var deadline = Long.MAX_VALUE
    private var reset = false
    private val awaitedAnswers = ArrayDeque<Awaited>()
    private val trend = ArrayList<Int>()
    private val session = LiveSession()

    /** What the readout and the rows show. */
    val state: StateFlow<LiveMeasureState> = stateFlow.asStateFlow()

    /** True from a start until the measure stops, for whatever must stay quiet meanwhile. */
    val isMeasuring: StateFlow<Boolean> = measuringFlow.asStateFlow()

    /** The user tapped Measure for [mode]. */
    fun start(mode: LiveMode) = synchronized(lock) {
        val current = stateFlow.value
        val measuring = current.mode != null
        val action = LiveMeasureOwnership.decide(
            monitoring = measuring,
            userInitiated = true,
            // Every measure here is one the user started; there is no background measure yet.
            userMeasuring = measuring,
            workoutHolding = false,
            sameMode = current.mode == mode,
        )
        when (action) {
            is LiveMeasureOwnership.Action.Start, LiveMeasureOwnership.Action.Takeover -> begin(mode)
            LiveMeasureOwnership.Action.Rearm -> if (!current.preparing) reArm(mode)
            is LiveMeasureOwnership.Action.SwitchMode -> if (current.preparing) begin(mode) else reArm(mode)
            LiveMeasureOwnership.Action.Ignore -> Unit
        }
    }

    /** The user tapped Stop. Not a failure; the row keeps what was read. */
    fun stop() = synchronized(lock) { finish(cycle, failure = null) }

    /** The connection was torn down or the link left `Authenticated`: the measure stops for good. */
    fun onLinkLost() = synchronized(lock) { finish(cycle, failure = MeasureFailure.RingDisconnected) }

    /** One `0x15` frame from the ring, routed here by the frame dispatcher. */
    fun onFrame(frame: ByteArray) = synchronized(lock) {
        val current = stateFlow.value
        val mode = current.mode
        // Upstream drops a frame whose XOR trailer is wrong before decoding it (RS:5336).
        if (mode == null || !Frame.isValid(frame)) {
            stateFlow.value = current.copy(framesNotUsed = current.framesNotUsed + 1)
            return@synchronized
        }
        restartStaleTimer()
        val now = monotonicMillis()
        var next = current.copy(stale = false)
        val reading: Int? = when (mode) {
            LiveMode.HEART_RATE -> {
                val locked = LiveHR.decodeLocked(frame)
                if (locked != null) {
                    trend += locked
                    if (trend.size > TREND_CAPACITY) trend.removeAt(0)
                    session.add(now, locked)
                    next = next.copy(newest = locked, warmingUp = false, settledHeartRate = LiveHR.settled(trend))
                } else if (LiveHR.decode(frame) != null) {
                    next = next.copy(warmingUp = true)
                }
                locked
            }
            LiveMode.SPO2 -> LiveHR.decodeSpO2(frame)?.also { spo2 ->
                session.add(now, spo2)
                next = next.copy(newest = spo2)
            }
        }
        val evidence = (current.evidence ?: MeasureEvidence(mode)).let {
            it.copy(liveFrames = it.liveFrames + 1, unusableFrames = it.unusableFrames + if (reading == null) 1 else 0)
        }
        stateFlow.value = next.copy(session = session.snapshot(), evidence = evidence)
    }

    /**
     * One `0x86` frame, routed here by the frame dispatcher: the ring's answer to a `06 xx 00` write,
     * `86 <status> <xor>`. The ring answers in write order, so each answer belongs to the oldest
     * write still waiting for one. An answer nothing waits for (no measure runs) changes nothing.
     */
    fun onModeReply(frame: ByteArray) = synchronized(lock) {
        val current = stateFlow.value
        val mode = current.mode ?: return@synchronized
        if (!Frame.isValid(frame) || frame.size < 3) return@synchronized
        val awaited = awaitedAnswers.removeFirstOrNull() ?: return@synchronized
        if (awaited == Awaited.RESET) return@synchronized // the way back to idle; only the mode's answer decides
        val status = frame[1].toInt() and 0xFF
        val evidence = (current.evidence ?: MeasureEvidence(mode)).let { it.copy(modeReplies = it.modeReplies + status) }
        stateFlow.value = current.copy(evidence = evidence)
        // `86 fc …` is "already in that mode", accepted as upstream does (RS:5299-5307).
        if (status == MODE_ACCEPTED || status == MODE_ALREADY) return@synchronized
        if (reset) finish(cycle, MeasureFailure.ModeRejected(mode, status)) else resetAndReenter(mode)
    }

    // Everything below runs with `lock` held, except inside the launched coroutines.

    private fun begin(mode: LiveMode) {
        cancelJobs()
        val myCycle = ++cycle
        trend.clear()
        session.reset()
        deadline = Long.MAX_VALUE
        reset = false
        awaitedAnswers.clear()
        stateFlow.value = stateFlow.value.copy(
            mode = mode, preparing = true, warmingUp = false, newest = null, settledHeartRate = null,
            stale = false, session = session.snapshot(), evidence = MeasureEvidence(mode),
        ).withRow(mode) { it.copy(failure = null) }
        measuringFlow.value = true
        runJob = scope.launch { enterAndPoll(myCycle, mode, resetFirst = false) }
    }

    /**
     * The ring refused the mode: back to idle with `06 00 00`, 1 s to settle there, then the whole
     * entry again and a fresh budget — upstream's way into a mode the ring would not switch to
     * directly (`ios/OpenCircuit/BLE/RingSession.swift:2614-2621` @ b1c2fdd). Once per measure.
     */
    private fun resetAndReenter(mode: LiveMode) {
        runJob?.cancel()
        reEntryJob?.cancel()
        reEntryJob = null
        reset = true
        awaitedAnswers.clear()
        deadline = Long.MAX_VALUE
        val current = stateFlow.value
        stateFlow.value = current.copy(
            preparing = true,
            evidence = (current.evidence ?: MeasureEvidence(mode)).let { it.copy(resets = it.resets + 1) },
        )
        val myCycle = cycle
        runJob = scope.launch { enterAndPoll(myCycle, mode, resetFirst = true) }
    }

    private suspend fun enterAndPoll(myCycle: Long, mode: LiveMode, resetFirst: Boolean) {
        if (resetFirst) {
            if (!write(myCycle, Command.sportStop, Awaited.RESET)) return
            delay(RESET_SETTLE_MILLIS)
        }
        if (!writeEntry(myCycle, mode)) return
        val armedAt = synchronized(lock) {
            // A run cancelled under the lock (a refusal's re-entry keeps the cycle) must not arm.
            if (cycle != myCycle || !coroutineContext.isActive) return
            val now = monotonicMillis()
            deadline = now + budgetMillis(mode)
            stateFlow.value = stateFlow.value.copy(preparing = false)
            now
        }
        pollUntilTheBudget(myCycle, firstPollAt = armedAt + SETTLE_MILLIS)
    }

    /** Re-tap or mode switch while polling: fresh values, a fresh budget, the entry re-written. */
    private fun reArm(mode: LiveMode) {
        reEntryJob?.cancel()
        val myCycle = cycle
        val switching = stateFlow.value.mode != mode
        trend.clear()
        if (switching) session.reset()
        deadline = monotonicMillis() + budgetMillis(mode)
        // A fresh request: the ring may be reset once more if it refuses this mode.
        reset = false
        val current = stateFlow.value
        stateFlow.value = current.copy(
            mode = mode, warmingUp = false, newest = null, settledHeartRate = null, stale = false,
            session = session.snapshot(),
            evidence = if (switching || current.evidence == null) MeasureEvidence(mode) else current.evidence,
        ).withRow(mode) { it.copy(failure = null) }
        reEntryJob = scope.launch { writeEntry(myCycle, mode) }
    }

    /** `d0 00 00`, the mode, `07 00 00`, each followed by 250 ms. False when the measure ended. */
    private suspend fun writeEntry(myCycle: Long, mode: LiveMode): Boolean {
        val modeCommand = if (mode == LiveMode.HEART_RATE) Command.liveHRMode else Command.liveSpO2Mode
        for (command in listOf(Command.statusQuery, modeCommand, Command.fetch)) {
            if (!write(myCycle, command, if (command === modeCommand) Awaited.MODE else null)) return false
            delay(ENTRY_SPACING_MILLIS)
        }
        return true
    }

    private suspend fun pollUntilTheBudget(myCycle: Long, firstPollAt: Long) {
        var nextPollAt = firstPollAt
        while (true) {
            val now = monotonicMillis()
            val budgetEndsAt = synchronized(lock) {
                if (cycle != myCycle || !coroutineContext.isActive) return
                if (now >= deadline) {
                    finishAtTheBudget(myCycle)
                    return
                }
                deadline
            }
            if (now >= nextPollAt) {
                nextPollAt = now + POLL_INTERVAL_MILLIS
                if (!write(myCycle, Command.poll)) return
            }
            delay(minOf(nextPollAt, budgetEndsAt) - monotonicMillis())
        }
    }

    /**
     * Writes [command]; on a refusal or failure ends the measure with it on the row. [awaits] says
     * the ring answers this write with a `0x86` frame (queued before the write, as the answer may
     * arrive before the write's own completion).
     */
    private suspend fun write(myCycle: Long, command: ByteArray, awaits: Awaited? = null): Boolean {
        coroutineContext.ensureActive()
        if (awaits != null) {
            synchronized(lock) {
                if (cycle == myCycle) {
                    // The reset's answer was due within its 1 s settle; one still missing when the
                    // mode is written is given up, so the next answer is read as the mode's.
                    if (awaits == Awaited.MODE) awaitedAnswers.remove(Awaited.RESET)
                    awaitedAnswers.addLast(awaits)
                }
            }
        }
        val failure = when (val result = send(command)) {
            SendResult.Sent -> return true
            is SendResult.Refused -> MeasureFailure.CommandRefused(result.reason)
            is SendResult.Failed -> MeasureFailure.CommandFailed(result.reason)
        }
        synchronized(lock) { finish(myCycle, failure) }
        return false
    }

    private fun finishAtTheBudget(myCycle: Long) {
        val current = stateFlow.value
        val locked = when (current.mode) {
            LiveMode.HEART_RATE -> current.settledHeartRate != null
            LiveMode.SPO2 -> current.newest != null
            null -> return
        }
        finish(myCycle, failure = if (locked) null else MeasureFailure.NoReading(current.evidence ?: MeasureEvidence(current.mode)))
    }

    /** Ends cycle [myCycle] if it is still the running one: jobs cancelled, the row updated. */
    private fun finish(myCycle: Long, failure: MeasureFailure?) {
        val current = stateFlow.value
        val mode = current.mode ?: return
        if (myCycle != cycle) return
        cancelJobs()
        cycle++
        val reading = if (mode == LiveMode.HEART_RATE) current.settledHeartRate else current.newest
        trend.clear()
        session.reset()
        deadline = Long.MAX_VALUE
        awaitedAnswers.clear()
        stateFlow.value = LiveMeasureState(
            heartRate = current.heartRate,
            spo2 = current.spo2,
            framesNotUsed = current.framesNotUsed,
            evidence = current.evidence,
        ).withRow(mode) { MeasureResult(lastValue = reading ?: it.lastValue, failure = failure) }
        measuringFlow.value = false
    }

    private fun restartStaleTimer() {
        staleJob?.cancel()
        val myCycle = cycle
        staleJob = scope.launch {
            delay(STALE_AFTER_MILLIS)
            synchronized(lock) {
                // `synchronized` is no cancellation point: a timer a newer frame cancelled while this
                // waited for the lock must not mark that frame's readout stale. Cancels happen under
                // the lock, so this check sees them.
                if (cycle == myCycle && isActive) stateFlow.value = stateFlow.value.copy(stale = true)
            }
        }
    }

    private fun cancelJobs() {
        runJob?.cancel()
        reEntryJob?.cancel()
        staleJob?.cancel()
        runJob = null
        reEntryJob = null
        staleJob = null
    }

    private fun LiveMeasureState.withRow(mode: LiveMode, change: (MeasureResult) -> MeasureResult) = when (mode) {
        LiveMode.HEART_RATE -> copy(heartRate = change(heartRate))
        LiveMode.SPO2 -> copy(spo2 = change(spo2))
    }

    /** A write the ring answers with a `0x86` frame. */
    private enum class Awaited { MODE, RESET }

    private companion object {
        /** `86 00 86`: the mode write was accepted (PROTOCOL.md §4). */
        const val MODE_ACCEPTED = 0x00

        /** `86 fc 7a`: already in that mode, which upstream treats as accepted (RS:5299-5307). */
        const val MODE_ALREADY = 0xFC

        /** Idle settle after `06 00 00` before the entry is written again (RS:2621: "settle in idle so `06 01` is accepted"). */
        const val RESET_SETTLE_MILLIS = 1_000L

        /** Sleep after each entry write (RS:1123-1126). */
        const val ENTRY_SPACING_MILLIS = 250L

        /** Wait after the entry before the first poll (RS:1145). */
        const val SETTLE_MILLIS = 2_000L

        /** From one poll's start to the next; never less (PROTOCOL.md §5.1, RS:1181). */
        const val POLL_INTERVAL_MILLIS = 2_000L

        /** No `0x15` for this long while measuring: the readout is stale (RS:230-235). */
        const val STALE_AFTER_MILLIS = 30_000L

        /** Locked heart-rate frames kept for settling (RS:5346-5347). */
        const val TREND_CAPACITY = 12

        /** User-measure budget per mode (RS:3352-3354). */
        fun budgetMillis(mode: LiveMode): Long = if (mode == LiveMode.SPO2) 45_000L else 90_000L
    }
}
