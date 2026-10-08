package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `NightStager`: one night's staging as the app commits it and the replay harness measures it
 * (upstream `RingSession.swift` `overnightStagedSegments` :1668-1733, `personalSleepBaseline`
 * :1736-1760, `computeSleepExtras` :2114-2188 @ b1c2fdd). Fixtures are the kept seven-night
 * backlog's synthetic nights, moved in time on the raw path where a case needs another time of day.
 */
class NightStagerTest {

    private val backlog = BacklogSevenNights.load()
    private val zone = backlog.zone
    private val night1 = BulkSleep.latestNightRecords(backlog.nightRecords(0), zone, temperatures = backlog.temps)

    // The overnight envelope gate

    @Test
    fun anOvernightNightStagesExactlyAsTheClassifierStagesIt() {
        val staged = NightStager.stage(night1, archive = backlog.records, zone = zone, temperatures = backlog.temps)

        assertTrue(staged.any { it.stage == SleepStage.IN_BED })
        assertEquals(SleepStaging.classify(night1, temperatures = backlog.temps), staged)
    }

    @Test
    fun aDaytimeBlockStagesToNothing() {
        val midday = moved(night1, Duration.ofHours(12))
        assertTrue(SleepStaging.classify(midday).isNotEmpty(), "the classifier alone stages the block")

        assertEquals(emptyList(), NightStager.stage(midday, archive = midday, zone = zone))
    }

    @Test
    fun aTruncatedMorningTailIsJudgedAgainstTheArchiveNotTheSlice() {
        // The night's last 3 h, moved so it ends late morning: the plain overnight rule rejects it.
        val end = night1.last().date()
        val tail = moved(night1.filter { !it.date().isBefore(end.minus(Duration.ofHours(3))) }, Duration.ofHours(5))
        val tailStart = tail.first().date()
        assertTrue(SleepStaging.classify(tail).isNotEmpty())

        // Nothing recorded before it: the onset was never captured, so the presumed night is accepted.
        assertTrue(NightStager.stage(tail, archive = tail, zone = zone).isNotEmpty())
        // Recorded awake hours right up to it: the onset was observed — the same slice is rejected.
        val awakeBefore = awake(tailStart.minus(Duration.ofHours(6)), count = 6 * 24)
        assertEquals(emptyList(), NightStager.stage(tail, archive = awakeBefore + tail, zone = zone))
    }

    @Test
    fun theReplayHarnessStagesThroughTheSameStager() {
        // The harness's own records → its 30 h union → its night slice → NightStager: one path.
        val week = backlog.records
        val replayed = SleepReplay.stage(week, zone, temperatures = backlog.temps)
        assertTrue(replayed.segments.isNotEmpty())
        assertEquals(NightStager.stage(replayed.nightRecords, replayed.union, zone, temperatures = backlog.temps), replayed.segments)
        // And the harness drops a daytime block exactly as the app does.
        assertEquals(emptyList(), SleepReplay.stage(moved(night1, Duration.ofHours(12)), zone).segments)
    }

    // The personal deep-sleep HR baseline

    @Test
    fun theBaselineIsTheMedianOfUpToSevenRecentNightsAndNeverTonight() {
        val tonight = nightKey(night1)
        fun stored(daysBefore: Long, hrDeep: Int) = NightStager.RecentNight(tonight.minus(Duration.ofDays(daysBefore)), hrDeep, skinTempC = 0.0)

        // Tonight already stored (a re-sync) with a deep HR of 90: it must not move its own baseline.
        val recent = listOf(NightStager.RecentNight(tonight, 90, 0.0), stored(1, 50), stored(2, 52), stored(3, 54))
        assertEquals(SleepStaging.PersonalBaseline.fromRecentDeepHR(listOf(50, 52, 54)), NightStager.personalBaseline(night1, recent, zone))
        // Two settled nights are not enough.
        assertNull(NightStager.personalBaseline(night1, recent.take(3), zone))
        // Upstream reads 8 stored nights and keeps at most 7: the 9th-latest never counts.
        val nine = (1L..9L).map { stored(it, if (it == 9L) 200 else 50 + it.toInt()) }
        assertEquals(
            SleepStaging.PersonalBaseline.fromRecentDeepHR((1..7).map { 50 + it }),
            NightStager.personalBaseline(night1, nine, zone),
        )
    }

    // The night's extras

    @Test
    fun skinTemperatureIsPublishedWithheldOrLeftAloneByItsCoverage() {
        val segs = NightStager.stage(night1, backlog.records, zone, temperatures = backlog.temps)
        val start = segs.minOf { it.start }
        val end = segs.maxOf { it.end }
        val summary = SleepStaging.summary(segs)
        fun extras(temps: List<TemperatureSample>) = NightStager.extras(summary, segs, night1, start, end, temps, emptyList(), zone)

        // Every 10 min across the window at 34.0 °C: published.
        val spread = generateSequence(start) { it.plus(Duration.ofMinutes(10)) }.takeWhile { !it.isAfter(end) }
            .map { TemperatureSample(it, 34.0) }.toList()
        val published = extras(spread)
        assertEquals(34.0, published.skinTempC)
        assertFalse(published.skinTempWithheld)
        // The same count crowded into the window's first minutes: judged and rejected — withheld, no value.
        val crowded = spread.mapIndexed { i, s -> TemperatureSample(start.plusSeconds(i.toLong()), s.celsius) }
        val withheld = extras(crowded)
        assertEquals(0.0, withheld.skinTempC)
        assertTrue(withheld.skinTempWithheld)
        // No readings at all: not measured — nothing published, nothing withheld.
        val none = extras(emptyList())
        assertEquals(0.0, none.skinTempC)
        assertFalse(none.skinTempWithheld)
    }

    @Test
    fun theScoreStressStageHeartRatesAndMovementComeFromTheWholeNight() {
        val segs = NightStager.stage(night1, backlog.records, zone, temperatures = backlog.temps)
        val start = segs.minOf { it.start }
        val end = segs.maxOf { it.end }
        val summary = SleepStaging.summary(segs)
        val extras = NightStager.extras(summary, segs, night1, start, end, emptyList(), emptyList(), zone)

        assertEquals(SleepDetailMetrics.averageHRByStage(night1, segs), extras.hrByStage)
        assertTrue(extras.hrByStage.isNotEmpty())
        assertEquals(SleepDetailMetrics.movementSummary(night1, DateInterval(start, end)).levels, extras.movementLevels)
        assertTrue(extras.movementLevels.isNotEmpty())
        assertTrue(extras.sleepScore in 1..100, "a staged night scores")
        val rmssd = night1.filter { DateInterval(start, end).containsClosed(it.date()) }.mapNotNull { it.hrvRMSSD }
        assertEquals(SleepStress.overnightScore(rmssd) ?: 0, extras.stressScore)
    }

    private fun nightKey(records: List<BulkRecord>): Instant {
        val block = checkNotNull(BulkSleep.mainSleep(records))
        return checkNotNull(SleepNightKey.night(block.start, block.end, zone))
    }

    /** [records] with every counter moved [by] (raw path: only bytes 0–3 change). */
    private fun moved(records: List<BulkRecord>, by: Duration): List<BulkRecord> = records.map { r ->
        val counter = r.counter + by.seconds
        val b = r.raw.copyOf()
        b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
        b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
        BulkRecord.of(b)!!
    }

    /** [count] awake epochs from [start], 150 s apart: motion that varies within and between epochs. */
    private fun awake(start: Instant, count: Int): List<BulkRecord> {
        val shape = intArrayOf(8, 62, 19, 77, 34)
        return (0 until count).map { i ->
            val counter = start.epochSecond - Command.SYNC_EPOCH + i * 150L
            val b = ByteArray(BulkRecord.LENGTH)
            b[0] = (counter shr 24).toByte(); b[1] = (counter shr 16).toByte()
            b[2] = (counter shr 8).toByte(); b[3] = counter.toByte()
            b[4] = 80
            val lift = (i % 7) * 6
            for (k in 10 until 15) b[k] = ((shape[(k - 10 + i) % 5] + lift) and 0xff).toByte()
            BulkRecord.of(b)!!
        }
    }
}
