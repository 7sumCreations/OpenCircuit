package io.github.opencircuit.app

import io.github.opencircuit.health.MenstruationFlow
import io.github.opencircuit.health.MenstruationPeriod
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.store.LocalStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Logged periods through the app's real store: a period saved as the period log saves it reaches
 * Health Connect as its flow days and its period record, named in the row's own id column; an edit
 * the store marks clinical (a shorter end) replaces the records and deletes the day it removed, by
 * id, after the replacement was accepted. The period log's screens are a later epic's; this is the
 * writer side they will call into.
 */
class StorePeriodWriteTest {

    private val wall = Instant.parse("2026-10-16T12:00:00Z")
    private fun day(d: Int): Instant = Instant.parse("2026-10-%02dT08:00:00Z".format(java.util.Locale.ROOT, d))

    @Test
    fun aLoggedPeriodIsWrittenAndAShorterEditDeletesTheDayItRemoved() = runTest {
        val h = healthWorld(wallStart = wall)
        try {
            val store = LocalStore(h.db)
            store.savePeriodEntry(start = day(10), end = day(13), flowLevelRaw = 3, symptoms = emptyList(), notes = "", now = wall)

            h.flush.request()
            testScheduler.advanceUntilIdle()

            val flows = h.hc.records(RecordType.MENSTRUATION_FLOW).map { it as MenstruationFlow }.sortedBy { it.time }
            assertEquals(listOf("oc:flow:2026-10-10", "oc:flow:2026-10-11", "oc:flow:2026-10-12", "oc:flow:2026-10-13"), flows.map { it.meta.clientRecordId })
            val period = h.hc.records(RecordType.MENSTRUATION_PERIOD).map { it as MenstruationPeriod }.single()
            assertEquals(Instant.parse("2026-10-14T00:00:00Z"), period.end)
            val row = store.allPeriodEntries().single()
            assertTrue(row.healthWritten)
            assertEquals(flows.map { it.meta.clientRecordId } + "oc:period:2026-10-10", row.hkSampleUUIDs)

            store.savePeriodEntry(start = day(10), end = day(12), flowLevelRaw = 3, symptoms = emptyList(), notes = "", now = wall)
            h.flush.request()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("oc:flow:2026-10-10", "oc:flow:2026-10-11", "oc:flow:2026-10-12"),
                h.hc.records(RecordType.MENSTRUATION_FLOW).map { it.meta.clientRecordId }.sorted(),
            )
            assertEquals(Instant.parse("2026-10-13T00:00:00Z"), (h.hc.records(RecordType.MENSTRUATION_PERIOD).single() as MenstruationPeriod).end)
            assertTrue(store.allPeriodEntries().single().healthWritten)
            assertEquals(emptyList(), store.periodEntriesNeedingHealthMirror(wall, java.time.ZoneOffset.UTC))
        } finally {
            h.appScope.cancel()
            h.db.close()
        }
    }

    @Test
    fun aLoggedHeadacheIsNeverWrittenAndStaysPendingBookkeeping() = runTest {
        val h = healthWorld(wallStart = wall)
        try {
            val store = LocalStore(h.db)
            store.saveHeadacheEntry(onset = day(15), end = day(15).plusSeconds(3600), severityRaw = 3, symptoms = listOf("nausea"), now = wall)

            h.flush.request()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), h.hc.calls, "Health Connect has no headache type: nothing is sent")
            assertEquals(listOf(day(15)), store.pendingHeadacheEntries().map { it.onset }, "and nothing marks it written")
        } finally {
            h.appScope.cancel()
            h.db.close()
        }
    }
}
