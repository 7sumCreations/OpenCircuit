package io.github.opencircuit.ringkit

// Swift standard-library numeric semantics the port must reproduce exactly. Kotlin's own `minOf`,
// `maxOf`, `Math.round` and `sorted()` differ from Swift's on NaN, on -0.0 and on rounding ties,
// and those differences change upstream outputs on inputs the ported code can reach. One home for
// each, so every port calls the same rule.

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/** Swift's `min(x, y)`: `y < x ? y : x` (a NaN `x` is returned; a NaN `y` is ignored). */
internal fun swiftMin(x: Double, y: Double): Double = if (y < x) y else x

/** Swift's `max(x, y)`: `y >= x ? y : x` (a NaN `x` is returned; a NaN `y` is ignored). */
internal fun swiftMax(x: Double, y: Double): Double = if (y >= x) y else x

/** Swift's `Sequence.min()` over a non-empty list: the first element, replaced by any later one that compares smaller. */
internal fun swiftSequenceMin(xs: List<Double>): Double {
    var result = xs[0]
    for (k in 1 until xs.size) if (xs[k] < result) result = xs[k]
    return result
}

/** Swift's `rounded()` (to nearest, ties away from zero). */
internal fun roundHalfAwayFromZero(x: Double): Double {
    val t = truncate(x)
    return if (abs(x - t) >= 0.5) t + sign(x) else t
}

/**
 * [values] sorted exactly as Swift's `sorted()` sorts `[Double]`: the standard library's stable
 * sort driven by `<`. For values without NaN this equals any stable ascending sort in which -0.0
 * and 0.0 compare equal (so they keep their input order — Kotlin's `sorted()` puts -0.0 first).
 * With NaN, which `<` never orders, the result depends on the algorithm, so the algorithm is
 * reproduced: insertion sort up to 20 values; above that, natural runs (a strictly descending run
 * reversed) extended to the minimum run length by insertion, merged under the same run-stack rules
 * and with the same merge direction and tie preference.
 */
internal fun swiftSorted(values: List<Double>): DoubleArray {
    val a = values.toDoubleArray()
    SwiftStableSort.sort(a)
    return a
}

/**
 * Foundation's `String(format: "%.Nf", v)` — or `"%+.Nf"` when [forceSign] — with N = [fractionDigits],
 * reproduced character for character (measured on the pinned build). The one home for fixed-decimal
 * text: Java's `String.format` rounds half up and reads the default locale, and this does neither.
 *
 * - The exact binary value is rounded ties to even (0.25 → "0.2", 2.5 → "2"; 0.35 is below its tie,
 *   so "0.3"), in ASCII digits with a "." separator, the whole decimal expansion for large values.
 * - The sign comes from the sign bit: −0.0, and a negative value that rounds to zero, print "-0.0";
 *   [forceSign] puts "+" on everything else. NaN prints "nan" whatever its sign (never signed);
 *   infinities print "inf" / "-inf" ("+inf" when forced).
 * - A negative [fractionDigits] makes upstream's format "%.-Nf", which Foundation reads as no
 *   decimals, left-justified in a field N characters wide.
 * - Foundation keeps only the first 510 characters of the number. It crashes once |N| reaches
 *   2^31 − 512; here every width gets the same 510-character text, and digits past
 *   [SWIFT_FIXED_EXACT_DIGITS] are never computed (a double's exact expansion has at most 1 074
 *   decimals), so a huge N allocates nothing extra.
 */
internal fun swiftFixed(v: Double, fractionDigits: Int, forceSign: Boolean = false): String {
    val precision = if (fractionDigits >= 0) minOf(fractionDigits, SWIFT_FIXED_EXACT_DIGITS) else 0
    val width = if (fractionDigits >= 0) 0L else -fractionDigits.toLong()
    val negative = v.toRawBits() < 0
    val sign = if (negative) "-" else if (forceSign) "+" else ""
    val text = when {
        v.isNaN() -> "nan"
        v.isInfinite() -> sign + "inf"
        else -> sign + BigDecimal(v).abs().setScale(precision, RoundingMode.HALF_EVEN).toPlainString()
    }
    val padded = if (text.length < width) text.padEnd(minOf(width, SWIFT_FIXED_MAX_LENGTH.toLong()).toInt()) else text
    return if (padded.length > SWIFT_FIXED_MAX_LENGTH) padded.substring(0, SWIFT_FIXED_MAX_LENGTH) else padded
}

/** The most characters Foundation writes for one `%f` conversion (measured). */
internal const val SWIFT_FIXED_MAX_LENGTH = 510

/** Decimals [swiftFixed] computes at most: past a double's 1 074-decimal expansion, and past [SWIFT_FIXED_MAX_LENGTH]. */
internal const val SWIFT_FIXED_EXACT_DIGITS = 1_100

private object SwiftStableSort {

    fun sort(a: DoubleArray) {
        val count = a.size
        if (count <= 1) return
        if (count <= 20) {
            insertionSort(a, 0, count, 1)
            return
        }
        val minRun = minimumMergeRunLength(count)
        val buffer = DoubleArray(count / 2 + 1)
        val runs = ArrayList<IntArray>() // each [lo, hi)
        var start = 0
        while (start < count) {
            var end = nextRunEnd(a, start)
            if (end < 0) { // a strictly descending run, reported negated
                end = -end
                a.reverse(start, end)
            }
            if (end < count && end - start < minRun) {
                val newEnd = minOf(count, start + minRun)
                insertionSort(a, start, newEnd, end)
                end = newEnd
            }
            runs += intArrayOf(start, end)
            mergeTopRuns(runs, a, buffer)
            start = end
        }
        while (runs.size > 1) mergeRuns(runs, runs.size - 1, a, buffer)
    }

    /** Swift's `_minimumMergeRunLength`: the count itself below 64, else 32…64. */
    private fun minimumMergeRunLength(c: Int): Int {
        val bitsToUse = 6
        if (c < (1 shl bitsToUse)) return c
        val offset = (Long.SIZE_BITS - bitsToUse) - java.lang.Long.numberOfLeadingZeros(c.toLong())
        val mask = (1 shl offset) - 1
        return (c shr offset) + if (c and mask == 0) 0 else 1
    }

    /**
     * End of the natural run starting at [start]: ascending runs continue while the next value is
     * not `<` the previous one; strictly descending runs while it is. A descending run's end is
     * returned negated.
     */
    private fun nextRunEnd(a: DoubleArray, start: Int): Int {
        var previous = start
        var current = start + 1
        if (current >= a.size) return current
        val isDescending = a[current] < a[previous]
        do {
            previous = current
            current++
        } while (current < a.size && isDescending == (a[current] < a[previous]))
        return if (isDescending) -current else current
    }

    /** Insertion sort of `[lo, hi)` whose prefix `[lo, sortedEnd)` is already sorted; moves left only while strictly `<`. */
    private fun insertionSort(a: DoubleArray, lo: Int, hi: Int, sortedEnd: Int) {
        for (i in sortedEnd until hi) {
            var j = i
            while (j > lo && a[j] < a[j - 1]) {
                val t = a[j]; a[j] = a[j - 1]; a[j - 1] = t
                j--
            }
        }
    }

    private fun size(r: IntArray): Int = r[1] - r[0]

    /** Swift's `_mergeTopRuns`: restore W > X + Y, X > Y + Z, Y > Z on the top of the run stack. */
    private fun mergeTopRuns(runs: ArrayList<IntArray>, a: DoubleArray, buffer: DoubleArray) {
        while (runs.size > 1) {
            var lastIndex = runs.size - 1
            if (lastIndex >= 3 && size(runs[lastIndex - 3]) <= size(runs[lastIndex - 2]) + size(runs[lastIndex - 1])) {
                if (size(runs[lastIndex - 2]) < size(runs[lastIndex])) lastIndex -= 1
            } else if (lastIndex >= 2 && size(runs[lastIndex - 2]) <= size(runs[lastIndex - 1]) + size(runs[lastIndex])) {
                if (size(runs[lastIndex - 2]) < size(runs[lastIndex])) lastIndex -= 1
            } else if (size(runs[lastIndex - 1]) <= size(runs[lastIndex])) {
                // merge the top two
            } else {
                break
            }
            mergeRuns(runs, lastIndex, a, buffer)
        }
    }

    private fun mergeRuns(runs: ArrayList<IntArray>, i: Int, a: DoubleArray, buffer: DoubleArray) {
        val low = runs[i - 1][0]
        val mid = runs[i][0]
        val high = runs[i][1]
        merge(a, low, mid, high, buffer)
        runs[i - 1] = intArrayOf(low, high)
        runs.removeAt(i)
    }

    /**
     * Swift's `_merge`: buffer the SHORTER side (the lower one when strictly shorter) and merge
     * forward (taking the upper value only when it is `<` the buffered one) or backward (taking the
     * lower value only when the buffered one is `<` it); whatever is left in the buffer is moved
     * back at the end.
     */
    private fun merge(a: DoubleArray, low: Int, mid: Int, high: Int, buffer: DoubleArray) {
        val lowCount = mid - low
        val highCount = high - mid
        var destLow = low
        var bufferLow = 0
        var bufferHigh: Int
        if (lowCount < highCount) {
            a.copyInto(buffer, 0, low, mid)
            bufferHigh = lowCount
            var srcLow = mid
            while (bufferLow < bufferHigh && srcLow < high) {
                if (a[srcLow] < buffer[bufferLow]) {
                    a[destLow] = a[srcLow]; srcLow++
                } else {
                    a[destLow] = buffer[bufferLow]; bufferLow++
                }
                destLow++
            }
        } else {
            a.copyInto(buffer, 0, mid, high)
            bufferHigh = highCount
            var destHigh = high
            var srcHigh = mid
            destLow = mid
            while (bufferHigh > bufferLow && srcHigh > low) {
                destHigh--
                if (buffer[bufferHigh - 1] < a[srcHigh - 1]) {
                    srcHigh--
                    a[destHigh] = a[srcHigh]
                    destLow--
                } else {
                    bufferHigh--
                    a[destHigh] = buffer[bufferHigh]
                }
            }
        }
        for (k in bufferLow until bufferHigh) a[destLow + (k - bufferLow)] = buffer[k]
    }
}
