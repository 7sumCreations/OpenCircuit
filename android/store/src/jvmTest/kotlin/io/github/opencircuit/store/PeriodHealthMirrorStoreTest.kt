package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which logged periods the Health Connect writer looks at, and how it records what it wrote, read
 * from upstream's source (ios/OpenCircuit/CycleStore.swift `periodEntriesNeedingHealthMirror`
 * :173-194 and `recordPeriodEntryHK` :204-211 @ b1c2fdd). Here the ids recorded are Health Connect
 * clientRecordIds, kept in the row's `hk_sample_uuids` column.
 */
class PeriodHealthMirrorStoreTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private fun day(d: Int, time: String = "08:00:00"): Instant = Instant.parse("2026-10-%02dT%sZ".format(java.util.Locale.ROOT, d, time))

    private suspend fun LocalStore.log(start: Instant, end: Instant?) =
        savePeriodEntry(start = start, end = end, flowLevelRaw = 2, symptoms = emptyList(), notes = "", now = now)

    @Test
    fun aPeriodNeverWrittenIsAskedForWhateverItsEnd() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.log(day(1), day(5))
            store.log(day(10), null)

            assertEquals(listOf(day(1), day(10)), store.periodEntriesNeedingHealthMirror(now, utc).map { it.start })
        }
    }

    @Test
    fun aWrittenPeriodThatEndedBeforeTodayIsNotAskedForAgain() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.log(day(1), day(5))
            store.recordPeriodEntryHealthIds(day(1), listOf("oc:flow:2026-10-01"), finalized = true)

            assertEquals(emptyList(), store.periodEntriesNeedingHealthMirror(now, utc).map { it.start })
        }
    }

    @Test
    fun aWrittenOpenPeriodStaysAskedForBecauseADayMayHaveElapsed() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.log(day(12), null)
            store.recordPeriodEntryHealthIds(day(12), listOf("oc:flow:2026-10-12"), finalized = true)

            assertEquals(listOf(day(12)), store.periodEntriesNeedingHealthMirror(now, utc).map { it.start })
        }
    }

    @Test
    fun aWrittenPeriodEndingLaterThanTodayStaysAskedForUntilItsEndDayIsToday() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.log(day(14), day(17))
            store.log(day(2), day(16, "23:59:59"))
            store.recordPeriodEntryHealthIds(day(14), listOf("oc:flow:2026-10-14"), finalized = true)
            store.recordPeriodEntryHealthIds(day(2), listOf("oc:flow:2026-10-02"), finalized = true)

            // Ends tomorrow: kept. Ends later today: today's day is not after today, so done.
            assertEquals(listOf(day(14)), store.periodEntriesNeedingHealthMirror(now, utc).map { it.start })
        }
    }

    @Test
    fun theEndDayIsTheDayInTheGivenZone() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            // 02:00 UTC on the 17th is still the 16th in New York.
            store.log(day(10), day(17, "02:00:00"))
            store.recordPeriodEntryHealthIds(day(10), listOf("oc:flow:2026-10-10"), finalized = true)

            assertEquals(listOf(day(10)), store.periodEntriesNeedingHealthMirror(now, utc).map { it.start })
            assertEquals(emptyList(), store.periodEntriesNeedingHealthMirror(now, ZoneId.of("America/New_York")).map { it.start })
        }
    }

    @Test
    fun recordingIdsStoresThemAndSetsWrittenOnlyWhenFinalized() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.log(day(1), day(3))

            store.recordPeriodEntryHealthIds(day(1), listOf("oc:flow:2026-10-01", "oc:flow:2026-10-02"), finalized = false)
            val pending = store.allPeriodEntries().single()
            assertEquals(listOf("oc:flow:2026-10-01", "oc:flow:2026-10-02"), pending.hkSampleUUIDs)
            assertFalse(pending.healthWritten)

            store.recordPeriodEntryHealthIds(day(1), listOf("oc:flow:2026-10-01"), finalized = true)
            val written = store.allPeriodEntries().single()
            assertEquals(listOf("oc:flow:2026-10-01"), written.hkSampleUUIDs)
            assertTrue(written.healthWritten)
        }
    }

    @Test
    fun recordingForAPeriodNoLongerLoggedChangesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.log(day(1), day(3))

            store.recordPeriodEntryHealthIds(day(9), listOf("oc:flow:2026-10-09"), finalized = true)

            val row = store.allPeriodEntries().single()
            assertEquals(emptyList(), row.hkSampleUUIDs)
            assertFalse(row.healthWritten)
        }
    }

    @Test
    fun aClinicalEditReopensAWrittenPeriodAndKeepsItsIdsForTheDelete() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.log(day(1), day(5))
            store.recordPeriodEntryHealthIds(day(1), listOf("oc:flow:2026-10-01", "oc:period:2026-10-01"), finalized = true)

            store.log(day(1), day(3))

            val row = store.periodEntriesNeedingHealthMirror(now, utc).single()
            assertFalse(row.healthWritten)
            assertEquals(listOf("oc:flow:2026-10-01", "oc:period:2026-10-01"), row.hkSampleUUIDs)
        }
    }
}
