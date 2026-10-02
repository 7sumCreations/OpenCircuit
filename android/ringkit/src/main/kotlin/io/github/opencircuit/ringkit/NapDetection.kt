package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/NapDetection.swift (@ b1c2fdd),
// whole. (`MIN_NAP_DURATION` arrived first, with night selection, which reads it; it keeps its home.)
//
// Port notes:
//  • `naps` takes the zone "overnight" is judged in as a required `ZoneId`; upstream reads the device
//    calendar inside `SleepWindow.isOvernightBlock`.
//  • Durations are `Duration`s (upstream `TimeInterval`).
//  • Windows are closed at both ends (an epoch exactly on a nap's end belongs to it), as upstream's
//    `t >= start && t <= end`.

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

/**
 * Automatic nap detection. The ring app auto-records naps longer than 15 minutes and folds them into
 * daily sleep. The same daytime sleep is found from the SAME motion signal night detection uses —
 * [ActivityPeriod.detectFromMotion] over the day's `0x4c` `[10:15]` motion — keeping only stillness
 * blocks that are (a) at least [MIN_NAP_DURATION], (b) NOT the main overnight sleep, (c) daytime (a
 * non-overnight midpoint, via [SleepWindow.isOvernightBlock]) and (d) predominantly ring-measured
 * sleep. The daytime gate is what keeps naps from double-counting against the main night.
 */
object NapDetection {

    /** Minimum length for a stillness block to count as a nap: 15 min, the ring app's own auto-nap floor. */
    val MIN_NAP_DURATION: Duration = Duration.ofMinutes(15)

    /**
     * A daytime block at least this long is more likely a long sedentary period (a film, a long
     * meeting) than a nap; still recorded, but flagged [Nap.isLongNap] for the UI to caveat, as the
     * app does.
     */
    val LONG_NAP_DURATION: Duration = Duration.ofHours(3)

    /**
     * Minimum share of a candidate block's WORN epochs the ring measured as sleep (sleep-vitals
     * layout) for it to count as a nap — what separates a real nap from awake stillness, which the
     * ring tags as activity. 🟡 Real sleep blocks ran 43–67 % sleep-vitals upstream, the awake all-day
     * channel's still runs 0–25 %.
     */
    const val MIN_NAP_SLEEP_VITALS_SHARE: Double = 0.35

    /**
     * One nap: its span and its health-store segments (in-bed + asleep/awake or staged sub-segments).
     * A value, as upstream's struct is: the segment list is copied in and read-only out, so a caller
     * mutating the list it passed cannot change a nap already built.
     */
    class Nap(val start: Instant, val end: Instant, segments: List<SleepSegment>) {
        val segments: List<SleepSegment> = Collections.unmodifiableList(ArrayList(segments))

        override fun equals(other: Any?): Boolean =
            other is Nap && start == other.start && end == other.end && segments == other.segments

        override fun hashCode(): Int = listOf(start, end, segments).hashCode()

        override fun toString(): String = "Nap(start=$start, end=$end, segments=$segments)"

        val duration: Duration get() = Duration.between(start, end)

        val isLongNap: Boolean get() = duration >= LONG_NAP_DURATION

        /** Time actually asleep within the nap (Core, Deep and REM sub-segments). */
        val asleep: Duration
            get() = segments
                .filter { it.stage == SleepStage.ASLEEP_CORE || it.stage == SleepStage.ASLEEP_DEEP || it.stage == SleepStage.ASLEEP_REM }
                .fold(Duration.ZERO) { acc, s -> acc.plus(s.duration) }
    }

    /**
     * Daytime naps in a day's [records], in chronological order, excluding anything that overlaps
     * [mainSleep] (pass the already-detected main block, e.g. [BulkSleep.mainSleep]); [zone] is the
     * one "overnight" is judged in. [temperatures] apply the night's off-wrist / charging wear gate.
     */
    fun naps(
        records: List<BulkRecord>,
        mainSleep: ActivityPeriod?,
        zone: ZoneId,
        temperatures: List<TemperatureSample> = emptyList(),
        epoch: Long = Command.SYNC_EPOCH,
    ): List<Nap> {
        val timeline = BulkSleep.motionTimeline(records, epoch)
        val periods = ActivityPeriod.detectFromMotion(timeline, temperatureSamples = temperatures)

        return periods.mapNotNull { p ->
            if (p.activity != Activity.SLEEP) return@mapNotNull null
            if (p.duration < MIN_NAP_DURATION) return@mapNotNull null
            // Exclude the main overnight sleep and anything overlapping it — no double count.
            if (mainSleep != null && p.start.isBefore(mainSleep.end) && p.end.isAfter(mainSleep.start)) return@mapNotNull null
            // Daytime only: a block whose MIDPOINT is at night is another night, not a nap. Deliberately
            // the plain form — a nap is judged as observed, never with a presumed unobserved onset.
            if (SleepWindow.isOvernightBlock(p.start, p.end, zone)) return@mapNotNull null
            // A real nap vs awake stillness: predominantly ring-measured sleep.
            if (sleepVitalsShare(p, records, epoch) < MIN_NAP_SLEEP_VITALS_SHARE) return@mapNotNull null
            Nap(p.start, p.end, napSegments(records, p, epoch))
        }.sortedBy { it.start }
    }

    /** Share of the block's WORN (non-idle) epochs on the sleep-vitals layout; 0 when none is worn. */
    private fun sleepVitalsShare(period: ActivityPeriod, records: List<BulkRecord>, epoch: Long): Double {
        val worn = records.filter {
            val t = it.date(epoch)
            !t.isBefore(period.start) && !t.isAfter(period.end) && it.layout != BulkRecord.Layout.IDLE
        }
        if (worn.isEmpty()) return 0.0
        return worn.count { it.layout == BulkRecord.Layout.SLEEP_VITALS }.toDouble() / worn.size
    }

    /**
     * Health-store segments for a nap: a full-window in-bed envelope plus the sleep inside it — the
     * night's own classifier's Light/Deep/REM, clipped to the nap, when it stages any sleep; otherwise
     * a coarse asleep/awake split from the motion detector, and the whole window asleep when that finds
     * nothing inside. Staging may shift the asleep/awake split but never removes the nap or its bounds.
     */
    private fun napSegments(records: List<BulkRecord>, period: ActivityPeriod, epoch: Long): List<SleepSegment> {
        val window = DateInterval(period.start, period.end)
        val inWindow = records.filter {
            val t = it.date(epoch)
            !t.isBefore(period.start) && !t.isAfter(period.end)
        }

        val staged = BulkSleep.stagedSegments(inWindow, within = window, epoch = epoch)
        val hasStagedSleep = staged.any {
            it.stage == SleepStage.ASLEEP_CORE || it.stage == SleepStage.ASLEEP_DEEP || it.stage == SleepStage.ASLEEP_REM
        }
        if (hasStagedSleep) {
            val out = mutableListOf(SleepSegment(period.start, period.end, SleepStage.IN_BED))
            for (seg in staged) {
                if (seg.stage == SleepStage.IN_BED) continue
                val s = maxOf(seg.start, period.start)
                val e = minOf(seg.end, period.end)
                if (e.isAfter(s)) out += SleepSegment(s, e, seg.stage)
            }
            return out.sortedBy { it.start }
        }

        // Coarse fallback: in-bed envelope + the motion-detected asleep/awake sub-blocks.
        val periods = ActivityPeriod.detectFromMotion(BulkSleep.motionTimeline(inWindow, epoch))
        val segs = mutableListOf(SleepSegment(period.start, period.end, SleepStage.IN_BED))
        for (p in periods) {
            if (!(p.start.isBefore(period.end) && p.end.isAfter(period.start))) continue
            val s = maxOf(p.start, period.start)
            val e = minOf(p.end, period.end)
            if (!e.isAfter(s)) continue
            segs += SleepSegment(s, e, if (p.activity == Activity.SLEEP) SleepStage.ASLEEP_CORE else SleepStage.AWAKE)
        }
        // No interior detection (a short uniform nap) → the whole window is asleep.
        if (segs.size == 1) segs += SleepSegment(period.start, period.end, SleepStage.ASLEEP_CORE)
        return segs
    }
}
