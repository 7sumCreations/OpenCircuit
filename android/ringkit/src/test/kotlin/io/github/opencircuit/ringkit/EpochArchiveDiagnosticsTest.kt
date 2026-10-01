package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The text section over the drained `0x4c` archive: span, coverage and the gap report.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/EpochArchiveDiagnosticsTests.swift
 * (@ b1c2fdd), all 4 tests.
 */
class EpochArchiveDiagnosticsTest {

    /** :7-13 — one sleep-vitals record at [counter] (HR, HRV, RR×8, SpO2 present). */
    private fun rec(counter: Long, hr: Int = 60): BulkRecord {
        val b = ByteArray(23)
        b[0] = ((counter ushr 24) and 0xff).toByte(); b[1] = ((counter ushr 16) and 0xff).toByte()
        b[2] = ((counter ushr 8) and 0xff).toByte(); b[3] = (counter and 0xff).toByte()
        b[4] = hr.toByte(); b[5] = 50; b[7] = 120; b[8] = 97 // HR, HRV, RR*8, SpO2
        return BulkRecord.of(b)!!
    }

    // :15
    @Test
    fun emptyArchive() {
        assertTrue(EpochArchiveDiagnostics.report(emptyList()).contains("empty"))
    }

    // :19
    @Test
    fun contiguousHasNoGap() {
        val recs = (0 until 6).map { rec(it * 150L) } // 150 s steps
        val report = EpochArchiveDiagnostics.report(recs)
        assertTrue(report.contains("Epochs: 6"))
        assertTrue(report.contains("(none — contiguous coverage)"))
        assertTrue(report.contains("HR 6")) // all six carry HR
    }

    // :27 — three contiguous epochs, a 6.3 h hole, then two more. `UInt32(6.3 * 3600)` is 22 680.
    @Test
    fun flagsOvernightHole() {
        val base = 0L
        val hole = (6.3 * 3600).toLong()
        val recs = listOf(
            rec(base), rec(base + 150), rec(base + 300),
            rec(base + 300 + hole),
            rec(base + 300 + hole + 150),
        )
        val report = EpochArchiveDiagnostics.report(recs)
        assertTrue(report.contains("6.3h"), "the 6.3 h hole must be reported")
        assertFalse(report.contains("(none — contiguous coverage)"))
    }

    // :38 — a 5-min gap is under the default 6-min threshold, so it is not flagged.
    @Test
    fun gapThresholdRespected() {
        val recs = listOf(rec(0), rec(300)) // 5 min
        assertTrue(EpochArchiveDiagnostics.report(recs).contains("(none — contiguous coverage)"))
    }
}
