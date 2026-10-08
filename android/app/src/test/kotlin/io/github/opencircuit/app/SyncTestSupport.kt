package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.app.sync.SyncTriggerSources
import io.github.opencircuit.app.sync.learningNights
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.SleepStore
import java.time.Duration
import java.time.ZoneId
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.sync.CommitResult
import io.github.opencircuit.app.sync.HistoryStore
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.app.sync.SyncEvidence
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.CommitPlanner
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.HistoryJournal
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.time.ZoneOffset

/** The wall clock of every sync test: 2026-10-08T09:00:00Z at virtual time 0. */
internal val SYNC_TEST_EPOCH: Instant = Instant.parse("2026-10-08T09:00:00Z")

/** The ring's id in the store: its address, as the app keys it. */
internal const val TEST_RING_ID = "AA:BB:CC:DD:EE:FF"

/**
 * A [HistoryStore] over the real store that notes "commit returned" in the ring fake's own event
 * log ([note]) — so a test orders the commit against what the ring saw, even at the same virtual
 * instant. [failAppendsOf] makes the append of the matching pages throw (a full disk).
 */
internal class RecordingStore(private val real: HistoryStore, private val note: (String) -> Unit) : HistoryStore {
    var failAppendsOf: (ByteArray) -> Boolean = { false }

    /** Runs inside each append, before the page is written (a link drop while a page is being stored). */
    var duringAppend: suspend (ByteArray) -> Unit = {}

    override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long {
        if (failAppendsOf(page)) throw IllegalStateException("disk full")
        duringAppend(page)
        return real.append(page, receivedAt, drainId)
    }

    /** How far each commit was told the sync drained the ring, in order. */
    val drained = CopyOnWriteArrayList<CommitPlanner.Drained>()

    /** What each commit was told the sync's channels delivered, in order. */
    val evidence = CopyOnWriteArrayList<SyncEvidence>()

    override suspend fun commit(now: Instant, drained: CommitPlanner.Drained, keepGoing: () -> Boolean, evidence: SyncEvidence): CommitResult {
        this.drained += drained
        this.evidence += evidence
        val result = real.commit(now, drained, keepGoing, evidence)
        note("commit returned")
        return result
    }
}

/** One sync test's world: the real in-memory store, a ring fake, the session and the Ring screen's view model. */
internal class SyncWorld(
    val db: StoreDatabase,
    val ring: RingFake,
    val store: RecordingStore,
    val prefs: PrefsAppPrefs,
    val session: RingSessionController,
    val viewModel: RingViewModel,
    /** Every line the session logged, in order. */
    val logs: List<String>,
) {
    val journal = HistoryJournal(db)
    val blobs = BlobStore(db)
}

/**
 * Builds the session exactly as the app does (the session's history over the real store) and the
 * Ring screen's view model over it, on this test's virtual time. Close [SyncWorld.db] at the end.
 */
internal suspend fun TestScope.syncWorld(
    sleepPages: List<ByteArray>,
    endOfHistory: ByteArray? = RingFake.END_OF_HISTORY,
): SyncWorld = syncWorld { scope, now ->
    RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to sleepPages), endOfHistory = endOfHistory)
}

/**
 * As [syncWorld], with the ring fake built by [makeRing] on the test's background scope and virtual
 * clock; [chunkRecords] and [insideChunk] are passed to the store's commit (a slow commit).
 */
internal suspend fun TestScope.syncWorld(
    chunkRecords: Int = CommitPlanner.CHUNK_RECORDS,
    insideChunk: suspend (Int) -> Unit = {},
    /** Build the automatic syncs too, as the app does; off for the tests that drive Sync now alone. */
    triggers: Boolean = false,
    /** The app's preferences file (share one between two worlds for a relaunch). */
    keyValues: InMemoryKeyValues = InMemoryKeyValues(),
    /** The phone's time zone. */
    zone: ZoneId = ZoneOffset.UTC,
    /** The wall clock at virtual time 0. */
    wallStart: Instant = SYNC_TEST_EPOCH,
    makeRing: (CoroutineScope, () -> Long) -> RingFake,
): SyncWorld {
    // Queries on the test's own scheduler: none is still running on a real thread when the test
    // moves virtual time on (the drain's quiet timer would otherwise race the store).
    val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
    val wall = { wallStart.plusMillis(testScheduler.currentTime) }
    val ring = makeRing(backgroundScope) { testScheduler.currentTime }
    val store = RecordingStore(StoreHistory({ db }, TEST_RING_ID, { zone }, chunkRecords, insideChunk), ring::note)
    val prefs = PrefsAppPrefs(keyValues)
    val logs = CopyOnWriteArrayList<String>()
    // As the app's AppContainer.historyFor: the store's latest nights, the ring's marks in the preferences.
    val sources = if (triggers) {
        SyncTriggerSources(
            zone = { zone },
            storedNights = { learningNights(db) },
            marks = PrefsSyncMarks(keyValues, TEST_RING_ID),
        )
    } else {
        null
    }
    val session = RingSessionController(
        ring,
        backgroundScope,
        monotonicMillis = { testScheduler.currentTime },
        log = { logs += it },
        history = SessionHistory(store, wallClock = wall, disconnectAfterSync = { prefs.disconnectAfterSync }, triggers = sources),
    )
    val viewModel = RingViewModel(sessionsOf(session), "Ring", backgroundScope, prefs = prefs, wallClock = wall)
    return SyncWorld(db, ring, store, prefs, session, viewModel, logs)
}

/**
 * Stores one night per (onset, wake) pair through the real sleep store, keyed at the wake day's
 * local midnight in [zone] — as a staged night is.
 */
internal suspend fun SyncWorld.storeNights(zone: ZoneId, vararg nights: Pair<Instant, Instant>) {
    val store = SleepStore(db)
    for ((onset, wake) in nights) {
        val inBed = Duration.between(onset, wake)
        store.saveSleepSummary(
            SleepStaging.Summary(inBed = inBed, awake = Duration.ZERO, light = inBed, deep = Duration.ZERO, rem = Duration.ZERO),
            night = wake.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(),
            inBedStart = onset,
            inBedEnd = wake,
            sleepOnset = onset,
            sleepWake = wake,
            now = wake,
            zone = zone,
        )
    }
}

/**
 * When each sync opened the sleep channel's first round: the app's `02 00 <cursor> 00 01 00`
 * writes, as the ring saw them. A sync that drains the channel in one round writes one.
 */
internal fun SyncWorld.sleepOpens(): List<Long> =
    ring.writes.filter { it.hex.length == 18 && it.hex.startsWith("02") && it.hex.endsWith("000100") }.map { it.atMillis }

/** A ring holding [pages] on the sleep channel and nothing on the all-day one; both end with `0x50`. */
internal fun ringWith(pages: List<ByteArray>, endOfHistory: ByteArray? = RingFake.END_OF_HISTORY): (CoroutineScope, () -> Long) -> RingFake =
    { scope, now ->
        RingFake(scope, now, mapOf(Command.SYNC_CHANNEL_SLEEP to pages, Command.SYNC_CHANNEL_ALL_DAY to emptyList()), endOfHistory = endOfHistory)
    }

/** A worn `0x10` descriptor whose quarter-hour step bucket (`[4:6]`) holds 42 steps: the wearer is walking. */
internal val walkingDescriptor: ByteArray get() = hex("10420200002a0140013e000000000fa100ff00")
