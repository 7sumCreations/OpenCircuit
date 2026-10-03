package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the nap writes could differ from upstream (ios/OpenCircuit/Store/LocalStore.swift
 * :2865-2995 @ b1c2fdd) through what Kotlin, Java time or the store add: instants cut to the stored
 * millisecond, Java's rounding, a calendar window across a clock change, and the strict edges of
 * the night overlap. Each was run red against a stub or a named mutation first.
 */
class NapHazardTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-20T12:00:00Z")

    private fun at(text: String): Instant = Instant.parse(text)

    private val summary = SleepStaging.Summary(
        inBed = Duration.ofHours(8), awake = Duration.ofHours(1), light = Duration.ofHours(7), deep = Duration.ZERO, rem = Duration.ZERO,
    )

    /** A night in bed `[inBedStart, inBedEnd]`, keyed by the day of [night] in [zone]. */
    private suspend fun SleepStore.night(inBedStart: Instant, inBedEnd: Instant, night: Instant = inBedEnd, zone: ZoneId = utc) {
        saveSleepSummary(summary, night = night, inBedStart = inBedStart, inBedEnd = inBedEnd, now = now, zone = zone)
    }

    private suspend fun SleepStore.allNaps(): List<StoredNapRecord> = naps(from = at("2000-01-01T00:00:00Z"), to = at("2100-01-01T00:00:00Z"))

    /**
     * Swift compares `Date`s below the millisecond; the store keeps whole milliseconds. A nap ending
     * 0.4 ms after a night begins shares time with it to Swift but abuts it as stored, so it is cut
     * before the overlap is measured and saved — the overlap is the one between the stored forms.
     */
    @Test
    fun aNapEndingWithinAMillisecondAfterANightBeginsAbutsItAsStoredAndIsSaved() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T06:00:00Z"))

            store.saveNap(at("2025-06-15T20:00:00Z"), at("2025-06-15T22:00:00Z").plusNanos(400_000), 120, false, now = now, zone = utc)
            assertFalse(store.addManualNap(at("2025-06-16T06:00:00Z").minusNanos(1), at("2025-06-16T07:00:00Z"), now = now, zone = utc))
            assertTrue(store.addManualNap(at("2025-06-16T06:00:00Z"), at("2025-06-16T07:00:00Z").plusNanos(999_999), now = now, zone = utc))

            assertEquals(
                listOf(at("2025-06-15T20:00:00Z") to at("2025-06-15T22:00:00Z"), at("2025-06-16T06:00:00Z") to at("2025-06-16T07:00:00Z")),
                store.allNaps().map { it.start to it.end },
            )
        }
    }

    /**
     * A start a fraction of a millisecond off a stored nap's is that nap: a re-detection updates it, a
     * manual add is refused as a duplicate, and an edit finds it.
     */
    @Test
    fun aStartWithinTheSameMillisecondAsAStoredNapsFindsThatNap() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val start = at("2025-06-15T13:00:00Z")
            store.saveNap(start, at("2025-06-15T14:00:00Z"), 55, false, now = now, zone = utc)

            store.saveNap(start.plusNanos(400_000), at("2025-06-15T14:30:00Z"), 85, false, now = now, zone = utc)
            assertFalse(store.addManualNap(start.plusNanos(999_999), at("2025-06-15T15:00:00Z"), now = now, zone = utc))
            assertTrue(store.editNap(start.plusNanos(1), at("2025-06-15T13:10:00Z"), at("2025-06-15T14:10:00Z"), now = now, zone = utc))

            val nap = store.allNaps().single()
            assertEquals(Triple(start, at("2025-06-15T14:30:00Z"), true), Triple(nap.start, nap.end, nap.isManuallyEdited))
        }
    }

    /**
     * Upstream rounds a manual nap's minutes with `.rounded()`, half away from zero (measured, Swift
     * 6.3.2: `(-0.5).rounded()` is −1, where Java's `Math.round(-0.5)` is 0). The window is never
     * empty or reversed by then (`end > start` is checked first), so the ties met are positive: half a
     * minute rounds up, a millisecond less rounds down. The three-hour long-nap line is included.
     */
    @Test
    fun aManualNapsMinutesRoundHalfAwayFromZeroAndThreeHoursExactlyIsLong() = runBlocking<Unit> {
        val cases = listOf(
            Duration.ofSeconds(90) to (2 to false),
            Duration.ofMillis(89_999) to (1 to false),
            Duration.ofSeconds(30) to (1 to false),
            Duration.ofMillis(29_999) to (0 to false),
            Duration.ofHours(3).minusMillis(1) to (180 to false),
            Duration.ofHours(3) to (180 to true),
        )
        for ((length, expected) in cases) {
            withInMemoryStore { db ->
                val store = SleepStore(db)
                val start = at("2025-06-15T13:00:00Z")
                assertTrue(store.addManualNap(start, start.plus(length), now = now, zone = utc), "$length")
                val added = store.allNaps().single()
                assertEquals(expected, added.asleepMin to added.isLongNap, "add $length")

                // An edit of an automatic nap rounds the same way.
                val auto = at("2025-06-16T13:00:00Z")
                store.saveNap(auto, auto.plusSeconds(600), 10, false, now = now, zone = utc)
                assertTrue(store.editNap(auto, auto, auto.plus(length), now = now, zone = utc))
                val edited = store.naps(on = auto, zone = utc).single()
                assertEquals(expected, edited.asleepMin to edited.isLongNap, "edit $length")
            }
        }
    }

    /**
     * Upstream looks for the nap's night among the nights keyed from two calendar days before the nap's
     * start to two after its end (`Calendar.date(byAdding: .day)`, both ends included). Calendar days in
     * the zone given, not 48 hours: across Paris's 2025-10-26 clock change, two days before 12:00 CET on
     * the 27th is 12:00 CEST on the 25th — 10:00Z, 49 hours earlier. A night keyed at 10:00Z (midnight
     * in Honolulu) whose in-bed window covers the nap is found in Paris, so the nap is not saved.
     */
    @Test
    fun theNightsLookedAtAreThoseKeyedWithinTwoCalendarDaysOfTheNapInTheZoneGiven() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val paris = ZoneId.of("Europe/Paris")
            val store = SleepStore(db)
            store.night(at("2025-10-25T09:00:00Z"), at("2025-10-27T12:00:00Z"), night = at("2025-10-25T12:00:00Z"), zone = ZoneId.of("Pacific/Honolulu"))
            assertEquals(at("2025-10-25T10:00:00Z"), store.recentSleepSummaries().single().night)
            val napStart = at("2025-10-27T11:00:00Z")
            assertEquals(Duration.ofHours(49), Duration.between(napStart.atZone(paris).minusDays(2).toInstant(), napStart))

            store.saveNap(napStart, napStart.plusSeconds(1_800), 30, false, now = now, zone = paris)
            assertFalse(store.addManualNap(napStart, napStart.plusSeconds(1_800), now = now, zone = paris))

            assertEquals(emptyList(), store.allNaps())
        }
    }

    /** Upstream's overlap is strict (:2990-2993): a nap that ends where a night begins, or begins where it ends, is not inside it. */
    @Test
    fun aNapThatOnlyTouchesANightsRecordedOrEditedWindowIsNotInsideIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.night(at("2025-06-15T22:00:00Z"), at("2025-06-16T06:00:00Z"))
            store.night(at("2025-06-16T23:00:00Z"), at("2025-06-17T07:00:00Z"))
            store.applySleepEdit(
                night = at("2025-06-17T07:00:00Z"),
                times = SleepEdit.Times(at("2025-06-16T22:00:00Z"), at("2025-06-16T22:30:00Z"), at("2025-06-17T08:00:00Z")),
                summary = summary, now = now, zone = utc,
            )

            store.saveNap(at("2025-06-15T21:00:00Z"), at("2025-06-15T22:00:00Z"), 60, false, now = now, zone = utc) // ends at the start
            store.saveNap(at("2025-06-16T06:00:00Z"), at("2025-06-16T07:00:00Z"), 60, false, now = now, zone = utc) // starts at the end
            assertTrue(store.addManualNap(at("2025-06-16T21:00:00Z"), at("2025-06-16T22:00:00Z"), now = now, zone = utc)) // edited start
            assertTrue(store.addManualNap(at("2025-06-17T08:00:00Z"), at("2025-06-17T09:00:00Z"), now = now, zone = utc)) // edited end
            // One millisecond into either window is inside.
            assertFalse(store.addManualNap(at("2025-06-16T05:59:59.999Z"), at("2025-06-16T06:30:00Z"), now = now, zone = utc))
            assertFalse(store.addManualNap(at("2025-06-17T07:59:59.999Z"), at("2025-06-17T09:30:00Z"), now = now, zone = utc))

            assertEquals(
                listOf(at("2025-06-15T21:00:00Z"), at("2025-06-16T06:00:00Z"), at("2025-06-16T21:00:00Z"), at("2025-06-17T08:00:00Z")),
                store.allNaps().map { it.start },
            )
        }
    }
}
