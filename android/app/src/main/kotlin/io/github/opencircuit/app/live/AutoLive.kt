package io.github.opencircuit.app.live

import io.github.opencircuit.app.sync.AutoSyncSkip
import io.github.opencircuit.app.sync.AutoSyncSkipReason
import io.github.opencircuit.app.sync.AutoSyncTrigger
import io.github.opencircuit.app.sync.SyncReport
import io.github.opencircuit.app.sync.SyncState
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.AutoMeasureGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * The dashboard's live heart rate after a sync: a small policy over [LiveMeasureController] (the
 * one measure stack, the one frame collector), never a measure of its own.
 *
 * - **Trigger: a sync's end.** A NEW finished sync whose commit returned ([SyncReport.commit] not
 *   null) — the one `SyncState` update that ends a sync normally. An error or cancellation ends a
 *   sync with no new report and arms nothing. The trigger is the drain's END, so the link-up sync
 *   always runs first and live heart rate never holds it back. A sync that took an already-armed
 *   live heart rate over hands it back at its end however it ended (a failed commit, an error):
 *   this connection was caught up before it, and the next sync retries what it missed.
 * - **Never on the charger** ([onCharger]): a ring on its charger is not on a finger (the user's
 *   Measure refuses too); while it sits there the start is tried again every [BACKOFF_BASE].
 * - **Or a link-up with nothing due.** A link-up whose sync was skipped only because the last
 *   complete sync finished under the link-up throttle ago ([linkUpSkips]: `LINK_UP` + `THROTTLED`,
 *   the link kept) arms it the same way: a sync is due nowhere, so "sync first" is already met.
 *   Any other skip (the night, a sync or a measure running, a throttled link-up that closed the
 *   link) arms nothing.
 * - **Only while Today is on screen** ([todayVisible]): a sync that ends while it is not starts
 *   nothing yet; Today leaving the screen stops the automatic measure (a measure the user started
 *   is left alone). Today coming back on screen starts it again — on the held, authenticated link
 *   ([linkState]), once this connection has had a sync whose commit returned, and unless the user's
 *   Stop or a failure ended it since. A dropped link waits for the next connection's sync.
 * - **Never in the night** ([nightQuiet], the window the automatic syncs follow) and never during a
 *   sync or a user's measure: the controller refuses those too.
 * - **Live while visible:** at each 90 s budget end the measure starts again. A measure that ended
 *   with no lock counts toward the ported not-worn rule ([AutoMeasureGate]); once the ring seems
 *   off a finger the next start waits, doubling from [BACKOFF_BASE] up to [BACKOFF_CAP]. A lock
 *   resets it.
 * - **The user comes first:** a tap on Measure takes the automatic measure over (the controller's
 *   ownership rule); when the user's measure ends, heart rate starts again. Stop on the automatic
 *   measure, a lost link or a refused write ends it until the next sync. A sync that needs the link
 *   makes it stand down ([LiveMeasureController.yieldAuto]); that sync's end starts it again.
 *
 * Every decision runs in one collector, one event at a time.
 */
class AutoLive(
    private val measure: LiveMeasureController,
    private val sync: StateFlow<SyncState>,
    private val todayVisible: StateFlow<Boolean>,
    /** Whether the night is quiet now (`SyncTriggers.nightQuietNow`); false for a session with no automatic syncs. */
    private val nightQuiet: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    /** The latest raw skin temperature, °C, when known: a warm ring is worn ([AutoMeasureGate.appearsNotWorn]). */
    private val skinTemperatureC: () -> Double? = { null },
    /** The link: Today coming back starts live heart rate only on a held, authenticated one; a drop disarms it. */
    private val linkState: StateFlow<LinkState> = MutableStateFlow(LinkState.Authenticated),
    /** The automatic syncs' skips, oldest first (`SyncTriggers.skips`): a link-up throttled on a held link arms it. */
    private val linkUpSkips: StateFlow<List<AutoSyncSkip>> = MutableStateFlow(emptyList()),
    /** The ring's latest descriptor says it sits on its charger (`DeviceStatusState.onCharger`). */
    private val onCharger: () -> Boolean = { false },
) {
    private sealed interface Event {
        data class SyncEnded(val report: SyncReport) : Event
        /** A sync stopped running, with a report or without one (an unexpected error publishes none). */
        data object SyncStopped : Event
        data class Visible(val visible: Boolean) : Event
        data class Ended(val end: MeasureEnd) : Event
        /** The back-off numbered [id] ran out; one cancelled since (a newer [id]) is stale and ignored. */
        data class BackoffOver(val id: Long) : Event
        data object LinkLost : Event
        /** A link-up held its sync back only because the last complete one is under the throttle: nothing is due. */
        data object LinkUpThrottled : Event
    }

    private val backoffOver = Channel<Event>(Channel.CONFLATED)

    // Touched only by the one collector.
    /** This connection had a committed sync and neither the user's Stop nor a failure ended live heart rate since. */
    private var armed = false
    private var active = false
    private var noLock = 0
    private var backoff: Job? = null
    private var backoffId = 0L

    /** Starts following the syncs, Today's visibility and the measures. Called once, by the session's start. */
    fun start() {
        // A sync that ended before this start is not a new end.
        var seen = sync.value.last
        val syncEnds: Flow<Event> = sync.map { it.last }.filterNotNull().map { report ->
            // A new report only: the same one seen again (any other change of the state) is not a new end.
            if (report === seen) null else report.also { seen = it }
        }.filterNotNull().map { Event.SyncEnded(it) }
        val syncStops: Flow<Event> = sync.map { it.syncing }.distinctUntilChanged().drop(1).filter { !it }.map { Event.SyncStopped }
        val visibility: Flow<Event> = todayVisible.drop(1).map { Event.Visible(it) }
        val ends: Flow<Event> = measure.lastEnd.drop(1).filterNotNull().map { Event.Ended(it) }
        val linkLost: Flow<Event> = linkState.map { it == LinkState.Authenticated }.distinctUntilChanged()
            .filter { !it }.map { Event.LinkLost }
        // A skip kept before this start is not a new link-up; a repeated skip is kept as a new entry.
        // The list is a state, so several skips can land in one update: every entry not seen before
        // is looked at, not only the newest (a later skip must not hide a throttled link-up).
        var seenSkips = linkUpSkips.value
        val throttled: Flow<Event> = linkUpSkips.map { kept ->
            val fresh = kept.filter { entry -> seenSkips.none { it === entry } }
            seenSkips = kept
            fresh.any { it.trigger == AutoSyncTrigger.LINK_UP && it.reason == AutoSyncSkipReason.THROTTLED && !it.disconnected }
        }.filter { it }.map { Event.LinkUpThrottled }
        scope.launch { merge(syncEnds, syncStops, visibility, ends, linkLost, throttled, backoffOver.receiveAsFlow()).collect(::handle) }
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.SyncEnded -> onSyncEnded(event.report)
            // Already armed and wanted: the sync that made it stand down hands it back, committed or not
            // (a committed end also arms it afresh; whichever comes first starts it, the other finds it running).
            Event.SyncStopped -> if (armed && active && !measure.isMeasuring.value) tryStart()
            is Event.Visible -> if (event.visible) onShown() else onHidden()
            is Event.Ended -> onEnded(event.end)
            is Event.BackoffOver -> if (event.id == backoffId) {
                backoff = null
                // Ran out while Today was away: served; Today coming back starts at once.
                tryStart()
            }
            Event.LinkLost -> {
                armed = false
                active = false
                cancelBackoff()
            }
            Event.LinkUpThrottled -> arm("a link-up with no sync due")
        }
    }

    private suspend fun onSyncEnded(report: SyncReport) {
        // A paused sync is not finished: its resume's end decides.
        if (report.commit == null || report.paused) return
        arm("a sync ended")
    }

    /**
     * This connection is caught up — its sync committed, or its link-up found the last complete
     * sync under the throttle — so live heart rate may run on it.
     */
    private suspend fun arm(why: String) {
        // A sync that closed the link (or lost it) arms nothing: the next connection waits for its own link-up.
        if (linkState.value != LinkState.Authenticated) return
        armed = true
        // A fresh sync starts afresh, seen or not: no back-off carried over from before it.
        noLock = 0
        cancelBackoff()
        if (!todayVisible.value) {
            log("auto-live: $why while Today was not on screen; live heart rate waits for Today")
            return
        }
        active = true
        tryStart()
    }

    /** Today is back on screen: live heart rate again if this connection's sync armed it (the ruling's checks in [tryStart]). */
    private suspend fun onShown() {
        if (!armed || active) return
        if (linkState.value != LinkState.Authenticated) return
        active = true
        // A not-worn back-off still running is waited out (what is left of it), never restarted or skipped.
        if (backoff?.isActive == true) return
        tryStart()
    }

    private fun onHidden() {
        if (!active) return
        active = false
        // The back-off keeps running while Today is away.
        measure.yieldAuto()
    }

    private suspend fun onEnded(end: MeasureEnd) {
        // The user's Stop or a failure of the automatic measure holds until the next sync, even when seen after Today left.
        if (end.auto && (end.reason == MeasureEndReason.STOPPED || end.reason == MeasureEndReason.FAILED)) armed = false
        if (!active) return
        when {
            end.reason == MeasureEndReason.YIELDED -> Unit // a sync or the user took the link; what follows decides
            end.auto && end.reason == MeasureEndReason.FAILED -> stop("the measure failed")
            end.auto && end.reason == MeasureEndReason.STOPPED -> stop("the user stopped it")
            // The user's own measure ended, however (a failure of theirs too): back to heart rate.
            !end.auto -> tryStart()
            end.locked -> {
                noLock = 0
                tryStart()
            }
            else -> {
                noLock++
                startOrBackOff()
            }
        }
    }

    /** Starts at once, or — once the ring seems off a finger (the ported not-worn rule) — after its back-off. */
    private suspend fun startOrBackOff() {
        cancelBackoff()
        val temperature = skinTemperatureC()
        if (!AutoMeasureGate.appearsNotWorn(noLock, temperature)) {
            tryStart()
            return
        }
        val wait = AutoMeasureGate.interval(BACKOFF_BASE, BACKOFF_CAP, noLock, temperature)
        log("auto-live: no lock $noLock times; the ring seems off a finger, next try in ${wait.seconds} s")
        retryAfter(wait)
    }

    /** Tries [tryStart] again after [wait] (the back-off); one cancelled since is stale and ignored. */
    private fun retryAfter(wait: Duration) {
        val id = ++backoffId
        backoff = scope.launch {
            delay(wait.toMillis())
            backoffOver.send(Event.BackoffOver(id))
        }
    }

    private suspend fun tryStart() {
        if (!active || !todayVisible.value) return
        // No link, no measure: every write would be refused (a dropped link waits for the next connection's sync).
        if (linkState.value != LinkState.Authenticated) return
        // A user's measure runs: its end brings heart rate back.
        if (measure.isMeasuring.value) return
        if (onCharger()) {
            // Not on a finger; checked again after a heart-rate budget's wait, without touching the ring.
            if (backoff?.isActive != true) {
                log("auto-live: the ring is on its charger; next try in ${BACKOFF_BASE.seconds} s")
                retryAfter(BACKOFF_BASE)
            }
            return
        }
        if (nightQuiet()) {
            stop("the night is quiet")
            return
        }
        // Refused only while a sync runs: that sync's end starts it again.
        if (measure.startAuto(LiveMode.HEART_RATE)) log("auto-live: live heart rate started") else log("auto-live: not started, a sync is running")
    }

    private fun stop(why: String) {
        armed = false
        active = false
        cancelBackoff()
        log("auto-live: stopped until the next sync ($why)")
    }

    /** Cancels the running back-off; a run-out it already queued is stale from here on (a new id). */
    private fun cancelBackoff() {
        backoff?.cancel()
        backoff = null
        backoffId++
    }

    companion object {
        /** The first wait once the ring seems off a finger: one heart-rate budget. */
        val BACKOFF_BASE: Duration = Duration.ofSeconds(90)

        /** The longest wait: upstream's auto-measure interval (`RingSession.swift:1354` @ b1c2fdd, 600 s). */
        val BACKOFF_CAP: Duration = Duration.ofSeconds(600)
    }
}
