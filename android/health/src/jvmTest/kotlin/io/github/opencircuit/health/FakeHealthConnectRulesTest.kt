package io.github.opencircuit.health

import io.github.opencircuit.health.TestRecords.NOW
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [FakeHealthConnect] is a claim about the platform, so each of its rules has its own test here:
 * a writer test that passes against the fake proves something only if the fake refuses what the
 * phone refuses. The rules and their sources (AOSP Health Connect on Android 14 to 16):
 *  1. records are keyed by (record type, clientRecordId); a record needs an id;
 *  2. a stored record is replaced only by a HIGHER version (the documented contract; AOSP also
 *     replaces on an equal one), and the returned id is the same either way;
 *  3. a call is atomic: one bad record, or one type without its WRITE grant, stores nothing;
 *  4. the platform's record rules ([HealthRanges]) at the fake's clock;
 *  5. deletes go by clientRecordId within one type, reach only this app's records, need the WRITE
 *     grant, and a missing id is not an error;
 *  6. quotas: 1000 write calls per 15 minutes and 8000 per 24 hours, 5 MB per call; plus the
 *     platform's "busy" refusals (data sync, another user in the foreground);
 *  7. injected failures, once or until cleared, and "crash after accept";
 *  8. an inspection view of the stored records and the calls made.
 */
class FakeHealthConnectRulesTest {

    private val clock = TestClock(NOW)
    private fun fake() = FakeHealthConnect(clock = clock::now).apply { grantAll() }

    // --- rule 1: keyed by (type, clientRecordId) ------------------------------------------------

    @Test
    fun rule1_theSameIdInTwoTypesIsTwoRecords() = runTest {
        val hc = fake()
        hc.insert(RecordType.OXYGEN_SATURATION, listOf(TestRecords.spo2(id = "same")))
        hc.insert(RecordType.RESPIRATORY_RATE, listOf(TestRecords.respiratoryRate(id = "same")))
        assertEquals(1, hc.records(RecordType.OXYGEN_SATURATION).size)
        assertEquals(1, hc.records(RecordType.RESPIRATORY_RATE).size)
        // Two ids in one type: two records.
        hc.insert(RecordType.OXYGEN_SATURATION, listOf(TestRecords.spo2(id = "other")))
        assertEquals(setOf("same", "other"), hc.records(RecordType.OXYGEN_SATURATION).map { it.meta.clientRecordId }.toSet())
    }

    @Test
    fun rule1_aRecordCannotBeBuiltWithoutAnId() {
        assertFailsWith<IllegalArgumentException> { TestRecords.meta("") }
        assertFailsWith<IllegalArgumentException> { TestRecords.meta("   ") }
        assertFailsWith<IllegalArgumentException> { TestRecords.meta("oc:x", version = 0) }
    }

    @Test
    fun rule1_aRecordOfAnotherTypeInTheCallIsRefused() = runTest {
        val hc = fake()
        assertFailsWith<HealthInvalidRecordException> {
            hc.insert(RecordType.OXYGEN_SATURATION, listOf(TestRecords.spo2(id = "a"), TestRecords.respiratoryRate(id = "b")))
        }
        assertTrue(hc.records(RecordType.OXYGEN_SATURATION).isEmpty())
        assertTrue(hc.records(RecordType.RESPIRATORY_RATE).isEmpty())
    }

    // --- rule 2: upsert only on a higher version -------------------------------------------------

    @Test
    fun rule2_onlyAHigherVersionReplacesAndTheReturnedIdNeverChanges() = runTest {
        val hc = fake()
        val first = hc.insert(RecordType.RESTING_HEART_RATE, listOf(TestRecords.restingHeartRate(50, id = "x", version = 2)))
        // Lower: ignored.
        val lower = hc.insert(RecordType.RESTING_HEART_RATE, listOf(TestRecords.restingHeartRate(51, id = "x", version = 1)))
        assertEquals(50, (hc.records(RecordType.RESTING_HEART_RATE).single() as RestingHeartRate).beatsPerMinute)
        // Equal: ignored (the documented rule; the writer never relies on AOSP's equal-replaces).
        val equal = hc.insert(RecordType.RESTING_HEART_RATE, listOf(TestRecords.restingHeartRate(52, id = "x", version = 2)))
        assertEquals(50, (hc.records(RecordType.RESTING_HEART_RATE).single() as RestingHeartRate).beatsPerMinute)
        // Higher: replaces, still one record.
        val higher = hc.insert(RecordType.RESTING_HEART_RATE, listOf(TestRecords.restingHeartRate(53, id = "x", version = 3)))
        assertEquals(53, (hc.records(RecordType.RESTING_HEART_RATE).single() as RestingHeartRate).beatsPerMinute)

        assertEquals(1, first.size)
        assertEquals(listOf(first, first, first), listOf(lower, equal, higher))
        // Another id in the same type returns another platform id.
        val y = hc.insert(RecordType.RESTING_HEART_RATE, listOf(TestRecords.restingHeartRate(55, id = "y")))
        assertTrue(y != first)
    }

    // --- rule 3: atomic calls --------------------------------------------------------------------

    @Test
    fun rule3_oneBadRecordStoresNothingOfTheCall() = runTest {
        val hc = fake()
        assertFailsWith<HealthInvalidRecordException> {
            hc.insert(
                RecordType.HEART_RATE_VARIABILITY_RMSSD,
                listOf(TestRecords.hrv(40.0, id = "good"), TestRecords.hrv(250.0, id = "bad"), TestRecords.hrv(41.0, id = "good2")),
            )
        }
        assertTrue(hc.records(RecordType.HEART_RATE_VARIABILITY_RMSSD).isEmpty())
    }

    @Test
    fun rule3_aTypeWithoutItsWriteGrantStoresNothing() = runTest {
        val hc = fake()
        hc.revoke(HealthPermission.OXYGEN_SATURATION)
        val e = assertFailsWith<HealthNotGrantedException> { hc.insert(RecordType.OXYGEN_SATURATION, listOf(TestRecords.spo2())) }
        assertEquals(HealthPermission.OXYGEN_SATURATION, e.permission)
        assertTrue(hc.records(RecordType.OXYGEN_SATURATION).isEmpty())
        // Other types are unaffected; flow and period share one permission.
        hc.insert(RecordType.RESPIRATORY_RATE, listOf(TestRecords.respiratoryRate()))
        hc.revoke(HealthPermission.MENSTRUATION)
        assertFailsWith<HealthNotGrantedException> { hc.insert(RecordType.MENSTRUATION_PERIOD, listOf(TestRecords.period())) }
        assertFailsWith<HealthNotGrantedException> { hc.insert(RecordType.MENSTRUATION_FLOW, listOf(TestRecords.flow())) }
        assertEquals(1, hc.records(RecordType.RESPIRATORY_RATE).size)
    }

    @Test
    fun rule3_grantsAreWhatTheFakeReports() = runTest {
        val hc = FakeHealthConnect(clock = clock::now)
        assertEquals(emptySet(), hc.grantedWrites())
        hc.grant(HealthPermission.STEPS, HealthPermission.SLEEP)
        assertEquals(setOf(HealthPermission.STEPS, HealthPermission.SLEEP), hc.grantedWrites())
        assertFailsWith<HealthNotGrantedException> { hc.insert(RecordType.DISTANCE, listOf(TestRecords.distance())) }
    }

    // --- rule 4: the platform's record rules at the fake's clock --------------------------------

    @Test
    fun rule4_recordRulesAreThePlatformsNotJetpacksJvmRules() = runTest {
        val hc = fake()
        val start = NOW.minusSeconds(3600)
        // An empty heart-rate series passes Jetpack's JVM checks; the phone refuses it.
        assertFailsWith<HealthInvalidRecordException> {
            hc.insert(RecordType.HEART_RATE, listOf(TestRecords.heartRate(start = start, samples = emptyList())))
        }
        assertFailsWith<HealthInvalidRecordException> {
            hc.insert(RecordType.HEART_RATE, listOf(TestRecords.heartRate(start = start, samples = listOf(HeartRateSample(start.minusSeconds(1), 60)))))
        }
        assertFailsWith<HealthInvalidRecordException> { hc.insert(RecordType.STEPS, listOf(TestRecords.steps(0))) }
        val s = NOW.minusSeconds(7200)
        assertFailsWith<HealthInvalidRecordException> {
            hc.insert(
                RecordType.SLEEP_SESSION,
                listOf(
                    TestRecords.sleep(
                        start = s, end = NOW.minusSeconds(60),
                        stages = listOf(SleepStage(s, s.plusSeconds(120), SleepStageType.DEEP), SleepStage(s.plusSeconds(60), s.plusSeconds(600), SleepStageType.REM)),
                    ),
                ),
            )
        }
        assertFailsWith<HealthInvalidRecordException> {
            hc.insert(RecordType.SKIN_TEMPERATURE, listOf(TestRecords.skin(deltas = listOf(SkinTemperatureDelta(NOW.minusSeconds(3600), 0.1)))))
        }
        assertTrue(RecordType.entries.all { hc.records(it).isEmpty() })
    }

    @Test
    fun rule4_theFutureIsJudgedByTheFakesClock() = runTest {
        val hc = fake()
        val later = NOW.plusSeconds(600)
        assertFailsWith<HealthInvalidRecordException> { hc.insert(RecordType.STEPS, listOf(TestRecords.steps(start = later, end = later.plusSeconds(60)))) }
        clock.advance(Duration.ofSeconds(600))
        hc.insert(RecordType.STEPS, listOf(TestRecords.steps(start = later, end = later.plusSeconds(60))))
        assertEquals(1, hc.records(RecordType.STEPS).size)
    }

    // --- rule 5: deletes -------------------------------------------------------------------------

    @Test
    fun rule5_deletesGoByIdWithinOneTypeAndAMissingIdIsNoError() = runTest {
        val hc = fake()
        hc.insert(RecordType.OXYGEN_SATURATION, listOf(TestRecords.spo2(id = "a"), TestRecords.spo2(id = "b")))
        hc.insert(RecordType.RESPIRATORY_RATE, listOf(TestRecords.respiratoryRate(id = "a")))
        hc.deleteByClientIds(RecordType.OXYGEN_SATURATION, listOf("a", "never-written"))
        assertEquals(listOf("b"), hc.records(RecordType.OXYGEN_SATURATION).map { it.meta.clientRecordId })
        assertEquals(listOf("a"), hc.records(RecordType.RESPIRATORY_RATE).map { it.meta.clientRecordId })
    }

    @Test
    fun rule5_deletesReachOnlyThisAppsRecords() = runTest {
        val hc = fake()
        hc.seedOtherApp(TestRecords.steps(id = "a"))
        hc.insert(RecordType.STEPS, listOf(TestRecords.steps(id = "a")))
        hc.deleteByClientIds(RecordType.STEPS, listOf("a"))
        assertTrue(hc.records(RecordType.STEPS).isEmpty())
        assertEquals(listOf("a"), hc.otherAppRecords(RecordType.STEPS).map { it.meta.clientRecordId })
        // And reading back returns only our own.
        hc.insert(RecordType.STEPS, listOf(TestRecords.steps(id = "b")))
        assertEquals(listOf("b"), hc.readOwn(RecordType.STEPS, Instant.EPOCH, NOW.plusSeconds(1)).map { it.meta.clientRecordId })
    }

    @Test
    fun rule5_afterARevokeNeitherDeleteNorReadOwnWorks() = runTest {
        val hc = fake()
        hc.insert(RecordType.STEPS, listOf(TestRecords.steps(id = "a")))
        hc.revoke(HealthPermission.STEPS)
        assertFailsWith<HealthNotGrantedException> { hc.deleteByClientIds(RecordType.STEPS, listOf("a")) }
        assertFailsWith<HealthNotGrantedException> { hc.readOwn(RecordType.STEPS, Instant.EPOCH, NOW) }
        assertEquals(1, hc.records(RecordType.STEPS).size)
    }

    // --- rule 6: quotas and busy refusals -------------------------------------------------------

    @Test
    fun rule6_theThousandAndFirstCallIn15MinutesIsRefusedThenAllowedOnceTheWindowMoves() = runTest {
        val hc = fake()
        repeat(1000) { i -> hc.insert(RecordType.STEPS, listOf(TestRecords.steps(id = "s$i"))) }
        val e = assertFailsWith<HealthRetryLaterException> { hc.insert(RecordType.STEPS, listOf(TestRecords.steps(id = "over"))) }
        assertEquals(RetryReason.RATE_LIMITED, e.reason)
        // Deletes spend the same quota.
        assertFailsWith<HealthRetryLaterException> { hc.deleteByClientIds(RecordType.STEPS, listOf("s0")) }
        assertEquals(1000, hc.records(RecordType.STEPS).size)
        clock.advance(Duration.ofMinutes(15).plusMillis(1))
        hc.insert(RecordType.STEPS, listOf(TestRecords.steps(id = "after")))
        assertEquals(1001, hc.records(RecordType.STEPS).size)
    }

    @Test
    fun rule6_eightThousandCallsIn24HoursIsTheDailyCap() = runTest {
        val hc = fake()
        // 8 windows of 1000 calls, each 15 minutes apart: under the 15-minute cap, at the daily one.
        repeat(8) { w ->
            repeat(1000) { i -> hc.deleteByClientIds(RecordType.STEPS, listOf("w$w-$i")) }
            clock.advance(Duration.ofMinutes(15).plusMillis(1))
        }
        assertFailsWith<HealthRetryLaterException> { hc.deleteByClientIds(RecordType.STEPS, listOf("one-more")) }
        clock.advance(Duration.ofHours(24))
        hc.deleteByClientIds(RecordType.STEPS, listOf("next-day"))
    }

    @Test
    fun rule6_aCallOver5MegabytesIsRefusedAndStoresNothing() = runTest {
        val hc = fake()
        val start = NOW.minusSeconds(3600)
        // 3600 samples per hour record: about 230 KB each by the estimate, so 25 of them pass 5 MB.
        val samples = List(3600) { HeartRateSample(start.plusSeconds(it.toLong()), 60) }
        val hours = List(25) { TestRecords.heartRate(id = "h$it", start = start, samples = samples) }
        assertTrue(RecordSize.estimateBytes(hours) > 5L * 1024 * 1024)
        val e = assertFailsWith<HealthRetryLaterException> { hc.insert(RecordType.HEART_RATE, hours) }
        assertEquals(RetryReason.RATE_LIMITED, e.reason)
        assertTrue(hc.records(RecordType.HEART_RATE).isEmpty())
        hc.insert(RecordType.HEART_RATE, hours.take(5))
        assertEquals(5, hc.records(RecordType.HEART_RATE).size)
    }

    @Test
    fun rule6_busyRefusalsAreRetryableAndStoreNothing() = runTest {
        val hc = fake()
        for (reason in listOf(RetryReason.DATA_SYNC_IN_PROGRESS, RetryReason.NOT_FOREGROUND_USER)) {
            hc.busy = reason
            val e = assertFailsWith<HealthRetryLaterException> { hc.insert(RecordType.STEPS, listOf(TestRecords.steps())) }
            assertEquals(reason, e.reason)
            assertFailsWith<HealthRetryLaterException> { hc.deleteByClientIds(RecordType.STEPS, listOf("x")) }
        }
        hc.busy = null
        hc.insert(RecordType.STEPS, listOf(TestRecords.steps()))
        assertEquals(1, hc.records(RecordType.STEPS).size)
    }

    // --- rule 7: injected failures ---------------------------------------------------------------

    @Test
    fun rule7_aFailureOnceFailsOneCallOfThatTypeOnly() = runTest {
        val hc = fake()
        hc.failNext(RecordType.STEPS, HealthCallFailedException("disk error"))
        assertFailsWith<HealthCallFailedException> { hc.insert(RecordType.STEPS, listOf(TestRecords.steps(id = "a"))) }
        hc.insert(RecordType.DISTANCE, listOf(TestRecords.distance()))
        hc.insert(RecordType.STEPS, listOf(TestRecords.steps(id = "a")))
        assertEquals(1, hc.records(RecordType.STEPS).size)
        assertEquals(1, hc.records(RecordType.DISTANCE).size)
    }

    @Test
    fun rule7_aPersistentFailureLastsUntilCleared() = runTest {
        val hc = fake()
        hc.failAlways(RecordType.SLEEP_SESSION, HealthCallFailedException("broken"))
        repeat(3) { assertFailsWith<HealthCallFailedException> { hc.insert(RecordType.SLEEP_SESSION, listOf(TestRecords.sleep())) } }
        hc.clearFailures()
        hc.insert(RecordType.SLEEP_SESSION, listOf(TestRecords.sleep()))
        assertEquals(1, hc.records(RecordType.SLEEP_SESSION).size)
    }

    @Test
    fun rule7_aCrashAfterAcceptStoresTheRecordsThenFailsAndAReplayAddsNoCopy() = runTest {
        val hc = fake()
        hc.crashAfterAccept(RecordType.OXYGEN_SATURATION)
        val records = listOf(TestRecords.spo2(id = "a"), TestRecords.spo2(id = "b"))
        assertFailsWith<HealthCallFailedException> { hc.insert(RecordType.OXYGEN_SATURATION, records) }
        assertEquals(2, hc.records(RecordType.OXYGEN_SATURATION).size)
        // The caller did not see the success, so it sends the same records again: still two.
        hc.insert(RecordType.OXYGEN_SATURATION, records.map { TestRecords.spo2(id = it.meta.clientRecordId, version = 2) })
        assertEquals(2, hc.records(RecordType.OXYGEN_SATURATION).size)
    }

    // --- rule 8: inspection ----------------------------------------------------------------------

    @Test
    fun rule8_theCallLogNamesEveryCallAndItsOutcome() = runTest {
        val hc = fake()
        hc.insert(RecordType.STEPS, listOf(TestRecords.steps(id = "a"), TestRecords.steps(id = "b")))
        hc.revoke(HealthPermission.SLEEP)
        runCatching { hc.insert(RecordType.SLEEP_SESSION, listOf(TestRecords.sleep())) }
        hc.deleteByClientIds(RecordType.STEPS, listOf("a"))
        assertEquals(
            listOf(
                FakeHealthConnect.Call(FakeHealthConnect.Call.Kind.INSERT, RecordType.STEPS, 2, FakeHealthConnect.Call.Outcome.OK, listOf("a", "b")),
                FakeHealthConnect.Call(FakeHealthConnect.Call.Kind.INSERT, RecordType.SLEEP_SESSION, 1, FakeHealthConnect.Call.Outcome.NOT_GRANTED, listOf(TestRecords.sleep().meta.clientRecordId)),
                FakeHealthConnect.Call(FakeHealthConnect.Call.Kind.DELETE, RecordType.STEPS, 1, FakeHealthConnect.Call.Outcome.OK, listOf("a")),
            ),
            hc.calls,
        )
        assertEquals(listOf("b"), hc.records(RecordType.STEPS).map { it.meta.clientRecordId })
    }

    @Test
    fun rule8_readOwnReturnsRecordsStartingInTheRange() = runTest {
        val hc = fake()
        val t = NOW.minusSeconds(3600)
        hc.insert(RecordType.HEART_RATE_VARIABILITY_RMSSD, listOf(TestRecords.hrv(time = t, id = "in"), TestRecords.hrv(time = NOW, id = "out")))
        assertEquals(listOf("in"), hc.readOwn(RecordType.HEART_RATE_VARIABILITY_RMSSD, t, NOW).map { it.meta.clientRecordId })
    }

    @Test
    fun theFakesStatusAndFeaturesAreSetByTheTest() = runTest {
        val hc = fake()
        assertEquals(HealthSdkStatus.AVAILABLE, hc.sdkStatus())
        assertTrue(hc.featureAvailable(HealthFeature.SKIN_TEMPERATURE))
        hc.status = HealthSdkStatus.UNAVAILABLE
        hc.features = emptySet()
        assertEquals(HealthSdkStatus.UNAVAILABLE, hc.sdkStatus())
        assertEquals(false, hc.featureAvailable(HealthFeature.SKIN_TEMPERATURE))
    }
}
