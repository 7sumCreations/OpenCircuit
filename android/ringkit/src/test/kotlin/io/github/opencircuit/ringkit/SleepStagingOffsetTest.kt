package io.github.opencircuit.ringkit

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Port of upstream SleepStagingOffsetTests.swift (@ b1c2fdd), all 18 tests: the point-of-no-return
 * OFFSET pass (`markPointOfNoReturnOffset`) and its derived margin.
 *
 * The unit cases drive the pass DIRECTLY rather than through a synthetic night, deliberately: a
 * synthetic record sequence carries a CONSTANT motion byte, which de-floors to "still" everywhere, so
 * a fixture built to look "awake" silently produces sleep and the assertion goes vacuous. Feeding the
 * smoothed-HR array straight in keeps every case honest about which signal is under test. The
 * `classify` cases at the bottom cover the wiring, including one that fails if the call site is
 * removed. Upstream's `inout [Bool]` is a caller-owned `BooleanArray` here. Every literal is typed
 * from upstream.
 */
class SleepStagingOffsetTest {

    private val floor = 50.0

    private fun asleepMask(n: Int): BooleanArray = BooleanArray(n)

    /** Flat sleeping HR with a sustained rise over the last [tail] epochs. */
    private fun hrRisingAtEnd(n: Int, tail: Int, sleepHR: Double = 52.0, wakeHR: Double = 62.0): List<Double> =
        (0 until n).map { if (it >= n - tail) wakeHR else sleepHR }

    private fun secs(d: Duration): Double = d.seconds + d.nano / 1e9

    // The derived margin

    @Test
    fun testMarginIsDerivedFromTheNightsOwnSpread() {
        // floor 50, median 60 -> spread 10 -> 0.5 x 10 = 5 bpm.
        val hr = (0 until 100).map { (50 + (it / 10)).toDouble() }
        val m = SleepStaging.resolvedOffsetMargin(hr, 50.0, SleepStaging.Tuning.DEFAULT)
        assertEquals(maxOf(2.0, 0.5 * (SleepStaging.percentileForTesting(hr, 0.50) - 50)), m, 0.001)
        assertTrue(m > 0)
    }

    /** A person with a WIDER sleeping-HR spread must get a wider margin — the whole point of deriving it. */
    @Test
    fun testWiderSpreadYieldsWiderMargin() {
        val tight = List(50) { 52.0 } + List(50) { 54.0 }
        val wide = List(50) { 52.0 } + List(50) { 78.0 }
        val mTight = SleepStaging.resolvedOffsetMargin(tight, 50.0, SleepStaging.Tuning.DEFAULT)
        val mWide = SleepStaging.resolvedOffsetMargin(wide, 50.0, SleepStaging.Tuning.DEFAULT)
        assertTrue(mWide > mTight)
    }

    /** A near-flat night must not derive a hair-trigger threshold. */
    @Test
    fun testFlatNightIsFlooredAtTheQuantisationMargin() {
        val flat = List(100) { 50.0 }
        val m = SleepStaging.resolvedOffsetMargin(flat, 50.0, SleepStaging.Tuning.DEFAULT)
        assertEquals(SleepStaging.Tuning.DEFAULT.offsetNoReturnMinMarginBPM, m)
        assertTrue(m > 0, "the floor must keep the pass safe, not disable it")
    }

    @Test
    fun testFractionZeroDisablesTheDerivation() {
        val t = SleepStaging.Tuning(offsetNoReturnSpreadFraction = 0.0)
        assertEquals(0.0, SleepStaging.resolvedOffsetMargin(listOf(50.0, 60.0, 70.0), 50.0, t))
    }

    @Test
    fun testEmptyHRDisablesTheDerivation() {
        assertEquals(0.0, SleepStaging.resolvedOffsetMargin(emptyList(), 50.0, SleepStaging.Tuning.DEFAULT))
    }

    // What the pass does

    @Test
    fun testMarksTrailingRunThatNeverReturnsToFloor() {
        val awake = asleepMask(60)
        SleepStaging.markPointOfNoReturnOffset(
            awake, smHR = hrRisingAtEnd(n = 60, tail = 20), floor = floor, margin = 4.0, tuning = SleepStaging.Tuning.DEFAULT,
        )
        assertEquals(40, awake.indexOfFirst { it }, "wake should start where the rise begins")
        assertTrue((40 until 60).all { awake[it] })
        assertFalse((0 until 40).any { awake[it] })
    }

    @Test
    fun testNonPositiveMarginIsANoOp() {
        val awake = asleepMask(60)
        val before = awake.copyOf()
        SleepStaging.markPointOfNoReturnOffset(
            awake, smHR = hrRisingAtEnd(n = 60, tail = 20), floor = floor, margin = 0.0, tuning = SleepStaging.Tuning.DEFAULT,
        )
        assertEquals(before.toList(), awake.toList())
    }

    /** The whole point of the pass: a bump that SETTLES BACK is not final wake, however high. */
    @Test
    fun testIgnoresInteriorBumpThatSettlesBack() {
        val hr = MutableList(60) { 52.0 }
        for (i in 20 until 26) hr[i] = 75.0
        val awake = asleepMask(60)
        SleepStaging.markPointOfNoReturnOffset(awake, smHR = hr, floor = floor, margin = 4.0, tuning = SleepStaging.Tuning.DEFAULT)
        assertFalse(awake.contains(true), "a bump that returns to the floor is not final wake")
    }

    @Test
    fun testMarkedRegionIsAlwaysASuffix() {
        val hr = MutableList(60) { 52.0 }
        for (i in 20 until 26) hr[i] = 75.0 // interior bump
        for (i in 45 until 60) hr[i] = 62.0 // real final wake
        val awake = asleepMask(60)
        SleepStaging.markPointOfNoReturnOffset(awake, smHR = hr, floor = floor, margin = 4.0, tuning = SleepStaging.Tuning.DEFAULT)
        assertEquals(45, awake.indexOfFirst { it })
        assertFalse((0 until 45).any { awake[it] }, "the interior bump must stay asleep")
    }

    // Safety properties

    @Test
    fun testCannotReachTheHeadOnAUniformlyElevatedNight() {
        val awake = asleepMask(60)
        SleepStaging.markPointOfNoReturnOffset(
            awake, smHR = List(60) { 62.0 }, floor = floor, margin = 4.0, tuning = SleepStaging.Tuning.DEFAULT,
        )
        assertFalse(awake.contains(true))
    }

    /** PRECEDENCE: must not overturn a rescued second bout / vitals-softened morning. */
    @Test
    fun testRefusesToStartBeforeARescuedRegion() {
        val awake = asleepMask(60)
        val hr = MutableList(60) { 52.0 }
        for (i in 30 until 60) hr[i] = 62.0
        SleepStaging.markPointOfNoReturnOffset(
            awake, smHR = hr, floor = floor, margin = 4.0, notBefore = 44, tuning = SleepStaging.Tuning.DEFAULT,
        )
        assertFalse((0 until 45).any { awake[it] }, "must not reclaim epochs an earlier pass already judged asleep")
    }

    /** SURVIVAL: never trim a night down to a token fragment. */
    @Test
    fun testRevertsWhenNoConsolidatedSleepWouldSurvive() {
        val t = SleepStaging.Tuning.DEFAULT
        val hr = MutableList(60) { 62.0 }
        for (i in 0 until (t.minConsolidatedSleepEpochs - 1)) hr[i] = 52.0
        val awake = asleepMask(60)
        val before = awake.copyOf()
        SleepStaging.markPointOfNoReturnOffset(awake, smHR = hr, floor = floor, margin = 4.0, tuning = t)
        assertEquals(before.toList(), awake.toList(), "must revert rather than leave a token sleep fragment")
    }

    @Test
    fun testNoOpOnEmptyOrMismatchedInput() {
        val empty = BooleanArray(0)
        SleepStaging.markPointOfNoReturnOffset(empty, smHR = emptyList(), floor = floor, margin = 4.0, tuning = SleepStaging.Tuning.DEFAULT)
        assertTrue(empty.isEmpty())
        val awake = asleepMask(10)
        val before = awake.copyOf()
        SleepStaging.markPointOfNoReturnOffset(
            awake, smHR = listOf(52.0, 52.0), floor = floor, margin = 4.0, tuning = SleepStaging.Tuning.DEFAULT,
        )
        assertEquals(before.toList(), awake.toList(), "length mismatch must be a no-op, not a crash")
    }

    @Test
    fun testWideMarginDegradesToNoOp() {
        val awake = asleepMask(60)
        val before = awake.copyOf()
        SleepStaging.markPointOfNoReturnOffset(
            awake, smHR = hrRisingAtEnd(n = 60, tail = 20), floor = floor, margin = 40.0, tuning = SleepStaging.Tuning.DEFAULT,
        )
        assertEquals(before.toList(), awake.toList())
    }

    // Fixtures

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
    }

    private fun vrec(counter: Long, hr: Int, hrv: Int = 55): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = 1
        return BulkRecord.of(b)!!
    }

    private fun arec(counter: Long): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[8] = 0x12
        for (k in 0 until 5) b[10 + k] = 0x14
        return BulkRecord.of(b)!!
    }

    /**
     * A night that sleeps flat then rises quietly at the end AND stops emitting sleep vitals there —
     * a real wake, not a terminal REM period. The vitals thinning matters: the pass deliberately
     * refuses to cut without it (the terminal-REM guard).
     */
    private fun quietMorningRiseNight(epochs: Int = 160, riseAt: Int = 140, sleepHR: Int = 54, wakeHR: Int = 64): List<BulkRecord> {
        val recs = mutableListOf<BulkRecord>()
        var c = 1_000_000L
        for (i in 0 until epochs) {
            val awakeTail = i >= riseAt
            // Sleeping epochs carry HRV on ~half the epochs (the real sleepV/activity interleave);
            // the awake tail carries none, which is what true wake looks like on the wire.
            val hrv = if (awakeTail) 0 else (if (i % 2 == 0) 55 else 0)
            recs += vrec(c, hr = if (awakeTail) wakeHR else sleepHR, hrv = hrv)
            c += BulkRecord.EPOCH_SECONDS.toLong()
        }
        return recs
    }

    // Integration through `classify`

    /** PINS THE CALL SITE — the default is ENABLED, so this compares default against fraction 0. */
    @Test
    fun testEnabledByDefaultMovesTheStagedWindowVersusDisabled() {
        val recs = quietMorningRiseNight()
        val off = SleepStaging.classify(recs, tuning = SleepStaging.Tuning(offsetNoReturnSpreadFraction = 0.0))
        val on = SleepStaging.classify(recs) // default = enabled
        val offWake = SleepStaging.sleepWindow(off)?.wake
        val onWake = SleepStaging.sleepWindow(on)?.wake
        if (offWake == null || onWake == null) fail("both configurations must still stage a night")
        assertTrue(
            onWake.isBefore(offWake),
            "the DEFAULT must pull final wake earlier than the disabled config — " +
                "if this fails the pass is not wired in, or the default was reverted to off",
        )
        assertEquals(
            SleepStaging.sleepWindow(off)?.onset, SleepStaging.sleepWindow(on)?.onset,
            "the offset pass must not disturb the onset",
        )
    }

    @Test
    fun testDefaultIsEnabled() {
        assertTrue(
            SleepStaging.Tuning.DEFAULT.offsetNoReturnSpreadFraction > 0,
            "the offset pass ships ON; disabling it is a deliberate act",
        )
    }

    /**
     * A mid-night wake followed by a SECOND BOUT at a HIGHER level than the night's floor never
     * "returns to the floor". Before the precedence guard the offset scan deleted the whole bout.
     */
    @Test
    fun testSecondBoutAtHigherHRSurvivesTheOffsetPass() {
        val step = BulkRecord.EPOCH_SECONDS.toLong()
        val recs = mutableListOf<BulkRecord>()
        var c = 0x0c22_0000L
        repeat(12) { recs += arec(c); c += step }
        repeat(96) { recs += vrec(c, hr = 50); c += step }
        repeat(4) { recs += arec(c); c += step }
        repeat(96) { recs += vrec(c, hr = 71); c += step }
        repeat(12) { recs += arec(c); c += step }

        val off = secs(SleepStaging.totalAsleep(SleepStaging.classify(recs, tuning = SleepStaging.Tuning(offsetNoReturnSpreadFraction = 0.0))))
        val on = secs(SleepStaging.totalAsleep(SleepStaging.classify(recs)))
        assertEquals(off, on, 1.0, "the offset pass must not undo rescueSecondBoutHRWake")
        assertTrue(off / 60 > 400, "sanity: the fixture really does stage two long bouts")
    }

    /**
     * The MAGNITUDE bound. On a night with NO wake at all, whose HR merely drifts up across the later
     * half, the unbounded pass destroyed 101.5 min at k=4 / 196.5 min at k=2 — `smHR` is a rolling
     * MEDIAN, so the drift never dips back under the p12 floor.
     */
    @Test
    fun testDriftingButSleepingNightIsNotAmputated() {
        val recs = mutableListOf<BulkRecord>()
        var c = 1_000_000L
        for (i in 0 until 200) {
            recs += vrec(c, hr = if (i < 100) 50 else 52 + (i - 100) / 20)
            c += BulkRecord.EPOCH_SECONDS.toLong()
        }
        val off = secs(SleepStaging.totalAsleep(SleepStaging.classify(recs, tuning = SleepStaging.Tuning(offsetNoReturnSpreadFraction = 0.0)))) / 60
        val on = secs(SleepStaging.totalAsleep(SleepStaging.classify(recs))) / 60
        // The scan is bounded to `onsetSearchEpochs` (48 epochs = 120 min) from the end.
        assertTrue(on > off - 121, "trimmed ${off - on} min — more than the search bound allows")
    }
}
