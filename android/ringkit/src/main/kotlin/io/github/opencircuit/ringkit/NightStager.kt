package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId

// One night's staging — the steps between a night's records and what the store keeps for it — shared
// by the app's history commit and the replay harness (`SleepReplay.stage`), so the two can never
// stage a night differently. Port of upstream ios/OpenCircuit/BLE/RingSession.swift (@ b1c2fdd):
// `overnightStagedSegments` (:1668-1733), `personalSleepBaseline` (:1736-1760) and
// `computeSleepExtras` (:2114-2188). Upstream reads the store, the clock and the device calendar
// inside those functions; here every input is a parameter, the zone included.

/** One night's staging: classify, the overnight envelope gate, the personal baseline and the extras. */
object NightStager {

    /** A night already in the store, as the baseline and the extras read it: its key, deep-sleep HR and skin temperature (0 = none). */
    class RecentNight(val night: Instant, val hrDeep: Int, val skinTempC: Double)

    /**
     * What the store keeps beside the stage minutes (upstream's `SleepNightExtras`): the nightly skin
     * temperature (0 = not published) and whether a judged coverage withheld it, the composite score,
     * the overnight stress score (0 = none), the average heart rate per stage and the movement levels.
     */
    data class Extras(
        val skinTempC: Double = 0.0,
        val skinTempWithheld: Boolean = false,
        val sleepScore: Int = 0,
        val stressScore: Int = 0,
        val hrByStage: Map<SleepStage, Int> = emptyMap(),
        val movementLevels: List<Int> = emptyList(),
    )

    /** How many stored nights the baseline reads, and how many of them it keeps (upstream `limit: 8`, `prefix(7)`). */
    private const val BASELINE_READ = 8
    private const val BASELINE_NIGHTS = 7

    /**
     * The staged hypnogram of one night, or nothing when it is not an overnight night (upstream
     * `overnightStagedSegments`). [nightRecords] is the night's slice; [archive] is every record held
     * — the truncated-night correction judges the hole before the night against it, never against
     * the slice, whose leading edge is 30 min before the onset by construction.
     *
     * Classify, then gate the WHOLE in-bed envelope (earliest start to latest end, so a stitched night
     * is judged as one): plainly overnight → kept; otherwise kept only when it is overnight with the
     * onset presumed missing, which [BulkSleep.onsetIsUnobserved] must earn against [archive].
     * Nothing in bed → the classifier's output as it is.
     */
    fun stage(
        nightRecords: List<BulkRecord>,
        archive: List<BulkRecord>,
        zone: ZoneId,
        temperatures: List<TemperatureSample> = emptyList(),
        baseline: SleepStaging.PersonalBaseline? = null,
        tuning: SleepStaging.Tuning = SleepStaging.Tuning.DEFAULT,
        motionPolicy: BulkSleep.MotionChannelPolicy = BulkSleep.MotionChannelPolicy.DEFAULT,
        epoch: Long = Command.SYNC_EPOCH,
    ): List<SleepSegment> {
        val segs = SleepStaging.classify(nightRecords, temperatures, epoch, tuning, baseline, motionPolicy)
        val inBeds = segs.filter { it.stage == SleepStage.IN_BED }
        val lo = inBeds.minOfOrNull { it.start } ?: return segs
        val hi = inBeds.maxOf { it.end }
        if (SleepWindow.isOvernightBlock(lo, hi, zone)) return segs
        val onsetIsUnobserved = BulkSleep.onsetIsUnobserved(DateInterval(lo, maxOf(hi, lo)), archive, epoch)
        return if (SleepWindow.isOvernightBlock(lo, hi, onsetIsUnobserved = onsetIsUnobserved, zone = zone)) segs else emptyList()
    }

    /**
     * The wearer's deep-sleep HR baseline (upstream `personalSleepBaseline`): the median deep HR of up
     * to 7 of the [recent] stored nights (latest first, as the store lists them; upstream reads 8),
     * never the night being staged — matched by its key day in [zone], so a re-sync of the same night
     * cannot fold its own deep HR into its baseline. Null below 3 nights.
     */
    fun personalBaseline(
        nightRecords: List<BulkRecord>,
        recent: List<RecentNight>,
        zone: ZoneId,
        epoch: Long = Command.SYNC_EPOCH,
    ): SleepStaging.PersonalBaseline? {
        val stagedDay = BulkSleep.mainSleep(nightRecords, epoch = epoch)?.let { SleepNightKey.night(it.start, it.end, zone) }
        val deepHR = recent.take(BASELINE_READ)
            .filter { stagedDay == null || CalendarDay.startOfDay(it.night, zone) != stagedDay }
            .take(BASELINE_NIGHTS)
            .map { it.hrDeep }
        return SleepStaging.PersonalBaseline.fromRecentDeepHR(deepHR)
    }

    /**
     * The night's extras (upstream `computeSleepExtras`), over the in-bed window `[start, end]`:
     * the skin temperature by [SkinTempBaseline.nightlyVerdict] on [temperatures] (published → the
     * mean; a rejected coverage → withheld, so a stale stored value is cleared; too few → left
     * alone), its offset from the baseline of the [recent] nights other than tonight, the per-stage
     * heart rate and movement from [nightRecords] (the whole night, never one drain's slice), the
     * overnight stress from the window's RMSSD, and the composite score with the resting HR.
     */
    fun extras(
        summary: SleepStaging.Summary,
        segments: List<SleepSegment>,
        nightRecords: List<BulkRecord>,
        start: Instant,
        end: Instant,
        temperatures: List<TemperatureSample>,
        recent: List<RecentNight>,
        zone: ZoneId,
        epoch: Long = Command.SYNC_EPOCH,
    ): Extras {
        val window = DateInterval(start, maxOf(end, start))
        var skinTempC = 0.0
        var withheld = false
        val nightlyTemp: Double? = when (val verdict = SkinTempBaseline.nightlyVerdict(temperatures, window)) {
            is SkinTempBaseline.NightlyVerdict.Published -> verdict.celsius.also { skinTempC = it }
            is SkinTempBaseline.NightlyVerdict.RejectedCoverage -> null.also { withheld = true }
            SkinTempBaseline.NightlyVerdict.NotMeasured -> null
        }
        // Prior nights only: tonight's own stored row, keyed the way rows are keyed, is excluded.
        val tonightDay = SleepNightKey.night(start, end, zone)
        val prior = recent
            .filter { it.skinTempC > 0 && CalendarDay.startOfDay(it.night, zone) != tonightDay }
            .map { SkinTempBaseline.NightlyTemp(it.night, it.skinTempC) }
        val baseline = SkinTempBaseline.baseline(prior)
        val tempOffset = if (nightlyTemp != null && baseline != null) nightlyTemp - baseline else null

        val rmssd = nightRecords.filter { window.containsClosed(it.date(epoch)) }.mapNotNull { it.hrvRMSSD }
        val nightHR = nightRecords.mapNotNull { r -> r.heartRate?.let { val t = r.date(epoch); HRSample(it, t, t) } }
        val composite = SleepScore.composite(
            SleepScore.CompositeInput(
                totalAsleep = SleepStaging.seconds(summary.totalAsleep),
                timeAwake = SleepStaging.seconds(summary.awake),
                efficiency = summary.efficiency,
                deep = SleepStaging.seconds(summary.deep),
                light = SleepStaging.seconds(summary.light),
                rem = SleepStaging.seconds(summary.rem),
                restingHR = RestingHR.value(nightHR, segments),
                tempOffsetC = tempOffset,
            ),
        )
        return Extras(
            skinTempC = skinTempC,
            skinTempWithheld = withheld,
            sleepScore = composite.score,
            stressScore = SleepStress.overnightScore(rmssd) ?: 0,
            hrByStage = SleepDetailMetrics.averageHRByStage(nightRecords, segments, epoch),
            movementLevels = SleepDetailMetrics.movementSummary(nightRecords, window, epoch = epoch).levels,
        )
    }
}
