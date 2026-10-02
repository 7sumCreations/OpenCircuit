package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Resting-HR derivation (#18, #37). Pure value-type math.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/RestingHRTests.swift (@ b1c2fdd),
 * all 13 tests. `testDailyValuesEmptyForNoReadings` relied on the default calendar; the port names a
 * zone (UTC), which an empty input never reaches.
 */
class RestingHRTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000) // fixed anchor

    private fun hr(bpm: Int, offset: Double): HRSample = HRSample(bpm, t0.plusMillis(Math.round(offset * 1000)))

    /** Assert an optional Double equals [expected] (the derivations return null when there's no data). */
    private fun assertNear(value: Double?, expected: Double) {
        if (value == null) fail("expected $expected, got null")
        assertEquals(expected, value, 0.001)
    }

    // Sleep mean (preferred tier)

    @Test
    fun sleepMeanAveragesAsleepReadings() { // :25-29
        val sleep = listOf(SleepSegment(t0, t0.plusSeconds(600), SleepStage.ASLEEP_CORE))
        val samples = listOf(hr(60, 0.0), hr(50, 100.0), hr(70, 200.0)) // all inside the segment
        assertNear(RestingHR.sleepMean(samples, sleep, minSleepSamples = 3), 60.0)
    }

    @Test
    fun sleepMeanExcludesAwakeAndOutOfWindow() { // :31-39
        val sleep = listOf(
            SleepSegment(t0, t0.plusSeconds(300), SleepStage.ASLEEP_DEEP),
            SleepSegment(t0.plusSeconds(300), t0.plusSeconds(600), SleepStage.AWAKE),
        )
        // 3 asleep readings (mean 55) + a high awake reading + a reading after the window.
        val samples = listOf(hr(50, 0.0), hr(55, 100.0), hr(60, 200.0), hr(120, 400.0), hr(120, 1000.0))
        assertNear(RestingHR.sleepMean(samples, sleep, minSleepSamples = 3), 55.0)
    }

    @Test
    fun sleepMeanNilWhenTooFewAsleepReadings() { // :41-44
        val sleep = listOf(SleepSegment(t0, t0.plusSeconds(600), SleepStage.ASLEEP_REM))
        assertNull(RestingHR.sleepMean(listOf(hr(60, 0.0), hr(58, 100.0)), sleep, minSleepSamples = 3))
    }

    @Test
    fun sleepMeanNilWhenNoAsleepSegments() { // :46-49
        val sleep = listOf(SleepSegment(t0, t0.plusSeconds(600), SleepStage.IN_BED))
        assertNull(RestingHR.sleepMean(listOf(hr(60, 0.0), hr(58, 100.0), hr(59, 200.0)), sleep, minSleepSamples = 3))
    }

    // Lowest sustained (fallback tier)

    @Test
    fun lowestSustainedFindsTheLowMinuteBlock() { // :53-60
        // 5 high readings then 5 low readings, one per minute; the 5-min window over the low
        // block has the minimum mean (50).
        val samples = mutableListOf<HRSample>()
        for (i in 0 until 5) samples += hr(70, i * 60.0)
        for (i in 5 until 10) samples += hr(50, i * 60.0)
        assertNear(RestingHR.lowestSustained(samples, window = 300.0), 50.0)
    }

    @Test
    fun lowestSustainedSingleReadingFallsBackToThatReading() { // :62-64
        assertNear(RestingHR.lowestSustained(listOf(hr(55, 0.0)), window = 300.0), 55.0)
    }

    @Test
    fun lowestSustainedAllIsolatedFallsBackToLowestSingle() { // :66-70
        // Readings >5 min apart never co-occur in a window → fall back to the single lowest.
        val samples = listOf(hr(60, 0.0), hr(50, 1000.0), hr(70, 2000.0))
        assertNear(RestingHR.lowestSustained(samples, window = 300.0), 50.0)
    }

    @Test
    fun lowestSustainedNilWhenEmpty() { // :72-74
        assertNull(RestingHR.lowestSustained(emptyList(), window = 300.0))
    }

    // Tier selection

    @Test
    fun valuePrefersSleepMeanOverDaytimeLow() { // :78-83
        val sleep = listOf(SleepSegment(t0, t0.plusSeconds(300), SleepStage.ASLEEP_CORE))
        val asleep = listOf(hr(55, 0.0), hr(55, 100.0), hr(55, 200.0)) // sleep mean 55
        val daytime = listOf(hr(45, 4000.0), hr(45, 4060.0), hr(45, 4120.0)) // sustained low 45 (ignored)
        assertNear(RestingHR.value(asleep + daytime, sleep), 55.0)
    }

    @Test
    fun valueFallsBackWhenNoSleep() { // :85-88
        val daytime = listOf(hr(45, 0.0), hr(45, 60.0), hr(45, 120.0))
        assertNear(RestingHR.value(daytime), 45.0)
    }

    // Daily grouping

    @Test
    fun dailyValuesGroupsByCalendarDay() { // :92-109
        val utc = ZoneOffset.UTC
        val day1 = CalendarDay.startOfDay(Instant.ofEpochSecond(1_700_000_000), utc)!!
        val day2 = day1.atZone(utc).plusDays(1).toInstant()
        val samples = listOf(
            HRSample(60, day1.plusSeconds(3600)),
            HRSample(50, day1.plusSeconds(3660)),
            HRSample(70, day2.plusSeconds(3600)),
            HRSample(72, day2.plusSeconds(3660)),
        )
        val daily = RestingHR.dailyValues(samples, zone = utc)
        assertEquals(2, daily.size)
        assertEquals(day1, daily[0].day)
        assertEquals(day2, daily[1].day)
        assertEquals(55.0, daily[0].bpm, 0.001) // (60+50)/2 sustained window
        assertEquals(71.0, daily[1].bpm, 0.001) // (70+72)/2
    }

    @Test
    fun dailyValuesEmptyForNoReadings() { // :111-113
        assertEquals(emptyList(), RestingHR.dailyValues(emptyList(), zone = ZoneOffset.UTC))
    }

    // lowestSustained O(m)→byte-identical regression (watchdog-hang fix)

    /** Inline copy of the ORIGINAL O(m²) algorithm — the reference the fast two-pointer version must reproduce bit-for-bit. */
    private fun referenceLowestSustained(hr: List<HRSample>, window: Double): Double? {
        if (hr.isEmpty()) return null
        val sorted = hr.sortedBy { it.start }
        var best: Double? = null
        for (i in sorted.indices) {
            val cutoff = sorted[i].start.plusMillis(Math.round(window * 1000))
            val bucket = mutableListOf<HRSample>()
            var j = i
            while (j < sorted.size && sorted[j].start < cutoff) {
                bucket += sorted[j]
                j++
            }
            if (!(bucket.size >= 2 || sorted.size == 1)) continue
            val m = bucket.fold(0.0) { acc, s -> acc + s.bpm.toDouble() } / bucket.size.toDouble()
            best = best?.let { swiftMin(it, m) } ?: m
        }
        if (best == null) best = sorted.minOf { it.bpm.toDouble() }
        return best
    }

    private fun assertIdentical(hr: List<HRSample>) {
        val ref = referenceLowestSustained(hr, window = RestingHR.SUSTAINED_WINDOW)
        val got = RestingHR.lowestSustained(hr, window = RestingHR.SUSTAINED_WINDOW)
        // Exact equality (bit-for-bit) — window sums are exact integers in Double, so no epsilon.
        assertEquals(ref, got, "fast lowestSustained diverged from the O(m²) reference")
    }

    @Test
    fun lowestSustainedByteIdenticalAcrossShapes() { // :143-168
        // Edge cases.
        assertIdentical(emptyList())
        assertIdentical(listOf(HRSample(61, t0))) // single reading
        assertIdentical(listOf(HRSample(61, t0), HRSample(70, t0.plusSeconds(3600)))) // isolated (>window apart)
        assertIdentical(listOf(HRSample(60, t0), HRSample(50, t0.plusSeconds(60)))) // exactly 2 in-window

        // Deterministic pseudo-random sets (seeded LCG) incl. a DENSE same-day cluster (the workout
        // worst case: hundreds of readings inside a few 5-min windows).
        var seed = 0x9E3779B97F4A7C15uL
        fun next(): ULong {
            seed = seed * 6364136223846793005uL + 1442695040888963407uL
            return seed
        }
        for (shape in 0 until 8) {
            val samples = mutableListOf<HRSample>()
            val count = listOf(1, 2, 5, 50, 200, 600, 1000, 3)[shape]
            // Dense shapes pack readings ~2 s apart (many per 5-min window); sparse ones ~2–8 min.
            val dense = shape >= 3 && shape != 7
            for (k in 0 until count) {
                val step = if (dense) (next() % 4uL).toDouble() else (120uL + next() % 360uL).toDouble()
                val offset = k.toDouble() * (if (dense) 2 else 1) + step
                samples += HRSample(40 + (next() % 130uL).toInt(), t0.plusMillis(Math.round(offset * 1000))) // 40…169 bpm
            }
            assertIdentical(samples)
        }
    }
}
