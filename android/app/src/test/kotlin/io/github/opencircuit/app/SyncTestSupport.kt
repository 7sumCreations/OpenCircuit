package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsAppPrefs
import io.github.opencircuit.app.ring.RingViewModel
import io.github.opencircuit.app.session.RingSessionController
import io.github.opencircuit.app.sync.CommitResult
import io.github.opencircuit.app.sync.HistoryStore
import io.github.opencircuit.app.sync.SessionHistory
import io.github.opencircuit.app.sync.StoreHistory
import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.HistoryJournal
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.time.Instant
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

    override suspend fun append(page: ByteArray, receivedAt: Instant, drainId: Long?): Long {
        if (failAppendsOf(page)) throw IllegalStateException("disk full")
        return real.append(page, receivedAt, drainId)
    }

    override suspend fun commit(now: Instant): CommitResult {
        val result = real.commit(now)
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
): SyncWorld {
    // Queries on the test's own scheduler: none is still running on a real thread when the test
    // moves virtual time on (the drain's quiet timer would otherwise race the store).
    val db = StoreFactory.openInMemory(StandardTestDispatcher(testScheduler))
    val wall = { SYNC_TEST_EPOCH.plusMillis(testScheduler.currentTime) }
    val ring = RingFake(backgroundScope, { testScheduler.currentTime }, mapOf(Command.SYNC_CHANNEL_SLEEP to sleepPages), endOfHistory = endOfHistory)
    val store = RecordingStore(StoreHistory({ db }, TEST_RING_ID, { ZoneOffset.UTC }), ring::note)
    val prefs = PrefsAppPrefs(InMemoryKeyValues())
    val session = RingSessionController(
        ring,
        backgroundScope,
        monotonicMillis = { testScheduler.currentTime },
        log = {},
        history = SessionHistory(store, wallClock = wall, disconnectAfterSync = { prefs.disconnectAfterSync }),
    )
    val viewModel = RingViewModel(sessionsOf(session), "Ring", backgroundScope, prefs = prefs, wallClock = wall)
    return SyncWorld(db, ring, store, prefs, session, viewModel)
}
