package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/OSASpO2.swift (@ b1c2fdd),
// whole: `OSAWaveform` (the `0x48` dense-PPG frame decode) and `OSASpO2` (the SpO₂ analyzer).
//
// Port notes:
//  • Frames are `ByteArray`s read unsigned; decoded samples are `Int` (24-bit, 0…16 777 215) and the
//    cursor and counter `Long` (upstream `UInt32`). `dominantSessionFrames` returns copies, as a Swift
//    `[[UInt8]]` is a value.
//  • Two cursors with the same frame count: upstream picks whichever a per-process-seeded Swift
//    Dictionary yields first (measured: either, run to run); the port picks the cursor seen first.
//  • Swift's `min`/`max`/`max(by:)`/`Sequence.min()` and its stable `sorted()` are reproduced, so NaN,
//    ±Inf and -0.0 land where upstream puts them.
//  • `desaturationEvents` stops when its scan would revisit an index; upstream loops forever there
//    (or traps stepping before the start). Every input upstream returns on is unchanged.
//  • The Goertzel step's `cos`/`sin` are `StrictMath`'s (fdlibm): the same bits on every JVM and on
//    Android, whose `Math` uses the platform's libm. Measured against upstream's Swift output, the
//    JVM's intrinsic `Math.cos`/`Math.sin` left 4 of 8731 differential values one ulp off; fdlibm
//    left none.

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The OSA dense-PPG `0x48` waveform (../docs/RUNBOOK_OSA_APNEA.md): ~4.15 Hz per channel, three PPG
 * channels (IR = ch0, Red = ch1, Green = ch2). The coarse `0x4c` SpO₂ (2.5-min epochs) misses the
 * brief nadirs that drive event metrics; this waveform is the substrate for them.
 */
object OSAWaveform {

    /** Full `0x48` notification length: `[0x48]` opcode + 13-B header + 182-B payload + XOR. */
    const val FRAME_LENGTH: Int = 197
    const val OPCODE: Int = 0x48

    /** Samples per channel in one frame (60 samples / 3 channels). */
    const val SAMPLES_PER_CHANNEL_PER_FRAME: Int = 20

    /**
     * Decode `0x48` frames into 3 continuous channels `[ch0 (IR), ch1 (Red), ch2 (Green)]`.
     * Frames shorter than [FRAME_LENGTH] or with another opcode are skipped. Deduplicated by the
     * 4-byte counter (`frame[2..5]`; the morning store-and-forward burst retransmits ~1900 frames a
     * night) — the first frame of each counter wins — and ordered by DESCENDING counter, which is
     * chronological (the counter counts down). Payload = `[marker][30 samples][marker][30 samples]`,
     * samples 3-byte big-endian, the three LEDs interleaved by `index % 3`. Frames of another
     * night's session are the caller's to drop ([dominantSessionFrames]).
     */
    fun channels(frames: List<ByteArray>): List<List<Int>> {
        val byCounter = HashMap<Long, List<List<Int>>>()
        for (f in frames) {
            if (f.size < FRAME_LENGTH || f.u8(0) != OPCODE) continue
            val counter = be32(f, 2)
            if (byCounter.containsKey(counter)) continue
            val ch = List(3) { ArrayList<Int>(SAMPLES_PER_CHANNEL_PER_FRAME) }
            for (blk in intArrayOf(15, 106)) { // first-sample index of each 30-sample block
                for (s in 0 until 30) {
                    val i = blk + s * 3
                    ch[s % 3] += (f.u8(i) shl 16) or (f.u8(i + 1) shl 8) or f.u8(i + 2)
                }
            }
            byCounter[counter] = ch
        }
        val out = List(3) { ArrayList<Int>() }
        for (c in byCounter.keys.sortedDescending()) { // descending counter = chronological
            val ch = byCounter.getValue(c)
            for (k in 0 until 3) out[k] += ch[k]
        }
        return out
    }

    /** The 4-byte session cursor of a `0x48` frame (`frame[6..9]`) — distinct per night; null on a short or foreign frame. */
    fun sessionCursor(frame: ByteArray): Long? {
        if (frame.size < FRAME_LENGTH || frame.u8(0) != OPCODE) return null
        return be32(frame, 6)
    }

    /**
     * Copies of the frames of the MODAL (most frequent) session cursor — this night's burst. A morning
     * dump can re-emit a previous night's session verbatim; keeping the dominant cursor stops
     * [channels] concatenating two nights. Frames without a valid cursor are dropped. On a tie the
     * cursor seen first wins.
     */
    fun dominantSessionFrames(frames: List<ByteArray>): List<ByteArray> {
        val counts = LinkedHashMap<Long, Int>()
        for (f in frames) sessionCursor(f)?.let { counts[it] = (counts[it] ?: 0) + 1 }
        // Swift's max(by:): the first entry, replaced only by a strictly larger count.
        var modal: Long? = null
        var best = 0
        for ((cursor, n) in counts) if (modal == null || best < n) { modal = cursor; best = n }
        if (modal == null) return emptyList()
        return frames.filter { sessionCursor(it) == modal }.map { it.copyOf() }
    }

    private fun be32(f: ByteArray, at: Int): Long =
        (f.u8(at).toLong() shl 24) or (f.u8(at + 1).toLong() shl 16) or (f.u8(at + 2).toLong() shl 8) or f.u8(at + 3).toLong()
}

/**
 * The OSA SpO₂ analyzer — the port of the desktop pipeline that reproduces the ring app's 3-night
 * assessment SpO₂ average to ±1 % and nadir to ±3 % (../docs/RUNBOOK_OSA_APNEA.md). Chain: `0x48`
 * frames → dedupe by counter → 3 PPG channels → per-window frequency-domain ratio-of-ratios →
 * `SpO₂ = A − B·R`. Pure and deterministic; no I/O.
 *
 * ⚠️ The event metrics ([NightSummary.timeBelow90Seconds], [NightSummary.odi]) are ESTIMATES — the
 * app's artifact rejection and event scoring are proprietary. Label them EXPERIMENTAL wherever they
 * are shown or written. [NightSummary.averageSpO2] is the validated one.
 */
object OSASpO2 {

    // Calibration (3-night least squares on average + nadir anchors).

    /** `SpO₂ = CAL_A − CAL_B · R`; a 2-parameter fit, re-fit when more labelled nights land. */
    const val CAL_A: Double = 104.91
    const val CAL_B: Double = 15.18

    /** Nominal sample rate (Hz per channel), pulse-anchored. Sets only the time axis of the event metrics. */
    const val SAMPLE_RATE_HZ: Double = 4.15

    // Frequency-domain search band (cycles/sample) for the cardiac peak.
    const val F_MIN: Double = 0.10
    const val F_MAX: Double = 0.40
    const val FREQ_COUNT: Int = 60

    /** [FREQ_COUNT] frequencies evenly spaced from [F_MIN] to [F_MAX], as upstream computes them. */
    val FREQS: List<Double> = List(FREQ_COUNT) { F_MIN + (F_MAX - F_MIN) * it.toDouble() / (FREQ_COUNT - 1).toDouble() }

    // Windowing and gating.

    /** ~30 s at 4.15 Hz. */
    const val WINDOW_LENGTH: Int = 128
    const val WINDOW_STEP: Int = 64

    /** IR pulse SNR floor (kills the fake-low artifact). */
    const val SNR_IR_FLOOR: Double = 5.0

    /** Red and green SNR floors. */
    const val SNR_OTHER_FLOOR: Double = 4.0

    /** Low perfusion ⇒ AC_ir tiny ⇒ R unreliable. */
    const val PI_IR_FLOOR_PERCENT: Double = 0.15

    /** `|DFT|` amplitude at normalised frequency [f] (cycles/sample), mean removed (Goertzel). 0 for an empty input. */
    fun goertzelMagnitude(x: List<Double>, f: Double): Double {
        val n = x.size
        if (n == 0) return 0.0
        val mean = sum(x) / n.toDouble()
        val w = 2 * PI * f
        val cr = StrictMath.cos(w)
        val ci = StrictMath.sin(w)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s0 = (v - mean) + 2 * cr * s1 - s2
            s2 = s1
            s1 = s0
        }
        val re = s1 - s2 * cr
        val im = s2 * ci
        return sqrt(re * re + im * im) * 2 / n.toDouble()
    }

    private fun sum(x: List<Double>): Double {
        var s = 0.0
        for (v in x) s += v
        return s
    }

    private fun mean(x: List<Double>): Double = sum(x) / x.size.toDouble()

    /** Median with Swift's stable sort; 0 for an empty input; the mean of the middle pair for an even count. */
    private fun median(x: List<Double>): Double {
        val s = swiftSorted(x)
        val n = s.size
        if (n == 0) return 0.0
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun bandAmplitudes(w: List<Double>): List<Double> = FREQS.map { goertzelMagnitude(w, it) }

    /** Swift's `indices.max(by: { a[$0] < a[$1] })`: the first index of the maximum (a NaN never replaces). */
    private fun argMax(a: List<Double>): Int? {
        if (a.isEmpty()) return null
        var result = 0
        for (k in 1 until a.size) if (a[result] < a[k]) result = k
        return result
    }

    /**
     * Ratio-of-ratios `R = (AC_red / DC_red) / (AC_ir / DC_ir)` for ONE window, ungated: null when a
     * DC is not positive or the IR pulse is zero. Locks the cardiac frequency on the green channel.
     */
    fun windowRatio(ir: List<Double>, red: List<Double>, green: List<Double>): Double? {
        val dci = mean(ir)
        val dcr = mean(red)
        if (!(dci > 0 && dcr > 0)) return null
        val ampg = bandAmplitudes(green)
        val fi = argMax(ampg) ?: return null
        val fstar = FREQS[fi]
        val aci = goertzelMagnitude(ir, fstar)
        val acr = goertzelMagnitude(red, fstar)
        if (!(aci > 0)) return null
        return (acr / dcr) / (aci / dci)
    }

    /** Calibrated SpO₂ for a ratio [r], clamped to at most 100 (Swift's `min`: a NaN ratio gives 100). */
    fun spo2FromRatio(r: Double): Double = swiftMin(100.0, CAL_A - CAL_B * r)

    /**
     * Gated per-window SpO₂ series across the night, chronological, one value per [WINDOW_STEP]
     * samples. Windows without a clean pulse in all three channels, with IR perfusion below
     * [PI_IR_FLOOR_PERCENT], or with R outside (0.1, 2.5) are dropped, as clinical oximeters flag
     * low-perfusion readings. The shortest channel bounds the night.
     */
    fun spo2Series(ir: List<Int>, red: List<Int>, green: List<Int>): List<Double> {
        val n = minOf(ir.size, red.size, green.size)
        if (n < WINDOW_LENGTH) return emptyList()
        val out = mutableListOf<Double>()
        var a = 0
        while (a + WINDOW_LENGTH <= n) {
            val start = a
            a += WINDOW_STEP
            val wi = ir.subList(start, start + WINDOW_LENGTH).map { it.toDouble() }
            val wr = red.subList(start, start + WINDOW_LENGTH).map { it.toDouble() }
            val wg = green.subList(start, start + WINDOW_LENGTH).map { it.toDouble() }
            val dci = mean(wi)
            val dcr = mean(wr)
            if (!(dci > 0 && dcr > 0)) continue
            val ampg = bandAmplitudes(wg)
            val fi = argMax(ampg) ?: continue
            val fstar = FREQS[fi]
            val medg = median(ampg)
            val si = goertzelMagnitude(wi, fstar) / (median(bandAmplitudes(wi)) + 1e-9)
            val sr = goertzelMagnitude(wr, fstar) / (median(bandAmplitudes(wr)) + 1e-9)
            val sg = ampg[fi] / (medg + 1e-9)
            if (!(si >= SNR_IR_FLOOR && sr >= SNR_OTHER_FLOOR && sg >= SNR_OTHER_FLOOR)) continue
            val aci = goertzelMagnitude(wi, fstar)
            val pii = aci / dci
            if (!(pii * 100 >= PI_IR_FLOOR_PERCENT)) continue
            val r = (goertzelMagnitude(wr, fstar) / dcr) / pii
            if (!(r > 0.1 && r < 2.5)) continue
            out += spo2FromRatio(r)
        }
        return out
    }

    /**
     * Centred median filter over a [k]-wide window (`k / 2` each side), clamped at the edges. Used to
     * reject single-window spikes before taking extremes. [k] ≤ 1 or an empty input returns [x] as is.
     */
    fun medianFilter(x: List<Double>, k: Int): List<Double> {
        if (k <= 1 || x.isEmpty()) return x.toList()
        val n = x.size
        val h = (k / 2).toLong()
        return List(n) { i ->
            val lo = maxOf(0L, i - h).toInt()
            val hi = minOf(n.toLong(), i + h + 1).toInt()
            median(x.subList(lo, hi))
        }
    }

    /** A night's SpO₂ summary. [averageSpO2] is the validated metric; the rest are ESTIMATES. */
    data class NightSummary(
        val averageSpO2: Double,
        val minSpO2: Double,
        val timeBelow90Seconds: Double,
        /** Desaturation events per hour. */
        val odi: Double,
        val validWindows: Int,
        val durationHours: Double,
    )

    /**
     * Oxygen Desaturation Index events: dips at least [dropPercent] below a rolling median baseline
     * ([baselineWindow] wide), each followed until near-recovery (within 1 % of that baseline), with
     * [refractory] epochs skipped after each. ESTIMATE (coarse vs the app's event scorer).
     *
     * Parameters that would make the scan stand still or step back (a negative [refractory], or a
     * [dropPercent] under 1 with [refractory] 0) are bounded: the scan stops the first time it would
     * revisit an index — upstream never returns there — and the dips counted so far are returned.
     */
    fun desaturationEvents(spo2: List<Double>, dropPercent: Double = 3.0, baselineWindow: Int = 20, refractory: Int = 4): Int {
        val n = spo2.size
        if (n == 0) return 0
        val base = medianFilter(spo2, baselineWindow)
        val visited = BooleanArray(n)
        var events = 0
        var i = 0L
        while (i < n) {
            // The scan is a function of its index alone, so a revisit is upstream's endless loop, and an
            // index before the start is its out-of-range trap.
            if (i < 0 || visited[i.toInt()]) break
            val at = i.toInt()
            visited[at] = true
            if (spo2[at] <= base[at] - dropPercent) {
                var j = at
                while (j < n && spo2[j] <= base[at] - 1.0) j++ // extend until near-recovery
                events++
                i = j.toLong() + refractory
            } else {
                i++
            }
        }
        return events
    }

    /**
     * Full night summary from a collected `0x48` burst: keep this night's session, decode the
     * channels, summarise. Null when there are not enough clean windows. The single entry point a
     * BLE handler calls once a burst completes.
     */
    fun summarize(frames: List<ByteArray>, hz: Double = SAMPLE_RATE_HZ): NightSummary? {
        val night = OSAWaveform.dominantSessionFrames(frames)
        if (night.isEmpty()) return null
        val ch = OSAWaveform.channels(night)
        return summarize(ch[0], ch[1], ch[2], hz)
    }

    /** Full night summary from decoded channels; [hz] sets the time axis of the event metrics. Null when no window is clean. */
    fun summarize(ir: List<Int>, red: List<Int>, green: List<Int>, hz: Double = SAMPLE_RATE_HZ): NightSummary? {
        val series = medianFilter(spo2Series(ir, red, green), 3) // ~45 s smoothing
        if (series.isEmpty()) return null
        val totalSamples = minOf(ir.size, red.size, green.size)
        val durationHours = totalSamples.toDouble() / hz / 3600
        val secPerWindow = WINDOW_STEP.toDouble() / hz
        val below90 = series.count { it < 90 }
        val odi = if (durationHours > 0) desaturationEvents(series).toDouble() / durationHours else 0.0
        return NightSummary(
            averageSpO2 = mean(series),
            minSpO2 = swiftSequenceMin(series),
            timeBelow90Seconds = below90.toDouble() * secPerWindow,
            odi = odi,
            validWindows = series.size,
            durationHours = durationHours,
        )
    }
}
