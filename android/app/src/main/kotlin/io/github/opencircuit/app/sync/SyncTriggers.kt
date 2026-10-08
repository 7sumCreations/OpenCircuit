package io.github.opencircuit.app.sync

import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.DeviceStatus
import io.github.opencircuit.ringkit.HistoryDrainCadence
import io.github.opencircuit.ringkit.NightWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * The syncs nobody taps for (PORTING.md D-272): upstream's link-ready auto-sync
 * (`ios/OpenCircuit/ContentView.swift:924-941` @ b1c2fdd) and its keepalive-driven periodic drain
 * (`ios/OpenCircuit/BLE/RingSession.swift:1220-1313`), gated by the night's quiet ([NightWindow]).
 *
 * - **Link-up.** Each time the link becomes authenticated: a sync, unless the last COMPLETE sync
 *   ([SyncTriggerSources.marks], kept across launches) finished less than [THROTTLE] ago — a sync
 *   left unfinished by a pause is never throttled — or the night is quiet. Until the first complete
 *   sync ever, the night's quiet does not hold it back. A link-up held back by the night is owed:
 *   it runs once the quiet ends while the link is still up (the catch-up).
 * - **While the link is held.** With "Disconnect after syncing" OFF, a sync whenever
 *   [HistoryDrainCadence] is due (1 h after the last sync finished, by day) and the night is not
 *   quiet. After a night that is hours, so the first evaluation past the quiet drains once.
 * - **Never two at once, never during a measure**: a trigger that finds a sync or a measure running
 *   does nothing (the controller refuses as well). Sync now is the controller's own and bypasses
 *   every rule here.
 *
 * The window is resolved again at every evaluation from [SyncTriggerSources.storedNights]. A
 * `0x10` / `0x87` descriptor whose quarter-hour step bucket is above 0 at or after the learned wake
 * confirms the wearer is up ([onDescriptor]) and is evaluated at once. Evaluations otherwise run at
 * least every [TICK_MILLIS], and exactly when the quiet or the cadence ends.
 */
class SyncTriggers(
    private val sync: HistoryDrainController,
    private val linkState: StateFlow<LinkState>,
    private val isMeasuring: StateFlow<Boolean>,
    private val sources: SyncTriggerSources,
    private val wallClock: () -> Instant,
    private val disconnectAfterSync: () -> Boolean,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    /** A link-up sync held back by the night, owed once the quiet ends. Touched only by the loop. */
    private var catchUpOwed = false

    /** When a descriptor last confirmed the wearer was up. */
    private val wakeConfirmedAt = AtomicReference<Instant?>(null)

    /** The window the last evaluation resolved: the descriptor route reads it (it cannot suspend). */
    private val lastWindow = AtomicReference<NightWindow?>(null)

    /** When the last sync this session saw finished, whatever its outcome (the cadence's clock). */
    private val lastSyncFinished = AtomicReference<Instant?>(null)

    private val pokes = Channel<Unit>(Channel.CONFLATED)

    /** Starts following the link and the syncs. Called once, by the session's start. */
    fun start() {
        scope.launch {
            sync.state.collect { state ->
                val report = state.last ?: return@collect
                if (lastSyncFinished.getAndSet(report.finishedAt) == report.finishedAt) return@collect
                if (report.outcome == SyncOutcome.COMPLETE && !report.paused && !sources.marks.setLastCompleteSync(report.finishedAt)) {
                    log("sync-trigger: the last complete sync could not be saved; the next launch syncs at link-up")
                }
            }
        }
        scope.launch {
            linkState.collectLatest { state ->
                if (state != LinkState.Authenticated) return@collectLatest
                onLinkUp()
                while (true) {
                    val wait = evaluateHeld()
                    // A descriptor confirming the wake cuts the wait short.
                    withTimeoutOrNull(wait) { pokes.receive() }
                }
            }
        }
    }

    /** One `0x10` / `0x87` descriptor (the dispatcher's thread): steps past the learned wake end the night's quiet. */
    fun onDescriptor(frame: ByteArray) {
        val steps = DeviceStatus.steps(frame) ?: return
        val window = lastWindow.get() ?: return
        val now = wallClock()
        if (!window.confirmsWake(now, steps)) return
        val before = wakeConfirmedAt.getAndSet(now)
        // Once per night: a later walking descriptor only moves the latch on.
        if (before == null || before.isBefore(window.window.start)) {
            log("sync-trigger: steps past the learned wake; the night's quiet ends")
            pokes.trySend(Unit)
        }
    }

    /** The night the time now belongs to, from the store's latest nights (the fallback when they cannot be read). */
    suspend fun nightWindow(): NightWindow? {
        val nights = try {
            sources.storedNights()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("sync-trigger: stored nights could not be read (${e::class.java.simpleName}); using the fallback window")
            emptyList()
        }
        return NightWindow.resolve(nights, wallClock(), sources.zone()).also { lastWindow.set(it) }
    }

    private suspend fun onLinkUp() {
        if (sync.isSyncing.value || isMeasuring.value) return
        val now = wallClock()
        val lastComplete = sources.marks.lastCompleteSync.notAfter(now)
        if (lastComplete == null) {
            start("link-up, no complete sync yet")
            return
        }
        val unfinished = sync.state.value.last?.paused == true
        if (!unfinished && Duration.between(lastComplete, now) < THROTTLE) {
            log("sync-trigger: link-up within ${THROTTLE.seconds} s of the last complete sync; not syncing")
            return
        }
        val window = nightWindow()
        if (window != null && window.isQuiet(now, wakeConfirmedAt.get())) {
            catchUpOwed = true
            log("sync-trigger: link-up inside the night; the sync waits for the night's end")
            return
        }
        start(if (unfinished) "link-up, unfinished sync" else "link-up")
    }

    /**
     * One evaluation while the link is held; returns how long to wait before the next (at most
     * [TICK_MILLIS], exactly to the quiet's or the cadence's end when that comes sooner).
     */
    private suspend fun evaluateHeld(): Long {
        if (sync.isSyncing.value || isMeasuring.value) return TICK_MILLIS
        val now = wallClock()
        val window = nightWindow()
        if (window != null && window.isQuiet(now, wakeConfirmedAt.get())) {
            val ends = if (window.learned) window.quietCeiling else window.window.end
            return untilOrTick(now, ends)
        }
        if (catchUpOwed) {
            catchUpOwed = false
            start("catch-up after the night")
            return TICK_MILLIS
        }
        if (disconnectAfterSync()) return TICK_MILLIS
        val last = listOfNotNull(lastSyncFinished.get().notAfter(now), sources.marks.lastCompleteSync.notAfter(now)).maxOrNull()
        val due = HistoryDrainCadence.isDue(last, now, isNight = false, batterySaver = false)
        if (HistoryDrainCadence.shouldDrain(manual = false, inSleepWindow = false, isDue = due)) {
            start("periodic")
            return TICK_MILLIS
        }
        // Not due means a last sync exists (isDue is true without one).
        val next = last?.plus(HistoryDrainCadence.interval(isNight = false, batterySaver = false)) ?: return TICK_MILLIS
        return untilOrTick(now, next)
    }

    private fun start(reason: String) {
        if (sync.syncNow()) log("sync-trigger: $reason")
    }

    /**
     * This mark, unless it is after [now]: the phone's clock was stepped back since it was taken,
     * and a mark from the future would hold every sync until the clock caught up with it.
     */
    private fun Instant?.notAfter(now: Instant): Instant? = this?.takeUnless { it.isAfter(now) }

    private fun untilOrTick(now: Instant, at: Instant): Long =
        Duration.between(now, at).toMillis().coerceIn(0, TICK_MILLIS)

    companion object {
        /** No link-up sync within this of the last complete one (upstream `autoSyncInterval`, ContentView.swift:132). */
        val THROTTLE: Duration = Duration.ofSeconds(300)

        /** The longest wait between two evaluations while the link is held. */
        const val TICK_MILLIS: Long = 60_000L
    }
}
