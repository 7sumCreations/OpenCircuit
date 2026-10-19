package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three ways a call can come back refused, each landing in a different bucket of the writer's
 * report, and each one a `catch` clause nothing drove end-to-end until this recheck:
 *
 *  - a grant withdrawn between reading the grants and making the call — [HealthNotGrantedException]
 *    from the platform, though the writer thought the type was allowed: not allowed, never a failure
 *    (a failure map entry would put the type on the card as broken when the wearer simply did not
 *    allow it, and it would stay there until a write that never happens);
 *  - a delete Health Connect asks to be retried later — catching up, never a failure, and the id the
 *    delete would have removed stays named so Health Connect is never left emptier;
 *  - the skin-temperature feature check asked to wait — the check is a call like any other, so the
 *    flush catches up rather than failing the type or calling it unsupported.
 *
 * Asserted on the report's buckets, on the store's marks, and on what Health Connect holds — never
 * on which clause caught.
 */
class RefusedCallTest {

    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val generations = MemoryGenerations()
    private val zone: ZoneId = ZoneOffset.UTC

    private fun writer(port: HealthConnectPort = hc) = HealthWriter(
        port, samples, WriteGeneration(generations), InMemoryHealthFailures(), clock::now, { zone }, { RingDevice("Gen 2") },
    )

    private fun hr(minutes: Long, bpm: Double) = QuantitySample(MetricKind.HEART_RATE, Instant.parse("2026-10-16T08:00:00Z").plusSeconds(minutes * 60), value = bpm)

    private class MemoryGenerations(var value: Long? = null) : WriteGenerationStore {
        override suspend fun load(): Long? = value
        override suspend fun save(generation: Long) { value = generation }
    }

    // ---- a grant taken away while the call was in flight ----

    @Test
    fun aGrantWithdrawnWhileTheCallWasInFlightIsNotAllowedNotFailed() = runTest {
        samples.add(hr(10, 60.0))
        // The writer read the grants and found the type allowed; the platform says otherwise by the
        // time the call lands (the wearer revoked it in Health Connect meanwhile).
        hc.failNext(RecordType.HEART_RATE, HealthNotGrantedException(HealthPermission.HEART_RATE, "revoked while the call was in flight"))

        val result = writer().flush()

        assertTrue(RecordType.HEART_RATE in result.notGranted, "the type is not allowed, which is what the platform said")
        assertEquals(emptyMap(), result.failures, "a withdrawn grant is not a write failure: nothing enters the failure map")
        assertTrue(RecordType.SKIN_TEMPERATURE in result.notGranted || result.notGranted.isNotEmpty(), "the refusal is reported as a refusal")
        assertNull(samples.mark(MetricKind.HEART_RATE), "nothing was accepted, so nothing is marked: the sample stays pending")
        assertEquals(emptyList(), hc.records(RecordType.HEART_RATE), "Health Connect holds nothing of it")
    }

    // ---- a delete Health Connect asks to retry later ----

    private val sleep = InMemoryHealthSleep()
    private val night = Instant.parse("2026-06-19T00:00:00Z")
    private val key = "${night.epochSecond}.0"
    private val id = "oc:sleep:$key"

    private fun at(h: Double): Instant = night.plusMillis((h * 3_600_000).toLong())

    private val recorded = listOf(
        SleepSegment(at(-1.0), at(7.0), SleepStage.IN_BED),
        SleepSegment(at(-0.5), at(3.0), SleepStage.ASLEEP_CORE),
        SleepSegment(at(3.0), at(4.0), SleepStage.ASLEEP_DEEP),
        SleepSegment(at(4.0), at(6.5), SleepStage.ASLEEP_REM),
    )

    private fun edit(wake: Double = 8.0) = QueuedNightEdit(
        night = night,
        inBedStart = at(-2.0),
        sleepOnset = at(-2.0),
        sleepWake = at(wake),
        segments = listOf(
            SleepSegment(at(-2.0), at(-1.0), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(-2.0), at(-0.5), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        ) + recorded.drop(1) + listOf(
            SleepSegment(at(-1.0), at(wake), SleepStage.IN_BED),
            SleepSegment(at(6.5), at(wake), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_OVER_MEASURED),
        ),
    )

    private fun sleepWriter() = HealthWriter(
        hc, samples, WriteGeneration(generations), InMemoryHealthFailures(), { clock.now() }, { zone }, { RingDevice("Gen 2") }, sleep = sleep,
    )

    private fun sessions() = hc.records(RecordType.SLEEP_SESSION).map { it as SleepSession }.sortedBy { it.start }

    /** A night written unedited under an earlier key, then re-keyed and edited: its edit's write has an old id to delete. */
    private suspend fun rekeyedEditedNight() {
        val oldNight = night.minus(Duration.ofDays(1))
        val oldId = "oc:sleep:${oldNight.epochSecond}.0"
        sleep.putNight(StoredSleepNight("${oldNight.epochSecond}.0", oldNight, edited = false, timeline = recorded, recordedEnd = at(7.0)))
        sleepWriter().flush()
        sleep.rekey(oldNight, night, key)
        sleep.putNight(StoredSleepNight(key, night, edited = true, timeline = recorded, recordedEnd = at(7.0)))
        sleep.queue(edit())
    }

    @Test
    fun aDeleteHealthConnectAsksToRetryLeavesTheFlushCatchingUpAndTheOldIdNamed() = runBlocking<Unit> {
        val oldId = "oc:sleep:${night.minus(Duration.ofDays(1)).epochSecond}.0"
        rekeyedEditedNight()
        hc.failNext(FakeHealthConnect.Call.Kind.DELETE, RecordType.SLEEP_SESSION, HealthRetryLaterException(RetryReason.RATE_LIMITED, "the write quota is spent"))

        val result = sleepWriter().flush()

        assertTrue(result.catchingUp, "Health Connect asked to be called later: the flush catches up, it does not fail")
        assertEquals(emptyMap(), result.failures, "a delete asked to wait is not a write failure")
        assertEquals(listOf(id, oldId), sleep.idsOf(night), "the id the delete would have removed stays named, for the next flush")
        assertEquals(listOf(id, oldId), sessions().map { it.meta.clientRecordId }, "Health Connect is never left emptier than it was")
    }

    // ---- the skin-temperature feature check asked to wait ----

    /** The night keyed on [day] (start of day UTC), in bed 23:00 the evening before to 07:00, with a published [mean]. */
    private fun nightWindow(day: String, mean: Double): NightWindow {
        val key = Instant.parse("${day}T00:00:00Z")
        return NightWindow(key = "${key.epochSecond}.0", night = key, inBedStart = key.minusSeconds(3600), inBedEnd = key.plusSeconds(7 * 3600), nightlyCelsius = mean)
    }

    private fun temp(at: Instant, c: Double) = QuantitySample(MetricKind.TEMPERATURE, at, value = c)

    private fun seedThreePriorNightsAndTonight(): NightWindow {
        samples.addNights(nightWindow("2026-10-12", 33.0), nightWindow("2026-10-13", 33.5), nightWindow("2026-10-14", 34.0))
        val tonight = nightWindow("2026-10-16", 33.9)
        samples.addNights(tonight)
        samples.add(temp(tonight.inBedStart.plusSeconds(600), 33.2), temp(tonight.inBedStart.plusSeconds(1800), 34.1))
        return tonight
    }

    @Test
    fun aSkinTemperatureFeatureCheckAskedToWaitCatchesUpAndFailsNothing() = runTest {
        val tonight = seedThreePriorNightsAndTonight()
        val checkWaits = object : HealthConnectPort by hc {
            override suspend fun featureAvailable(feature: HealthFeature): Boolean = throw HealthRetryLaterException(RetryReason.RATE_LIMITED, "Health Connect is busy")
        }

        val result = HealthWriter(checkWaits, samples, WriteGeneration(generations), InMemoryHealthFailures(), clock::now, { zone }, { RingDevice("Gen 2") }).flush()

        assertTrue(result.catchingUp, "the feature check is a call: asked to wait, the flush catches up")
        assertEquals(emptyMap(), result.failures, "asked to wait is not a failure of the type")
        assertTrue(RecordType.SKIN_TEMPERATURE !in result.unsupported, "the feature is not unsupported — the check never answered")
        assertNull(samples.mark(MetricKind.TEMPERATURE), "nothing was written, so the readings stay pending")
        assertEquals(tonight.inBedStart.plusSeconds(1800), samples.pendingSamples(MetricKind.TEMPERATURE).maxOf { it.start }, "the readings are still waiting for the next flush")
    }
}
