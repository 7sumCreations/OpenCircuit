package io.github.opencircuit.app

import io.github.opencircuit.app.health.StoreHealthFailures
import io.github.opencircuit.app.health.StoreHealthSamples
import io.github.opencircuit.app.health.StoreHealthSleep
import io.github.opencircuit.app.health.StoreWriteGenerations
import io.github.opencircuit.health.FakeHealthConnect
import io.github.opencircuit.health.HealthWriter
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RingDevice
import io.github.opencircuit.health.SleepSession
import io.github.opencircuit.health.WriteGeneration
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.PendingSleepReconcile
import io.github.opencircuit.store.SleepNightExtras
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import androidx.room3.useWriterConnection
import io.github.opencircuit.store.keyText
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The writer's sleep over the app's real store ([StoreHealthSleep]): a stored night, a queued edit,
 * a nap and a night that moved to its wake day reach a Health Connect that enforces the platform's
 * rules, and the store's own marks move from what was accepted.
 */
class StoreHealthSleepTest {
    private val zone = ZoneOffset.UTC
    private var now = Instant.parse("2026-06-20T09:00:00Z")
    private val db: StoreDatabase = runBlocking { StoreFactory.openInMemory() }
    private val hc = FakeHealthConnect({ now }).apply { grantAll() }
    private val writer = HealthWriter(
        hc, StoreHealthSamples { db }, WriteGeneration(StoreWriteGenerations({ db }, { now })), StoreHealthFailures({ db }, { now }, log = {}),
        { now }, { zone }, { RingDevice("Gen 2") },
        sleep = StoreHealthSleep({ db }, { now }, { zone }),
    )

    @AfterTest
    fun close() = db.close()

    private val bed = Instant.parse("2026-06-18T22:00:00Z")
    private val wake = Instant.parse("2026-06-19T06:00:00Z")
    private val hypnogram = listOf(
        SleepSegment(bed, wake, SleepStage.IN_BED),
        SleepSegment(bed.plusSeconds(1_200), bed.plusSeconds(10_800), SleepStage.ASLEEP_CORE),
        SleepSegment(bed.plusSeconds(10_800), bed.plusSeconds(14_400), SleepStage.ASLEEP_DEEP),
        SleepSegment(bed.plusSeconds(14_400), wake.minusSeconds(600), SleepStage.ASLEEP_REM),
    )

    private suspend fun saveNight(night: Instant = wake) {
        SleepStore(db).saveSleepSummary(
            SleepStaging.summary(hypnogram), night, bed, wake, bed.plusSeconds(1_200), wake.minusSeconds(600),
            SleepNightExtras(hypnogram = hypnogram), now, zone,
        )
    }

    private fun sessions() = hc.records(RecordType.SLEEP_SESSION).map { it as SleepSession }.sortedBy { it.start }

    private suspend fun sql(statement: String) = db.useWriterConnection { c -> c.usePrepared(statement) { it.step() } }

    private suspend fun query(statement: String): List<String> = db.useWriterConnection { c ->
        c.usePrepared(statement) { s -> buildList { while (s.step()) add(s.getText(0)) } }
    }

    @Test
    fun aStoredNightIsWrittenUnderItsOwnKeyTextAndTheStoresSleepMarkMoves() = runBlocking<Unit> {
        saveNight()
        val stored = assertNotNull(SleepStore(db).sleepSummary(wake, zone))

        writer.flush()

        assertEquals(listOf("oc:sleep:${stored.keyText}"), sessions().map { it.meta.clientRecordId })
        assertEquals(bed, sessions().single().start)
        assertEquals(wake, LocalStore(db).loadCursor().last(MetricKind.SLEEP))
        assertEquals(listOf("oc:sleep:${stored.keyText}"), SleepStore(db).sleepHealthRecordIds(stored.night))
        assertNotNull(SleepStore(db).mirroredNight(stored.night))
        val calls = hc.calls.size
        writer.flush()
        assertEquals(calls, hc.calls.size, "an unchanged night is not written again")
    }

    @Test
    fun aQueuedEditReplacesTheNightAndIsClearedAndItsMarksMove() = runBlocking<Unit> {
        saveNight()
        writer.flush()
        val store = SleepStore(db)
        val row = assertNotNull(store.sleepSummary(wake, zone))
        val times = SleepEdit.Times(bed.minusSeconds(3_600), bed.minusSeconds(3_600), wake)
        val edited = SleepEdit.recompute(hypnogram, times)
        assertTrue(store.applySleepEdit(wake, times, SleepStaging.summary(edited), edited, now, zone))
        store.setPendingSleepReconcile(PendingSleepReconcile(row.night, times.inBedStart, times.sleepOnset, times.sleepWake, edited), zone, now)

        writer.flush()

        val s = sessions().single()
        assertEquals("oc:sleep:${row.keyText}", s.meta.clientRecordId)
        assertEquals(bed.minusSeconds(3_600), s.start, "the edited bedtime")
        assertTrue(store.pendingSleepReconciles().isEmpty())
        assertTrue(store.pendingSleepEditHealthWrites().isEmpty(), "the edit's extension is marked written")
    }

    @Test
    fun anUnreadableQueueIsReportedAsASleepFailureAndLeftAsStored() = runBlocking<Unit> {
        saveNight()
        sql("INSERT INTO store_kv (key, value, updated_at) VALUES ('sleep.edit.pending-reconcile.v2', '{not a queue', 0)")

        val result = writer.flush()

        assertEquals("UnreadableEditQueueException", result.failures[RecordType.SLEEP_SESSION])
        assertEquals(listOf("{not a queue"), query("SELECT value FROM store_kv WHERE key = 'sleep.edit.pending-reconcile.v2'"))
        assertEquals(1, sessions().size, "the settled night is still written")
    }

    @Test
    fun aNapIsWrittenAndMarked() = runBlocking<Unit> {
        val start = Instant.parse("2026-06-19T13:00:00Z")
        SleepStore(db).saveNap(start, start.plusSeconds(3_600), 50, false, now = now, zone = zone)

        writer.flush()

        assertEquals(listOf("oc:nap:${start.toEpochMilli()}"), sessions().map { it.meta.clientRecordId })
        assertTrue(SleepStore(db).pendingNaps().isEmpty())
    }

    @Test
    fun aNightMovedToItsWakeDayIsWrittenUnderItsNewIdAndTheOldOneDeleted() = runBlocking<Unit> {
        saveNight(night = bed) // keyed by the day it started, as before the wake-day key
        val oldKey = assertNotNull(SleepStore(db).sleepSummary(bed, zone)).keyText
        writer.flush()
        assertEquals(listOf("oc:sleep:$oldKey"), sessions().map { it.meta.clientRecordId })

        SleepStore(db).rekeySleepNightsToWakeDay(zone, now)
        val newKey = assertNotNull(SleepStore(db).sleepSummary(wake, zone)).keyText
        writer.flush()

        assertEquals(listOf("oc:sleep:$newKey"), sessions().map { it.meta.clientRecordId }, "one night, under its new id")
        assertEquals(FakeHealthConnect.Call.Kind.DELETE to listOf("oc:sleep:$oldKey"), hc.calls.last().let { it.kind to it.ids })
    }
}
