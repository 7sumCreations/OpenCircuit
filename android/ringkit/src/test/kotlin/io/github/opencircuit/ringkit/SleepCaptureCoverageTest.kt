package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Was last night FULLY captured, or limited by the ring's onboard memory? Truncation drops the FRONT
 * of the night, so only a span that fits the buffer AND an onset well after the scheduled bedtime is
 * flagged — never duration alone.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepCaptureCoverageTests.swift
 * (@ b1c2fdd) — all 9 tests. Spans are `Duration`s here (`h(x)` = x hours, to the millisecond).
 */
class SleepCaptureCoverageTest {

    private fun h(x: Double): Duration = Duration.ofMillis((x * 3_600_000).roundToLong())

    /** Bedtime 22:30; onset/wake built relative to it. */
    private fun bedtime(): Instant = Instant.ofEpochSecond(1_780_000_000)

    /**
     * The reported bug shape: bed 22:30, real wake ~07:00, but only the last ~4.75 h drained, so the
     * captured onset is ~02:15 (≈3.75 h after bedtime) — the front of the night is missing.
     */
    @Test
    fun missingFrontIsTruncated() {
        val bed = bedtime()
        val onset = bed.plus(h(3.75))
        assertEquals(
            SleepCaptureCoverage.Coverage.LIKELY_TRUNCATED,
            SleepCaptureCoverage.classify(capturedOnset = onset, capturedInBed = h(4.75), scheduledBedtime = bed),
        )
    }

    /**
     * A short night that FIT in the buffer (onset at bedtime, woke early after 4.5 h) is complete, not
     * truncated — the false positive duration-only flagging would have produced.
     */
    @Test
    fun shortNightThatFitTheBufferIsFull() {
        val bed = bedtime()
        assertEquals(SleepCaptureCoverage.Coverage.FULL, SleepCaptureCoverage.classify(capturedOnset = bed, capturedInBed = h(4.5), scheduledBedtime = bed))
    }

    /** A full night (span beyond the buffer) is complete regardless of onset — it was drained overnight. */
    @Test
    fun overBufferIsFull() {
        val bed = bedtime()
        val onset = bed.plus(h(4.0)) // even a late onset
        assertEquals(SleepCaptureCoverage.Coverage.FULL, SleepCaptureCoverage.classify(capturedOnset = onset, capturedInBed = h(8.0), scheduledBedtime = bed))
    }

    /** Onset only slightly after bedtime (< the missing-onset threshold) is not a missing front. */
    @Test
    fun onsetNearBedtimeIsFull() {
        val bed = bedtime()
        val onset = bed.plusSeconds(30 * 60)
        assertEquals(SleepCaptureCoverage.Coverage.FULL, SleepCaptureCoverage.classify(capturedOnset = onset, capturedInBed = h(4.6), scheduledBedtime = bed))
    }

    /** Onset BEFORE bedtime (went to bed early) is never truncated. */
    @Test
    fun onsetBeforeBedtimeIsFull() {
        val bed = bedtime()
        val onset = bed.minusSeconds(20 * 60)
        assertEquals(SleepCaptureCoverage.Coverage.FULL, SleepCaptureCoverage.classify(capturedOnset = onset, capturedInBed = h(4.5), scheduledBedtime = bed))
    }

    /** No schedule ⇒ no bedtime reference ⇒ never flagged (avoids nagging when we can't tell). */
    @Test
    fun noScheduleIsFull() {
        val onset = bedtime().plus(h(4.0))
        assertEquals(SleepCaptureCoverage.Coverage.FULL, SleepCaptureCoverage.classify(capturedOnset = onset, capturedInBed = h(4.75), scheduledBedtime = null))
    }

    /** Degenerate / empty spans are treated as "can't tell" → full. */
    @Test
    fun zeroSpanIsFull() {
        val bed = bedtime()
        assertEquals(SleepCaptureCoverage.Coverage.FULL, SleepCaptureCoverage.classify(capturedOnset = bed, capturedInBed = Duration.ZERO, scheduledBedtime = bed))
        assertEquals(SleepCaptureCoverage.Coverage.FULL, SleepCaptureCoverage.classify(capturedOnset = bed, capturedInBed = Duration.ofSeconds(-10), scheduledBedtime = bed))
    }

    /** Boundary: a span just past the buffer + slack is full even with a late onset. */
    @Test
    fun justOverBufferPlusSlackIsFull() {
        val bed = bedtime()
        val onset = bed.plus(h(3.0))
        assertEquals(
            SleepCaptureCoverage.Coverage.FULL,
            SleepCaptureCoverage.classify(
                capturedOnset = onset,
                capturedInBed = SleepCaptureCoverage.RING_BUFFER.plus(SleepCaptureCoverage.BUFFER_SLACK).plusSeconds(60),
                scheduledBedtime = bed,
            ),
        )
    }

    /** Boundary: onset exactly the missing-onset threshold after bedtime, span within buffer ⇒ truncated. */
    @Test
    fun onsetExactlyAtThresholdIsTruncated() {
        val bed = bedtime()
        val onset = bed.plus(SleepCaptureCoverage.MIN_MISSING_ONSET)
        assertEquals(
            SleepCaptureCoverage.Coverage.LIKELY_TRUNCATED,
            SleepCaptureCoverage.classify(capturedOnset = onset, capturedInBed = h(4.75), scheduledBedtime = bed),
        )
    }
}
