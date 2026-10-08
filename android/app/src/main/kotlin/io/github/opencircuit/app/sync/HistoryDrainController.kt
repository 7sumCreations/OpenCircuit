package io.github.opencircuit.app.sync

import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.AndroidDrainTiming
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.DrainBudget
import io.github.opencircuit.ringkit.DrainContinuation
import io.github.opencircuit.ringkit.DrainProgress
import io.github.opencircuit.ringkit.HistoryChannelExitReason
import io.github.opencircuit.ringkit.HistoryChannelOutcome
import io.github.opencircuit.ringkit.HistoryChannelTrace
import io.github.opencircuit.ringkit.HistoryChannelVerdict
import io.github.opencircuit.ringkit.HistoryDrainPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** How a sync ended. */
enum class SyncOutcome {
    /** Every channel ended cleanly: the ring's `0x50` after its `0x82`, or nothing to send. */
    COMPLETE,

    /** Pages stopped with no end report, or the sync was cut short: what came is stored; the rest is drained next time. */
    PARTIAL,

    /** A page could not be saved, so it was not acknowledged: the ring keeps it for the next sync. */
    SAVE_FAILED,

    /** The ring did not connect in time; nothing was asked of it. */
    NOT_CONNECTED,

    /** The link refused or failed a sync open. */
    OPEN_FAILED,

    /** The pages are kept on the phone, but moving them into the store failed; the next sync retries. */
    COMMIT_FAILED,

    /** The ring answered no channel's open, even after the link's re-auth. */
    NO_ACK,
}

/** Whether a channel needed the open's one fallback (PORTING.md D-264). */
enum class OpenFallback {
    /** The ring answered the open. */
    NONE,

    /** Nothing within 5 s of the open: the link re-ran the ring's auth and the open was sent again. */
    REAUTH,
}

/**
 * One channel of one sync: its reopen rounds and what they saw. Counts and flags only, never a
 * record; the `0x82` answers are kept byte for byte ([syncAcks], plain hex) because their bytes
 * are the protocol's own status, not the wearer's data.
 */
data class ChannelReport(
    /** `sleep` or `all-day` ([HistoryDrainPlan.Step.label]). */
    val label: String,
    /** The wire selector byte. */
    val channel: Int,
    /** Each round's trace, oldest first (independent copies). */
    val rounds: List<HistoryChannelTrace>,
    /** The channel's verdict over its rounds ([HistoryChannelVerdict]); null with no round. */
    val verdict: HistoryChannelOutcome?,
    /** Every `0x82` the ring answered this channel with, in order, as plain hex. */
    val syncAcks: List<String>,
    /** Whether the open needed the re-auth fallback. */
    val openFallback: OpenFallback,
    /** With [OpenFallback.REAUTH]: whether the ring answered after it; null without it. */
    val fallbackHelped: Boolean?,
    /** The first `0x4c` page's 16-bit countdown, or null when no `0x4c` page came. */
    val firstCountdown: Int?,
    /** The latest `0x4c` page's countdown, or null. */
    val lastCountdown: Int?,
    /** New `0x4c` records this channel delivered (unique counters, re-sent ones not counted again). */
    val records: Int,
    /** `0x50` frames that came before this channel's `0x82`: answers to a status query, not the end. */
    val statusReplies: Int,
)

/** One finished sync, as the Ring data card shows it. */
data class SyncReport(
    /** When the sync finished. */
    val finishedAt: Instant,
    /** How it ended. */
    val outcome: SyncOutcome,
    /** What its commit stored; null when the commit failed. */
    val commit: CommitResult?,
    /** Each channel it drained, in order. */
    val channels: List<ChannelReport> = emptyList(),
)

/**
 * How far one channel of the running sync has come ([io.github.opencircuit.ringkit.DrainProgress]):
 * [records] new `0x4c` records so far, [expected] that plus the latest page's countdown (null
 * before a page), [etaSeconds] at the record rate since the channel's first page (null until a
 * second page), [done] once the channel has ended.
 */
data class ChannelProgress(
    val label: String,
    val records: Int,
    val expected: Int?,
    val etaSeconds: Long?,
    val done: Boolean,
)

/** The sync's state for the screen. */
data class SyncState(
    /** A sync is running. */
    val syncing: Boolean = false,
    /** Pages stored and acknowledged so far in the running sync. */
    val pagesThisSync: Int = 0,
    /** The last sync that finished in this session, or null before the first. */
    val last: SyncReport? = null,
    /** The running sync's channels so far, in order; empty when no sync runs. */
    val channels: List<ChannelProgress> = emptyList(),
)

/**
 * Drains the ring's history into the store, on request ([syncNow]): both history channels, in
 * the foreground order of [HistoryDrainPlan] — `0x00` sleep, then `0x03` all-day (upstream
 * `performHistoryDrain` / `drainChannel`, `ios/OpenCircuit/BLE/RingSession.swift:3611-3787,
 * 3908-4105` @ b1c2fdd, timed in seconds on a monotonic clock instead of upstream's 1 s ticks,
 * [AndroidDrainTiming], PORTING.md D-265).
 *
 * 1. Connects the ring if it is not connected ([connect]) and waits for the link to be
 *    authenticated, at most [CONNECT_TIMEOUT_MILLIS]; then waits for any status query still
 *    unanswered ([awaitStatusQuiet]).
 * 2. Each channel round opens with `02 00 <now as the sync cursor> <channel> 01 00`, then 300 ms
 *    later `07 00 00`. No `01 00 00` (PORTING.md D-262). If neither the `0x82` answer nor a page
 *    comes within 5 s, the link re-runs the ring's auth ([RingLink.reauthenticate], D-264) and the
 *    open is sent again — once per sync; nothing at all 20 s after the open ends the channel
 *    unanswered (NO_ACK).
 * 3. Pages reach [pages] through the session's one collector and frame dispatcher; each is
 *    stored, then acknowledged, and the ring sends the next. A round ends on the ring's `0x50`
 *    after this round's `0x82` (a `0x50` before it is a status reply), 6 s after an `82 ff`
 *    "nothing to send", 20 s after an answered open with no page, or 6 s of quiet after a page —
 *    the first quiet earns one `07 00 00` nudge ([DrainContinuation.shouldNudge]). A round still
 *    receiving pages runs 45 s, extended 45 s at a time to 3,600 s ([DrainBudget]). A round that
 *    went quiet after adding records is reopened, up to 12 times ([DrainContinuation.shouldReopen]).
 * 4. The whole sync stops at 90 min; a page that could not be saved, an acknowledgement that did
 *    not go out, a refused open or a lost link stops it at once. Whatever was stored is committed
 *    ([HistoryStore.commit]), whatever the outcome; each channel's verdict follows D-43
 *    ([HistoryChannelVerdict]) and each round is logged as a `history-drain` row (counts only).
 * 5. With [disconnectAfterSync] on, closes the link — only after the commit has returned.
 *
 * One sync at a time, and none while a live measure runs ([isMeasuring]): [syncNow] then does
 * nothing. [isSyncing] is true from [syncNow] until the sync's report is published. Runs in
 * [scope] on [wallClock] (the sync cursor, the drain id, the stored times) and [monotonicMillis]
 * (every wait, on the scheduler's time).
 */
class HistoryDrainController(
    private val link: RingLink,
    private val connect: () -> Unit,
    private val pages: HistoryPages,
    private val store: HistoryStore,
    private val wallClock: () -> Instant,
    private val monotonicMillis: () -> Long,
    private val disconnectAfterSync: () -> Boolean,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    /** True while a live measure runs: no sync starts then. */
    private val isMeasuring: () -> Boolean = { false },
    /** Returns once no status query (`d0 00 00`) is waiting for its answer, or after at most 2 s. */
    private val awaitStatusQuiet: suspend () -> Unit = {},
) {
    private val stateFlow = MutableStateFlow(SyncState())
    private val syncingFlow = MutableStateFlow(false)

    /** What the Ring data card shows. */
    val state: StateFlow<SyncState> = stateFlow.asStateFlow()

    /** True from [syncNow] until the sync's report is published: the keepalive and the live measure stay quiet meanwhile. */
    val isSyncing: StateFlow<Boolean> = syncingFlow.asStateFlow()

    /** Starts a sync; false (and nothing done) when one is already running or a live measure runs. */
    fun syncNow(): Boolean {
        if (isMeasuring()) {
            log("Sync now not started: a live measure is running")
            return false
        }
        var started = false
        stateFlow.update { current ->
            started = !current.syncing
            if (started) current.copy(syncing = true, pagesThisSync = 0, channels = emptyList()) else current
        }
        if (!started) return false
        syncingFlow.value = true
        scope.launch {
            val report = try {
                sync()
            } catch (e: CancellationException) {
                stateFlow.update { it.copy(syncing = false, channels = emptyList()) }
                syncingFlow.value = false
                throw e
            }
            stateFlow.update { SyncState(syncing = false, pagesThisSync = it.pagesThisSync, last = report) }
            syncingFlow.value = false
        }
        return true
    }

    private suspend fun sync(): SyncReport {
        if (link.state.value != LinkState.Authenticated) {
            connect()
            val up = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { link.state.first { it == LinkState.Authenticated } }
            if (up == null) return commitAndReport(SyncOutcome.NOT_CONNECTED, emptyList())
        }
        awaitStatusQuiet()
        val plan = HistoryDrainPlan.steps(inBackground = false, allDayOnly = false, sportEnabled = false, now = wallClock(), nightWindowEnd = null)
        val run = SyncRun(deadline = monotonicMillis() + AndroidDrainTiming.WHOLE_SYNC.toMillis())
        val drainId = wallClock().toEpochMilli()
        val signals = Channel<HistorySignal>(Channel.UNLIMITED)
        pages.attach(drainId) { signals.trySend(it) }
        try {
            coroutineScope {
                val watcher = launch {
                    link.state.first { it != LinkState.Authenticated }
                    signals.trySend(HistorySignal.LinkDown)
                }
                for (step in plan) {
                    if (link.state.value != LinkState.Authenticated) {
                        run.linkLost = true
                        break
                    }
                    run.reports += drainChannel(step, signals, run)
                    if (run.stopped) break
                }
                watcher.cancel()
            }
        } finally {
            pages.detach(drainId)
        }
        return commitAndReport(run.outcome(plan.size), run.reports)
    }

    /** One channel: rounds until one ends without earning a reopen. */
    private suspend fun drainChannel(step: HistoryDrainPlan.Step, signals: Channel<HistorySignal>, run: SyncRun): ChannelReport {
        val channel = ChannelRun(step, index = stateFlow.value.channels.size)
        stateFlow.update { it.copy(channels = it.channels + channel.progress()) }
        var round = 0
        while (true) {
            val before = run.counters.size
            val trace = runRound(step, round, signals, run, channel)
            channel.rounds += trace
            log(drainRow(trace, channel))
            if (run.stopped) break
            val gained = run.counters.size - before
            val reopen = DrainContinuation.shouldReopen(
                exitReason = trace.exitReason,
                recordsAdded = gained,
                round = round,
                inBackground = false,
                backgroundTimeRemaining = Duration.ZERO,
            )
            if (!reopen) break
            round++
        }
        channel.done = true
        publish(channel)
        return channel.report()
    }

    /** Puts [channel]'s progress on the screen's state. */
    private fun publish(channel: ChannelRun) {
        stateFlow.update { state ->
            state.copy(channels = state.channels.mapIndexed { i, p -> if (i == channel.index) channel.progress() else p })
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runRound(
        step: HistoryDrainPlan.Step,
        round: Int,
        signals: Channel<HistorySignal>,
        run: SyncRun,
        channel: ChannelRun,
    ): HistoryChannelTrace {
        val trace = HistoryChannelTrace(step.label, step.channel, wallClock())
        trace.reopenRound = round
        trace.recordsAtStart = run.counters.size
        fun finish(reason: HistoryChannelExitReason): HistoryChannelTrace {
            trace.finishedAt = wallClock()
            trace.recordsAtEnd = run.counters.size
            trace.exitReason = reason
            return trace
        }

        val openAt = monotonicMillis()
        if (!open(step.channel)) {
            trace.openWriteFailed = true
            run.openFailed = true
            return finish(HistoryChannelExitReason.LINK_UNUSABLE)
        }
        var answered = false
        var ackAt = 0L
        var sawPages = false
        var quietFrom = 0L
        var cap = AndroidDrainTiming.NOMINAL_CAP.toMillis()
        var nudgesWithoutProgress = 0
        var nudgesThisRound = 0
        var uniqueAtLastNudge = -1
        var fellBack = false

        while (true) {
            val deadline = when {
                sawPages -> minOf(quietFrom + QUIET, openAt + cap)
                !answered && !run.reauthUsed -> openAt + OPEN_ANSWER
                !answered -> openAt + NO_ANSWER
                trace.sawEmptyHistorySignal -> ackAt + QUIET
                else -> openAt + EMPTY_NO_PAGES
            }.coerceAtMost(run.deadline)
            // select, not withTimeout around receive: a timeout can never take a signal with it.
            val signal = select<HistorySignal?> {
                signals.onReceive { it }
                onTimeout((deadline - monotonicMillis()).coerceAtLeast(0)) { null }
            }
            val now = monotonicMillis()
            if (signal != null) {
                when (signal) {
                    is HistorySignal.SyncAck -> {
                        val frame = signal.frame
                        channel.syncAcks += frame.toPlainHex()
                        trace.noteOpcode(SYNC_ACK)
                        trace.sawSyncAck = true
                        trace.syncAckFlag = if (frame.size > 2) frame.u8(2) else null
                        // byte 1 = ff: the ring's pointer is already at the end (upstream RingSession.swift:4630-4635).
                        if (frame.size > 1 && frame.u8(1) == 0xFF) trace.sawEmptyHistorySignal = true
                        if (!answered) {
                            answered = true
                            ackAt = now
                        }
                        if (fellBack) channel.fallbackHelped = true
                    }
                    is HistorySignal.PageStored -> {
                        trace.noteOpcode(signal.opcode)
                        when (signal.opcode) {
                            PAGE_4C -> trace.page4CCount++
                            PAGE_47 -> trace.page47Count++
                            PAGE_4D -> trace.page4DCount = (trace.page4DCount ?: 0) + 1
                        }
                        sawPages = true
                        quietFrom = now
                        if (fellBack) channel.fallbackHelped = true
                        val added = signal.counters.count { run.counters.add(it) }
                        stateFlow.update { it.copy(pagesThisSync = it.pagesThisSync + 1) }
                        if (signal.opcode == PAGE_4C) {
                            channel.onEpochPage(signal.countdown, added, now)
                            publish(channel)
                        }
                    }
                    is HistorySignal.EndOfHistory -> {
                        // Before this round's 0x82 a 0x50 is the ring's answer to a status query.
                        if (!answered) {
                            channel.statusReplies++
                        } else {
                            trace.noteOpcode(END_OF_HISTORY)
                            trace.endMarkerCount++
                            return finish(HistoryChannelExitReason.END_MARKER)
                        }
                    }
                    HistorySignal.SaveFailed -> {
                        run.saveFailed = true
                        return finish(HistoryChannelExitReason.CANCELLED)
                    }
                    // The ring keeps the page and offers it again; it will not send the next one.
                    is HistorySignal.AckFailed -> {
                        run.ackFailed = true
                        return finish(if (link.state.value == LinkState.Authenticated) HistoryChannelExitReason.CANCELLED else HistoryChannelExitReason.LINK_UNUSABLE)
                    }
                    HistorySignal.LinkDown -> {
                        run.linkLost = true
                        if (!sawPages && !answered) trace.openWriteFailed = true
                        return finish(HistoryChannelExitReason.LINK_UNUSABLE)
                    }
                }
                continue
            }

            when {
                now >= run.deadline -> {
                    run.timedOut = true
                    return finish(if (sawPages) HistoryChannelExitReason.HARD_TIMEOUT else HistoryChannelExitReason.QUIET_NO_PAGES)
                }
                !sawPages && !answered && !run.reauthUsed && now >= openAt + OPEN_ANSWER -> {
                    // The open's one fallback of the sync (PORTING.md D-264): the link's re-auth, then the open again.
                    run.reauthUsed = true
                    fellBack = true
                    channel.openFallback = OpenFallback.REAUTH
                    channel.fallbackHelped = false
                    val result = link.reauthenticate()
                    if (result != SendResult.Sent) log("history-drain: the link's re-auth did not succeed ($result); opening again")
                    if (!open(step.channel)) {
                        trace.openWriteFailed = true
                        run.openFailed = true
                        return finish(HistoryChannelExitReason.LINK_UNUSABLE)
                    }
                }
                !sawPages && !answered && now >= openAt + NO_ANSWER -> return finish(HistoryChannelExitReason.QUIET_NO_PAGES)
                !sawPages && answered && trace.sawEmptyHistorySignal && now >= ackAt + QUIET -> return finish(HistoryChannelExitReason.QUIET_NO_PAGES)
                !sawPages && answered && now >= openAt + EMPTY_NO_PAGES -> return finish(HistoryChannelExitReason.QUIET_NO_PAGES)
                sawPages && now >= quietFrom + QUIET -> {
                    val unique = run.counters.size
                    if (uniqueAtLastNudge >= 0 && unique > uniqueAtLastNudge) nudgesWithoutProgress = 0
                    val nudge = DrainContinuation.shouldNudge(
                        sawPages = true,
                        sawEndMarker = false,
                        nudgesWithoutProgress = nudgesWithoutProgress,
                        nudgesThisRound = nudgesThisRound,
                        // Ticks read as seconds: the 8-tick headroom is 8 s before the ceiling.
                        tick = ((now - openAt) / 1_000).toInt(),
                        ceiling = AndroidDrainTiming.CEILING.seconds.toInt(),
                        inBackground = false,
                        backgroundTimeRemaining = Duration.ZERO,
                    )
                    if (!nudge || link.send(Command.fetch) != SendResult.Sent) return finish(HistoryChannelExitReason.QUIET_AFTER_PAGES)
                    nudgesWithoutProgress++
                    nudgesThisRound++
                    uniqueAtLastNudge = unique
                    quietFrom = monotonicMillis()
                    trace.fetchNudges = (trace.fetchNudges ?: 0) + 1
                }
                sawPages && now >= openAt + cap -> {
                    // Still streaming (the quiet exit above did not fire): extend, up to the ceiling.
                    val extend = DrainBudget.shouldExtend(
                        tick = (now - openAt).toInt(),
                        cap = cap.toInt(),
                        ceiling = CEILING.toInt(),
                        sawPages = true,
                        quietTicks = (now - quietFrom).toInt(),
                        quietExitThreshold = QUIET.toInt(),
                    )
                    if (!extend) return finish(HistoryChannelExitReason.HARD_TIMEOUT)
                    cap = DrainBudget.extendedCap(cap.toInt(), STEP.toInt(), CEILING.toInt()).toLong()
                }
            }
        }
    }

    /** `02 00 <cursor> <channel> 01 00`, 300 ms, `07 00 00`; false when the link did not write either. */
    private suspend fun open(channel: Int): Boolean {
        if (link.send(Command.syncUpToNow(wallClock(), channel)) != SendResult.Sent) return false
        delay(AndroidDrainTiming.OPEN_TO_FETCH.toMillis())
        return link.send(Command.fetch) == SendResult.Sent
    }

    private suspend fun commitAndReport(outcome: SyncOutcome, channels: List<ChannelReport>): SyncReport {
        val commit = try {
            store.commit(wallClock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("The sync's commit failed: ${e::class.java.simpleName}; the pages stay on the phone for the next sync")
            return SyncReport(wallClock(), SyncOutcome.COMMIT_FAILED, commit = null, channels = channels)
        }
        // Only after the commit returned: what the ring dropped is in the store by now.
        if (disconnectAfterSync()) link.disconnect()
        return SyncReport(wallClock(), outcome, commit, channels)
    }

    /** One `history-drain` row per round: counts and flags only, never a frame's bytes. */
    private fun drainRow(trace: HistoryChannelTrace, channel: ChannelRun): String =
        "history-drain label=${trace.label} round=${trace.reopenRound ?: 0} outcome=${trace.outcome.rawValue} " +
            "exit=${trace.exitReason?.rawValue} ack=${trace.sawSyncAck} flag=${trace.syncAckFlag ?: "-"} " +
            "empty=${trace.sawEmptyHistorySignal} 4c=${trace.page4CCount} 47=${trace.page47Count} 4d=${trace.page4DCount ?: 0} " +
            "50=${trace.endMarkerCount} added=${trace.recordsAdded} nudges=${trace.fetchNudges ?: 0} " +
            "fallback=${channel.openFallback.name.lowercase(Locale.ROOT)} countdown=${channel.firstCountdown ?: "-"}..${channel.lastCountdown ?: "-"} " +
            "statusReplies=${channel.statusReplies}"

    /** What one sync has done so far. Touched only by the sync's own coroutine. */
    private class SyncRun(val deadline: Long) {
        val counters = HashSet<Long>()
        val reports = mutableListOf<ChannelReport>()
        var reauthUsed = false
        var saveFailed = false
        var openFailed = false
        var ackFailed = false
        var linkLost = false
        var timedOut = false

        val stopped: Boolean get() = saveFailed || openFailed || ackFailed || linkLost || timedOut

        fun outcome(planned: Int): SyncOutcome = when {
            saveFailed -> SyncOutcome.SAVE_FAILED
            openFailed -> SyncOutcome.OPEN_FAILED
            reports.isNotEmpty() && reports.all { it.verdict == HistoryChannelOutcome.NO_ACK } -> SyncOutcome.NO_ACK
            !stopped && reports.size == planned &&
                reports.all { it.verdict == HistoryChannelOutcome.COMPLETE || it.verdict == HistoryChannelOutcome.EMPTY } -> SyncOutcome.COMPLETE
            else -> SyncOutcome.PARTIAL
        }
    }

    /** What one channel has seen over its rounds. Touched only by the sync's own coroutine. */
    private class ChannelRun(val step: HistoryDrainPlan.Step, val index: Int) {
        val rounds = mutableListOf<HistoryChannelTrace>()
        val syncAcks = mutableListOf<String>()
        var openFallback = OpenFallback.NONE
        var fallbackHelped: Boolean? = null
        var firstCountdown: Int? = null
        var lastCountdown: Int? = null
        var records = 0
        var statusReplies = 0
        var done = false
        private var firstPageAt: Long? = null
        private var firstPageRecords = 0
        private var lastPageAt = 0L

        /** A `0x4c` page with [added] new records and its [countdown] arrived at monotonic [at]. */
        fun onEpochPage(countdown: Int?, added: Int, at: Long) {
            records += added
            if (firstPageAt == null) {
                firstPageAt = at
                firstPageRecords = added
            }
            lastPageAt = at
            if (countdown != null) {
                if (firstCountdown == null) firstCountdown = countdown
                lastCountdown = countdown
            }
        }

        fun progress(): ChannelProgress {
            val estimate = DrainProgress.of(records, lastCountdown, firstPageRecords, firstPageAt ?: lastPageAt, lastPageAt)
            return ChannelProgress(step.label, records, estimate.expected, estimate.etaSeconds, done)
        }

        fun report() = ChannelReport(
            label = step.label,
            channel = step.channel,
            rounds = rounds.map { it.copy() },
            verdict = HistoryChannelVerdict.of(rounds),
            syncAcks = syncAcks.toList(),
            openFallback = openFallback,
            fallbackHelped = fallbackHelped,
            firstCountdown = firstCountdown,
            lastCountdown = lastCountdown,
            records = records,
            statusReplies = statusReplies,
        )
    }

    companion object {
        /** How long a sync waits for the ring to connect before giving up. */
        const val CONNECT_TIMEOUT_MILLIS = 30_000L

        private val QUIET = AndroidDrainTiming.QUIET.toMillis()
        private val OPEN_ANSWER = AndroidDrainTiming.OPEN_ANSWER.toMillis()
        private val NO_ANSWER = AndroidDrainTiming.NO_ANSWER.toMillis()
        private val EMPTY_NO_PAGES = AndroidDrainTiming.EMPTY_NO_PAGES.toMillis()
        private val CEILING = AndroidDrainTiming.CEILING.toMillis()
        private val STEP = AndroidDrainTiming.EXTEND_STEP.toMillis()

        private const val PAGE_47 = 0x47
        private const val PAGE_4C = 0x4C
        private const val PAGE_4D = 0x4D
        private const val SYNC_ACK = 0x82
        private const val END_OF_HISTORY = 0x50

        private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

        private fun ByteArray.toPlainHex(): String = joinToString("") { b -> "%02x".format(Locale.ROOT, b.toInt() and 0xFF) }

        /** The first and latest opcode a round saw (upstream `updateActiveDrainTrace`). */
        private fun HistoryChannelTrace.noteOpcode(opcode: Int) {
            if (firstOpcode == null) firstOpcode = opcode
            lastOpcode = opcode
        }
    }
}
