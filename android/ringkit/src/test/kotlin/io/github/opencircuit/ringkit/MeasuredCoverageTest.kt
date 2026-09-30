package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.MeasuredCoverage.Ground
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only behaviour tests for [MeasuredCoverage] and [DateInterval]. Upstream has no test file
 * of its own for `MeasuredCoverage.swift` — it is exercised through the sleep suites, whose vectors
 * port with the sleep code. Until then these pin the documented semantics: merged half-open spans,
 * the retention guard (`trusted`), and a `partition` that always tiles its range exactly. Expected
 * values are worked out by hand from those rules, not by running the code.
 */
class MeasuredCoverageTest {

    private val t0 = Instant.parse("2026-08-18T22:00:00Z")
    private fun at(min: Long): Instant = t0.plusSeconds(min * 60)
    private fun iv(a: Long, b: Long) = DateInterval(at(a), at(b))
    private fun piece(a: Long, b: Long, g: Ground) = MeasuredCoverage.Piece(iv(a, b), g)

    // --- DateInterval ---

    @Test
    fun dateIntervalIsHalfOpenAndRejectsAnEndBeforeItsStart() {
        val w = iv(0, 10)
        assertTrue(at(0) in w)
        assertFalse(at(10) in w, "half-open: the end instant is outside")
        assertTrue(w.containsClosed(at(10)), "the closed rule, for ports of Foundation's DateInterval")
        assertFalse(at(-1) in w)
        assertTrue(iv(5, 5).isEmpty)
        assertEquals(Duration.ofMinutes(10), w.duration)
        assertFailsWith<IllegalArgumentException> { DateInterval(at(10), at(0)) }
    }

    // --- construction ---

    @Test
    fun eachRecordCoversOneEpochAndTouchingEpochsMergeIntoOneSpan() {
        val dates = listOf(t0, t0.plusSeconds(150), t0.plusSeconds(300), t0.plusSeconds(1000))
        val c = MeasuredCoverage.ofRecordDates(dates)
        assertEquals(
            listOf(DateInterval(t0, t0.plusSeconds(450)), DateInterval(t0.plusSeconds(1000), t0.plusSeconds(1150))),
            c.intervals,
        )
        assertEquals(t0, c.earliestCovered)
        assertEquals(t0.plusSeconds(1150), c.latestCovered)
    }

    @Test
    fun constructionSortsCoalescesOverlapsAndDropsEmptySpans() {
        val c = MeasuredCoverage(listOf(iv(30, 40), iv(0, 10), iv(5, 20), iv(50, 50)))
        assertEquals(listOf(iv(0, 20), iv(30, 40)), c.intervals)
        assertTrue(MeasuredCoverage(listOf(iv(7, 7))).isEmpty)
    }

    @Test
    fun aNonPositiveEpochLengthCoversNothing() {
        assertEquals(MeasuredCoverage.EMPTY, MeasuredCoverage.ofRecordDates(listOf(t0), Duration.ZERO))
        assertTrue(MeasuredCoverage.ofRecordDates(listOf(t0), Duration.ofSeconds(-150)).isEmpty)
        assertNull(MeasuredCoverage.EMPTY.earliestCovered)
        assertNull(MeasuredCoverage.EMPTY.latestCovered)
    }

    @Test
    fun recordsCoverFromTheirCounterPlusTheSyncEpoch() {
        // Upstream's real FR02.018 page: 6 records, 150 s apart, first counter 0x0c22a16b.
        val page = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
            "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
            "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
            "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"
        val c = MeasuredCoverage.ofRecords(BulkSleep.recordsFromPage(hex(page)))
        val first = Instant.ofEpochSecond(1_577_793_600L + 0x0c22a16bL)
        assertEquals(listOf(DateInterval(first, first.plusSeconds(6 * 150L))), c.intervals)
    }

    // --- queries ---

    @Test
    fun queriesOverAWindowWithAHole() {
        val c = MeasuredCoverage(listOf(iv(0, 20), iv(30, 40)))
        val w = iv(10, 45)
        assertEquals(listOf(iv(10, 20), iv(30, 40)), c.measuredPortions(w))
        assertEquals(listOf(iv(20, 30), iv(40, 45)), c.unmeasuredPortions(w))
        assertEquals(Duration.ofMinutes(20), c.measuredDuration(w))
        assertEquals(Duration.ofMinutes(10), c.longestGap(w))
        assertEquals(20.0 / 35.0, c.fraction(w), 1e-12)
    }

    @Test
    fun anEmptyRangeYieldsNothingAndAFullyCoveredOneHasNoGap() {
        val c = MeasuredCoverage(listOf(iv(0, 60)))
        val empty = iv(10, 10)
        assertTrue(c.measuredPortions(empty).isEmpty())
        assertTrue(c.unmeasuredPortions(empty).isEmpty())
        assertTrue(c.partition(empty).isEmpty())
        assertEquals(0.0, c.fraction(empty))
        assertEquals(Duration.ZERO, c.longestGap(iv(5, 50)))
        assertEquals(1.0, c.fraction(iv(5, 50)))
    }

    // --- the retention guard ---

    @Test
    fun trustedIsUnknownForAnEmptyWindowOrOneWithNoRecordInIt() {
        val c = MeasuredCoverage(listOf(iv(60, 120)))
        assertNull(c.trusted(iv(0, 30)), "rule 1: an all-empty window says nothing")
        assertNull(c.trusted(iv(10, 10)))
        assertNull(MeasuredCoverage.EMPTY.trusted(iv(0, 30)))
        val t = assertNotNull(c.trusted(iv(0, 200)))
        assertEquals(at(60), t.provenFrom, "rule 2: the horizon is the first instant the oldest record covers")
        assertEquals(c.intervals, t.intervals)
    }

    @Test
    fun partitionTilesTheRangeAndSplitsAGapAtTheProofHorizon() {
        val c = MeasuredCoverage(listOf(iv(60, 90), iv(120, 150)), provenFrom = at(30))
        val parts = c.partition(iv(0, 180))
        assertEquals(
            listOf(
                piece(0, 30, Ground.UNKNOWN),
                piece(30, 60, Ground.UNMEASURED),
                piece(60, 90, Ground.MEASURED),
                piece(90, 120, Ground.UNMEASURED),
                piece(120, 150, Ground.MEASURED),
                piece(150, 180, Ground.UNMEASURED),
            ),
            parts,
        )
        parts.zipWithNext { a, b -> assertEquals(a.range.end, b.range.start, "pieces must be contiguous") }
    }

    @Test
    fun aTrustedCoverageCallsGroundBeforeItsOldestRecordUnknownAndTheTrailingEdgeUnmeasured() {
        val trusted = assertNotNull(MeasuredCoverage(listOf(iv(60, 90))).trusted(iv(0, 180)))
        assertEquals(
            listOf(piece(0, 60, Ground.UNKNOWN), piece(60, 90, Ground.MEASURED), piece(90, 180, Ground.UNMEASURED)),
            trusted.partition(iv(0, 180)),
        )
    }

    @Test
    fun anUntrustedCoverageNeverReportsUnknown() {
        val c = MeasuredCoverage(listOf(iv(60, 90)))
        assertEquals(Instant.MIN, c.provenFrom)
        assertTrue(c.partition(iv(0, 180)).none { it.ground == Ground.UNKNOWN })
    }

    // --- provenance labels ---

    @Test
    fun labelsWithNoProvenHoleGiveNoCoverage() {
        val segs = listOf(SleepSegment(at(0), at(60), SleepStage.ASLEEP_CORE))
        assertNull(MeasuredCoverage.fromProvenanceLabels(segs))
    }

    @Test
    fun labelCoverageTreatsOnlyAssertedAsUnmeasuredAndNeverSaysUnknown() {
        val segs = listOf(
            SleepSegment(at(0), at(60), SleepStage.ASLEEP_CORE),
            SleepSegment(at(60), at(90), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
            SleepSegment(at(90), at(120), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_COVERAGE_UNKNOWN),
        )
        val labels = assertNotNull(MeasuredCoverage.fromProvenanceLabels(segs))
        assertEquals(
            listOf(
                piece(-30, 0, Ground.UNMEASURED),
                piece(0, 60, Ground.MEASURED),
                piece(60, 90, Ground.UNMEASURED),
                piece(90, 120, Ground.MEASURED),
            ),
            labels.partition(iv(-30, 120)),
        )
    }

    // --- equality ---

    @Test
    fun equalityIsByMergedSpansAndHorizon() {
        assertEquals(MeasuredCoverage(listOf(iv(0, 10), iv(10, 20))), MeasuredCoverage(listOf(iv(0, 20))))
        assertEquals(MeasuredCoverage(listOf(iv(0, 10), iv(10, 20))).hashCode(), MeasuredCoverage(listOf(iv(0, 20))).hashCode())
        assertNotEquals(MeasuredCoverage(listOf(iv(0, 20))), MeasuredCoverage(listOf(iv(0, 20)), provenFrom = at(0)))
    }
}
