package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the manual sleep edit: reversed edits and a reversed
 * recorded night, edits days outside the recorded night, windows longer than 48 h, minimum-duration
 * and night-span edges, coverage edges, hostile base segments through `recompute`, reversed and
 * sentinel windows through `widenRecorded`, the picker-minute comparison across DST and a zone whose
 * offset carries seconds, and instants at the ends of `Instant`'s range. Kept out of the
 * upstream-port classes so their counts stay exact.
 *
 * Every expected value was measured on upstream's pinned Swift build with the same instants (offsets
 * in seconds from 1 700 000 000 unless stated); the end-of-range cases have no upstream counterpart
 * (a Swift `Date` never overflows) and pin the Kotlin bound instead.
 */
class SleepEditHazardTest {

    private val ref: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(h: Double): Instant = ref.plusSeconds((h * 3600).toLong())
    private fun off(t: Instant): Long = Duration.between(ref, t).seconds
    private fun seg(a: Double, b: Double, s: SleepStage, p: SleepProvenance = SleepProvenance.MEASURED) = SleepSegment(at(a), at(b), s, p)
    private fun describe(segs: List<SleepSegment>): String =
        segs.joinToString(" ") { "(${off(it.start)},${off(it.end)},${it.stage.rawValue},${it.provenance.rawValue})" }
    private fun describe(b: SleepEdit.Bounds): String = "(${off(b.earliest)},${off(b.latest)})"
    private fun times(a: Double, o: Double, w: Double) = SleepEdit.Times(at(a), at(o), at(w))

    @Test
    fun reversedEditsAreRejectedInUpstreamsOrder() {
        assertEquals(SleepEdit.Invalid.OnsetBeforeBedtime, SleepEdit.validate(times(7.0, 4.0, 1.0), at(0.0), at(8.0)))
        assertEquals(SleepEdit.Invalid.WakeNotAfterOnset, SleepEdit.validate(times(0.0, 4.0, 2.0), at(0.0), at(8.0)))
        assertNull(SleepEdit.validate(times(1.0, 1.0, 8.0), at(0.0), at(8.0)), "an onset AT bedtime is fine")
        assertEquals(SleepEdit.Invalid.EndNotAfterStart, SleepEdit.validate(SleepEdit.Window(at(58.0), at(50.0)), at(0.0), at(8.0)))
        // recompute refuses a reversed or empty edit outright.
        val base = listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE))
        assertEquals("", describe(SleepEdit.recompute(base, times(2.0, 2.0, 2.0))))
        assertEquals("", describe(SleepEdit.recompute(base, times(7.0, 4.0, 1.0))))
        assertEquals("", describe(SleepEdit.recompute(base, times(2.0, 1.0, 8.0))))
        assertEquals("", describe(SleepEdit.recompute(base, SleepEdit.Window(at(6.0), at(5.0)))))
    }

    @Test
    fun aReversedOrEmptyRecordedNightStillGivesUpstreamsBounds() {
        assertEquals("(7200,68400)", describe(SleepEdit.bounds(at(8.0), at(0.0))))
        assertEquals(Duration.ofSeconds(50_400), SleepEdit.maxWindowDuration(at(8.0), at(0.0)))
        assertEquals(SleepEdit.Invalid.StartBeforeEarliest, SleepEdit.validate(times(0.0, 1.0, 7.0), at(8.0), at(0.0)))
        assertEquals(SleepEdit.Invalid.StartBeforeEarliest, SleepEdit.validate(SleepEdit.Window(at(0.0), at(7.0)), at(8.0), at(0.0)))
        assertEquals("(-10800,50400)", describe(SleepEdit.bounds(at(3.0), at(3.0))))
    }

    @Test
    fun editsDaysOutsideTheRecordedNightAreRejected() {
        assertEquals(SleepEdit.Invalid.StartBeforeEarliest, SleepEdit.validate(times(-50.0, -49.0, -42.0), at(0.0), at(8.0)))
        assertEquals(SleepEdit.Invalid.EndAfterLatest, SleepEdit.validate(times(50.0, 51.0, 58.0), at(0.0), at(8.0)))
        assertEquals(SleepEdit.Invalid.EndAfterLatest, SleepEdit.validate(times(50.0, 51.0, 58.0), at(0.0), at(8.0), minDuration = Duration.ofHours(9)))
        assertEquals(SleepEdit.Invalid.StartBeforeEarliest, SleepEdit.validate(SleepEdit.Window(at(-50.0), at(-42.0)), at(0.0), at(8.0)))
        assertEquals(SleepEdit.Invalid.EndAfterLatest, SleepEdit.validate(SleepEdit.Window(at(50.0), at(58.0)), at(0.0), at(8.0)))
    }

    @Test
    fun windowsLongerThan48Hours() {
        assertEquals(SleepEdit.Invalid.EndAfterLatest, SleepEdit.validate(times(-6.0, -5.0, 44.0), at(0.0), at(8.0)))
        // A saved 50 h edit stays selectable and re-savable, one hour more is not.
        val saved50 = DateInterval(at(-6.0), at(44.0))
        assertEquals("(-21600,158400)", describe(SleepEdit.bounds(at(0.0), at(8.0), existingEdit = saved50)))
        assertEquals(Duration.ofSeconds(180_000), SleepEdit.maxWindowDuration(at(0.0), at(8.0), existingEdit = saved50))
        assertNull(SleepEdit.validate(times(-6.0, -5.0, 44.0), at(0.0), at(8.0), existingEdit = saved50))
        assertEquals(SleepEdit.Invalid.EndAfterLatest, SleepEdit.validate(times(-6.0, -5.0, 45.0), at(0.0), at(8.0), existingEdit = saved50))
        // A 60 h recorded night: its whole parity floor is savable, past it the too-long rule.
        assertEquals("(-21600,237600)", describe(SleepEdit.bounds(at(0.0), at(60.0))))
        assertEquals(Duration.ofSeconds(237_600), SleepEdit.maxWindowDuration(at(0.0), at(60.0)))
        assertNull(SleepEdit.validate(times(-3.0, 0.0, 63.0), at(0.0), at(60.0)))
        assertEquals(SleepEdit.Invalid.TooLong(maxMinutes = 3960), SleepEdit.validate(times(-4.0, 0.0, 63.0), at(0.0), at(60.0)))
        // recompute fills a 50 h window as asked (the validator, not recompute, bounds the night).
        assertEquals("(0,180000,asleepCore,measured)", describe(SleepEdit.recompute(emptyList(), SleepEdit.Window(at(0.0), at(50.0)))))
        assertEquals(
            "(-3600,180000,inBed,measured) (-3600,0,awake,measured) (0,28800,asleepCore,measured) (28800,180000,asleepCore,measured)",
            describe(SleepEdit.recompute(listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE)), times(-1.0, 0.0, 50.0))),
        )
    }

    @Test
    fun minimumDurationAndNightSpanEdges() {
        assertNull(SleepEdit.validate(times(0.0, 1.0, 2.0), at(0.0), at(8.0), minDuration = Duration.ofMillis(-90_500)))
        // Minutes are whole, truncated toward zero, as Swift's `Int(_:)`.
        assertEquals(SleepEdit.Invalid.TooShort(minMinutes = 60), SleepEdit.validate(times(0.0, 1.0, 2.0), at(0.0), at(8.0), minDuration = Duration.ofSeconds(3659)))
        assertEquals(SleepEdit.Invalid.TooShort(minMinutes = 61), SleepEdit.validate(SleepEdit.Window(at(0.0), at(1.0)), at(0.0), at(8.0), minDuration = Duration.ofSeconds(3719)))

        assertEquals("(-21600,50400)", describe(SleepEdit.bounds(at(0.0), at(8.0), dataCoverage = DateInterval(at(-100.0), at(100.0)))))
        assertEquals("(-360000,50400)", describe(SleepEdit.bounds(at(0.0), at(8.0), existingEdit = DateInterval(at(-100.0), at(-90.0)))))
        assertEquals("(-21600,50400)", describe(SleepEdit.bounds(at(0.0), at(8.0), dataCoverage = DateInterval(at(-10.0), at(20.0)), maxNightSpan = Duration.ofHours(-1))))
        assertEquals(Duration.ofSeconds(50_400), SleepEdit.maxWindowDuration(at(0.0), at(8.0), maxNightSpan = Duration.ofHours(-1)))
        assertEquals("(-21600,50400)", describe(SleepEdit.bounds(at(0.0), at(8.0), maxNightSpan = Duration.ZERO)))
        assertEquals("(-360000,3589200)", describe(SleepEdit.bounds(at(0.0), at(8.0), dataCoverage = DateInterval(at(-100.0), at(100.0)), maxNightSpan = Duration.ofHours(1000))))
        // clamp on bounds that cross: Swift's min(max(...)) gives the latest edge.
        assertEquals(at(2.0), SleepEdit.clamp(at(5.0), SleepEdit.Bounds(at(8.0), at(2.0))))
    }

    @Test
    fun dataCoverageEdges() {
        fun cov(r: DateInterval?) = r?.let { "${off(it.start)}...${off(it.end)}" } ?: "nil"
        assertEquals("-10800...18000", cov(SleepEdit.dataCoverage(listOf(at(5.0), at(1.0), at(5.0), at(-3.0), at(30.0)), at(0.0), at(8.0))))
        assertEquals("-21600...50400", cov(SleepEdit.dataCoverage(listOf(at(-6.0), at(14.0)), at(0.0), at(8.0)))) // both ends inclusive
        assertEquals("nil", cov(SleepEdit.dataCoverage(listOf(at(-6.0).minusSeconds(1), at(14.0).plusSeconds(1)), at(0.0), at(8.0))))
        assertEquals("nil", cov(SleepEdit.dataCoverage(listOf(at(1.0), at(4.0)), at(0.0), at(8.0), maxNightSpan = Duration.ofHours(-1))))
        assertEquals("nil", cov(SleepEdit.dataCoverage(listOf(at(0.0), at(4.0), at(8.0)), at(0.0), at(8.0), maxNightSpan = Duration.ZERO)))
        assertEquals("0...28800", cov(SleepEdit.dataCoverage(listOf(at(0.0), at(4.0), at(8.0)), at(8.0), at(0.0), maxNightSpan = Duration.ZERO)))
        assertEquals("10800...10800", cov(SleepEdit.dataCoverage(listOf(at(3.0)), at(0.0), at(8.0))))
    }

    @Test
    fun recomputeOnHostileBaseSegmentsMatchesUpstream() {
        fun window(base: List<SleepSegment>, s: Double, e: Double) = describe(SleepEdit.recompute(base, SleepEdit.Window(at(s), at(e))))
        val overlapping = listOf(seg(2.0, 6.0, SleepStage.ASLEEP_CORE), seg(0.0, 3.0, SleepStage.ASLEEP_DEEP), seg(5.0, 8.0, SleepStage.ASLEEP_REM))
        assertEquals("(3600,10800,asleepDeep,measured) (7200,21600,asleepCore,measured) (18000,25200,asleepREM,measured)", window(overlapping, 1.0, 7.0))
        assertEquals(
            "(-3600,0,asleepCore,measured) (0,10800,asleepDeep,measured) (7200,21600,asleepCore,measured) (18000,28800,asleepREM,measured) (28800,32400,asleepCore,measured)",
            window(overlapping, -1.0, 9.0),
        )
        assertEquals(
            "(-3600,0,asleepCore,measured) (0,7200,asleepDeep,measured) (10800,32400,asleepCore,measured)",
            window(listOf(seg(5.0, 3.0, SleepStage.ASLEEP_CORE), seg(0.0, 2.0, SleepStage.ASLEEP_DEEP)), -1.0, 9.0),
        )
        assertEquals("(0,18000,asleepCore,measured) (10800,28800,asleepCore,measured)", window(listOf(seg(5.0, 3.0, SleepStage.ASLEEP_CORE)), 0.0, 8.0))
        assertEquals(
            "(-3600,0,asleepCore,measured) (0,28800,asleepCore,measured) (0,28800,asleepCore,measured) (28800,32400,asleepCore,measured)",
            window(listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE), seg(0.0, 8.0, SleepStage.ASLEEP_CORE)), -1.0, 9.0),
        )
        assertEquals(
            "(-3600,0,inBed,measured) (-3600,0,asleepCore,measured) (0,28800,inBed,measured) (0,28800,asleepCore,measured) " +
                "(28800,180000,inBed,measured) (28800,180000,asleepCore,measured)",
            window(listOf(seg(0.0, 8.0, SleepStage.IN_BED), seg(0.0, 8.0, SleepStage.ASLEEP_CORE)), -1.0, 50.0),
        )
        assertEquals("(0,10800,asleepCore,measured) (10800,28800,asleepCore,measured)", window(listOf(seg(3.0, 3.0, SleepStage.ASLEEP_CORE)), 0.0, 8.0))
        assertEquals(
            "(-3600,0,inBed,measured) (-3600,0,asleepCore,measured) (0,28800,inBed,measured) (28800,32400,inBed,measured) (28800,32400,asleepCore,measured)",
            window(listOf(seg(0.0, 8.0, SleepStage.IN_BED)), -1.0, 9.0),
        )
        assertEquals(
            "(-3600,0,asleepCore,measured) (0,28800,awake,measured) (28800,32400,asleepCore,measured)",
            window(listOf(seg(0.0, 8.0, SleepStage.AWAKE)), -1.0, 9.0),
        )
        assertEquals(
            "(3600,14400,asleepCore,asserted) (14400,28800,asleepDeep,assertedOverMeasured) (28800,32400,asleepCore,measured)",
            window(listOf(seg(0.0, 4.0, SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED), seg(4.0, 8.0, SleepStage.ASLEEP_DEEP, SleepProvenance.ASSERTED_OVER_MEASURED)), 1.0, 9.0),
        )

        fun three(base: List<SleepSegment>, a: Double, o: Double, w: Double, c: MeasuredCoverage? = null) =
            describe(SleepEdit.recompute(base, times(a, o, w), coverage = c))
        val night = listOf(seg(0.0, 8.0, SleepStage.ASLEEP_CORE))
        assertEquals(
            "(-3600,32400,inBed,measured) (-3600,3600,awake,measured) (3600,10800,asleepDeep,measured) (7200,21600,asleepCore,measured) " +
                "(18000,28800,asleepREM,measured) (28800,32400,asleepCore,measured)",
            three(overlapping, -1.0, 1.0, 9.0),
        )
        assertEquals(
            "(-3600,32400,inBed,measured) (-3600,0,awake,measured) (0,7200,asleepDeep,measured) (10800,32400,asleepCore,measured)",
            three(listOf(seg(5.0, 3.0, SleepStage.ASLEEP_CORE), seg(0.0, 2.0, SleepStage.ASLEEP_DEEP)), -1.0, 0.0, 9.0),
        )
        for (stage in listOf(SleepStage.AWAKE, SleepStage.IN_BED)) {
            assertEquals("(-3600,32400,inBed,measured) (-3600,0,awake,measured) (0,32400,asleepCore,measured)", three(listOf(seg(0.0, 8.0, stage)), -1.0, 0.0, 9.0), "$stage-only base")
        }
        assertEquals(
            "(-3600,32400,inBed,measured) (-3600,0,awake,measured) (0,28800,asleepCore,measured) (0,28800,asleepCore,measured) (28800,32400,asleepCore,measured)",
            three(night + night, -1.0, 0.0, 9.0),
        )
        assertEquals(
            "(12240,16560,inBed,measured) (12240,12600,awake,measured)",
            three(listOf(seg(0.0, 3.0, SleepStage.ASLEEP_CORE), seg(5.0, 8.0, SleepStage.ASLEEP_DEEP)), 3.4, 3.5, 4.6),
        )
        assertEquals(
            "(-3600,32400,inBed,measured) (-3600,0,awake,measured) (0,28800,asleepCore,measured) (28800,32400,asleepCore,measured)",
            three(night, -1.0, 0.0, 9.0, MeasuredCoverage.EMPTY),
        )
        assertEquals(
            "(-3600,0,inBed,assertedCoverageUnknown) (0,28800,inBed,assertedOverMeasured) (28800,36000,inBed,asserted) " +
                "(-3600,0,awake,assertedCoverageUnknown) (0,28800,asleepCore,measured) (28800,36000,asleepCore,asserted)",
            three(night, -1.0, 0.0, 10.0, MeasuredCoverage(listOf(DateInterval(at(0.0), at(8.0))))),
        )
        assertEquals(
            "(-3600,7200,inBed,assertedCoverageUnknown) (7200,28800,inBed,assertedOverMeasured) (28800,36000,inBed,asserted) " +
                "(-3600,0,awake,assertedCoverageUnknown) (0,28800,asleepCore,measured) (28800,36000,asleepCore,asserted)",
            three(night, -1.0, 0.0, 10.0, MeasuredCoverage(listOf(DateInterval(at(2.0), at(8.0))))),
        )
        assertEquals("(32400,36000,inBed,measured) (32400,36000,asleepCore,measured)", three(night, 9.0, 9.0, 10.0))
    }

    @Test
    fun widenRecordedOnReversedAndSentinelWindows() {
        fun w(s: Double, e: Double, o: Double = s, k: Double = e) = SleepEdit.RecordedWindow(at(s), at(e), at(o), at(k))
        fun describe(x: SleepEdit.RecordedWindow?) = x?.let { "(${off(it.inBedStart)},${off(it.inBedEnd)},${off(it.sleepOnset)},${off(it.sleepWake)})" } ?: "nil"
        assertEquals("(14400,28800,14400,28800)", describe(SleepEdit.widenRecorded(w(9.0, 3.0), w(4.0, 8.0))))
        assertEquals("nil", describe(SleepEdit.widenRecorded(w(3.0, 9.0), w(10.0, 2.0))))
        assertEquals("(7200,36000,10800,32400)", describe(SleepEdit.widenRecorded(w(3.0, 9.0), w(2.0, 10.0, 8.0, 4.0))))
        assertEquals("(7200,36000,10800,32400)", describe(SleepEdit.widenRecorded(w(3.0, 9.0, 8.0, 4.0), w(2.0, 10.0, 3.0, 9.0))))
        assertEquals("nil", describe(SleepEdit.widenRecorded(w(3.0, 9.0), w(2.0, 2.0))))
        // One second past the sentinel is a known edge; at or before it is unknown.
        val dp = SleepEdit.DISTANT_PAST
        val justAfter = SleepEdit.RecordedWindow(dp.plusSeconds(1), dp.plusSeconds(2), dp.plusSeconds(1), dp.plusSeconds(2))
        assertEquals("(-63835769599,32400,-63835769599,32400)", describe(SleepEdit.widenRecorded(justAfter, w(3.0, 9.0))))
        val before = SleepEdit.RecordedWindow(dp.minusSeconds(10), at(5.0), dp.minusSeconds(10), at(5.0))
        assertEquals("(10800,32400,10800,32400)", describe(SleepEdit.widenRecorded(before, w(3.0, 9.0))))
        // Instant.MIN lies before the sentinel, so it is unknown too (no upstream counterpart).
        val min = SleepEdit.RecordedWindow(Instant.MIN, Instant.MIN, Instant.MIN, Instant.MIN)
        assertEquals("(10800,32400,10800,32400)", describe(SleepEdit.widenRecorded(min, w(3.0, 9.0))))
        assertEquals("nil", describe(SleepEdit.widenRecorded(w(3.0, 9.0), min)))
    }

    /**
     * Upstream compares at its calendar's minute granularity, which cuts at whole minutes of absolute
     * time: one hour apart across New York's DST fall-back is not the same minute although the wall
     * clock reads 01:30 twice, and in Monrovia in 1970 (offset −0:44:30) the cut is the UTC minute,
     * not the local one.
     */
    @Test
    fun pickerMinuteIsAWholeMinuteOfAbsoluteTimeInEveryZone() {
        val newYork = ZoneId.of("America/New_York")
        val first = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(newYork).withEarlierOffsetAtOverlap().toInstant()
        assertEquals(1_793_511_000L, first.epochSecond)
        assertFalse(SleepEdit.isSamePickerMinute(first, first.plusSeconds(3600), newYork))
        assertFalse(SleepEdit.isSamePickerMinute(first.plusSeconds(20), first.plusSeconds(3610), newYork))

        val monrovia = ZoneId.of("Africa/Monrovia")
        val z = Instant.EPOCH
        assertEquals(-2670, monrovia.rules.getOffset(z).totalSeconds, "precondition: the offset carries seconds")
        assertTrue(SleepEdit.isSamePickerMinute(z.plusSeconds(10), z.plusSeconds(40), monrovia))
        assertFalse(SleepEdit.isSamePickerMinute(z.plusSeconds(35), z.plusSeconds(85), monrovia))
        assertTrue(SleepEdit.isSamePickerMinute(z.plusSeconds(29), z.plusSeconds(31), monrovia))

        val utc = ZoneId.of("UTC")
        assertTrue(SleepEdit.isSamePickerMinute(z.plusSeconds(10), z.plusSeconds(40), utc))
        assertFalse(SleepEdit.isSamePickerMinute(z.minusSeconds(1), z, utc))
        assertTrue(SleepEdit.isSamePickerMinute(z.minusSeconds(30), z.minusSeconds(1), utc))
        assertFalse(SleepEdit.isSamePickerMinute(z.plusMillis(59_900), z.plusSeconds(60), utc))
        assertTrue(SleepEdit.isSamePickerMinute(z.plusSeconds(55), z.plusSeconds(5), utc))
        assertTrue(SleepEdit.isSamePickerMinute(z.plusSeconds(10), z.plusSeconds(40), ZoneId.of("Asia/Kolkata")))
        // The ends of Instant's range compare without throwing (upstream's calendar cannot place
        // dates that far out and answers false; see the porting record).
        assertTrue(SleepEdit.isSamePickerMinute(Instant.MAX, Instant.MAX.minusSeconds(10), utc))
        assertFalse(SleepEdit.isSamePickerMinute(Instant.MIN, Instant.MAX, ZoneId.of("Pacific/Kiritimati")))
    }

    /** A DST night is plain instant arithmetic: the margins are absolute hours, not wall-clock ones. */
    @Test
    fun dstNightsKeepAbsoluteMargins() {
        val newYork = ZoneId.of("America/New_York")
        for ((onset, wake) in listOf(
            LocalDateTime.of(2026, 3, 7, 23, 0) to LocalDateTime.of(2026, 3, 8, 7, 0), // spring forward: 7 h real
            LocalDateTime.of(2026, 10, 31, 23, 0) to LocalDateTime.of(2026, 11, 1, 7, 0), // fall back: 9 h real
        )) {
            val o = onset.atZone(newYork).toInstant()
            val w = wake.atZone(newYork).toInstant()
            val b = SleepEdit.bounds(o, w)
            assertEquals(o.minus(SleepEdit.STRANDED_EDIT_MARGIN), b.earliest, "$onset")
            assertEquals(w.plus(SleepEdit.STRANDED_EDIT_MARGIN), b.latest, "$onset")
            assertNull(SleepEdit.validate(SleepEdit.Times(o.minusSeconds(1800), o, w.plusSeconds(3600)), o, w))
        }
    }

    /** Instants at the ends of `Instant`'s range saturate instead of throwing (a Swift `Date` never overflows). */
    @Test
    fun theEndsOfTimeSaturateInsteadOfThrowing() {
        val min = Instant.MIN
        val max = Instant.MAX
        val low = SleepEdit.bounds(min, min.plus(Duration.ofHours(8)))
        assertEquals(min, low.earliest)
        assertEquals(min.plus(Duration.ofHours(14)), low.latest)
        val high = SleepEdit.bounds(max.minus(Duration.ofHours(8)), max)
        assertEquals(max.minus(Duration.ofHours(14)), high.earliest)
        assertEquals(max, high.latest)
        val whole = SleepEdit.bounds(min, max, dataCoverage = DateInterval(min, max), existingEdit = DateInterval(min, max))
        assertEquals(SleepEdit.Bounds(min, max), whole)
        assertEquals(SleepEdit.Bounds(min, max), SleepEdit.bounds(min, max, maxNightSpan = Duration.ofSeconds(Long.MAX_VALUE)))
        assertNull(SleepEdit.validate(SleepEdit.Times(min, min, max), min, max))
        assertEquals(DateInterval(min, max), SleepEdit.dataCoverage(listOf(min, max), max, min, maxNightSpan = Duration.ofSeconds(Long.MAX_VALUE)))
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE), SleepEdit.maxWindowDuration(min, max, maxNightSpan = Duration.ofSeconds(Long.MAX_VALUE)))
        assertEquals(SleepEdit.Invalid.EndAfterLatest, SleepEdit.validate(SleepEdit.Times(min, min, max), min, min.plus(Duration.ofHours(8))))
        assertEquals(listOf(SleepSegment(min, max, SleepStage.ASLEEP_CORE)), SleepEdit.recompute(emptyList(), SleepEdit.Window(min, max)))
    }
}
