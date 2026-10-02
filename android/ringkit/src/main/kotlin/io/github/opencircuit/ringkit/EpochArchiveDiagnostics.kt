package io.github.opencircuit.ringkit

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure diagnostics over the drained `0x4c` epoch archive. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/Diagnostics/EpochArchiveDiagnostics.swift:15-78
// (@ b1c2fdd).
//
// The most useful artifact for triaging "why is my sleep / HRV / respiratory rate blank": it shows
// which epochs were actually drained and, crucially, the GAPS between them. A hole here is history
// the app NEVER pulled off the ring — invisible in a raw-frame capture but obvious here.
//
// TEXT FORMAT. Byte-identical to upstream's. Timestamps use the pattern `MM-dd HH:mm` with
// `Locale.ROOT` (upstream: `en_US_POSIX`). Hours use one decimal rounded from the exact binary
// value, ties to even — what upstream's `String(format: "%.1f")` does. Java's `String.format`
// rounds differently (0.15 → "0.2", 0.25 → "0.3"; upstream writes "0.1" and "0.2"), so it is not
// used here.

object EpochArchiveDiagnostics {

    /**
     * Minimum gap between consecutive epoch counters to flag as a hole. Epochs step 150 s, so a few
     * multiples is normal cadence; past this it is a genuinely missing stretch.
     */
    val DEFAULT_GAP_THRESHOLD: Duration = Duration.ofMinutes(6)

    /**
     * A shareable text section: span, layout / vitals coverage and the gap report. [zone] formats
     * the timestamps (the app passes the phone's zone so a tester reads local time; the default is
     * UTC). The `(UTC±h)` label is [zone]'s whole-hour offset at the LAST epoch in the archive.
     * Lines are joined with `\n`, no trailing newline.
     */
    fun report(
        records: List<BulkRecord>,
        gapThreshold: Duration = DEFAULT_GAP_THRESHOLD,
        zone: ZoneId = ZoneOffset.UTC,
        epoch: Long = Command.SYNC_EPOCH,
    ): String {
        val lines = mutableListOf("# Epoch archive (drained 0x4c sleep/activity history)")
        val sorted = records.sortedBy { it.counter }
        if (sorted.isEmpty()) {
            lines += "(empty — nothing drained for the retained window, or archive cleared)"
            return lines.joinToString("\n")
        }
        val first = sorted.first()
        val last = sorted.last()

        val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(zone)
        fun t(r: BulkRecord): String = fmt.format(r.date(epoch))
        // Integer division truncates toward zero, as upstream's `Int` division does: +05:30 → "+5".
        val offH = zone.rules.getOffset(last.date(epoch)).totalSeconds / 3600
        val offLabel = "UTC${if (offH >= 0) "+" else ""}$offH"

        var idle = 0
        var sleepV = 0
        var activity = 0
        var hr = 0
        var hrv = 0
        var spo2 = 0
        // What the sample path emits — a superset of the strict counts above (quiet activity epochs
        // carry real HRV, any worn epoch carries RR). Reported on its OWN line so the
        // `Vitals coverage:` line keeps counting the strict accessors and stays comparable.
        var mHRV = 0
        var mRR = 0
        for (r in sorted) {
            when (r.layout) {
                BulkRecord.Layout.IDLE -> idle += 1
                BulkRecord.Layout.SLEEP_VITALS -> sleepV += 1
                BulkRecord.Layout.ACTIVITY -> activity += 1
            }
            if (r.heartRate != null) hr += 1
            if (r.hrvRMSSD != null) hrv += 1
            if (r.spo2Percent != null) spo2 += 1
            if (r.measuredHRVRMSSD != null) mHRV += 1
            if (r.measuredRespiratoryRate != null) mRR += 1
        }

        lines += "Epochs: ${sorted.size}   span: ${t(first)} → ${t(last)} ($offLabel)"
        lines += "Layout: sleepV $sleepV · activity $activity · idle $idle"
        lines += "Vitals coverage: HR $hr · HRV $hrv · SpO2 $spo2"
        lines += "Measured (#185): HRV $mHRV · RR $mRR"
        lines += ""
        lines += "Gaps > ${gapThreshold.toMinutes()} min (a hole = history NEVER drained — the key sleep-loss signal):"
        var anyGap = false
        for (i in 1 until sorted.size) {
            val gapSeconds = sorted[i].counter - sorted[i - 1].counter
            if (Duration.ofSeconds(gapSeconds) > gapThreshold) {
                anyGap = true
                lines += "  ${t(sorted[i - 1])} ──${oneDecimal(gapSeconds / 3600.0)}h──> ${t(sorted[i])}"
            }
        }
        if (!anyGap) lines += "  (none — contiguous coverage)"
        return lines.joinToString("\n")
    }

    /** Upstream's `String(format: "%.1f", v)`: the exact binary value, one decimal, ties to even. */
    internal fun oneDecimal(v: Double): String = swiftFixed(v, 1)
}
