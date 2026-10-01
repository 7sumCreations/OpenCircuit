package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SleepDetailMetrics.MovementLevel
import io.github.opencircuit.ringkit.SleepScore.Composite.Factor
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the other night metrics — the composite sleep score, overnight
 * stress, per-stage HR and the movement chart, and overnight averages: NaN and ±Inf inputs, zero and
 * negative values, HR and motion bytes 0 and 255, reversed segments, unsorted and duplicated
 * records, empty input and rounding ties. Kept out of the upstream-port classes so their counts
 * stay exact.
 *
 * Every expected value was measured on upstream's pinned Swift build with the same inputs, except
 * the inputs upstream traps on (a NaN reaching an `Int` conversion), which the port rejects with an
 * `IllegalArgumentException` instead.
 */
class NightMetricsHazardTest {

    private val h = 3600.0

    private fun factors(c: SleepScore.Composite): List<String> = Factor.entries.map { f -> c.factors[f]?.toString() ?: "-" }

    @Test
    fun compositeOnInfiniteAndOutOfRangeInputs() {
        val a = SleepScore.composite(
            SleepScore.CompositeInput(
                totalAsleep = Double.POSITIVE_INFINITY, timeAwake = Double.POSITIVE_INFINITY, efficiency = Double.POSITIVE_INFINITY,
                deep = h, light = h, rem = h, restingHR = Double.NEGATIVE_INFINITY, tempOffsetC = Double.POSITIVE_INFINITY,
            ),
        )
        assertEquals(80, a.score)
        assertEquals(SleepScore.Tier.GOOD, a.tier)
        assertEquals(listOf("1.0", "1.0", "1.0", "1.0", "0.0", "0.0"), factors(a))

        val b = SleepScore.composite(
            SleepScore.CompositeInput(totalAsleep = h, timeAwake = -600.0, efficiency = -1.0, deep = 0.0, light = 0.0, rem = 0.0, sleepGoal = 0.0),
        )
        assertEquals(13, b.score)
        assertEquals(SleepScore.Tier.NEEDS_IMPROVEMENT, b.tier)
        assertEquals(listOf("0.0", "0.0", "0.0", "-", "-", "1.0"), factors(b), "a zero goal scores time asleep 0")

        val c = SleepScore.composite(
            SleepScore.CompositeInput(totalAsleep = h, timeAwake = 0.0, efficiency = 0.9, deep = 0.0, light = 0.0, rem = 0.0, sleepGoal = -h),
        )
        assertEquals(35, c.score)
        assertEquals(listOf("0.0", "0.0", "0.8888888888888891", "-", "-", "1.0"), factors(c))

        // A NaN goal is not > 0, so time asleep scores 0 (upstream returns, no trap).
        val g = SleepScore.composite(
            SleepScore.CompositeInput(totalAsleep = 8 * h, timeAwake = 600.0, efficiency = 0.9, deep = h, light = h, rem = h, sleepGoal = Double.NaN),
        )
        assertEquals(58, g.score)
        assertEquals(SleepScore.Tier.NEEDS_IMPROVEMENT, g.tier)
        assertEquals(0.0, g.factors[Factor.TIME_ASLEEP])
    }

    /** Upstream traps converting a NaN score to `Int` ("Double value cannot be converted to Int"); the port rejects it. */
    @Test
    fun compositeInputsThatProduceNoNumberAreRejected() {
        fun input(efficiency: Double = 0.9, deep: Double = h, restingHR: Double? = null) =
            SleepScore.CompositeInput(totalAsleep = 8 * h, timeAwake = 600.0, efficiency = efficiency, deep = deep, light = h, rem = h, restingHR = restingHR)
        assertFailsWith<IllegalArgumentException> { SleepScore.composite(input(efficiency = Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { SleepScore.composite(input(deep = Double.POSITIVE_INFINITY)) } // ∞/∞ restorative share
        assertFailsWith<IllegalArgumentException> { SleepScore.composite(input(restingHR = Double.NaN)) }
    }

    @Test
    fun stressScoreOnHostileRMSSD() {
        assertEquals(15, SleepStress.score(Double.POSITIVE_INFINITY))
        assertEquals(90, SleepStress.score(Double.NEGATIVE_INFINITY), "below 1 ms clamps to 1 → the stressed end")
        assertEquals(90, SleepStress.score(0.0))
        assertEquals(90, SleepStress.score(-5.0))
        assertEquals(15, SleepStress.score(1e308))
        assertEquals(15, SleepStress.score(Double.MAX_VALUE))
        // Upstream traps on NaN (`Int(NaN.rounded())`); the port rejects it.
        assertFailsWith<IllegalArgumentException> { SleepStress.score(Double.NaN) }
    }

    @Test
    fun overnightStressOnHostileInput() {
        assertNull(SleepStress.overnightScore(rmssd = emptyList()))
        assertEquals(15, SleepStress.overnightScore(rmssd = listOf(255)))
        assertEquals(15, SleepStress.overnightScore(rmssd = listOf(Int.MAX_VALUE, -1, 0)), "non-positive dropped; the largest Int is just very relaxed")
        assertEquals(90, SleepStress.overnightScore(rmssd = listOf(1)))
        val d = SleepStress.stateDurations(rmssd = listOf(70, 15, 0, -3), epochSeconds = -150)
        assertEquals(
            mapOf(SleepStress.Band.RELAXED to Duration.ofSeconds(-150), SleepStress.Band.HIGH to Duration.ofSeconds(-150)),
            d,
            "a negative epoch length is multiplied through, as upstream",
        )
        assertTrue(SleepStress.stateDurations(rmssd = emptyList(), epochSeconds = 150).isEmpty())

        val b = ByteArray(23).also { it[4] = 60; it[5] = 0xFF.toByte(); it[8] = 0x62; for (k in 10 until 15) it[k] = 1 }
        assertEquals(15, SleepStress.overnightScore(listOf(assertNotNull(BulkRecord.of(b)))), "HRV byte 255 is a (relaxed) sleep-vitals reading")
        assertNull(SleepStress.overnightScore(emptyList<BulkRecord>()))
    }

    private fun hrRec(c: Long, hr: Int, motion: List<Int> = listOf(1, 1, 1, 1, 1), tag: Int = 0x62): BulkRecord {
        val b = ByteArray(23)
        b[0] = (c shr 24).toByte(); b[1] = (c shr 16).toByte(); b[2] = (c shr 8).toByte(); b[3] = c.toByte()
        b[4] = hr.toByte(); b[8] = tag.toByte()
        for (k in 0 until 5) b[10 + k] = motion[k].toByte()
        return assertNotNull(BulkRecord.of(b))
    }

    private val t0: Instant = Instant.ofEpochSecond(1000 + 1_577_793_600L)

    @Test
    fun averageHRByStageOnHostileSegmentsAndBytes() {
        val segs = listOf(
            SleepSegment(t0, t0.plusSeconds(600), SleepStage.ASLEEP_DEEP),
            SleepSegment(t0.plusSeconds(900), t0.plusSeconds(600), SleepStage.ASLEEP_REM), // end < start
            SleepSegment(t0.plusSeconds(600), t0.plusSeconds(900), SleepStage.ASLEEP_CORE),
        )
        // HR byte 0 and 255 read as no HR; the epoch on the core segment's END falls to the closed pass.
        val recs = listOf(hrRec(1000, 50), hrRec(1150, 0), hrRec(1300, 255), hrRec(1600, 70), hrRec(1900, 64), hrRec(1750, 61))
        assertEquals(mapOf(SleepStage.ASLEEP_CORE to 65, SleepStage.ASLEEP_DEEP to 50), SleepDetailMetrics.averageHRByStage(recs, segs))
        assertTrue(SleepDetailMetrics.averageHRByStage(recs, emptyList()).isEmpty())
        assertTrue(SleepDetailMetrics.averageHRByStage(emptyList(), segs).isEmpty())
        // 50.5 rounds half away from zero, as Swift's rounded().
        assertEquals(51, SleepDetailMetrics.averageHRByStage(listOf(hrRec(1000, 50), hrRec(1150, 51)), listOf(segs[0]))[SleepStage.ASLEEP_DEEP])
    }

    @Test
    fun movementOnHostileRecords() {
        assertTrue(SleepDetailMetrics.movement(emptyList()).isEmpty())
        // Unsorted and duplicated records, motion bytes 0 and 255.
        val mv = listOf(
            hrRec(1300, 60, motion = listOf(0, 255, 0, 255, 0)),
            hrRec(1000, 60, motion = listOf(1, 1, 1, 4, 1)),
            hrRec(1000, 60, motion = listOf(1, 1, 1, 4, 1)),
            hrRec(1150, 60, motion = listOf(255, 255, 255, 255, 255)),
        )
        assertEquals(
            listOf("1577794600:LIGHT:3", "1577794600:LIGHT:3", "1577794750:STILL:0", "1577794900:ACTIVE:510"),
            SleepDetailMetrics.movement(mv).map { "${it.time.epochSecond}:${it.level}:${it.magnitude}" },
            "sorted by counter (stable), duplicates kept, a constant 255 run is still",
        )
        val expectedAtOrBelowZero = listOf(MovementLevel.ACTIVE, MovementLevel.ACTIVE, MovementLevel.STILL, MovementLevel.ACTIVE)
        assertEquals(expectedAtOrBelowZero, SleepDetailMetrics.movement(mv, activeThreshold = 0).map { it.level })
        assertEquals(expectedAtOrBelowZero, SleepDetailMetrics.movement(mv, activeThreshold = -5).map { it.level })
        assertTrue(SleepDetailMetrics.movement(mv, window = DateInterval(Instant.EPOCH, Instant.ofEpochSecond(10))).isEmpty())
        val s = SleepDetailMetrics.movementSummary(emptyList())
        assertEquals(0, s.total)
        assertEquals(0.0, s.movementFraction, "no epochs → 0, never NaN")
        assertTrue(s.levels.isEmpty())
    }

    @Test
    fun derivedActiveCutEdges() {
        assertEquals(Int.MAX_VALUE, SleepDetailMetrics.derivedActiveCut(emptyList()), "nothing moved → no epoch can be active")
        assertEquals(Int.MAX_VALUE, SleepDetailMetrics.derivedActiveCut(listOf(0, -3)))
        assertEquals(5, SleepDetailMetrics.derivedActiveCut(listOf(5)))
        assertEquals(2, SleepDetailMetrics.derivedActiveCut(listOf(1, 2)), "index round(0.8) = 1")
    }

    @Test
    fun overnightMeanOnHostileValues() {
        val t = Instant.EPOCH
        val w = DateInterval(t, t.plusSeconds(3600))
        fun p(v: Double) = OvernightAverages.Point(v, t)
        assertTrue(assertNotNull(OvernightAverages.mean(listOf(p(Double.NaN), p(50.0)), w)).isNaN())
        assertTrue(assertNotNull(OvernightAverages.mean(listOf(p(Double.POSITIVE_INFINITY), p(Double.NEGATIVE_INFINITY)), w)).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, OvernightAverages.mean(listOf(p(1e308), p(1e308)), w), "the running sum overflows, as upstream")
        assertEquals(5.0, OvernightAverages.mean(listOf(p(5.0)), DateInterval(t, t)), "a zero-length window is still closed")
    }
}
