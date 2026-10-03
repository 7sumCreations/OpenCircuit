package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sleep reads' edges, upstream's predicates read from source
 * (ios/OpenCircuit/Store/LocalStore.swift:2042-2097 @ b1c2fdd), which has no store test of them:
 * a half-open night range, newest-first lists, a day found in the zone given, and an overlap that
 * needs a positive span, a known recorded window and more than zero shared time.
 */
class SleepReadsTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2025-07-01T00:00:00Z")
    private val d1 = Instant.parse("2025-06-14T00:00:00Z")
    private val d2 = Instant.parse("2025-06-15T00:00:00Z")
    private val d3 = Instant.parse("2025-06-16T00:00:00Z")

    private fun summary(asleepMin: Long) = SleepStaging.Summary(
        inBed = Duration.ofMinutes(480), awake = Duration.ofMinutes(480 - asleepMin), light = Duration.ofMinutes(asleepMin),
        deep = Duration.ZERO, rem = Duration.ZERO,
    )

    /** A night ending at 06:00 on [day], in bed from 22:00 the evening before. */
    private suspend fun SleepStore.saveNightEndingOn(day: Instant, asleepMin: Long = 420, zone: ZoneId = utc, extras: SleepNightExtras = SleepNightExtras()) {
        val end = day.plusSeconds(6 * 3_600)
        saveSleepSummary(summary(asleepMin), night = end, inBedStart = end.minusSeconds(8 * 3_600), inBedEnd = end, extras = extras, now = now, zone = zone)
    }

    @Test
    fun sleepSummariesAreTheNightsOfAHalfOpenRangeOldestFirst() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            for (day in listOf(d3, d1, d2)) store.saveNightEndingOn(day)

            assertEquals(listOf(d1, d2), store.sleepSummaries(from = d1, to = d3).map { it.night })
            assertEquals(emptyList(), store.sleepSummaries(from = d2, to = d2))
        }
    }

    @Test
    fun recentAndLatestAreNewestFirstAndBounded() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            assertNull(store.latestSleepSummary())
            assertEquals(emptyList(), store.recentSleepSummaries())

            for (day in listOf(d2, d3, d1)) store.saveNightEndingOn(day)

            assertEquals(d3, store.latestSleepSummary()?.night)
            assertEquals(listOf(d3, d2), store.recentSleepSummaries(limit = 2).map { it.night })
            assertEquals(listOf(d3, d2, d1), store.recentSleepSummaries().map { it.night })
        }
    }

    /** Kolkata is UTC+05:30: its 2025-06-15 runs from 2025-06-14T18:30Z to 2025-06-15T18:30Z. */
    @Test
    fun aNightIsFoundFromAnyInstantOfItsDayInTheZoneGiven() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val kolkata = ZoneId.of("Asia/Kolkata")
            val timeline = listOf(SleepSegment(Instant.parse("2025-06-14T20:00:00Z"), Instant.parse("2025-06-15T00:30:00Z"), SleepStage.ASLEEP_CORE))
            val end = Instant.parse("2025-06-15T00:30:00Z")
            store.saveSleepSummary(
                summary(270), night = end, inBedStart = Instant.parse("2025-06-14T20:00:00Z"), inBedEnd = end,
                extras = SleepNightExtras(hypnogram = timeline), now = now, zone = kolkata,
            )

            assertEquals(Instant.parse("2025-06-14T18:30:00Z"), store.sleepSummary(Instant.parse("2025-06-15T18:29:59Z"), kolkata)?.night)
            assertEquals(timeline, store.hypnogram(Instant.parse("2025-06-14T18:30:00Z"), kolkata))
            assertNull(store.sleepSummary(Instant.parse("2025-06-15T18:30:00Z"), kolkata), "the next local day")
            assertEquals(emptyList(), store.hypnogram(Instant.parse("2025-06-15T18:30:00Z"), kolkata))
        }
    }

    @Test
    fun anOverlapNeedsAPositiveSpanAndMoreThanZeroSharedTime() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNightEndingOn(d2)
            val start = d2.minusSeconds(2 * 3_600)
            val end = d2.plusSeconds(6 * 3_600)

            assertNull(store.sleepSummaryOverlapping(start.plusSeconds(3_600), start.plusSeconds(3_600)), "an empty span")
            assertNull(store.sleepSummaryOverlapping(start.plusSeconds(7_200), start.plusSeconds(3_600)), "a reversed span")
            assertNull(store.sleepSummaryOverlapping(end, end.plusSeconds(3_600)), "a span starting at the night's end")
            assertNull(store.sleepSummaryOverlapping(start.minusSeconds(3_600), start), "a span ending at the night's start")
            assertEquals(d2, store.sleepSummaryOverlapping(end.minusMillis(1), end.plusSeconds(3_600))?.night, "one shared millisecond")
        }
    }

    @Test
    fun aNightWithNoKnownRecordedWindowNeverOverlaps() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveSleepSummary(
                summary(420), night = d2, inBedStart = SleepEdit.DISTANT_PAST, inBedEnd = SleepEdit.DISTANT_PAST, now = now, zone = utc,
            )

            assertNull(store.sleepSummaryOverlapping(SleepEdit.DISTANT_PAST, Instant.parse("2100-01-01T00:00:00Z")))
        }
    }

    @Test
    fun anOverlapIsJudgedOnTheRecordedWindowNotTheEditedOne() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.saveNightEndingOn(d2)
            val editedStart = d2.plusSeconds(10 * 3_600).toEpochMilli()
            val editedEnd = d2.plusSeconds(12 * 3_600).toEpochMilli()
            db.execRaw("UPDATE stored_sleep_summary SET edited_in_bed_start = $editedStart, edited_in_bed_end = $editedEnd, is_manually_edited = 1")

            assertNull(store.sleepSummaryOverlapping(d2.plusSeconds(10 * 3_600), d2.plusSeconds(12 * 3_600)))
        }
    }
}
