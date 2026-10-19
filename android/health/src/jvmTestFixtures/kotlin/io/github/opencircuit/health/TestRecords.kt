package io.github.opencircuit.health

import java.time.Instant
import java.time.ZoneOffset

/**
 * Small builders for valid records, so a test states only what it is about. Every builder makes a
 * record that passes [HealthRanges] at [TestRecords.NOW] unless the test changes a field.
 */
object TestRecords {
    val NOW: Instant = Instant.parse("2026-10-16T12:00:00Z")
    val OFFSET: ZoneOffset = ZoneOffset.ofHours(2)
    val RING = RingDevice("Gen 2")

    fun meta(id: String, version: Long = 1, method: RecordingMethod = RecordingMethod.AUTOMATICALLY_RECORDED) =
        RecordMeta(id, version, method, if (method == RecordingMethod.MANUAL_ENTRY) null else RING)

    fun heartRate(
        id: String = "oc:hr:1",
        start: Instant = NOW.minusSeconds(3600),
        end: Instant = NOW,
        samples: List<HeartRateSample> = listOf(HeartRateSample(start, 60)),
        version: Long = 1,
    ) = HeartRateSeries(start, OFFSET, end, OFFSET, samples, meta(id, version))

    fun restingHeartRate(bpm: Long = 55, time: Instant = NOW.minusSeconds(60), id: String = "oc:rhr:1", version: Long = 1) =
        RestingHeartRate(time, OFFSET, bpm, meta(id, version))

    fun hrv(millis: Double = 40.0, time: Instant = NOW.minusSeconds(60), id: String = "oc:hrv:1", version: Long = 1) =
        HeartRateVariabilityRmssd(time, OFFSET, millis, meta(id, version))

    fun spo2(percent: Double = 97.0, time: Instant = NOW.minusSeconds(60), id: String = "oc:spo2:1", version: Long = 1) =
        OxygenSaturation(time, OFFSET, percent, meta(id, version))

    fun respiratoryRate(rate: Double = 14.0, time: Instant = NOW.minusSeconds(60), id: String = "oc:rr:1", version: Long = 1) =
        RespiratoryRate(time, OFFSET, rate, meta(id, version))

    fun skin(
        baseline: Double? = 33.0,
        deltas: List<SkinTemperatureDelta>? = null,
        start: Instant = NOW.minusSeconds(8 * 3600),
        end: Instant = NOW.minusSeconds(3600),
        id: String = "oc:skin:2026-10-15",
        version: Long = 1,
    ) = SkinTemperature(
        start, OFFSET, end, OFFSET, baseline,
        deltas ?: listOf(SkinTemperatureDelta(start, 0.2), SkinTemperatureDelta(start.plusSeconds(600), -0.1)),
        SkinTemperatureLocation.FINGER, meta(id, version),
    )

    fun steps(
        count: Long = 120,
        start: Instant = NOW.minusSeconds(900),
        end: Instant = NOW,
        id: String = "oc:steps:1",
        version: Long = 1,
    ) = Steps(start, OFFSET, end, OFFSET, count, meta(id, version))

    fun distance(meters: Double = 90.0, start: Instant = NOW.minusSeconds(900), end: Instant = NOW, id: String = "oc:dist:1") =
        Distance(start, OFFSET, end, OFFSET, meters, meta(id))

    fun activeCalories(kcal: Double = 12.0, start: Instant = NOW.minusSeconds(3600), end: Instant = NOW, id: String = "oc:ae:1", version: Long = 1) =
        ActiveCaloriesBurned(start, OFFSET, end, OFFSET, kcal, meta(id, version))

    fun bmr(kcalPerDay: Double = 1600.0, time: Instant = NOW.minusSeconds(3600), id: String = "oc:bmr:1") =
        BasalMetabolicRate(time, OFFSET, kcalPerDay, meta(id))

    fun sleep(
        start: Instant = NOW.minusSeconds(10 * 3600),
        end: Instant = NOW.minusSeconds(2 * 3600),
        stages: List<SleepStage>? = null,
        id: String = "oc:sleep:2026-10-15",
        version: Long = 1,
    ) = SleepSession(
        start, OFFSET, end, OFFSET, null, null,
        stages ?: listOf(
            SleepStage(start, start.plusSeconds(3600), SleepStageType.LIGHT),
            SleepStage(start.plusSeconds(3600), end, SleepStageType.DEEP),
        ),
        meta(id, version),
    )

    fun flow(time: Instant = NOW.minusSeconds(3600), id: String = "oc:flow:2026-10-16") =
        MenstruationFlow(time, OFFSET, MenstruationFlowLevel.MEDIUM, meta(id, method = RecordingMethod.MANUAL_ENTRY))

    fun period(start: Instant = NOW.minusSeconds(3 * 86_400), end: Instant = NOW, id: String = "oc:period:2026-10-13") =
        MenstruationPeriod(start, OFFSET, end, OFFSET, meta(id, method = RecordingMethod.MANUAL_ENTRY))
}
