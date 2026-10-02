package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the active-energy write window: what the upstream vectors never
 * feed in. The anchor and the not-before floor are STORED dates (an unset one reads back as the
 * epoch), so here they arrive unset, at the epoch, at the day start, at `now`, far in the future and
 * at both ends of `Instant`'s range; the delta's energy arrives NaN, infinite, negative, subnormal and
 * astronomically large; and `now` sits on the day start or at the end of time. Kept out of the
 * upstream-port class so its count stays exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). A Swift
 * `Date` has no end where an `Instant` does: here the window saturates at the end of the range
 * instead of throwing, and it never starts before its day or ends anywhere but `now`.
 */
class ActiveEnergyWindowHazardTest {

    private val day: Instant = Instant.ofEpochSecond(1_753_660_800)
    private fun at(hours: Double): Instant = day.plusMillis(Math.round(hours * 3_600_000))
    private fun offsets(w: DateInterval?): Pair<Long, Long>? = w?.let { secondsBetween(day, it.start).toLong() to secondsBetween(day, it.end).toLong() }
    private fun resolve(anchor: Instant?, notBefore: Instant?, now: Instant, kcal: Double = 0.0) =
        ActiveEnergyWindow.resolve(anchor = anchor, notBefore = notBefore, now = now, dayStart = day, kcal = kcal)

    @Test
    fun energyThatIsNotAPositiveFiniteNumber() {
        val now = at(9.0).plusSeconds(44)
        // NaN, negative, -infinity and the smallest positive double do not widen (measured).
        for (k in listOf(Double.NaN, -100.0, Double.NEGATIVE_INFINITY, Double.MIN_VALUE, 0.0, -0.0)) {
            assertEquals(32_400L to 32_444L, offsets(resolve(at(9.0), null, now, k)), "kcal $k")
        }
        // +infinity and 1e300 need more time than the day holds: the window starts at the day (measured).
        for (k in listOf(Double.POSITIVE_INFINITY, 1e300, Double.MAX_VALUE)) {
            assertEquals(0L to 32_444L, offsets(resolve(at(9.0), null, now, k)), "kcal $k")
        }
        // The same through the internal widening step: reversed spans, the exact plausible width, and a
        // day start after the end (upstream returns the day start; resolve then finds no window).
        assertEquals(28_770L, secondsBetween(day, ActiveEnergyWindow.widenedStart(at(9.0), at(8.0), 10.0, day)).toLong())
        assertEquals(at(9.0), ActiveEnergyWindow.widenedStart(at(9.0), at(9.5), Double.NaN, day))
        assertEquals(day, ActiveEnergyWindow.widenedStart(at(9.0), at(9.5), Double.POSITIVE_INFINITY, day))
        assertEquals(at(9.0), ActiveEnergyWindow.widenedStart(at(9.0), at(9.0).plusSeconds(570), 190.0, day), "570 s is exactly plausible for 190 kcal")
        assertEquals(at(9.0).minusSeconds(1), ActiveEnergyWindow.widenedStart(at(9.0), at(9.0).plusSeconds(569), 190.0, day))
        assertEquals(at(10.0), ActiveEnergyWindow.widenedStart(at(9.0), at(9.01), 50.0, at(10.0)))
    }

    @Test
    fun unsetEpochFarFutureAndEdgeAnchorsAndFloors() {
        // An anchor at now: no window without energy; with energy the window widens back over time the
        // last write already covered, as upstream (measured: 30 s for 10 kcal).
        assertNull(resolve(at(9.0), null, at(9.0)))
        assertEquals(32_370L to 32_400L, offsets(resolve(at(9.0), null, at(9.0), 10.0)))
        // An anchor AT the day start is not one written today: the first-flush floor applies (measured).
        assertEquals(25_200L to 32_400L, offsets(resolve(day, at(7.0), at(9.0))))
        // The unset anchor (the epoch) and a far-future one are discarded (measured).
        assertEquals(25_200L to 32_400L, offsets(resolve(Instant.EPOCH, at(7.0), at(9.0))))
        assertEquals(0L to 32_400L, offsets(resolve(Instant.ofEpochSecond(30_000_000_000_000_000), null, at(9.0))))
        // A floor at now or at the day start, a floor long past, a huge first delta: the day start (measured).
        assertEquals(0L to 32_400L, offsets(resolve(null, at(9.0), at(9.0))))
        assertEquals(0L to 32_400L, offsets(resolve(null, day, at(9.0))))
        assertEquals(0L to 32_400L, offsets(resolve(null, Instant.ofEpochSecond(-62_135_769_600), at(9.0))))
        assertEquals(0L to 32_400L, offsets(resolve(null, at(7.0), at(9.0), 1e6)))
        // No window when now is the day start.
        assertNull(resolve(null, null, day))
    }

    @Test
    fun instantsAtTheEndsOfTimeNeitherThrowNorEscapeTheDay() {
        assertEquals(0L to 32_400L, offsets(resolve(Instant.MAX, null, at(9.0))), "an anchor at the end of time is discarded")
        assertEquals(0L to 32_400L, offsets(resolve(Instant.MIN, Instant.MIN, at(9.0))), "an anchor at the start of time is not today's")
        val toTheEnd = assertNotNull(resolve(null, null, Instant.MAX, Double.POSITIVE_INFINITY))
        assertEquals(day to Instant.MAX, toTheEnd.start to toTheEnd.end)
        val wholeRange = assertNotNull(ActiveEnergyWindow.resolve(null, null, Instant.MAX, Instant.MIN, 1e300))
        assertEquals(Instant.MIN to Instant.MAX, wholeRange.start to wholeRange.end, "widening saturates at the start of time")
        val anchored = assertNotNull(ActiveEnergyWindow.resolve(Instant.MAX, null, Instant.MAX, Instant.MIN, 5.0))
        assertEquals(Instant.MAX.minusSeconds(15) to Instant.MAX, anchored.start to anchored.end)
        assertNull(ActiveEnergyWindow.resolve(null, null, Instant.MAX, Instant.MAX, 1e300), "no time after the end of time")
    }

    @Test
    fun everyWindowStaysInsideItsDayEndsAtNowAndNeverImpliesAnImplausibleRate() {
        // A sweep of seeded hostile combinations. Properties of every answer: when now is not after the
        // day start there is no window; otherwise any window starts no earlier than the day start, ends at
        // now, is not empty, and is at least as long as 20 kcal a minute needs unless it was clamped to
        // the day start.
        val rng = Random(20_261_003)
        val instants = listOf(null, Instant.EPOCH, Instant.MIN, Instant.MAX, day, day.minusSeconds(1), day.plusSeconds(1))
        val energies = listOf(0.0, -0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MIN_VALUE, 1e300, 0.001)
        var windows = 0
        repeat(20_000) {
            fun pick(): Instant? = if (rng.nextInt(4) == 0) instants[rng.nextInt(instants.size)] else day.plusMillis(rng.nextLong(-86_400_000L, 2 * 86_400_000L))
            val anchor = pick()
            val notBefore = pick()
            val now = pick() ?: day.plusSeconds(3600)
            val kcal = if (rng.nextInt(3) == 0) energies[rng.nextInt(energies.size)] else rng.nextDouble(0.0, 3000.0)
            val w = resolve(anchor, notBefore, now, kcal)
            if (!(now > day)) {
                assertNull(w, "now $now is not after the day start")
                return@repeat
            }
            if (w == null) return@repeat
            windows++
            assertTrue(w.start >= day && w.start < w.end && w.end == now, "anchor $anchor floor $notBefore now $now kcal $kcal → $w")
            if (kcal > 0 && kcal.isFinite() && w.start > day) {
                val needed = kcal / ActiveEnergyWindow.MAX_PLAUSIBLE_KCAL_PER_MINUTE * 60.0
                assertTrue(secondsBetween(w.start, w.end) >= needed - 2e-9, "a $kcal kcal window of ${secondsBetween(w.start, w.end)} s")
            }
        }
        assertTrue(windows > 5_000, "the sweep must reach windows: $windows")
    }
}
