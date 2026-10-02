package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the daily resting heart rate: what the upstream vectors never
 * feed in — a sustained window of zero, below zero, NaN or infinite length, duplicated and unsorted
 * readings, readings outside the valid range only, a sleep floor of zero or below, a reversed sleep
 * segment, readings at the far end of `Instant`'s range, and day grouping at both 2026 clock changes
 * and on a day whose midnight does not exist. Kept out of the upstream-port class so its count stays
 * exact.
 *
 * Every expected value that matches upstream was measured on the pinned Swift build (Swift 6.3.2);
 * where Kotlin deliberately differs the test says so, and `PORTING.md` records why.
 */
class RestingHRHazardTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun hr(bpm: Int, offset: Long) = HRSample(bpm, t0.plusSeconds(offset))
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    @Test
    fun lowestSustainedWithADegenerateWindowBehavesAsUpstream() {
        val one = listOf(hr(60, 0))
        val three = listOf(hr(60, 0), hr(50, 100), hr(70, 200))
        // A window of zero, below zero or NaN never holds a reading: a single reading divides an
        // empty sum by zero (NaN, measured), several fall back to the lowest one.
        for (w in listOf(0.0, -1.0, Double.NaN)) {
            assertTrue(RestingHR.lowestSustained(one, w)!!.isNaN(), "one reading, window $w")
            assertEquals(50.0, RestingHR.lowestSustained(three, w), "three readings, window $w")
        }
        // An endless window holds every later reading: the lowest suffix mean.
        for (w in listOf(Double.POSITIVE_INFINITY, 1e300)) {
            assertEquals(60.0, RestingHR.lowestSustained(one, w))
            assertEquals(60.0, RestingHR.lowestSustained(three, w))
        }
    }

    @Test
    fun lowestSustainedOnDuplicatedUnsortedAndTiedReadings() {
        assertEquals(60.0, RestingHR.lowestSustained(listOf(hr(60, 0), hr(60, 0)), 300.0))
        assertEquals(51.0, RestingHR.lowestSustained(listOf(hr(70, 1000), hr(50, 0), hr(52, 60), hr(90, 1060)), 300.0))
        // Readings at the same instant keep their input order through the stable sort.
        assertEquals(60.0, RestingHR.lowestSustained(listOf(hr(80, 0), hr(40, 0), hr(90, 400)), 300.0))
        assertEquals(60.0, RestingHR.lowestSustained(listOf(hr(40, 0), hr(80, 0), hr(90, 400)), 300.0))
        assertNull(RestingHR.lowestSustained(emptyList(), 300.0))
        // A duplicated spot read forms a "sustained" window of its own (measured; kept as upstream).
        val detailed = RestingHR.lowestSustainedDetailed(listOf(hr(44, 0), hr(44, 0), hr(68, 600)), 300.0)!!
        assertEquals(44.0, detailed.value)
        assertTrue(detailed.wasSustained)
    }

    @Test
    fun valueOnInvalidReadingsDegenerateSleepFloorAndReversedSegments() {
        assertNull(RestingHR.value(listOf(hr(4, 0), hr(250, 60), hr(-3, 120))), "only out-of-range readings")
        val asleep = listOf(SleepSegment(t0, t0.plusSeconds(600), SleepStage.ASLEEP_CORE))
        // A floor of zero or below accepts an empty in-sleep set, whose mean upstream defines as 0.
        assertEquals(0.0, RestingHR.value(listOf(hr(60, 5000)), asleep, minSleepSamples = 0))
        assertEquals(0.0, RestingHR.value(listOf(hr(60, 5000)), asleep, minSleepSamples = -2))
        // A segment that ends before it starts contains nothing, so the daytime fallback answers.
        val reversed = listOf(SleepSegment(t0.plusSeconds(600), t0, SleepStage.ASLEEP_CORE))
        assertEquals(51.0, RestingHR.value(listOf(hr(50, 100), hr(51, 200), hr(52, 300), hr(70, 4000), hr(71, 4060)), reversed))
    }

    @Test
    fun readingsAtTheEndOfTimeNeitherThrowNorBreakTheDailyGrouping() {
        val last = Instant.MAX
        val samples = listOf(HRSample(60, last.minusSeconds(60)), HRSample(50, last))
        assertEquals(55.0, RestingHR.value(samples))
        assertEquals(55.0, RestingHR.lowestSustained(samples, RestingHR.SUSTAINED_WINDOW))
        // No zone can place these instants in a calendar day: they are left out of the grouping.
        assertEquals(emptyList(), RestingHR.dailyValues(samples, zone = newYork))
        val keep = listOf(HRSample(62, Instant.ofEpochSecond(1_780_000_000)), HRSample(64, Instant.ofEpochSecond(1_780_000_060)))
        assertEquals(listOf(63.0), RestingHR.dailyValues(keep + samples, zone = newYork).map { it.bpm })

        // A reading 31.7 million years out still gets its real start of day (upstream's Foundation
        // calendar gives a day 500 000 years earlier there; see PORTING.md).
        val far = Instant.ofEpochSecond(1_000_000_000_000_000)
        val day = RestingHR.dailyValues(listOf(HRSample(60, far), HRSample(50, far.plusSeconds(60))), zone = newYork).single()
        assertEquals(55.0, day.bpm)
        assertEquals(LocalTime.MIDNIGHT, day.day.atZone(newYork).toLocalTime())
        assertEquals(far.atZone(newYork).toLocalDate(), day.day.atZone(newYork).toLocalDate())
    }

    @Test
    fun dailyValuesAtBothTwentyTwentySixClockChangesMatchUpstream() {
        // Readings either side of each midnight around the New York changes, and an asleep segment
        // that ends half a second before the next midnight (measured on the pinned build).
        for ((start, nextStart, expected) in listOf(
            // 8 March 2026: a 23-hour day.
            Triple(1_772_946_000L, 1_773_028_800L, listOf(1_772_859_600L to 62.0, 1_772_946_000L to 67.0, 1_773_028_800L to 71.0)),
            // 1 November 2026: a 25-hour day.
            Triple(1_793_505_600L, 1_793_595_600L, listOf(1_793_419_200L to 62.0, 1_793_505_600L to 67.0, 1_793_595_600L to 71.0)),
        )) {
            val s = Instant.ofEpochSecond(start)
            val next = Instant.ofEpochSecond(nextStart)
            val samples = listOf(
                HRSample(61, s.minusSeconds(60)), HRSample(63, s.minusSeconds(30)),
                HRSample(55, s), HRSample(57, s.plusSeconds(60)),
                HRSample(66, next.minusSeconds(60)), HRSample(68, next.minusSeconds(1)),
                HRSample(70, next), HRSample(72, next.plusSeconds(30)),
            )
            val sleep = listOf(SleepSegment(next.minusSeconds(90), next.minusMillis(500), SleepStage.ASLEEP_DEEP))
            val daily = RestingHR.dailyValues(samples.reversed(), sleep, zone = newYork, minSleepSamples = 2)
            assertEquals(expected.map { Instant.ofEpochSecond(it.first) to it.second }, daily.map { it.day to it.bpm })
        }
        // Santiago skips midnight on 6 September 2026: that day starts at 01:00 local time.
        val santiago = ZoneId.of("America/Santiago")
        val noon = Instant.ofEpochSecond(1_788_667_200 + 11 * 3600)
        val daily = RestingHR.dailyValues(listOf(HRSample(58, noon), HRSample(60, noon.plusSeconds(60))), zone = santiago)
        assertEquals(listOf(Instant.ofEpochSecond(1_788_667_200)), daily.map { it.day })
        assertEquals(emptyList(), RestingHR.dailyValues(emptyList(), zone = santiago))
    }
}
