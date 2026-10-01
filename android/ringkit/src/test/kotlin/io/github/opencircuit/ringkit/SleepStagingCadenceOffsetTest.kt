package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SleepStaging.CadenceStep
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Port of upstream SleepStagingCadenceOffsetTests.swift (@ b1c2fdd), all 28 tests: the SpO2-CADENCE
 * trailing-edge wake locator — `cadenceSteps` and `markCadenceWakeOffset`.
 *
 * The defect it fixes: when the primary motion channel is a flat placeholder and the morning HR rise
 * is small, every sleeper-side pass misses the wake, the staged night runs to the last record, and
 * the reported wake becomes `lastRecord + 120 s` — a function of when the user SYNCED.
 *
 * The unit cases drive the pass DIRECTLY, for the same reason the offset suite does: a synthetic
 * record sequence carries a constant motion byte that de-floors to "still" everywhere, so a fixture
 * built to look "awake" silently stages as sleep. The `classify` cases at the bottom cover the
 * wiring and go red if the call site is deleted. Upstream's `inout [Bool]` is a caller-owned
 * `BooleanArray` here. Every literal is typed from upstream.
 */
class SleepStagingCadenceOffsetTest {

    private val floor = 50.0
    private val def = SleepStaging.Tuning.DEFAULT

    private fun asleepMask(n: Int): BooleanArray = BooleanArray(n)

    /** Flat sleeping HR with a sustained rise over the last [tail] epochs — a rise that never returns. */
    private fun hrRisingAtEnd(n: Int, tail: Int, sleepHR: Double = 52.0, wakeHR: Double = 62.0): List<Double> =
        (0 until n).map { if (it >= n - tail) wakeHR else sleepHR }

    /** `alternating` for `[0, quietEnd]`, then [terminator] at `quietEnd + 1`, then `violation` after. */
    private fun cadence(n: Int, quietEnd: Int, terminator: CadenceStep = CadenceStep.VIOLATION): List<CadenceStep> {
        val out = MutableList(n) { CadenceStep.VIOLATION }
        out[0] = CadenceStep.UNKNOWN
        for (i in 1..quietEnd) if (i < n) out[i] = CadenceStep.ALTERNATING
        if (quietEnd + 1 < n) out[quietEnd + 1] = terminator
        return out
    }

    private fun secs(d: Duration): Double = d.seconds + d.nano / 1e9

    // `cadenceSteps`: reading the ring's SpO2 duty cycle off the wire

    private fun times(n: Int, step: Int = BulkRecord.EPOCH_SECONDS): List<Instant> =
        (0 until n).map { Instant.ofEpochSecond(1_000_000L + it * step) }

    private fun at(vararg offsets: Int): List<Instant> = offsets.map { Instant.ofEpochSecond(1_000_000L + it) }

    private fun alternatingLayouts(n: Int): List<BulkRecord.Layout> =
        (0 until n).map { if (it % 2 == 0) BulkRecord.Layout.SLEEP_VITALS else BulkRecord.Layout.ACTIVITY }

    private val sv = BulkRecord.Layout.SLEEP_VITALS
    private val act = BulkRecord.Layout.ACTIVITY

    @Test
    fun testPerfectAlternationIsAllAlternating() {
        val steps = SleepStaging.cadenceSteps(times(10), alternatingLayouts(10))
        assertEquals(CadenceStep.UNKNOWN, steps[0], "there is no step INTO the first row")
        assertTrue(steps.drop(1).all { it == CadenceStep.ALTERNATING })
    }

    @Test
    fun testSameTemplateTwiceIsAViolation() {
        val steps = SleepStaging.cadenceSteps(times(4), listOf(sv, act, act, sv))
        assertEquals(listOf(CadenceStep.UNKNOWN, CadenceStep.ALTERNATING, CadenceStep.VIOLATION, CadenceStep.ALTERNATING), steps)
    }

    /**
     * ONE missing epoch is bridged by PARITY: after an even number of steps the template returns to
     * itself, so `S … S` across 300 s is the alternation intact, not a break.
     */
    @Test
    fun testOneMissingEpochIsBridgedByParity() {
        val steps = SleepStaging.cadenceSteps(at(0, 150, 450), listOf(act, sv, sv))
        assertEquals(CadenceStep.ALTERNATING, steps[2], "S→(A dropped)→S is the cadence holding, not breaking")
    }

    @Test
    fun testOneMissingEpochWithTheWrongParityIsStillAViolation() {
        val steps = SleepStaging.cadenceSteps(at(0, 150, 450), listOf(act, sv, act))
        assertEquals(CadenceStep.VIOLATION, steps[2], "two steps must return to the SAME template")
    }

    /** More than one missing epoch carries NO information. */
    @Test
    fun testTwoMissingEpochsCarryNoInformation() {
        val steps = SleepStaging.cadenceSteps(at(0, 150, 600), listOf(act, sv, sv))
        assertEquals(CadenceStep.UNKNOWN, steps[2])
    }

    /** JITTER, not a hole: an exact `dt == 150` test silently suppresses genuine violations. */
    @Test
    fun testJitteredStepUnderOneAndAHalfEpochsIsStillOneEpoch() {
        val t = at(0, 221)
        assertEquals(
            CadenceStep.VIOLATION, SleepStaging.cadenceSteps(t, listOf(sv, sv))[1],
            "a 221 s step is ONE epoch — the violation must survive the jitter",
        )
        assertEquals(CadenceStep.ALTERNATING, SleepStaging.cadenceSteps(t, listOf(sv, act))[1])
    }

    /** An unworn epoch is outside the measurement program altogether — absence of evidence. */
    @Test
    fun testIdleEpochCarriesNoInformation() {
        val steps = SleepStaging.cadenceSteps(times(3), listOf(sv, BulkRecord.Layout.IDLE, sv))
        assertEquals(listOf(CadenceStep.UNKNOWN, CadenceStep.UNKNOWN, CadenceStep.UNKNOWN), steps)
    }

    @Test
    fun testMismatchedInputsProduceNothing() {
        assertTrue(SleepStaging.cadenceSteps(times(3), alternatingLayouts(2)).isEmpty())
        assertTrue(SleepStaging.cadenceSteps(emptyList(), emptyList()).isEmpty())
    }

    // `markCadenceWakeOffset`: what the locator does

    @Test
    fun testCutsAtTheEndOfTheLastQuietRun() {
        val awake = asleepMask(120)
        SleepStaging.markCadenceWakeOffset(
            awake, cadence(n = 120, quietEnd = 99), hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 4.0, tuning = def,
        )
        assertEquals(100, awake.indexOfFirst { it }, "wake is the epoch AFTER the last epoch of the trusted quiet run")
        assertTrue((100 until 120).all { awake[it] })
    }

    /** Suffix-only by construction — the pass may never punch a hole in the middle of a night. */
    @Test
    fun testMarkedRegionIsAlwaysASuffix() {
        val awake = asleepMask(120)
        SleepStaging.markCadenceWakeOffset(
            awake, cadence(n = 120, quietEnd = 99), hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 4.0, tuning = def,
        )
        val first = awake.indexOfFirst { it }
        if (first < 0) fail("expected a cut")
        assertTrue((first until 120).all { awake[it] })
        assertFalse((0 until first).any { awake[it] })
    }

    /** THE KILL SWITCH. 0 must be a total no-op, so a regression can always be turned off in one line. */
    @Test
    fun testZeroQuietEpochsIsANoOp() {
        val awake = asleepMask(120)
        val before = awake.copyOf()
        SleepStaging.markCadenceWakeOffset(
            awake, cadence(n = 120, quietEnd = 99), hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 4.0,
            tuning = SleepStaging.Tuning(cadenceWakeQuietEpochs = 0),
        )
        assertEquals(before.toList(), awake.toList())
    }

    @Test
    fun testNonPositiveMarginIsANoOp() {
        val awake = asleepMask(120)
        val before = awake.copyOf()
        SleepStaging.markCadenceWakeOffset(
            awake, cadence(n = 120, quietEnd = 99), hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 0.0, tuning = def,
        )
        assertEquals(before.toList(), awake.toList())
    }

    /**
     * THE GUARD THAT STOPS THE PASS RE-MANUFACTURING THE VERY ARTEFACT IT EXISTS TO REMOVE. A quiet run
     * still going when the capture stops has not been observed to END.
     */
    @Test
    fun testDeclinesWhenTheQuietRunReachesTheDataEdge() {
        val awake = asleepMask(120)
        val before = awake.copyOf()
        val cad = MutableList(120) { CadenceStep.ALTERNATING }
        cad[0] = CadenceStep.UNKNOWN
        SleepStaging.markCadenceWakeOffset(awake, cad, hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 4.0, tuning = def)
        assertEquals(before.toList(), awake.toList(), "an unterminated quiet run is not evidence of a wake")
    }

    /** A hole is absence of evidence, not evidence of a wake. */
    @Test
    fun testDeclinesWhenTheRunEndsAtAHoleRatherThanAViolation() {
        val awake = asleepMask(120)
        val before = awake.copyOf()
        SleepStaging.markCadenceWakeOffset(
            awake, cadence(n = 120, quietEnd = 99, terminator = CadenceStep.UNKNOWN), hrRisingAtEnd(n = 120, tail = 20),
            floor = floor, margin = 4.0, tuning = def,
        )
        assertEquals(before.toList(), awake.toList())
    }

    @Test
    fun testDeclinesWhenNoRunReachesTheQuietBar() {
        val awake = asleepMask(120)
        val before = awake.copyOf()
        // Alternation that never holds for more than 5 epochs anywhere.
        val cad = MutableList(120) { CadenceStep.ALTERNATING }
        for (i in 0 until 120 step 6) cad[i] = CadenceStep.VIOLATION
        SleepStaging.markCadenceWakeOffset(awake, cad, hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 4.0, tuning = def)
        assertEquals(before.toList(), awake.toList(), "the cadence never held for a plausible night")
    }

    /** LAST, not LONGEST. */
    @Test
    fun testTakesTheLastQualifyingRunNotTheLongest() {
        val awake = asleepMask(120)
        val cad = MutableList(120) { CadenceStep.ALTERNATING }
        cad[0] = CadenceStep.UNKNOWN
        cad[80] = CadenceStep.VIOLATION // long run  [0, 79]  (80 epochs)
        for (i in 100 until 120) cad[i] = CadenceStep.VIOLATION // short run [80, 99] (20) — the LAST at/above K,
        // and nothing qualifies after it
        SleepStaging.markCadenceWakeOffset(awake, cad, hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 4.0, tuning = def)
        assertEquals(100, awake.indexOfFirst { it }, "the LONGEST run ends at 79; taking it would delete two more hours of sleep")
    }

    /** The independent second witness: the body never settled back. */
    @Test
    fun testDeclinesWhenHRSettlesBackToTheFloorAfterTheCut() {
        val awake = asleepMask(120)
        val before = awake.copyOf()
        val hr = hrRisingAtEnd(n = 120, tail = 20).toMutableList()
        hr[110] = 51.0 // one dip back to the floor after the cut
        SleepStaging.markCadenceWakeOffset(awake, cadence(n = 120, quietEnd = 99), hr, floor = floor, margin = 4.0, tuning = def)
        assertEquals(before.toList(), awake.toList(), "a suffix that returns to the sleeping floor is not final wake")
    }

    /**
     * A pass that REMOVES sleep must not be able to commit a night down to a token fragment: every
     * asleep run is 10 epochs — enough for `onsetSustainEpochs` (6), too short for
     * `minConsolidatedSleepEpochs` (16).
     */
    @Test
    fun testDeclinesWhenNoConsolidatedSleepWouldSurvive() {
        val awake = asleepMask(120)
        for (i in 0 until 120) if ((i % 12) >= 10) awake[i] = true // 10 asleep, 2 awake, repeating
        val seeded = awake.copyOf()
        assertNotNull(SleepStaging.sleepSpanForTesting(seeded.toList(), 6), "fixture sanity: the pass must get past its own onset-span guard")
        assertNull(SleepStaging.sleepSpanForTesting(seeded.toList(), 16), "fixture sanity: no consolidated run exists to survive with")
        SleepStaging.markCadenceWakeOffset(
            awake, cadence(n = 120, quietEnd = 99), hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 4.0, tuning = def,
        )
        assertEquals(seeded.toList(), awake.toList(), "the cut must be reverted, not committed to a fragment")
    }

    /** `notBefore` — a rescued second bout must not be overturned by this pass. */
    @Test
    fun testCannotCutAtOrBeforeNotBefore() {
        val awake = asleepMask(120)
        val before = awake.copyOf()
        SleepStaging.markCadenceWakeOffset(
            awake, cadence(n = 120, quietEnd = 99), hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 4.0,
            notBefore = 105, tuning = def,
        )
        assertEquals(before.toList(), awake.toList())
    }

    /** The magnitude bound: no further back from the tail than `onsetSearchEpochs` (48 ≈ 2 h). */
    @Test
    fun testCannotReachFurtherBackThanTheOnsetSearchBound() {
        val awake = asleepMask(200)
        val before = awake.copyOf()
        // The quiet run ends at 99 — 100 epochs from the end, well outside the 48-epoch reach.
        SleepStaging.markCadenceWakeOffset(
            awake, cadence(n = 200, quietEnd = 99), hrRisingAtEnd(n = 200, tail = 100), floor = floor, margin = 4.0, tuning = def,
        )
        assertEquals(before.toList(), awake.toList())
    }

    /** REGIME PLAUSIBILITY: 10.8 h of unbroken cadence is a ring in continuous SpO2 mode, not a night. */
    @Test
    fun testDeclinesWhenTheQuietRunIsTooLongToBeANight() {
        val awake = asleepMask(300)
        val before = awake.copyOf()
        val cad = cadence(n = 300, quietEnd = 259) // 260 epochs ≈ 10.8 h
        val hr = hrRisingAtEnd(n = 300, tail = 40)
        SleepStaging.markCadenceWakeOffset(awake, cad, hr, floor = floor, margin = 4.0, tuning = def)
        assertEquals(before.toList(), awake.toList(), "10.8 h of unbroken cadence is a ring in continuous SpO2 mode")

        val relaxed = asleepMask(300)
        SleepStaging.markCadenceWakeOffset(
            relaxed, cad, hr, floor = floor, margin = 4.0, tuning = SleepStaging.Tuning(cadenceWakeMaxQuietEpochs = 300),
        )
        assertEquals(260, relaxed.indexOfFirst { it }, "sanity: only the plausibility bound was stopping it")
    }

    @Test
    fun testMismatchedArrayLengthsAreANoOp() {
        val awake = asleepMask(120)
        val before = awake.copyOf()
        SleepStaging.markCadenceWakeOffset(
            awake, cadence(n = 100, quietEnd = 80), hrRisingAtEnd(n = 120, tail = 20), floor = floor, margin = 4.0, tuning = def,
        )
        assertEquals(before.toList(), awake.toList())
    }

    // Fixtures

    private fun counterBytes(b: ByteArray, counter: Long) {
        b[0] = (counter shr 24).toByte(); b[1] = ((counter shr 16) and 0xFF).toByte()
        b[2] = ((counter shr 8) and 0xFF).toByte(); b[3] = (counter and 0xFF).toByte()
    }

    /** A still, sleep-vitals-bearing epoch (`SLEEP_VITALS`). */
    private fun vrec(counter: Long, hr: Int, hrv: Int = 55): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[5] = hrv.toByte(); b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = 1
        return BulkRecord.of(b)!!
    }

    /**
     * A STILL epoch on the `ACTIVITY` template — the other half of the ring's SpO2 duty cycle. Motion
     * is the baseline `1`: this record means "no SpO2 reading in this epoch", not "the wearer moved".
     * Wake is expressed as ELEVATED HR only.
     */
    private fun qrec(counter: Long, hr: Int): BulkRecord {
        val b = ByteArray(23)
        counterBytes(b, counter)
        b[4] = hr.toByte(); b[8] = 0x12
        for (k in 0 until 5) b[10 + k] = 1
        return BulkRecord.of(b)!!
    }

    /**
     * The ring alternates its SpO2 duty cycle 1:1 through the night, then the duty cycle EXITS and the
     * morning is same-template throughout. The wearer is still, the morning HR rise is too small to
     * clear the wake margin, and the ring keeps emitting sleep-vitals across the wake — so the cadence
     * is the only witness left.
     */
    private fun cadenceExitNight(epochs: Int = 160, riseAt: Int = 120, sleepHR: Int = 54, wakeHR: Int = 64): List<BulkRecord> {
        val recs = mutableListOf<BulkRecord>()
        var c = 1_000_000L
        for (i in 0 until epochs) {
            recs += if (i >= riseAt) {
                vrec(c, hr = wakeHR, hrv = 55) // same template, every epoch
            } else {
                if (i % 2 == 0) vrec(c, hr = sleepHR, hrv = 55) else qrec(c, hr = sleepHR)
            }
            c += BulkRecord.EPOCH_SECONDS.toLong()
        }
        return recs
    }

    // Integration through `classify` (these go red if the call site is deleted)

    /** PINS THE CALL SITE. The default is ENABLED, so this compares default against `0`. */
    @Test
    fun testEnabledByDefaultMovesTheStagedWakeVersusDisabled() {
        val recs = cadenceExitNight()
        val off = SleepStaging.classify(recs, tuning = SleepStaging.Tuning(cadenceWakeQuietEpochs = 0))
        val on = SleepStaging.classify(recs) // default = enabled
        val offWake = SleepStaging.sleepWindow(off)?.wake
        val onWake = SleepStaging.sleepWindow(on)?.wake
        if (offWake == null || onWake == null) fail("both configurations must still stage a night")
        assertTrue(
            onWake.isBefore(offWake),
            "the DEFAULT must pull final wake earlier than the disabled config — if this " +
                "fails the pass is not wired in, or the default was reverted to off",
        )
        assertTrue(
            secs(Duration.between(onWake, offWake)) > 60.0 * 60,
            "the whole same-template morning must come off, not a token epoch",
        )
        assertEquals(
            SleepStaging.sleepWindow(off)?.onset, SleepStaging.sleepWindow(on)?.onset,
            "a trailing-edge pass must not disturb the onset",
        )
    }

    /** With the pass off, the reported wake IS the data edge: `lastRecord + 120 s`. */
    @Test
    fun testWithThePassDisabledTheWakeIsMerelyTheLastRecord() {
        val recs = cadenceExitNight()
        val off = SleepStaging.classify(recs, tuning = SleepStaging.Tuning(cadenceWakeQuietEpochs = 0))
        val wake = SleepStaging.sleepWindow(off)?.wake
        val last = recs.lastOrNull()?.date()
        if (wake == null || last == null) fail("expected a staged night")
        assertEquals(120.0, secs(Duration.between(last, wake)), 1.0)
    }

    /**
     * SYNC-TIME INVARIANCE: staging the SAME night after truncating the capture at successively later
     * points must not keep pushing the wake later once the wake itself is in the data.
     */
    @Test
    fun testWakeStopsMovingOnceItIsInTheData() {
        val recs = cadenceExitNight()
        val wakes = mutableListOf<Instant>()
        for (extra in 4..40 step 4) {
            val truncated = recs.take(120 + extra)
            val w = SleepStaging.sleepWindow(SleepStaging.classify(truncated))?.wake
                ?: fail("every truncation must still stage a night (cut at +$extra)")
            wakes += w
        }
        assertEquals(1, wakes.toSet().size, "the wake moved with the truncation point: ${wakes.map { it.epochSecond }}")
    }

    @Test
    fun testDefaultIsEnabled() {
        assertTrue(def.cadenceWakeQuietEpochs > 0, "the cadence locator ships ON; disabling it is a deliberate act")
    }

    /** The measured admissible band for K over upstream's corpus is [12, 27]. */
    @Test
    fun testDefaultQuietBarSitsInsideTheMeasuredAdmissibleBand() {
        val k = def.cadenceWakeQuietEpochs
        assertTrue(k >= 12, "below 12 the 11-epoch post-wake run on 08-09 wins (+43 min)")
        assertTrue(k <= 27, "above 27 the 08-04 night jumps back an extra 70 min")
    }

    /** A night the ring alternated through to the very last record must be left ALONE. */
    @Test
    fun testNightStillInCadenceAtTheDataEdgeIsUntouched() {
        val recs = mutableListOf<BulkRecord>()
        var c = 1_000_000L
        for (i in 0 until 160) {
            recs += if (i % 2 == 0) vrec(c, hr = 54) else qrec(c, hr = 54)
            c += BulkRecord.EPOCH_SECONDS.toLong()
        }
        val off = secs(SleepStaging.totalAsleep(SleepStaging.classify(recs, tuning = SleepStaging.Tuning(cadenceWakeQuietEpochs = 0))))
        val on = secs(SleepStaging.totalAsleep(SleepStaging.classify(recs)))
        assertEquals(off, on, 1.0)
        assertTrue(off > 0, "fixture sanity: the night really does stage")
    }
}
