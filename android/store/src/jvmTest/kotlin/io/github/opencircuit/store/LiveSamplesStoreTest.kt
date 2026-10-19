package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Readings measured live (a settled heart rate, the last blood oxygen, a nightly skin temperature)
 * are kept outside the sync cursor: the cursor is forward-only, and a reading taken now would make
 * the next drain drop every older sample the ring still holds (upstream fixed this after the pin
 * by saving live readings outside the cursor, deduped by start; PORTING.md D-234, D-293).
 *
 * The Health Connect mark is forward-only too. Before live readings, a drain could only store
 * samples newer than everything stored; now a drain can land samples behind a live reading the
 * writer already marked, so ingest pulls that kind's mark back to just before the oldest sample it
 * stored (PORTING.md D-294).
 */
class LiveSamplesStoreTest {
    private val now = Instant.parse("2026-10-16T15:00:00Z")
    private val utc = ZoneOffset.UTC
    private fun at(text: String): Instant = Instant.parse("2026-10-16T${text}Z")
    private fun hr(time: String, bpm: Double) = QuantitySample(MetricKind.HEART_RATE, at(time), value = bpm)
    private fun spo2(time: String, fraction: Double) = QuantitySample(MetricKind.SPO2, at(time), value = fraction)
    private fun temp(time: String, c: Double) = QuantitySample(MetricKind.TEMPERATURE, at(time), value = c)

    @Test
    fun aLiveReadingIsStoredWithoutMovingTheIngestCursor() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val stored = store.insertLiveSamples(listOf(hr("14:00:00", 64.0), spo2("14:01:00", 0.97), temp("03:00:00", 34.1)), now)

            assertEquals(3, stored.size)
            assertEquals(listOf(64.0), store.samples(MetricKind.HEART_RATE, at("00:00:00"), at("23:00:00")).map { it.value })
            assertEquals(listOf(0.97), store.samples(MetricKind.SPO2, at("00:00:00"), at("23:00:00")).map { it.value })
            assertEquals(listOf(34.1), store.samples(MetricKind.TEMPERATURE, at("00:00:00"), at("23:00:00")).map { it.value })
            val cursor = store.loadCursor()
            assertTrue(MetricKind.entries.all { cursor.last(it) == null }, "no ingest cursor row was written: $cursor")
        }
    }

    /** The guard: a reading taken live at 14:00, then a drain of the ring's 08:00-13:59 backlog. */
    @Test
    fun aLaterDrainKeepsEveryOlderSampleOfTheBacklog() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.insertLiveSamples(listOf(hr("14:00:00", 64.0), spo2("14:01:00", 0.97)), now)

            val drained = listOf(hr("08:00:00", 55.0), hr("11:00:00", 58.0), hr("13:59:00", 61.0), spo2("09:00:00", 0.95))
            val ingested = store.ingest(drained, now, utc)

            assertEquals(drained.sortedBy { it.start }, ingested.sortedBy { it.start })
            assertEquals(
                listOf(55.0, 58.0, 61.0, 64.0),
                store.samples(MetricKind.HEART_RATE, at("00:00:00"), at("23:00:00")).map { it.value },
            )
            assertEquals(listOf(0.95, 0.97), store.samples(MetricKind.SPO2, at("00:00:00"), at("23:00:00")).map { it.value })
            assertEquals(at("13:59:00"), store.loadCursor().last(MetricKind.HEART_RATE), "the cursor is the drain's own newest")
        }
    }

    @Test
    fun aReadingAlreadyStoredAtThatStartIsNotStoredTwice() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr("10:00:00", 60.0)), now, utc)
            store.insertLiveSamples(listOf(hr("12:00:00", 62.0)), now)

            val stored = store.insertLiveSamples(listOf(hr("10:00:00", 70.0), hr("12:00:00", 72.0), hr("12:00:00", 73.0), hr("12:30:00", 65.0)), now)

            assertEquals(listOf(hr("12:30:00", 65.0)), stored)
            assertEquals(
                listOf(60.0, 62.0, 65.0),
                store.samples(MetricKind.HEART_RATE, at("00:00:00"), at("23:00:00")).map { it.value },
                "the first reading at a start is kept",
            )
        }
    }

    /** Same start, another kind: both kept (the dedupe is per kind). Sub-millisecond starts compare as stored. */
    @Test
    fun theDedupeIsPerKindAndAtTheStoredMillisecond() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.insertLiveSamples(listOf(hr("12:00:00", 62.0)), now)

            val stored = store.insertLiveSamples(
                listOf(spo2("12:00:00", 0.96), QuantitySample(MetricKind.HEART_RATE, at("12:00:00").plusNanos(400_000), value = 66.0)),
                now,
            )

            assertEquals(listOf(MetricKind.SPO2), stored.map { it.kind })
        }
    }

    @Test
    fun anImplausibleReadingIsNotStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val stored = store.insertLiveSamples(
                listOf(
                    hr("12:00:00", 250.0),
                    QuantitySample(MetricKind.HEART_RATE, now.plusSeconds(2 * 86_400), value = 60.0),
                    temp("03:00:00", Double.NaN),
                    spo2("12:05:00", 0.98),
                ),
                now,
            )

            assertEquals(listOf(spo2("12:05:00", 0.98)), stored)
        }
    }

    @Test
    fun onlyHeartRateBloodOxygenAndSkinTemperatureAreTakenLive() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            assertFailsWith<IllegalArgumentException> {
                store.insertLiveSamples(listOf(QuantitySample(MetricKind.STEPS, at("12:00:00"), value = 40.0)), now)
            }
            assertTrue(store.samples(MetricKind.STEPS, at("00:00:00"), at("23:00:00")).isEmpty())
        }
    }

    /** The Health Connect half: a drain behind a live reading the writer already marked. */
    @Test
    fun aDrainBehindAWrittenLiveReadingPullsTheHealthMarkBack() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.insertLiveSamples(listOf(hr("14:00:00", 64.0)), now)
            store.markHealthWritten(store.pendingHealthSamples(MetricKind.HEART_RATE))
            assertTrue(store.pendingHealthSamples(MetricKind.HEART_RATE).isEmpty())

            store.ingest(listOf(hr("08:00:00", 55.0), hr("13:59:00", 61.0)), now, utc)

            assertEquals(
                listOf(55.0, 61.0, 64.0),
                store.pendingHealthSamples(MetricKind.HEART_RATE).map { it.value },
                "the drained samples wait for Health Connect (the live one is written again, under the same id)",
            )
        }
    }

    /** Nothing behind the mark: the mark stays where the writer left it. Other kinds' marks are not touched. */
    @Test
    fun aDrainAfterTheMarkLeavesEveryMarkWhereItWas() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.insertLiveSamples(listOf(hr("10:00:00", 64.0), spo2("14:00:00", 0.97)), now)
            store.markHealthWritten(store.pendingHealthSamples())

            store.ingest(listOf(hr("11:00:00", 55.0), spo2("14:30:00", 0.96)), now, utc)

            assertEquals(listOf(55.0), store.pendingHealthSamples(MetricKind.HEART_RATE).map { it.value })
            assertEquals(listOf(0.96), store.pendingHealthSamples(MetricKind.SPO2).map { it.value })
        }
    }

    /**
     * A live reading stamped behind the mark (the phone's clock behind the ring's, whose drained
     * samples the writer already marked) waits for Health Connect too, instead of being taken as
     * written and pruned as such.
     */
    @Test
    fun aLiveReadingBehindTheMarkPullsTheHealthMarkBack() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(listOf(hr("13:00:00", 60.0), hr("14:30:00", 62.0)), now, utc)
            store.markHealthWritten(store.pendingHealthSamples(MetricKind.HEART_RATE))
            assertTrue(store.pendingHealthSamples(MetricKind.HEART_RATE).isEmpty())

            store.insertLiveSamples(listOf(hr("14:00:00", 64.0)), now)

            assertEquals(
                listOf(64.0, 62.0),
                store.pendingHealthSamples(MetricKind.HEART_RATE).map { it.value },
                "the live reading waits for Health Connect (the later one is written again, under the same id)",
            )
        }
    }

    @Test
    fun aKindNeverWrittenHasNoMarkToPullBack() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.insertLiveSamples(listOf(hr("14:00:00", 64.0)), now)
            store.ingest(listOf(hr("08:00:00", 55.0)), now, utc)

            assertEquals(listOf(55.0, 64.0), store.pendingHealthSamples(MetricKind.HEART_RATE).map { it.value })
            assertEquals(emptyList(), db.queryRaw("SELECT kind_raw FROM stored_cursor WHERE kind_raw LIKE 'hk:%'"))
        }
    }
}
