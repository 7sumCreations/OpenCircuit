package io.github.opencircuit.app.sync

import java.time.Instant

/** What a ring session needs to keep the ring's history: the store, the wall clock and the user's choice. */
class SessionHistory(
    /** Where pages are stored and committed. */
    val store: HistoryStore,
    /** The phone's wall clock: the sync cursor and the stored times. */
    val wallClock: () -> Instant,
    /** The "Disconnect after syncing" switch, read when a sync's commit has returned. */
    val disconnectAfterSync: () -> Boolean,
) {
    companion object {
        /**
         * No history kept: every page is refused and so never acknowledged (the ring keeps them),
         * and a sync never disconnects. For sessions built without a store (the E8 tests).
         */
        val NONE = SessionHistory(HistoryStore.NONE, wallClock = { Instant.EPOCH }, disconnectAfterSync = { false })
    }
}
