package io.github.opencircuit.health

import io.github.opencircuit.health.TestRecords.NOW
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Every bound is typed here from Health Connect's platform record rules (AOSP's
 * `android.health.connect.datatypes.*Record` validation on Android 14 to 16), never read back
 * from [HealthRanges], so a wrong constant there fails a test here. Each bound is probed just
 * inside, on, and just outside the edge.
 */
class HealthRangesTest {

    private fun ok(record: HealthRecord) = assertNull(HealthRanges.violation(record, NOW), "should pass: $record")
    private fun bad(record: HealthRecord) = assertNotNull(HealthRanges.violation(record, NOW), "should be refused: $record")

    @Test
    fun heartRateSamplesAreOneTo300AndASeriesNeedsOne() {
        val start = NOW.minusSeconds(3600)
        fun hr(vararg bpm: Long) = TestRecords.heartRate(start = start, samples = bpm.map { HeartRateSample(start.plusSeconds(1), it) })
        ok(hr(1)); ok(hr(300)); ok(hr(60, 61))
        bad(hr(0)); bad(hr(301)); bad(hr(60, 301))
        bad(hr()) // the platform refuses an empty series; Jetpack's JVM rules would let it through
    }

    @Test
    fun heartRateSamplesLieInsideTheSeriesAndTheSeriesMayBeAnInstant() {
        val start = NOW.minusSeconds(3600)
        val end = NOW.minusSeconds(60)
        ok(TestRecords.heartRate(start = start, end = end, samples = listOf(HeartRateSample(start, 60), HeartRateSample(end, 61))))
        bad(TestRecords.heartRate(start = start, end = end, samples = listOf(HeartRateSample(start.minusMillis(1), 60))))
        bad(TestRecords.heartRate(start = start, end = end, samples = listOf(HeartRateSample(end.plusMillis(1), 60))))
        ok(TestRecords.heartRate(start = start, end = start, samples = listOf(HeartRateSample(start, 60))))
        bad(TestRecords.heartRate(start = start, end = start.minusMillis(1), samples = listOf(HeartRateSample(start, 60))))
    }

    @Test
    fun restingHeartRateIsOneTo300() {
        ok(TestRecords.restingHeartRate(1)); ok(TestRecords.restingHeartRate(300))
        bad(TestRecords.restingHeartRate(0)); bad(TestRecords.restingHeartRate(301))
    }

    @Test
    fun rmssdIsOneTo200Milliseconds() {
        ok(TestRecords.hrv(1.0)); ok(TestRecords.hrv(200.0))
        bad(TestRecords.hrv(0.999)); bad(TestRecords.hrv(200.001)); bad(TestRecords.hrv(250.0))
        bad(TestRecords.hrv(Double.NaN))
    }

    @Test
    fun oxygenSaturationIsZeroTo100Percent() {
        ok(TestRecords.spo2(0.0)); ok(TestRecords.spo2(100.0))
        bad(TestRecords.spo2(-0.001)); bad(TestRecords.spo2(100.001)); bad(TestRecords.spo2(Double.NaN))
    }

    @Test
    fun respiratoryRateIsZeroTo1000() {
        ok(TestRecords.respiratoryRate(0.0)); ok(TestRecords.respiratoryRate(1000.0))
        bad(TestRecords.respiratoryRate(-0.001)); bad(TestRecords.respiratoryRate(1000.001))
    }

    @Test
    fun skinTemperatureBaselineDeltasAndDeltaTimes() {
        val start = NOW.minusSeconds(8 * 3600)
        val end = NOW.minusSeconds(3600)
        fun skin(baseline: Double?, vararg deltas: Pair<Instant, Double>) =
            TestRecords.skin(baseline = baseline, start = start, end = end, deltas = deltas.map { SkinTemperatureDelta(it.first, it.second) })
        ok(skin(0.0, start to 0.0)); ok(skin(100.0, start to 0.0)); ok(skin(null, start to 0.0))
        bad(skin(-0.001, start to 0.0)); bad(skin(100.001, start to 0.0))
        ok(skin(33.0, start to -30.0)); ok(skin(33.0, start to 30.0))
        bad(skin(33.0, start to -30.001)); bad(skin(33.0, start to 30.001))
        // Delta times lie in [start, end): one at the end itself is refused.
        ok(skin(33.0, end.minusMillis(1) to 0.1))
        bad(skin(33.0, end to 0.1))
        bad(skin(33.0, start.minusMillis(1) to 0.1))
    }

    @Test
    fun stepsAreOneToAMillionOverAForwardSpan() {
        ok(TestRecords.steps(1)); ok(TestRecords.steps(1_000_000))
        bad(TestRecords.steps(0)) // the platform takes 0, Jetpack refuses it: the stricter rule holds
        bad(TestRecords.steps(1_000_001))
        bad(TestRecords.steps(start = NOW.minusSeconds(60), end = NOW.minusSeconds(60)))
        bad(TestRecords.steps(start = NOW.minusSeconds(60), end = NOW.minusSeconds(61)))
    }

    @Test
    fun distanceActiveCaloriesAndBmrUpperAndLowerBounds() {
        ok(TestRecords.distance(0.0)); ok(TestRecords.distance(1_000_000.0))
        bad(TestRecords.distance(-0.001)); bad(TestRecords.distance(1_000_000.001))
        ok(TestRecords.activeCalories(0.0)); ok(TestRecords.activeCalories(1_000_000.0))
        bad(TestRecords.activeCalories(-0.001)); bad(TestRecords.activeCalories(1_000_000.001))
        ok(TestRecords.bmr(0.0)); ok(TestRecords.bmr(10_000.0))
        bad(TestRecords.bmr(-0.001)); bad(TestRecords.bmr(10_000.001))
    }

    @Test
    fun nothingMayStartInTheFuture() {
        ok(TestRecords.hrv(time = NOW))
        bad(TestRecords.hrv(time = NOW.plusMillis(1)))
        bad(TestRecords.steps(start = NOW.plusMillis(1), end = NOW.plusSeconds(60)))
        bad(TestRecords.flow(time = NOW.plusMillis(1)))
        bad(TestRecords.sleep(start = NOW.plusMillis(1), end = NOW.plusSeconds(3600), stages = emptyList()))
    }

    @Test
    fun aPeriodMayEndInTheFutureButLastsAtMost31Days() {
        ok(TestRecords.period(start = NOW.minusSeconds(86_400), end = NOW.plusSeconds(86_400)))
        val start = NOW.minusSeconds(40 * 86_400L)
        ok(TestRecords.period(start = start, end = start.plusSeconds(31 * 86_400L)))
        bad(TestRecords.period(start = start, end = start.plusSeconds(31 * 86_400L + 1)))
        bad(TestRecords.period(start = start, end = start))
    }

    @Test
    fun sleepStagesLieInsideTheSessionAndDoNotOverlap() {
        val s = NOW.minusSeconds(10 * 3600)
        val e = NOW.minusSeconds(2 * 3600)
        fun stage(from: Instant, to: Instant, t: SleepStageType = SleepStageType.LIGHT) = SleepStage(from, to, t)
        ok(TestRecords.sleep(start = s, end = e, stages = emptyList()))
        ok(TestRecords.sleep(start = s, end = e, stages = listOf(stage(s, s.plusSeconds(60)), stage(s.plusSeconds(60), e))))
        // Given out of order, they are still checked in time order (the platform sorts them).
        ok(TestRecords.sleep(start = s, end = e, stages = listOf(stage(s.plusSeconds(60), e), stage(s, s.plusSeconds(60)))))
        bad(TestRecords.sleep(start = s, end = e, stages = listOf(stage(s, s.plusSeconds(61)), stage(s.plusSeconds(60), e))))
        bad(TestRecords.sleep(start = s, end = e, stages = listOf(stage(s.minusMillis(1), s.plusSeconds(60)))))
        bad(TestRecords.sleep(start = s, end = e, stages = listOf(stage(s, e.plusMillis(1)))))
        bad(TestRecords.sleep(start = s, end = e, stages = listOf(stage(s.plusSeconds(60), s.plusSeconds(60)))))
        bad(TestRecords.sleep(start = s, end = s, stages = emptyList()))
    }

    @Test
    fun checkDropsAndCountsTheBadOnesAndNeverClamps() {
        val good1 = TestRecords.hrv(40.0, id = "oc:hrv:a")
        val tooHigh = TestRecords.hrv(250.0, id = "oc:hrv:b")
        val good2 = TestRecords.hrv(200.0, id = "oc:hrv:c")
        val future = TestRecords.hrv(50.0, time = NOW.plusSeconds(1), id = "oc:hrv:d")

        val checked = HealthRanges.check(listOf(good1, tooHigh, good2, future), NOW)

        assertEquals(listOf(good1, good2), checked.kept)
        assertEquals(2, checked.rejected)
        // Kept records are the very objects given: nothing was rewritten into range.
        assertSame(good2, checked.kept[1])
        assertEquals(200.0, checked.kept[1].millis)
    }

    @Test
    fun checkOfNothingKeepsNothingAndRejectsNothing() {
        val checked = HealthRanges.check(emptyList<HealthRecord>(), NOW)
        assertEquals(emptyList(), checked.kept)
        assertEquals(0, checked.rejected)
    }
}
