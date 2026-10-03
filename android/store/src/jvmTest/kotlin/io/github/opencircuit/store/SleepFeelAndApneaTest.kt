package io.github.opencircuit.store

import io.github.opencircuit.ringkit.OSASpO2
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wearer's feel score and the night's apnea summary, read from upstream's source
 * (ios/OpenCircuit/Store/LocalStore.swift `setFeelScore` :2101-2109, `applyOSASummary` :2029-2039
 * @ b1c2fdd), which has no store test of either.
 */
class SleepFeelAndApneaTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val first = Instant.parse("2025-06-15T07:00:00Z")
    private val second = Instant.parse("2025-06-16T07:00:00Z")
    private val now = Instant.parse("2025-06-16T09:00:00Z")
    private val later = Instant.parse("2025-06-16T10:00:00Z")

    private val osa = OSASpO2.NightSummary(
        averageSpO2 = 95.5, minSpO2 = 88.0, timeBelow90Seconds = 120.0, odi = 3.5, validWindows = 40, durationHours = 8.0,
    )

    private suspend fun SleepStore.saveNight(end: Instant, zone: ZoneId = utc) {
        saveSleepSummary(
            SleepStaging.Summary(
                inBed = Duration.ofHours(8), awake = Duration.ofHours(1), light = Duration.ofHours(7), deep = Duration.ZERO, rem = Duration.ZERO,
            ),
            night = end, inBedStart = end.minusSeconds(8 * 3_600), inBedEnd = end, now = now, zone = zone,
        )
    }

    private suspend fun StoreDatabase.snapshot(): List<String> = queryRaw("SELECT * FROM stored_sleep_summary ORDER BY id")

    /** Upstream: `max(0, min(score, 9))` (:2106). */
    @Test
    fun theFeelScoreIsClampedToZeroThroughNine() = runBlocking<Unit> {
        for ((given, stored) in listOf(-1 to 0, 0 to 0, 5 to 5, 9 to 9, 10 to 9, Int.MIN_VALUE to 0, Int.MAX_VALUE to 9)) {
            withInMemoryStore { db ->
                val store = SleepStore(db)
                store.saveNight(first)
                val before = assertNotNull(store.sleepSummary(first, utc))

                store.setFeelScore(given, night = first, now = later, zone = utc)

                assertEquals(before.copy(feelScore = stored, updatedAt = later), store.sleepSummary(first, utc), "score $given")
            }
        }
    }

    @Test
    fun theFeelScoreLandsOnTheNightOfTheDayInTheZoneGiven() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kolkata = ZoneId.of("Asia/Kolkata")
            val store = SleepStore(db)
            store.saveNight(Instant.parse("2025-06-15T20:00:00Z"), kolkata) // 01:30 on the 16th in Kolkata

            store.setFeelScore(7, night = Instant.parse("2025-06-16T03:00:00Z"), now = later, zone = kolkata)

            assertEquals(7, assertNotNull(store.sleepSummary(Instant.parse("2025-06-16T03:00:00Z"), kolkata)).feelScore)
        }
    }

    /** A rating only makes sense once a night exists: no night, nothing written. */
    @Test
    fun aFeelScoreForADayWithNoNightWritesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNight(first)
            val before = db.snapshot()

            store.setFeelScore(7, night = first.plusSeconds(3 * 86_400), now = later, zone = utc)

            assertEquals(before, db.snapshot())
        }
    }

    /** Upstream's `try?` reads a failing lookup as "no night" and returns silently (:2105). Here it throws. */
    @Test
    fun aFailingLookupFailsTheFeelScore() = runBlocking<Unit> {
        withInMemoryStore { db ->
            SleepStore(db).saveNight(first)
            val before = db.snapshot()
            val real = db.sleepDao()
            val failing = object : SleepDao by real {
                override suspend fun summaryAt(night: Instant) = error("injected failure")
            }

            assertFailsWith<IllegalStateException> { SleepStore(db, failing).setFeelScore(7, night = first, now = later, zone = utc) }

            assertEquals(before, db.snapshot())
        }
    }

    /** The apnea summary attaches to the LATEST night (:2030), whatever night the burst came from. */
    @Test
    fun anApneaSummaryLandsOnTheLatestNightOnly() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNight(second)
            store.saveNight(first)
            val earlier = assertNotNull(store.sleepSummary(first, utc))
            val latest = assertNotNull(store.sleepSummary(second, utc))

            assertTrue(store.applyOSASummary(osa, now = later))

            assertEquals(
                latest.copy(osaAvgSpO2 = 95.5, osaMinSpO2 = 88.0, osaTimeBelow90Sec = 120.0, osaODI = 3.5, osaValidWindows = 40, updatedAt = later),
                store.sleepSummary(second, utc),
            )
            assertEquals(earlier, store.sleepSummary(first, utc))
            assertEquals(
                listOf("real|real|real|real"),
                db.queryRaw(
                    "SELECT typeof(osa_avg_spo2), typeof(osa_min_spo2), typeof(osa_time_below_90_sec), typeof(osa_odi) " +
                        "FROM stored_sleep_summary WHERE osa_valid_windows = 40",
                ),
            )
        }
    }

    @Test
    fun anApneaSummaryWithNoValidWindowOrNoNightIsNotApplied() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            assertFalse(store.applyOSASummary(osa, now = later), "no night stored")

            store.saveNight(first)
            val before = db.snapshot()
            assertFalse(store.applyOSASummary(osa.copy(validWindows = 0), now = later), "no valid window")
            assertFalse(store.applyOSASummary(osa.copy(validWindows = -1), now = later), "a negative window count")
            assertEquals(before, db.snapshot())
        }
    }

    /** Upstream swallows a failed save and still returns true (:2037-2038). Here the failure is thrown. */
    @Test
    fun aFailedApneaWriteThrowsAndChangesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            SleepStore(db).saveNight(first)
            val before = db.snapshot()
            val real = db.sleepDao()
            val failingWrite = object : SleepDao by real {
                override suspend fun updateSummary(row: StoredSleepSummaryEntity) = error("injected failure")
            }
            val failingLookup = object : SleepDao by real {
                override suspend fun latestSummary() = error("injected failure")
            }

            assertFailsWith<IllegalStateException> { SleepStore(db, failingWrite).applyOSASummary(osa, now = later) }
            assertFailsWith<IllegalStateException> { SleepStore(db, failingLookup).applyOSASummary(osa, now = later) }

            assertEquals(before, db.snapshot())
        }
    }

    /**
     * SQLite binds NaN as NULL, which the NOT NULL columns refuse, and an infinite saturation or event
     * rate is no reading; upstream would store either. Here a summary with any such figure is refused
     * before anything is written.
     */
    @Test
    fun anApneaSummaryWithAFigureThatIsNotANumberIsRefusedAndNothingWritten() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNight(first)
            val before = db.snapshot()
            val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).flatMap { v ->
                listOf(osa.copy(averageSpO2 = v), osa.copy(minSpO2 = v), osa.copy(timeBelow90Seconds = v), osa.copy(odi = v))
            }

            for (summary in bad) {
                assertFailsWith<IllegalArgumentException>("$summary") { store.applyOSASummary(summary, now = later) }
            }

            assertEquals(before, db.snapshot())
        }
    }
}
