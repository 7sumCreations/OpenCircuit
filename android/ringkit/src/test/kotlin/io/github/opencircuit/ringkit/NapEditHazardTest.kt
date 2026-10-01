package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Kotlin-only hostile-input checks for the manual nap edit: the exact length limits, `now` at either
 * end of the window, a night or another nap touching or of zero length, a reversed window that is
 * also in the future, the 09:00 / 21:00 daytime edges, both 2026 DST days, the zone the day is judged
 * in, and windows `java.time` cannot place in a zone. Kept out of the upstream-port class so its count
 * stays exact.
 *
 * Every expected value was measured on upstream's pinned Swift build with the device zone set to the
 * zone named here; days are anchored at local midnight (New York unless stated) plus ABSOLUTE hours,
 * as upstream's tests anchor them.
 */
class NapEditHazardTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val june15 = Instant.ofEpochSecond(1_781_496_000) // 2026-06-15 00:00 New York
    private val march8 = Instant.ofEpochSecond(1_772_946_000) // 2026-03-08 00:00 New York (spring forward)
    private val november1 = Instant.ofEpochSecond(1_793_505_600) // 2026-11-01 00:00 New York (fall back)

    private fun at(h: Double, midnight: Instant = june15): Instant = midnight.plusSeconds((h * 3600).toLong())
    private fun win(a: Double, b: Double, midnight: Instant = june15) = NapEdit.Window(at(a, midnight), at(b, midnight))

    @Test
    fun lengthLimitsAreExact() {
        assertNull(NapEdit.validate(win(10.0, 16.0), newYork)) // exactly 6 h
        assertEquals(NapEdit.Invalid.TooLong(maxHours = 6), NapEdit.validate(NapEdit.Window(at(10.0), at(16.0).plusSeconds(1)), newYork))
        assertEquals(NapEdit.Invalid.TooShort(minMinutes = 15), NapEdit.validate(NapEdit.Window(at(14.0), at(14.25).minusSeconds(1)), newYork))
        assertEquals(NapEdit.Invalid.EndNotAfterStart, NapEdit.validate(win(15.0, 14.0), newYork, now = at(10.0)), "reversed AND future: the order rule first")
    }

    @Test
    fun nowAtEitherEndOfTheWindow() {
        assertNull(NapEdit.validate(win(14.0, 15.0), newYork, now = at(15.0)))
        assertEquals(NapEdit.Invalid.InFuture, NapEdit.validate(win(14.0, 15.0), newYork, now = at(14.0)))
    }

    @Test
    fun touchingAndZeroLengthNeighbours() {
        // Touching the night's end is no overlap, so the daytime gate decides (07:00-08:00 is not daytime).
        assertEquals(NapEdit.Invalid.NotDaytime, NapEdit.validate(win(7.0, 8.0), newYork, night = DateInterval(at(-1.0), at(7.0))))
        assertNull(NapEdit.validate(win(14.0, 15.0), newYork, night = DateInterval(at(15.0), at(23.0))))
        // A zero-length night or nap inside the window still overlaps it (strict comparisons both ways).
        assertEquals(NapEdit.Invalid.OverlapsNight, NapEdit.validate(win(14.0, 15.0), newYork, night = DateInterval(at(14.5), at(14.5))))
        assertEquals(NapEdit.Invalid.OverlapsNap, NapEdit.validate(win(14.0, 15.0), newYork, otherNaps = listOf(DateInterval(at(14.5), at(14.5)))))
    }

    @Test
    fun theDaytimeEdgesAreTheMidpointsAtNineAndTwentyOne() {
        assertNull(NapEdit.validate(win(20.5, 21.0), newYork)) // midpoint 20:45
        assertEquals(NapEdit.Invalid.NotDaytime, NapEdit.validate(win(20.5, 21.5), newYork)) // midpoint 21:00
        assertNull(NapEdit.validate(win(8.5, 9.5), newYork)) // midpoint 09:00
        assertEquals(NapEdit.Invalid.NotDaytime, NapEdit.validate(win(8.0, 9.5), newYork)) // midpoint 08:45
    }

    @Test
    fun dstDaysAreJudgedOnTheLocalWallClock() {
        val hours = listOf(8.0 to 9.0, 9.0 to 10.0, 20.0 to 21.0, 21.0 to 22.0, 14.0 to 15.0, 1.5 to 2.5)
        fun verdicts(midnight: Instant, zone: ZoneId) = hours.map { (a, b) -> NapEdit.validate(win(a, b, midnight), zone)?.toString() ?: "nil" }
        val nd = NapEdit.Invalid.NotDaytime.toString()
        // Spring forward: 8 absolute hours after midnight is 09:00 local, so 8-9 is a daytime nap.
        assertEquals(listOf("nil", "nil", nd, nd, "nil", nd), verdicts(march8, newYork))
        // Fall back: 9 absolute hours after midnight is 08:00 local, so 9-10 is still before 09:00.
        assertEquals(listOf(nd, nd, "nil", "nil", "nil", nd), verdicts(november1, newYork))
        assertEquals(listOf(nd, "nil", "nil", nd, "nil", nd), verdicts(june15, newYork))
        // The same instants judged in Tokyo.
        val tokyo = ZoneId.of("Asia/Tokyo")
        for (midnight in listOf(march8, november1, june15)) {
            assertEquals(listOf(nd, nd, "nil", "nil", nd, "nil"), verdicts(midnight, tokyo), "$midnight in Tokyo")
        }
    }

    /** An instant `java.time` cannot place is not a daytime nap (upstream's calendar answers the same far out). */
    @Test
    fun windowsThatCannotBePlacedInTheZoneAreNotDaytime() {
        val hour = Duration.ofHours(1)
        assertEquals(NapEdit.Invalid.NotDaytime, NapEdit.validate(NapEdit.Window(Instant.MIN, Instant.MIN.plus(hour)), newYork))
        assertEquals(NapEdit.Invalid.NotDaytime, NapEdit.validate(NapEdit.Window(Instant.MAX.minus(hour), Instant.MAX), newYork))
        assertEquals(NapEdit.Invalid.TooLong(maxHours = 6), NapEdit.validate(NapEdit.Window(Instant.MIN, Instant.MAX), newYork))
    }
}
