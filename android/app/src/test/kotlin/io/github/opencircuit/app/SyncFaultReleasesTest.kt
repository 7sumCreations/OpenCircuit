package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import io.github.opencircuit.ble.LinkState
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A sync that fails on an unexpected error (not a cancellation) still ends: the "syncing" flag is
 * released, so the keepalive, the live measure and the next sync are not held off for the rest of
 * the process. Here the night window's time zone throws while the sync resolves it.
 */
class SyncFaultReleasesTest {

    private val wallStart = Instant.parse("2026-10-08T14:00:00Z")

    @Test
    fun aSyncThatThrowsReleasesTheSyncingFlagAndTheNextSyncRuns() = runTest {
        val kv = InMemoryKeyValues()
        // A complete sync a minute before: the link-up itself does not sync.
        PrefsSyncMarks(kv, TEST_RING_ID).setLastCompleteSync(wallStart.minusSeconds(60))
        var boom = false
        val zone: () -> ZoneId = { if (boom) throw IllegalStateException("zone") else ZoneOffset.UTC }
        val w = syncWorld(triggers = true, keyValues = kv, wallStart = wallStart, triggerZone = zone, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(2_000)
            assertEquals(LinkState.Authenticated, w.session.state.value)
            assertEquals(emptyList(), w.sleepOpens())

            boom = true
            assertTrue(w.session.sync.syncNow())
            advanceTo(6_000)
            boom = false
            assertFalse(w.session.sync.isSyncing.value, "the failed sync released the flag")
            assertFalse(w.session.sync.state.value.syncing)

            assertTrue(w.session.sync.syncNow(), "a new sync can start")
            advanceTo(60_000)
            assertEquals(1, w.sleepOpens().size, "the new sync drained")
        } finally {
            w.db.close()
        }
    }
}
