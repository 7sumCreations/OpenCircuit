package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export's `coverageFraction` witness — see `ExportCoverageWitness` for the measured defect these
 * cover (the persisted store rows are the forward-only sync cursor's output, not the record set
 * staging runs on, so a single live sample stranded 62 real epochs and the file published
 * `0.7333 / 4 gaps` about a window the ring had recorded end to end).
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ExportCoverageWitnessTests.swift
 * (@ b1c2fdd) — all 16 tests.
 */
class ExportCoverageWitnessTest {

    // MARK: - Record construction

    /**
     * A worn `0x4c` epoch with a real HR byte. Built from raw bytes rather than a synthetic
     * convenience constructor so the record goes through the SAME layout / `heartRate` accessors
     * production reads (a hand-made "record" that skips the real decode path makes a test pass by
     * construction).
     */
    private fun wornRecord(at: Instant, hr: Int = 58): BulkRecord {
        val raw = counterBytes(at)
        raw[4] = hr.toByte() // HR — `[4]` on any worn epoch
        raw[8] = 0x60 // 96 % SpO2 → sleep vitals, not the activity sentinel
        raw[9] = 0x0a
        for (i in 10 until 15) raw[i] = 2 // motion, deliberately NOT the 01×5 idle template
        return BulkRecord.of(raw)!!
    }

    /** The unworn/charging template — a record that exists but measures nothing. */
    private fun idleRecord(at: Instant): BulkRecord {
        val raw = counterBytes(at)
        raw[4] = 0x05; raw[5] = 0x00; raw[6] = 0x0c; raw[7] = 0x00
        raw[9] = 0x0a
        for (i in 10 until 15) raw[i] = 1
        return BulkRecord.of(raw)!!
    }

    /** Upstream's `UInt32(Int(date.timeIntervalSince1970) - Command.syncEpoch)`, big-endian in `[0:4]`. */
    private fun counterBytes(at: Instant): ByteArray {
        val counter = at.epochSecond - Command.SYNC_EPOCH
        require(counter in 0..0xFFFF_FFFFL) { "a UInt32 counter: $counter" }
        val raw = ByteArray(BulkRecord.LENGTH)
        raw[0] = (counter ushr 24).toByte(); raw[1] = (counter ushr 16).toByte()
        raw[2] = (counter ushr 8).toByte(); raw[3] = counter.toByte()
        return raw
    }

    private val start: Instant = Instant.ofEpochSecond(1_755_000_000)
    private val end: Instant get() = start.plusSeconds(3 * 3600)

    private fun epochs(count: Int, from: Long = 0): List<BulkRecord> =
        (0 until count).map { wornRecord(start.plusSeconds(from + it * 150L)) }

    // MARK: - The defect

    /**
     * THE MEASURED DEFECT, in miniature. The ring recorded the whole window; the store holds only the
     * tail because a live sample pushed the forward-only cursor past the rest. The store-only witness
     * reports a large hole that never happened; the union reports none.
     */
    @Test
    fun archiveEpochsRescueACoverageHoleTheForwardOnlyCursorInvented() {
        val archive = epochs(72) // 72 × 150 s = the full 3 h window
        val strandedTail = archive.takeLast(20).map { it.date() }

        val storeOnly = ExportCoverage.assess(strandedTail, from = start, to = end)
        assertEquals(1, storeOnly.gaps.size)
        assertTrue(storeOnly.longestGapSeconds > 7000)
        assertTrue(storeOnly.coverageFraction < 0.3)

        val witness = ExportCoverageWitness.sampleTimes(archives = listOf(archive), storedHeartRateTimes = strandedTail, from = start, to = end)
        val union = ExportCoverage.assess(witness, from = start, to = end)
        assertEquals(0, union.gaps.size, "the ring recorded every epoch of this window")
        assertEquals(1.0, union.coverageFraction, 0.0001)
    }

    /**
     * THE OTHER HALF, and the reason this is a UNION and not a switch to the archive. The archive is a
     * ~30 h rolling buffer; a night it has aged out of must fall back to the store, NOT be reported as
     * a night-long hole.
     */
    @Test
    fun anArchiveThatHasAgedPastTheNightLeavesTheStoreWitnessAlone() {
        // Archive holds only ground a full day AFTER the night being exported.
        val staleArchive = (0 until 72).map { wornRecord(start.plusSeconds(86_400 + it * 150L)) }
        val storeTimes = epochs(72).map { it.date() }

        val witness = ExportCoverageWitness.sampleTimes(archives = listOf(staleArchive), storedHeartRateTimes = storeTimes, from = start, to = end)
        val union = ExportCoverage.assess(witness, from = start, to = end)
        val storeOnly = ExportCoverage.assess(storeTimes, from = start, to = end)
        assertEquals(storeOnly.coverageFraction, union.coverageFraction)
        assertEquals(storeOnly.gaps.size, union.gaps.size)
        assertEquals(storeOnly.observedSamples, union.observedSamples)
    }

    // MARK: - Invariants

    /**
     * MONOTONICITY. The union can only ever ADD instants we genuinely hold, so no night whose coverage
     * hole is REAL can have it papered over. Checked against a real hole — the archive itself is
     * missing the middle hour.
     */
    @Test
    fun aGenuineRecordingHoleSurvivesTheUnion() {
        val firstHour = epochs(24) // 00:00 → 01:00
        val lastHour = epochs(24, from = 2 * 3600) // 02:00 → 03:00
        val archive = firstHour + lastHour
        val storeTimes = lastHour.map { it.date() }

        val witness = ExportCoverageWitness.sampleTimes(archives = listOf(archive), storedHeartRateTimes = storeTimes, from = start, to = end)
        val union = ExportCoverage.assess(witness, from = start, to = end)
        val storeOnly = ExportCoverage.assess(storeTimes, from = start, to = end)

        assertEquals(1, union.gaps.size, "the middle hour is a real hole in the record stream")
        assertEquals(3600.0, union.longestGapSeconds, 150.0)
        // …and it is still an improvement on the store-only view, never a regression.
        assertTrue(union.coverageFraction > storeOnly.coverageFraction)
        assertTrue(union.longestGapSeconds <= storeOnly.longestGapSeconds)
    }

    /** An idle (unworn/charging) record is not a measurement and must not count as coverage. */
    @Test
    fun idleRecordsAreNotCoverage() {
        val idle = (0 until 72).map { idleRecord(start.plusSeconds(it * 150L)) }
        val witness = ExportCoverageWitness.sampleTimes(archives = listOf(idle), storedHeartRateTimes = emptyList(), from = start, to = end)
        assertTrue(witness.isEmpty())
        assertEquals(0.0, ExportCoverage.assess(witness, from = start, to = end).coverageFraction)
    }

    /**
     * TWO RINGS ARE NOT ONE TIMELINE. The witness picks the archive that actually recorded this night
     * instead of unioning them into a coverage number neither ring earned.
     */
    @Test
    fun twoRingArchivesAreNotMergedIntoOneWitness() {
        val wornRing = epochs(72)
        // The other ring contributed only 12 (worn) epochs to the same window — "recorded less", not
        // "was on a charger". The witness must pick the archive that actually covers the night.
        val otherRing = epochs(12)
        val witness = ExportCoverageWitness.sampleTimes(archives = listOf(otherRing, wornRing), storedHeartRateTimes = emptyList(), from = start, to = end)
        assertEquals(72, witness.size)
        assertEquals(wornRing.map { it.date() }.toSet(), witness.toSet())
    }

    /**
     * Records outside the window are ignored rather than counted, and a degenerate window returns the
     * store witness untouched.
     */
    @Test
    fun outOfWindowRecordsAndDegenerateWindows() {
        val before = (1..10).map { wornRecord(start.minusSeconds(it * 150L)) }
        val inside = epochs(5)
        val witness = ExportCoverageWitness.sampleTimes(archives = listOf(before + inside), storedHeartRateTimes = emptyList(), from = start, to = end)
        assertEquals(5, witness.size)

        val stored = listOf(start)
        assertEquals(stored, ExportCoverageWitness.sampleTimes(archives = listOf(inside), storedHeartRateTimes = stored, from = end, to = start))
    }

    // MARK: - Edge probes

    /**
     * THE TESTER'S NIGHT, IN MINIATURE. The ring recorded continuously INTO the bedtime; the persisted
     * heart-rate rows stop 6641 s earlier because the forward-only cursor stranded everything after a
     * late-stamped live sample. Store-only calls that a resumed-after-gap(6641); the union sees the
     * epochs and calls it witnessed.
     */
    @Test
    fun aStrandedCursorNoLongerManufacturesABedtimeGap() {
        val bedtime = start
        // 44 epochs running right up to the bedtime — the last one 150 s before it.
        val archive = (1..44).map { wornRecord(bedtime.minusSeconds(it * 150L)) }
        val strandedStoreRow = bedtime.minusSeconds(6641)

        val storeOnly = BedtimeProvenance.classify(
            inBedStart = bedtime,
            lastMeasurementBefore = strandedStoreRow,
            earliestRetainedMeasurement = bedtime.minusSeconds(30 * 86_400L),
        )
        assertEquals(BedtimeProvenance.Verdict.ResumedAfterGap(6641.0), storeOnly)

        val edges = ExportCoverageWitness.edges(
            archives = listOf(archive),
            storedLastBeforeStart = strandedStoreRow,
            storedFirstAfterEnd = null,
            storedEarliestRetained = bedtime.minusSeconds(30 * 86_400L),
            inBedStart = bedtime, inBedEnd = end,
        )
        assertEquals(
            BedtimeProvenance.Verdict.Witnessed,
            BedtimeProvenance.classify(
                inBedStart = edges.inBedStart,
                lastMeasurementBefore = edges.lastMeasurementBeforeStart,
                earliestRetainedMeasurement = edges.earliestRetainedMeasurement,
            ),
        )
        assertTrue(edges.archiveMovedAnEdge)
        assertEquals("store+archive(44,moved)", edges.witnessDescription)
    }

    /** The trailing edge, same shape: stopped-then-resumed becomes witnessed. */
    @Test
    fun theWakeEdgeIsAlsoRescuedByTheArchive() {
        val archive = (1..10).map { wornRecord(end.plusSeconds(it * 150L)) }
        val lateStoreRow = end.plusSeconds(4 * 3600)

        assertEquals(
            WakeProvenance.Verdict.StoppedThenResumed(4.0 * 3600),
            WakeProvenance.classify(inBedEnd = end, firstMeasurementAfter = lateStoreRow, earliestRetainedMeasurement = start),
        )

        val edges = ExportCoverageWitness.edges(
            archives = listOf(archive),
            storedLastBeforeStart = null,
            storedFirstAfterEnd = lateStoreRow,
            storedEarliestRetained = start,
            inBedStart = start, inBedEnd = end,
        )
        assertEquals(
            WakeProvenance.Verdict.Witnessed,
            WakeProvenance.classify(
                inBedEnd = edges.inBedEnd,
                firstMeasurementAfter = edges.firstMeasurementAfterEnd,
                earliestRetainedMeasurement = edges.earliestRetainedMeasurement,
            ),
        )
    }

    /**
     * MONOTONICITY AT AN EDGE. A hole the archive ALSO has stays reported at its full width. (The
     * archive here starts 4 h before the bedtime and then stops 2 h before it.)
     */
    @Test
    fun aRealEdgeGapSurvivesTheUnionAtItsFullWidth() {
        val bedtime = start
        val archive = (0 until 48).map { wornRecord(bedtime.minusSeconds(4 * 3600L).plusSeconds(it * 150L)) }
        val edges = ExportCoverageWitness.edges(
            archives = listOf(archive),
            storedLastBeforeStart = null,
            storedFirstAfterEnd = null,
            storedEarliestRetained = null,
            inBedStart = bedtime, inBedEnd = end,
        )
        val verdict = BedtimeProvenance.classify(
            inBedStart = edges.inBedStart,
            lastMeasurementBefore = edges.lastMeasurementBeforeStart,
            earliestRetainedMeasurement = edges.earliestRetainedMeasurement,
        )
        assertIs<BedtimeProvenance.Verdict.ResumedAfterGap>(verdict, "a two-hour hole before the bedtime must still be reported")
        // Last archive epoch sits at bedtime − 4 h + 47 × 150 s = bedtime − 7350 s.
        assertEquals(7350.0, verdict.seconds, 1.0)
    }

    /**
     * THE RETENTION HORIZON IS NOT A RECORDING GAP. A night five days old is far outside the archive's
     * ~30 h, so the widened window never reaches it. Store answers stand untouched.
     */
    @Test
    fun anArchiveOutOfReachOfTheNightLeavesTheStoreAnswersAlone() {
        val nightStart = start.minusSeconds(5 * 86_400L)
        val nightEnd = nightStart.plusSeconds(8 * 3600)
        // Archive = the last 30 h, i.e. days AFTER this night.
        val archive = (0 until 720).map { wornRecord(start.plusSeconds(it * 150L)) }

        val storedBefore = nightStart.minusSeconds(200)
        val storedAfter = nightEnd.plusSeconds(200)
        val storedEarliest = nightStart.minusSeconds(10 * 86_400L)
        val edges = ExportCoverageWitness.edges(
            archives = listOf(archive),
            storedLastBeforeStart = storedBefore,
            storedFirstAfterEnd = storedAfter,
            storedEarliestRetained = storedEarliest,
            inBedStart = nightStart, inBedEnd = nightEnd,
        )

        assertEquals(0L, edges.archiveEpochsInReach)
        assertFalse(edges.archiveMovedAnEdge)
        assertEquals("store", edges.witnessDescription)
        assertEquals(storedBefore, edges.lastMeasurementBeforeStart)
        assertEquals(storedAfter, edges.firstMeasurementAfterEnd)
        assertEquals(storedEarliest, edges.earliestRetainedMeasurement)
    }

    /**
     * THE RETENTION GUARD STILL FIRES. Store is empty and the archive holds only ground AFTER the
     * night's trailing edge — "the ring stopped" is indistinguishable from "our data does not reach".
     * Unknown, silent; the union must not convert it into a loud multi-hour "recording gap".
     */
    @Test
    fun unioningEarliestRetainedDoesNotDisarmTheRetentionGuard() {
        val nightEnd = start
        val archive = (1..20).map { wornRecord(nightEnd.plusSeconds(3600 + it * 150L)) }
        val edges = ExportCoverageWitness.edges(
            archives = listOf(archive),
            storedLastBeforeStart = null,
            storedFirstAfterEnd = null,
            storedEarliestRetained = null,
            inBedStart = nightEnd.minusSeconds(3 * 3600), inBedEnd = nightEnd,
        )

        assertNotNull(edges.firstMeasurementAfterEnd, "the archive does hold later records")
        assertEquals(
            WakeProvenance.Verdict.Unknown,
            WakeProvenance.classify(
                inBedEnd = edges.inBedEnd,
                firstMeasurementAfter = edges.firstMeasurementAfterEnd,
                earliestRetainedMeasurement = edges.earliestRetainedMeasurement,
            ),
        )
    }

    /** An idle archive is not a measurement here either — a ring asleep in its case must not witness a bedtime. */
    @Test
    fun idleArchiveRecordsCannotWitnessAnEdge() {
        val bedtime = start
        val idle = (1..20).map { idleRecord(bedtime.minusSeconds(it * 150L)) }
        val edges = ExportCoverageWitness.edges(
            archives = listOf(idle),
            storedLastBeforeStart = null, storedFirstAfterEnd = null, storedEarliestRetained = null,
            inBedStart = bedtime, inBedEnd = end,
        )
        assertEquals(0L, edges.archiveEpochsInReach)
        assertNull(edges.lastMeasurementBeforeStart)
        assertEquals("store", edges.witnessDescription)
    }

    /** Two rings are still not one timeline: the edge probe uses the SAME per-ring tie-break as `sampleTimes`. */
    @Test
    fun edgeProbeKeepsTheRingsSeparate() {
        val bedtime = start
        val wornRing = (1..40).map { wornRecord(bedtime.minusSeconds(it * 150L)) }
        // A DIFFERENT ring, worn much closer to the bedtime but with almost no history.
        val otherRing = listOf(wornRecord(bedtime.minusSeconds(10)))
        val edges = ExportCoverageWitness.edges(
            archives = listOf(otherRing, wornRing),
            storedLastBeforeStart = null, storedFirstAfterEnd = null, storedEarliestRetained = null,
            inBedStart = bedtime, inBedEnd = end,
        )
        assertEquals(40L, edges.archiveEpochsInReach)
        assertEquals(bedtime.minusSeconds(150), edges.lastMeasurementBeforeStart, "the winning ring's own newest epoch, not the other ring's")
    }

    /** A caller with no wake time passes the same instant for both edges; that must still probe a real window. */
    @Test
    fun degenerateWindowStillProbesTheLeadingEdge() {
        val bedtime = start
        val archive = (1..4).map { wornRecord(bedtime.minusSeconds(it * 150L)) }
        val edges = ExportCoverageWitness.edges(
            archives = listOf(archive),
            storedLastBeforeStart = null, storedFirstAfterEnd = null, storedEarliestRetained = null,
            inBedStart = bedtime, inBedEnd = bedtime,
        )
        assertEquals(bedtime.minusSeconds(150), edges.lastMeasurementBeforeStart)
    }

    /**
     * The widening is the ARCHIVE'S OWN retention horizon, not a number invented here — so an archive
     * record exactly one horizon before the bedtime is still in reach, and one beyond it is not.
     */
    @Test
    fun theWideningIsTheArchiveRetentionHorizon() {
        val bedtime = start
        val atHorizon = wornRecord(bedtime.minus(EpochArchive.RETENTION))
        val pastHorizon = wornRecord(bedtime.minus(EpochArchive.RETENTION).minusSeconds(150))

        assertEquals(
            1L,
            ExportCoverageWitness.edges(
                archives = listOf(listOf(atHorizon)),
                storedLastBeforeStart = null, storedFirstAfterEnd = null, storedEarliestRetained = null,
                inBedStart = bedtime, inBedEnd = bedtime,
            ).archiveEpochsInReach,
        )

        assertEquals(
            0L,
            ExportCoverageWitness.edges(
                archives = listOf(listOf(pastHorizon)),
                storedLastBeforeStart = null, storedFirstAfterEnd = null, storedEarliestRetained = null,
                inBedStart = bedtime, inBedEnd = bedtime,
            ).archiveEpochsInReach,
        )
    }

    /**
     * No archive at all ⇒ identical to the store-only behaviour these three probes shipped with. The
     * degeneration is VISIBLE (`witnessDescription == "store"`): an empty archive used to be
     * indistinguishable from a covered night.
     */
    @Test
    fun withNoArchiveTheProbeIsExactlyTheOldStoreOnlyBehaviour() {
        val before = start.minusSeconds(6641)
        val after = end.plusSeconds(900)
        val earliest = start.minusSeconds(86_400)
        val edges = ExportCoverageWitness.edges(
            archives = emptyList(),
            storedLastBeforeStart = before,
            storedFirstAfterEnd = after,
            storedEarliestRetained = earliest,
            inBedStart = start, inBedEnd = end,
        )
        assertEquals(before, edges.lastMeasurementBeforeStart)
        assertEquals(after, edges.firstMeasurementAfterEnd)
        assertEquals(earliest, edges.earliestRetainedMeasurement)
        assertFalse(edges.archiveMovedAnEdge)
        assertEquals("store", edges.witnessDescription)
        assertEquals(
            SleepConfidence.Coverage(
                inBedStart = start, inBedEnd = end,
                lastMeasurementBeforeStart = before,
                firstMeasurementAfterEnd = after,
                measurementsAfterEnd = listOf(after),
                earliestRetainedMeasurement = earliest,
            ),
            edges.coverage,
        )
    }
}
