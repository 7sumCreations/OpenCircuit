package io.github.opencircuit.ringkit

import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the robust (median / MAD) baseline: what the upstream vectors
 * never feed in. The prior days are STORED daily values (a resting HR, an HRV mean, a bedtime) read
 * back from history, so here they arrive NaN, infinite, signed zero, astronomically large or
 * unsorted; the window sizes and noise floor arrive zero, negative or NaN; clock minutes arrive at
 * the ends of `Int`. Kept out of the upstream-port class so its count stays exact.
 *
 * The rule under test: an unreadable (NaN or infinite) day is a missing day, never a day that
 * silently drags the median, and no input crashes, loops or corrupts the answer. Every upstream
 * outcome quoted below was measured on the pinned Swift build (Swift 6.3.2); where Kotlin
 * deliberately differs the test says so, and `PORTING.md` records why.
 */
class RobustBaselineHazardTest {

    private val nan = Double.NaN
    private val inf = Double.POSITIVE_INFINITY

    private fun assertStats(median: Double, mad: Double, n: Int, s: RobustBaseline.Stats?, why: String) {
        val stats = assertNotNull(s, "$why: a baseline exists")
        assertEquals(median, stats.median, "$why: median")
        assertEquals(mad, stats.mad, "$why: mad")
        assertEquals(n, stats.n, "$why: n")
    }

    @Test
    fun statsTreatAnUnreadableDayAsAMissingDay() {
        // Upstream counts a NaN day and sorts it wherever `<` leaves it: measured, two NaN days among
        // eight give a NaN median and a NaN MAD — every later z is then 0, "nothing unusual", for as
        // long as the NaN stays in the window. Four +Inf days give an infinite median.
        assertNull(RobustBaseline.stats(listOf(50.0, nan, 70.0, nan, 55.0, 65.0, 62.0, 61.0)), "six readable days are not a baseline")
        assertNull(RobustBaseline.stats(List(7) { nan }), "seven unreadable days are no days")
        assertNull(RobustBaseline.stats(listOf(60.0, 61.0, 62.0, 63.0, 64.0, 65.0, nan)), "upstream: median 63 over 6 + NaN")
        assertNull(RobustBaseline.stats(listOf(inf, inf, inf, inf, 60.0, 61.0, 62.0)), "upstream: median +Inf, MAD NaN")
        // Seven readable days around unreadable ones: the baseline of the seven alone.
        assertStats(61.0, 4.0, 7, RobustBaseline.stats(listOf(50.0, nan, 70.0, inf, 55.0, 65.0, -inf, 62.0, 61.0, 60.0)), "seven readable")
        // The window holds the newest 60 READABLE days: unreadable days at the end do not push readable ones out.
        val sixtyThenNaN = (1..60).map { it.toDouble() } + List(5) { nan }
        assertStats(30.5, 15.0, 60, RobustBaseline.stats(sixtyThenNaN), "newest 60 readable")
        // Property over a seeded sweep: inserting unreadable days anywhere never changes the answer, and
        // the answer from readable days is always a finite median inside their range with a finite MAD.
        val rng = Random(0x5244_4231)
        repeat(500) { k ->
            val finite = List(rng.nextInt(7, 90)) { rng.nextInt(-500, 500) / 4.0 }
            val dirty = finite.toMutableList()
            repeat(rng.nextInt(1, 12)) { dirty.add(rng.nextInt(0, dirty.size + 1), listOf(nan, inf, -inf)[rng.nextInt(3)]) }
            val clean = assertNotNull(RobustBaseline.stats(finite), "case $k")
            val withHoles = assertNotNull(RobustBaseline.stats(dirty), "case $k with unreadable days")
            assertEquals(clean.median, withHoles.median, "case $k median")
            assertEquals(clean.mad, withHoles.mad, "case $k mad")
            assertEquals(clean.n, withHoles.n, "case $k n")
            val window = finite.takeLast(60)
            assertTrue(clean.median in window.min()..window.max() && clean.mad.isFinite() && clean.mad >= 0.0, "case $k: $clean")
        }
    }

    @Test
    fun statsWithDegenerateWindowsSignedZeroAndHugeDaysAsUpstream() {
        val flat = List(7) { 60.0 }
        // Refused, as upstream (measured nil for each): no minimum, a negative minimum, a maximum below
        // the minimum, a negative maximum, nothing at all.
        assertNull(RobustBaseline.stats(flat, minDays = 0))
        assertNull(RobustBaseline.stats(flat, minDays = -1))
        assertNull(RobustBaseline.stats(flat, minDays = 5, maxDays = 3))
        assertNull(RobustBaseline.stats(flat, minDays = 1, maxDays = -1))
        assertNull(RobustBaseline.stats(emptyList(), minDays = 1))
        assertNull(RobustBaseline.stats(List(6) { 60.0 }), "exactly one day short of the minimum")
        assertStats(60.0, 0.0, 7, RobustBaseline.stats(flat), "exactly the minimum")
        assertStats(60.0, 0.0, 1, RobustBaseline.stats(flat, minDays = 1, maxDays = 1), "a one-day window")
        // Signed zeros keep their input order through the stable sort: the middle value is +0.0 (measured).
        val zeros = RobustBaseline.stats(listOf(-0.0, 0.0, -0.0, 0.0, 0.0, -0.0, 0.0))
        assertEquals(0.0.toRawBits(), assertNotNull(zeros).median.toRawBits())
        assertEquals(0.0.toRawBits(), zeros.mad.toRawBits())
        // Finite but astronomically spread days: median 1e308, MAD 0 (three deviations are +Inf), as upstream.
        assertStats(1e308, 0.0, 7, RobustBaseline.stats(listOf(1e308, -1e308, 1e308, -1e308, 1e308, -1e308, 1e308)), "huge")
    }

    @Test
    fun zOfAnUnreadableTodayOrBaselineContributesNothing() {
        // Upstream's own rule (a result that is not finite reads as 0), measured for each: kept.
        val flat = RobustBaseline.Stats(median = 60.0, mad = 0.0, n = 7)
        for (today in listOf(nan, inf, -inf)) assertEquals(0.0, RobustBaseline.z(today, flat, noiseFloor = 5.0), "today $today")
        assertEquals(0.0, RobustBaseline.z(70.0, RobustBaseline.Stats(nan, 1.0, 7), noiseFloor = 5.0), "NaN median")
        assertEquals(0.0, RobustBaseline.z(70.0, RobustBaseline.Stats(60.0, nan, 7), noiseFloor = 5.0), "NaN MAD")
        assertEquals(0.0, RobustBaseline.z(70.0, RobustBaseline.Stats(60.0, inf, 7), noiseFloor = 5.0), "infinite MAD")
        assertEquals(0.0, RobustBaseline.z(70.0, RobustBaseline.Stats(inf, 1.0, 7), noiseFloor = 5.0), "infinite median")
    }

    @Test
    fun zClampsAnOverflowingDeviationInsteadOfZeroingIt() {
        // Upstream divides by a scale floored at the smallest normal double and returns 0 when the
        // quotient is not finite. With a zero noise floor and a zero MAD (a perfectly regular person)
        // that makes the score non-monotonic — measured: a deviation of 1 reads 4 (the clamp), one of
        // 4, 7, 8, 10, 1e10 or 1e300 reads 0; -1 reads -4, -10 reads 0. A difference of two finite days
        // that overflows (1e308 - (-1e308)) also reads 0. The port clamps an overflowing quotient of
        // finite readings by its sign.
        val flat = RobustBaseline.Stats(median = 60.0, mad = 0.0, n = 7)
        for (d in listOf(1.0, 4.0, 7.0, 8.0, 10.0, 1e10, 1e300)) {
            assertEquals(4.0, RobustBaseline.z(60.0 + d, flat, noiseFloor = 0.0), "+$d")
            assertEquals(-4.0, RobustBaseline.z(60.0 - d, flat, noiseFloor = 0.0), "-$d")
        }
        assertEquals(4.0, RobustBaseline.z(1e308, RobustBaseline.Stats(-1e308, 1.0, 7), noiseFloor = 5.0))
        assertEquals(-4.0, RobustBaseline.z(-1e308, RobustBaseline.Stats(1e308, 1.0, 7), noiseFloor = 5.0))
        // Nothing else changes: across a sweep of finite days, MADs and floors the score never leaves
        // the clamp and never falls as today rises.
        for (mad in listOf(0.0, 1e-300, 0.1, 10.0)) {
            for (floor in listOf(0.0, 1e-300, 0.3, 5.0)) {
                val stats = RobustBaseline.Stats(60.0, mad, 7)
                val days = (-308..308 step 4).flatMap { e -> listOf(60.0 - Math.pow(10.0, e.toDouble()), 60.0 + Math.pow(10.0, e.toDouble())) } +
                    (-400..400).map { 60.0 + it * 0.25 } + listOf(-Double.MAX_VALUE, Double.MAX_VALUE)
                var previous = Double.NEGATIVE_INFINITY
                for (today in days.sorted()) {
                    val z = RobustBaseline.z(today, stats, noiseFloor = floor)
                    assertTrue(z in -4.0..4.0, "mad $mad floor $floor today $today: $z")
                    assertTrue(z >= previous, "mad $mad floor $floor: z($today) = $z fell below $previous")
                    previous = z
                }
            }
        }
    }

    @Test
    fun zWithDegenerateFloorOrClampBehavesAsUpstream() {
        val flat = RobustBaseline.Stats(median = 60.0, mad = 0.0, n = 7)
        // All measured on the pinned build. A NaN floor with a zero MAD leaves a zero scale: 0.
        assertEquals(0.0, RobustBaseline.z(62.0, flat, noiseFloor = nan))
        assertEquals(1.3489815189531904, RobustBaseline.z(62.0, RobustBaseline.Stats(60.0, 1.0, 7), noiseFloor = nan))
        assertEquals(4.0, RobustBaseline.z(62.0, flat, noiseFloor = -5.0), "a negative floor floors at the smallest normal double")
        assertEquals(0.0, RobustBaseline.z(62.0, flat, noiseFloor = inf))
        assertEquals(8.0, RobustBaseline.z(100.0, flat, noiseFloor = 5.0, clamp = nan), "a NaN clamp does not clamp")
        assertEquals(-4.0, RobustBaseline.z(62.0, flat, noiseFloor = 5.0, clamp = -4.0))
        assertEquals(0.0, RobustBaseline.z(62.0, flat, noiseFloor = 5.0, clamp = 0.0))
        assertEquals(8.0, RobustBaseline.z(100.0, flat, noiseFloor = 5.0, clamp = inf))
        // An overflowing quotient that a NaN or infinite clamp cannot bound reads 0, as upstream (which
        // zeroes every quotient that is not finite): z is never infinite.
        for (clamp in listOf(nan, inf)) {
            assertEquals(0.0, RobustBaseline.z(70.0, flat, noiseFloor = 0.0, clamp = clamp), "clamp $clamp, +overflow")
            assertEquals(0.0, RobustBaseline.z(50.0, flat, noiseFloor = 0.0, clamp = clamp), "clamp $clamp, -overflow")
            assertEquals(0.0, RobustBaseline.z(1e308, RobustBaseline.Stats(-1e308, 1.0, 7), noiseFloor = 5.0, clamp = clamp), "clamp $clamp, overflowing difference")
        }
        assertEquals(-4.0, RobustBaseline.z(70.0, flat, noiseFloor = 0.0, clamp = -4.0), "a negative clamp still bounds an overflow")
        assertEquals(4.0, RobustBaseline.z(80.0, flat, noiseFloor = 5.0), "exactly the clamp")
        assertEquals((-0.0).toRawBits(), RobustBaseline.z(-0.0, RobustBaseline.Stats(0.0, 0.0, 7), noiseFloor = 5.0).toRawBits(), "-0.0 survives")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun circularMedianNormalisesEveryIntBreaksTiesByInputOrderAndFinishesOnALongHistory() {
        // Measured on the pinned build (its Int is 64-bit; these are the 32-bit extremes).
        assertEquals(1312, RobustBaseline.circularMedianMinutes(listOf(Int.MIN_VALUE)))
        assertEquals(127, RobustBaseline.circularMedianMinutes(listOf(Int.MAX_VALUE)))
        assertEquals(0, RobustBaseline.circularMedianMinutes(listOf(Int.MIN_VALUE, Int.MAX_VALUE, 0, -1)))
        // An exact tie between two rotations keeps the first candidate in input order, as upstream.
        assertEquals(360, RobustBaseline.circularMedianMinutes(listOf(0, 720)))
        assertEquals(1080, RobustBaseline.circularMedianMinutes(listOf(720, 0)))
        assertEquals(0, RobustBaseline.circularMedianMinutes(listOf(0, 480, 960)))
        assertEquals(960, RobustBaseline.circularMedianMinutes(listOf(960, 480, 0)))
        assertEquals(0, RobustBaseline.circularMedianMinutes(listOf(1430, 1430, 10, 10)), "duplicated bedtimes")
        // A half-minute median rounds half away from zero (Swift's rounded()): 100.5 -> 101, and from
        // 23:59 the half minute rounds up and wraps to midnight.
        assertEquals(101, RobustBaseline.circularMedianMinutes(listOf(100, 101)))
        assertEquals(0, RobustBaseline.circularMedianMinutes(listOf(1439, 0)))
        // The search is quadratic: upstream took 7.4 s for 3 000 bedtimes on its debug build. A long
        // history must still finish (the headache index passes at most its 60-day window).
        val rng = Random(0x4342_4544)
        val long = List(3000) { rng.nextInt(0, 1440) }
        val m = assertNotNull(RobustBaseline.circularMedianMinutes(long))
        assertTrue(m in 0..1439)
    }

    @Test
    fun circularDeltaIsExactForEveryIntPair() {
        // Upstream subtracts in 64 bits (and traps on Int.max - (-1), measured exit 133). The port
        // subtracts two Ints in 64 bits, so no pair wraps: a 32-bit subtraction would give 1 here.
        assertEquals(255, RobustBaseline.circularDeltaMinutes(Int.MAX_VALUE, Int.MIN_VALUE))
        assertEquals(255, RobustBaseline.circularDeltaMinutes(Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(128, RobustBaseline.circularDeltaMinutes(Int.MAX_VALUE, -1))
        assertEquals(20, RobustBaseline.circularDeltaMinutes(-10, 10))
        assertEquals(6, RobustBaseline.circularDeltaMinutes(1440 * 5 + 3, -1440 * 7 - 3))
        // Every answer is a short-way-round distance: 0 … 720, symmetric.
        val rng = Random(0x4344_4c54)
        repeat(2000) {
            val a = rng.nextInt()
            val b = rng.nextInt()
            val d = RobustBaseline.circularDeltaMinutes(a, b)
            assertTrue(d in 0..720, "$a $b -> $d")
            assertEquals(d, RobustBaseline.circularDeltaMinutes(b, a))
            assertEquals(Math.floorMod(a.toLong() - b.toLong(), 1440L).let { minOf(it, 1440 - it) }.toInt(), d)
        }
    }
}
