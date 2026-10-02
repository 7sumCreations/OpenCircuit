package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editable ceiling must not be a function of how badly the night was truncated: the late edge
 * gets a floor anchored on the recorded ONSET (one plausible night after the parity bedtime), a
 * 20-hour window is still refused by the too-long rule, the stranded ceiling and a saved edit stay
 * floors, the late edge is the same at every state of the archive, a well-recorded night keeps its
 * old ceiling, and every minute the new ceiling unlocks is still tagged asserted.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepEditTruncatedCeilingTests.swift
 * (@ b1c2fdd) — all 11 tests.
 */
class SleepEditTruncatedCeilingTest {

    private val ref: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(h: Double): Instant = ref.plusNanos((h * 3600e9).roundToLong())
    private fun seconds(d: Duration): Double = d.seconds + d.nano / 1e9
    private fun epochSeconds(t: Instant): Double = t.epochSecond + t.nano / 1e9

    /** The rule, restated independently of the implementation. */
    private fun oneNightAfterTheParityBedtime(recordedOnset: Instant): Instant =
        recordedOnset.minus(SleepEdit.EDIT_MARGIN).plus(SleepEdit.DEFAULT_MAX_NIGHT_SPAN)

    // the new property

    @Test
    fun theCeilingHasAFloorTheTruncationCannotMove() {
        val onset = at(0.0)
        val floor = oneNightAfterTheParityBedtime(onset)
        var wakeH = 9.0
        while (wakeH >= 0.25) {
            val b = SleepEdit.bounds(onset, at(wakeH))
            assertTrue(
                b.latest >= floor,
                "a night truncated to ${wakeH}h dropped the editable ceiling below one plausible night after the parity bedtime",
            )
            wakeH -= 0.25
        }
    }

    @Test
    fun aHeadFragmentNightCanReachTheRealMorningWake() {
        val recordedOnset = at(0.0) // ring-derived onset ≈ the real bedtime
        val recordedWake = at(1.75) // where the record of the night stops: a 1 h 45 m "night"
        val realWake = at(8.5) // the wearer knows when she got up

        assertTrue(recordedWake.plus(SleepEdit.STRANDED_EDIT_MARGIN) < realWake, "precondition: the old ceiling (recordedWake + 6 h) is below her wake")

        val b = SleepEdit.bounds(recordedOnset, recordedWake)
        assertTrue(b.latest >= realWake, "her real wake must be selectable in the picker")

        val times = SleepEdit.Times(recordedOnset, recordedOnset.plusSeconds(300), realWake)
        assertNull(
            SleepEdit.validate(times, recordedOnset, recordedWake, minDuration = Duration.ofMinutes(30)),
            "the validator must accept exactly what the picker offered",
        )
    }

    @Test
    fun theCeilingIsOneNightAfterTheParityBedtime() {
        for (wakeH in listOf(0.5, 1.0, 2.0, 3.0, 4.0)) {
            val b = SleepEdit.bounds(at(0.0), at(wakeH))
            assertEquals(
                epochSeconds(oneNightAfterTheParityBedtime(at(0.0))),
                epochSeconds(b.latest),
                0.1,
                "a $wakeH h recorded night must reach one night past the parity bedtime",
            )
        }
    }

    // what still bounds the night

    @Test
    fun aTwentyHourNightIsStillRejected() {
        val recordedOnset = at(0.0)
        val recordedWake = at(1.75)
        val b = SleepEdit.bounds(recordedOnset, recordedWake)

        val twentyHours = SleepEdit.Times(at(-6.0), at(-5.75), at(14.0))
        assertEquals(20.0 * 3600, seconds(twentyHours.inBedDuration), 0.1, "precondition: 20 h")
        assertNotNull(SleepEdit.validate(twentyHours, recordedOnset, recordedWake), "a 20-hour night must be refused")

        // The widest window the picker itself can offer is refused by the too-long rule.
        val widest = SleepEdit.Times(b.earliest, b.earliest.plusSeconds(900), b.latest)
        assertTrue(widest.inBedDuration > SleepEdit.DEFAULT_MAX_NIGHT_SPAN, "precondition: the bounds span more than one night")
        assertEquals(
            SleepEdit.Invalid.TooLong(maxMinutes = SleepEdit.DEFAULT_MAX_NIGHT_SPAN.toMinutes()),
            SleepEdit.validate(widest, recordedOnset, recordedWake),
        )

        // …and a window at exactly one night is accepted: the rule caps, it does not creep.
        val atLimit = SleepEdit.Times(b.earliest, b.earliest.plusSeconds(900), b.earliest.plus(SleepEdit.DEFAULT_MAX_NIGHT_SPAN))
        assertTrue(atLimit.sleepWake <= b.latest, "precondition: inside the bounds")
        assertNull(SleepEdit.validate(atLimit, recordedOnset, recordedWake))
    }

    // the invariants the three earlier drafts broke

    @Test
    fun theCeilingNeverFallsBelowTheStrandedMargin() {
        for (onsetH in listOf(-4.0, 0.0, 3.0)) {
            for (spanH in listOf(0.25, 1.0, 3.0, 5.0, 8.0, 11.0, 13.0)) {
                val onset = at(onsetH)
                val wake = at(onsetH + spanH)
                val b = SleepEdit.bounds(onset, wake)
                assertTrue(
                    b.latest >= wake.plus(SleepEdit.STRANDED_EDIT_MARGIN),
                    "onset ${onsetH}h span ${spanH}h: the stranded ceiling is a FLOOR the new rule may only add to",
                )
            }
        }
    }

    @Test
    fun theCeilingDoesNotDependOnWhenTheSheetIsOpened() {
        val onset = at(0.0)
        val wake = at(1.75)
        var previous: Instant? = null
        for (archiveEndH in listOf(2.0, 4.0, 7.0, 10.0, 14.0, 20.0)) {
            val b = SleepEdit.bounds(onset, wake, dataCoverage = DateInterval(at(-3.0), at(archiveEndH)))
            if (previous != null) assertTrue(b.latest >= previous, "the ceiling moved DOWN as the archive grew")
            previous = b.latest
        }
    }

    @Test
    fun theLateEdgeIsTheSameAtEveryStateOfTheArchive() {
        val onset = at(0.0)
        val wake = at(1.75)
        val noCoverage = SleepEdit.bounds(onset, wake).latest
        val cap = onset.minus(SleepEdit.EDIT_MARGIN).plus(SleepEdit.DEFAULT_MAX_NIGHT_SPAN)
        for (upperH in listOf(1.9, 3.0, 6.0, 8.0, 11.0, 13.0, 14.0)) {
            val coverage = DateInterval(at(-3.0), at(upperH))
            val b = SleepEdit.bounds(onset, wake, dataCoverage = coverage)
            assertEquals(
                epochSeconds(noCoverage),
                epochSeconds(b.latest),
                0.1,
                "an archive ending at ${upperH}h moved a ceiling that is supposed to be a pure function of the recorded night",
            )
            assertTrue(
                b.latest >= minOf(coverage.end, cap),
                "the constant ceiling must be at least what the coverage widening could have bought — no wearer loses reach",
            )
        }
    }

    /** The disclosed non-monotonicity, measured: a fuller staging lowers the ceiling, never below the floors. */
    @Test
    fun aFullerStagingLowersTheCeilingButNeverBelowTheFloors() {
        val wake = at(1.0)
        val strandedFloor = wake.plus(SleepEdit.STRANDED_EDIT_MARGIN) // at(7)

        val before = SleepEdit.bounds(at(0.0), wake).latest
        val after = SleepEdit.bounds(at(-2.0), wake).latest
        assertEquals(epochSeconds(at(11.0)), epochSeconds(before), 0.1)
        assertEquals(epochSeconds(at(9.0)), epochSeconds(after), 0.1)
        assertTrue(after < before, "precondition: this is the drop, stated plainly")
        assertTrue(after >= strandedFloor, "…but never through the stranded floor")

        // And once she has SAVED an edit, the drop cannot reach her own times at all.
        val saved = DateInterval(at(0.0), before)
        assertTrue(SleepEdit.bounds(at(-2.0), wake, existingEdit = saved).latest >= saved.end)
    }

    @Test
    fun aWellRecordedNightKeepsExactlyTheOldCeiling() {
        for (spanH in listOf(5.0, 6.0, 8.0, 9.5, 12.0)) {
            val onset = at(0.0)
            val wake = at(spanH)
            val b = SleepEdit.bounds(onset, wake)
            assertEquals(
                epochSeconds(wake.plus(SleepEdit.STRANDED_EDIT_MARGIN)),
                epochSeconds(b.latest),
                0.1,
                "a $spanH h recorded night must be byte-identical to the old rule",
            )
        }
    }

    @Test
    fun anOnsetWideningCannotStrandASavedEdit() {
        val wake = at(1.75)
        val saved = DateInterval(at(0.0), at(8.5)) // what she saved this morning
        var lowestSeen = Instant.MAX
        for (onsetH in listOf(0.0, -1.0, -3.0, -6.0)) { // successively fuller stagings
            val b = SleepEdit.bounds(at(onsetH), wake, existingEdit = saved)
            assertTrue(b.earliest <= saved.start)
            assertTrue(b.latest >= saved.end, "a fuller staging must not strand her own saved wake")
            assertTrue(b.latest >= wake.plus(SleepEdit.STRANDED_EDIT_MARGIN), "…nor drop below the stranded ceiling")
            lowestSeen = minOf(lowestSeen, b.latest)
            assertNull(
                SleepEdit.validate(SleepEdit.Times(saved.start, saved.start.plusSeconds(300), saved.end), at(onsetH), wake, existingEdit = saved),
                "re-opening an edited night must not silently clamp her own times",
            )
        }
        assertTrue(lowestSeen >= saved.end)
    }

    // the bounds <-> provenance pairing

    @Test
    fun everyMinuteTheNewCeilingUnlocksIsStillTaggedAsserted() {
        val recordedOnset = at(0.0)
        val recordedWake = at(1.75)
        val realWake = at(8.5)
        val b = SleepEdit.bounds(recordedOnset, recordedWake)
        assertTrue(b.latest >= realWake, "precondition: the new ceiling reaches it")

        val base = listOf(SleepSegment(recordedOnset, recordedWake, SleepStage.ASLEEP_CORE))
        // The ring recorded the fragment and nothing after it.
        val coverage = MeasuredCoverage(listOf(DateInterval(recordedOnset, recordedWake)))
        val out = SleepEdit.recompute(base, SleepEdit.Times(recordedOnset, recordedOnset, realWake), coverage = coverage)
        val asserted = out.filter { it.stage != SleepStage.IN_BED && it.provenance == SleepProvenance.ASSERTED }.sumOf { seconds(it.duration) }
        val measured = out.filter { it.stage != SleepStage.IN_BED && it.provenance == SleepProvenance.MEASURED }.sumOf { seconds(it.duration) }
        assertEquals(seconds(Duration.between(recordedWake, realWake)), asserted, 1.0, "every unlocked minute past the recording must be tagged asserted")
        assertEquals(seconds(Duration.between(recordedOnset, recordedWake)), measured, 1.0, "and the recorded fragment must stay measured")
        assertFalse(
            out.any { it.stage != SleepStage.IN_BED && it.end > recordedWake && it.provenance == SleepProvenance.MEASURED },
            "nothing past the recording may claim to be a measurement",
        )
    }
}
