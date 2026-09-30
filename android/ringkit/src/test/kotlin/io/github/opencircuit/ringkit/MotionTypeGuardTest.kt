package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

/**
 * Kotlin-only checks that the motion port keeps what Swift's types guaranteed for free: `UInt8`
 * motion bytes compare and sum unsigned, `struct`s and arrays are values, Foundation's
 * `DateInterval.contains` includes the end instant, and Swift's `rounded()` rounds half away from
 * zero. Kept out of the upstream-port test classes so their counts stay exact.
 */
class MotionTypeGuardTest {

    private fun rec(counter: Long, motion: List<Int> = listOf(1, 1, 1, 1, 1), tail: List<Int> = listOf(0, 0, 0, 0, 0)): BulkRecord {
        val b = ByteArray(BulkRecord.LENGTH)
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        b[4] = 60; b[5] = 45; b[7] = 120; b[8] = 96
        for (i in 0 until 5) { b[10 + i] = motion[i].toByte(); b[15 + i] = tail[i].toByte() }
        return assertNotNull(BulkRecord.of(b))
    }

    @Test
    fun motionBytesAreReadUnsignedEverywhere() {
        // 0xF0 must rank ABOVE 0x10; a signed read makes it -16 and flips orderings, sums and minima.
        val r = rec(1000, motion = listOf(0x10, 0xF0, 0x10, 0xF0, 0x10), tail = listOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
        assertEquals(List(5) { if (it % 2 == 1) 240f else 16f }, BulkSleep.motionTimeline(listOf(r)).map { it.movement })
        assertEquals(listOf((0x10 * 3 + 0xF0 * 2).toFloat()), BulkSleep.motionMagnitudes(listOf(r)))
        assertEquals(listOf(16f), BulkSleep.motionIntensityFallbackMagnitudes(listOf(r), degenerate = false), "tail sum 1275 is above the seam")
        assertEquals(0x10, BulkSleep.medianQuietMinimum(listOf(r)))
        // Slot 1 sits above the others on both epochs only when 0xF0 is read as 240.
        val a = rec(1000, motion = listOf(0x10, 0xF0, 0x10, 0x10, 0x10))
        val b = rec(1150, motion = listOf(0x10, 0x70, 0x10, 0x10, 0x10))
        assertEquals(1.0, BulkSleep.slotOrderConsistency(listOf(a, b)), "the ordering is consistent read unsigned")
    }

    @Test
    fun recordsWithinKeepsBothEndInstantsLikeFoundationDateInterval() {
        val recs = listOf(rec(1000), rec(1150), rec(1300), rec(1450))
        val start = recs[1].date()
        val end = recs[2].date()
        assertEquals(listOf(recs[1], recs[2]), BulkSleep.records(recs, within = DateInterval(start, end)),
            "the window is closed: an epoch exactly on its end is kept")
        assertEquals(listOf(recs[1]), BulkSleep.records(recs, within = DateInterval(start, start)), "a zero-length window keeps its instant")
    }

    @Test
    fun recordsWithoutAHintIsAnIndependentCopy() {
        val input = mutableListOf(rec(1000), rec(1150))
        val out = BulkSleep.records(input, within = null)
        assertEquals<List<BulkRecord>>(input, out)
        assertNotSame<List<BulkRecord>>(input, out)
        input.clear()
        assertEquals(2, out.size, "a caller's later edit to its own list never changes the result")
    }

    @Test
    fun contiguousFragmentsSortsAndSplitsOnlyAboveTheGap() {
        val gap = ActivityPeriod.GRAVITY_MAX_GAP.seconds
        val a = rec(0xFFFF_0000L)
        val b = rec(0xFFFF_0000L + gap) // exactly the max gap: same run
        val c = rec(0xFFFF_0000L + 2 * gap + 1) // one second over: new run
        assertEquals(listOf(listOf(a, b), listOf(c)), BulkSleep.contiguousFragments(listOf(c, a, b)),
            "counters above 2^31 still order and subtract as unsigned")
        assertEquals(emptyList(), BulkSleep.contiguousFragments(emptyList()))
    }

    @Test
    fun rollingLowPercentileRejectsUnpairedInputAndHandlesEdges() {
        val t0 = Instant.ofEpochSecond(1_700_000_000)
        assertFailsWith<IllegalArgumentException> {
            ActivityPeriod.rollingLowPercentile(listOf(1f, 2f), listOf(t0), Duration.ofMinutes(30), 0.10)
        }
        assertEquals(emptyList(), ActivityPeriod.rollingLowPercentile(emptyList(), emptyList(), Duration.ofMinutes(30), 0.10))
        // A window with no worn sample floors at 0, so the unworn sentinel de-floors to itself (active).
        assertEquals(listOf(Float.MAX_VALUE), ActivityPeriod.motionAboveLocalFloor(listOf(MotionSample(t0, Float.MAX_VALUE))))
    }

    @Test
    fun percentileIndexRoundsHalfAwayFromZeroAsSwiftDoes() {
        // Six worn samples in one window: (6 - 1) * 0.1 = 0.5 -> Swift rounds to index 1, the 2nd smallest.
        val t0 = Instant.ofEpochSecond(1_700_000_000)
        val times = List(6) { t0.plusSeconds(it * 30L) }
        assertEquals(List(6) { 2f }, ActivityPeriod.rollingLowPercentile(listOf(5f, 1f, 2f, 3f, 4f, 6f), times, Duration.ofMinutes(30), 0.10))
    }

    @Test
    fun sampleTypesAndPolicyAreValues() {
        val t = Instant.ofEpochSecond(1_700_000_000)
        assertEquals(MotionSample(t, 1f), MotionSample(t, 1f))
        assertEquals(TemperatureSample(t, 33.5), TemperatureSample(t, 33.5))
        assertEquals(HeartRateSample(t, 58), HeartRateSample(t, 58))
        assertEquals(BulkSleep.MotionChannelPolicy.DEFAULT, BulkSleep.MotionChannelPolicy())
        assertEquals(BulkSleep.MotionChannelPolicy.DEFAULT.hashCode(), BulkSleep.MotionChannelPolicy().hashCode())
        assertNotEquals(BulkSleep.MotionChannelPolicy.DEFAULT, BulkSleep.MotionChannelPolicy(magnitudeChannelEnabled = true))
        assertEquals(250, BulkSleep.MotionChannelPolicy.DEFAULT.magnitudeActiveCut)
    }

    @Test
    fun timelinesSkipIdleAndReadOnlyStrictSleepVitals() {
        val sv = rec(1000)
        val idle = assertNotNull(BulkRecord.of(hex("0c099dbf05000c00120a01010101010000000000000000")))
        val quietActivity = assertNotNull(BulkRecord.of(hex("0c22a16b55210a7d120a01010101010000000000040000")))
        assertEquals(
            listOf(HeartRateSample(sv.date(), 60), HeartRateSample(quietActivity.date(), 0x55)),
            BulkSleep.heartRateTimeline(listOf(sv, idle, quietActivity)),
        )
        assertEquals(listOf(sv.date()), BulkSleep.sleepVitalTimeline(listOf(sv, idle, quietActivity)))
    }
}
