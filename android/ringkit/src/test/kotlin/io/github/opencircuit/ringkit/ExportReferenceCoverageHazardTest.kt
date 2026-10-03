package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportReferenceCoverage.Reference
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Kotlin-only checks of the reference-wake coverage where a port could quietly differ from Swift: the
 * strict bounds at the edge instant, and the signed seconds past the reported end, which upstream
 * computes as the difference of two `Date` doubles (seconds since 2001), not as an exact duration.
 * Kept out of the upstream-port classes so their counts stay exact.
 *
 * Every expected value was printed by upstream's own `ExportReferenceCoverage` at the pin, built with
 * Swift 6.3.2 on macOS 26, for the same inputs.
 */
class ExportReferenceCoverageHazardTest {

    private val now: Instant = Instant.ofEpochSecond(1_755_000_000)

    @Test
    fun aWakeExactlyAtTheExportInstantStandsAndOneAMillisecondLaterIsClamped() {
        assertEquals(ExportReferenceCoverage.ReferenceEnd(now, Reference.MANUAL_SCHEDULE_WAKE), ExportReferenceCoverage.reference(now, asOf = now))
        assertEquals(
            ExportReferenceCoverage.ReferenceEnd(now, Reference.MANUAL_SCHEDULE_WAKE_SO_FAR),
            ExportReferenceCoverage.reference(now.plusMillis(1), asOf = now),
            "a wake still ahead of the export instant is closed at the export instant",
        )
        val past = now.minusSeconds(1)
        assertEquals(ExportReferenceCoverage.ReferenceEnd(past, Reference.MANUAL_SCHEDULE_WAKE), ExportReferenceCoverage.reference(past, asOf = now))
        assertNull(ExportReferenceCoverage.reference(null, asOf = now), "no schedule, no reference")
    }

    @Test
    fun aReferenceAtOrBeforeTheReportedStartHasNoWindowAndATinyOneExpectsNothing() {
        val end = now.plusSeconds(3 * 3600)
        assertNull(ExportReferenceCoverage.assess(emptyList(), now, end, referenceEnd = now, reference = Reference.MANUAL_SCHEDULE_WAKE))
        assertNull(ExportReferenceCoverage.assess(emptyList(), now, end, referenceEnd = now.minusSeconds(1), reference = Reference.MANUAL_SCHEDULE_WAKE))

        // One second of window: nothing expected, the sample on its start observed, no gap (measured).
        val row = assertNotNull(
            ExportReferenceCoverage.assess(listOf(now, now.plusSeconds(150)), now, end, referenceEnd = now.plusSeconds(1), reference = Reference.MANUAL_SCHEDULE_WAKE_SO_FAR),
        )
        assertEquals(Reference.MANUAL_SCHEDULE_WAKE_SO_FAR, row.reference)
        assertEquals(-10_799.0, row.beyondReportedEndSeconds)
        assertEquals(0L, row.assessment.expectedSamples)
        assertEquals(1, row.assessment.observedSamples)
        assertEquals(0.0, row.assessment.coverageFraction)
        assertEquals(emptyList(), row.assessment.gaps)
    }

    /**
     * Upstream's `referenceEnd.timeIntervalSince(reportedEnd)` subtracts the two dates' doubles, so a
     * millisecond-stamped pair prints the double difference (10800.333000183105), not 10800.333. The
     * export prints this number with 17 significant digits, so the exact difference would change bytes.
     */
    @Test
    fun theSecondsPastTheReportedEndAreTheDifferenceOfTheTwoDateDoubles() {
        val cases = listOf(
            Triple(1_755_000_000.123, 1_755_010_800.456, 10_800.333000183105),
            Triple(1_755_000_000.580, 1_755_003_600.0, 3_599.4200000762939),
            Triple(1_755_000_000.1, 1_755_000_000.3, 0.20000004768371582),
            Triple(1_755_010_800.456, 1_755_000_000.123, -10_800.333000183105),
        )
        for ((reported, reference, expected) in cases) {
            val reportedEnd = FoundationDate.unix(reported)
            val row = assertNotNull(
                ExportReferenceCoverage.assess(
                    emptyList(), reportedEnd.minusSeconds(36_000), reportedEnd, FoundationDate.unix(reference), Reference.MANUAL_SCHEDULE_WAKE,
                ),
            )
            assertEquals(expected.toRawBits(), row.beyondReportedEndSeconds.toRawBits(), "$reported → $reference: ${row.beyondReportedEndSeconds}")
        }
        assertEquals(4_667_163_160_187_240_448L, 10_800.333000183105.toRawBits(), "the bits upstream printed")
    }
}
