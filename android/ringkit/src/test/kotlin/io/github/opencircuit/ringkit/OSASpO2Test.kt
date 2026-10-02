package io.github.opencircuit.ringkit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OSA dense-PPG SpO₂ analyzer: `0x48` frame decode, dedupe and order, the session filter, the
 * Goertzel magnitude, the ratio-of-ratios and calibration, and the event metrics. Golden vectors
 * exported upstream from the validated desktop pipeline; the two `0x48` frames are real captures
 * that upstream's test publishes verbatim.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/OSASpO2Tests.swift (@ b1c2fdd)
 * — all 13 tests.
 */
class OSASpO2Test {

    // :18-30 — real 0x48 frames (opcode 0x48 + 196 B). frame0: counter 88940, cursor 0x0c43adeb.
    private val frame0 = "48" +
        "c100015b6c0c43adeb004650005b056740046446042c12056bb90465dd042e880555c70462d1042c8c05" +
        "4ce504636a042d4a054a1d0463a7042e1d054a8f04645e042f40054d0c0465580430b6054dad04640404" +
        "3047052b09045fe0042c08052b63045fe8042c6d5c052a78045fb0042c96052eb704606a042dcc05324a" +
        "0460e9042f7305164f045c03042b240517c6045abc042a9c05196f045a27042a92052014045ae3042bd6" +
        "052774045c0e042dc5051726045829042aad05179e0456580428c3fd"
    private val frame1 = "48" +
        "c100015b580c43adeb00465fa05e051ef80455ee04291405282f04563c042a0105334b0456f4042b5b05" +
        "28530452150426f0052f8704506e0425d20536d4044fa50425be054005044fcc0426f80545c2044e0a04" +
        "26db05397b0449090423740540670447fe0424e95d05464c0447f70426a1054dbc0448e0042904053d3a" +
        "0443f104259a0538a904416e0424e505399204419a0425f1053d5c04438c0427a80540e0044517042926" +
        "05254f0440340423710521d7043f0f04229305214d043ead0422755b"

    // MARK: frame decode (GOLDEN 1)

    @Test
    fun frameDecodeChannels() { // :34-40
        val ch = OSAWaveform.channels(listOf(hex(frame0)))
        assertEquals(20, ch[0].size) // 20 samples/channel/frame
        assertEquals(listOf(354112, 355257, 349639, 347365, 346653, 346767), ch[0].take(6)) // IR
        assertEquals(listOf(287814, 288221, 287441, 287594, 287655, 287838), ch[1].take(6)) // Red
        assertEquals(listOf(273426, 274056, 273548, 273738, 273949, 274240), ch[2].take(6)) // Green
    }

    @Test
    fun dedupeByCounterAndChronologicalOrder() { // :42-48
        // frame0 counter 88940 > frame1 counter 88920; chronological = descending counter, so
        // frame0's samples come first. The duplicate frame0 is dropped.
        val ch = OSAWaveform.channels(listOf(hex(frame0), hex(frame1), hex(frame0)))
        assertEquals(40, ch[0].size, "two unique frames × 20 samples/ch (dup dropped)")
        assertEquals(354112, ch[0][0], "higher counter (earlier) first")
    }

    @Test
    fun rejectsWrongOpcodeAndShortFrames() { // :50-54
        assertTrue(OSAWaveform.channels(listOf(hex("4c00"))).all { it.isEmpty() })
        val short = hex(frame0).copyOf(hex(frame0).size - 50)
        assertTrue(OSAWaveform.channels(listOf(short)).all { it.isEmpty() })
    }

    @Test
    fun sessionCursor() { // :56-58
        assertEquals(0x0c43adebL, OSAWaveform.sessionCursor(hex(frame0)))
    }

    @Test
    fun dominantSessionFilterIsolatesOneNight() { // :60-67
        // frame0 + frame1 share cursor 0x0c43adeb; forge one frame of a different night.
        val other = hex(frame0)
        other[6] = 0x0c; other[7] = 0x44; other[8] = 0xf9.toByte(); other[9] = 0x2a // cursor 0x0c44f92a
        val kept = OSAWaveform.dominantSessionFrames(listOf(hex(frame0), hex(frame1), other))
        assertEquals(2, kept.size, "modal cursor 0x0c43adeb wins; the lone other-night frame is dropped")
        assertTrue(kept.all { OSAWaveform.sessionCursor(it) == 0x0c43adebL })
    }

    @Test
    fun summarizeFramesInsufficientReturnsNil() { // :69-73
        assertNull(OSASpO2.summarize(frames = emptyList()))
        // two frames = 40 samples/ch < one 128-sample window → no series → nil
        assertNull(OSASpO2.summarize(frames = listOf(hex(frame0), hex(frame1))))
    }

    // MARK: Goertzel (GOLDEN 2)

    @Test
    fun goertzelMatchesDesktop() { // :77-81
        // Python: A=1000, f=0.2, N=128 → goertzel_mag = 1003.8800
        val sig = List(128) { 1000.0 * cos(2 * PI * 0.2 * it) }
        assertEquals(1003.88, OSASpO2.goertzelMagnitude(sig, 0.2), 0.05)
    }

    @Test
    fun goertzelZeroOnFlat() { // :83-86
        assertEquals(0.0, OSASpO2.goertzelMagnitude(List(128) { 500.0 }, 0.2), 1e-6)
    }

    // MARK: ratio-of-ratios + calibration (GOLDEN 3)

    @Test
    fun windowRatioAnalytic() { // :90-99
        // R = (AC_red/DC_red)/(AC_ir/DC_ir). Leakage at the locked fstar cancels in the ratio, so R
        // is exact: (120/8000)/(100/10000) = 1.5.
        val f = OSASpO2.FREQS[20]
        val ir = List(128) { 10000.0 + 100 * cos(2 * PI * f * it) }
        val red = List(128) { 8000.0 + 120 * cos(2 * PI * f * it) }
        val grn = List(128) { 5000.0 + 200 * cos(2 * PI * f * it) }
        val r = assertNotNull(OSASpO2.windowRatio(ir, red, grn))
        assertEquals(1.5, r, 0.01)
    }

    @Test
    fun calibrationCurve() { // :101-106
        // Desktop golden: R=0.63281 → 104.91 - 15.18*0.63281 = 95.304
        assertEquals(95.304, OSASpO2.spo2FromRatio(0.63281), 0.005)
        assertEquals(82.14, OSASpO2.spo2FromRatio(1.5), 0.005)
        assertEquals(100.0, OSASpO2.spo2FromRatio(0.2), 1e-9, "clamped ≤ 100")
    }

    // MARK: metrics

    @Test
    fun desaturationEventCount() { // :110-116
        // flat 97 with two clean dips to 85 → 2 events
        val s = MutableList(60) { 97.0 }
        for (i in 10 until 13) s[i] = 85.0
        for (i in 40 until 43) s[i] = 85.0
        assertEquals(2, OSASpO2.desaturationEvents(s))
    }

    @Test
    fun medianFilterRejectsSpike() { // :118-123
        val s = MutableList(11) { 96.0 }
        s[5] = 70.0 // single-window artifact spike
        val f = OSASpO2.medianFilter(s, 3)
        assertEquals(96.0, f[5], "median filter removes the lone spike")
    }

    @Test
    fun summarizeOnCleanDesaturatedSignal() { // :125-136
        // Constant R=1.5 signal → every window SpO2 = 82.14; all windows pass the gates
        // (PI_ir = 100/10000 = 1% > 0.15%, high SNR clean pulse).
        val f = OSASpO2.FREQS[20]
        val n = 400
        val ir = List(n) { (10000.0 + 100 * cos(2 * PI * f * it)).toInt() }
        val red = List(n) { (8000.0 + 120 * cos(2 * PI * f * it)).toInt() }
        val grn = List(n) { (5000.0 + 200 * cos(2 * PI * f * it)).toInt() }
        val summary = assertNotNull(OSASpO2.summarize(ir, red, grn))
        assertEquals(82.14, summary.averageSpO2, 0.2)
        assertTrue(summary.validWindows > 0)
    }
}
