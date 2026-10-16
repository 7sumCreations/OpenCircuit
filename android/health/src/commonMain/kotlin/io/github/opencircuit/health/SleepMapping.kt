package io.github.opencircuit.health

import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage as RingStage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A night or a nap as one Health Connect sleep session (research Q11 as approved; spike S10 on the
 * device: overlapping stages and a stage outside the session are refused).
 *
 * - The session spans the earliest to the latest of the segments given (the in-bed span).
 * - In-bed is not a stage: in-bed time no other segment covers is AWAKE_IN_BED; outside the in-bed
 *   layer nothing is invented.
 * - AWAKE → AWAKE, deep → DEEP, REM → REM; core → LIGHT on a [Path.STAGED] night, SLEEPING on a
 *   [Path.COARSE] one (the motion-only pair says "asleep", not which stage).
 * - Stages never overlap: sorted by start, a stage that starts before the previous one ended starts
 *   where it ended (the ring's sub-second jitter), and one with nothing left is dropped, as is any
 *   zero-length stage.
 * - One record, whatever the provenance: the wearer's own spans (proven-unmeasured, the
 *   `SleepHealthPublication.userEntered` bucket) are named in [SleepSession.notes] as local times; a
 *   record whose [RecordMeta.method] is MANUAL_ENTRY (a nap she added) needs no such note.
 *
 * Health Connect has no per-stage provenance and no custom metadata, so this is what survives of
 * upstream's per-sample `HKMetadataKeyWasUserEntered` (PORTING D-290).
 */
object SleepMapping {
    /** Which staging produced a night's segments. */
    enum class Path {
        /** The hypnogram: light, deep, REM, awake. */
        STAGED,

        /** The motion-only pair: in bed, asleep. */
        COARSE,
    }

    /** [Path.COARSE] when every segment is in-bed or core asleep (the ring's coarse pair), else [Path.STAGED]. */
    fun pathOf(segments: List<SleepSegment>): Path =
        if (segments.all { it.stage == RingStage.IN_BED || it.stage == RingStage.ASLEEP_CORE }) Path.COARSE else Path.STAGED

    /** The session of [segments], or null when they hold no time at all. */
    fun session(segments: List<SleepSegment>, path: Path, meta: RecordMeta, zone: ZoneId, title: String?): SleepSession? {
        val segs = segments.filter { it.end.isAfter(it.start) }
        if (segs.isEmpty()) return null
        val start = segs.minOf { it.start }
        val end = segs.maxOf { it.end }

        val stages = ArrayList<SleepStage>()
        var covered: Instant? = null
        for (s in segs.filter { it.stage != RingStage.IN_BED }.sortedWith(compareBy({ it.start }, { it.end }))) {
            val from = covered?.let { maxOf(it, s.start) } ?: s.start
            if (s.end.isAfter(from)) stages += SleepStage(from, s.end, typeOf(s.stage, path))
            covered = covered?.let { maxOf(it, s.end) } ?: s.end
        }
        val awakeInBed = ArrayList<SleepStage>()
        for ((bedStart, bedEnd) in union(segs.filter { it.stage == RingStage.IN_BED })) {
            var cursor = bedStart
            for (st in stages) {
                if (!st.end.isAfter(cursor) || !st.start.isBefore(bedEnd)) continue
                if (st.start.isAfter(cursor)) awakeInBed += SleepStage(cursor, st.start, SleepStageType.AWAKE_IN_BED)
                cursor = maxOf(cursor, st.end)
            }
            if (bedEnd.isAfter(cursor)) awakeInBed += SleepStage(cursor, bedEnd, SleepStageType.AWAKE_IN_BED)
        }

        val notes = if (meta.method == RecordingMethod.AUTOMATICALLY_RECORDED) {
            union(segs.filter { it.provenance.isProvenUnmeasured })
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ", prefix = "Times you entered: ") { (a, b) -> "${CLOCK.format(a.atZone(zone))}-${CLOCK.format(b.atZone(zone))}" }
        } else {
            null
        }
        return SleepSession(
            start, zone.rules.getOffset(start), end, zone.rules.getOffset(end), title, notes,
            (stages + awakeInBed).sortedBy { it.start }, meta,
        )
    }

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    private fun typeOf(stage: RingStage, path: Path): SleepStageType = when (stage) {
        RingStage.AWAKE -> SleepStageType.AWAKE
        RingStage.ASLEEP_DEEP -> SleepStageType.DEEP
        RingStage.ASLEEP_REM -> SleepStageType.REM
        RingStage.ASLEEP_CORE -> if (path == Path.STAGED) SleepStageType.LIGHT else SleepStageType.SLEEPING
        RingStage.IN_BED -> SleepStageType.AWAKE_IN_BED
    }

    /** The spans of [segments] merged where they overlap or touch, earliest first. */
    private fun union(segments: List<SleepSegment>): List<Pair<Instant, Instant>> {
        val out = ArrayList<Pair<Instant, Instant>>()
        for (s in segments.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && !s.start.isAfter(last.second)) {
                out[out.size - 1] = last.first to maxOf(last.second, s.end)
            } else {
                out += s.start to s.end
            }
        }
        return out
    }
}
