package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.VitalsBaseline.Status
import io.github.opencircuit.ringkit.WellnessBalance.Input
import io.github.opencircuit.ringkit.WellnessBalance.Result.Factor
import io.github.opencircuit.ringkit.WellnessBalance.Tier
import io.github.opencircuit.ringkit.WellnessBalance.Trend
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the readiness score: what the upstream vectors never feed in.
 * Its sub-scores are stored values read back from history (last night's sleep score, the overnight
 * stress score, the vitals status, the day's activity score) and its trend reads a list of stored
 * daily scores, so here they arrive at the ends of `Int`, outside their documented ranges, and in
 * sums that sit on a rounding tie. Kept out of the upstream-port class so its count stays exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Every
 * sub-score is an integer and every factor is clamped to 0…1, so no input reaches upstream's
 * `Int(x)` conversion with a NaN or an out-of-range value; the hazards here are the summation order
 * and the 64-bit sum of the trend.
 */
class WellnessBalanceHazardTest {

    @Test
    fun subScoresOutsideTheirRangeClampAsUpstreamAndEveryScoreIsAnInRangeInteger() {
        // Measured: sleep Int32.max, stress Int32.min, anomaly, activity Int32.min → 65 good with
        // sleep 1, recovery 1, vitals 0, activity 0; sleep Int32.min, stress Int32.max, activity
        // Int32.max → 19 needsImprovement with sleep 0, recovery 0, activity 1; a stress of 14 or 0
        // reads full recovery (100), 91 none (0).
        val a = assertNotNull(WellnessBalance.score(Input(Int.MAX_VALUE, Int.MIN_VALUE, Status.ANOMALY, Int.MIN_VALUE)))
        assertEquals(65, a.score)
        assertEquals(Tier.GOOD, a.tier)
        assertEquals(mapOf(Factor.SLEEP to 1.0, Factor.RECOVERY to 1.0, Factor.VITALS to 0.0, Factor.ACTIVITY to 0.0), a.factors)
        val b = assertNotNull(WellnessBalance.score(Input(Int.MIN_VALUE, Int.MAX_VALUE, null, Int.MAX_VALUE)))
        assertEquals(19, b.score)
        assertEquals(Tier.NEEDS_IMPROVEMENT, b.tier)
        assertEquals(mapOf(Factor.SLEEP to 0.0, Factor.RECOVERY to 0.0, Factor.ACTIVITY to 1.0), b.factors)
        assertEquals(100, WellnessBalance.score(Input(overnightStress = 14))?.score)
        assertEquals(100, WellnessBalance.score(Input(overnightStress = 0))?.score)
        assertEquals(0, WellnessBalance.score(Input(overnightStress = 91))?.score)
        // Exactly the documented ends of each range and one past them.
        assertEquals(1.0, WellnessBalance.score(Input(overnightStress = 15))!!.factors[Factor.RECOVERY])
        assertEquals(0.0, WellnessBalance.score(Input(overnightStress = 90))!!.factors[Factor.RECOVERY])
        assertEquals(1.0, WellnessBalance.score(Input(sleepScore = 101))!!.factors[Factor.SLEEP])
        assertEquals(0.0, WellnessBalance.score(Input(activityScore = -1))!!.factors[Factor.ACTIVITY])
        // Every combination of extreme, boundary and ordinary sub-scores gives an integer score in
        // 0…100, its tier, and factors in 0…1 that are never -0.0.
        val ints = listOf(null, Int.MIN_VALUE, -1, 0, 1, 14, 15, 52, 59, 60, 84, 85, 90, 91, 100, 101, Int.MAX_VALUE)
        val statuses = listOf(null) + Status.entries
        for (s in ints) for (st in ints) for (v in statuses) for (act in ints) {
            val r = WellnessBalance.score(Input(s, st, v, act))
            if (s == null && st == null && v == null && act == null) {
                assertNull(r, "nothing to score")
                continue
            }
            val res = assertNotNull(r, "$s $st $v $act")
            assertTrue(res.score in 0..100, "$s $st $v $act → ${res.score}")
            assertEquals(Tier.of(res.score), res.tier)
            for ((f, x) in res.factors) assertTrue(x in 0.0..1.0 && x.toRawBits() != (-0.0).toRawBits(), "$f = $x")
        }
    }

    /** Upstream's four weights, typed from `WellnessBalance.swift:61-63`, in declaration order. */
    private val weights = doubleArrayOf(0.40, 0.25, 0.20, 0.15)

    private fun permutations(xs: List<Int>): List<List<Int>> =
        if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }

    private fun scoreSummedIn(order: List<Int>, v: DoubleArray): Int {
        var num = 0.0
        var den = 0.0
        for (k in order) {
            num += weights[k] * v[k]
            den += weights[k]
        }
        return roundHalfAwayFromZero(num / den * 100).toInt()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun factorsAreSummedInDeclarationOrderWhereUpstreamsOrderChangesFromOneLaunchToTheNext() {
        // Upstream adds the present factors by iterating a dictionary whose order Swift seeds per
        // process. Measured over 20 launches of the pinned build: sleep 1, stress 48, activity 24 gave
        // 22 in some and 23 in others (sleep 2 / stress 18 / activity 56: 41 or 42; sleep 3 / stress
        // 15 / activity 20: 36 or 37) — even the order of `factors` changed between two inputs with
        // the same keys in one launch. With deterministic hashing the order is fixed (23, 42, 37).
        // The port adds them in declaration order — sleep, recovery, vitals, activity — always.
        assertEquals(23, WellnessBalance.score(Input(sleepScore = 1, overnightStress = 48, activityScore = 24))?.score)
        assertEquals(42, WellnessBalance.score(Input(sleepScore = 2, overnightStress = 18, activityScore = 56))?.score)
        assertEquals(37, WellnessBalance.score(Input(sleepScore = 3, overnightStress = 15, activityScore = 20))?.score)
        // Every input reaches one of these factor values (anything outside a range clamps onto its
        // end), so the sweep below covers every input the API accepts: 3 204 431 present-factor
        // combinations. A search over all of them on the pinned formula found 11 712 whose rounded
        // score depends on the summation order. On every input the port's score is one of the
        // answers upstream can give, and on those 11 712 it is the declaration-order one.
        val domains = listOf(
            (0..100).toList(), (15..90).toList(), listOf(0, 1, 2), (0..100).toList(),
        )
        var total = 0
        var sensitive = 0
        for (mask in 1 until 16) {
            val present = (0 until 4).filter { mask and (1 shl it) != 0 }
            val orders = permutations(present)
            val idx = IntArray(4)
            fun visit(j: Int) {
                if (j == present.size) {
                    total++
                    val raw = IntArray(4) { k -> domains[k][idx[k]] }
                    val v = DoubleArray(4)
                    if (0 in present) v[0] = raw[0] / 100.0
                    if (1 in present) v[1] = (90.0 - raw[1]) / (90.0 - 15.0)
                    if (2 in present) v[2] = doubleArrayOf(1.0, 0.5, 0.0)[raw[2]]
                    if (3 in present) v[3] = raw[3] / 100.0
                    val possible = orders.map { scoreSummedIn(it, v) }.toSet()
                    val input = Input(
                        sleepScore = raw[0].takeIf { 0 in present },
                        overnightStress = raw[1].takeIf { 1 in present },
                        vitalsStatus = Status.entries[raw[2]].takeIf { 2 in present },
                        activityScore = raw[3].takeIf { 3 in present },
                    )
                    val got = WellnessBalance.score(input)!!.score
                    assertTrue(got in possible, "$input → $got, upstream can give $possible")
                    if (possible.size > 1) {
                        sensitive++
                        assertEquals(scoreSummedIn(present, v), got, "$input: the declaration-order sum")
                    }
                    return
                }
                val k = present[j]
                for (i in domains[k].indices) {
                    idx[k] = i
                    visit(j + 1)
                }
            }
            visit(0)
        }
        assertEquals(3_204_431, total)
        assertEquals(11_712, sensitive)
    }

    @Test
    fun anchoredScoreNeedsASleepSubScoreWhateverItsValue() {
        // Any sleep sub-score anchors, even one outside 0…100 (it clamps); none never does, however
        // much else is present.
        assertNull(WellnessBalance.anchoredScore(Input(overnightStress = 15, vitalsStatus = Status.NORMAL, activityScore = 100)))
        assertNull(WellnessBalance.anchoredScore(Input()))
        assertEquals(0, WellnessBalance.anchoredScore(Input(sleepScore = Int.MIN_VALUE))?.score)
        assertEquals(100, WellnessBalance.anchoredScore(Input(sleepScore = Int.MAX_VALUE))?.score)
        val full = Input(80, 40, Status.WATCH, 70)
        assertEquals(WellnessBalance.score(full), WellnessBalance.anchoredScore(full))
        assertEquals(69, WellnessBalance.anchoredScore(full)?.score, "measured: 69 good")
    }

    @Test
    fun everyVitalsStatusHasItsFactor() {
        assertEquals(listOf(1.0, 0.5, 0.0), Status.entries.map { WellnessBalance.vitalsFactor(it) })
    }

    @Test
    fun theTrendSumsPriorScoresInSixtyFourBits() {
        // Upstream's Int is 64-bit: measured, today 0 against [Int32.max, Int32.max] is "down" (mean
        // 2 147 483 647); a 32-bit sum wraps to -2, a mean of -1, and reads "steady".
        assertEquals(Trend.DOWN, WellnessBalance.trend(0, listOf(Int.MAX_VALUE, Int.MAX_VALUE)))
        assertEquals(Trend.DOWN, WellnessBalance.trend(0, List(3) { Int.MAX_VALUE }))
        assertEquals(Trend.UP, WellnessBalance.trend(Int.MAX_VALUE, listOf(Int.MIN_VALUE, Int.MAX_VALUE)))
        assertEquals(Trend.UP, WellnessBalance.trend(Int.MAX_VALUE, List(5) { Int.MIN_VALUE }))
        assertEquals(Trend.STEADY, WellnessBalance.trend(Int.MIN_VALUE, emptyList()), "no prior scores: steady")
    }

    @Test
    fun aNegativeZeroOrExtremeDeadbandBehavesAsUpstream() {
        // Measured: with a negative deadband the "up" test runs first and wins (70 vs [70], -3 → up);
        // a change at or past it reads down (67 or 66 → down) — never steady.
        assertEquals(Trend.UP, WellnessBalance.trend(70, listOf(70), deadband = -3))
        assertEquals(Trend.DOWN, WellnessBalance.trend(67, listOf(70), deadband = -3))
        assertEquals(Trend.DOWN, WellnessBalance.trend(66, listOf(70), deadband = -3))
        assertEquals(Trend.STEADY, WellnessBalance.trend(70, listOf(70), deadband = 0))
        // Exactly the deadband is steady both ways; a fractional mean is compared as a double.
        assertEquals(Trend.STEADY, WellnessBalance.trend(73, listOf(70)))
        assertEquals(Trend.STEADY, WellnessBalance.trend(67, listOf(70)))
        assertEquals(Trend.UP, WellnessBalance.trend(74, listOf(70, 71)))
        assertEquals(Trend.STEADY, WellnessBalance.trend(73, listOf(70, 71)))
        // The ends of Int (measured): -Int.MIN is taken as a double (2 147 483 648), never negated in
        // 32 bits — where it would wrap back to Int.MIN, Int.MIN against [0] would read steady.
        assertEquals(Trend.UP, WellnessBalance.trend(70, listOf(70), deadband = Int.MIN_VALUE))
        assertEquals(Trend.DOWN, WellnessBalance.trend(Int.MIN_VALUE, listOf(0), deadband = Int.MIN_VALUE))
        assertEquals(Trend.UP, WellnessBalance.trend(Int.MAX_VALUE, listOf(Int.MIN_VALUE), deadband = Int.MAX_VALUE))
    }
}
