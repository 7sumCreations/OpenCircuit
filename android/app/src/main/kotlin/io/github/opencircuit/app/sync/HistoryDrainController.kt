package io.github.opencircuit.app.sync

import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ble.RingLink
import io.github.opencircuit.ble.SendResult
import io.github.opencircuit.ringkit.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/** How a sync ended. */
enum class SyncOutcome {
    /** The ring answered the open and reported the end of its history (`0x50` after `0x82`). */
    COMPLETE,

    /** Pages stopped with no end report: what came is stored; the rest is drained next time. */
    PARTIAL,

    /** A page could not be saved, so it was not acknowledged: the ring keeps it for the next sync. */
    SAVE_FAILED,

    /** The ring did not connect in time; nothing was asked of it. */
    NOT_CONNECTED,

    /** The link refused or failed the sync open. */
    OPEN_FAILED,

    /** The pages are kept on the phone, but moving them into the store failed; the next sync retries. */
    COMMIT_FAILED,
}

/** One finished sync, as the Ring data card shows it. */
data class SyncReport(
    /** When the sync finished. */
    val finishedAt: Instant,
    /** How it ended. */
    val outcome: SyncOutcome,
    /** What its commit stored; null when the commit failed. */
    val commit: CommitResult?,
)

/** The sync's state for the screen. */
data class SyncState(
    /** A sync is running. */
    val syncing: Boolean = false,
    /** Pages stored and acknowledged so far in the running sync. */
    val pagesThisSync: Int = 0,
    /** The last sync that finished in this session, or null before the first. */
    val last: SyncReport? = null,
)

/**
 * Drains the ring's history into the store, on request ([syncNow]): the sleep channel `0x00`.
 *
 * 1. Connects the ring if it is not connected ([connect]) and waits for the link to be
 *    authenticated, at most [CONNECT_TIMEOUT_MILLIS].
 * 2. Opens the channel: `02 00 <now as the sync cursor, 4 bytes big endian> 00 01 00`, then
 *    300 ms later `07 00 00`. No `01 00 00`: the link runs the ring's auth itself and refuses it
 *    (upstream writes one before each open, `ios/OpenCircuit/BLE/RingSession.swift:3939-3941` @
 *    b1c2fdd, on a hypothesis its own comment calls unsourced).
 * 3. Pages reach [pages] through the session's one collector and frame dispatcher; each is
 *    stored, then acknowledged, and the ring sends the next. The drain ends on the ring's `0x50`
 *    after its `0x82` ([SyncOutcome.COMPLETE]), on [QUIET_MILLIS] with nothing from the ring
 *    ([SyncOutcome.PARTIAL]), or on a page that could not be saved ([SyncOutcome.SAVE_FAILED]).
 *    An `0x50` before this channel's `0x82` is a status reply, not the end.
 * 4. Commits whatever was stored ([HistoryStore.commit]), whatever the outcome.
 * 5. With [disconnectAfterSync] on, closes the link — only after the commit has returned, so the
 *    link is never closed on data that is not in the store yet.
 *
 * One sync at a time: [syncNow] while one runs does nothing. Runs in [scope] on [wallClock] (the
 * sync cursor, the drain id, the stored times) and the scheduler's time (the waits).
 */
class HistoryDrainController(
    private val link: RingLink,
    private val connect: () -> Unit,
    private val pages: HistoryPages,
    private val store: HistoryStore,
    private val wallClock: () -> Instant,
    private val disconnectAfterSync: () -> Boolean,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    private val stateFlow = MutableStateFlow(SyncState())

    /** What the Ring data card shows. */
    val state: StateFlow<SyncState> = stateFlow.asStateFlow()

    /** Starts a sync; false (and nothing done) when one is already running. */
    fun syncNow(): Boolean {
        var started = false
        stateFlow.update { current ->
            started = !current.syncing
            if (started) current.copy(syncing = true, pagesThisSync = 0) else current
        }
        if (!started) return false
        scope.launch {
            val report = try {
                sync()
            } catch (e: CancellationException) {
                stateFlow.update { it.copy(syncing = false) }
                throw e
            }
            stateFlow.update { SyncState(syncing = false, pagesThisSync = it.pagesThisSync, last = report) }
        }
        return true
    }

    private suspend fun sync(): SyncReport {
        if (link.state.value != LinkState.Authenticated) {
            connect()
            val up = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { link.state.first { it == LinkState.Authenticated } }
            if (up == null) return commitAndReport(SyncOutcome.NOT_CONNECTED)
        }
        val drainId = wallClock().toEpochMilli()
        val signals = Channel<HistorySignal>(Channel.UNLIMITED)
        pages.attach(drainId) { signals.trySend(it) }
        val outcome = try {
            drainSleepChannel(signals)
        } finally {
            pages.detach(drainId)
        }
        return commitAndReport(outcome)
    }

    private suspend fun drainSleepChannel(signals: Channel<HistorySignal>): SyncOutcome {
        if (link.send(Command.syncUpToNow(wallClock(), Command.SYNC_CHANNEL_SLEEP)) != SendResult.Sent) return SyncOutcome.OPEN_FAILED
        delay(OPEN_TO_FETCH_MILLIS)
        if (link.send(Command.fetch) != SendResult.Sent) return SyncOutcome.OPEN_FAILED
        var answered = false
        while (true) {
            val signal = withTimeoutOrNull(QUIET_MILLIS) { signals.receive() } ?: return SyncOutcome.PARTIAL
            when (signal) {
                is HistorySignal.SyncAck -> answered = true
                is HistorySignal.PageStored -> stateFlow.update { it.copy(pagesThisSync = it.pagesThisSync + 1) }
                // Before this channel's 0x82 a 0x50 is the ring's answer to a status query.
                is HistorySignal.EndOfHistory -> if (answered) return SyncOutcome.COMPLETE
                HistorySignal.SaveFailed -> return SyncOutcome.SAVE_FAILED
                // The ring keeps the page and offers it again; it will not send the next one.
                is HistorySignal.AckFailed -> return SyncOutcome.PARTIAL
            }
        }
    }

    private suspend fun commitAndReport(outcome: SyncOutcome): SyncReport {
        val commit = try {
            store.commit(wallClock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("The sync's commit failed: ${e::class.java.simpleName}; the pages stay on the phone for the next sync")
            return SyncReport(wallClock(), SyncOutcome.COMMIT_FAILED, commit = null)
        }
        // Only after the commit returned: what the ring dropped is in the store by now.
        if (disconnectAfterSync()) link.disconnect()
        return SyncReport(wallClock(), outcome, commit)
    }

    companion object {
        /** From the sync open to `07 00 00` (upstream sleeps 300 ms after each write, `RingSession.swift:3939-3941`). */
        const val OPEN_TO_FETCH_MILLIS = 300L

        /** Nothing from the ring for this long ends the channel (Android-tuned; upstream counts ticks). */
        const val QUIET_MILLIS = 6_000L

        /** How long a sync waits for the ring to connect before giving up. */
        const val CONNECT_TIMEOUT_MILLIS = 30_000L
    }
}
