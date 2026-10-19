package io.github.opencircuit.health

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A logged period as Health Connect records: one menstruation flow record per day it covers and
 * the menstruation period record(s) over those days. Read from upstream's builder
 * (ios/OpenCircuit/Health/HealthKitWriter.swift `menstrualFlowSamples` :803-843 @ b1c2fdd), which
 * writes one flow sample per day and no period object (HealthKit has none). The days come from the
 * ported `CyclePredictor.periodMirrorLastDay`; the 31-day period limit was measured on Android 16.
 */
class FlowRecordBuilderTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-16T12:00:00Z")

    private fun entry(start: String, end: String?, flow: Int = 2, ids: List<String> = emptyList(), written: Boolean = false) =
        PeriodToWrite(Instant.parse(start), end?.let(Instant::parse), flow, written, ids)

    @Test
    fun flowLevelsAreMappedAsUpstreamDoesAndAnythingElseIsMedium() {
        // HKW:811-815: 1 → light, 3 → heavy, else medium.
        assertEquals(MenstruationFlowLevel.LIGHT, PeriodRecords.flowLevel(1))
        assertEquals(MenstruationFlowLevel.MEDIUM, PeriodRecords.flowLevel(2))
        assertEquals(MenstruationFlowLevel.HEAVY, PeriodRecords.flowLevel(3))
        for (other in listOf(0, 4, -1, Int.MAX_VALUE)) assertEquals(MenstruationFlowLevel.MEDIUM, PeriodRecords.flowLevel(other), "$other")
    }

    @Test
    fun aLoggedPeriodIsOneFlowRecordPerDayAtLocalMidnightAndOnePeriodOverThem() {
        val zone = ZoneId.of("Europe/Berlin")
        val built = PeriodRecords.build(entry("2026-10-03T07:30:00Z", "2026-10-05T20:00:00Z", flow = 3), now, zone, version = 4)

        assertEquals(listOf("oc:flow:2026-10-03", "oc:flow:2026-10-04", "oc:flow:2026-10-05"), built.flows.map { it.meta.clientRecordId })
        assertEquals(
            listOf("2026-10-02T22:00:00Z", "2026-10-03T22:00:00Z", "2026-10-04T22:00:00Z").map(Instant::parse),
            built.flows.map { it.time },
        )
        assertTrue(built.flows.all { it.zoneOffset == ZoneOffset.ofHours(2) && it.flow == MenstruationFlowLevel.HEAVY })
        val period = built.periods.single()
        assertEquals("oc:period:2026-10-03", period.meta.clientRecordId)
        assertEquals(Instant.parse("2026-10-02T22:00:00Z"), period.start)
        assertEquals(Instant.parse("2026-10-05T22:00:00Z"), period.end, "the period ends at the start of the day after its last")
        for (r in built.flows + built.periods) {
            assertEquals(RecordingMethod.MANUAL_ENTRY, r.meta.method, "the wearer typed it in")
            assertNull(r.meta.device, "no ring measured it")
            assertEquals(4L, r.meta.version)
            assertNull(HealthRanges.violation(r, now), "$r")
        }
    }

    @Test
    fun anOpenPeriodCoversUpToTodayAndStopsAtItsEighthDay() {
        // Today is its 8th day: 8 flows, through today.
        val eighth = PeriodRecords.build(entry("2026-10-09T09:00:00Z", null), now, utc, 1)
        assertEquals(8, eighth.flows.size)
        assertEquals(LocalDate.parse("2026-10-16"), eighth.flows.last().time.atZone(utc).toLocalDate())
        // Today would be its 9th day: still 8, the cap stops it adding days the wearer never stated.
        val ninth = PeriodRecords.build(entry("2026-10-08T09:00:00Z", null), now, utc, 1)
        assertEquals(8, ninth.flows.size)
        assertEquals(LocalDate.parse("2026-10-15"), ninth.flows.last().time.atZone(utc).toLocalDate())
        assertEquals(Instant.parse("2026-10-16T00:00:00Z"), ninth.periods.single().end)
    }

    @Test
    fun anOpenPeriodNeverTakesBackADayAlreadyWritten() {
        // Twelve flow days already in Health Connect (written before the cap): the floor keeps them.
        val written = (4..15).map { "oc:flow:2026-10-%02d".format(java.util.Locale.ROOT, it) } + "oc:period:2026-10-04"
        val built = PeriodRecords.build(entry("2026-10-04T09:00:00Z", null, ids = written, written = true), now, utc, 2)

        assertEquals(12, built.flows.size, "the period id is not a covered day")
        assertEquals(written.dropLast(1), built.flows.map { it.meta.clientRecordId })
    }

    @Test
    fun aLoggedEndAfterTodayIsWrittenThroughTodayAndThePeriodMayEndInTheFuture() {
        val built = PeriodRecords.build(entry("2026-10-14T09:00:00Z", "2026-10-18T09:00:00Z"), now, utc, 1)

        assertEquals(listOf("oc:flow:2026-10-14", "oc:flow:2026-10-15", "oc:flow:2026-10-16"), built.flows.map { it.meta.clientRecordId })
        assertTrue(built.flows.none { it.time.isAfter(now) }, "a flow day is never in the future")
        val period = built.periods.single()
        assertEquals(Instant.parse("2026-10-17T00:00:00Z"), period.end, "ends at tomorrow's start: accepted on Android 16")
        assertNull(HealthRanges.violation(period, now))
    }

    @Test
    fun aPeriodLoggedToStartInTheFutureBuildsNothing() {
        val built = PeriodRecords.build(entry("2026-10-17T09:00:00Z", "2026-10-19T09:00:00Z"), now, utc, 1)

        assertEquals(emptyList(), built.flows)
        assertEquals(emptyList(), built.periods)
    }

    @Test
    fun aPeriodLongerThanThirtyOneDaysIsSplitIntoPiecesOfAtMostThirtyOne() {
        // A 40-day logged period (an explicit end is never capped): 31 + 9 days.
        val built = PeriodRecords.build(entry("2026-09-01T09:00:00Z", "2026-10-10T09:00:00Z"), now, utc, 1)

        assertEquals(40, built.flows.size)
        assertEquals(listOf("oc:period:2026-09-01", "oc:period:2026-10-02"), built.periods.map { it.meta.clientRecordId })
        assertEquals(listOf(31L, 9L), built.periods.map { Duration.between(it.start, it.end).toDays() })
        assertEquals(built.periods[0].end, built.periods[1].start, "the pieces meet, no day lost or doubled")
        assertTrue(built.periods.all { HealthRanges.violation(it, now) == null })
        // Exactly 31 days is one piece.
        val thirtyOne = PeriodRecords.build(entry("2026-09-01T09:00:00Z", "2026-10-01T09:00:00Z"), now, utc, 1)
        assertEquals(1, thirtyOne.periods.size)
        assertEquals(31L, Duration.between(thirtyOne.periods[0].start, thirtyOne.periods[0].end).toDays())
    }

    @Test
    fun aThirtyOneDayPieceOverTheAutumnClockChangeIsOneDayShorter() {
        // Europe/London gains an hour on 2026-10-25: 31 local days from 1 October last 31 d + 1 h.
        val london = ZoneId.of("Europe/London")
        val later = Instant.parse("2026-11-05T12:00:00Z")
        val built = PeriodRecords.build(entry("2026-10-01T09:00:00Z", "2026-10-31T09:00:00Z"), later, london, 1)

        assertEquals(31, built.flows.size)
        assertEquals(listOf("oc:period:2026-10-01", "oc:period:2026-10-31"), built.periods.map { it.meta.clientRecordId })
        assertTrue(built.periods.all { HealthRanges.violation(it, later) == null }, "each piece within the platform's 31 days")
        assertEquals(built.periods[0].end, built.periods[1].start)
    }

    @Test
    fun upToDateReadsTheCoveredDaysOffTheFlowIdsOnly() {
        val ids = listOf("oc:flow:2026-10-14", "oc:flow:2026-10-15", "oc:flow:2026-10-16", "oc:period:2026-10-14")
        assertTrue(PeriodRecords.isUpToDate(entry("2026-10-14T09:00:00Z", null, ids = ids, written = true), now, utc))
        // A day has elapsed since (tomorrow): no longer up to date.
        assertTrue(!PeriodRecords.isUpToDate(entry("2026-10-14T09:00:00Z", null, ids = ids, written = true), now.plusSeconds(86_400), utc))
        // Edited (written cleared): never up to date, whatever the count.
        assertTrue(!PeriodRecords.isUpToDate(entry("2026-10-14T09:00:00Z", null, ids = ids, written = false), now, utc))
    }

    @Test
    fun noHeadacheRecordTypeExistsSoNothingOfAHeadacheCanBeEmitted() {
        // Upstream pins that its flow builder never emits the headache type (HeadacheHealthKitTests
        // :136); Health Connect has no headache type at all, so the record model must not grow one.
        assertEquals(
            listOf(
                "HEART_RATE", "RESTING_HEART_RATE", "HEART_RATE_VARIABILITY_RMSSD", "OXYGEN_SATURATION", "RESPIRATORY_RATE",
                "SKIN_TEMPERATURE", "STEPS", "DISTANCE", "ACTIVE_CALORIES_BURNED", "BASAL_METABOLIC_RATE", "SLEEP_SESSION",
                "MENSTRUATION_FLOW", "MENSTRUATION_PERIOD",
            ),
            RecordType.entries.map { it.name },
        )
        val built = PeriodRecords.build(entry("2026-10-11T00:00:00Z", "2026-10-11T00:00:00Z"), now, utc, 1)
        assertEquals(listOf(RecordType.MENSTRUATION_FLOW), built.flows.map { it.type })
        assertEquals(listOf(RecordType.MENSTRUATION_PERIOD), built.periods.map { it.type })
    }
}
