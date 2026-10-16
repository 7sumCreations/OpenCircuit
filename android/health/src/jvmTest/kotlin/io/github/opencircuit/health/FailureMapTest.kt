package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The persisted per-type write-failure map: a type whose write failed is stamped with when, and
 * cleared by its next successful write, so "nothing pending" and "writes failing" stay apart and
 * every flush entry point sees the same map. Ported from upstream
 * ios/OpenCircuitTests/HealthKitShareTypesTests.swift (@ b1c2fdd) :254
 * `testWriteFailureMapPersistsAndClearsPerMetric` and :279
 * `testFlushResultFailuresDefaultEmptyAndDoNotCountAsWrite`, over
 * ios/OpenCircuit/Health/HealthKitWriter.swift :377-396; the rest is Kotlin-only, through the
 * writer and [FakeHealthConnect]: a skipped (not granted) type and a call that is only "later"
 * never enter the map.
 */
class FailureMapTest {

    private fun at(s: Long) = Instant.ofEpochSecond(s)

    // ---- ported ----

    @Test
    fun writeFailureMapPersistsAndClearsPerType() {
        var map = emptyMap<RecordType, Instant>()
        assertTrue(map.isEmpty())

        // SpO2 + sleep fail this pass; heart rate wrote fine.
        map = FailureMap.merge(map, written = setOf(RecordType.HEART_RATE), failed = setOf(RecordType.OXYGEN_SATURATION, RecordType.SLEEP_SESSION), now = at(1000))
        assertEquals(setOf(RecordType.OXYGEN_SATURATION, RecordType.SLEEP_SESSION), map.keys)

        // Next pass: SpO2 recovers (a later success wins), sleep still failing → only sleep remains.
        map = FailureMap.merge(map, written = setOf(RecordType.OXYGEN_SATURATION), failed = setOf(RecordType.SLEEP_SESSION), now = at(2000))
        assertEquals(setOf(RecordType.SLEEP_SESSION), map.keys)

        // Sleep recovers → the map empties (back to a clean "nothing failing" state).
        map = FailureMap.merge(map, written = setOf(RecordType.SLEEP_SESSION), failed = emptySet(), now = at(3000))
        assertTrue(map.isEmpty())
    }

    @Test
    fun flushResultFailuresDefaultEmptyAndDoNotCountAsWrite() {
        val r = FlushResult(finishedAt = at(0))
        assertTrue(r.failures.isEmpty())
        assertEquals(0, r.recordsWritten)
        val failed = r.copy(failures = mapOf(RecordType.SLEEP_SESSION to "HealthCallFailedException"))
        assertEquals(0, failed.recordsWritten)
    }

    // ---- Kotlin-only ----

    @Test
    fun aFailureKeepsItsTimeAndIsStampedAgainEachTimeItFails() {
        var map = FailureMap.merge(emptyMap(), written = emptySet(), failed = setOf(RecordType.SLEEP_SESSION), now = at(1000))
        map = FailureMap.merge(map, written = emptySet(), failed = emptySet(), now = at(2000))
        assertEquals(mapOf(RecordType.SLEEP_SESSION to at(1000)), map, "a pass that did not try sleep leaves it as it was")
        map = FailureMap.merge(map, written = emptySet(), failed = setOf(RecordType.SLEEP_SESSION), now = at(3000))
        assertEquals(mapOf(RecordType.SLEEP_SESSION to at(3000)), map)
    }

    @Test
    fun aTypeThatWroteAndThenFailedInOnePassIsStillFailing() {
        // Its first call was accepted and a later one failed: part of it is still waiting.
        val map = FailureMap.merge(emptyMap(), written = setOf(RecordType.HEART_RATE), failed = setOf(RecordType.HEART_RATE), now = at(1000))
        assertEquals(mapOf(RecordType.HEART_RATE to at(1000)), map)
    }

    // ---- through the writer ----

    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val generations = InMemoryWriteGenerations()
    private val failureStore = InMemoryHealthFailures()
    private val t0 = Instant.parse("2026-10-16T02:00:00Z")

    private fun writer() = HealthWriter(hc, samples, WriteGeneration(generations), failureStore, clock::now, { ZoneOffset.UTC }, { RingDevice("Gen 2") })

    private fun spo2(min: Long) = QuantitySample(MetricKind.SPO2, t0.plusSeconds(60 * min), value = 0.97)
    private fun rr(min: Long) = QuantitySample(MetricKind.RESPIRATORY_RATE, t0.plusSeconds(60 * min), value = 14.0)

    @Test
    fun aFailedTypeIsStoredWithItsTimeAndClearedByItsNextSuccessOnAnotherWriter() = runTest {
        samples.add(spo2(1), rr(1))
        hc.failNext(RecordType.OXYGEN_SATURATION, HealthCallFailedException("the connection broke"))

        val failed = writer().flush()

        assertEquals(mapOf(RecordType.OXYGEN_SATURATION to clock.now()), failureStore.stored)
        assertEquals(failureStore.stored, failed.failureMap)

        // Another entry point's writer, later: the write goes through and the stamp goes.
        clock.advance(Duration.ofMinutes(5))
        val healed = writer().flush()

        assertEquals(emptyMap(), failureStore.stored)
        assertEquals(emptyMap(), healed.failureMap)
    }

    @Test
    fun aFailedTypeThatIsNotTriedKeepsItsStamp() = runTest {
        samples.add(spo2(1))
        hc.failNext(RecordType.OXYGEN_SATURATION, HealthCallFailedException("the connection broke"))
        writer().flush()
        val stamp = clock.now()

        // Not allowed any more: skipped, so neither written nor failed this time.
        hc.revoke(HealthPermission.OXYGEN_SATURATION)
        samples.add(rr(2))
        clock.advance(Duration.ofMinutes(5))
        writer().flush()

        assertEquals(mapOf(RecordType.OXYGEN_SATURATION to stamp), failureStore.stored)
    }

    @Test
    fun aTypeNotAllowedOrACallForLaterNeverEntersTheMap() = runTest {
        samples.add(spo2(1), rr(1))
        hc.revoke(HealthPermission.OXYGEN_SATURATION)
        hc.failNext(RecordType.RESPIRATORY_RATE, HealthRetryLaterException(RetryReason.RATE_LIMITED, "later"))

        val result = writer().flush()

        assertEquals(setOf(RecordType.OXYGEN_SATURATION), result.notGranted)
        assertTrue(result.catchingUp)
        assertEquals(emptyMap(), failureStore.stored)
        assertEquals(emptyMap(), result.failureMap)
    }

    @Test
    fun nothingChangedSavesNothing() = runTest {
        writer().flush()
        assertEquals(0, failureStore.saves)
    }
}
