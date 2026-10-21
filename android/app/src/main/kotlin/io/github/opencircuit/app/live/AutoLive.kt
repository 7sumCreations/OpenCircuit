package io.github.opencircuit.app.live

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
 *   sync with no new report and starts nothing. The trigger is the drain's END, so the link-up sync
 *   always runs first and live heart rate never holds it back.
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
) {
    private sealed interface Event {
        data class SyncEnded(val report: SyncReport) : Event
        data class Visible(val visible: Boolean) : Event
        data class Ended(val end: MeasureEnd) : Event
        data object BackoffOver : Event
        data object LinkLost : Event
    }

    private val backoffOver = Channel<Event>(Channel.CONFLATED)

    // Touched only by the one collector.
    /** This connection had a committed sync and neither the user's Stop nor a failure ended live heart rate since. */
    private var armed = false
    private var active = false
    private var noLock = 0
    private var backoff: Job? = null

    /** Starts following the syncs, Today's visibility and the measures. Called once, by the session's start. */
    fun start() {
        // A sync that ended before this start is not a new end.
        var seen = sync.value.last
        val syncEnds: Flow<Event> = sync.map { it.last }.filterNotNull().map { report ->
            // A new report only: the same one seen again (any other change of the state) is not a new end.
            if (report === seen) null else report.also { seen = it }
        }.filterNotNull().map { Event.SyncEnded(it) }
        val visibility: Flow<Event> = todayVisible.drop(1).map { Event.Visible(it) }
        val ends: Flow<Event> = measure.lastEnd.drop(1).filterNotNull().map { Event.Ended(it) }
        val linkLost: Flow<Event> = linkState.map { it == LinkState.Authenticated }.distinctUntilChanged()
            .filter { !it }.map { Event.LinkLost }
        scope.launch { merge(syncEnds, visibility, ends, linkLost, backoffOver.receiveAsFlow()).collect(::handle) }
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.SyncEnded -> onSyncEnded(event.report)
            is Event.Visible -> if (event.visible) onShown() else onHidden()
            is Event.Ended -> onEnded(event.end)
            Event.BackoffOver -> tryStart()
            Event.LinkLost -> {
                armed = false
                active = false
                cancelBackoff()
            }
        }
    }

    private suspend fun onSyncEnded(report: SyncReport) {
        if (report.commit == null) return
        armed = true
        if (!todayVisible.value) {
            log("auto-live: a sync ended while Today was not on screen; live heart rate waits for Today")
            return
        }
        active = true
        noLock = 0
        cancelBackoff()
        tryStart()
    }

    /** Today is back on screen: live heart rate again if this connection's sync armed it (the ruling's checks in [tryStart]). */
    private suspend fun onShown() {
        if (!armed || active) return
        if (linkState.value != LinkState.Authenticated) return
        active = true
        noLock = 0
        cancelBackoff()
        tryStart()
    }

    private fun onHidden() {
        if (!active) return
        active = false
        cancelBackoff()
        measure.yieldAuto()
    }

    private suspend fun onEnded(end: MeasureEnd) {
        // The user's Stop or a failure of the automatic measure holds until the next sync, even when seen after Today left.
        if (end.auto && (end.reason == MeasureEndReason.STOPPED || end.reason == MeasureEndReason.FAILED)) armed = false
        if (!active) return
        when {
            end.reason == MeasureEndReason.YIELDED -> Unit // a sync or the user took the link; what follows decides
            end.reason == MeasureEndReason.FAILED -> stop("the measure failed")
            end.auto && end.reason == MeasureEndReason.STOPPED -> stop("the user stopped it")
            !end.auto -> tryStart() // the user's own measure ended: back to heart rate
            end.locked -> {
                noLock = 0
                tryStart()
            }
            else -> {
                noLock++
                val temperature = skinTemperatureC()
                if (AutoMeasureGate.appearsNotWorn(noLock, temperature)) {
                    val wait = AutoMeasureGate.interval(BACKOFF_BASE, BACKOFF_CAP, noLock, temperature)
                    log("auto-live: no lock $noLock times; the ring seems off a finger, next try in ${wait.seconds} s")
                    backoff = scope.launch {
                        delay(wait.toMillis())
                        backoffOver.send(Event.BackoffOver)
                    }
                } else {
                    tryStart()
                }
            }
        }
    }

    private suspend fun tryStart() {
        if (!active || !todayVisible.value) return
        // A user's measure runs: its end brings heart rate back.
        if (measure.isMeasuring.value) return
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

    private fun cancelBackoff() {
        backoff?.cancel()
        backoff = null
    }

    companion object {
        /** The first wait once the ring seems off a finger: one heart-rate budget. */
        val BACKOFF_BASE: Duration = Duration.ofSeconds(90)

        /** The longest wait: upstream's auto-measure interval (`RingSession.swift:1354` @ b1c2fdd, 600 s). */
        val BACKOFF_CAP: Duration = Duration.ofSeconds(600)
    }
}
