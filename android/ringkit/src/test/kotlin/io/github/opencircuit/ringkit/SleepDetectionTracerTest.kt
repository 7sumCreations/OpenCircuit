package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.SleepDifferentialFixtures.bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tracer for sleep detection: recorded `0x4c` records → the detector → the main sleep
 * block → the health-store segments → the duration score, each stage checked against upstream's
 * golden output for the same night AND against the stage before it. The stages each pass alone;
 * this checks they agree where they meet.
 *
 * Nights come from the differential set (`SleepDifferentialFixtures`), rebuilt on the raw byte path.
 */
class SleepDetectionTracerTest {

    private val nights = SleepDifferentialFixtures.inputs().associateBy { it.id }
    private val goldens = SleepDifferentialFixtures.goldens()

    private fun golden(id: String, kind: String): List<String> = goldens.getValue(id).filter { it.startsWith("$kind ") }

    @Test
    fun gappedNightCrossesDetectionMainSleepSegmentsAndScore() {
        val n = nights.getValue("synthetic-017") // a 3 h recording hole splits the night
        assertTrue(n.temps.isNotEmpty(), "the night carries skin temperatures, so the wear gate is on the path")

        // records → detector → main sleep block
        val main = assertNotNull(BulkSleep.mainSleep(n.records, temperatures = n.temps))
        assertEquals(golden(n.id, "main"), listOf("main ${main.start.epochSecond} ${main.end.epochSecond}"))

        // main block → segments: two contiguous runs, each segmented alone and stitched in order
        assertEquals(2, BulkSleep.contiguousFragments(n.records).size)
        val segs = BulkSleep.sleepSegments(n.records, temperatures = n.temps)
        assertEquals(golden(n.id, "seg"), segs.map { "seg ${it.stage.rawValue} ${it.start.epochSecond} ${it.end.epochSecond}" })
        val inBed = segs.filter { it.stage == SleepStage.IN_BED }
        assertEquals(2, inBed.size, "one in-bed span per run")
        assertEquals(main.start to main.end, inBed[0].start to inBed[0].end, "the main block is the first run's in-bed span")
        for (s in segs.filter { it.stage != SleepStage.IN_BED }) {
            assertTrue(inBed.any { !s.start.isBefore(it.start) && !s.end.isAfter(it.end) }, "$s lies inside an in-bed span")
        }

        // main block → score: graded (below the 8 h ideal) and equal to the duration form
        val score = SleepScore.score(main.start, main.end)
        assertEquals(golden(n.id, "score"), listOf("score ${bits(score)}"))
        assertEquals(SleepScore.score(durationSeconds = main.duration.seconds), score)
        assertTrue(score > 0.0 && score < 100.0, "a graded score, not the clamp: $score")
    }

    @Test
    fun awakeStillEveningIsRemovedByTheHeartRateGateBeforeMainSleep() {
        val n = nights.getValue("synthetic-026") // a still, high-HR evening, then the real night
        val motionOnly = assertNotNull(ActivityPeriod.mainSleepBlock(ActivityPeriod.detectFromMotion(BulkSleep.motionTimeline(n.records))))

        val main = assertNotNull(BulkSleep.mainSleep(n.records, temperatures = n.temps))
        assertEquals(golden(n.id, "main"), listOf("main ${main.start.epochSecond} ${main.end.epochSecond}"))
        assertTrue(motionOnly.start.isBefore(main.start), "motion alone opens the night in the awake evening; the HR gate moves it later")

        val segs = BulkSleep.sleepSegments(n.records, temperatures = n.temps)
        assertEquals(golden(n.id, "seg"), segs.map { "seg ${it.stage.rawValue} ${it.start.epochSecond} ${it.end.epochSecond}" })
        assertEquals(golden(n.id, "score"), listOf("score ${bits(SleepScore.score(main.start, main.end))}"))
    }
}
