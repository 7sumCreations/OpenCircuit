package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * EDIT → RECONCILE → RECONCILE MUST NOT LEAVE TWO NIGHTS IN THE HEALTH STORE.
 *
 * The decision that governs duplication is a pair of pure values: what we PUBLISH, and what we report
 * as WITHHELD. The delete that removes the previous copy of a night spares every withheld span — so a
 * span both published AND reported as withheld bars the cleanup from the ground the fresh write just
 * landed on, and the store keeps both copies. The control test runs the stale "every asserted span"
 * definition through the same model and shows the duplicate.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepHealthPublicationIdempotencyTests.swift
 * (@ b1c2fdd) — all 4 tests.
 */
class SleepHealthPublicationIdempotencyTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(m: Long): Instant = t0.plusSeconds(m * 60)
    private fun iv(a: Long, b: Long) = DateInterval(at(a), at(b))

    /** A recorded night 00:00 → 08:00 extended to 10:00 over ground the records prove empty. */
    private val edited: List<SleepSegment>
        get() {
            val base = listOf(SleepSegment(at(0), at(480), SleepStage.IN_BED), SleepSegment(at(0), at(480), SleepStage.ASLEEP_CORE))
            return SleepEdit.recompute(
                base,
                SleepEdit.Times(inBedStart = at(0), sleepOnset = at(0), sleepWake = at(600)),
                coverage = MeasuredCoverage(listOf(iv(0, 480))),
            )
        }

    /** Swift's `Range.overlaps`: half-open, and an empty range overlaps nothing. */
    private fun overlaps(a: DateInterval, b: DateInterval): Boolean =
        !(b.end <= a.start || a.end <= b.start || a.isEmpty || b.isEmpty)

    // MARK: the invariant

    @Test
    fun noPublishedSpanIsAlsoReportedAsWithheld() {
        val segments = edited
        assertTrue(segments.containsAssertedTime, "precondition: this night has a proven hole")
        assertFalse(SleepStaging.totalAsleep(segments.healthUserEntered) == Duration.ZERO, "precondition: the hole is published as the wearer's own entry")

        for (span in segments.withheldSpans) {
            for (seg in segments.healthPublishable) {
                if (seg.end > span.start && seg.start < span.end) {
                    fail("published $seg overlaps withheld $span — the cleanup cannot remove the previous copy of this span, so the store keeps both")
                }
            }
        }
    }

    // MARK: the model — one reconcile, then another

    /** One health-store sleep sample, as this app's cleanup sees it. */
    private data class Sample(val id: Int, val span: DateInterval, val app: Boolean) // app: authored by us; the store refuses to delete anyone else's

    /**
     * A minimal model of the transition cleanup: delete this app's sleep OVERLAPPING the recorded
     * in-bed span, EXCEPT the samples just written and except every withheld span (overlap semantics
     * for the exclusions too).
     */
    private fun afterCleanup(inHealth: List<Sample>, freshIds: Set<Int>, recorded: DateInterval, withheld: List<DateInterval>): List<Sample> =
        inHealth.filter { sample ->
            when {
                !sample.app -> true
                !overlaps(sample.span, recorded) -> true
                sample.id in freshIds -> true
                withheld.any { overlaps(sample.span, it) } -> true
                else -> false
            }
        }

    @Test
    fun aSecondReconcileLeavesExactlyOneCopyOfTheAssertedSpan() {
        val segments = edited
        val hole = iv(480, 600)
        val recorded = iv(0, 480)

        // An UNTRACKED prior sample over the hole: only the overlap cleanup can ever remove it.
        val prior = Sample(1, iv(0, 600), app = true)
        // The fresh write: everything the publication publishes.
        val fresh = Sample(2, iv(0, 600), app = true)

        val after = afterCleanup(listOf(prior, fresh), setOf(fresh.id), recorded, segments.withheldSpans)
        assertEquals(listOf(fresh.id), after.map { it.id }, "the previous copy of the night must be removed — one night, one copy")
        assertEquals(1, after.count { overlaps(it.span, hole) }, "the asserted span must exist exactly once in the health store")
    }

    @Test
    fun theSTALEWithheldDefinitionIsWhatDuplicatesIt() {
        // THE CONTROL: every asserted span, whether or not we write it. Same model, one duplicate.
        val segments = edited
        val staleWithheld = MeasuredCoverage(
            segments.filter { it.provenance.isProvenUnmeasured && it.end > it.start }.map { DateInterval(it.start, it.end) },
        ).intervals
        assertFalse(staleWithheld.isEmpty(), "precondition: the stale rule names the hole")

        val prior = Sample(1, iv(0, 600), app = true)
        val fresh = Sample(2, iv(0, 600), app = true)
        val after = afterCleanup(listOf(prior, fresh), setOf(fresh.id), iv(0, 480), staleWithheld)
        assertEquals(2, after.size, "with the stale definition the previous night survives — this is the defect")
    }

    @Test
    fun anotherAppsSleepIsNeverRemovedByEitherPass() {
        val segments = edited
        val theirs = Sample(9, iv(60, 120), app = false)
        val fresh = Sample(2, iv(0, 600), app = true)
        val after = afterCleanup(listOf(theirs, fresh), setOf(fresh.id), iv(0, 480), segments.withheldSpans)
        assertTrue(theirs in after)
    }
}
