package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Implementation-agnostic invariants of the manual sleep edit: `recompute` never escapes the edited
 * window and never fabricates asleep time beyond it; a window inside an interior gap invents
 * nothing; a degenerate window yields nothing; the bounds contain the parity floor and stay inside
 * their exact rule; clamp is idempotent; the validator's accept/reject boundary is exactly the
 * bounds; the reported 3 am night can reach a morning wake.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepEditInvariantTests.swift
 * (@ b1c2fdd) — all 8 tests. The reported-night test names its zone (America/New_York, the night's
 * own zone; upstream used the device calendar).
 */
class SleepEditInvariantTest {

    private val ref: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(h: Double): Instant = ref.plusNanos((h * 3600e9).roundToLong())
    private fun seg(a: Double, b: Double, s: SleepStage) = SleepSegment(at(a), at(b), s)
    private fun seconds(d: Duration): Double = d.seconds + d.nano / 1e9
    private fun asleepSeconds(segs: List<SleepSegment>): Double =
        segs.filter { it.stage != SleepStage.AWAKE && it.stage != SleepStage.IN_BED }.sumOf { seconds(it.duration) }

    /** A spread of well-formed (non-overlapping) base nights + a spread of windows around them. */
    private val bases: List<List<SleepSegment>> = listOf(
        emptyList(), // no recording
        listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE)), // single block
        listOf(seg(0.0, 8.0, SleepStage.IN_BED), seg(0.0, 8.0, SleepStage.ASLEEP_CORE)), // two-layer staged night
        listOf(seg(0.0, 3.0, SleepStage.ASLEEP_CORE), seg(5.0, 8.0, SleepStage.ASLEEP_DEEP)), // interior awake gap 3-5
        listOf(seg(1.0, 2.5, SleepStage.ASLEEP_CORE), seg(2.5, 4.0, SleepStage.ASLEEP_DEEP), seg(4.0, 6.5, SleepStage.ASLEEP_REM)),
    )
    private val windows: List<SleepEdit.Window> = listOf(-1.0, -0.5, 0.0, 0.5, 1.0, 3.5, 4.0).flatMap { start ->
        listOf(7.0, 8.0, 9.0, 9.5, 6.0, 4.5).map { end -> SleepEdit.Window(at(start), at(end)) }
    }

    /** INVARIANT 1: every recomputed segment lies within the edited window. */
    @Test
    fun recomputeNeverEscapesTheWindow() {
        for (base in bases) {
            for (w in windows.filter { it.inBedEnd > it.inBedStart }) {
                for (s in SleepEdit.recompute(base, w)) {
                    assertTrue(s.start >= w.inBedStart, "segment starts before the window")
                    assertTrue(s.end <= w.inBedEnd, "segment ends after the window")
                    assertTrue(s.end > s.start, "degenerate segment emitted")
                }
            }
        }
    }

    /** INVARIANT 2: recompute never credits more asleep time than the window is long. */
    @Test
    fun recomputeNeverFabricatesAsleepBeyondWindow() {
        for (base in bases) {
            for (w in windows.filter { it.inBedEnd > it.inBedStart }) {
                val out = SleepEdit.recompute(base, w)
                assertTrue(asleepSeconds(out) <= seconds(w.duration) + 0.001, "asleep exceeds the edited window length")
            }
        }
    }

    /** INVARIANT 3: a window wholly inside an INTERIOR recording gap invents nothing. */
    @Test
    fun windowInsideInteriorGapInventsNothing() {
        val base = listOf(seg(0.0, 3.0, SleepStage.ASLEEP_CORE), seg(5.0, 8.0, SleepStage.ASLEEP_DEEP)) // gap 3-5
        val out = SleepEdit.recompute(base, SleepEdit.Window(at(3.4), at(4.6)))
        assertEquals(0.0, asleepSeconds(out), "the 3-5 h awake gap was back-filled as sleep")
    }

    /** INVARIANT 4: a degenerate window (end <= start) yields nothing. */
    @Test
    fun degenerateWindowIsEmpty() {
        assertTrue(SleepEdit.recompute(listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE)), SleepEdit.Window(at(5.0), at(5.0))).isEmpty())
        assertTrue(SleepEdit.recompute(listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE)), SleepEdit.Window(at(6.0), at(5.0))).isEmpty())
    }

    /**
     * INVARIANT 5: bounds always contain the recorded night ±3 h, stay inside their exact rule (the
     * parity floor widened by the stranded margin, and on the late edge by one plausible night after
     * the parity bedtime), and clamp is idempotent and a no-op inside them.
     */
    @Test
    fun boundsWidthAndClampIdempotence() {
        for ((o, wk) in listOf(0.0 to 8.0, -2.0 to 5.0, 1.0 to 1.5)) {
            val b = SleepEdit.bounds(at(o), at(wk))
            val floorEarliest = at(o).minus(SleepEdit.EDIT_MARGIN)
            val floorLatest = at(wk).plus(SleepEdit.EDIT_MARGIN)
            val span = SleepEdit.DEFAULT_MAX_NIGHT_SPAN

            assertTrue(b.earliest <= floorEarliest, "the ±3 h parity floor is a FLOOR")
            assertTrue(b.latest >= floorLatest, "the ±3 h parity floor is a FLOOR")
            assertTrue(
                b.earliest >= at(o).minus(SleepEdit.STRANDED_EDIT_MARGIN),
                "no coverage was supplied, so nothing may reach past the stranded margin on the edge's own anchor",
            )
            assertTrue(
                b.latest <= maxOf(at(wk).plus(SleepEdit.STRANDED_EDIT_MARGIN), floorEarliest.plus(span)),
                "no coverage was supplied, so the late edge may reach the stranded margin or the truncation ceiling, and nothing more",
            )

            // The exact rule, spelled out.
            val wantEarliest = minOf(floorEarliest, at(o).minus(SleepEdit.STRANDED_EDIT_MARGIN))
            val wantLatest = maxOf(floorLatest, maxOf(at(wk).plus(SleepEdit.STRANDED_EDIT_MARGIN), floorEarliest.plus(span)))
            assertEquals(seconds(Duration.between(ref, wantEarliest)), seconds(Duration.between(ref, b.earliest)), 0.1)
            assertEquals(seconds(Duration.between(ref, wantLatest)), seconds(Duration.between(ref, b.latest)), 0.1)

            for (probe in listOf(-10.0, -3.0, 0.0, 4.0, 20.0)) {
                val once = SleepEdit.clamp(at(probe), b)
                assertEquals(once, SleepEdit.clamp(once, b), "clamp is not idempotent")
                assertTrue(once >= b.earliest)
                assertTrue(once <= b.latest)
            }
        }
    }

    /** A night already filling the span is widened by the stranded margin too (no span-dependent cancel). */
    @Test
    fun aFullNightIsWidenedByTheStrandedMarginToo() {
        val b = SleepEdit.bounds(at(0.0), at(8.0))
        assertEquals(at(-6.0), b.earliest, "onset − 6 h: the margin is no longer span-dependent")
        assertEquals(at(14.0), b.latest, "wake + 6 h: ditto")
        // The parity floor is still a FLOOR, not the rule.
        assertTrue(b.earliest <= at(-3.0))
        assertTrue(b.latest >= at(11.0))
    }

    /** INVARIANT 6: validate's accept/reject boundary is exactly `bounds`, swept past it on both sides. */
    @Test
    fun validateBoundaryProperty() {
        val onset = at(0.0)
        val wake = at(8.0)
        val margin = seconds(SleepEdit.STRANDED_EDIT_MARGIN) / 3600
        var deltaH = -8.0
        while (deltaH <= 8.0) {
            val startEdit = SleepEdit.Window(at(deltaH), wake)
            // `deltaH < 8` keeps the window non-degenerate: at +8 h `EndNotAfterStart` fires first.
            assertEquals(
                deltaH >= -margin && deltaH < 8.0,
                SleepEdit.isValid(startEdit, onset, wake),
                "start-edge validity wrong at Δ=${deltaH}h",
            )
            val endEdit = SleepEdit.Window(onset, at(8 + deltaH))
            assertEquals(deltaH <= margin && (8 + deltaH) > 0, SleepEdit.isValid(endEdit, onset, wake), "end-edge validity wrong at Δ=${deltaH}h")
            deltaH += 0.25
        }
    }

    /**
     * The reported night (Gen 2 FR02.018, America/New_York, 2026-08-24): staged onset 20:34:49 →
     * wake 05:55:14, a 9 h 20 m span. Her real morning wake must be reachable, and the corrected
     * window with her 3 am bedtime must validate.
     */
    @Test
    fun theReportedNightCanNowReachAPlausibleMorningWake() {
        val zone = ZoneId.of("America/New_York")
        val day: ZonedDateTime = LocalDate.of(2026, 8, 23).atStartOfDay(zone)
        fun t(h: Int, m: Int, s: Int = 0, plusDays: Long = 0): Instant =
            day.plusDays(plusDays).plusSeconds((h * 3600 + m * 60 + s).toLong()).toInstant()
        val onset = t(20, 34, 49)
        val wake = t(5, 55, 14, plusDays = 1)
        assertTrue(Duration.between(onset, wake) > Duration.ofHours(8), "precondition: this night is past the cliff the old arithmetic had")

        val b = SleepEdit.bounds(onset, wake)
        assertTrue(b.latest >= t(11, 0, 0, plusDays = 1), "her real morning wake must be reachable")
        val times = SleepEdit.Times(t(3, 0, 0, plusDays = 1), t(3, 15, 0, plusDays = 1), t(11, 0, 0, plusDays = 1))
        assertNull(SleepEdit.validate(times, onset, wake, minDuration = Duration.ofMinutes(30)))
    }
}
