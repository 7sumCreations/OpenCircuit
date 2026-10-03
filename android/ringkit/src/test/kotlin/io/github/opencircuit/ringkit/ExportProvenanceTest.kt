package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportEngine.SleepRow
import io.github.opencircuit.ringkit.ExportEngine.SleepSessionRow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ExportProvenanceTests.swift (@ b1c2fdd)
 * — all 5 tests.
 *
 * THE EXPORT MUST BE ABLE TO SAY WHICH MINUTES WERE MEASURED. Two things these tests stop regressing:
 * a MEASURED night's JSON is what earlier schema-3 exports produced (no provenance key anywhere), and
 * `measuredEfficiency` is OMITTED when withheld — never 0 (a real efficiency) and never a JSON null.
 *
 * Upstream's fixture buckets its night with `Calendar.current.startOfDay`; here the start of the day
 * is taken in an explicit [zone], the zone the export is printed in.
 */
class ExportProvenanceTest {

    private val t0 = FoundationDate.unix(1_700_000_000.0)
    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    private fun at(m: Double): Instant = t0.plusNanos((m * 60e9).roundToLong())

    private fun json(hypnogram: List<SleepSegment>): ReplayJson.Obj {
        val night = t0.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val summary = SleepRow(
            night = night, asleepMin = 400, deepMin = 40, lightMin = 330, remMin = 30, awakeMin = 36,
            efficiency = 0.9, skinTempC = 0.0, sleepScore = 70, stressScore = 0,
        )
        val session = SleepSessionRow(
            sessionID = ExportEngine.sessionID(night, zone), night = night,
            inBedStart = at(0.0), inBedEnd = at(439.0), sleepOnset = at(36.0), sleepWake = at(439.0),
            isManuallyEdited = true, hypnogram = hypnogram, summary = summary,
        )
        val text = ExportEngine.toJSON(samples = emptyList(), sleep = listOf(summary), daily = emptyList(), zone = zone, now = t0, sleepSessions = listOf(session))
            ?: fail("export did not produce a JSON object — a nil/optional leaked into the dictionary")
        return ExportJsonReader.root(text)
    }

    private fun firstSession(obj: ReplayJson.Obj): ReplayJson.Obj = obj["sleepSessions"]?.asObjectList()?.firstOrNull() ?: fail("no session")

    private val measuredNight
        get() = listOf(
            SleepSegment(at(0.0), at(439.0), SleepStage.IN_BED),
            SleepSegment(at(0.0), at(36.0), SleepStage.AWAKE),
            SleepSegment(at(36.0), at(439.0), SleepStage.ASLEEP_CORE),
        )

    /** The 08-18 shape: a long asserted block over a hole, plus real measured sleep before it. */
    private val partlyAssertedNight
        get() = listOf(
            SleepSegment(at(0.0), at(195.0), SleepStage.IN_BED),
            SleepSegment(at(195.0), at(439.0), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(0.0), at(36.0), SleepStage.AWAKE, SleepProvenance.ASSERTED_OVER_MEASURED),
            SleepSegment(at(36.0), at(195.0), SleepStage.ASLEEP_CORE),
            SleepSegment(at(195.0), at(439.0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        )

    @Test
    fun measuredNightEmitsNoProvenanceKeysAtAll() {
        val session = firstSession(json(measuredNight))
        val rows = session["hypnogram"]?.asObjectList() ?: emptyList()
        assertFalse(rows.isEmpty())
        for (row in rows) assertNull(row["provenance"], "a measured night's export must be unchanged for existing consumers")
        assertNull(session["provenanceSummary"])
    }

    @Test
    fun assertedSegmentsAreLabelledInTheTimeline() {
        val rows = firstSession(json(partlyAssertedNight))["hypnogram"]?.asObjectList() ?: emptyList()
        val asserted = rows.filter { it.string("provenance") == "asserted" }
        assertEquals(1, asserted.size, "the invented block must be individually identifiable")
        assertEquals("asleepCore", asserted.firstOrNull()?.string("stage"))
        assertEquals(244.0 * 60, asserted.firstOrNull()?.double("durationSec"))

        // A relabelled-but-measured span is distinguishable from an invented one.
        assertEquals(1, rows.count { it.string("provenance") == "assertedOverMeasured" })
    }

    @Test
    fun provenanceSummaryRollsTheSameFactUp() {
        val s = assertNotNull(firstSession(json(partlyAssertedNight)).obj("provenanceSummary"))
        assertEquals(244.0 * 60, s.double("assertedAsleepSec"))
        assertEquals(159.0 * 60, s.double("measuredAsleepSec"))
        assertEquals(195.0 * 60, s.double("coveredInBedSec"))
        assertEquals(195.0 / 439.0, assertNotNull(s.double("coverageFraction")), 1e-9)
        assertEquals(244.0 * 60, s.double("longestUnmeasuredGapSec"))
        assertEquals(false, s["scorable"]?.let { (it as? ReplayJson.Bool)?.value })
    }

    @Test
    fun withheldEfficiencyIsOMITTEDNotZeroAndNotNull() {
        // Barely any covered ground -> the ratio is withheld. The key must simply not be there.
        val thin = listOf(
            SleepSegment(at(0.0), at(5.0), SleepStage.IN_BED),
            SleepSegment(at(5.0), at(439.0), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(0.0), at(5.0), SleepStage.ASLEEP_CORE),
            SleepSegment(at(5.0), at(439.0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        )
        val obj = json(thin) // also proves the writer did not refuse it
        val s = assertNotNull(firstSession(obj).obj("provenanceSummary"))
        assertNull(s["measuredEfficiency"], "withheld must be ABSENT, never 0 and never null")
        assertNotNull(s["coverageFraction"], "coverage is still stated — that is the honest part")
    }

    @Test
    fun publishedEfficiencyIsPresentWhenThereIsEnoughGround() {
        val s = assertNotNull(firstSession(json(partlyAssertedNight)).obj("provenanceSummary"))
        assertEquals(159.0 / 195.0, assertNotNull(s.double("measuredEfficiency")), 1e-9)
    }
}
