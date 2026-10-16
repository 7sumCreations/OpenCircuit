package io.github.opencircuit.store

import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Health Connect watermark: what has been stored but not yet written to Health Connect, per
 * kind, and the mark that moves only when the writer says a write was accepted.
 *
 * The first four tests are ported from upstream ios/OpenCircuitTests/HealthWatermarkTests.swift
 * (@ b1c2fdd :29, :39, :53, :62) over LocalStore.swift :1215-1233 and :1358-1377. The rest are
 * Kotlin-only: the zero-value rule seen through a kind ingest keeps at zero (a heart rate of 0 never
 * passes ingest, so upstream's own zero test cannot fail), a zero heart rate planted on the raw path,
 * the mark's stored form, forward-only marks, per-kind reads, and the ingest cursor left alone.
 */
class HealthWatermarkTest {

    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val utc = ZoneOffset.UTC

    /** `t` seconds after the ring's counter epoch, as upstream's fixture (1970 fails ingest's plausibility check). */
    private fun at(t: Long): Instant = Instant.ofEpochSecond(Command.SYNC_EPOCH + t)

    private fun hr(value: Double, t: Long) = QuantitySample(MetricKind.HEART_RATE, at(t), value = value)

    private fun spo2(value: Double, t: Long) = QuantitySample(MetricKind.SPO2, at(t), value = value)

    private suspend fun StoreDatabase.plantSample(kindRaw: String, t: Long, value: Double) {
        val ms = at(t).toEpochMilli()
        execRaw("INSERT INTO stored_sample(kind_raw, start, `end`, value, raw_value, is_delta, daily_total) VALUES ('$kindRaw', $ms, $ms, $value, NULL, 0, NULL)")
    }

    // ---- ported (HealthWatermarkTests.swift) ----

    @Test
    fun autoPersistDoesNotStarveTheHealthWrite() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(70.0, 100), hr(72.0, 200)), now, utc)

            assertEquals(listOf(70.0, 72.0), store.pendingHealthSamples().map { it.value })
        }
    }

    @Test
    fun markWrittenAdvancesTheWatermark() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(70.0, 100), hr(72.0, 200)), now, utc)
            store.markHealthWritten(store.pendingHealthSamples())

            assertTrue(store.pendingHealthSamples().isEmpty())

            store.ingest(listOf(hr(75.0, 300)), now, utc)
            assertEquals(listOf(75.0), store.pendingHealthSamples().map { it.value })
        }
    }

    @Test
    fun unwrittenSamplesBackfill() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(70.0, 100)), now, utc)
            store.pendingHealthSamples() // read, never marked written

            assertEquals(listOf(70.0), store.pendingHealthSamples().map { it.value })
        }
    }

    @Test
    fun zeroValuesAreExcluded() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(0.0, 100), hr(70.0, 200)), now, utc)

            assertEquals(listOf(70.0), store.pendingHealthSamples().map { it.value })
        }
    }

    // ---- Kotlin-only ----

    @Test
    fun aZeroReadingThatIngestKeepsIsStillNeverPending() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            // Ingest checks only the heart rate's range: an SpO2 of 0 is stored.
            store.ingest(listOf(spo2(0.0, 100), spo2(0.97, 200)), now, utc)
            assertEquals(2, store.samples(MetricKind.SPO2, at(0), at(1_000)).size, "both readings are stored")

            assertEquals(listOf(0.97), store.pendingHealthSamples().map { it.value })
        }
    }

    @Test
    fun aZeroHeartRateOnTheRawPathIsNeverPending() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.plantSample("heartRate", 100, 0.0)
            db.plantSample("heartRate", 200, 64.0)

            assertEquals(listOf(at(200)), LocalStore(db).pendingHealthSamples().map { it.start })
        }
    }

    @Test
    fun theMarkIsKeptAsAnHkCursorRowPerKindAndLeavesTheIngestCursorAlone() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(70.0, 100), spo2(0.97, 150), hr(72.0, 200)), now, utc)
            val ingestRows = db.queryRaw("SELECT kind_raw, last FROM stored_cursor ORDER BY kind_raw")

            store.markHealthWritten(store.pendingHealthSamples())

            assertEquals(
                listOf("hk:heartRate|${at(200).toEpochMilli()}", "hk:spo2|${at(150).toEpochMilli()}"),
                db.queryRaw("SELECT kind_raw, last FROM stored_cursor WHERE kind_raw LIKE 'hk:%' ORDER BY kind_raw"),
            )
            assertEquals(ingestRows, db.queryRaw("SELECT kind_raw, last FROM stored_cursor WHERE kind_raw NOT LIKE 'hk:%' ORDER BY kind_raw"))
            assertEquals(at(200), store.loadCursor().last(MetricKind.HEART_RATE))
        }
    }

    @Test
    fun aMarkNeverMovesBack() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(70.0, 100), hr(72.0, 200), hr(74.0, 300)), now, utc)

            store.markHealthWritten(listOf(hr(74.0, 300)))
            store.markHealthWritten(listOf(hr(70.0, 100)))

            assertEquals(emptyList(), store.pendingHealthSamples())
            assertEquals(listOf("hk:heartRate|${at(300).toEpochMilli()}"), db.queryRaw("SELECT kind_raw, last FROM stored_cursor WHERE kind_raw LIKE 'hk:%'"))
        }
    }

    @Test
    fun markingNothingWritesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(70.0, 100)), now, utc)

            store.markHealthWritten(emptyList())

            assertEquals(emptyList(), db.queryRaw("SELECT kind_raw FROM stored_cursor WHERE kind_raw LIKE 'hk:%'"))
            assertEquals(listOf(70.0), store.pendingHealthSamples().map { it.value })
        }
    }

    @Test
    fun aKindThatIsNotWrittenFromTheStoreGetsNoMark() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            // Steps take their own path (step samples), as upstream; a step reading here moves no mark.
            store.markHealthWritten(listOf(QuantitySample(MetricKind.STEPS, at(100), value = 12.0)))

            assertEquals(emptyList(), db.queryRaw("SELECT kind_raw FROM stored_cursor"))
        }
    }

    @Test
    fun pendingByKindReadsOneKindOldestFirstFromItsOwnMark() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(72.0, 200), spo2(0.97, 150), hr(70.0, 100), hr(74.0, 300)), now, utc)
            store.markHealthWritten(listOf(hr(70.0, 100), spo2(0.97, 150)))

            assertEquals(listOf(at(200), at(300)), store.pendingHealthSamples(MetricKind.HEART_RATE).map { it.start })
            assertEquals(emptyList(), store.pendingHealthSamples(MetricKind.SPO2))
            // The all-kinds read is every mirrored kind's pending, oldest first.
            assertEquals(listOf(at(200), at(300)), store.pendingHealthSamples().map { it.start })
        }
    }

    @Test
    fun twoSamplesAtTheSameInstantAreBothPendingAndOneMarkCoversBoth() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.plantSample("hrvSDNN", 100, 40.0)
            db.plantSample("hrvSDNN", 100, 42.0)
            val store = LocalStore(db)

            val pending = store.pendingHealthSamples(MetricKind.HRV_SDNN)
            assertEquals(listOf(40.0, 42.0), pending.map { it.value }, message = "row order breaks the tie")
            store.markHealthWritten(pending)
            assertEquals(emptyList(), store.pendingHealthSamples(MetricKind.HRV_SDNN))
        }
    }

    /** The existence check agrees with the pending read: above zero, after the mark, not after the limit. */
    @Test
    fun whetherAnythingIsPendingIsAnsweredWithoutReadingIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            assertEquals(false, store.hasPendingHealthSamples(MetricKind.HEART_RATE, at(1_000)))
            db.plantSample(MetricKind.HEART_RATE.rawValue, 50, 0.0)
            assertEquals(false, store.hasPendingHealthSamples(MetricKind.HEART_RATE, at(1_000)), "a zero is never pending")
            store.ingest(listOf(hr(70.0, 100), hr(72.0, 200)), now, utc)
            assertEquals(true, store.hasPendingHealthSamples(MetricKind.HEART_RATE, at(1_000)))
            assertEquals(false, store.hasPendingHealthSamples(MetricKind.HEART_RATE, at(99)), "nothing at or before the limit")
            store.markHealthWritten(listOf(hr(70.0, 100)))
            assertEquals(true, store.hasPendingHealthSamples(MetricKind.HEART_RATE, at(1_000)))
            store.markHealthWritten(listOf(hr(72.0, 200)))
            assertEquals(false, store.hasPendingHealthSamples(MetricKind.HEART_RATE, at(1_000)))
            assertEquals(false, store.hasPendingHealthSamples(MetricKind.STEPS, at(1_000)), "a kind not written from here")
        }
    }

    /**
     * A write reads what is pending, a sync stores older samples while it sends, then the write
     * marks what it sent: the mark stops before the samples it never saw, which stay pending.
     */
    @Test
    fun aMarkNeverPassesASampleStoredAfterTheWriterRead() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr(70.0, 100), spo2(0.97, 100)), now, utc)
            val seen = store.sampleRowWatermark()
            val read = store.pendingHealthSamples()

            store.insertLiveSamples(listOf(hr(64.0, 50), hr(66.0, 300)), now)
            store.markHealthWritten(read, seen)

            assertEquals(listOf(64.0, 70.0, 66.0), store.pendingHealthSamples(MetricKind.HEART_RATE).map { it.value })
            assertEquals(emptyList(), store.pendingHealthSamples(MetricKind.SPO2), "a kind with nothing new moves as before")

            store.markHealthWritten(store.pendingHealthSamples(MetricKind.HEART_RATE), store.sampleRowWatermark())
            assertEquals(emptyList(), store.pendingHealthSamples(MetricKind.HEART_RATE))
        }
    }
}
