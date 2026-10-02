package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin-only hostile-input checks for the sleep-window caption: the latency clause at and around its
 * bounds, non-finite / zero / negative / subnormal in-bed bases, a span exactly at the tolerance,
 * non-finite spans, and onset/wake at the ends of `Instant`'s range. Kept out of the upstream-port
 * class so its count stays exact.
 *
 * Every expected string was printed by upstream's pinned Swift build with the same inputs and the same
 * fixed clock, then pasted here.
 */
class SleepWindowCaptionHazardTest {

    private val ampm: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(ZoneOffset.UTC)
    private val clock: (Instant) -> String = { ampm.format(it) }
    private val epochClock: (Instant) -> String = { it.epochSecond.toString() }

    private fun at(hour: Long, minute: Long): Instant = Instant.ofEpochSecond(hour * 3600 + minute * 60)

    private fun line(onset: Instant?, wake: Instant?, inBedStart: Instant?, inBed: Double): String? =
        SleepWindowCaption.line(onset = onset, wake = wake, inBedStart = inBedStart, measuredInBed = inBed, clock = clock)

    @Test
    fun latencyClauseBoundariesAsUpstream() {
        val onset = at(0, 24)
        val cases = listOf(
            0L to "Asleep 12:24 AM–8:49 AM",
            30_000L to "Asleep 12:24 AM–8:49 AM",
            59_999L to "Asleep 12:24 AM–8:49 AM",
            60_000L to "Asleep 12:24 AM–8:49 AM · 1m to fall asleep",
            89_999L to "Asleep 12:24 AM–8:49 AM · 1m to fall asleep",
            90_000L to "Asleep 12:24 AM–8:49 AM · 2m to fall asleep",
            149_999L to "Asleep 12:24 AM–8:49 AM · 2m to fall asleep",
            150_000L to "Asleep 12:24 AM–8:49 AM · 3m to fall asleep",
            210_000L to "Asleep 12:24 AM–8:49 AM · 4m to fall asleep",
            14_399_000L to "Asleep 12:24 AM–8:49 AM · 240m to fall asleep",
            14_399_999L to "Asleep 12:24 AM–8:49 AM · 240m to fall asleep",
            14_400_000L to "Asleep 12:24 AM–8:49 AM",
            -60_000L to "Asleep 12:24 AM–8:49 AM",
        )
        for ((latencyMillis, expected) in cases) {
            assertEquals(expected, line(onset, at(8, 49), onset.minusMillis(latencyMillis), 8 * 3600 + 30 * 60.0), "latency $latencyMillis ms")
        }
    }

    @Test
    fun nonFiniteZeroNegativeAndTinyBasesAsUpstream() {
        val plain = "Asleep 1:24 AM–12:27 PM"
        val stitched = "Asleep between 1:24 AM and 12:27 PM · nothing was recorded across part of that 11h 3m window"
        val cases = listOf(
            Double.NaN to plain, Double.POSITIVE_INFINITY to plain, Double.NEGATIVE_INFINITY to plain, -1.0 to plain,
            0.0 to plain, -0.0 to plain, Double.MIN_VALUE to stitched, 1.0 to stitched, 34_695.0 to plain, 34_694.0 to plain,
        )
        for ((basis, expected) in cases) {
            assertEquals(expected, line(at(1, 24), at(12, 27), null, basis), "basis $basis")
        }
    }

    @Test
    fun thePredicateOnExactAndNonFiniteSpansAsUpstream() {
        val basis = 30_000.0 / 1.15
        assertEquals(30_000.0, basis * SleepWindowCaption.CONTIGUOUS_TOLERANCE, "the probe sits exactly on the tolerance")
        assertEquals(true, SleepWindowCaption.isContiguous(span = 30_000.0, measuredInBed = basis), "inclusive at the tolerance")
        assertEquals(false, SleepWindowCaption.isContiguous(span = Double.NaN, measuredInBed = 3_600.0))
        assertEquals(false, SleepWindowCaption.isContiguous(span = Double.POSITIVE_INFINITY, measuredInBed = 3_600.0))
        assertEquals(true, SleepWindowCaption.isContiguous(span = -5.0, measuredInBed = 3_600.0))
        assertEquals(true, SleepWindowCaption.isContiguous(span = Double.POSITIVE_INFINITY, measuredInBed = Double.POSITIVE_INFINITY))
    }

    @Test
    fun stitchedWindowsAtTheFormatterBoundariesAsUpstream() {
        assertEquals(
            "Asleep between 12:00 AM and 10:00 AM · nothing was recorded across part of that 10 hours window",
            line(at(0, 0), at(10, 0), null, 3_600.0),
        )
        assertEquals(
            "Asleep between 12:00 AM and 12:59 AM · nothing was recorded across part of that 1 hour window",
            line(at(0, 0), Instant.ofEpochSecond(3_570), null, 60.0),
        )
    }

    @Test
    fun theEndsOfTimeRenderWithoutThrowing() {
        // Measured upstream at ±3e16 s, rendered with a clock that prints whole epoch seconds.
        val farPast = Instant.ofEpochSecond(-30_000_000_000_000_000)
        val farFuture = Instant.ofEpochSecond(30_000_000_000_000_000)
        fun caption(onset: Instant, wake: Instant, inBed: Double) =
            SleepWindowCaption.line(onset, wake, onset.minusSeconds(100), inBed, epochClock)
        assertEquals(
            "Asleep between -30000000000000000 and 30000000000000000 · nothing was recorded across part of that 16666666666666h 40m window",
            caption(farPast, farFuture, 3_600.0),
        )
        assertEquals("Asleep -30000000000000000–30000000000000000 · 2m to fall asleep", caption(farPast, farFuture, 0.0))
        // The whole range of `Instant` (a `Date` cannot hold it exactly).
        assertEquals(
            "Asleep between -31557014167219200 and 31556889864403199 · nothing was recorded across part of that 17531640008784 hours window",
            SleepWindowCaption.line(Instant.MIN, Instant.MAX, Instant.MAX, 3_600.0, epochClock),
        )
    }
}
