package io.github.opencircuit.app.sync

import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.DeviceStatus
import io.github.opencircuit.ringkit.HistoryDrainCadence
import io.github.opencircuit.ringkit.NightWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** Which automatic sync a skip held back. */
enum class AutoSyncTrigger {
    /** The link became authenticated. */
    LINK_UP,

    /** The link-up held back by the night, owed once the quiet ends. */
    CATCH_UP,

    /** The 1 h cadence while the link is held. */
    CADENCE,
}

/** Why an automatic sync did not run. */
enum class AutoSyncSkipReason {
    /** The last complete sync finished less than [SyncTriggers.THROTTLE] ago. */
    THROTTLED,

    /** The night is quiet ([NightWindow.isQuiet]). */
    NIGHT_QUIET,

    /** A sync was already running. */
    ALREADY_SYNCING,

    /** A live measure was running. */
    MEASURING,
}

/**
 * One automatic sync that did not run, kept so the phone can show why ([SyncTriggers.skips]):
 * logcat is out of reach on the phone. [disconnected]: the skip closed the link, as the
 * sync-and-disconnect mode holds no idle link.
 */
data class AutoSyncSkip(
    val at: Instant,
    val trigger: AutoSyncTrigger,
    val reason: AutoSyncSkipReason,
    val disconnected: Boolean = false,
)

/**
 * The syncs nobody taps for (PORTING.md D-272): upstream's link-ready auto-sync
 * (`ios/OpenCircuit/ContentView.swift:924-941` @ b1c2fdd) and its keepalive-driven periodic drain
 * (`ios/OpenCircuit/BLE/RingSession.swift:1220-1313`), gated by the night's quiet ([NightWindow]).
 *
 * - **Link-up.** Each time the link becomes authenticated: a sync, unless the last COMPLETE sync
 *   ([SyncTriggerSources.marks], kept across launches) finished less than [THROTTLE] ago — a sync
 *   left unfinished by a pause, or one that ended NOT_CONNECTED while this link was still coming
 *   up, is never throttled — or the night is quiet. Until the first complete
 *   sync ever, the night's quiet does not hold it back. A link-up held back by the night is owed:
 *   it runs once the quiet ends while the link is still up (the catch-up). An AUTOMATIC link-up
 *   (the remembered ring's reconnect at launch, the link's own reconnect after a drop) the
 *   throttle holds back with "Disconnect after syncing" ON closes the link ([disconnect]): that
 *   mode holds no idle link it did not need. The same for an automatic link-up the night holds
 *   back with the switch ON (PORTING.md D-275): the link closes at once and the catch-up stays
 *   owed — when the quiet ends it starts a sync that connects the ring itself, while the app's
 *   process is alive (no foreground service yet). With the switch OFF the link is held and the
 *   catch-up runs on it. A link-up the user asked for ([takeUserLinkUp]: Scan & connect, Try
 *   again, Sync now) is never closed here, nor is one a measure is running on.
 * - **Every skip says why.** Each automatic sync that does not run — throttled, night quiet, a
 *   sync already running, a measure running — is kept in [skips] with the trigger it held back,
 *   for Connection details (logcat is out of reach on the phone). A skip that repeats on every
 *   evaluation is kept once, at its latest time.
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
    /** Closes the link (`RingLink.disconnect`): a throttled automatic link-up in the sync-and-disconnect mode. */
    private val disconnect: () -> Unit,
    /**
     * Whether the link-up happening now is one the user asked for (Scan & connect, Try again,
     * Sync now), taking the mark (`RingSessionController.takeUserLinkUp`). Read once per link-up.
     */
    private val takeUserLinkUp: () -> Boolean,
) {
    /** A link-up sync held back by the night, owed once the quiet ends. Touched only by the link's one collection (one block at a time). */
    @Volatile
    private var catchUpOwed = false

    /** When a descriptor last confirmed the wearer was up. */
    private val wakeConfirmedAt = AtomicReference<Instant?>(null)

    /** The window the last evaluation resolved: the descriptor route reads it (it cannot suspend). */
    private val lastWindow = AtomicReference<NightWindow?>(null)

    /** When the last sync this session saw finished, whatever its outcome (the cadence's clock). */
    private val lastSyncFinished = AtomicReference<Instant?>(null)

    private val pokes = Channel<Unit>(Channel.CONFLATED)

    private val skipsFlow = MutableStateFlow<List<AutoSyncSkip>>(emptyList())

    /** The running sync is one this class started; released whenever no sync runs, however it ended. */
    private val ownSync = AtomicBoolean(false)

    /** The automatic syncs that did not run this session, oldest first (the last [SKIPS_KEPT]). */
    val skips: StateFlow<List<AutoSyncSkip>> = skipsFlow.asStateFlow()

    /** Starts following the link and the syncs. Called once, by the session's start. */
    fun start() {
        scope.launch { sync.isSyncing.collect { syncing -> if (!syncing) ownSync.set(false) } }
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
            // collectLatest joins the cancelled block before the next starts: the blocks never overlap.
            linkState.collectLatest { state ->
                if (state != LinkState.Authenticated) {
                    catchUpOffLink()
                    return@collectLatest
                }
                // The link-up closed the link: nothing to evaluate on it.
                if (!onLinkUp()) return@collectLatest
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

    /** One link-up; returns false when it closed the link (the sync-and-disconnect mode holds no idle link). */
    private suspend fun onLinkUp(): Boolean {
        // Taken first, on every path: the mark belongs to this link-up and no later one.
        val byUser = takeUserLinkUp()
        val now = wallClock()
        busyReason()?.let { reason ->
            // The catch-up's own connect brings this link-up: the sync running is the one wanted.
            if (!(reason == AutoSyncSkipReason.ALREADY_SYNCING && ownSync.get())) skipped(now, AutoSyncTrigger.LINK_UP, reason)
            return true
        }
        val lastComplete = sources.marks.lastCompleteSync.notAfter(now)
        if (lastComplete == null) {
            start(AutoSyncTrigger.LINK_UP, "link-up, no complete sync yet")
            return true
        }
        // Unfinished: paused, or ended NOT_CONNECTED on a link that was left to come up — this link-up is its sync.
        val unfinished = sync.state.value.last?.let { it.paused || it.outcome == SyncOutcome.NOT_CONNECTED } == true
        if (!unfinished && Duration.between(lastComplete, now) < THROTTLE) {
            // The sync-and-disconnect mode holds no idle link it brought up itself; a link the user
            // asked for is theirs (a measure may be about to start on it), and a running measure
            // was already a skip above.
            val close = disconnectAfterSync() && !byUser && !isMeasuring.value
            val why = when {
                close -> ", disconnecting"
                byUser && disconnectAfterSync() -> ", the user asked for this link: kept"
                else -> ""
            }
            log("sync-trigger: link-up within ${THROTTLE.seconds} s of the last complete sync; not syncing$why")
            skipped(now, AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.THROTTLED, disconnected = close)
            if (close) disconnect()
            return !close
        }
        val window = nightWindow()
        if (window != null && window.isQuiet(now, wakeConfirmedAt.get())) {
            // Owed before any close: the catch-up then connects on its own once the quiet ends (catchUpOffLink).
            catchUpOwed = true
            // The same rule as the throttle's (PORTING.md D-275): never the user's link, never a measure's.
            val close = disconnectAfterSync() && !byUser && !isMeasuring.value
            val why = if (close) "; disconnecting until then" else ""
            log("sync-trigger: link-up inside the night; the sync waits for the night's end$why")
            skipped(now, AutoSyncTrigger.LINK_UP, AutoSyncSkipReason.NIGHT_QUIET, disconnected = close)
            if (close) disconnect()
            return !close
        }
        start(AutoSyncTrigger.LINK_UP, if (unfinished) "link-up, unfinished sync" else "link-up")
        return true
    }

    /**
     * The link is not up and a catch-up is owed in the sync-and-disconnect mode (the night closed
     * the link at its link-up): waits for the quiet to end — the learned ceiling or the window's
     * end, as no descriptor can confirm the wake without a link — then starts the catch-up, whose
     * sync connects the ring itself ([HistoryDrainController.syncNow]). Runs only while the app's
     * process is alive: nothing wakes a stopped process for it (no foreground service yet), so a
     * catch-up the process did not live to run is the next link-up's sync. Switch OFF: nothing here;
     * the held link's own evaluation runs the catch-up.
     */
    private suspend fun catchUpOffLink() {
        while (catchUpOwed && disconnectAfterSync()) {
            val now = wallClock()
            val busy = busyReason()
            if (busy != null) {
                if (!(busy == AutoSyncSkipReason.ALREADY_SYNCING && ownSync.get())) skipped(now, AutoSyncTrigger.CATCH_UP, busy)
                delay(TICK_MILLIS)
                continue
            }
            val window = nightWindow()
            if (window != null && window.isQuiet(now, wakeConfirmedAt.get())) {
                val wait = untilOrTick(now, if (window.learned) window.quietCeiling else window.window.end)
                // Still quiet at its end (a clock stepped back): look again a tick on, never spin.
                delay(if (wait == 0L) TICK_MILLIS else wait)
                continue
            }
            // Owed until a sync serves it: a refused start is tried again a tick on.
            if (start(AutoSyncTrigger.CATCH_UP, "catch-up after the night; connecting")) {
                catchUpOwed = false
                return
            }
            delay(TICK_MILLIS)
        }
    }

    /**
     * One evaluation while the link is held; returns how long to wait before the next (at most
     * [TICK_MILLIS], exactly to the quiet's or the cadence's end when that comes sooner).
     */
    private suspend fun evaluateHeld(): Long {
        val now = wallClock()
        val busy = busyReason()
        if (busy != null) {
            val cadence = cadence(now)
            // A sync this class started is the one the trigger wanted: no skip for it.
            val heldBack = if (catchUpOwed) AutoSyncTrigger.CATCH_UP else AutoSyncTrigger.CADENCE.takeIf { cadence.due }
            if (heldBack != null && !(busy == AutoSyncSkipReason.ALREADY_SYNCING && ownSync.get())) skipped(now, heldBack, busy)
            // Evaluated again when the cadence falls due, so a skip is kept at the time it happened.
            val next = cadence.last?.takeUnless { cadence.due }?.plus(HistoryDrainCadence.interval(isNight = false, batterySaver = false))
            return next?.let { untilOrTick(now, it) } ?: TICK_MILLIS
        }
        val window = nightWindow()
        if (window != null && window.isQuiet(now, wakeConfirmedAt.get())) {
            // An owed catch-up was already reported at its link-up.
            if (!catchUpOwed && cadence(now).due) skipped(now, AutoSyncTrigger.CADENCE, AutoSyncSkipReason.NIGHT_QUIET)
            val ends = if (window.learned) window.quietCeiling else window.window.end
            return untilOrTick(now, ends)
        }
        if (catchUpOwed) {
            // Owed until a sync serves it: a refused start (a measure began meanwhile) is evaluated again.
            if (start(AutoSyncTrigger.CATCH_UP, "catch-up after the night")) catchUpOwed = false
            return TICK_MILLIS
        }
        val cadence = cadence(now)
        if (cadence.due) {
            start(AutoSyncTrigger.CADENCE, "periodic")
            return TICK_MILLIS
        }
        // Not due means a last sync exists (isDue is true without one), or the switch is on.
        val next = cadence.last?.plus(HistoryDrainCadence.interval(isNight = false, batterySaver = false)) ?: return TICK_MILLIS
        return untilOrTick(now, next)
    }

    /** Whether the held link's cadence sync is due at [now] (never with "Disconnect after syncing" on), and the last sync it counts from. */
    private fun cadence(now: Instant): Cadence {
        if (disconnectAfterSync()) return Cadence(due = false, last = null)
        val last = listOfNotNull(lastSyncFinished.get().notAfter(now), sources.marks.lastCompleteSync.notAfter(now)).maxOrNull()
        val due = HistoryDrainCadence.isDue(last, now, isNight = false, batterySaver = false)
        return Cadence(HistoryDrainCadence.shouldDrain(manual = false, inSleepWindow = false, isDue = due), last)
    }

    private data class Cadence(val due: Boolean, val last: Instant?)

    /** Why no automatic sync can start now, or null when one can. */
    private fun busyReason(): AutoSyncSkipReason? = when {
        sync.isSyncing.value -> AutoSyncSkipReason.ALREADY_SYNCING
        isMeasuring.value -> AutoSyncSkipReason.MEASURING
        else -> null
    }

    /**
     * Starts the [trigger]'s sync; returns whether a sync now serves it: this one started, or the
     * controller refused because another sync started since the check (it drains the same ring).
     * False when a measure refused it: the trigger is not served.
     */
    private fun start(trigger: AutoSyncTrigger, reason: String): Boolean {
        if (sync.syncNow()) {
            ownSync.set(true)
            log("sync-trigger: $reason")
            return true
        }
        // The controller refused: a sync or a measure started since the check.
        val why = busyReason() ?: AutoSyncSkipReason.ALREADY_SYNCING
        skipped(wallClock(), trigger, why)
        return why == AutoSyncSkipReason.ALREADY_SYNCING
    }

    /**
     * Keeps one skip in [skips] (the last [SKIPS_KEPT]). The same trigger skipped for the same
     * reason as the newest kept one replaces it, at the new time: a skip repeated on every
     * evaluation cannot push the others out.
     */
    private fun skipped(at: Instant, trigger: AutoSyncTrigger, reason: AutoSyncSkipReason, disconnected: Boolean = false) {
        val skip = AutoSyncSkip(at, trigger, reason, disconnected)
        skipsFlow.update { kept ->
            val newest = kept.lastOrNull()
            val base = if (newest != null && newest.trigger == trigger && newest.reason == reason) kept.dropLast(1) else kept
            (base + skip).takeLast(SKIPS_KEPT)
        }
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

        /** How many skips [skips] keeps. */
        const val SKIPS_KEPT: Int = 10
    }
}
