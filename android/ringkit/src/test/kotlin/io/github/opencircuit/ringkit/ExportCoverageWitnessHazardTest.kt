package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

/**
 * Kotlin-only checks of the coverage witness where a port could quietly differ from Swift: the set
 * that dedupes and orders the instants after the night, `max` / `min` over optional candidates, the
 * inclusive and strict bounds at the edge instant, the per-ring tie-break, a hostile widening, and
 * lists handed back that Swift would have copied. Kept out of the upstream-port class so its count
 * stays exact.
 *
 * Every expected value was printed by upstream's own `ExportCoverageWitness` at the pin, built with
 * Swift 6.3.2 on macOS 26, for the same records. Instants are written as seconds from [s] (the
 * night's start); [e] is three hours later.
 */
class ExportCoverageWitnessHazardTest {

    private val s0 = 1_755_000_000L
    private val s: Instant = Instant.ofEpochSecond(s0)
    private val e: Instant = s.plusSeconds(3 * 3600)
    private val retention = EpochArchive.RETENTION.seconds

    private fun at(offset: Long): Instant = s.plusSeconds(offset)

    /** A worn epoch with a heart rate, on the raw path (counter big-endian, HR in `[4]`). */
    private fun worn(offset: Long): BulkRecord {
        val counter = s0 + offset - Command.SYNC_EPOCH
        val raw = ByteArray(BulkRecord.LENGTH)
        raw[0] = (counter ushr 24).toByte(); raw[1] = (counter ushr 16).toByte()
        raw[2] = (counter ushr 8).toByte(); raw[3] = counter.toByte()
        raw[4] = 58; raw[8] = 0x60; raw[9] = 0x0a
        for (i in 10 until 15) raw[i] = 2
        return BulkRecord.of(raw)!!
    }

    private fun rel(t: Instant?): Double? = t?.let { secondsBetween(s, it) }

    /** The probe's printed line, in the same shape, so each case reads as the measurement it repeats. */
    private fun line(x: ExportCoverageWitness.Edges): String =
        "last=${rel(x.lastMeasurementBeforeStart)} first=${rel(x.firstMeasurementAfterEnd)} " +
            "after=${x.measurementsAfterEnd.map { rel(it) }} earliest=${rel(x.earliestRetainedMeasurement)} " +
            "reach=${x.archiveEpochsInReach} moved=${x.archiveMovedAnEdge} desc=${x.witnessDescription}"

    private fun edges(
        archives: List<List<BulkRecord>>,
        last: Instant? = null,
        first: Instant? = null,
        earliest: Instant? = null,
        start: Instant = s,
        end: Instant = e,
        widening: Double = retention.toDouble(),
    ) = ExportCoverageWitness.edges(archives, last, first, earliest, start, end, widening)

    @Test
    fun theInstantsAfterTheNightAreDedupedAndAscendingWhateverTheArchiveOrder() {
        // Unsorted archive with one record twice, and a store row equal to an archive epoch.
        assertEquals(
            "last=null first=10950.0 after=[10950.0, 11100.0, 11250.0] earliest=10950.0 reach=4 moved=true desc=store+archive(4,moved)",
            line(edges(listOf(listOf(worn(11250), worn(10950), worn(11100), worn(10950))), first = at(10950))),
            "a duplicated epoch is one instant after the night but still counts twice in reach",
        )
        assertEquals(
            "last=null first=10950.5 after=[10950.5, 11100.0] earliest=11100.0 reach=1 moved=true desc=store+archive(1,moved)",
            line(edges(listOf(listOf(worn(11100))), first = at(10950).plusMillis(500))),
        )
    }

    @Test
    fun theStoresFirstAfterEndCompetesUnfilteredButOnlyJoinsTheRunWhenAfterTheEdge() {
        // Upstream takes `min` over the store answer as given, but filters it `> inBedEnd` for the run.
        assertEquals(
            "last=null first=10800.0 after=[11100.0] earliest=11100.0 reach=1 moved=true desc=store+archive(1,moved)",
            line(edges(listOf(listOf(worn(11100))), first = e)),
        )
        assertEquals(
            "last=null first=10790.0 after=[11100.0] earliest=11100.0 reach=1 moved=true desc=store+archive(1,moved)",
            line(edges(listOf(listOf(worn(11100))), first = e.minusSeconds(10))),
        )
    }

    @Test
    fun maxAndMinOverOptionalCandidatesNeverLetANilWinAndAnEqualAnswerIsNotAMove() {
        assertEquals("last=null first=null after=[] earliest=null reach=0 moved=false desc=store", line(edges(emptyList())))
        assertEquals(
            "last=-100.0 first=null after=[] earliest=-600.0 reach=1 moved=true desc=store+archive(1,moved)",
            line(edges(listOf(listOf(worn(-600))), last = at(-100))),
            "the store's closer answer stands; the earliest moves",
        )
        assertEquals(
            "last=-600.0 first=null after=[] earliest=-86400.0 reach=1 moved=true desc=store+archive(1,moved)",
            line(edges(listOf(listOf(worn(-600))), earliest = at(-86_400))),
        )
        assertEquals(
            "last=-600.0 first=null after=[] earliest=-600.0 reach=1 moved=false desc=store+archive(1)",
            line(edges(listOf(listOf(worn(-600))), last = at(-600), earliest = at(-600))),
            "an archive answer equal to the store's moves nothing",
        )
    }

    @Test
    fun theSampleWindowIsInclusiveAtBothEdgesAndADegenerateOneReturnsTheStoreAlone() {
        val bounds = ExportCoverageWitness.sampleTimes(listOf(listOf(worn(0), worn(10_800), worn(-1), worn(10_801))), emptyList(), s, e)
        assertEquals(listOf(0.0, 10_800.0), bounds.map { rel(it) })

        val equal = ExportCoverageWitness.sampleTimes(listOf(listOf(worn(0))), listOf(at(7)), s, s)
        assertEquals(listOf(7.0), equal.map { rel(it) }, "from == to: the archive record at that instant is not consulted")
        assertEquals(emptyList(), ExportCoverageWitness.sampleTimes(listOf(listOf(worn(0))), emptyList(), e, s))

        // Archive instants first, in the archive's own order, then the store's — neither sorted nor deduped.
        val order = ExportCoverageWitness.sampleTimes(listOf(listOf(worn(300), worn(150))), listOf(at(900), at(150)), s, e)
        assertEquals(listOf(300.0, 150.0, 900.0, 150.0), order.map { rel(it) })
    }

    @Test
    fun anEpochOnAnEdgeIsInReachButIsNeitherBeforeTheStartNorAfterTheEnd() {
        assertEquals("last=null first=null after=[] earliest=0.0 reach=1 moved=true desc=store+archive(1,moved)", line(edges(listOf(listOf(worn(0))))))
        assertEquals("last=null first=null after=[] earliest=10800.0 reach=1 moved=true desc=store+archive(1,moved)", line(edges(listOf(listOf(worn(10_800))))))
        assertEquals(
            "last=null first=null after=[] earliest=0.0 reach=1 moved=true desc=store+archive(1,moved)",
            line(edges(listOf(listOf(worn(0))), start = s, end = s)),
        )
        // The widened window is inclusive at its far edge too.
        assertEquals(
            "last=null first=118800.0 after=[118800.0] earliest=118800.0 reach=1 moved=true desc=store+archive(1,moved)",
            line(edges(listOf(listOf(worn(10_800 + retention))))),
        )
        assertEquals("last=null first=null after=[] earliest=null reach=0 moved=false desc=store", line(edges(listOf(listOf(worn(10_800 + retention + 1))))))
        // An inverted window is normalised for reach; before / after still use the edges as given.
        assertEquals(
            "last=-150.0 first=10950.0 after=[10950.0] earliest=-150.0 reach=2 moved=true desc=store+archive(2,moved)",
            line(edges(listOf(listOf(worn(-150), worn(10_950))), start = e, end = s)),
        )
    }

    @Test
    fun ringsTieToTheFirstAndCountOnlyInWindowEpochs() {
        assertEquals("last=-300.0 first=null after=[] earliest=-300.0 reach=1 moved=true desc=store+archive(1,moved)", line(edges(listOf(listOf(worn(-300)), listOf(worn(-150))))))
        assertEquals("last=-150.0 first=null after=[] earliest=-150.0 reach=1 moved=true desc=store+archive(1,moved)", line(edges(listOf(listOf(worn(-150)), listOf(worn(-300))))))
        val bulkOutOfReach = listOf(worn(-300)) + (1..50L).map { worn(-retention - 1000 - it * 150) }
        assertEquals(
            "last=-150.0 first=null after=[] earliest=-450.0 reach=2 moved=true desc=store+archive(2,moved)",
            line(edges(listOf(bulkOutOfReach, listOf(worn(-150), worn(-450))))),
            "the ring with more epochs IN the window wins, not the longer archive",
        )
        assertEquals(listOf(300.0), ExportCoverageWitness.sampleTimes(listOf(listOf(worn(300)), listOf(worn(150))), emptyList(), s, e).map { rel(it) })
    }

    /**
     * A NaN widening makes both bounds a NaN `Date`, and Swift's `>=` / `<=` on `Date` are the
     * `Comparable` defaults `!(a < b)` — TRUE against NaN (measured) — so every record is in reach.
     * −∞ empties the window; +∞ and 1e300 take every record; a negative widening shrinks the night.
     */
    @Test
    fun aHostileWideningBehavesAsUpstreamsDateArithmetic() {
        val archive = listOf(listOf(worn(-150), worn(3600), worn(7200), worn(10_950), worn(-864_000)))
        val all = "last=-150.0 first=10950.0 after=[10950.0] earliest=-864000.0 reach=5 moved=true desc=store+archive(5,moved)"
        val none = "last=null first=null after=[] earliest=null reach=0 moved=false desc=store"
        val night = "last=null first=null after=[] earliest=3600.0 reach=2 moved=true desc=store+archive(2,moved)"
        val expected = listOf(
            Double.NaN to all, Double.POSITIVE_INFINITY to all, Double.NEGATIVE_INFINITY to none, 0.0 to night,
            -3600.0 to night, -5401.0 to none, 1e300 to all, 0.5 to night,
        )
        for ((w, want) in expected) assertEquals(want, line(edges(archive, widening = w)), "widening $w")
    }

    /** Swift hands back a copy of the store array; the Kotlin result must not be the caller's list or writable. */
    @Test
    fun theReturnedWitnessIsACopyAndReadOnly() {
        val stored = mutableListOf(at(7))
        val degenerate = ExportCoverageWitness.sampleTimes(emptyList(), stored, s, s)
        assertNotSame<List<Instant>>(stored, degenerate)
        stored += at(8)
        assertEquals(listOf(at(7)), degenerate, "a later change to the caller's list does not reach the result")
        assertFailsWith<UnsupportedOperationException> { (degenerate as MutableList<Instant>).add(at(9)) }

        val archive = mutableListOf(worn(150))
        val archives = mutableListOf<List<BulkRecord>>(archive)
        val witness = ExportCoverageWitness.sampleTimes(archives, emptyList(), s, e)
        archive += worn(300)
        archives += listOf(worn(450))
        assertEquals(listOf(at(150)), witness)
        assertFailsWith<UnsupportedOperationException> { (witness as MutableList<Instant>).add(at(9)) }
    }
}
