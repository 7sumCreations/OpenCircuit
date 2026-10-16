package io.github.opencircuit.app

import io.github.opencircuit.app.ring.RingAction
import io.github.opencircuit.health.HeartRateSeries
import io.github.opencircuit.health.HrHours
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.store.BlobStore
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.codec.Decoded
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tracer: Sync now on the Ring screen → the ring-faithful fake's pages → the real store's
 * commit → the post-commit hook → the flush on the app's scope → the writer → a Health Connect
 * that enforces the platform's rules. Asserted on what Health Connect holds against what the store
 * holds, on the store's own marks, and on the calls Health Connect saw.
 */
class HealthWriterTracerTest {

    private val june = Instant.parse("2026-06-01T00:00:00Z")
    private val july = Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun aSyncedNightsHeartRateReachesHealthConnectAsHourRecordsAndASecondSyncWritesNothingAgain() = runTest {
        val h = healthWorld()
        val w = syncWorld(reuseDb = h.db, afterCommit = { h.flush.request() }, makeRing = ringWith(HistoryTestPages.backlog(3)))
        try {
            advanceTo(1_000)
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(60_000)

            val stored = LocalStore(h.db).samples(MetricKind.HEART_RATE, june, july)
            assertTrue(stored.isNotEmpty(), "the sync stored heart rate")
            val byHour = stored.filter { it.value > 0 }.groupBy { HrHours.hourStart(it.start) }.toSortedMap()
            val written = h.hc.records(RecordType.HEART_RATE).map { it as HeartRateSeries }

            assertEquals(byHour.keys.map { "oc:hr:${it.toEpochMilli()}" }, written.map { it.meta.clientRecordId })
            assertEquals(
                byHour.values.map { hour -> hour.map { it.start to it.value.roundToLong() } },
                written.map { record -> record.samples.map { it.time to it.beatsPerMinute } },
            )
            assertEquals(emptyList(), LocalStore(h.db).pendingHealthSamples(MetricKind.HEART_RATE), "every written sample is marked")
            assertEquals(Decoded.Readable(1L), BlobStore(h.db).loadHealthWriteGeneration())
            val callsAfterFirst = h.hc.calls.size
            assertEquals(written.size, h.hc.calls.sumOf { it.count })

            // The link was closed after the sync; the next one finds nothing new on the ring.
            w.viewModel.onAction(RingAction.SyncNow)
            advanceTo(120_000)

            assertEquals(2, w.ring.events.count { it.what == "commit returned" })
            assertEquals(callsAfterFirst, h.hc.calls.size, "nothing pending, nothing sent")
            assertEquals(written, h.hc.records(RecordType.HEART_RATE))
            assertEquals(Decoded.Readable(1L), BlobStore(h.db).loadHealthWriteGeneration(), "no write, no new generation")
        } finally {
            h.appScope.cancel()
            w.db.close()
        }
    }
}
