package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Does the union of a diagnostics export's per-drain raw-record blobs cover the epochs the app
 * HOLDS? Upstream's measured case: a 35-minute "hole" that was in the diagnostic, not the data.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ArchiveEvidenceCoverageTests.swift
 * (@ b1c2fdd), all 7 tests.
 */
class ArchiveEvidenceCoverageTest {

    private val step = 150L
    private val base = 0x0c220000L

    /** :22-29 — a sleep-vitals-shaped record with a given counter, built on the raw byte path. */
    private fun rec(counter: Long): BulkRecord {
        val b = ByteArray(23)
        b[0] = ((counter ushr 24) and 0xFF).toByte()
        b[1] = ((counter ushr 16) and 0xFF).toByte()
        b[2] = ((counter ushr 8) and 0xFF).toByte()
        b[3] = (counter and 0xFF).toByte()
        b[4] = 55
        b[5] = 40
        b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = 1
        return BulkRecord.of(b)!!
    }

    // :31
    private fun run(n: Int, from: Long): List<BulkRecord> = (0 until n).map { rec(from + it * step) }

    // :35
    @Test
    fun completeCoverageReportsNoGap() {
        val archive = run(20, base)
        val report = ArchiveEvidenceCoverage.report(archive = archive, evidence = archive)
        assertTrue(report.isComplete)
        assertEquals(20, report.archiveRecordCount)
        assertEquals(20, report.evidenceRecordCount)
        assertEquals(0, report.longestMissingRunSeconds)
    }

    // :45 — the tester's shape: a contiguous archive whose middle stretch is in no blob.
    @Test
    fun missingMiddleStretchIsReportedWithItsSpan() {
        val archive = run(30, base)
        val evidence = archive.take(10) + archive.takeLast(7)
        val report = ArchiveEvidenceCoverage.report(archive = archive, evidence = evidence)
        assertFalse(report.isComplete)
        assertEquals(13, report.missingFromEvidence.size, "13 epochs, exactly the measured case")
        assertEquals(13 * 150, report.longestMissingRunSeconds, "a blob-only replay would see a hole this wide")
        assertEquals(30, report.archiveRecordCount)
        assertEquals(17, report.evidenceRecordCount)
    }

    // :60 — overlapping blobs are the norm, so both sides dedup by epoch counter.
    @Test
    fun duplicateRecordsAreDedupedOnBothSides() {
        val archive = run(10, base)
        val evidence = archive + archive + archive
        val report = ArchiveEvidenceCoverage.report(archive = archive + archive, evidence = evidence)
        assertEquals(10, report.archiveRecordCount)
        assertEquals(10, report.evidenceRecordCount)
        assertTrue(report.isComplete)
    }

    // :71 — cadence jitter (152 s intervals on Gen 3) must not split a missing run.
    @Test
    fun cadenceJitterDoesNotSplitAMissingRun() {
        val counters = mutableListOf(base)
        for (i in 1 until 12) counters += base + i * step + (i % 3)
        val archive = counters.map { rec(it) }
        val report = ArchiveEvidenceCoverage.report(archive = archive, evidence = emptyList())
        assertEquals(12, report.missingFromEvidence.size)
        assertTrue(report.longestMissingRunSeconds >= 11 * 150)
    }

    // :81 — two separate holes report the LONGER one, not their sum.
    @Test
    fun separateHolesReportTheLongestRun() {
        val archive = run(40, base)
        val evidence = archive.toMutableList()
        evidence.subList(30, 34).clear() // 4 epochs
        evidence.subList(5, 7).clear() // 2 epochs
        val report = ArchiveEvidenceCoverage.report(archive = archive, evidence = evidence)
        assertEquals(6, report.missingFromEvidence.size)
        assertEquals(4 * 150, report.longestMissingRunSeconds)
    }

    // :93 — blobs that OVERSHOOT the archive are not a gap.
    @Test
    fun evidenceBeyondTheArchiveIsNotAGap() {
        val archive = run(10, base + 10 * step)
        val evidence = run(30, base)
        val report = ArchiveEvidenceCoverage.report(archive = archive, evidence = evidence)
        assertTrue(report.isComplete)
        assertEquals(30, report.evidenceRecordCount)
    }

    // :101
    @Test
    fun emptyArchiveIsVacuouslyComplete() {
        val report = ArchiveEvidenceCoverage.report(archive = emptyList(), evidence = run(4, base))
        assertTrue(report.isComplete)
        assertEquals(0, report.archiveRecordCount)
        assertEquals(0, report.longestMissingRunSeconds)
    }
}
