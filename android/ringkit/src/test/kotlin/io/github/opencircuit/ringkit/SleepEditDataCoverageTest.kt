package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sleep editor's data-coverage widening: the epochs actually held for a night may widen the
 * editable bounds (capped at one plausible night), the stranded margin makes a real wake reachable
 * after the recorder stops, the truncation ceiling makes the late edge a pure function of the
 * recorded night, `validate` agrees with every edge the picker offers, and the paired window is
 * capped by the too-long rule.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepEditDataCoverageTests.swift
 * (@ b1c2fdd) — all 21 tests. Instants are built on the UTC wall clock exactly as upstream builds
 * them; Swift's `ClosedRange<Date>` is a `DateInterval`.
 */
class SleepEditDataCoverageTest {

    private fun d(day: Int, h: Int, m: Int): Instant = ZonedDateTime.of(2026, 8, day, h, m, 0, 0, ZoneOffset.UTC).toInstant()
    private fun range(a: Instant, b: Instant) = DateInterval(a, b)

    /** The user's night as the app recorded it after the loss. */
    private val recordedOnset = d(4, 7, 30)
    private val recordedWake = d(4, 8, 55)

    // the regression

    @Test
    fun unwidenedBoundsCannotReachTheRealBedtime() {
        val b = SleepEdit.bounds(recordedOnset, recordedWake)
        assertEquals(d(4, 1, 30), b.earliest, "onset − 6 h")
        assertEquals(d(4, 18, 30), b.latest, "one night past the parity bedtime (04:30 + 14 h)")
        assertTrue(b.latest > d(4, 14, 55), "…which is strictly more than the old wake + 6 h ceiling")
        assertTrue(b.earliest > d(4, 0, 15), "00:15 bedtime is still outside even the stranded margin")
    }

    @Test
    fun archiveCoverageWidensBoundsToReachTheRealBedtime() {
        // The archive still holds the evening before + the morning tail.
        val b = SleepEdit.bounds(recordedOnset, recordedWake, dataCoverage = range(d(3, 19, 45), d(4, 8, 53)))
        assertTrue(b.earliest <= d(4, 0, 15), "the real 00:15 bedtime must now be selectable")
        assertTrue(b.latest >= d(4, 8, 53))
    }

    @Test
    fun wideningNeverGoesTighterThanTheParityFloor() {
        // Coverage narrower than the margin contributes nothing.
        val b = SleepEdit.bounds(recordedOnset, recordedWake, dataCoverage = range(d(4, 7, 45), d(4, 8, 30)))
        assertEquals(d(4, 1, 30), b.earliest)
        assertEquals(d(4, 18, 30), b.latest)
        assertEquals(SleepEdit.bounds(recordedOnset, recordedWake), b, "narrow coverage must be indistinguishable from no coverage")
    }

    // the recorder stops mid-night (Gen 2 Air, 2026-08-22)

    /** Last epoch 02:31:51, staging ended the night at 02:08:51, real wake 06:15. */
    @Test
    fun theWearerCanEnterHerRealWakeWhenTheRecorderStopped() {
        val onset = d(22, 0, 16) // staged onset, ≈ her real bedtime
        val stagedWake = d(22, 2, 8) // where the data ran out, NOT where she woke
        val lastEpoch = d(22, 2, 31)
        val realWake = d(22, 6, 15)

        val coverage = range(d(21, 20, 36), lastEpoch)
        val b = SleepEdit.bounds(onset, stagedWake, dataCoverage = coverage)

        assertTrue(stagedWake.plus(SleepEdit.EDIT_MARGIN) < realWake, "precondition: the ±3 h margin cannot reach her real wake")
        assertTrue(coverage.end < stagedWake.plus(SleepEdit.EDIT_MARGIN), "precondition: coverage ends early, so it cannot widen anything either")

        assertTrue(b.latest >= realWake, "her real 06:15 wake must be selectable")
        assertNull(
            SleepEdit.validate(SleepEdit.Times(onset, onset, realWake), onset, stagedWake, dataCoverage = coverage),
            "and the validator must accept what the picker offered",
        )
    }

    /** At no hour is her real wake out of reach, and the ceiling never moves as the archive grows. */
    @Test
    fun herRealWakeIsReachableAtEveryHourSheMightEdit() {
        val onset = d(22, 0, 16)
        val stagedWake = d(22, 2, 8)
        val realWake = d(22, 6, 15)
        val firstEpoch = d(21, 20, 36)
        val lastBeforeHole = d(22, 2, 31)
        val strandedFloor = stagedWake.plus(SleepEdit.STRANDED_EDIT_MARGIN)

        var previous: Instant? = null
        for (editHour in listOf(6, 7, 8, 9, 11, 14, 20)) {
            // The archive as it stands at `editHour`: before ~06:40 there is nothing past the hole.
            val upper = if (editHour <= 6) lastBeforeHole else d(22, editHour, 0)
            val b = SleepEdit.bounds(onset, stagedWake, dataCoverage = range(firstEpoch, upper))
            assertTrue(b.latest >= realWake, "editing at $editHour:00 must still reach her real wake")
            assertTrue(b.latest >= strandedFloor, "the stranded margin is a floor the clock cannot erode")
            if (previous != null) {
                assertTrue(b.latest >= previous, "the ceiling must never move DOWN as the archive grows")
                assertEquals(previous, b.latest, "…and it must not move at ALL: the truncation ceiling swallows the coverage widening")
            }
            previous = b.latest
        }

        val atSixThirtyNine = SleepEdit.bounds(onset, stagedWake, dataCoverage = range(firstEpoch, lastBeforeHole))
        assertTrue(stagedWake.plus(SleepEdit.EDIT_MARGIN) < realWake, "precondition: the old ±3 h ceiling was below her real wake")
        assertTrue(atSixThirtyNine.latest >= realWake)
    }

    /** Whatever the editor offers, `validate` agrees; the widest pair is refused by DURATION only. */
    @Test
    fun validatorAgreesWithEveryEdgeTheStrandedPickerOffers() {
        val onset = d(22, 0, 16)
        val stagedWake = d(22, 2, 8)
        val b = SleepEdit.bounds(onset, stagedWake)
        for (edge in listOf(b.latest, b.latest.minusSeconds(60), b.earliest.plusSeconds(3600)).filter { it > onset }) {
            val bedtime = maxOf(b.earliest, edge.minus(SleepEdit.DEFAULT_MAX_NIGHT_SPAN))
            assertNull(
                SleepEdit.validate(SleepEdit.Times(bedtime, bedtime.plusSeconds(900), edge), onset, stagedWake),
                "picker offered $edge but validate refused it",
            )
        }
        val widest = SleepEdit.Times(b.earliest, b.earliest.plusSeconds(900), b.latest)
        assertTrue(widest.inBedDuration > SleepEdit.DEFAULT_MAX_NIGHT_SPAN, "precondition: the widened bounds exceed one night")
        assertEquals(
            SleepEdit.Invalid.TooLong(maxMinutes = SleepEdit.DEFAULT_MAX_NIGHT_SPAN.toMinutes()),
            SleepEdit.validate(widest, onset, stagedWake),
        )
    }

    @Test
    fun onePlausibleNightSurvivesTwoNightCoverage() {
        val b = SleepEdit.bounds(recordedOnset, recordedWake, dataCoverage = range(d(3, 2, 53), d(4, 8, 53)))
        assertTrue(b.earliest > d(3, 2, 53), "must not reach the neighbouring night")
        assertTrue(b.earliest <= d(4, 0, 15), "…but still reaches this night's bedtime")
    }

    /** The too-long rule replaces the removed pairwise edge cap and must not be deletable. */
    @Test
    fun windowStretchedAcrossWidenedBoundsIsTooLong() {
        val coverage = range(d(3, 2, 53), d(4, 13, 0))
        val b = SleepEdit.bounds(recordedOnset, recordedWake, dataCoverage = coverage)
        assertTrue(Duration.between(b.earliest, b.latest) > SleepEdit.DEFAULT_MAX_NIGHT_SPAN, "precondition: the widened bounds exceed one night")
        val stretched = SleepEdit.Times(b.earliest, b.earliest.plusSeconds(900), b.latest)
        assertEquals(
            SleepEdit.Invalid.TooLong(maxMinutes = SleepEdit.DEFAULT_MAX_NIGHT_SPAN.toMinutes()),
            SleepEdit.validate(stretched, recordedOnset, recordedWake, dataCoverage = coverage),
        )
        // A window at exactly the limit passes — the rule caps, it doesn't creep.
        val atLimit = SleepEdit.Times(b.earliest, b.earliest.plusSeconds(900), b.earliest.plus(SleepEdit.DEFAULT_MAX_NIGHT_SPAN))
        assertNull(SleepEdit.validate(atLimit, recordedOnset, recordedWake, dataCoverage = coverage))
    }

    @Test
    fun maxWindowDurationFloorAndExistingEditEscapes() {
        // 13 h recorded night -> floor span 19 h > maxNightSpan; the whole floor must validate.
        val longOnset = d(3, 19, 0)
        val longWake = d(4, 8, 0)
        val floorTimes = SleepEdit.Times(longOnset.minus(SleepEdit.EDIT_MARGIN), longOnset, longWake.plus(SleepEdit.EDIT_MARGIN))
        assertNull(SleepEdit.validate(floorTimes, longOnset, longWake))
        // A saved edit longer than one night-span must remain re-savable verbatim.
        val saved = range(d(3, 18, 0), d(4, 9, 0)) // 15 h
        val savedTimes = SleepEdit.Times(saved.start, saved.start.plusSeconds(900), saved.end)
        assertNull(SleepEdit.validate(savedTimes, recordedOnset, recordedWake, existingEdit = saved))
    }

    @Test
    fun nilCoverageIsExactlyTheOldBehaviour() {
        assertEquals(SleepEdit.bounds(recordedOnset, recordedWake), SleepEdit.bounds(recordedOnset, recordedWake, dataCoverage = null))
    }

    // dataCoverage itself

    @Test
    fun coverageIsScopedToTheNightNotTheWholeArchive() {
        val dates = listOf(d(2, 23, 0), d(3, 3, 0), d(3, 22, 0), d(4, 2, 0), d(4, 8, 53))
        val cov = assertNotNull(SleepEdit.dataCoverage(dates, recordedOnset, recordedWake))
        assertTrue(cov.start >= d(3, 18, 55), "records older than wake − maxNightSpan are excluded")
        assertEquals(d(4, 8, 53), cov.end)
    }

    // composition: dataCoverage -> bounds (what production actually does)

    private fun archiveDates(start: Instant, end: Instant): List<Instant> {
        val out = mutableListOf<Instant>()
        var t = start
        while (t <= end) {
            out += t
            t = t.plusSeconds(150)
        }
        return out
    }

    /** The early bound must not move as the day goes on, and the real bedtime stays reachable. */
    @Test
    fun boundsDoNotMoveAsTheDayGoesOn() {
        val trueBedtime = d(4, 0, 15)
        val earliestSeen = mutableSetOf<Instant>()
        for (hour in listOf(9, 11, 13, 15, 18, 21, 23)) {
            val coverage = SleepEdit.dataCoverage(archiveDates(d(3, 12, 0), d(4, hour, 0)), recordedOnset, recordedWake)
            val b = SleepEdit.bounds(recordedOnset, recordedWake, dataCoverage = coverage)
            earliestSeen += b.earliest
            assertTrue(b.earliest <= trueBedtime, "editing at $hour:00 must still reach the real bedtime")
        }
        assertEquals(1, earliestSeen.size, "bounds.earliest must be time-invariant, not a window trailing the clock")
    }

    /** The late edge is capped too, anchored on the EARLY floor edge. */
    @Test
    fun lateEdgeIsCappedAtOneNightSpan() {
        val coverage = SleepEdit.dataCoverage(archiveDates(d(3, 12, 0), d(4, 21, 0)), recordedOnset, recordedWake)
        val b = SleepEdit.bounds(recordedOnset, recordedWake, dataCoverage = coverage)
        assertTrue(b.latest <= d(4, 4, 30).plus(SleepEdit.DEFAULT_MAX_NIGHT_SPAN), "late edge stops one night-span past the early floor edge")
        assertTrue(b.latest < d(4, 19, 0), "must not let the user claim sleep into the evening")
    }

    // the 2026-08-16 seesaw (device case, Gen 2 Air tester)

    @Test
    fun eveningCoverageMustNotEatTheHeldMorning() {
        val onset = d(16, 3, 44)
        val wake = d(16, 6, 4)
        val dates = archiveDates(d(15, 4, 53), d(15, 23, 43)) + archiveDates(d(16, 3, 44), d(16, 10, 51))
        val coverage = assertNotNull(SleepEdit.dataCoverage(dates, onset, wake))
        assertTrue(coverage.end >= d(16, 10, 15), "the real wake IS inside held coverage — precondition")
        val b = SleepEdit.bounds(onset, wake, dataCoverage = coverage)
        assertTrue(b.latest >= d(16, 10, 15), "the tester must be able to select the wake the ring recorded")
        // The evening side keeps its own cap: one night-span before the late floor edge.
        assertTrue(b.earliest >= wake.plus(SleepEdit.EDIT_MARGIN).minus(SleepEdit.DEFAULT_MAX_NIGHT_SPAN))
        // And the corrected window itself validates end-to-end.
        val times = SleepEdit.Times(d(16, 3, 44), d(16, 3, 45), d(16, 10, 15))
        assertNull(SleepEdit.validate(times, onset, wake, minDuration = Duration.ofMinutes(30), dataCoverage = coverage))
    }

    /** The mirror: morning coverage widening `latest` must not drag the early cap along. */
    @Test
    fun morningCoverageMustNotEatTheHeldEvening() {
        val onset = d(16, 3, 44)
        val wake = d(16, 6, 4)
        val coverage = assertNotNull(SleepEdit.dataCoverage(archiveDates(d(15, 21, 0), d(16, 10, 51)), onset, wake))
        val b = SleepEdit.bounds(onset, wake, dataCoverage = coverage)
        assertTrue(b.earliest <= d(15, 21, 0), "held evening data stays reachable however far the morning widened")
    }

    // rules that must not be deletable without a test failing

    @Test
    fun parityFloorSurvivesTheCapOnALongNight() {
        val onset = d(3, 22, 0)
        val wake = d(4, 8, 0) // a 10 h night
        val noCoverage = SleepEdit.bounds(onset, wake)
        assertEquals(d(3, 16, 0), noCoverage.earliest, "onset − 6 h (stranded margin, own anchor)")
        assertEquals(d(4, 14, 0), noCoverage.latest, "wake + 6 h (ditto)")
        assertTrue(noCoverage.earliest <= d(3, 19, 0), "onset − 3 h is a FLOOR")
        assertTrue(noCoverage.latest >= d(4, 11, 0), "wake + 3 h is a FLOOR")

        val wide = SleepEdit.bounds(onset, wake, dataCoverage = range(d(2, 20, 0), d(4, 20, 0)))
        assertTrue(wide.earliest <= d(3, 19, 0), "floor is a floor — widening only adds")
        assertTrue(wide.latest >= d(4, 11, 0))
    }

    @Test
    fun anAlreadySavedEditIsAlwaysStillSelectable() {
        val saved = range(d(4, 0, 15), d(4, 8, 53))
        val b = SleepEdit.bounds(recordedOnset, recordedWake, dataCoverage = null, existingEdit = saved) // coverage fully pruned
        assertTrue(b.earliest <= saved.start)
        assertTrue(b.latest >= saved.end)
        val times = SleepEdit.Times(saved.start, saved.start.plusSeconds(900), saved.end)
        assertNull(
            SleepEdit.validate(times, recordedOnset, recordedWake, existingEdit = saved),
            "re-opening an edited night must not silently clamp the user's own times",
        )
    }

    @Test
    fun coverageExcludesRecordsPastTheForwardWindow() {
        // Pins the `<= hi` half of the night scoping.
        val cov = assertNotNull(SleepEdit.dataCoverage(listOf(d(3, 22, 0), d(4, 2, 0), d(4, 23, 0)), recordedOnset, recordedWake))
        assertEquals(d(3, 22, 0), cov.start)
        assertEquals(d(4, 2, 0), cov.end, "d(4,23,0) is beyond onset + 14 h and excluded")
    }

    @Test
    fun coverageIsNilWhenNoRecordsFallInTheWindow() {
        assertNull(SleepEdit.dataCoverage(listOf(d(1, 4, 0)), recordedOnset, recordedWake))
        assertNull(SleepEdit.dataCoverage(emptyList(), recordedOnset, recordedWake))
    }

    // the validator must agree with the picker

    @Test
    fun validatorAcceptsTheWidenedWindowThePickerNowOffers() {
        val coverage = range(d(3, 19, 45), d(4, 8, 53))
        val times = SleepEdit.Times(d(4, 0, 15), d(4, 0, 30), d(4, 8, 53))
        // Without coverage the check rejects it — the picker-offers / Save-refuses bug class.
        assertEquals(SleepEdit.Invalid.StartBeforeEarliest, SleepEdit.validate(times, recordedOnset, recordedWake))
        assertNull(SleepEdit.validate(times, recordedOnset, recordedWake, dataCoverage = coverage))
    }

    @Test
    fun wideningStillRejectsAnInventedNight() {
        // You cannot assert sleep in open space far from any data.
        val coverage = range(d(3, 19, 45), d(4, 8, 53))
        val times = SleepEdit.Times(d(2, 21, 0), d(2, 21, 30), d(3, 5, 0))
        assertNotNull(SleepEdit.validate(times, recordedOnset, recordedWake, dataCoverage = coverage))
    }
}
