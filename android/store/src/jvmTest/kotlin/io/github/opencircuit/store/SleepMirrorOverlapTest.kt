package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Port of upstream ios/OpenCircuitTests/SleepMirrorOverlapTests.swift (@ b1c2fdd): a night is
 * resolved by in-bed overlap against the stored rows, not by the start-of-day of a span's first
 * segment, so a bedtime that straddles midnight cannot resolve to a different day than the stored
 * night. Named for the health mirror upstream, but it calls only the store.
 *
 * Upstream ran in the simulator's zone; here the zone is UTC. Each save names a fixed `now`.
 */
class SleepMirrorOverlapTest {

    private val ref = Instant.ofEpochSecond(1_750_000_000)
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-16T09:00:00Z")

    private fun at(hours: Double): Instant = ref.plusMillis((hours * 3_600_000).toLong())

    private fun startOfDay(t: Instant): Instant = t.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    private suspend fun seedNight(store: SleepStore, night: Instant, inBedStart: Instant, inBedEnd: Instant) {
        val summary = SleepStaging.Summary(
            inBed = Duration.between(inBedStart, inBedEnd), awake = Duration.ofMinutes(30), light = Duration.ofHours(5),
            deep = Duration.ofMinutes(90), rem = Duration.ofMinutes(60),
        )
        store.saveSleepSummary(
            summary, night = night, inBedStart = inBedStart, inBedEnd = inBedEnd, sleepOnset = inBedStart, sleepWake = inBedEnd,
            now = now, zone = zone,
        )
    }

    /** Upstream `testOverlappingSpanResolvesTheRow` (`:37`). */
    @Test
    fun overlappingSpanResolvesTheRow() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seedNight(store, night = at(0.0), inBedStart = at(0.0), inBedEnd = at(8.0))
            // A re-drain whose span is shifted but still overlaps the stored in-bed window.
            val row = store.sleepSummaryOverlapping(start = at(1.0), end = at(9.0))
            assertEquals(at(0.0), row?.inBedStart, "an overlapping span must resolve the stored night")
        }
    }

    /** Upstream `testNonOverlappingSpanReturnsNil` (`:45`). */
    @Test
    fun nonOverlappingSpanReturnsNil() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seedNight(store, night = at(0.0), inBedStart = at(0.0), inBedEnd = at(8.0))
            assertNull(store.sleepSummaryOverlapping(start = at(10.0), end = at(12.0)), "a span that doesn't touch the in-bed window must not resolve it")
        }
    }

    /** Upstream `testPicksLargestOverlapAmongNights` (`:52`). */
    @Test
    fun picksLargestOverlapAmongNights() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seedNight(store, night = at(-24.0), inBedStart = at(-25.0), inBedEnd = at(-17.0)) // prior night
            seedNight(store, night = at(0.0), inBedStart = at(0.0), inBedEnd = at(8.0)) // last night
            // Span overlaps last night by ~7 h and the prior night not at all.
            val row = store.sleepSummaryOverlapping(start = at(1.0), end = at(9.0))
            assertEquals(at(0.0), row?.inBedStart)
        }
    }

    /** Upstream `testResolvesEvenWhenSpanStartDayDiffersFromNightKey` (`:61`). */
    @Test
    fun resolvesEvenWhenSpanStartDayDiffersFromNightKey() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            // The night starts just after midnight (keyed to day D); the re-drain span starts just
            // before it (day D-1): the bedtime-straddles-midnight case.
            val midnight = startOfDay(ref)
            val inBedStart = midnight.plusSeconds(15 * 60) // 00:15, day D
            val inBedEnd = inBedStart.plusSeconds(8 * 3_600)
            seedNight(store, night = inBedStart, inBedStart = inBedStart, inBedEnd = inBedEnd)
            val rowDay = startOfDay(inBedStart) // day D
            val queryStart = midnight.minusSeconds(30 * 60) // 23:30, day D-1
            assertNotEquals(rowDay, startOfDay(queryStart), "precondition: query start is on an earlier calendar day than the row key")
            val resolved = store.sleepSummaryOverlapping(start = queryStart, end = inBedEnd)
            assertEquals(rowDay, resolved?.night, "overlap resolution must find the row regardless of the day-key mismatch")
        }
    }
}
