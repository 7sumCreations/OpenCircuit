package io.github.opencircuit.ringkit

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Port of upstream ObservedGapCeilingProbeTests.swift (@ b1c2fdd) — a COVERAGE-GEOMETRY probe: what
// does `observed / expected` read for a gap COMPLETELY covered by records? The guard's ratio counts
// records STRICTLY inside the gap but expects `gap / 150 s`, and the gap's endpoints are detector
// block boundaries, not record times, so a full gap does not read 1.0 by construction. An earlier
// upstream model said it could never reach 1.0; the corpus refuted it (a real 43-min bridge reads
// 0.988). What this probe establishes is the per-length FLOOR for a fully observed gap. Plain
// vectors: no corpus, no environment gate.

class ObservedGapCeilingProbeTest {

    /** How many 150 s records can fall STRICTLY inside a [gap]-second window, min and max over every 1 s alignment. */
    private fun achievableInterior(gap: Double): Pair<Int, Int> {
        val cadence = BulkRecord.EPOCH_SECONDS.toDouble()
        var lo = Int.MAX_VALUE
        var hi = 0
        for (step in 0 until cadence.toInt()) {
            var count = 0
            var t = step.toDouble()
            while (t < gap) {
                if (t > 0) count += 1
                t += cadence
            }
            lo = minOf(lo, count)
            hi = maxOf(hi, count)
        }
        return lo to hi
    }

    @Test
    fun testCoverageBandForAFullyObservedGap() {
        val cadence = BulkRecord.EPOCH_SECONDS.toDouble()
        println("\n=== OBSERVED-GAP COVERAGE BAND (gap fully covered at ${cadence.toInt()} s cadence)")
        println("gapMin        L   fullyObservedFloor   fullyObservedCeiling")

        val floors = mutableListOf<Pair<Double, Double>>()
        for (gapMinutes in listOf(7.5, 10.0, 15.0, 20.0, 30.0, 39.2, 43.0, 60.0, 90.0, 120.0, 360.0)) {
            val gap = gapMinutes * 60.0
            val l = gap / cadence
            val (min, max) = achievableInterior(gap)
            val floor = min.toDouble() / l
            val ceiling = max.toDouble() / l
            floors += gapMinutes to floor
            println(String.format(Locale.ROOT, "%6.1f  %7.3f   %18.4f   %20.4f", gapMinutes, l, floor, ceiling))

            // THE REFUTATION, pinned: a fully observed gap CAN read 1.0 or more.
            assertTrue(ceiling >= 1.0, "gap $gapMinutes min: a full gap must be able to read >= 1.0")
        }

        // The guard never judges a gap at or below the contiguity floor: the shortest judgeable gap.
        val floorMinutes = BulkSleep.ONSET_CONTIGUITY_GAP.seconds / 60.0
        println(String.format(Locale.ROOT, "\nonsetContiguityGap = %.1f min -> shortest judgeable gap", floorMinutes))
        for (want in listOf(7.5, 30.0, 60.0, 360.0)) {
            println(String.format(Locale.ROOT, "fully-observed floor at %5.1f min: %.4f", want, floors.first { it.first == want }.second))
        }

        // The floor is exactly (⌈L⌉ − 1)/L, and NOT monotone in gap length (43 min floors at 0.988,
        // 60 min at 0.958) — a bounded, accepted limitation of a completeness test.
        for ((gapMinutes, floor) in floors) {
            val l = gapMinutes * 60.0 / cadence
            assertTrue(abs(floor - (ceil(l) - 1) / l) <= 1e-9, "gap $gapMinutes min: floor must equal (ceil(L) - 1)/L")
        }
        // Over WHOLE gap lengths the floor does rise with length.
        val wholeL = floors.filter { (it.first * 60.0 / cadence) % 1.0 == 0.0 }
        for (i in 1 until wholeL.size) {
            assertTrue(wholeL[i].second >= wholeL[i - 1].second - 1e-9, "at whole L the floor must not fall as the gap lengthens")
        }
    }

    /**
     * The shipped cut must sit ABOVE the fully-observed floor for the gap lengths the corpus produced
     * (39–43 min), or it could not tell a complete gap from a holed one. The two corpus bridges,
     * measured upstream from the bytes: 2580 s with 17 interior records at an unbroken 150 s cadence
     * (0.988, COMPLETE) and 2350 s with 14 interior records and 215/275 s deltas (0.894, HOLED).
     */
    @Test
    fun testShippedCutSeparatesTheTwoCorpusBridges() {
        val complete = 17.0 / (2580.0 / BulkRecord.EPOCH_SECONDS)
        val holed = 14.0 / (2350.0 / BulkRecord.EPOCH_SECONDS)
        assertEquals(0.9884, complete, 0.0002)
        assertEquals(0.8936, holed, 0.0002)

        val cut = BulkSleep.OBSERVED_GAP_ABSORB_COVERAGE_CUT
        assertTrue(cut > 0, "the guard ships ENABLED")
        assertTrue(complete >= cut, "the complete gap must fire at the shipped cut")
        assertTrue(
            holed < cut,
            "the holed gap must be DECLINED at the shipped cut — a gap missing an epoch is a hole, which is exactly what the backward chain exists for",
        )

        // And the complete gap really is complete: 0.988 is its own fully-observed floor.
        val l = 2580.0 / BulkRecord.EPOCH_SECONDS
        assertEquals((ceil(l) - 1) / l, complete, 1e-9)
    }
}
