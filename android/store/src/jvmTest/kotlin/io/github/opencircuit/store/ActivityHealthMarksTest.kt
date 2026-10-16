package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What the live step path and the Health Connect writer keep in `store_kv` for steps, energy and the
 * daily records: the step fold's baseline (upstream keeps it in UserDefaults, RingSession.swift
 * :2239-2253), the last day written per daily record type (upstream `hk.restingHR.lastDay`), and the
 * kilocalories written per energy bucket of a day. Each survives a second store over the same
 * database (a relaunch), and a value that cannot be read is read as absent, never as a wrong value.
 */
class ActivityHealthMarksTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")

    @Test
    fun theStepBaselineSurvivesARelaunch() = runBlocking<Unit> {
        withInMemoryStore { db ->
            assertNull(BlobStore(db).loadStepBaseline())
            val baseline = StepBaseline(raw = 412, day = Instant.parse("2026-10-16T00:00:00Z"), sampleAt = Instant.parse("2026-10-16T10:07:31.250Z"))

            BlobStore(db).saveStepBaseline(baseline, now)

            assertEquals(baseline, BlobStore(db).loadStepBaseline())
            assertEquals(listOf("412;1792108800000;1792145251250"), db.queryRaw("SELECT value FROM store_kv WHERE `key` = 'steps.baseline'"))
        }
    }

    @Test
    fun anUnreadableStepBaselineIsNone() = runBlocking<Unit> {
        withInMemoryStore { db ->
            for (raw in listOf("", "412", "x;1;2", "412;１;2", "-1;1;2", "70000;1;2", "1;2;3;4")) {
                db.execRaw("INSERT OR REPLACE INTO store_kv(`key`, value, updated_at) VALUES ('steps.baseline', '$raw', 0)")
                assertNull(BlobStore(db).loadStepBaseline(), raw)
            }
        }
    }

    @Test
    fun aStepBaselineOutsideTheCountersRangeIsRefused() = runBlocking<Unit> {
        withInMemoryStore { db ->
            assertFailsWith<IllegalArgumentException> {
                BlobStore(db).saveStepBaseline(StepBaseline(raw = 65_536, day = now, sampleAt = now), now)
            }
        }
    }

    @Test
    fun theLastDayWrittenIsKeptPerType() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = BlobStore(db)
            assertNull(store.loadHealthDayMark("RESTING_HEART_RATE"))

            store.saveHealthDayMark("RESTING_HEART_RATE", LocalDate.of(2026, 10, 15), now)
            store.saveHealthDayMark("BASAL_METABOLIC_RATE", LocalDate.of(2026, 10, 14), now)

            assertEquals(LocalDate.of(2026, 10, 15), BlobStore(db).loadHealthDayMark("RESTING_HEART_RATE"))
            assertEquals(LocalDate.of(2026, 10, 14), BlobStore(db).loadHealthDayMark("BASAL_METABOLIC_RATE"))
            db.execRaw("UPDATE store_kv SET value = '2026-1０-15' WHERE `key` = 'hc.lastDay/RESTING_HEART_RATE'")
            assertNull(BlobStore(db).loadHealthDayMark("RESTING_HEART_RATE"), "unreadable: none")
        }
    }

    @Test
    fun theKilocaloriesWrittenPerBucketAreKeptPerDayAndOldDaysForgotten() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = BlobStore(db)
            val day = LocalDate.of(2026, 10, 16)
            val buckets = mapOf(Instant.parse("2026-10-16T09:15:00Z") to 12.5, Instant.parse("2026-10-16T09:00:00Z") to 0.0)
            assertEquals(emptyMap(), store.loadHealthEnergyWritten(day))

            store.saveHealthEnergyWritten(day, buckets, now)
            store.saveHealthEnergyWritten(LocalDate.of(2026, 10, 9), mapOf(Instant.parse("2026-10-09T09:00:00Z") to 3.0), now)
            store.saveHealthEnergyWritten(LocalDate.of(2026, 10, 10), mapOf(Instant.parse("2026-10-10T09:00:00Z") to 4.0), now)

            assertEquals(buckets, BlobStore(db).loadHealthEnergyWritten(day))

            store.forgetHealthEnergyWrittenBefore(LocalDate.of(2026, 10, 10))

            assertEquals(emptyMap(), store.loadHealthEnergyWritten(LocalDate.of(2026, 10, 9)))
            assertEquals(mapOf(Instant.parse("2026-10-10T09:00:00Z") to 4.0), store.loadHealthEnergyWritten(LocalDate.of(2026, 10, 10)))
            assertEquals(buckets, store.loadHealthEnergyWritten(day))
        }
    }

    @Test
    fun anUnreadableEnergyMapIsEmpty() = runBlocking<Unit> {
        withInMemoryStore { db ->
            for (raw in listOf("nonsense", "1=x", "1=2=3", "１=2.0", "1=NaN", "1=-2.0")) {
                db.execRaw("INSERT OR REPLACE INTO store_kv(`key`, value, updated_at) VALUES ('hc.activeEnergy/2026-10-16', '$raw', 0)")
                assertEquals(emptyMap(), BlobStore(db).loadHealthEnergyWritten(LocalDate.of(2026, 10, 16)), raw)
            }
        }
    }
}
