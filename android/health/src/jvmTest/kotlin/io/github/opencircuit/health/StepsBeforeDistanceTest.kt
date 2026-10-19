package io.github.opencircuit.health

import io.github.opencircuit.ringkit.DistanceEstimate
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Steps, then the distance estimated from them (upstream HealthKitWriter.swift:538-598 @ b1c2fdd):
 * each stored step row is one steps record `oc:steps:<row start>` over its own window, so records
 * never overlap (Health Connect counts only the newest of overlapping records from one app); the
 * rows are marked once Health Connect accepted them, and only then is each one's distance written,
 * `oc:dist:<row start>`, `steps × 0.248 m`. Distance rides the rows' mark: steps that fail write no
 * distance (the rows stay pending), and distance not allowed while steps are is not caught up later
 * (upstream's accepted tradeoff, ported `distanceMayWrite`).
 */
class StepsBeforeDistanceTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val activity = InMemoryHealthActivity(zone)
    private val failures = InMemoryHealthFailures()
    private val generations = InMemoryWriteGenerations()

    private fun writer() = HealthWriter(
        hc, InMemoryHealthSamples(), WriteGeneration(generations), failures, clock::now, { zone }, { RingDevice("Gen 2") },
        activity = activity,
    )

    private fun at(text: String): Instant = Instant.parse("2026-10-16T${text}Z")

    private fun steps() = hc.records(RecordType.STEPS).map { it as Steps }.sortedBy { it.start }
    private fun distances() = hc.records(RecordType.DISTANCE).map { it as Distance }.sortedBy { it.start }

    // The same flush also writes energy and the daily records; these tests look at steps and distance only.
    private fun stepCalls() = hc.calls.filter { it.type == RecordType.STEPS || it.type == RecordType.DISTANCE }

    private fun FlushResult.stepsAndDistance() = written.filterKeys { it == RecordType.STEPS || it == RecordType.DISTANCE }

    @Test
    fun eachRowIsOneStepsRecordOverItsWindowAndItsDistanceFollows() = runTest {
        val a = activity.addSteps(at("07:30:00"), at("07:31:00"), 25)
        val b = activity.addSteps(at("08:00:00"), at("08:02:00"), 40)

        val result = writer().flush()

        assertEquals(listOf("oc:steps:${at("07:30:00").toEpochMilli()}", "oc:steps:${at("08:00:00").toEpochMilli()}"), steps().map { it.meta.clientRecordId })
        assertEquals(listOf(25L, 40L), steps().map { it.count })
        assertEquals(listOf(at("07:31:00"), at("08:02:00")), steps().map { it.end })
        assertEquals(setOf(zone.rules.getOffset(at("08:00:00"))), steps().flatMap { listOf(it.startZoneOffset, it.endZoneOffset) }.toSet())
        assertEquals(setOf(RingDevice("Gen 2")), steps().map { it.meta.device }.toSet())
        assertEquals(listOf("oc:dist:${at("07:30:00").toEpochMilli()}", "oc:dist:${at("08:00:00").toEpochMilli()}"), distances().map { it.meta.clientRecordId })
        assertEquals(listOf(25 * DistanceEstimate.METERS_PER_STEP, 40 * DistanceEstimate.METERS_PER_STEP), distances().map { it.meters })
        assertEquals(steps().map { it.start to it.end }, distances().map { it.start to it.end }, "each distance over its steps' window")
        assertTrue(activity.isWritten(a) && activity.isWritten(b))
        assertEquals(listOf(RecordType.STEPS, RecordType.DISTANCE), stepCalls().map { it.type }, "steps first, then distance")
        assertEquals(mapOf(RecordType.STEPS to 2, RecordType.DISTANCE to 2), result.stepsAndDistance())
    }

    @Test
    fun aSecondFlushWritesNothingAgain() = runTest {
        activity.addSteps(at("07:30:00"), at("07:31:00"), 25)
        writer().flush()
        val calls = stepCalls().size

        val again = writer().flush()

        assertEquals(calls, stepCalls().size)
        assertEquals(emptyMap(), again.stepsAndDistance())
    }

    @Test
    fun stepsThatFailWriteNoDistanceAndStayPending() = runTest {
        val a = activity.addSteps(at("07:30:00"), at("07:31:00"), 25)
        hc.failNext(RecordType.STEPS, IllegalStateException("boom"))

        val result = writer().flush()

        assertEquals(setOf(RecordType.STEPS), result.failures.keys)
        assertTrue(hc.calls.none { it.type == RecordType.DISTANCE })
        assertFalse(activity.isWritten(a))

        val retry = writer().flush()
        assertEquals(mapOf(RecordType.STEPS to 1, RecordType.DISTANCE to 1), retry.stepsAndDistance())
    }

    /** Upstream's tradeoff (HealthKitWriter.swift:579-588): the rows are marked, the distance is not caught up later. */
    @Test
    fun distanceNotAllowedWhileStepsAreIsSkippedAndNotCaughtUpLater() = runTest {
        hc.revoke(HealthPermission.DISTANCE)
        val a = activity.addSteps(at("07:30:00"), at("07:31:00"), 25)

        val result = writer().flush()

        assertEquals(mapOf(RecordType.STEPS to 1), result.stepsAndDistance())
        assertEquals(setOf(RecordType.DISTANCE), result.notGranted)
        assertTrue(activity.isWritten(a))

        hc.grant(HealthPermission.DISTANCE)
        writer().flush()
        assertEquals(emptyList(), distances())
    }

    @Test
    fun stepsNotAllowedWriteNeitherAndTheRowsWait() = runTest {
        hc.revoke(HealthPermission.STEPS)
        val a = activity.addSteps(at("07:30:00"), at("07:31:00"), 25)

        val result = writer().flush()

        assertEquals(setOf(RecordType.STEPS), result.notGranted)
        assertEquals(emptyList(), stepCalls())
        assertFalse(activity.isWritten(a))
    }

    /**
     * Rows that share a start (a phone clock set back) would share an id, and the second would replace
     * the first: they are one record holding their sum, over to the latest end — the rows already
     * written at that start included, so the record is the whole of them.
     */
    @Test
    fun rowsSharingAStartAreOneRecordWithTheirSum() = runTest {
        activity.addSteps(at("08:00:00"), at("08:01:00"), 10)
        writer().flush()
        activity.addSteps(at("08:00:00"), at("08:02:00"), 5)

        writer().flush()

        assertEquals(listOf(15L), steps().map { it.count })
        assertEquals(listOf(at("08:02:00")), steps().map { it.end })
        assertEquals(listOf(15 * DistanceEstimate.METERS_PER_STEP), distances().map { it.meters })
    }

    /** A window with no length breaks the platform's rule: dropped and counted once, never sent, never pending again. */
    @Test
    fun aRowWithAnEmptyWindowIsDroppedCountedAndNotPendingAgain() = runTest {
        val empty = activity.addSteps(at("08:00:00"), at("08:00:00"), 7)
        activity.addSteps(at("08:05:00"), at("08:06:00"), 9)

        val result = writer().flush()

        assertEquals(mapOf(RecordType.STEPS to 1), result.rejected)
        assertEquals(listOf(9L), steps().map { it.count })
        assertTrue(activity.isWritten(empty))
        assertEquals(emptyMap(), writer().flush().rejected)
    }

    /** A crash after Health Connect stored the call: nothing was marked, the next flush sends the same ids, nothing twice. */
    @Test
    fun aCrashAfterAcceptReplaysUnderTheSameIds() = runTest {
        activity.addSteps(at("07:30:00"), at("07:31:00"), 25)
        hc.crashAfterAccept(RecordType.STEPS)
        writer().flush()
        hc.clearFailures()

        writer().flush()

        assertEquals(listOf(25L), steps().map { it.count })
        assertEquals(1, distances().size)
    }

    /** Ported from upstream `HealthKitShareTypesTests.swift:290` `testDistanceOnlyWritesWhenStepsSucceeded`. */
    @Test
    fun distanceOnlyWritesWhenStepsSucceeded() {
        assertFalse(ActivityRecords.distanceMayWrite(stepsFailed = true, distanceFailed = false))
        assertFalse(ActivityRecords.distanceMayWrite(stepsFailed = true, distanceFailed = true))
        assertFalse(ActivityRecords.distanceMayWrite(stepsFailed = false, distanceFailed = true))
        assertTrue(ActivityRecords.distanceMayWrite(stepsFailed = false, distanceFailed = false))
    }
}
