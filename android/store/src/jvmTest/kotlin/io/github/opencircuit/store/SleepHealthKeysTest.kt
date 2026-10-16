package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A night's Health Connect record ids and its mirror record: kept under the stored row's own key
 * text, which `NightRekey` moves with the row, so a night that moves keeps naming the record it was
 * written as (and the next write can delete that id once its new one is accepted).
 */
class SleepHealthKeysTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2026-06-29T09:00:00Z")
    private val night = Instant.parse("2026-06-29T00:00:00Z")

    @Test
    fun idsAreKeptUnderTheRowsOwnKeyTextAsRecordIds() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            assertEquals(emptyList(), store.sleepHealthRecordIds(night))

            store.setSleepHealthRecordIds(night, listOf("oc:sleep:${night.epochSecond}.0", "oc:sleep:${night.epochSecond}.0"), now)

            assertEquals(listOf("oc:sleep:${night.epochSecond}.0"), store.sleepHealthRecordIds(night), "kept once")
            assertEquals("[\"oc:sleep:${night.epochSecond}.0\"]", db.kvDao().get("sleep.edit.hkuuids.${night.epochSecond}.0")?.value)
            store.setSleepHealthRecordIds(night, emptyList(), now)
            assertNull(db.kvDao().get("sleep.edit.hkuuids.${night.epochSecond}.0"), "none clears the key")
        }
    }

    @Test
    fun anUnreadableIdListIsNullNeverEmpty() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.kvDao().upsert(StoreKvEntity("sleep.edit.hkuuids.${night.epochSecond}.0", "[1,2]", now))
            assertNull(SleepStore(db).sleepHealthRecordIds(night))
        }
    }

    @Test
    fun theMirrorRecordRoundTripsAndAnUnreadableOneIsAbsent() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            assertNull(store.mirroredNight(night))
            val record = MirroredNight("cbf29ce484222325", night.minusSeconds(7_200), night.plusSeconds(25_200))
            store.setMirroredNight(night, record, now)
            assertEquals(record, store.mirroredNight(night))

            db.kvDao().upsert(StoreKvEntity("sleep.mirror.night.${night.epochSecond}.0", "{\"signature\":1}", now))
            assertNull(store.mirroredNight(night))
        }
    }

    @Test
    fun aNightMovedToItsWakeDayCarriesItsIdsAndMirrorWithIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            // A night keyed by the day it started (as rows were before the wake-day key): 22:00 → 06:30.
            val bedtime = Instant.parse("2026-06-28T22:00:00Z")
            val wake = Instant.parse("2026-06-29T06:30:00Z")
            val summary = SleepStaging.Summary(Duration.ofHours(8), Duration.ofMinutes(30), Duration.ofHours(5), Duration.ofHours(1), Duration.ofMinutes(90))
            store.saveSleepSummary(summary, bedtime, bedtime, wake, now = now, zone = zone)
            val oldKey = assertNotNull(store.sleepSummary(bedtime, zone)).night
            store.setSleepHealthRecordIds(oldKey, listOf("oc:sleep:${oldKey.epochSecond}.0"), now)
            store.setMirroredNight(oldKey, MirroredNight("abc", bedtime, wake), now)

            store.rekeySleepNightsToWakeDay(zone, now)

            val newKey = assertNotNull(store.sleepSummary(wake, zone)).night
            assertEquals(night, newKey)
            assertEquals(listOf("oc:sleep:${oldKey.epochSecond}.0"), store.sleepHealthRecordIds(newKey), "the old id is still named, for the next write to delete")
            assertEquals("abc", store.mirroredNight(newKey)?.signature)
            assertEquals(emptyList(), store.sleepHealthRecordIds(oldKey))
        }
    }
}
