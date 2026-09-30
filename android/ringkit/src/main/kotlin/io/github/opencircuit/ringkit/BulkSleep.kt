package io.github.opencircuit.ringkit

// Reassembles `0x4c` history pages into records and maps them to health samples. PARTIAL port of
// upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/BulkSleep.swift (@ b1c2fdd): the page/stream
// split (`:368-396`), the motion timeline and motion-channel selection (`:398-803`, `:915-1140`),
// the HRV pooling gate (`:804-913`) and the sample path (`:1656-1722`).
//
// A 0x4c page is `[0x4c][0x00][countdown][N × 23-byte record][xor]` (../docs/PROTOCOL.md §5.3).
// Records align to page boundaries — each page body is a whole number of records.
//
// Not ported here: the night half — main sleep, sleep segments and staging (`:1141-1655`).

import java.time.Duration
import java.time.Instant
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

    // Motion timeline and motion-channel selection
    //
    // Every motion byte is read unsigned: awake epochs routinely carry counts ≥ 0x80, and a signed
    // read would turn a burst into a negative "stillness".

    /** `[10:15]` read unsigned, one fresh array per call. */
    private fun BulkRecord.motionCounts(): IntArray {
        val m = motion
        return IntArray(5) { m.u8(it) }
    }

    /**
     * Split [records] into runs with no gap wider than [maxGap] between consecutive epochs (sorted
     * by counter). The default matches the detector's own gap-break, so a fragment never holds a gap
     * the detector would itself split on. A gap of exactly [maxGap] does not split.
     */
    fun contiguousFragments(records: List<BulkRecord>, maxGap: Duration = ActivityPeriod.GRAVITY_MAX_GAP): List<List<BulkRecord>> {
        val sorted = records.sortedBy { it.counter }
        if (sorted.isEmpty()) return emptyList()
        val frags = mutableListOf<List<BulkRecord>>()
        var cur = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val gap = Duration.ofSeconds(sorted[i].counter - sorted[i - 1].counter)
            if (gap > maxGap) {
                frags += cur
                cur = mutableListOf(sorted[i])
            } else {
                cur += sorted[i]
            }
        }
        frags += cur
        return frags
    }

    /**
     * Expand `0x4c` records into a per-30 s motion timeline: five sub-samples per 150 s epoch from
     * `[10:15]`, passed through directly (baseline `01` = still) unless [motionSource] rejects the
     * primary channel for this run — then the chosen secondary channel's per-epoch magnitude is
     * repeated over the epoch's five slots. Callers should scope [records] to a worn period
     * (charging data reads as still too).
     */
    fun motionTimeline(
        records: List<BulkRecord>,
        epoch: Long = Command.SYNC_EPOCH,
        policy: MotionChannelPolicy = MotionChannelPolicy.DEFAULT,
    ): List<MotionSample> {
        val fallback = secondaryChannelMagnitudes(records, policy = policy)
        val out = ArrayList<MotionSample>(records.size * 5)
        records.forEachIndexed { index, r ->
            val base = r.date(epoch)
            val counts = r.motionCounts()
            for (k in 0 until 5) {
                out += MotionSample(base.plusSeconds(k * 30L), fallback?.get(index) ?: counts[k].toFloat())
            }
        }
        return out
    }

    /**
     * Which channel a run's motion is read from. [IntensityTail] carries WHY the primary was
     * rejected, because the two reasons need different tail→magnitude mappings.
     */
    internal sealed interface MotionSource {
        /** `[10:15]`, the normal path. */
        data object Primary : MotionSource

        /**
         * `[15:20]`. `degenerate == false` is the constant-filler shape; `true` is the FR04.009
         * non-expressive-primary shape.
         */
        data class IntensityTail(val degenerate: Boolean) : MotionSource

        /**
         * `[15:23)` decoded as five 12-bit magnitudes — the FR04 raised-floor shape. Reachable ONLY
         * when [MotionChannelPolicy.magnitudeChannelEnabled], which is NOT the shipped default.
         */
        data object ActivityMagnitudes : MotionSource
    }

    /**
     * Which secondary motion channels [motionSource] may select for a run, and where the decoded
     * magnitude channel's light/active seam sits. One value threaded through the whole motion path
     * so detection and staging can never read different channels.
     *
     * ⚠️ [DEFAULT] IS THE PRODUCT'S BEHAVIOUR. The constructor is internal: only this module's
     * tests (and a replay harness) build any other value. Immutable, compared by value.
     */
    class MotionChannelPolicy internal constructor(
        /** May a run fall through to the decoded `[15:23)` magnitude channel? `false` ⇒ never. */
        val magnitudeChannelEnabled: Boolean = ACTIVITY_MAGNITUDE_CHANNEL_ENABLED,
        /** Σ magnitudes at or above which an epoch on that channel is ACTIVE rather than light. */
        val magnitudeActiveCut: Int = ACTIVITY_MAGNITUDE_ACTIVE_CUT,
    ) {
        override fun equals(other: Any?): Boolean =
            other is MotionChannelPolicy && other.magnitudeChannelEnabled == magnitudeChannelEnabled &&
                other.magnitudeActiveCut == magnitudeActiveCut

        override fun hashCode(): Int = 31 * magnitudeChannelEnabled.hashCode() + magnitudeActiveCut

        override fun toString(): String =
            "MotionChannelPolicy(magnitudeChannelEnabled=$magnitudeChannelEnabled, magnitudeActiveCut=$magnitudeActiveCut)"

        companion object {
            /** The shipped policy: magnitude channel OFF. */
            val DEFAULT = MotionChannelPolicy()
        }
    }

    /**
     * THE KILL SWITCH for the decoded-magnitude motion channel. 🟡 It ships **false**, deliberately:
     * the channel swap is a staging change whose only evidence upstream is two UNLABELLED Gen 2 Air
     * nights, so nothing can adjudicate whether its wake time is closer to the truth. With it false
     * the output is byte-identical to the scoreboard that predates the channel. Flipping it on is the
     * deliverable of a labelled Gen 2 Air night.
     */
    const val ACTIVITY_MAGNITUDE_CHANNEL_ENABLED: Boolean = false

    /**
     * Pick the motion channel for a run. The primary `[10:15]` channel is unusable in these
     * structurally distinct ways:
     *  • CONSTANT FILLER — every worn epoch is a constant run ([BulkRecord.motionIsPlaceholder]).
     *  • NON-EXPRESSIVE — it varies, but only as a fixed intra-epoch template that never reads still
     *    ([primaryMotionIsDegenerate]; FR04.009).
     *  • RAISED FLOOR — it varies freely but never returns to baseline, a wandering pedestal the
     *    rolling floor cannot remove ([primaryFloorIsRaised]); gated by
     *    [MotionChannelPolicy.magnitudeChannelEnabled], which ships OFF.
     * The first two fall through to the `[15:20]` tail; each branch also requires at least two
     * epochs on which the channel it selects actually carries movement, so a motionless archive keeps
     * the primary path. The raised-floor reason is evaluated last and is NOT verdict-preserving: a
     * run that used to stay on the primary is exactly what it catches. The flag is what keeps the
     * shipped build unchanged.
     *
     * ⚠️ The constant-filler quantifier is all-or-nothing and fires on no known real source; widening
     * it to a placeholder SHARE was measured upstream to move staged nights scope-dependently. Leave
     * it alone until an absolute-floor tail mapping is fitted against real labels.
     */
    internal fun motionSource(records: List<BulkRecord>, policy: MotionChannelPolicy = MotionChannelPolicy.DEFAULT): MotionSource {
        val worn = records.filter { it.layout != BulkRecord.Layout.IDLE }
        if (worn.size < 4) return MotionSource.Primary
        val constantFiller = worn.all { it.motionIsPlaceholder }
        val degenerate = if (constantFiller) false else primaryMotionIsDegenerate(worn)
        if (constantFiller || degenerate) {
            val moving = worn.asSequence().filter { r -> r.motionIntensityTail.any { it.toInt() != 0 } }.take(2).count()
            return if (moving == 2) MotionSource.IntensityTail(degenerate) else MotionSource.Primary
        }
        if (!policy.magnitudeChannelEnabled) return MotionSource.Primary
        if (!primaryFloorIsRaised(worn)) return MotionSource.Primary
        val moving = worn.asSequence().filter { !it.activityMagnitudesAreZero }.take(2).count()
        return if (moving == 2) MotionSource.ActivityMagnitudes else MotionSource.Primary
    }

    /** True when this run reads motion off the `[15:20]` intensity tail instead of `[10:15]`. */
    internal fun usesMotionIntensityFallback(records: List<BulkRecord>): Boolean =
        motionSource(records) is MotionSource.IntensityTail

    /**
     * Minimum all-zero-tail ("the ring itself says nothing moved") epochs before the
     * non-expressive test judges a run: 24 × 150 s = 1 h = [ActivityPeriod.MIN_SLEEP_DURATION].
     */
    internal const val DEGENERATE_MIN_QUIET_EPOCHS = 24

    /**
     * At most this share of those epochs may resolve stillness on the primary channel. 🟢 MEASURED
     * upstream over every contiguous window of 9 real captures: FR04.009 0.000–0.269, real
     * Gen-2/Gen-3 0.792–1.000.
     */
    internal const val DEGENERATE_MAX_QUIET_STILL_FRACTION = 0.50

    /**
     * A slot pair must keep the SAME ordering on at least this share of those epochs. 🟢 MEASURED
     * upstream: FR04.009 0.833–1.000; real Gen-2/Gen-3 never above 0.167.
     */
    internal const val DEGENERATE_MIN_SLOT_ORDER_FRACTION = 0.75

    /**
     * Strength of a fixed intra-epoch template: the largest share of [epochs] on which one ordered
     * slot pair keeps the same ordering. ≈0.5 for independent noise, 0 for constant runs (every
     * comparison ties), ≈1 for a fixed step. Compares unsigned counts.
     */
    internal fun slotOrderConsistency(epochs: List<BulkRecord>): Double {
        if (epochs.isEmpty()) return 0.0
        val counts = epochs.map { it.motionCounts() }
        var best = 0
        for (j in 0 until 4) {
            for (k in j + 1 until 5) {
                var less = 0
                var greater = 0
                for (m in counts) {
                    if (m[j] < m[k]) less++ else if (m[k] < m[j]) greater++
                }
                best = maxOf(best, less, greater)
            }
        }
        return best.toDouble() / epochs.size
    }

    /**
     * True when the primary `[10:15]` channel is structurally unable to express stillness on this
     * run. Conditioned on the ring's own verdict: only epochs whose `[15:20]` tail is all zero are
     * judged, and both must hold — (a) fewer than [DEGENERATE_MAX_QUIET_STILL_FRACTION] of them
     * resolve stillness, and (b) some slot pair keeps its ordering on at least
     * [DEGENERATE_MIN_SLOT_ORDER_FRACTION] of them. Decided from the run's own distribution, never
     * a device label or firmware string.
     */
    internal fun primaryMotionIsDegenerate(worn: List<BulkRecord>): Boolean {
        val quiet = worn.filter { it.motionIntensityTailIsZero }
        if (quiet.size < DEGENERATE_MIN_QUIET_EPOCHS) return false
        val stillCount = quiet.count { it.motionResolvesStillness }
        if (stillCount.toDouble() >= quiet.size * DEGENERATE_MAX_QUIET_STILL_FRACTION) return false
        return slotOrderConsistency(quiet) >= DEGENERATE_MIN_SLOT_ORDER_FRACTION
    }

    /**
     * Whether the primary `[10:15]` channel reads STILL on each epoch of [run] after the SAME
     * rolling local floor the detector subtracts — one verdict per input epoch, in order. The share
     * threshold is [ActivityPeriod.GRAVITY_STILL_FRACTION], the share the detector itself requires,
     * reused so the two cannot drift apart. Reads `[10:15]` directly rather than through
     * [motionTimeline], which would re-enter [motionSource] and recurse.
     */
    internal fun primaryChannelIsStillAfterFloor(run: List<BulkRecord>, epoch: Long = Command.SYNC_EPOCH): List<Boolean> {
        val timeline = ArrayList<MotionSample>(run.size * 5)
        for (r in run) {
            val base = r.date(epoch)
            val counts = r.motionCounts()
            for (k in 0 until 5) timeline += MotionSample(base.plusSeconds(k * 30L), counts[k].toFloat())
        }
        val residual = ActivityPeriod.motionAboveLocalFloor(timeline)
        return run.indices.map { i ->
            val still = (i * 5 until i * 5 + 5).count { residual[it] < ActivityPeriod.MOTION_STILL_THRESHOLD }
            still.toFloat() / 5 >= ActivityPeriod.GRAVITY_STILL_FRACTION
        }
    }

    /**
     * Minimum MEDIAN per-epoch minimum sub-sample on the primary channel (over magnitude-quiet
     * epochs) before its floor counts as "off the `01` baseline". 🔴 NOT a firmware separator: both
     * FR04.009 corpus nights upstream carry a raised pedestal. It is not what keeps healthy channels
     * on the primary path either — the de-floored stillness conjunct is.
     */
    internal const val RAISED_FLOOR_MIN_MEDIAN_QUIET_MINIMUM = 16

    /**
     * True when the primary `[10:15]` channel varies freely yet never comes back to baseline. Same
     * method as [primaryMotionIsDegenerate] with two deliberate differences: the ring's verdict is
     * the layout-correct [BulkRecord.activityMagnitudesAreZero], and the proof is a raised,
     * WANDERING floor measured through the rolling floor ([primaryChannelIsStillAfterFloor]), not
     * the intra-epoch proxy. Gates in order: an hour of quiet epochs (cheap, so first — the floor
     * pass is O(n·w)); the primary refuses to read still on fewer than
     * [DEGENERATE_MAX_QUIET_STILL_FRACTION] of them; then the floor test. There is deliberately no
     * share-of-worn conjunct: a share of the window depends on how much daytime has drained.
     */
    internal fun primaryFloorIsRaised(worn: List<BulkRecord>): Boolean {
        val quietIndices = worn.indices.filter { worn[it].activityMagnitudesAreZero }
        if (quietIndices.size < DEGENERATE_MIN_QUIET_EPOCHS) return false
        val stillAfterFloor = primaryChannelIsStillAfterFloor(worn)
        val stillCount = quietIndices.count { stillAfterFloor[it] }
        if (stillCount.toDouble() >= quietIndices.size * DEGENERATE_MAX_QUIET_STILL_FRACTION) return false
        return medianQuietMinimum(quietIndices.map { worn[it] }) >= RAISED_FLOOR_MIN_MEDIAN_QUIET_MINIMUM
    }

    /**
     * Median over [epochs] of each epoch's SMALLEST `[10:15]` sub-sample (unsigned). The even-count
     * case takes the lower central value, biasing toward keeping the primary channel. 0 when empty.
     */
    internal fun medianQuietMinimum(epochs: List<BulkRecord>): Int {
        val mins = epochs.map { it.motionCounts().min() }.sorted()
        if (mins.isEmpty()) return 0
        return mins[(mins.size - 1) / 2]
    }

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
     * One magnitude per epoch using the same run-level channel selection as [motionTimeline]. On
     * the primary channel it is the unsigned sum of `[10:15]`. The staging model subtracts its
     * rolling floor afterwards, exactly as on primary motion.
     */
    internal fun motionMagnitudes(
        records: List<BulkRecord>,
        absoluteActiveCut: Int = MOTION_INTENSITY_ACTIVE_CUT,
        policy: MotionChannelPolicy = MotionChannelPolicy.DEFAULT,
    ): List<Float> =
        secondaryChannelMagnitudes(records, absoluteActiveCut, policy)
            ?: records.map { r -> r.motionCounts().sum().toFloat() }

    /**
     * The per-epoch magnitudes of whichever SECONDARY channel this run selected, or null when the
     * run stays on the primary `[10:15]` one. The single point of dispatch, so [motionTimeline] and
     * [motionMagnitudes] cannot drift apart on which channel a run reads.
     */
    internal fun secondaryChannelMagnitudes(
        records: List<BulkRecord>,
        absoluteActiveCut: Int = MOTION_INTENSITY_ACTIVE_CUT,
        policy: MotionChannelPolicy = MotionChannelPolicy.DEFAULT,
    ): List<Float>? = when (val source = motionSource(records, policy)) {
        MotionSource.Primary -> null
        is MotionSource.IntensityTail -> motionIntensityFallbackMagnitudes(records, source.degenerate, absoluteActiveCut)
        MotionSource.ActivityMagnitudes -> activityMagnitudeFallbackMagnitudes(records, policy.magnitudeActiveCut)
    }

    /**
     * Otsu two-class seam over [positive] (sorted ascending): the value at which the between-class
     * variance peaks, never splitting a tie. Reached only through the legacy per-set rank
     * (`absoluteActiveCut == 0`) on the non-expressive branch. 0 for an empty pool; the value itself
     * for a single one.
     */
    internal fun otsuIntensityCut(positive: List<Int>): Int {
        val n = positive.size
        if (n == 0) return 0
        val last = positive[n - 1]
        if (n < 2) return last
        val total = positive.sum()
        var lowSum = 0
        var best = last
        var bestVariance = -1.0
        for (i in 1 until n) {
            lowSum += positive[i - 1]
            if (positive[i] == positive[i - 1]) continue // never split a tie
            val w0 = i.toDouble() / n
            val w1 = 1 - i.toDouble() / n
            val m0 = lowSum.toDouble() / i
            val m1 = (total - lowSum).toDouble() / (n - i)
            val v = w0 * w1 * (m0 - m1) * (m0 - m1)
            if (v > bestVariance) {
                bestVariance = v
                best = positive[i]
            }
        }
        return best
    }

    /**
     * The ABSOLUTE light/active seam for the intensity tail, in raw `[15:20]` byte-sum units.
     * `0` restores the legacy per-set rank (p80 / Otsu) exactly — the one-line revert.
     *
     * Why absolute: a rank over "whatever has drained so far" makes the threshold a function of sync
     * timing — measured upstream moving 249 → 247 → 249 across consecutive cuts and flipping the
     * reported night. 345 is the median per-night legacy cut over the corpus (344.5), which preserves
     * the operating point the other calibrations were fitted to. 🔴 It is NOT a fitted physiological
     * threshold.
     */
    internal const val MOTION_INTENSITY_ACTIVE_CUT = 345

    /**
     * Map the `[15:20]` tail onto the `0 / 1 / 16` scale detection and staging share: a zero tail is
     * `0` whatever the seam, a tail at or above the seam `16`, anything else `1`. Tail bytes are
     * summed unsigned. With `absoluteActiveCut == 0` the seam is the legacy rank: Otsu when
     * [degenerate], else the 0.80 quantile (index rounded half away from zero, as Swift does).
     */
    internal fun motionIntensityFallbackMagnitudes(
        records: List<BulkRecord>,
        degenerate: Boolean,
        absoluteActiveCut: Int = MOTION_INTENSITY_ACTIVE_CUT,
    ): List<Float> {
        val sums = records.map { r -> r.motionIntensityTail.let { t -> (0 until 5).sumOf { t.u8(it) } } }
        val positive = sums.filter { it > 0 }.sorted()
        if (positive.isEmpty()) return List(records.size) { 0f }
        // A fixed seam does not care how much history has drained; the legacy ranks did.
        val activeCut = when {
            absoluteActiveCut > 0 -> absoluteActiveCut
            degenerate -> otsuIntensityCut(positive)
            else -> positive[Math.round((positive.size - 1) * 0.80).toInt()]
        }
        return sums.map { if (it <= 0) 0f else if (it >= activeCut) 16f else 1f }
    }

    /**
     * The light/active seam for the DECODED magnitude channel, in Σ `activityMagnitudes` units.
     * 🟡 250 is the centre of the flat region of a measured sweep over one UNLABELLED night, not a
     * fitted threshold; the sweep also shows it is second-order next to the channel swap itself.
     */
    internal const val ACTIVITY_MAGNITUDE_ACTIVE_CUT = 250

    /** The decoded `[15:23)` magnitudes on the same `0 / 1 / 16` scale as the tail fallback. */
    internal fun activityMagnitudeFallbackMagnitudes(
        records: List<BulkRecord>,
        absoluteActiveCut: Int = ACTIVITY_MAGNITUDE_ACTIVE_CUT,
    ): List<Float> = records.map { r ->
        val sum = r.activityMagnitudes.sum()
        if (sum <= 0) 0f else if (sum >= absoluteActiveCut) 16f else 1f
    }

    /**
     * Per-epoch heart-rate timeline (byte `[4]`) for the detector's HR gate, from the SAME records
     * as [motionTimeline]; idle/unworn epochs (no [BulkRecord.heartRate]) are skipped.
     */
    fun heartRateTimeline(records: List<BulkRecord>, epoch: Long = Command.SYNC_EPOCH): List<HeartRateSample> =
        records.mapNotNull { r -> r.heartRate?.let { HeartRateSample(r.date(epoch), it) } }

    /**
     * Times of epochs on which the ring emitted SLEEP VITALS (strict [BulkRecord.hrvRMSSD] present) —
     * the "ring is still measuring sleep" evidence the detector extends a night on.
     *
     * ⚠️ MUST keep using the strict `hrvRMSSD`, never the measured-HRV accessor: quiet activity
     * epochs carry HRV too, and feeding them here would extend nights through the awake evening.
     */
    fun sleepVitalTimeline(records: List<BulkRecord>, epoch: Long = Command.SYNC_EPOCH): List<Instant> =
        records.mapNotNull { r -> if (r.hrvRMSSD != null) r.date(epoch) else null }

    /**
     * Restrict [records] to those whose epoch falls within [hint], or return them unchanged when
     * [within] is null (as a new list: a caller mutating its own list later never changes this
     * result, as Swift's array copy guaranteed). The window is CLOSED, as Foundation's
     * `DateInterval.contains` is: an epoch exactly on the window's end is kept.
     */
    fun records(records: List<BulkRecord>, within: DateInterval?, epoch: Long = Command.SYNC_EPOCH): List<BulkRecord> {
        if (within == null) return records.toList()
        return records.filter { within.containsClosed(it.date(epoch)) }
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
