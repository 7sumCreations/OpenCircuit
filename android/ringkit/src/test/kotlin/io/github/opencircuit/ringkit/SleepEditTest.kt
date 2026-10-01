package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manual sleep edit: the editable bounds around the recorded night, the validator, the
 * picker-minute comparison, and `recompute` (trim, extension fill, interior gaps kept, the
 * bedtime-to-onset awake paint).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepEditTests.swift (@ b1c2fdd) —
 * all 23 tests. Upstream builds its instants as `Date`s (Double seconds); here they are `Instant`s
 * built from the same hour offsets, rounded to the nanosecond. The picker-minute test names its zone
 * (upstream: a Gregorian calendar in the device zone).
 */
class SleepEditTest {

    private val ref: Instant = Instant.ofEpochSecond(1_700_000_000) // fixed anchor, no wall-clock
    private fun at(hoursFromRef: Double): Instant = ref.plusNanos((hoursFromRef * 3600e9).roundToLong())
    private fun seconds(d: Duration): Double = d.seconds + d.nano / 1e9

    @Test
    fun editMarginIsThreeHours() {
        assertEquals(Duration.ofHours(3), SleepEdit.EDIT_MARGIN)
    }

    /**
     * The reported night, to the minute: in bed 22:51, ring-detected onset 23:47, wake 08:57 — and a
     * true onset of 01:13 the tester could not enter. The rule always permitted it.
     */
    @Test
    fun anOnsetPastMidnightIsPermittedByTheRule() {
        fun t(day: Int, h: Int, m: Int): Instant = ZonedDateTime.of(2026, 8, day, h, m, 0, 0, ZoneOffset.UTC).toInstant()
        val recordedOnset = t(11, 23, 47)
        val recordedWake = t(12, 8, 57)
        val times = SleepEdit.Times(inBedStart = t(11, 22, 51), sleepOnset = t(12, 1, 13), sleepWake = recordedWake)
        assertNull(
            SleepEdit.validate(times, recordedOnset, recordedWake, minDuration = Duration.ofMinutes(30)),
            "a 01:13 onset inside the detected in-bed window must be a legal edit",
        )

        // And the edit produces what the wearer asked for: pre-onset time is AWAKE, not sleep.
        val base = listOf(SleepSegment(recordedOnset, recordedWake, SleepStage.ASLEEP_CORE))
        val out = SleepEdit.recompute(base, times)
        val asleep = out.filter { it.stage != SleepStage.IN_BED && it.stage != SleepStage.AWAKE }
        assertEquals(t(12, 1, 13), asleep.minOf { it.start })
        assertTrue(out.any { it.stage == SleepStage.AWAKE && it.start == t(11, 22, 51) && it.end == t(12, 1, 13) })
    }

    /** The editable bound is the 6 h stranded margin on each edge's own anchor; ±3 h is a floor. */
    @Test
    fun boundsAreOnsetMinus6hToWakePlus6h() {
        val b = SleepEdit.bounds(at(0.0), at(8.0))
        assertEquals(at(-6.0), b.earliest)
        assertEquals(at(14.0), b.latest)
        assertTrue(b.earliest <= at(-3.0), "the ±3 h parity margin is a FLOOR")
        assertTrue(b.latest >= at(11.0), "the ±3 h parity margin is a FLOOR")
    }

    @Test
    fun clampPinsToBounds() {
        val b = SleepEdit.bounds(at(0.0), at(8.0))
        assertEquals(at(-6.0), SleepEdit.clamp(at(-8.0), b)) // below -> earliest
        assertEquals(at(14.0), SleepEdit.clamp(at(20.0), b)) // above -> latest
        assertEquals(at(2.0), SleepEdit.clamp(at(2.0), b)) // inside -> unchanged
    }

    @Test
    fun pickerMinuteComparisonIgnoresHiddenSecondsOnly() {
        val zone = ZoneId.of("America/New_York")
        val minute = Instant.ofEpochSecond(Math.floorDiv(at(2.0).epochSecond, 60L) * 60L)
        assertTrue(SleepEdit.isSamePickerMinute(minute.plusSeconds(5), minute.plusSeconds(55), zone))
        assertFalse(SleepEdit.isSamePickerMinute(minute.plusSeconds(55), minute.plusSeconds(65), zone))
    }

    @Test
    fun validWindowInsideBounds() {
        // Extend a truncated morning: wake 8h -> 9.5h, bedtime 0h -> -0.5h. Valid.
        val w = SleepEdit.Window(at(-0.5), at(9.5))
        assertNull(SleepEdit.validate(w, at(0.0), at(8.0)))
        assertTrue(SleepEdit.isValid(w, at(0.0), at(8.0)))
    }

    @Test
    fun exactBoundaryIsAllowed() {
        // Exactly onset-6h .. wake+6h is allowed (inclusive).
        assertNull(SleepEdit.validate(SleepEdit.Window(at(-6.0), at(14.0)), at(0.0), at(8.0)))
    }

    @Test
    fun startTooEarlyRejected() {
        assertEquals(SleepEdit.Invalid.StartBeforeEarliest, SleepEdit.validate(SleepEdit.Window(at(-6.5), at(8.0)), at(0.0), at(8.0)))
    }

    @Test
    fun endTooLateRejected() {
        assertEquals(SleepEdit.Invalid.EndAfterLatest, SleepEdit.validate(SleepEdit.Window(at(0.0), at(14.5)), at(0.0), at(8.0)))
    }

    @Test
    fun endNotAfterStartRejected() {
        assertEquals(SleepEdit.Invalid.EndNotAfterStart, SleepEdit.validate(SleepEdit.Window(at(5.0), at(5.0)), at(0.0), at(8.0)))
    }

    @Test
    fun tooShortRejected() {
        // 20-min window with a 60-min floor.
        val w = SleepEdit.Window(at(1.0), at(1.0 / 3.0 + 1))
        assertEquals(SleepEdit.Invalid.TooShort(minMinutes = 60), SleepEdit.validate(w, at(0.0), at(8.0), minDuration = Duration.ofMinutes(60)))
    }

    @Test
    fun windowDuration() {
        assertEquals(7.5 * 3600, seconds(SleepEdit.Window(at(0.0), at(7.5)).duration), 0.1)
        // Degenerate (end before start) is clamped to 0, never negative.
        assertEquals(Duration.ZERO, SleepEdit.Window(at(5.0), at(4.0)).duration)
    }

    @Test
    fun threeTimesRequireBedtimeThenOnsetThenWake() {
        assertEquals(
            SleepEdit.Invalid.OnsetBeforeBedtime,
            SleepEdit.validate(SleepEdit.Times(at(1.0), at(0.5), at(8.0)), at(1.0), at(8.0)),
        )
        assertEquals(
            SleepEdit.Invalid.WakeNotAfterOnset,
            SleepEdit.validate(SleepEdit.Times(at(0.0), at(8.0), at(8.0)), at(1.0), at(8.0)),
        )
    }

    // recompute

    private fun seg(a: Double, b: Double, stage: SleepStage) = SleepSegment(at(a), at(b), stage)

    private fun asleepSeconds(segs: List<SleepSegment>): Double =
        segs.filter { it.stage != SleepStage.AWAKE && it.stage != SleepStage.IN_BED }.sumOf { seconds(it.duration) }

    @Test
    fun recomputeExtendsTailAsAsleep() {
        // Recorded 0-6.8h; user drags wake to 8.5h. The tail 6.8-8.5 is credited as asleep (core).
        val out = SleepEdit.recompute(listOf(seg(0.0, 6.8, SleepStage.ASLEEP_CORE)), SleepEdit.Window(at(0.0), at(8.5)))
        assertEquals(2, out.size)
        assertEquals(seg(6.8, 8.5, SleepStage.ASLEEP_CORE), out.last())
        // Total asleep grew by the extension.
        assertEquals(8.5 * 3600, asleepSeconds(out), 0.1)
        assertEquals(1.0, SleepStaging.summary(out).efficiency, 0.0001)
    }

    @Test
    fun recomputeExtendsLeadAsAsleep() {
        // Recorded 1-8h; user pulls bedtime to -0.5h. The lead -0.5-1 is filled asleep.
        val out = SleepEdit.recompute(listOf(seg(1.0, 8.0, SleepStage.ASLEEP_CORE)), SleepEdit.Window(at(-0.5), at(8.0)))
        assertEquals(seg(-0.5, 1.0, SleepStage.ASLEEP_CORE), out.first())
        assertEquals(2, out.size)
    }

    @Test
    fun threeTimeRecomputeCountsPreSleepAsAwakeInBed() {
        val base = listOf(seg(1.0, 9.0, SleepStage.IN_BED), seg(1.0, 9.0, SleepStage.ASLEEP_CORE))
        val out = SleepEdit.recompute(base, SleepEdit.Times(at(0.0), at(1.0), at(9.0)))
        val summary = SleepStaging.summary(out)

        assertEquals(seg(0.0, 9.0, SleepStage.IN_BED), out.first())
        assertTrue(seg(0.0, 1.0, SleepStage.AWAKE) in out)
        assertEquals(9L * 60, summary.minutes.inBed)
        assertEquals(8L * 60, summary.minutes.asleep)
        assertEquals(60L, summary.minutes.awake)
        assertEquals(8.0 / 9.0, summary.efficiency, 0.0001)
        assertEquals(at(1.0), SleepStaging.sleepWindow(out)?.onset)
        assertEquals(at(9.0), SleepStaging.sleepWindow(out)?.wake)
    }

    @Test
    fun recomputeExtendsInBedLayerWithStagedNight() {
        val base = listOf(seg(0.0, 8.0, SleepStage.IN_BED), seg(0.0, 8.0, SleepStage.ASLEEP_CORE))
        val out = SleepEdit.recompute(base, SleepEdit.Window(at(-1.0), at(9.0)))
        val summary = SleepStaging.summary(out)
        assertEquals(10L * 60, summary.minutes.inBed)
        assertEquals(10L * 60, summary.minutes.asleep)
        assertEquals(1.0, summary.efficiency, 0.0001)
        assertEquals(3, out.count { it.stage == SleepStage.IN_BED })
    }

    @Test
    fun recomputeTrimsToWindowWithoutFill() {
        // Trim to 1-7h: clip the single 0-8 segment, no fill added.
        val out = SleepEdit.recompute(listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE)), SleepEdit.Window(at(1.0), at(7.0)))
        assertEquals(listOf(seg(1.0, 7.0, SleepStage.ASLEEP_CORE)), out)
    }

    @Test
    fun recomputePreservesInteriorGap() {
        // A real mid-night awake gap (3-5h has no asleep segment) must NOT be back-filled.
        val base = listOf(seg(0.0, 3.0, SleepStage.ASLEEP_CORE), seg(5.0, 8.0, SleepStage.ASLEEP_DEEP))
        assertEquals(base, SleepEdit.recompute(base, SleepEdit.Window(at(0.0), at(8.0)))) // interior gap untouched
    }

    @Test
    fun recomputeDoesNotFillTrimmedInteriorGap() {
        val base = listOf(seg(0.0, 3.0, SleepStage.ASLEEP_CORE), seg(5.0, 8.0, SleepStage.ASLEEP_DEEP))
        assertEquals(
            listOf(seg(5.0, 6.0, SleepStage.ASLEEP_DEEP)),
            SleepEdit.recompute(base, SleepEdit.Window(at(4.0), at(6.0))),
            "the 4-5 h interior gap must not be mistaken for a leading extension",
        )
    }

    @Test
    fun recomputeWindowWhollyInsideInteriorGapStaysEmpty() {
        val base = listOf(seg(0.0, 3.0, SleepStage.ASLEEP_CORE), seg(5.0, 8.0, SleepStage.ASLEEP_DEEP))
        assertTrue(SleepEdit.recompute(base, SleepEdit.Window(at(3.5), at(4.5))).isEmpty())
    }

    @Test
    fun recomputeEmptyBaseFillsWholeWindow() {
        val out = SleepEdit.recompute(emptyList(), SleepEdit.Window(at(0.0), at(7.0)))
        assertEquals(listOf(seg(0.0, 7.0, SleepStage.ASLEEP_CORE)), out)
        assertEquals(7L * 60, SleepStaging.summary(out).minutes.inBed)
    }

    @Test
    fun recomputeDropsSegmentsFullyOutsideWindow() {
        // A nap fragment at 10-11h is outside a 0-8 window -> dropped.
        val base = listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE), seg(10.0, 11.0, SleepStage.ASLEEP_CORE))
        assertEquals(listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE)), SleepEdit.recompute(base, SleepEdit.Window(at(0.0), at(8.0))))
    }
}
