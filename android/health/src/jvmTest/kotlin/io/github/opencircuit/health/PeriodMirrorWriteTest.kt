package io.github.opencircuit.health

import io.github.opencircuit.health.FakeHealthConnect.Call
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The period step of a flush, read from upstream's `flushMenstrualFlow`
 * (ios/OpenCircuit/Health/HealthKitWriter.swift:727-791 @ b1c2fdd) and re-expressed over Health
 * Connect ids: every logged period the store asks for that is not already up to date is written as
 * its flow days and its period record(s); the ids are named in the store before the call, the
 * records an edit removed are deleted by id only after the new ones were accepted, and the entry
 * is finalized only when nothing is left to delete. A failure stops the step; what was named stays
 * named.
 */
class PeriodMirrorWriteTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val periods = InMemoryHealthPeriods()
    private val failures = InMemoryHealthFailures()
    private val generations = InMemoryWriteGenerations()
    private fun writer() = HealthWriter(
        hc, InMemoryHealthSamples(), WriteGeneration(generations), failures, clock::now, { zone }, { RingDevice("Gen 2") },
        periods = periods,
    )

    private fun day(d: Int, time: String = "08:00:00"): Instant = Instant.parse("2026-10-%02dT%sZ".format(java.util.Locale.ROOT, d, time))
    private fun flowIds(vararg days: Int) = days.map { "oc:flow:2026-10-%02d".format(java.util.Locale.ROOT, it) }
    private fun flows() = hc.records(RecordType.MENSTRUATION_FLOW).map { it as MenstruationFlow }.sortedBy { it.time }
    private fun periodRecords() = hc.records(RecordType.MENSTRUATION_PERIOD).map { it as MenstruationPeriod }.sortedBy { it.start }
    private fun log(start: Instant, end: Instant?, flow: Int = 2) = periods.put(PeriodToWrite(start, end, flow, healthWritten = false, writtenIds = emptyList()))

    /** An edit as the store saves it: the clinical fields change, written clears, the written ids stay. */
    private fun edit(start: Instant, end: Instant?) {
        val row = periods.get(start)!!
        periods.put(row.copy(end = end, healthWritten = false))
    }

    @Test
    fun aLoggedPeriodIsWrittenAsItsFlowDaysAndItsPeriodThenFinalizedAndNotWrittenAgain() = runBlocking<Unit> {
        log(day(10), day(12), flow = 1)

        val result = writer().flush()

        assertEquals(flowIds(10, 11, 12), flows().map { it.meta.clientRecordId })
        assertTrue(flows().all { it.flow == MenstruationFlowLevel.LIGHT })
        assertEquals(listOf("oc:period:2026-10-10"), periodRecords().map { it.meta.clientRecordId })
        assertEquals(3, result.written[RecordType.MENSTRUATION_FLOW])
        assertEquals(1, result.written[RecordType.MENSTRUATION_PERIOD])
        val row = periods.get(day(10))!!
        assertTrue(row.healthWritten)
        assertEquals(flowIds(10, 11, 12) + "oc:period:2026-10-10", row.writtenIds)

        val calls = hc.calls.size
        writer().flush()
        assertEquals(calls, hc.calls.size, "a finalized period that ended before today is not looked at again")
    }

    @Test
    fun theIdsAreNamedInTheStoreBeforeTheFirstCall() = runBlocking<Unit> {
        log(day(10), day(11))
        val seen = mutableListOf<List<String>>()
        val watching = object : HealthConnectPort by hc {
            override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
                seen += periods.get(day(10))!!.writtenIds
                return hc.insert(type, records)
            }
        }
        HealthWriter(watching, InMemoryHealthSamples(), WriteGeneration(generations), failures, clock::now, { zone }, { RingDevice("Gen 2") }, periods = periods).flush()

        val all = flowIds(10, 11) + "oc:period:2026-10-10"
        assertEquals(listOf(all, all), seen, "a kill after a call can only leave a named record")
    }

    @Test
    fun anOpenPeriodUpToDateMakesNoCallAndANewDayAddsOnlyThatDayUnderTheSameIds() = runBlocking<Unit> {
        log(day(14), null)
        writer().flush()
        assertEquals(flowIds(14, 15, 16), flows().map { it.meta.clientRecordId })
        val calls = hc.calls.size

        writer().flush()
        assertEquals(calls, hc.calls.size, "same day: up to date, nothing sent (upstream's dozens-a-day bug)")

        clock.advance(Duration.ofDays(1))
        writer().flush()
        assertEquals(flowIds(14, 15, 16, 17), flows().map { it.meta.clientRecordId }, "replaced by id, never doubled")
        assertEquals(1, periodRecords().size)
        assertEquals(Instant.parse("2026-10-18T00:00:00Z"), periodRecords().single().end)
    }

    @Test
    fun anOpenPeriodPastItsEighthDayIsNotRewritten() = runBlocking<Unit> {
        log(day(1), null)
        clock.set(day(8, "12:00:00"))
        writer().flush()
        assertEquals(8, flows().size)
        val calls = hc.calls.size

        clock.set(day(9, "12:00:00")) // its 9th day
        writer().flush()

        assertEquals(calls, hc.calls.size)
        assertEquals(8, flows().size)
    }

    @Test
    fun anEditThatRemovesDaysDeletesThoseIdsAfterTheNewRecordsWereAccepted() = runBlocking<Unit> {
        log(day(1), day(5))
        writer().flush()
        assertEquals(5, flows().size)
        val before = hc.calls.size

        edit(day(1), day(3))
        writer().flush()

        val calls = hc.calls.drop(before)
        assertEquals(
            listOf(Call.Kind.INSERT to RecordType.MENSTRUATION_FLOW, Call.Kind.INSERT to RecordType.MENSTRUATION_PERIOD, Call.Kind.DELETE to RecordType.MENSTRUATION_FLOW),
            calls.map { it.kind to it.type },
        )
        assertEquals(flowIds(4, 5), calls.last().ids)
        assertEquals(flowIds(1, 2, 3), flows().map { it.meta.clientRecordId })
        assertEquals(Instant.parse("2026-10-04T00:00:00Z"), periodRecords().single().end)
        val row = periods.get(day(1))!!
        assertTrue(row.healthWritten)
        assertEquals(flowIds(1, 2, 3) + "oc:period:2026-10-01", row.writtenIds)
    }

    @Test
    fun aFailedDeleteKeepsTheIdNamedAndThePeriodPendingUntilItGoes() = runBlocking<Unit> {
        log(day(1), day(5))
        writer().flush()
        edit(day(1), day(3))
        hc.failNext(Call.Kind.DELETE, RecordType.MENSTRUATION_FLOW, HealthCallFailedException("busy"))

        writer().flush()

        val row = periods.get(day(1))!!
        assertFalse(row.healthWritten, "still something to delete")
        assertEquals(flowIds(1, 2, 3) + "oc:period:2026-10-01" + flowIds(4, 5), row.writtenIds)
        assertEquals(5, flows().size, "a failed delete never leaves Health Connect emptier than intended, and loses no name")

        writer().flush()
        assertEquals(flowIds(1, 2, 3), flows().map { it.meta.clientRecordId })
        assertTrue(periods.get(day(1))!!.healthWritten)
    }

    @Test
    fun aFailedWriteStopsTheStepKeepsEveryIdNamedAndIsRetried() = runBlocking<Unit> {
        log(day(1), day(2))
        log(day(10), day(11))
        hc.failNext(RecordType.MENSTRUATION_FLOW, HealthCallFailedException("disk"))

        val result = writer().flush()

        assertEquals("HealthCallFailedException", result.failures[RecordType.MENSTRUATION_FLOW])
        assertEquals(emptyList(), flows())
        assertFalse(periods.get(day(1))!!.healthWritten)
        assertEquals(flowIds(1, 2) + "oc:period:2026-10-01", periods.get(day(1))!!.writtenIds, "named: deleting the entry can still find them")
        assertEquals(emptyList(), periods.get(day(10))!!.writtenIds, "the step stopped at the first failure (upstream's break)")

        writer().flush()
        assertEquals(flowIds(1, 2, 10, 11), flows().map { it.meta.clientRecordId })
        assertTrue(periods.get(day(1))!!.healthWritten && periods.get(day(10))!!.healthWritten)
    }

    @Test
    fun aReplyLostAfterHealthConnectStoredTheRecordsLeavesThemNamedAndTheRetryReplacesThem() = runBlocking<Unit> {
        log(day(1), day(2))
        hc.crashAfterAccept(RecordType.MENSTRUATION_FLOW)

        writer().flush()
        assertEquals(2, flows().size, "Health Connect kept them")
        assertTrue(periods.get(day(1))!!.writtenIds.containsAll(flowIds(1, 2)))

        hc.clearFailures()
        writer().flush()
        assertEquals(2, flows().size, "re-sent under the same ids: no copy")
        assertTrue(periods.get(day(1))!!.healthWritten)
    }

    @Test
    fun withoutTheMenstruationGrantNothingIsSentAndThePeriodWaits() = runBlocking<Unit> {
        log(day(1), day(2))
        hc.revoke(HealthPermission.MENSTRUATION)

        val result = writer().flush()

        assertEquals(emptyList(), hc.calls)
        assertEquals(setOf(RecordType.MENSTRUATION_FLOW, RecordType.MENSTRUATION_PERIOD), result.notGranted)
        assertEquals(emptyList(), periods.get(day(1))!!.writtenIds)
        assertTrue(result.failures.isEmpty(), "not granted is skipped, never failed")
        assertEquals(null, generations.value, "nothing sent, no generation spent")
    }

    @Test
    fun aPeriodLoggedToStartTomorrowSendsNothing() = runBlocking<Unit> {
        log(day(17), day(19))

        writer().flush()

        assertEquals(emptyList(), hc.calls)
        assertFalse(periods.get(day(17))!!.healthWritten)
    }

    @Test
    fun aLongLoggedPeriodGoesInPiecesTheFakeAccepts() = runBlocking<Unit> {
        log(Instant.parse("2026-09-01T08:00:00Z"), day(10))

        val result = writer().flush()

        assertEquals(40, flows().size)
        assertEquals(listOf("oc:period:2026-09-01", "oc:period:2026-10-02"), periodRecords().map { it.meta.clientRecordId })
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun theRecordsOfADeletedPeriodAreDeletedByIdUnderTheirOwnTypes() = runBlocking<Unit> {
        log(day(1), day(2))
        writer().flush()
        val ids = periods.get(day(1))!!.writtenIds

        assertTrue(writer().deletePeriodRecords(ids))

        assertEquals(emptyList(), flows())
        assertEquals(emptyList(), periodRecords())
        hc.failNext(Call.Kind.DELETE, RecordType.MENSTRUATION_PERIOD, HealthCallFailedException("busy"))
        assertFalse(writer().deletePeriodRecords(listOf("oc:period:2026-10-01")), "a failed delete says so, for the caller to keep the ids")
    }
}
