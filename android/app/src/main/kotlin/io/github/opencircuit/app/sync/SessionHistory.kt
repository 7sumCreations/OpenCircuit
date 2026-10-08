package io.github.opencircuit.app.sync

import io.github.opencircuit.app.data.SyncMarks
import io.github.opencircuit.ringkit.NightWindow
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreDatabase
import java.time.Instant
import java.time.ZoneId

/** What a ring session needs to keep the ring's history: the store, the wall clock and the user's choice. */
class SessionHistory(
    /** Where pages are stored and committed. */
    val store: HistoryStore,
    /** The phone's wall clock: the sync cursor and the stored times. */
    val wallClock: () -> Instant,
    /** The "Disconnect after syncing" switch, read when a sync's commit has returned. */
    val disconnectAfterSync: () -> Boolean,
    /** What the automatic syncs read ([SyncTriggers]); null: the session syncs only when asked. */
    val triggers: SyncTriggerSources? = null,
) {
    companion object {
        /**
         * No history kept: every page is refused and so never acknowledged (the ring keeps them),
         * and a sync never disconnects. For sessions built without a store (the E8 tests).
         */
        val NONE = SessionHistory(HistoryStore.NONE, wallClock = { Instant.EPOCH }, disconnectAfterSync = { false })
    }
}

/**
 * What the automatic syncs read: the phone's time [zone] (the night is local), the [storedNights]
 * the window is learned from (the latest first, at most [NightWindow.LEARNING_NIGHTS]), and the
 * [marks] kept across launches.
 */
class SyncTriggerSources(
    val zone: () -> ZoneId,
    val storedNights: suspend () -> List<NightWindow.StoredNight>,
    val marks: SyncMarks,
)

/** The stored nights a window is learned from: the latest [NightWindow.LEARNING_NIGHTS] of [db], latest first. */
suspend fun learningNights(db: StoreDatabase): List<NightWindow.StoredNight> =
    SleepStore(db).recentSleepSummaries(NightWindow.LEARNING_NIGHTS).map { NightWindow.StoredNight(it.night, it.sleepOnset, it.sleepWake) }
