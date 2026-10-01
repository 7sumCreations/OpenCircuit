package io.github.opencircuit.ringkit

// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepDetailMetrics.swift
// (@ b1c2fdd), whole.
//
// Port notes:
//  • "Nothing moved" is an active cut of `Int.MAX_VALUE` (upstream `Int.max`, 64-bit); every
//    magnitude is far below either, so no epoch can reach it.
//  • A window keeps an epoch exactly on its end, as Foundation's closed `DateInterval.contains`.
//  • Averages and the percentile index round half away from zero, as Swift's `rounded()`.

import java.time.Instant

/**
 * Sleep-detail metrics: per-stage average HR and a 2.5-min, 3-level body-movement chart. Both are
 * pure functions of the decoded `0x4c` epochs — per-epoch HR `[4]` and the `[10:15]` motion channel
 * (../docs/PROTOCOL.md §5.3) — one level per 150 s epoch, which is what one record spans.
 */
object SleepDetailMetrics {

    // Per-stage average HR

    /**
     * Average HR (bpm, rounded) within each sleep stage. A sleep-vitals epoch's HR is attributed to
     * the first STAGED (non-in-bed) segment whose half-open span contains its time, or failing that
     * the first whose closed span does. Stages with no HR coverage are omitted.
     */
    fun averageHRByStage(records: List<BulkRecord>, segments: List<SleepSegment>, epoch: Long = Command.SYNC_EPOCH): Map<SleepStage, Int> {
        // Staged segments only — in-bed overlaps everything and would double-count.
        val staged = segments.filter { it.stage != SleepStage.IN_BED }
        if (staged.isEmpty()) return emptyMap()

        val sums = LinkedHashMap<SleepStage, Int>()
        val counts = LinkedHashMap<SleepStage, Int>()
        for (r in records) {
            if (r.layout != BulkRecord.Layout.SLEEP_VITALS) continue
            val hr = r.heartRate ?: continue
            val t = r.date(epoch)
            val seg = staged.firstOrNull { !it.start.isAfter(t) && t.isBefore(it.end) }
                ?: staged.firstOrNull { !it.start.isAfter(t) && !t.isAfter(it.end) }
                ?: continue
            sums[seg.stage] = (sums[seg.stage] ?: 0) + hr
            counts[seg.stage] = (counts[seg.stage] ?: 0) + 1
        }
        val out = LinkedHashMap<SleepStage, Int>()
        for ((stage, count) in counts) {
            if (count > 0) out[stage] = roundHalfAwayFromZero(sums.getValue(stage).toDouble() / count).toInt()
        }
        return out
    }

    // Movement timeline (2.5 min, 3 levels)

    /** Three intensity levels for one 2.5-min epoch, from the motion counts. [rawValue] is upstream's. */
    enum class MovementLevel(val rawValue: Int) {
        /** Baseline only — no movement. */
        STILL(0),

        /** Some movement. */
        LIGHT(1),

        /** Substantial movement (likely awake / an arousal). */
        ACTIVE(2),
    }

    /**
     * Fraction of the night's OWN moving epochs that read ACTIVE rather than LIGHT. The still/moving
     * split is absolute (zero intra-epoch motion = still); this only decides where, WITHIN the
     * movement the night had, light becomes active — a distribution split, not a physical motion
     * baseline, so a calm night is never painted active by an absolute cut.
     */
    const val ACTIVE_PERCENTILE: Double = 0.80

    /** One epoch of the movement chart; [magnitude] is the summed non-baseline motion. */
    data class MovementEpoch(val time: Instant, val level: MovementLevel, val magnitude: Int)

    /**
     * Per-epoch movement levels across [records] (sorted by counter, a stable sort), restricted to
     * [window] when given — one entry per `0x4c` record. Idle/unworn epochs read still. The motion
     * channel is the one [BulkSleep]'s detection reads for the same records. [activeThreshold] is an
     * optional absolute cut on the intra-epoch energy for ACTIVE; null (production) derives it from the
     * night's own movement distribution, so there is no hardcoded motion baseline.
     */
    fun movement(
        records: List<BulkRecord>,
        window: DateInterval? = null,
        activeThreshold: Int? = null,
        epoch: Long = Command.SYNC_EPOCH,
        motionPolicy: BulkSleep.MotionChannelPolicy = BulkSleep.MotionChannelPolicy.DEFAULT,
    ): List<MovementEpoch> {
        val scoped = records.sortedBy { it.counter }.filter { r -> window?.containsClosed(r.date(epoch)) ?: true }
        val source = BulkSleep.motionSource(scoped, policy = motionPolicy)
        val mags = scoped.map { record ->
            when (source) {
                BulkSleep.MotionSource.Primary -> epochMotionEnergy(record)
                is BulkSleep.MotionSource.IntensityTail -> record.motionIntensityTail.let { t -> (0 until t.size).sumOf { t.u8(it) } }
                BulkSleep.MotionSource.ActivityMagnitudes -> record.activityMagnitudes.sum()
            }
        }
        val cut = activeThreshold ?: derivedActiveCut(mags)
        return scoped.zip(mags) { r, mag ->
            val level = when {
                mag == 0 -> MovementLevel.STILL
                mag >= cut -> MovementLevel.ACTIVE
                else -> MovementLevel.LIGHT
            }
            MovementEpoch(r.date(epoch), level, mag)
        }
    }

    /**
     * Intra-epoch movement energy: how far the five 30-s sub-samples rise above the epoch's OWN
     * minimum. A constant run — the ring's still/placeholder filler at ANY level (Gen 2 `01`, Gen 3
     * `0f`, a drifted idle) — has zero energy (still); real motion varies sample to sample and rises
     * above its floor. No device-specific baseline constant.
     */
    internal fun epochMotionEnergy(r: BulkRecord): Int {
        val m = r.motion
        if (m.isEmpty()) return 0
        val base = (0 until m.size).minOf { m.u8(it) }
        return (0 until m.size).sumOf { m.u8(it) - base }
    }

    /**
     * Light/active boundary from the night's OWN movement: the [ACTIVE_PERCENTILE] of the positive
     * epoch energies (index rounded half away from zero). `Int.MAX_VALUE` when nothing moved, so an
     * all-still night has no active epochs.
     */
    internal fun derivedActiveCut(mags: List<Int>): Int {
        val positive = mags.filter { it > 0 }.sorted()
        if (positive.isEmpty()) return Int.MAX_VALUE
        val idx = roundHalfAwayFromZero((positive.size - 1).toDouble() * ACTIVE_PERCENTILE).toInt()
        return positive[idx]
    }

    /**
     * Compact movement summary for persistence and display: the per-epoch level series
     * ([MovementLevel.rawValue] per epoch, a few hundred bytes a night) plus the counts.
     */
    data class MovementSummary(val levels: List<Int>, val still: Int, val light: Int, val active: Int) {
        val total: Int get() = still + light + active

        /** Share of epochs with any movement (light or active), 0…1 — a one-glance "restlessness". */
        val movementFraction: Double get() = if (total > 0) (light + active).toDouble() / total else 0.0
    }

    /** [movement] reduced to a [MovementSummary] (default motion policy, as upstream). */
    fun movementSummary(
        records: List<BulkRecord>,
        window: DateInterval? = null,
        activeThreshold: Int? = null,
        epoch: Long = Command.SYNC_EPOCH,
    ): MovementSummary {
        val epochs = movement(records, window, activeThreshold, epoch)
        var still = 0
        var light = 0
        var active = 0
        for (e in epochs) {
            when (e.level) {
                MovementLevel.STILL -> still++
                MovementLevel.LIGHT -> light++
                MovementLevel.ACTIVE -> active++
            }
        }
        return MovementSummary(epochs.map { it.level.rawValue }, still, light, active)
    }
}
