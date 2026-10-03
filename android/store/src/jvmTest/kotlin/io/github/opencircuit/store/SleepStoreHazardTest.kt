package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepPersistOutcome
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Where a Kotlin and Room port of upstream's night save can go wrong where Swift and SwiftData
 * could not (ios/OpenCircuit/Store/LocalStore.swift:1582-1802, :1855-1910 @ b1c2fdd): the zone's
 * start of day, the stored form of "no timeline", 64-bit minutes in 32-bit columns, numbers SQLite
 * cannot keep, swallowed lookups, a failed save left half-applied, and ties between nights.
 */
class SleepStoreHazardTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-16T09:00:00Z")

    private fun summary(inBed: Duration, asleep: Duration) =
        SleepStaging.Summary(inBed = inBed, awake = inBed.minus(asleep), light = asleep, deep = Duration.ZERO, rem = Duration.ZERO)

    /**
     * São Paulo skipped 2018-11-04's midnight (clocks went from 00:00 to 01:00). Foundation's
     * `startOfDay` and `java.time` both give 01:00 local, 1 541 300 400 (measured on both). The night
     * is filed and found under that instant in the zone given, and not under UTC's midnight.
     */
    @Test
    fun aNightOnADayWithoutAMidnightIsFiledUnderTheFirstInstantOfThatDayInItsZone() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val saoPaulo = ZoneId.of("America/Sao_Paulo")
            val inBedStart = Instant.parse("2018-11-03T23:00:00Z")
            val inBedEnd = Instant.parse("2018-11-04T09:00:00Z")

            store.saveSleepSummary(
                summary(Duration.ofHours(10), Duration.ofHours(8)), night = inBedEnd, inBedStart = inBedStart, inBedEnd = inBedEnd,
                now = now, zone = saoPaulo,
            )

            assertEquals(listOf(Instant.ofEpochSecond(1_541_300_400)), db.sleepDao().allSummaries().map { it.night })
            assertEquals(480, store.sleepSummary(Instant.parse("2018-11-04T20:00:00Z"), saoPaulo)?.asleepMin)
            assertNull(store.sleepSummary(inBedEnd, utc), "UTC's midnight is another key")
        }
    }

    /**
     * A night saved without a timeline stores the 2-byte JSON `[]` (upstream's encoder writes the
     * same), so "no timeline" is non-empty bytes; its basis stays unknown and the three provenance
     * figures that need a timeline stay −1, while the measured and asserted seconds are the empty
     * breakdown's 0 (ios/OpenCircuit/Store/LocalStore.swift:1903-1909).
     */
    @Test
    fun aNightSavedWithoutATimelineStoresTheTwoByteEmptyListAndAnUnknownBasis() = runBlocking<Unit> {
        withInMemoryStore { db ->
            SleepStore(db).saveSleepSummary(
                summary(Duration.ofHours(8), Duration.ofHours(7)), night = now, inBedStart = now.minusSeconds(8 * 3_600), inBedEnd = now,
                now = now, zone = utc,
            )

            assertEquals(
                listOf("5B5D|5B5D||0.0|0.0|-1.0|-1.0|-1.0"),
                db.queryRaw(
                    "SELECT hex(hypnogram_data), hex(recorded_hypnogram_data), sleep_basis, measured_asleep_seconds, " +
                        "asserted_asleep_seconds, coverage_fraction, longest_gap_seconds, measured_efficiency FROM stored_sleep_summary",
                ),
            )
        }
    }

    /**
     * Upstream's minutes are a 64-bit `Int`; the columns are 32-bit here. A stage total past
     * `Int.MAX_VALUE` minutes (about 4 000 years) fails the write instead of wrapping to a negative
     * night, and nothing is stored.
     */
    @Test
    fun aStageTotalBeyondAnIntFailsTheWriteInsteadOfWrapping() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val huge = Duration.ofMinutes(Int.MAX_VALUE.toLong() + 1)

            assertFailsWith<ArithmeticException> {
                SleepStore(db).saveSleepSummary(
                    summary(huge, huge), night = now, inBedStart = now.minusSeconds(3_600), inBedEnd = now, now = now, zone = utc,
                )
            }

            assertEquals(listOf("0"), db.queryRaw("SELECT count(*) FROM stored_sleep_summary"))
        }
    }

    /**
     * SQLite binds NaN as NULL, which the NOT NULL columns refuse, and ±∞ is no temperature. A NaN
     * skin temperature is "not computed" upstream too (`NaN > 0` is false), so the stored one is
     * kept; +∞ is treated the same way here, where upstream would store it. Every REAL column the
     * save writes holds a real number.
     */
    @Test
    fun aNonFiniteSkinTemperatureKeepsTheStoredOneAndEveryWrittenRealColumnIsANumber() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val start = now.minusSeconds(10 * 3_600)
            store.saveSleepSummary(
                summary(Duration.ofHours(8), Duration.ofHours(6)), night = now, inBedStart = start, inBedEnd = start.plusSeconds(8 * 3_600),
                extras = SleepNightExtras(skinTempC = 33.5), now = now, zone = utc,
            )

            for ((i, temp) in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).withIndex()) {
                val outcome = store.saveSleepSummary(
                    summary(Duration.ofHours(9), Duration.ofHours(7).plusMinutes(i.toLong())), night = now, inBedStart = start,
                    inBedEnd = start.plusSeconds(9 * 3_600), extras = SleepNightExtras(skinTempC = temp), now = now, zone = utc,
                )
                assertEquals(SleepPersistOutcome.UPDATED, outcome, "skin temperature $temp")
                assertEquals(33.5, store.sleepSummary(now, utc)?.skinTempC, "skin temperature $temp")
            }

            assertEquals(
                listOf(List(7) { "real" }.joinToString("|")),
                db.queryRaw(
                    "SELECT typeof(efficiency), typeof(skin_temp_c), typeof(measured_asleep_seconds), typeof(asserted_asleep_seconds), " +
                        "typeof(coverage_fraction), typeof(longest_gap_seconds), typeof(measured_efficiency) FROM stored_sleep_summary",
                ),
            )
        }
    }

    /**
     * Upstream's span lookup is `try?`: a failing fetch falls through to the day lookup and the save
     * goes on (:1546-1551). Here it fails the save and nothing changes.
     */
    @Test
    fun aFailedSpanLookupFailsTheSaveInsteadOfFallingBackToTheDay() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val real = db.sleepDao()
            val start = now.minusSeconds(10 * 3_600)
            SleepStore(db).saveSleepSummary(
                summary(Duration.ofHours(8), Duration.ofHours(6)), night = now, inBedStart = start, inBedEnd = start.plusSeconds(8 * 3_600),
                now = now, zone = utc,
            )
            val before = SleepStore(db).sleepSummary(now, utc)
            val failing = object : SleepDao by real {
                override suspend fun overlapCandidates(start: Instant, end: Instant) = error("injected failure")
            }

            assertFails {
                SleepStore(db, failing).saveSleepSummary(
                    summary(Duration.ofHours(9), Duration.ofHours(8)), night = now, inBedStart = start,
                    inBedEnd = start.plusSeconds(9 * 3_600), now = now, zone = utc,
                )
            }

            assertEquals(listOf(before), real.allSummaries().map { it.toStoredNight(editedOnset = null) })
        }
    }

    /**
     * Upstream's failed `save()` leaves the changed model object dirty in its context, where any
     * later save of anything commits it. Here a failed write rolls back, and a later save of another
     * night leaves the first exactly as it was.
     */
    @Test
    fun aFailedUpdateLeavesTheNightAsStoredEvenAfterALaterSave() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val real = db.sleepDao()
            val start = now.minusSeconds(10 * 3_600)
            SleepStore(db).saveSleepSummary(
                summary(Duration.ofHours(8), Duration.ofHours(6)), night = now, inBedStart = start, inBedEnd = start.plusSeconds(8 * 3_600),
                extras = SleepNightExtras(sleepScore = 70), now = now, zone = utc,
            )
            val before = assertNotNull(SleepStore(db).sleepSummary(now, utc))
            val failing = object : SleepDao by real {
                override suspend fun updateSummary(row: StoredSleepSummaryEntity) = error("injected failure")
            }

            assertFails {
                SleepStore(db, failing).saveSleepSummary(
                    summary(Duration.ofHours(9), Duration.ofHours(8)), night = now, inBedStart = start,
                    inBedEnd = start.plusSeconds(9 * 3_600), extras = SleepNightExtras(sleepScore = 90), now = now.plusSeconds(60), zone = utc,
                )
            }
            val later = now.plusSeconds(2 * 86_400)
            SleepStore(db).saveSleepSummary(
                summary(Duration.ofHours(8), Duration.ofHours(7)), night = later, inBedStart = later.minusSeconds(8 * 3_600), inBedEnd = later,
                now = later, zone = utc,
            )

            assertEquals(before, SleepStore(db).sleepSummary(now, utc))
        }
    }

    /**
     * Upstream fetches every row unsorted and keeps the first of two equal overlaps, whichever the
     * fetch returned first (:2084-2097). Here the tie goes to the earlier night, whatever order the
     * rows were written in.
     */
    @Test
    fun aSpanOverlappingTwoNightsEquallyResolvesToTheEarlierNight() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val laterStart = Instant.parse("2025-06-15T00:00:00Z")
            val earlierStart = Instant.parse("2025-06-14T20:00:00Z")
            // The later night is written first, so it holds the lower row id.
            for (start in listOf(laterStart, earlierStart)) {
                val end = start.plusSeconds(2 * 3_600)
                store.saveSleepSummary(
                    summary(Duration.ofHours(2), Duration.ofHours(1)), night = end, inBedStart = start, inBedEnd = end, now = now, zone = utc,
                )
            }

            // One hour into each: 21:00..22:00 of the earlier night and 00:00..01:00 of the later.
            val resolved = store.sleepSummaryOverlapping(Instant.parse("2025-06-14T21:00:00Z"), Instant.parse("2025-06-15T01:00:00Z"))

            assertEquals(Instant.parse("2025-06-14T00:00:00Z"), resolved?.night)
        }
    }

    /**
     * The merge compares asleep time as upstream does, in whole rounded minutes times 60
     * (:1704-1708), not in the summary's seconds: 419.5 minutes rounds to the stored 420, so it is a
     * tie, and the wider window wins it — where 25 170 s against 25 200 s would keep the stored night.
     */
    @Test
    fun theMergeComparesRoundedMinutesSoHalfAMinuteLessSleepInAWiderWindowStillReplaces() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val start = Instant.parse("2025-06-14T22:00:00Z")
            val end = start.plusSeconds(8 * 3_600)
            store.saveSleepSummary(summary(Duration.ofHours(8), Duration.ofMinutes(420)), night = now, inBedStart = start, inBedEnd = end, now = now, zone = utc)

            val outcome = store.saveSleepSummary(
                summary(Duration.ofMinutes(490), Duration.ofSeconds(25_170)), night = now, inBedStart = start.minusSeconds(600),
                inBedEnd = end, now = now, zone = utc,
            )

            assertEquals(SleepPersistOutcome.UPDATED, outcome)
            assertEquals(start.minusSeconds(600), store.sleepSummary(now, utc)?.inBedStart)
        }
    }
}
