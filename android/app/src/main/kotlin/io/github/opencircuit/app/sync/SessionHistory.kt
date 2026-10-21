package io.github.opencircuit.app.sync

import io.github.opencircuit.app.data.SyncMarks
import io.github.opencircuit.app.live.LiveReadingStore
import io.github.opencircuit.ringkit.NightWindow
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** What a ring session needs to keep the ring's history: the store, the wall clock and the user's choice. */
class SessionHistory(
    /** Where pages are stored and committed. */
    val store: HistoryStore,
    /** The phone's wall clock: the sync cursor and the stored times. */
    val wallClock: () -> Instant,
    /**
     * Whether a sync's end closes the link: "Disconnect after syncing" ON and the app not in front
     * ([SyncLifecycle.disconnectAfterSync]); read when a sync's commit has returned.
     */
    val disconnectAfterSync: () -> Boolean,
    /** What the automatic syncs read ([SyncTriggers]); null: the session syncs only when asked. */
    val triggers: SyncTriggerSources? = null,
    /** The phone's time zone: the sync log's local days. */
    val zone: () -> ZoneId = { ZoneOffset.UTC },
    /** The debug build's sync test action ([SyncFaultInjector]); never cuts by default and in release. */
    val faults: SyncFaultInjector = SyncFaultInjector.NONE,
    /** After a sync's commit returned and its report is published: the Health Connect write ([HistoryDrainController]). */
    val afterCommit: (SyncReport) -> Unit = {},
    /** Where the readings measured live are kept (steps, skin temperature, a measure's result); nothing by default. */
    val live: LiveReadingStore = LiveReadingStore.NONE,
    /**
     * The "Disconnect after syncing" switch itself, whether or not the app is in front: a night
     * catch-up owed by a link the switch closed connects on its own whatever the app's state.
     */
    val disconnectSwitch: () -> Boolean = disconnectAfterSync,
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
