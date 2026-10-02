package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the exercise-minute estimate: what the upstream vectors never
 * feed in — a max HR of zero, below zero or at `Int.MAX_VALUE`, a resting HR that is NaN, `-0.0` or
 * just outside the plausible band, epoch and point widths of zero, below zero, NaN, infinite or
 * huge, duplicated, unsorted and reversed samples, a sample on the sleep window's end, and samples
 * at the far end of `Instant`'s range. Kept out of the upstream-port class so its count stays exact.
 *
 * Every expected value that matches upstream was measured on the pinned Swift build (Swift 6.3.2);
 * where Kotlin deliberately differs the test says so, and `PORTING.md` records why.
 */
class ExerciseMinutesHazardTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)
    private fun hr(bpm: Int, offset: Long, end: Long = offset) = HRSample(bpm, t0.plusSeconds(offset), t0.plusSeconds(end))
    private fun spans(pieces: List<ExerciseMinutes.ElevatedPiece>) =
        pieces.map { secondsBetween(t0, it.start) to secondsBetween(t0, it.end) }

    @Test
    fun thresholdAtTheExtremesOfMaxAndRestingHr() {
        for ((maxHR, rhr, expected) in listOf(
            Triple(Int.MAX_VALUE, null, 1_073_741_823),
            Triple(0, null, 60),
            Triple(-5, null, 60),
            Triple(185, Double.NaN, 92),
            Triple(185, -0.0, 92),
            Triple(185, 35.0, 95),
            Triple(185, 90.0, 128),
            Triple(185, 90.000001, 92),
            Triple(185, 34.999, 92),
            Triple(60, 59.5, 60),
            Triple(90, 90.0, 60),
            Triple(Int.MAX_VALUE, 89.0, 858_993_512),
        )) {
            assertEquals(expected, ExerciseMinutes.threshold(maxHR, rhr), "threshold($maxHR, $rhr)")
        }
    }

    @Test
    fun epochAndPointWidthsOutsideTheNormalRange() {
        val run = listOf(hr(120, 0), hr(120, 150), hr(120, 1000))
        assertEquals(listOf(0.0 to 150.0, 150.0 to 300.0), spans(ExerciseMinutes.elevatedPieces(run, maxHR = 185, epochSeconds = 150.0)))
        // Zero, negative and NaN widths give a point no time (measured).
        for (w in listOf(0.0, -150.0, Double.NaN)) {
            assertEquals(emptyList(), ExerciseMinutes.elevatedPieces(run, maxHR = 185, epochSeconds = w), "epoch $w")
            assertEquals(0.0, ExerciseMinutes.estimate(run, maxHR = 185, epochSeconds = w))
        }
        val lone = listOf(hr(120, 0))
        assertEquals(0.5, ExerciseMinutes.estimate(lone, maxHR = 185, pointSampleWidth = 30.0))
        for (w in listOf(-30.0, Double.NaN)) assertEquals(0.0, ExerciseMinutes.estimate(lone, maxHR = 185, pointSampleWidth = w))
        // An infinite or astronomically wide point has no end inside `Instant`'s range: it ends at
        // the last representable instant (upstream reports infinite or 1e298 minutes; PORTING.md).
        for (w in listOf(Double.POSITIVE_INFINITY, 1e300)) {
            val pieces = ExerciseMinutes.elevatedPieces(run, maxHR = 185, epochSeconds = w)
            assertEquals(listOf(t0 to Instant.MAX), pieces.map { it.start to it.end }, "epoch $w")
            val minutes = ExerciseMinutes.estimate(lone, maxHR = 185, pointSampleWidth = w)
            assertEquals(secondsBetween(t0, Instant.MAX) / 60.0, minutes, "point width $w")
        }
    }

    @Test
    fun duplicatedUnsortedAndReversedSamples() {
        // A duplicated isolated spot read neighbours its own copy and earns a full epoch (measured;
        // kept as upstream — de-duplication belongs to the record merge).
        assertEquals(2.5, ExerciseMinutes.estimate(listOf(hr(120, 0), hr(120, 0)), maxHR = 185))
        // A sample that ends before it starts is a point reading.
        assertEquals(0.0, ExerciseMinutes.estimate(listOf(hr(120, 600, 0)), maxHR = 185))
        assertEquals(
            listOf(600.0 to 750.0, 750.0 to 850.0),
            spans(ExerciseMinutes.elevatedPieces(listOf(hr(120, 600, 0), hr(121, 700)), maxHR = 185)),
        )
        val sorted = listOf(hr(130, 0, 600), hr(125, 300), hr(118, 450), hr(140, 2000, 2300))
        assertEquals(
            ExerciseMinutes.elevatedPieces(sorted, maxHR = 185),
            ExerciseMinutes.elevatedPieces(sorted.reversed(), maxHR = 185),
        )
        // A max HR of zero clamps to the 60 bpm floor.
        assertEquals(5.0, ExerciseMinutes.estimate(listOf(hr(60, 0), hr(60, 150)), maxHR = 0))
        // The sleep window is closed: a reading on its end is excluded, leaving one isolated point.
        val window = DateInterval(t0, t0.plusSeconds(600))
        assertEquals(0.0, ExerciseMinutes.estimate(listOf(hr(120, 600), hr(120, 750)), maxHR = 185, sleepWindow = window))
        assertEquals(0.0, ExerciseMinutes.estimate(emptyList(), maxHR = 185))
    }

    @Test
    fun samplesAtTheEndOfTimeNeitherThrowNorExtendPastIt() {
        val last = Instant.MAX
        val points = listOf(HRSample(120, last.minusSeconds(100)), HRSample(120, last))
        val pieces = ExerciseMinutes.elevatedPieces(points, maxHR = 185)
        assertEquals(listOf(last.minusSeconds(100) to last), pieces.map { it.start to it.end })
        assertEquals(100.0 / 60.0, ExerciseMinutes.estimate(points, maxHR = 185))
        val far = (0 until 12).map { HRSample(55, last.minusSeconds(12_000L - it * 1000L)) }
        assertNull(ExerciseMinutes.restingBaseline(far), "spot reads only: no sustained window")
    }

    @Test
    fun restingBaselineOnDuplicatedSpotReadsAndTheEffectiveSwitch() {
        // Every spot read duplicated: each copy pair is a "sustained" window, so the dormant
        // personalised baseline accepts the day (measured; kept as upstream, the model ships off).
        val doubled = (0 until 30).flatMap { listOf(hr(68, it * 600L), hr(68, it * 600L)) }
        assertEquals(68.0, ExerciseMinutes.restingBaseline(doubled))
        assertNull(ExerciseMinutes.effectiveRestingBaseline(doubled), "the shipped switch is off")
        assertEquals(68.0, ExerciseMinutes.effectiveRestingBaseline(doubled, derive = true))
        assertTrue(!ExerciseMinutes.PERSONALISED_THRESHOLD_ENABLED)
    }
}
