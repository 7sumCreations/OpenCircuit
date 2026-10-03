package io.github.opencircuit.store

import io.github.opencircuit.ringkit.BatteryTTE
import io.github.opencircuit.ringkit.CapturedFrame
import io.github.opencircuit.ringkit.CursorSpan
import io.github.opencircuit.ringkit.HealthNotification
import io.github.opencircuit.ringkit.HistoricalSportFrame
import io.github.opencircuit.ringkit.HistoryFrameCapture
import io.github.opencircuit.ringkit.RingActivityEventLedger
import io.github.opencircuit.ringkit.RingEvent
import io.github.opencircuit.ringkit.SyncAlert
import io.github.opencircuit.ringkit.WorkoutSessionSnapshot
import io.github.opencircuit.ringkit.WorkoutSportType
import io.github.opencircuit.store.codec.Decoded
import io.github.opencircuit.store.codec.EnergyLedgerDayCodec
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The typed accessors over the key-value table: one key per stored value, named as upstream names
 * its `UserDefaults` key (a per-ring value takes `/<ring id>`), each loaded through its codec and
 * failing closed as upstream does when the stored value cannot be read — a ledger, capture,
 * history or span list reads empty, a workout snapshot reads as none.
 *
 * Kotlin-only. Every unreadable fixture is planted on the raw path (SQL), never through a save.
 */
class BlobStoreTest {

    private val now = Instant.parse("2026-10-03T08:00:00Z")
    private val ring = "ring-A"

    /** Stores [value] under [key] directly, as a damaged or older value would be found. */
    private suspend fun StoreDatabase.plant(key: String, value: String) =
        execRaw("INSERT OR REPLACE INTO store_kv(`key`, value, updated_at) VALUES ('$key', '${value.replace("'", "''")}', 1)")

    private suspend fun StoreDatabase.rows(key: String) = queryRaw("SELECT value, updated_at FROM store_kv WHERE `key` = '$key'")

    @Test
    fun eachValueRoundTripsUnderItsUpstreamKeyName() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            val ledger = RingActivityEventLedger(mapOf("r1" to listOf(RingEvent(0x24, 1, 77L))), mapOf("r1" to RingActivityEventLedger.Overflow(2, now)))
            val capture = HistoryFrameCapture(listOf(CapturedFrame(now, byteArrayOf(0x4c, 0))))
            val discharge = listOf(BatteryTTE.Sample(percent = 80, at = now))
            val charge = listOf(BatteryTTE.Sample(percent = 20, at = now.plusSeconds(60)))
            val spans = listOf(CursorSpan(10, 20))
            val sport = listOf(HistoricalSportFrame.Sample(cursor = 5, heartRate = 90, steps = 3))
            val alerts = mapOf(SyncAlert.LOW_BATTERY to now)
            val health = mapOf(HealthNotification.HIGH_HR to now)
            val nights = mapOf(HealthNotification.FEVER to 20261002L)

            blobs.saveActivityEvents(ledger, now)
            blobs.saveHistoryCapture(ring, capture, now)
            blobs.saveBatteryHistory(ring, BlobStore.BatteryHistory.DISCHARGE, discharge, now)
            blobs.saveBatteryHistory(ring, BlobStore.BatteryHistory.CHARGE, charge, now)
            blobs.saveWorkoutSpans(ring, BlobStore.WorkoutSpans.RESOLVED, spans, now)
            blobs.saveWorkoutSpans(ring, BlobStore.WorkoutSpans.NOTIFIED, spans + CursorSpan(30), now)
            blobs.saveSportSamples(ring, sport, now)
            blobs.saveStrandedCounters(ring, setOf(3L, 1L), now)
            blobs.saveSyncAlertLastFired(alerts, now)
            blobs.saveHealthLastFired(health, now)
            blobs.saveHealthLastNight(nights, now)

            assertEquals(ledger, blobs.loadActivityEvents())
            assertEquals(capture, blobs.loadHistoryCapture(ring))
            assertEquals(discharge, blobs.loadBatteryHistory(ring, BlobStore.BatteryHistory.DISCHARGE))
            assertEquals(charge, blobs.loadBatteryHistory(ring, BlobStore.BatteryHistory.CHARGE))
            assertEquals(spans, blobs.loadWorkoutSpans(ring, BlobStore.WorkoutSpans.RESOLVED))
            assertEquals(spans + CursorSpan(30), blobs.loadWorkoutSpans(ring, BlobStore.WorkoutSpans.NOTIFIED))
            assertEquals(sport, blobs.loadSportSamples(ring))
            assertEquals(setOf(1L, 3L), blobs.loadStrandedCounters(ring))
            assertEquals(alerts, blobs.loadSyncAlertLastFired())
            assertEquals(health, blobs.loadHealthLastFired())
            assertEquals(nights, blobs.loadHealthLastNight())

            // The key names are upstream's, with `/<ring>` for a per-ring value; each row carries `now`.
            val keys = db.queryRaw("SELECT `key`, updated_at FROM store_kv ORDER BY `key`")
            assertEquals(
                listOf(
                    "alerts.health.lastFired", "alerts.health.lastNight",
                    "battery.chargeHistory.v1/ring-A", "battery.tteHistory.v1/ring-A",
                    "diagnostics.historyCapture.v1/ring-A", "obs.alertLastFired", "ring.activityEvents.v2",
                    "sleep.unpersistedEpochCounters/ring-A",
                    "workout.automaticDetection.notified.v2/ring-A", "workout.automaticDetection.resolved.v2/ring-A",
                    "workout.automaticDetection.samples.v1/ring-A",
                ).map { "$it|${now.toEpochMilli()}" },
                keys,
            )
        }
    }

    @Test
    fun twoRingsNeverShareAPerRingValue() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            blobs.saveStrandedCounters("ring-A", setOf(1L), now)
            blobs.saveStrandedCounters("ring-B", setOf(2L), now)
            assertEquals(setOf(1L), blobs.loadStrandedCounters("ring-A"))
            assertEquals(setOf(2L), blobs.loadStrandedCounters("ring-B"))
            assertEquals(emptySet(), blobs.loadStrandedCounters("ring-C"))
        }
    }

    @Test
    fun aRingIdThatCouldNotNameItsOwnKeyIsRefused() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            assertFailsWith<IllegalArgumentException> { blobs.loadStrandedCounters("") }
            assertFailsWith<IllegalArgumentException> { blobs.saveStrandedCounters("a/b", setOf(1L), now) }
        }
    }

    @Test
    fun aLedgerOrCaptureThatIsAbsentOrUnreadableLoadsEmpty() = runBlocking<Unit> {
        // Garbage, a missing key, a key of the wrong type, and an empty value.
        val unreadable = listOf("not json", """{"events":{}}""", """{"events":[],"overflow":{}}""", """{"frames":{}}""", "")
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            assertEquals(RingActivityEventLedger(), blobs.loadActivityEvents())
            assertEquals(HistoryFrameCapture(), blobs.loadHistoryCapture(ring))
            for (v in unreadable) {
                db.plant("ring.activityEvents.v2", v)
                db.plant("diagnostics.historyCapture.v1/$ring", v)
                assertEquals(RingActivityEventLedger(), blobs.loadActivityEvents(), v)
                assertEquals(HistoryFrameCapture(), blobs.loadHistoryCapture(ring), v)
            }
        }
    }

    @Test
    fun everyOtherListOrLedgerThatIsUnreadableLoadsEmpty() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            for (v in listOf("garbage", "[{}]", """{"x":"y"}""", """[{"cursor":1}]""")) {
                for (key in listOf(
                    "battery.tteHistory.v1/$ring", "battery.chargeHistory.v1/$ring",
                    "workout.automaticDetection.resolved.v2/$ring", "workout.automaticDetection.notified.v2/$ring",
                    "workout.automaticDetection.samples.v1/$ring", "sleep.unpersistedEpochCounters/$ring",
                    "obs.alertLastFired", "alerts.health.lastFired", "alerts.health.lastNight",
                )) {
                    db.plant(key, v)
                }
                assertEquals(emptyList(), blobs.loadBatteryHistory(ring, BlobStore.BatteryHistory.DISCHARGE), v)
                assertEquals(emptyList(), blobs.loadBatteryHistory(ring, BlobStore.BatteryHistory.CHARGE), v)
                assertEquals(emptyList(), blobs.loadWorkoutSpans(ring, BlobStore.WorkoutSpans.RESOLVED), v)
                assertEquals(emptyList(), blobs.loadWorkoutSpans(ring, BlobStore.WorkoutSpans.NOTIFIED), v)
                assertEquals(emptyList(), blobs.loadSportSamples(ring), v)
                assertEquals(emptySet(), blobs.loadStrandedCounters(ring), v)
                assertEquals(emptyMap(), blobs.loadSyncAlertLastFired(), v)
                assertEquals(emptyMap(), blobs.loadHealthLastFired(), v)
                assertEquals(emptyMap(), blobs.loadHealthLastNight(), v)
            }
        }
    }

    @Test
    fun aWorkoutSnapshotIsNoneWhenAbsentUnreadableUnwritableOrCleared() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            val snapshot = WorkoutSessionSnapshot(WorkoutSportType.entries.first(), now, now.plusSeconds(60), 3, -0.0, 120, 150)
            assertNull(blobs.loadWorkoutSnapshot())

            assertTrue(blobs.saveWorkoutSnapshot(snapshot, now))
            val read = assertNotNull(blobs.loadWorkoutSnapshot())
            assertEquals(snapshot, read)
            assertEquals(-0.0, read.activeKcal) // the sign of -0 kcal survives the store

            // An energy JSON cannot hold is not saved, and the stored snapshot stays (upstream's `encoded()` nil).
            assertFalse(blobs.saveWorkoutSnapshot(snapshot.copy(activeKcal = Double.NaN), now.plusSeconds(5)))
            assertEquals(snapshot, blobs.loadWorkoutSnapshot())

            blobs.clearWorkoutSnapshot()
            assertNull(blobs.loadWorkoutSnapshot())
            assertEquals(emptyList(), db.rows("workout.sessionSnapshot"))

            db.plant("workout.sessionSnapshot", """{"sport":"walk","hrSampleCount":5000000000}""")
            assertNull(blobs.loadWorkoutSnapshot())
        }
    }

    @Test
    fun anEnergyLedgerDayIsKeyedByItsLocalDateAndAnUnreadableOneIsNeverReadAsAbsent() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            val kolkata = ZoneId.of("Asia/Kolkata")
            val day = EnergyLedgerDay(
                Instant.parse("2026-10-02T18:30:00Z"), kolkata, null, listOf(5.0, 7.5), 0.0, 12.5, 12.5, null, null, 0.0,
            )
            val date = LocalDate.of(2026, 10, 3)
            assertNull(blobs.loadEnergyLedgerDay(date))

            assertTrue(blobs.saveEnergyLedgerDay(day, now))
            assertEquals(Decoded.Readable(day), blobs.loadEnergyLedgerDay(date))
            assertNull(blobs.loadEnergyLedgerDay(date.minusDays(1)))
            assertEquals(1, db.rows("hk.activeEnergy/2026-10-03").size)

            // A state JSON cannot hold is not saved; the stored day stays.
            assertFalse(blobs.saveEnergyLedgerDay(day.copy(carryKcal = Double.NaN), now))
            assertFalse(blobs.saveEnergyLedgerDay(day.copy(bucketKcal = listOf(-1.0)), now))
            assertEquals(Decoded.Readable(day), blobs.loadEnergyLedgerDay(date))

            // Unreadable is reported as unreadable — a caller must not plan it as a fresh day.
            val damaged = """{"bucketKcal":[-3.0],"carryKcal":0.0,"day":1790965800000,"dayTZ":"Asia/Kolkata",""" +
                """"savedKcal":0.0,"workoutCreditedKcal":0.0,"writtenKcal":0.0}"""
            db.plant("hk.activeEnergy/2026-10-03", damaged)
            assertEquals(damaged, assertIs<Decoded.Unreadable>(blobs.loadEnergyLedgerDay(date)).raw)

            // A readable state stored under another day's key is not that day's state.
            db.plant("hk.activeEnergy/2026-10-04", assertNotNull(EnergyLedgerDayCodec.encode(day)))
            assertIs<Decoded.Unreadable>(blobs.loadEnergyLedgerDay(date.plusDays(1)))
        }
    }

    /**
     * Kotlin-only: the ledger's rules live in its codec, not its constructor, so the save is the
     * gate. Every day the save accepts reads back under its own date — offset and region zones,
     * negative totals (kept, as upstream), -0.0, the extreme finite doubles, no buckets — and a day
     * whose instant cannot be stored at all fails the save loudly without writing anything.
     */
    @Test
    fun everyEnergyLedgerDayTheSaveAcceptsReadsBackUnderItsOwnDate() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            val zones = listOf(
                ZoneOffset.UTC, ZoneOffset.ofHoursMinutes(-9, -30), ZoneId.of("UTC"), ZoneId.of("GMT+01:00"),
                ZoneId.of("Pacific/Kiritimati"), ZoneId.of("America/St_Johns"),
            )
            for (zone in zones) {
                val midnight = LocalDate.of(2026, 10, 3).atStartOfDay(zone).toInstant()
                val day = EnergyLedgerDay(
                    midnight, zone, midnight.plusSeconds(3_600), listOf(0.0, Double.MIN_VALUE, Double.MAX_VALUE),
                    -4.0, -0.0, Double.MAX_VALUE, midnight, midnight, -Double.MAX_VALUE,
                )
                for (d in listOf(day, day.copy(bucketKcal = emptyList(), anchorEnd = null, bucketSeedDay = null, workoutCreditedDay = null))) {
                    assertTrue(blobs.saveEnergyLedgerDay(d, now), "$zone")
                    assertEquals(Decoded.Readable(d), blobs.loadEnergyLedgerDay(d.localDate), "$zone")
                }
            }

            val unstorable = EnergyLedgerDay(Instant.MAX, ZoneOffset.UTC, null, emptyList(), 0.0, 0.0, 0.0, null, null, 0.0)
            assertFails { blobs.saveEnergyLedgerDay(unstorable, now) }
            assertEquals(emptyList(), db.queryRaw("SELECT `key` FROM store_kv WHERE `key` LIKE 'hk.activeEnergy/+%'"))
        }
    }
}
