package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A night or a nap as one Health Connect sleep session (research Q11, as approved): the session
 * spans the earliest to the latest segment; in-bed is not a stage, its time no stage covers is
 * awake-in-bed; asleep-core is light on a staged night and plain sleeping on a coarse one; stages
 * never overlap (the later one starts where the earlier ended); the wearer's own spans are named in
 * the notes, a manually added nap is a manual entry.
 */
class SleepMappingTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val t0 = Instant.parse("2026-06-14T22:00:00Z")
    private fun at(min: Long): Instant = t0.plusSeconds(min * 60)
    private val meta = RecordMeta("oc:sleep:1", 1, RecordingMethod.AUTOMATICALLY_RECORDED, RingDevice("Gen 2"))

    private fun session(segments: List<SleepSegment>, path: SleepMapping.Path, m: RecordMeta = meta, title: String? = null) =
        assertNotNull(SleepMapping.session(segments, path, m, utc, title))

    @Test
    fun everyNightOfTheSevenNightBacklogBecomesOneSessionThePlatformAccepts() = runBlocking<Unit> {
        val now = Instant.parse("2026-06-20T00:00:00Z")
        val fake = FakeHealthConnect({ now }).apply { grantAll() }
        assertEquals(7, SevenNights.nights.size)
        for ((i, night) in SevenNights.nights.withIndex()) {
            for ((segments, path) in listOf(night.staged to SleepMapping.Path.STAGED, night.coarse to SleepMapping.Path.COARSE)) {
                assertTrue(segments.isNotEmpty(), "night $i staged something")
                assertEquals(path, SleepMapping.pathOf(segments), "night $i is recognised as $path")
                val s = assertNotNull(SleepMapping.session(segments, path, meta.copy(clientRecordId = "oc:sleep:$i:$path"), SevenNights.zone, null))
                assertEquals(segments.minOf { it.start }, s.start, "night $i $path: the session starts at the earliest segment")
                assertEquals(segments.maxOf { it.end }, s.end, "night $i $path: and ends at the latest")
                assertNull(HealthRanges.violation(s, now), "night $i $path passes every platform rule")
                s.stages.zipWithNext().forEach { (a, b) -> assertTrue(!b.start.isBefore(a.end), "night $i $path: stages never overlap") }
                if (path == SleepMapping.Path.COARSE) {
                    assertTrue(s.stages.none { it.stage == SleepStageType.LIGHT }, "a coarse night has no light sleep")
                    assertTrue(s.stages.any { it.stage == SleepStageType.SLEEPING })
                } else {
                    assertTrue(s.stages.any { it.stage == SleepStageType.DEEP || it.stage == SleepStageType.REM || it.stage == SleepStageType.LIGHT })
                    assertTrue(s.stages.none { it.stage == SleepStageType.SLEEPING })
                }
                fake.insert(RecordType.SLEEP_SESSION, listOf(s))
            }
        }
        assertEquals(14, fake.records(RecordType.SLEEP_SESSION).size)
    }

    @Test
    fun stagesMapOneToOneAndInBedTimeNoStageCoversIsAwakeInBed() {
        val s = session(
            listOf(
                SleepSegment(at(0), at(500), SleepStage.IN_BED),
                SleepSegment(at(0), at(15), SleepStage.AWAKE),
                SleepSegment(at(30), at(200), SleepStage.ASLEEP_CORE),
                SleepSegment(at(200), at(260), SleepStage.ASLEEP_DEEP),
                SleepSegment(at(260), at(380), SleepStage.ASLEEP_REM),
                SleepSegment(at(400), at(480), SleepStage.ASLEEP_CORE),
            ),
            SleepMapping.Path.STAGED,
        )
        assertEquals(
            listOf(
                SleepStage(at(0), at(15), SleepStageType.AWAKE),
                SleepStage(at(15), at(30), SleepStageType.AWAKE_IN_BED),
                SleepStage(at(30), at(200), SleepStageType.LIGHT),
                SleepStage(at(200), at(260), SleepStageType.DEEP),
                SleepStage(at(260), at(380), SleepStageType.REM),
                SleepStage(at(380), at(400), SleepStageType.AWAKE_IN_BED),
                SleepStage(at(400), at(480), SleepStageType.LIGHT),
                SleepStage(at(480), at(500), SleepStageType.AWAKE_IN_BED),
            ),
            s.stages,
        )
        assertEquals(at(0), s.start)
        assertEquals(at(500), s.end)
        assertEquals(ZoneOffset.UTC, s.startZoneOffset)
        assertNull(s.notes)
        assertNull(s.title)
    }

    @Test
    fun aCoarseNightIsInBedOverPlainSleep() {
        val s = session(listOf(SleepSegment(at(0), at(480), SleepStage.IN_BED), SleepSegment(at(20), at(470), SleepStage.ASLEEP_CORE)), SleepMapping.Path.COARSE)
        assertEquals(
            listOf(
                SleepStage(at(0), at(20), SleepStageType.AWAKE_IN_BED),
                SleepStage(at(20), at(470), SleepStageType.SLEEPING),
                SleepStage(at(470), at(480), SleepStageType.AWAKE_IN_BED),
            ),
            s.stages,
        )
    }

    @Test
    fun aJitterOverlapIsClippedToTheEarlierStagesEndAndAZeroLengthStageIsDropped() {
        val s = session(
            listOf(
                SleepSegment(at(0), at(60).plusMillis(400), SleepStage.ASLEEP_CORE),
                SleepSegment(at(60), at(120), SleepStage.ASLEEP_DEEP),
                SleepSegment(at(120), at(120), SleepStage.ASLEEP_REM),
                SleepSegment(at(120), at(180), SleepStage.ASLEEP_REM),
            ),
            SleepMapping.Path.STAGED,
        )
        assertEquals(
            listOf(
                SleepStage(at(0), at(60).plusMillis(400), SleepStageType.LIGHT),
                SleepStage(at(60).plusMillis(400), at(120), SleepStageType.DEEP),
                SleepStage(at(120), at(180), SleepStageType.REM),
            ),
            s.stages,
        )
    }

    @Test
    fun theWearersOwnSpansAreNamedInTheNotesInLocalTime() {
        val zone = ZoneId.of("Europe/Berlin") // 22:00 UTC is 00:00 there in June
        val s = assertNotNull(
            SleepMapping.session(
                listOf(
                    SleepSegment(at(-20), at(0), SleepStage.IN_BED, SleepProvenance.ASSERTED),
                    SleepSegment(at(-20), at(0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
                    SleepSegment(at(0), at(400), SleepStage.IN_BED),
                    SleepSegment(at(0), at(400), SleepStage.ASLEEP_CORE),
                    // Moved over ground the ring did record: an ordinary sample, not her entry.
                    SleepSegment(at(400), at(430), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED_OVER_MEASURED),
                    SleepSegment(at(430), at(450), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
                ),
                SleepMapping.Path.COARSE, meta, zone, null,
            ),
        )
        assertEquals("Times you entered: 23:40-00:00, 07:10-07:30", s.notes)
        assertEquals(RecordingMethod.AUTOMATICALLY_RECORDED, s.meta.method, "measured stages are never labelled as typed in")
    }

    @Test
    fun aManuallyAddedNapIsAManualEntryTitledNapWithNoNotes() {
        val manual = RecordMeta("oc:nap:1", 1, RecordingMethod.MANUAL_ENTRY, null)
        val s = session(
            listOf(SleepSegment(at(0), at(30), SleepStage.IN_BED, SleepProvenance.ASSERTED), SleepSegment(at(0), at(30), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED)),
            SleepMapping.Path.COARSE, manual, title = "Nap",
        )
        assertEquals("Nap", s.title)
        assertEquals(RecordingMethod.MANUAL_ENTRY, s.meta.method)
        assertNull(s.notes, "the whole nap is hers; the method says so")
        assertEquals(listOf(SleepStage(at(0), at(30), SleepStageType.SLEEPING)), s.stages)
    }

    @Test
    fun nothingToWriteIsNoSession() {
        assertNull(SleepMapping.session(emptyList(), SleepMapping.Path.STAGED, meta, utc, null))
        assertNull(SleepMapping.session(listOf(SleepSegment(at(5), at(5), SleepStage.IN_BED)), SleepMapping.Path.STAGED, meta, utc, null))
    }

    @Test
    fun aNightOnlyOfInBedAndCoreIsCoarseAndAnyOtherStageMakesItStaged() {
        assertEquals(SleepMapping.Path.COARSE, SleepMapping.pathOf(listOf(SleepSegment(at(0), at(9), SleepStage.IN_BED), SleepSegment(at(0), at(9), SleepStage.ASLEEP_CORE))))
        assertEquals(SleepMapping.Path.STAGED, SleepMapping.pathOf(listOf(SleepSegment(at(0), at(9), SleepStage.ASLEEP_CORE), SleepSegment(at(9), at(10), SleepStage.AWAKE))))
    }
}
