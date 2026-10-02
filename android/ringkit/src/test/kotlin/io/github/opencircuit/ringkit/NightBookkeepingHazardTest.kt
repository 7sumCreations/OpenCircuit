package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the rest of the night bookkeeping: the health-store settle
 * gate, the capture-coverage verdict, the score heal and the edited-night notice — exact boundaries,
 * negative and zero margins and spans, instants at the ends of time, reversed / duplicated / overflowing
 * hypnograms, and values the notice's formatter cannot render. Kept out of the upstream-port classes so
 * their counts stay exact.
 *
 * Every expected value was measured on upstream's pinned Swift build, except where a Kotlin bound
 * replaces an upstream trap or a `Double` sum that a `Duration` cannot hold; those cases say so.
 */
class NightBookkeepingHazardTest {

    private fun s(epoch: Long): Instant = Instant.ofEpochSecond(epoch)

    // MARK: settle gate

    @Test
    fun settleGateBoundariesAndTheEndsOfTimeAsUpstream() {
        val now = s(1_000_000)
        assertTrue(SleepHealthGate.isSettled(latestSegmentEnd = now, now = now, margin = Duration.ZERO))
        assertFalse(SleepHealthGate.isSettled(latestSegmentEnd = now.plusSeconds(1), now = now, margin = Duration.ZERO))
        assertTrue(SleepHealthGate.isSettled(latestSegmentEnd = now.plusSeconds(600), now = now, margin = Duration.ofSeconds(-600)), "a negative margin reaches into the future")
        assertFalse(SleepHealthGate.isSettled(latestSegmentEnd = now.plusSeconds(601), now = now, margin = Duration.ofSeconds(-600)))
        // Upstream measured with segment ends of ±3.2e16 s: far future never settled, far past settled,
        // and a finalization signal still writes a far-future night that has segments.
        assertFalse(SleepHealthGate.isSettled(latestSegmentEnd = Instant.MAX, now = now))
        assertTrue(SleepHealthGate.isSettled(latestSegmentEnd = Instant.MIN, now = now))
        assertTrue(SleepHealthGate.isReadyToWrite(latestSegmentEnd = Instant.MAX, now = now, finalized = true))
        assertFalse(SleepHealthGate.isSettled(latestSegmentEnd = s(-31_500_000_000_000_000), now = s(-31_500_000_000_000_000)))
        assertTrue(SleepHealthGate.isSettled(latestSegmentEnd = s(-31_500_000_000_000_000), now = s(-31_499_999_999_998_800)))
        // No upstream counterpart (a Swift Date never overflows): "now minus the margin" would leave
        // Instant's range here; the gate compares the gap instead and never throws.
        assertFalse(SleepHealthGate.isSettled(latestSegmentEnd = Instant.MIN, now = Instant.MIN, margin = Duration.ofSeconds(Long.MAX_VALUE)))
        assertTrue(SleepHealthGate.isSettled(latestSegmentEnd = Instant.MIN, now = Instant.MAX, margin = Duration.ofSeconds(Long.MIN_VALUE)))
    }

    // MARK: capture coverage

    @Test
    fun captureCoverageBoundariesAndTheEndsOfTimeAsUpstream() {
        val bed = s(1_780_000_000)
        fun c(onset: Instant, inBed: Duration, b: Instant?) = SleepCaptureCoverage.classify(capturedOnset = onset, capturedInBed = inBed, scheduledBedtime = b)
        val trunc = SleepCaptureCoverage.Coverage.LIKELY_TRUNCATED
        val full = SleepCaptureCoverage.Coverage.FULL
        assertEquals(trunc, c(bed.plusSeconds(5400), Duration.ofSeconds(18_300), bed), "buffer + slack exactly still fits")
        assertEquals(full, c(bed.plusSeconds(5400), Duration.ofSeconds(18_301), bed))
        assertEquals(full, c(bed.plusSeconds(5399), Duration.ofSeconds(18_300), bed))
        assertEquals(trunc, c(bed.plusSeconds(5400), Duration.ofSeconds(1), bed))
        assertEquals(trunc, c(bed.plusSeconds(86_400L * 400), Duration.ofHours(1), bed))
        // Upstream measured with onset / bedtime of ±3.2e16 s; here the ends of Instant's range.
        assertEquals(trunc, c(Instant.MAX, Duration.ofHours(1), Instant.MIN))
        assertEquals(full, c(Instant.MIN, Duration.ofHours(1), Instant.MAX))
    }

    // MARK: score heal

    private val t0 = s(1_700_000_000)
    private fun seg(a: Long, b: Long, st: SleepStage) = SleepSegment(t0.plusSeconds(a), t0.plusSeconds(b), st)
    private fun describe(segs: List<SleepSegment>): String {
        val sm = SleepScoreHeal.summary(segs) ?: return "nil"
        return "inBed=${sm.inBed.seconds} awake=${sm.awake.seconds} light=${sm.light.seconds} deep=${sm.deep.seconds} rem=${sm.rem.seconds} " +
            "score=${SleepScoreHeal.healedScore(hypnogram = segs) ?: "nil"}"
    }

    @Test
    fun scoreHealOnReversedDuplicatedAndLongHypnogramsAsUpstream() {
        assertEquals("nil", describe(listOf(seg(25_200, 0, SleepStage.IN_BED), seg(0, 3600, SleepStage.ASLEEP_CORE))), "reversed in-bed")
        assertEquals("nil", describe(listOf(seg(0, 25_200, SleepStage.IN_BED), seg(10_800, 3600, SleepStage.ASLEEP_CORE))), "reversed asleep")
        assertEquals(
            "inBed=25200 awake=0 light=18000 deep=-3600 rem=0 score=35",
            describe(listOf(seg(0, 25_200, SleepStage.IN_BED), seg(0, 18_000, SleepStage.ASLEEP_CORE), seg(7200, 3600, SleepStage.ASLEEP_DEEP))),
        )
        assertEquals(
            "inBed=50400 awake=0 light=43200 deep=0 rem=0 score=70",
            describe(listOf(seg(0, 25_200, SleepStage.IN_BED), seg(0, 25_200, SleepStage.IN_BED), seg(0, 21_600, SleepStage.ASLEEP_CORE), seg(0, 21_600, SleepStage.ASLEEP_CORE))),
        )
        assertEquals(
            "inBed=25200 awake=-3600 light=0 deep=18000 rem=0 score=73",
            describe(listOf(seg(0, 25_200, SleepStage.IN_BED), seg(3600, 0, SleepStage.AWAKE), seg(3600, 21_600, SleepStage.ASLEEP_DEEP))),
        )
        assertEquals("inBed=600000 awake=0 light=590000 deep=0 rem=0 score=75", describe(listOf(seg(0, 600_000, SleepStage.IN_BED), seg(0, 590_000, SleepStage.ASLEEP_CORE))))
        assertEquals(
            "inBed=43200 awake=43199 light=1 deep=0 rem=0 score=nil",
            describe(listOf(seg(0, 43_200, SleepStage.IN_BED), seg(0, 43_199, SleepStage.AWAKE), seg(43_199, 43_200, SleepStage.ASLEEP_CORE))),
            "the composite floors to 0 here, so the heal is refused",
        )
    }

    /**
     * Totals beyond `Duration`'s range: upstream sums seconds as `Double` and scores the night (74,
     * measured with the same 310 segments); a `Duration` sum would throw out of the heal. Bound: the
     * night cannot be described, so the row is left alone — the heal's own fail-safe answer.
     */
    @Test
    fun aHypnogramWhoseTotalsOverflowIsLeftAlone() {
        val a = s(-31_000_000_000_000_000)
        val b = s(31_000_000_000_000_000)
        val segs = List(160) { SleepSegment(a, b, SleepStage.IN_BED) } + List(150) { SleepSegment(a, b, SleepStage.ASLEEP_CORE) }
        assertNull(SleepScoreHeal.summary(segs))
        assertNull(SleepScoreHeal.healedScore(hypnogram = segs))
        // One such segment of each still fits and is scored.
        assertNotNull(SleepScoreHeal.healedScore(hypnogram = listOf(SleepSegment(a, b, SleepStage.IN_BED), SleepSegment(a, b, SleepStage.ASLEEP_CORE))))
    }

    // MARK: edited-night notice

    private fun line(m: Double, a: Double, h: Boolean = true) = SleepEditedNightNotice.line(measuredAsleep = m, assertedAsleep = a, mirrorsSleepToHealth = h)

    @Test
    fun noticeOnHostileValuesAsUpstream() {
        assertNull(line(Double.NaN, 600.0))
        assertNull(line(600.0, Double.NaN))
        assertNull(line(-1e-300, 600.0), "any negative measured value is the not-computed sentinel")
        assertNull(line(3600.0, 59.999))
        assertNull(line(Double.NEGATIVE_INFINITY, 600.0))
        val nothing = "We kept the times you set. The ring wasn’t recording for any of this night, so all 10 minutes of the sleep above is your account, not a measurement."
        assertEquals(nothing, line(-0.0, 600.0, h = false), "negative zero is not below zero")
        assertEquals(nothing, line(0.999, 600.0, h = false))
        assertEquals(
            "We kept the times you set. The ring recorded 277777777777h 47m of the sleep above; for the other 1 minute we have your account, not a measurement.",
            line(1e15, 60.0, h = false),
        )
    }

    @Test
    fun noticeDurationRoundsHalfAwayFromZeroAndFloorsAtOneMinuteAsUpstream() {
        val cases = listOf(
            89.999 to "1 minute", 90.0 to "2 minutes", 29.999 to "1 minute", 30.0 to "1 minute",
            -30.0 to "1 minute", -29.0 to "1 minute", 0.0 to "1 minute", 3570.0 to "1 hour",
            3569.9 to "59 minutes", 86_400.0 to "24 hours", 5.5e17 to "152777777777777h 46m",
            5.534023222112865e20 to "153722867280912913h 4m",
            -553_402_322_211_286_548_480.0 to "1 minute", // exactly -2^63 minutes: still a 64-bit integer upstream
        )
        for ((seconds, text) in cases) assertEquals(text, SleepEditedNightNotice.duration(seconds), "duration($seconds)")
    }

    /**
     * Values upstream TRAPS on (measured: "Double value cannot be converted to Int because it is either
     * infinite or NaN" / "... greater than Int.max"): the formatter rejects them, and the line stays
     * silent rather than throwing out of the card.
     */
    @Test
    fun valuesUpstreamTrapsOnAreRejectedAndTheLineStaysSilent() {
        for (bad in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 553_402_322_211_286_548_480.0)) {
            assertFailsWith<IllegalArgumentException>("duration($bad)") { SleepEditedNightNotice.duration(bad) }
        }
        assertNull(line(Double.POSITIVE_INFINITY, 600.0))
        assertNull(line(600.0, Double.POSITIVE_INFINITY))
        assertNull(line(1e300, 600.0))
        assertNull(line(600.0, 1e300))
    }
}
