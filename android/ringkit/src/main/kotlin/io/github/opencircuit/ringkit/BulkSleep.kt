package io.github.opencircuit.ringkit

// Reassembles `0x4c` history pages into records and maps them to health samples. PARTIAL port of
// upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/BulkSleep.swift (@ b1c2fdd): the page/stream
// split (`:368-396`), the HRV pooling gate (`:804-913`) and the sample path (`:1656-1722`).
//
// A 0x4c page is `[0x4c][0x00][countdown][N × 23-byte record][xor]` (../docs/PROTOCOL.md §5.3).
// Records align to page boundaries — each page body is a whole number of records.
//
// Not ported here: the motion timeline and motion-channel selection (`:398-803`, `:914-1140`),
// and the night half — main sleep, sleep segments and staging (`:1141-1655`).

import kotlin.math.abs

/** Reassembles `0x4c` pages into records and maps epochs to health samples. */
object BulkSleep {

    /**
     * Records carried by ONE `0x4c` page frame (full notification incl. opcode + XOR). Empty if the
     * XOR trailer is invalid or the opcode isn't `0x4c`. Empty is NOT evidence of corruption: a
     * valid page with no whole record is also empty — only `Frame.parse` tells the two apart.
     */
    fun recordsFromPage(frame: ByteArray): List<BulkRecord> {
        val p = Frame.parse(frame) ?: return emptyList()
        if (p.opcode != Frame.responseId(Opcode.PAGE_4C)) return emptyList()
        // body = [00][countdown][records…]; records begin at body index 2.
        val body = p.body
        if (body.size <= 2) return emptyList()
        return recordsFromStream(body.copyOfRange(2, body.size))
    }

    /** Split a raw record stream (no page header/trailer) into whole 23-byte records. A trailing partial chunk is dropped. */
    fun recordsFromStream(bytes: ByteArray): List<BulkRecord> {
        if (bytes.size < BulkRecord.LENGTH) return emptyList()
        return (0..bytes.size - BulkRecord.LENGTH step BulkRecord.LENGTH).mapNotNull { offset ->
            BulkRecord.of(bytes.copyOfRange(offset, offset + BulkRecord.LENGTH))
        }
    }

    /** Reassemble a multi-page bulk transfer into one ordered record list. */
    fun recordsFromPages(frames: List<ByteArray>): List<BulkRecord> = frames.flatMap { recordsFromPage(it) }

    // HRV pooling gate
    //
    // The sample path pools the HRV that 0x12/0x13 activity epochs carry. On every Gen 2 and Gen 3
    // archive upstream measured, the two record templates measure the same thing; on one device
    // family the activity template runs ~13–20 ms LOW. So the run's OWN data decides whether the
    // two populations agree — no ring generation, no firmware string, no per-device correction: a
    // disagreeing run simply stops pooling and falls back to sleep-vitals HRV alone.

    /**
     * Widest |HRV shift| (ms) between the two templates that still reads as AGREEMENT. 🟢 MEASURED
     * upstream over 10 real archives: agreeing devices land at ≤ 5.0, the disagreeing one at
     * ≥ 13.0; 9.0 is the midpoint and sits above a permutation null's p99. 🟡 The class boundary
     * rests on ONE archive of the disagreeing class.
     */
    internal const val HRV_POOLING_NOISE_FLOOR_MS = 9.0

    /**
     * Minimum epochs in EACH population before the comparison is judged at all. 🟢 MEASURED: the
     * separation saturates at N = 20; below it the 9.0 ms threshold stops being safe. The two
     * constants are jointly calibrated.
     */
    internal const val HRV_POOLING_MIN_EPOCHS = 20

    /**
     * Deterministic per-side cap on the pairwise difference set (Hodges–Lehmann is O(|a|·|b|)).
     * Stride-subsampling the SORTED pool preserves its shape and uses no RNG. Never binds on real
     * data; it bounds a caller that passes an unpruned set.
     */
    internal const val HRV_POOLING_SAMPLE_CAP = 512

    /** Hodges–Lehmann two-sample shift: the median of all pairwise `a - b` differences. 0 when either side is empty. */
    internal fun hrvShift(a: List<Int>, b: List<Int>): Double {
        fun thinned(v: List<Int>): IntArray {
            val s = v.sorted()
            if (s.size <= HRV_POOLING_SAMPLE_CAP) return s.toIntArray()
            val step = s.size.toDouble() / HRV_POOLING_SAMPLE_CAP
            return IntArray(HRV_POOLING_SAMPLE_CAP) { s[(it * step).toInt()] }
        }
        val x = thinned(a)
        val y = thinned(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val diffs = IntArray(x.size * y.size)
        var k = 0
        for (i in x) for (j in y) diffs[k++] = i - j
        diffs.sort()
        val n = diffs.size
        return if (n % 2 == 1) diffs[n / 2].toDouble() else (diffs[n / 2 - 1] + diffs[n / 2]) / 2.0
    }

    /** Verdict of the run-level HRV pooling gate. Public so the drain path can log it. */
    enum class HRVPooling { AGREE, DISAGREE, NO_EVIDENCE }

    /**
     * Do the two record templates' HRV populations agree on THIS run?
     *
     * BOTH sides are conditioned on the ring's own `[15:20]` "nothing moved" verdict: the activity
     * side through [BulkRecord.measuredHRVRMSSD]'s quiet gate, the sleep-vitals side here. That is
     * what removes the awake-vs-asleep physiology confound — do not "simplify" it back to all
     * sleep-vitals epochs, and never key it on the primary `[10:15]` channel.
     *
     * A calibration set with too few epochs on either side returns [HRVPooling.NO_EVIDENCE].
     */
    fun hrvPooling(calibration: List<BulkRecord>): HRVPooling {
        val act = mutableListOf<Int>()
        val sv = mutableListOf<Int>()
        for (r in calibration) {
            if (!r.motionIntensityTailIsZero) continue
            if (r.layout == BulkRecord.Layout.ACTIVITY) {
                r.measuredHRVRMSSD?.let { act += it }
            } else {
                // Band-guard the reference side too: `hrvRMSSD` does not reject 201…255, and a
                // garbage byte in the reference pool would bias the shift into a spurious DISAGREE.
                val v = r.hrvRMSSD
                if (v != null && v <= BulkRecord.MAX_PLAUSIBLE_HRV_MS) sv += v
            }
        }
        if (act.size < HRV_POOLING_MIN_EPOCHS || sv.size < HRV_POOLING_MIN_EPOCHS) return HRVPooling.NO_EVIDENCE
        return if (abs(hrvShift(act, sv)) <= HRV_POOLING_NOISE_FLOOR_MS) HRVPooling.AGREE else HRVPooling.DISAGREE
    }

    /**
     * Health samples for [records] (one drain's slice).
     *
     * [calibratedBy] is the record set the HRV pooling gate is judged on — pass the rolling
     * archive union, NOT this slice (a slice is far too short to decide). It gates ONLY the
     * recovered activity-epoch HRV; HR, SpO2 and RR are emitted identically either way.
     *
     * ⚠️ `calibratedBy == null` leaves the gate INERT (pooling on). It exists only for fixture
     * callers that pass a single record, which no calibration set could decide. Every path that
     * reaches the health store must pass a calibration set.
     */
    fun samples(
        records: List<BulkRecord>,
        calibratedBy: List<BulkRecord>? = null,
        epoch: Long = Command.SYNC_EPOCH,
    ): List<QuantitySample> =
        samples(records, verdict = calibratedBy?.let { hrvPooling(it) } ?: HRVPooling.AGREE, epoch = epoch)

    /**
     * [samples] with an ALREADY-RESOLVED pooling verdict — so a caller can carry the last DECIDED
     * verdict across commits instead of letting one night's epochs be committed under two
     * different verdicts.
     */
    fun samples(
        records: List<BulkRecord>,
        verdict: HRVPooling,
        epoch: Long = Command.SYNC_EPOCH,
    ): List<QuantitySample> {
        val poolActivityHRV = verdict == HRVPooling.AGREE
        val out = mutableListOf<QuantitySample>()
        for (r in records) {
            val t = r.date(epoch)
            r.heartRate?.let { out += QuantitySample(MetricKind.HEART_RATE, start = t, value = it.toDouble()) }
            // Sleep-vitals HRV is NEVER gated; only the recovered activity-epoch half waits on the verdict.
            val hrv = r.measuredHRVRMSSD
            if (hrv != null && (r.layout == BulkRecord.Layout.SLEEP_VITALS || poolActivityHRV)) {
                out += QuantitySample(MetricKind.HRV_SDNN, start = t, value = hrv.toDouble())
            }
            r.spo2Percent?.let { out += QuantitySample(MetricKind.SPO2, start = t, value = it / 100.0) }
            r.measuredRespiratoryRate?.let { out += QuantitySample(MetricKind.RESPIRATORY_RATE, start = t, value = it) }
        }
        return out
    }
}
