package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The intensity-tail light/active seam must be an ABSOLUTE value, not a rank over whatever has
 * drained. Both legacy seams (the 0.80 quantile and the Otsu split) are computed over the record set
 * being staged, so the same epoch could read "still" in one sync and "movement" in the next with no
 * byte of physiology changed. The central property is SET-INDEPENDENCE, tested by construction:
 * extend the set and assert the verdict on the ORIGINAL epochs does not move. Each such test has a
 * twin that pins the legacy behaviour, so the property can never pass vacuously.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/MotionIntensityAbsoluteCutTests.swift
 * (@ b1c2fdd) — all 12 tests. `:133` (`testEmittedMagnitudesStraddleTheDownstreamThresholds`)
 * reads the sleep-staging tuning and arrived with it.
 */
class MotionIntensityAbsoluteCutTest {

    // :19-33 — a worn sleep-vitals record whose [15:20] intensity tail sums to `tailSum`.
    private fun rec(counter: Long, tailSum: Int): BulkRecord {
        val b = ByteArray(23)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        b[4] = 55; b[5] = 50; b[8] = 0x62
        for (k in 0 until 5) b[10 + k] = 1
        var left = tailSum
        for (k in 0 until 5) {
            val take = minOf(left, 255)
            b[15 + k] = take.toByte()
            left -= take
        }
        assertEquals(0, left, "fixture cannot express a tail sum above 1275")
        return assertNotNull(BulkRecord.of(b))
    }

    // :35-37
    private fun records(sums: List<Int>): List<BulkRecord> =
        sums.mapIndexed { i, s -> rec(1_000_000L + i * BulkRecord.EPOCH_SECONDS, tailSum = s) }

    // :40 — straddles the shipped cut: 344 below, 345 exactly on it, 346 above.
    private val base = listOf(100, 200, 344, 345, 346, 500)

    private val grown = base + List(20) { 1200 }

    @Test
    fun absoluteCutIsIndependentOfWhatElseIsInTheSet() { // :44-53
        val short = BulkSleep.motionIntensityFallbackMagnitudes(records(base), degenerate = false)
        val long = BulkSleep.motionIntensityFallbackMagnitudes(records(grown), degenerate = false)
        assertEquals(short, long.take(base.size), "the same epochs must keep the same verdict when more history drains in")
        assertEquals(listOf(1f, 1f, 1f, 16f, 16f, 16f), short, "seam is >= 345: 344 is still, 345 and above are movement")
    }

    @Test
    fun theLegacyRankIsNotIndependentOfTheSet() { // :56-63
        val short = BulkSleep.motionIntensityFallbackMagnitudes(records(base), degenerate = false, absoluteActiveCut = 0)
        val long = BulkSleep.motionIntensityFallbackMagnitudes(records(grown), degenerate = false, absoluteActiveCut = 0)
        assertNotEquals(short, long.take(base.size), "fixture sanity: the legacy p80 rank MUST move when the set grows")
    }

    @Test
    fun absoluteCutIsIndependentOfTheSetOnTheDegenerateBranchToo() { // :66-73
        val short = BulkSleep.motionIntensityFallbackMagnitudes(records(base), degenerate = true)
        val long = BulkSleep.motionIntensityFallbackMagnitudes(records(grown), degenerate = true)
        assertEquals(short, long.take(base.size))
        assertEquals(listOf(1f, 1f, 1f, 16f, 16f, 16f), short, "the absolute seam replaces Otsu as well")
    }

    @Test
    fun theLegacyOtsuSeamIsAlsoNotIndependentOfTheSet() { // :75-82
        val short = BulkSleep.motionIntensityFallbackMagnitudes(records(base), degenerate = true, absoluteActiveCut = 0)
        val long = BulkSleep.motionIntensityFallbackMagnitudes(records(grown), degenerate = true, absoluteActiveCut = 0)
        assertNotEquals(short, long.take(base.size), "fixture sanity: Otsu carries the same exposure the quantile does")
    }

    @Test
    fun motionMagnitudesForwardsTheSeam() { // :85-92
        val recs = records(base)
        assertEquals(
            BulkSleep.motionIntensityFallbackMagnitudes(recs, degenerate = false, absoluteActiveCut = 400),
            BulkSleep.motionMagnitudes(recs, absoluteActiveCut = 400),
            "if this diverges, staging is not using the seam the tests pin",
        )
    }

    @Test
    fun zeroRestoresTheLegacyQuantileExactly() { // :95-101
        val got = BulkSleep.motionIntensityFallbackMagnitudes(records(base), degenerate = false, absoluteActiveCut = 0)
        // p80 of [100,200,344,345,346,500] is index round(5*0.8) = 4 -> 346.
        val expected = base.map { if (it == 0) 0f else if (it >= 346) 16f else 1f }
        assertEquals(expected, got, "0 must reproduce the legacy rank byte for byte")
    }

    @Test
    fun zeroRestoresTheLegacyOtsuSeamExactly() { // :104-111
        val otsu = BulkSleep.otsuIntensityCut(base.filter { it > 0 }.sorted())
        val got = BulkSleep.motionIntensityFallbackMagnitudes(records(base), degenerate = true, absoluteActiveCut = 0)
        assertEquals(base.map { if (it >= otsu) 16f else 1f }, got)
    }

    @Test
    fun aZeroTailIsAlwaysZeroMovement() { // :115-122
        for (cut in listOf(0, 345, 1_000_000)) {
            val m = BulkSleep.motionIntensityFallbackMagnitudes(records(listOf(0, 0, 500, 0)), degenerate = false, absoluteActiveCut = cut)
            assertEquals(0f, m[0]); assertEquals(0f, m[1]); assertEquals(0f, m[3])
        }
    }

    @Test
    fun anAllZeroTailProducesNoMovementAtAll() { // :125-129
        assertEquals(listOf(0f, 0f, 0f), BulkSleep.motionIntensityFallbackMagnitudes(records(listOf(0, 0, 0)), degenerate = false))
    }

    @Test
    fun emittedMagnitudesStraddleTheDownstreamThresholds() { // :133-139
        // Values must keep straddling the thresholds the rest of the pipeline is calibrated to:
        // `MOTION_STILL_THRESHOLD == 2` and the staging tuning's `awakeMotion == 15`.
        val m = BulkSleep.motionIntensityFallbackMagnitudes(records(listOf(100, 500)), degenerate = false)
        assertTrue(m[0] < ActivityPeriod.MOTION_STILL_THRESHOLD, "still must read below the still bar")
        assertTrue(m[1].toInt() > SleepStaging.Tuning.DEFAULT.awakeMotion, "active must clear awakeMotion")
    }

    @Test
    fun defaultSeamSitsInsideTheMeasuredCorpusBand() { // :145-148
        assertTrue(BulkSleep.MOTION_INTENSITY_ACTIVE_CUT >= 262)
        assertTrue(BulkSleep.MOTION_INTENSITY_ACTIVE_CUT <= 474)
    }

    @Test
    fun defaultSeamIsEnabled() { // :150-153
        assertTrue(BulkSleep.MOTION_INTENSITY_ACTIVE_CUT > 0, "0 is the revert; shipping it disables the fix")
    }
}
