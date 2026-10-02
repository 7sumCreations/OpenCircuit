package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Kotlin-only hostile-input checks for the provenance re-derivation: reversed, zero-length and
 * overlapping asserted segments, an empty night and an empty archive, a trusted archive with ground
 * it cannot speak about, segments sharing only an edge with the records, input order, a far-future
 * night, and a "before" that is better covered than the "after". Kept out of the upstream-port class
 * so its count stays exact. Every expected value was measured on upstream's pinned Swift build.
 */
class SleepProvenanceRederivationHazardTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun at(m: Long): Instant = t0.plusSeconds(m * 60)
    private fun seg(a: Long, b: Long, st: SleepStage, p: SleepProvenance = SleepProvenance.MEASURED) = SleepSegment(at(a), at(b), st, p)

    private val core = SleepStage.ASLEEP_CORE
    private val asserted = SleepProvenance.ASSERTED
    private val over = SleepProvenance.ASSERTED_OVER_MEASURED

    /** Records over minutes 10–20 and 40–50. */
    private val cov = MeasuredCoverage(listOf(DateInterval(at(10), at(20)), DateInterval(at(40), at(50))))

    @Test
    fun segmentsThatCoverNoTimeAreLeftAlone() {
        assertNull(SleepProvenanceRederivation.upgraded(listOf(seg(60, 0, core, asserted)), cov), "reversed")
        assertNull(SleepProvenanceRederivation.upgraded(listOf(seg(15, 15, core, asserted)), cov), "zero-length")
        assertNull(SleepProvenanceRederivation.upgraded(emptyList(), cov), "no segments")
        assertNull(SleepProvenanceRederivation.upgraded(listOf(seg(0, 30, core, asserted)), MeasuredCoverage.EMPTY), "no records")
        assertNull(
            SleepProvenanceRederivation.upgraded(listOf(seg(0, 10, core, asserted), seg(20, 40, core, asserted)), cov),
            "records that only touch a hole's edge are not under it",
        )
    }

    @Test
    fun overlappingAssertedSegmentsAreEachSplitOnTheirOwn() {
        val out = assertNotNull(SleepProvenanceRederivation.upgraded(listOf(seg(0, 30, core, asserted), seg(15, 45, SleepStage.AWAKE, asserted)), cov))
        assertEquals(
            listOf(
                seg(0, 10, core, asserted), seg(10, 20, core, over), seg(20, 30, core, asserted),
                seg(15, 20, SleepStage.AWAKE, over), seg(20, 40, SleepStage.AWAKE, asserted), seg(40, 45, SleepStage.AWAKE, over),
            ),
            out,
        )
    }

    @Test
    fun groundATrustedArchiveCannotSpeakAboutKeepsItsLabel() {
        val trusted = assertNotNull(MeasuredCoverage(listOf(DateInterval(at(10), at(20)))).trusted(DateInterval(at(0), at(30))))
        assertEquals(
            listOf(seg(0, 10, core, asserted), seg(10, 20, core, over), seg(20, 30, core, asserted)),
            SleepProvenanceRederivation.upgraded(listOf(seg(0, 30, core, asserted)), trusted),
        )
    }

    @Test
    fun inputOrderIsKeptAndOnlyAssertedSpansAreSplit() {
        val input = listOf(seg(40, 60, SleepStage.ASLEEP_REM, asserted), seg(0, 5, SleepStage.IN_BED), seg(0, 30, SleepStage.IN_BED, asserted))
        assertEquals(
            listOf(
                seg(40, 50, SleepStage.ASLEEP_REM, over), seg(50, 60, SleepStage.ASLEEP_REM, asserted),
                seg(0, 5, SleepStage.IN_BED),
                seg(0, 10, SleepStage.IN_BED, asserted), seg(10, 20, SleepStage.IN_BED, over), seg(20, 30, SleepStage.IN_BED, asserted),
            ),
            SleepProvenanceRederivation.upgraded(input, cov),
        )
        assertEquals(listOf(seg(12, 18, SleepStage.ASLEEP_DEEP, over)), SleepProvenanceRederivation.upgraded(listOf(seg(12, 18, SleepStage.ASLEEP_DEEP, asserted)), cov))
    }

    @Test
    fun aFarFutureNightAndANegativeUpgradeAsUpstream() {
        val base = Instant.ofEpochSecond(30_000_000_000)
        val out = SleepProvenanceRederivation.upgraded(
            listOf(SleepSegment(base, base.plusSeconds(3600), core, asserted)),
            MeasuredCoverage.ofRecordDates(listOf(base.plusSeconds(600)), java.time.Duration.ofSeconds(150)),
        )
        assertEquals(
            listOf(
                SleepSegment(base, base.plusSeconds(600), core, asserted),
                SleepSegment(base.plusSeconds(600), base.plusSeconds(750), core, over),
                SleepSegment(base.plusSeconds(750), base.plusSeconds(3600), core, asserted),
            ),
            out,
        )
        // An "after" with MORE asserted sleep than the "before" moves nothing out of the bucket.
        assertEquals(0.0, SleepProvenanceRederivation.upgradedAsleepSeconds(before = listOf(seg(0, 10, core)), after = listOf(seg(0, 10, core, asserted))))
    }
}
