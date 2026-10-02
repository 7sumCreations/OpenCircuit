package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manual nap edit / add validation: a daytime block of 15 min to 6 h that ends in the past and
 * overlaps neither the main night nor another nap.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/NapEditTests.swift (@ b1c2fdd) —
 * all 10 tests. Upstream anchors at local midnight "today" on the device calendar, and its daytime
 * gate reads the device calendar; here the day is fixed (2026-06-15, no DST change) and the zone is
 * named (America/New_York) and passed to `validate`, so each hour means the same local time on both
 * sides.
 */
class NapEditTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val midnight: Instant = LocalDate.of(2026, 6, 15).atStartOfDay(zone).toInstant()

    /** `at(14)` = 2 pm local; `at(2)` = 2 am; `at(-1)` = 11 pm the day before. */
    private fun at(hoursFromMidnight: Double): Instant = midnight.plusNanos((hoursFromMidnight * 3600e9).roundToLong())
    private fun win(a: Double, b: Double) = NapEdit.Window(at(a), at(b))

    @Test
    fun validDaytimeNap() {
        assertNull(NapEdit.validate(win(14.0, 14.75), zone))
        assertTrue(NapEdit.isValid(win(14.0, 14.75), zone))
    }

    @Test
    fun endNotAfterStart() {
        assertEquals(NapEdit.Invalid.EndNotAfterStart, NapEdit.validate(win(14.0, 14.0), zone))
        assertEquals(NapEdit.Invalid.EndNotAfterStart, NapEdit.validate(win(15.0, 14.0), zone))
    }

    @Test
    fun tooShort() {
        assertEquals(NapEdit.Invalid.TooShort(minMinutes = 15), NapEdit.validate(win(14.0, 14.0 + 10.0 / 60.0), zone))
    }

    @Test
    fun exactMinimumAllowed() {
        assertNull(NapEdit.validate(win(14.0, 14.25), zone)) // exactly 15 min
    }

    @Test
    fun tooLong() {
        assertEquals(NapEdit.Invalid.TooLong(maxHours = 6), NapEdit.validate(win(13.0, 20.0), zone)) // 7 h
    }

    @Test
    fun rejectsOvernightNap() {
        // A 2:00-2:30 am block, no night/overlap conflict -> rejected as not daytime.
        assertEquals(NapEdit.Invalid.NotDaytime, NapEdit.validate(win(2.0, 2.5), zone))
    }

    @Test
    fun rejectsFutureNap() {
        // now = 1 pm; a 2:00-2:30 pm nap ends in the future.
        assertEquals(NapEdit.Invalid.InFuture, NapEdit.validate(win(14.0, 14.5), zone, now = at(13.0)))
        // now = 3 pm; the same nap is in the past -> valid.
        assertNull(NapEdit.validate(win(14.0, 14.5), zone, now = at(15.0)))
    }

    @Test
    fun rejectsOverlapWithNight() {
        // Night 11 pm -> 7 am; a 6:00-6:30 am nap overlaps it (reported as overlap, not "not daytime").
        val night = DateInterval(at(-1.0), at(7.0))
        assertEquals(NapEdit.Invalid.OverlapsNight, NapEdit.validate(win(6.0, 6.5), zone, night = night))
        // A 2 pm nap doesn't overlap that night.
        assertNull(NapEdit.validate(win(14.0, 14.5), zone, night = night))
    }

    @Test
    fun rejectsOverlapWithAnotherNap() {
        val other = listOf(DateInterval(at(14.0), at(15.0)))
        assertEquals(NapEdit.Invalid.OverlapsNap, NapEdit.validate(win(14.5, 15.5), zone, otherNaps = other))
        assertNull(NapEdit.validate(win(15.0, 15.5), zone, otherNaps = other)) // adjacent, no overlap
    }

    @Test
    fun editingAnExistingNapExcludesItselfFromOverlap() {
        assertNull(NapEdit.validate(win(14.0, 15.5), zone, otherNaps = emptyList()))
    }
}
