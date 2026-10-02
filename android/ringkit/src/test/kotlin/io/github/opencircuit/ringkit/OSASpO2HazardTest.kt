package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the OSA analyzer: `0x48` frames of all-0x00 / all-0xFF
 * bytes, longer than a frame, far-future and duplicate counters, tied session cursors; channels that
 * are empty, zero, flat, negative, mismatched in length or at `Int.MAX_VALUE`; a sample rate of 0,
 * negative, NaN or infinite; NaN and ±Inf reaching the Goertzel magnitude, the ratio, the
 * calibration and the median filter; and event-detector parameters on which upstream never returns.
 * Kept out of the upstream-port class so its count stays exact.
 *
 * Every expected value was measured on upstream's pinned Swift build with the same inputs, except
 * where a test says upstream does not return (it loops forever or traps) or does not decide (the
 * tied cursor).
 */
class OSASpO2HazardTest {

    private fun frame(fill: Int = 0x00): ByteArray = ByteArray(OSAWaveform.FRAME_LENGTH) { fill.toByte() }.also { it[0] = 0x48 }

    private fun signals(n: Int = 400): Triple<List<Int>, List<Int>, List<Int>> {
        val f = OSASpO2.FREQS[20]
        return Triple(
            List(n) { (10000.0 + 100 * cos(2 * PI * f * it)).toInt() },
            List(n) { (8000.0 + 120 * cos(2 * PI * f * it)).toInt() },
            List(n) { (5000.0 + 200 * cos(2 * PI * f * it)).toInt() },
        )
    }

    private fun fmt(xs: List<Double>): String = xs.joinToString(",") { if (it.isNaN()) "nan" else it.toString() }

    @Test
    fun framesOfExtremeBytesDecode() {
        val ff = frame(0xFF)
        val ch = OSAWaveform.channels(listOf(ff))
        assertEquals(listOf(20, 20, 20), ch.map { it.size })
        assertEquals(listOf(16_777_215, 16_777_215), ch[0].take(2), "24-bit samples read unsigned")
        assertEquals(0xFFFF_FFFFL, OSAWaveform.sessionCursor(ff), "the cursor reads unsigned")
        assertEquals(listOf(20, 20, 20), OSAWaveform.channels(listOf(ff + byteArrayOf(0))).map { it.size }, "a longer frame is accepted")
        assertEquals(listOf(0, 0, 0), OSAWaveform.channels(emptyList()).map { it.size })
        assertEquals(0, OSAWaveform.dominantSessionFrames(emptyList()).size)
        assertNull(OSAWaveform.sessionCursor(byteArrayOf(0x48)))
    }

    @Test
    fun counterOrderAndDuplicates() {
        val a = frame().also { it[17] = 0x01 }
        val z = frame().also { for (k in 2..5) it[k] = 0xFF.toByte(); it[17] = 0x02 }
        assertEquals(2, OSAWaveform.channels(listOf(a, z))[0][0], "counter 0xFFFFFFFF sorts first (descending) whatever the arrival order")
        assertEquals(2, OSAWaveform.channels(listOf(z, a))[0][0])
        val a2 = frame().also { it[17] = 0x09 }
        assertEquals(9, OSAWaveform.channels(listOf(a2, a))[0][0], "the first frame of a counter wins")
    }

    /**
     * Upstream takes the max over a Swift Dictionary, whose order is seeded per process: with two
     * cursors tied, the measured pick was cursor 1 in 5 runs and cursor 2 in 7 of 12. The port picks
     * the cursor seen FIRST, deterministically.
     */
    @Test
    fun tiedSessionsKeepTheFirstSeenCursor() {
        val a = frame().also { it[9] = 1 }
        val b = frame().also { it[9] = 2; it[5] = 20 }
        assertEquals(listOf(1L), OSAWaveform.dominantSessionFrames(listOf(a, b)).map { OSAWaveform.sessionCursor(it) })
        assertEquals(listOf(2L), OSAWaveform.dominantSessionFrames(listOf(b, a)).map { OSAWaveform.sessionCursor(it) })
    }

    @Test
    fun summarizeHostileChannels() {
        val (ir, red, grn) = signals()
        val ref = assertNotNull(OSASpO2.summarize(ir, red, grn))
        assertEquals(82.12334713951563, ref.averageSpO2, 1e-9)
        assertEquals(82.12068625636596, ref.minSpO2, 1e-9)
        assertEquals(77.10843373493975, ref.timeBelow90Seconds, 1e-9)
        assertEquals(0.0, ref.odi)
        assertEquals(5, ref.validWindows)
        assertEquals(0.026773761713520746, ref.durationHours, 1e-15)

        assertNull(OSASpO2.summarize(emptyList(), emptyList(), emptyList()))
        assertNull(OSASpO2.summarize(List(400) { 0 }, List(400) { 0 }, grn), "zero DC → no window")
        assertNull(OSASpO2.summarize(List(400) { 0xFFFFFF }, List(400) { 0xFFFFFF }, List(400) { 0xFFFFFF }), "flat → no pulse")
        assertNull(OSASpO2.summarize(ir.map { -it }, red.map { -it }, grn), "negative DC → no window")
        assertNull(OSASpO2.summarize(List(400) { Int.MAX_VALUE }, List(400) { Int.MAX_VALUE }, grn), "flat at the top of the range → no pulse")

        val mismatch = assertNotNull(OSASpO2.summarize(ir, red.take(200), grn), "the shortest channel bounds the night")
        assertEquals(82.12124675513314, mismatch.averageSpO2, 1e-9)
        assertEquals(82.12124675513314, mismatch.minSpO2, 1e-9)
        assertEquals(30.8433734939759, mismatch.timeBelow90Seconds, 1e-9)
        assertEquals(2, mismatch.validWindows)
        assertEquals(0.013386880856760373, mismatch.durationHours, 1e-15)
    }

    @Test
    fun sampleRateEdgesKeepUpstreamsArithmetic() {
        val (ir, red, grn) = signals()
        fun s(hz: Double) = assertNotNull(OSASpO2.summarize(ir, red, grn, hz))
        for (hz in listOf(0.0, -4.15, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(82.12334713951563, s(hz).averageSpO2, 1e-9, "SpO2 values are rate-independent ($hz)")
            assertEquals(0.0, s(hz).odi, "odi is 0 unless the duration is positive ($hz)")
            assertEquals(5, s(hz).validWindows)
        }
        assertEquals(Double.POSITIVE_INFINITY, s(0.0).timeBelow90Seconds)
        assertEquals(Double.POSITIVE_INFINITY, s(0.0).durationHours)
        assertEquals(-77.10843373493975, s(-4.15).timeBelow90Seconds, 1e-9)
        assertEquals(-0.026773761713520746, s(-4.15).durationHours, 1e-15)
        assertTrue(s(Double.NaN).timeBelow90Seconds.isNaN())
        assertTrue(s(Double.NaN).durationHours.isNaN())
        assertEquals(0.0, s(Double.POSITIVE_INFINITY).timeBelow90Seconds)
        assertEquals(0.0, s(Double.POSITIVE_INFINITY).durationHours)
    }

    @Test
    fun nonFiniteValuesInTheSpectralSteps() {
        assertEquals(0.0, OSASpO2.goertzelMagnitude(emptyList(), 0.2))
        assertTrue(OSASpO2.goertzelMagnitude(listOf(Double.NaN, 1.0, 2.0), 0.2).isNaN())
        assertTrue(OSASpO2.goertzelMagnitude(listOf(1.0, 2.0, 3.0), Double.NaN).isNaN())
        assertNull(OSASpO2.windowRatio(emptyList(), emptyList(), emptyList()))
        // An all-NaN green band has no maximum to lock on; Swift's max(by:) keeps the first frequency.
        assertEquals(1.0, OSASpO2.windowRatio(listOf(1.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0), List(3) { Double.NaN }))
        // Swift's min(100, x) returns 100 when x is NaN.
        assertEquals(100.0, OSASpO2.spo2FromRatio(Double.NaN))
        assertEquals(100.0, OSASpO2.spo2FromRatio(Double.NEGATIVE_INFINITY))
        assertEquals(Double.NEGATIVE_INFINITY, OSASpO2.spo2FromRatio(Double.POSITIVE_INFINITY))
    }

    /** The median sorts exactly as Swift's stable sort does with `<`, so NaN and -0.0 land where they land upstream. */
    @Test
    fun medianFilterOnNanSignedZeroAndOddWindows() {
        assertEquals("nan,nan,70.0,96.0,96.0", fmt(OSASpO2.medianFilter(listOf(96.0, Double.NaN, 70.0, 96.0, 96.0), 3)))
        assertEquals("nan,70.0,83.0", fmt(OSASpO2.medianFilter(listOf(Double.NaN, 96.0, 70.0), 3)))
        assertEquals(listOf(1.5, 2.0, 3.0, 3.5), OSASpO2.medianFilter(listOf(1.0, 2.0, 3.0, 4.0), 2), "an even window")
        assertEquals(listOf(1.0, 2.0, 3.0), OSASpO2.medianFilter(listOf(1.0, 2.0, 3.0), 0))
        assertEquals(listOf(1.0, 2.0, 3.0), OSASpO2.medianFilter(listOf(1.0, 2.0, 3.0), -3))
        assertEquals(emptyList(), OSASpO2.medianFilter(emptyList(), 3))
        assertEquals(listOf(3.0, 3.0, 3.0), OSASpO2.medianFilter(listOf(5.0, 1.0, 3.0), Int.MAX_VALUE), "a huge window covers the series")
        assertEquals(
            listOf(1.0, -1.0, 1.0),
            OSASpO2.medianFilter(listOf(0.0, -0.0, 0.0), 3).map { if (1.0 / it < 0) -1.0 else 1.0 },
            "-0.0 and 0.0 keep their input order (stable)",
        )

        // 12 values, 7-wide windows (insertion sort).
        val d = MutableList(12) { ((it * 7) % 5).toDouble() }.also { it[6] = Double.NaN }
        assertEquals("1.5,2.0,1.5,2.0,3.0,4.0,nan,0.0,1.0,1.5,2.0,1.5", fmt(OSASpO2.medianFilter(d, 7)))
        // 30 values, 21-wide windows (one run extended by insertion).
        val w = MutableList(30) { 97.0 }.also { for (i in 0 until 30) if (i % 7 == 3) it[i] = Double.NaN; it[5] = 80.0; it[6] = 99.0 }
        assertEquals(
            "97.0,97.0,97.0,97.0,97.0,97.0,97.0,98.0,99.0,nan,nan,97.0,97.0,97.0,97.0,97.0,97.0,nan,97.0,97.0," +
                "97.0,97.0,97.0,97.0,97.0,97.0,97.0,97.0,nan,nan",
            fmt(OSASpO2.medianFilter(w, 20)),
        )
        val a = MutableList(30) { (100 - it).toDouble() }.also { it[4] = Double.NaN; it[17] = Double.NaN }
        assertEquals(
            "90.0,89.5,89.0,88.5,88.0,87.5,87.0,87.5,88.0,88.5,89.0,90.0,91.0,92.0,93.0,94.0,94.0,nan,72.0,72.0," +
                "72.5,73.0,73.5,74.0,74.5,75.0,75.5,76.0,76.5,76.0",
            fmt(OSASpO2.medianFilter(a, 20)),
        )
        // 50 values, windows of 21 to 41.
        val b = MutableList(50) { ((it * 37) % 23).toDouble() }.also { it[0] = Double.NaN; it[25] = Double.NaN; it[49] = Double.NaN }
        assertEquals(
            "11.0,11.5,11.0,10.5,11.0,11.5,12.0,12.5,13.0,13.5,14.0,14.0,14.0,14.5,15.0,15.5,16.0,16.5,17.0,17.5," +
                "18.0,19.0,20.0,21.0,22.0,nan,0.0,1.0,2.0,3.0,3.5,4.0,4.5,5.0,5.5,6.0,6.5,7.0,7.5,8.0," +
                "8.5,9.0,9.5,10.0,10.5,11.0,11.5,11.0,11.5,12.0",
            fmt(OSASpO2.medianFilter(b, 41)),
        )
        // 90 values, windows of 64 to 90 (merged runs).
        val c = MutableList(90) { ((it * 53) % 31).toDouble() }.also { it[3] = Double.NaN; it[60] = Double.NaN }
        assertEquals(
            "29.5,nan,nan,1.0,1.5,3.0,3.5,4.0,4.5,5.0,5.5,7.0,7.0,8.0,8.0,8.0,8.0,9.0,9.0,9.0," +
                "9.0,10.0,10.0,10.0,10.5,11.0,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5," +
                "10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5,10.5," +
                "10.5,10.5,10.5,10.5,11.0,11.5,12.0,12.0,12.0,12.0,12.0,12.0,12.0,12.0,13.0,12.5,12.0,12.5,13.0,12.5," +
                "13.0,13.5,13.0,13.0,13.0,13.0,13.0,13.5,14.0,14.0",
            fmt(OSASpO2.medianFilter(c, 127)),
        )
    }

    @Test
    fun desaturationEventsOnHostileSeries() {
        val s = MutableList(60) { 97.0 }.also { for (i in 10 until 13) it[i] = 85.0; it[40] = Double.NaN }
        assertEquals(1, OSASpO2.desaturationEvents(s), "a NaN epoch is never a dip")
        assertEquals(0, OSASpO2.desaturationEvents(emptyList()))
        assertEquals(0, OSASpO2.desaturationEvents(listOf(85.0)))
        assertEquals(0, OSASpO2.desaturationEvents(listOf(97.0, 85.0, 97.0), baselineWindow = 1), "no smoothing → the baseline IS the series")

        val u = MutableList(60) { 97.0 }.also { for (i in 10 until 13) it[i] = 96.5; it[30] = 80.0 }
        assertEquals(4, OSASpO2.desaturationEvents(u, dropPercent = 0.5, refractory = 1))
        assertEquals(59, OSASpO2.desaturationEvents(u, dropPercent = -3.0, refractory = 1))
        assertEquals(0, OSASpO2.desaturationEvents(u, baselineWindow = 0))
        assertEquals(1, OSASpO2.desaturationEvents(u, refractory = Int.MAX_VALUE - 100), "a huge refractory never overflows")

        val shallowTail = MutableList(60) { 97.0 }.also { for (i in 10 until 13) it[i] = 85.0; it[13] = 95.5 }
        assertEquals(1, OSASpO2.desaturationEvents(shallowTail, refractory = -1), "a negative refractory upstream survives counts as upstream")
    }

    /**
     * Parameters on which upstream's loop never advances (it runs forever) or steps before the start
     * (it traps on an out-of-range index). The port stops when the scan would revisit an index —
     * which, the scan being a function of its index alone, is exactly when upstream would never
     * return — and returns the dips counted until then. Every input upstream returns on is unchanged.
     * The timeout runs the test on its own thread, so a regression to upstream's busy loop FAILS here
     * instead of hanging the suite.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun desaturationParametersUpstreamNeverReturnsOnAreBounded() {
        val dip3 = MutableList(60) { 97.0 }.also { for (i in 10 until 13) it[i] = 85.0 }
        assertEquals(1, OSASpO2.desaturationEvents(dip3, refractory = -4), "upstream: endless loop")
        assertEquals(1, OSASpO2.desaturationEvents(dip3, refractory = -100), "upstream: index out of range")
        val dip4 = MutableList(60) { 97.0 }.also { for (i in 10 until 14) it[i] = 85.0 }
        assertEquals(2, OSASpO2.desaturationEvents(dip4, refractory = -1), "upstream: endless loop")
        val shallow = MutableList(60) { 97.0 }.also { for (i in 10 until 13) it[i] = 96.5 }
        assertEquals(1, OSASpO2.desaturationEvents(shallow, dropPercent = 0.5, refractory = 0), "upstream: endless loop")
    }
}
