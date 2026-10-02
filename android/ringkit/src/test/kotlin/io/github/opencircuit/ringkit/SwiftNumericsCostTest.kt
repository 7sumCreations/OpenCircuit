package io.github.opencircuit.ringkit

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins two properties of the shared Swift-semantics helpers in `SwiftNumerics.kt`.
 *
 * 1. The stable sort compares positions without boxing them. Every median, trimmed mean and rank in
 *    the module goes through it (sleep, OSA, baselines, energy, headache ranks), so a comparator that
 *    boxes two `Int`s per comparison allocates megabytes per sort of a night-sized series. The bound
 *    below is the sort's own working arrays plus slack; a boxing comparator exceeds it many times over.
 *    The measurement only ever errs low (a JIT that removes the boxes passes), never high.
 * 2. `swiftSequenceMax` lives beside `swiftSequenceMin` and keeps Swift's `Sequence.max()` semantics.
 */
class SwiftNumericsCostTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun `sorting positions allocates only its working arrays`() {
        val n = 20_000
        // Deterministic, unsorted, with ties: a fixed linear congruence over 0 until 997.
        val values = DoubleArray(n) { ((it.toLong() * 7_919L + 13L) % 997L).toDouble() }
        swiftSortedIndices(DoubleArray(64) { (63 - it).toDouble() }) // load the classes outside the window

        val before = threads.currentThreadAllocatedBytes
        val order = swiftSortedIndices(values)
        val allocated = threads.currentThreadAllocatedBytes - before

        for (k in 1 until n) assertTrue(values[order[k - 1]] <= values[order[k]], "sorted at $k")
        // order (4n) + merge buffer (2n) + the run list: about 120 KB at n = 20 000.
        assertTrue(allocated < 400_000L, "sorting $n positions allocated $allocated bytes")
    }

    @Test
    fun `swiftSequenceMax keeps the first element unless a later one compares greater`() {
        assertEquals(3.0, swiftSequenceMax(listOf(1.0, 3.0, 2.0)))
        assertTrue(swiftSequenceMax(listOf(Double.NaN, 5.0)).isNaN(), "a NaN first element is kept")
        assertEquals(5.0, swiftSequenceMax(listOf(1.0, Double.NaN, 5.0)), "a later NaN never displaces a number")
        assertEquals((-0.0).toRawBits(), swiftSequenceMax(listOf(-0.0, 0.0)).toRawBits(), "an equal later value does not replace")
    }
}
