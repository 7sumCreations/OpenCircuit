package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE TESTER NIGHT THE CAPTION WAS LYING ON, pinned to the character.
 *
 * Gen 2 Air, build 52. Card headline "4h 0m asleep / 4h 21m in bed", caption
 * "Asleep 1:24 AM–12:27 PM · 2m to fall asleep". The wearer reported the contradiction herself. The
 * span is 11 h 3 m; the measured in-bed is 4 h 21 m; the two cannot both describe one continuous
 * interval, and the en-dash claimed they did.
 *
 * Two things are pinned: WHICH rendering a night gets, and WHAT IT SAYS verbatim. The clock closure
 * is a fixed formatter (US English, UTC) so the assertions are locale-independent — the shipped card
 * injects the device's own short-time format, which is the only part of the line this file cannot own.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepWindowCaptionTests.swift
 * (@ b1c2fdd) — all 10 tests.
 */
class SleepWindowCaptionTest {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(ZoneOffset.UTC)
    private val clock: (Instant) -> String = { formatter.format(it) }

    private fun at(hour: Long, minute: Long): Instant = Instant.ofEpochSecond(hour * 3600 + minute * 60)

    private fun line(onset: Instant?, wake: Instant?, inBedStart: Instant?, inBed: Double): String? =
        SleepWindowCaption.line(onset = onset, wake = wake, inBedStart = inBedStart, measuredInBed = inBed, clock = clock)

    // MARK: The defect

    /** The reported night. 01:24 → 12:27 is 11 h 3 m of wall clock over 4 h 21 m of records. */
    @Test
    fun stitchedTesterNightDoesNotClaimAContinuousBlock() {
        val text = line(onset = at(1, 24), wake = at(12, 27), inBedStart = at(1, 22), inBed = 4 * 3600 + 21 * 60.0)
        assertEquals("Asleep between 1:24 AM and 12:27 PM · nothing was recorded across part of " + "that 11h 3m window", text)
    }

    /** The en-dash form is what asserts continuity, so its absence is the fix — not a detail of wording. */
    @Test
    fun stitchedNightDropsTheEnDashAndTheLatencyClause() {
        val text = line(onset = at(1, 24), wake = at(12, 27), inBedStart = at(1, 22), inBed = 4 * 3600 + 21 * 60.0)
        assertNotNull(text)
        assertFalse(text.contains("1:24 AM–12:27 PM"), "an en-dash range asserts one interval")
        assertFalse(text.contains("to fall asleep"), "a 2m latency beside an unrecorded hole reads as precision the night lacks")
    }

    // MARK: The ordinary night is untouched

    @Test
    fun contiguousNightRendersTheRangeAndTheLatency() {
        val text = line(onset = at(0, 24), wake = at(8, 49), inBedStart = at(0, 3), inBed = 8 * 3600 + 30 * 60.0)
        assertEquals("Asleep 12:24 AM–8:49 AM · 21m to fall asleep", text)
    }

    @Test
    fun contiguousNightWithoutMeasurableLatencyRendersTheRangeAlone() {
        val text = line(onset = at(0, 24), wake = at(8, 49), inBedStart = at(0, 24), inBed = 8 * 3600 + 30 * 60.0)
        assertEquals("Asleep 12:24 AM–8:49 AM", text)
    }

    /** A span inside the 15 % slack is rounding and trimmed awake tail, not a hole. */
    @Test
    fun spanWithinToleranceStaysContiguous() {
        val text = line(onset = at(0, 0), wake = at(8, 0), inBedStart = null, inBed = 7 * 3600 + 10 * 60.0)
        assertEquals("Asleep 12:00 AM–8:00 AM", text)
    }

    // MARK: Silence and safe defaults

    @Test
    fun noOnsetYieldsNoCaption() {
        assertNull(line(onset = null, wake = at(8, 49), inBedStart = at(0, 3), inBed = 8 * 3600.0))
        assertNull(line(onset = at(0, 24), wake = null, inBedStart = at(0, 3), inBed = 8 * 3600.0))
    }

    @Test
    fun wakeNotAfterOnsetYieldsNoCaption() {
        assertNull(line(onset = at(8, 49), wake = at(8, 49), inBedStart = at(0, 3), inBed = 8 * 3600.0))
    }

    /**
     * With no in-bed basis there is no positive evidence of a gap, so the plain rendering stands.
     * The predicate is only ever allowed to assert "contiguous"; it may not invent a hole.
     */
    @Test
    fun noMeasuredBasisFallsBackToThePlainRendering() {
        val text = line(onset = at(1, 24), wake = at(12, 27), inBedStart = null, inBed = 0.0)
        assertEquals("Asleep 1:24 AM–12:27 PM", text)
    }

    // MARK: The predicate the three card sites share

    @Test
    fun contiguityPredicateAgreesWithTheInBedLineOnTheTesterNight() {
        val inBed = 4 * 3600 + 21 * 60.0
        // The card suppressed its in-bed clock range on this night; the caption must reach the same
        // verdict from the same numbers, which is the whole reason the predicate is shared.
        assertFalse(SleepWindowCaption.isContiguous(span = 11 * 3600 + 3 * 60.0, measuredInBed = inBed))
        assertTrue(SleepWindowCaption.isContiguous(span = inBed, measuredInBed = inBed))
    }

    @Test
    fun zeroBasisIsTreatedAsContiguous() {
        assertTrue(SleepWindowCaption.isContiguous(span = 11 * 3600.0, measuredInBed = 0.0))
    }
}
