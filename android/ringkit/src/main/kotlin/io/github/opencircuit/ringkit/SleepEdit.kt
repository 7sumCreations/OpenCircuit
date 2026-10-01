package io.github.opencircuit.ringkit

// The manual sleep-time edit: the editable bounds, the validator and the non-destructive recompute.
// Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/SleepEdit.swift (@ b1c2fdd).
// Read upstream's comments for the measured nights behind every rule; the essentials are kept here.
//
// The edit is anchored on the RECORDED (ring-derived) onset and wake: the ±3 h RingConn margin is a
// floor that is always offered; the archive's own epochs (`dataCoverage`) may widen it, capped at one
// plausible night; a 6 h stranded margin is offered on each edge's OWN anchor, because a recorder can
// stop mid-night while the ring is still worn; and the late edge also reaches one plausible night after
// the parity bedtime (the truncation ceiling), because a truncated night moves `recordedWake` — the
// number the wearer opened the sheet to correct — earlier. ⚠️ Neither edge may ever be derived from the
// other (three upstream drafts failed that way); "one plausible night" is enforced on the proposed
// window by `validate`'s too-long rule. Time the ring did not record is tagged by `recompute` through
// the coverage (`MeasuredCoverage`), so a wider editor never turns one wrong number into a bigger one.
//
// Shape notes: Swift `Date` is `Instant`, `TimeInterval` is `Duration`, `ClosedRange<Date>` and
// `Range<Date>` are `DateInterval`; the structs are immutable data classes (variants by `copy`); the
// error enum is the sealed `Invalid`, whose minute counts are 64-bit as upstream's `Int`; Foundation's
// `Date.distantPast` is [SleepEdit.DISTANT_PAST]. A Swift `Date` never overflows, so instant arithmetic
// here saturates at `Instant.MIN` / `Instant.MAX` instead of throwing (only instants a billion years
// out are affected). `isSamePickerMinute` takes an explicit zone where upstream takes a calendar.

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Bounds, validation and recompute for a user's edit of a night's bedtime, sleep onset and wake. */
object SleepEdit {

    /** RingConn's editable margin: 3 h before the recorded sleep onset and 3 h after the recorded wake. */
    val EDIT_MARGIN: Duration = Duration.ofHours(3)

    /**
     * The margin offered on top of [EDIT_MARGIN], unconditionally, because the recording may simply have
     * STOPPED rather than the wearer having woken. Sized from measurement upstream (the longest stall and
     * front-edge loss on record are 4 h 05 m and 4 h 15 m). ⚠️ Raising it is cheap and lowering it is not.
     */
    val STRANDED_EDIT_MARGIN: Duration = Duration.ofHours(6)

    /** Longest span the editor may ever offer from the archive: one plausible night (the archive holds ~30 h). */
    val DEFAULT_MAX_NIGHT_SPAN: Duration = Duration.ofHours(14)

    /**
     * Foundation's `Date.distantPast` (−62 135 769 600 s): a [RecordedWindow] edge at or before it means
     * "unknown" (legacy rows, or no asleep block), matching the store.
     */
    val DISTANT_PAST: Instant = Instant.ofEpochSecond(-62_135_769_600L)

    /** The [earliest, latest] span the edited in-bed window may occupy. */
    data class Bounds(val earliest: Instant, val latest: Instant)

    /**
     * The span of epoch records that plausibly belong to the night anchored on
     * `[recordedOnset, recordedWake]` — records within [maxNightSpan] of the anchors, both ends included.
     * Pure, so the editor and the validator compute the identical value. Null when no record qualifies.
     */
    fun dataCoverage(
        recordDates: List<Instant>,
        recordedOnset: Instant,
        recordedWake: Instant,
        maxNightSpan: Duration = DEFAULT_MAX_NIGHT_SPAN,
    ): DateInterval? {
        val lo = recordedWake.minusSaturating(maxNightSpan)
        val hi = recordedOnset.plusSaturating(maxNightSpan)
        val inWindow = recordDates.filter { it >= lo && it <= hi }
        val first = inWindow.minOrNull() ?: return null
        return DateInterval(first, inWindow.max())
    }

    /**
     * The [earliest, latest] span the edited in-bed window may occupy, in upstream's order: the ±3 h
     * floor; coverage widening, capped one night-span from the OPPOSITE FLOOR edge; the stranded margin
     * on each edge's own anchor (after the caps — the order is the fix); the truncation ceiling
     * `floorEarliest + maxNightSpan` on the late edge; and an already-saved [existingEdit], always inside.
     * Monotone in coverage; the late edge is a pure function of the recorded night (and [existingEdit]).
     */
    fun bounds(
        recordedOnset: Instant,
        recordedWake: Instant,
        dataCoverage: DateInterval? = null,
        existingEdit: DateInterval? = null,
        maxNightSpan: Duration = DEFAULT_MAX_NIGHT_SPAN,
    ): Bounds {
        val floorEarliest = recordedOnset.minusSaturating(EDIT_MARGIN)
        val floorLatest = recordedWake.plusSaturating(EDIT_MARGIN)
        var earliest = floorEarliest
        var latest = floorLatest
        if (dataCoverage != null) {
            earliest = minOf(earliest, dataCoverage.start)
            latest = maxOf(latest, dataCoverage.end)
        }
        // Each cap is anchored on the OPPOSITE FLOOR edge, never on the other (widened) edge.
        earliest = minOf(floorEarliest, maxOf(earliest, floorLatest.minusSaturating(maxNightSpan)))
        latest = maxOf(floorLatest, minOf(latest, floorEarliest.plusSaturating(maxNightSpan)))
        // The stranded margin, AFTER the caps, so a long recorded span cannot cancel it.
        earliest = minOf(earliest, recordedOnset.minusSaturating(STRANDED_EDIT_MARGIN))
        latest = maxOf(latest, recordedWake.plusSaturating(STRANDED_EDIT_MARGIN))
        // The truncation ceiling: anchored on the recorded ONSET, which a wake-side truncation cannot move.
        latest = maxOf(latest, floorEarliest.plusSaturating(maxNightSpan))
        // A night the user has ALREADY edited stays fully selectable, outside every cap.
        if (existingEdit != null) {
            earliest = minOf(earliest, existingEdit.start)
            latest = maxOf(latest, existingEdit.end)
        }
        return Bounds(earliest, latest)
    }

    /** Clamp a proposed edge into the editable bounds (a live-dragging picker). */
    fun clamp(date: Instant, bounds: Bounds): Instant = minOf(maxOf(date, bounds.earliest), bounds.latest)

    /**
     * A night row's RECORDED (ring-derived) window — the four columns the edit sheet anchors on. An edge
     * at or before [DISTANT_PAST] means unknown.
     */
    data class RecordedWindow(
        val inBedStart: Instant,
        val inBedEnd: Instant,
        val sleepOnset: Instant,
        val sleepWake: Instant,
    ) {
        internal val inBedKnown: Boolean get() = inBedStart > DISTANT_PAST && inBedEnd > inBedStart
        internal val sleepKnown: Boolean get() = sleepOnset > DISTANT_PAST && sleepWake > sleepOnset
    }

    /**
     * Outward-only widening of a MANUALLY-EDITED night's recorded anchors from a later, fuller staging of
     * the same night (min start / max end; an unknown incoming sleep window leaves the stored one). Null
     * when there is nothing to do; the caller persists only on non-null.
     *
     * ⚠️ The editable window as a whole is NOT monotone under this: the late edge's truncation ceiling
     * reads the recorded onset, so an onset that moves earlier lowers it — never below
     * `recordedWake + STRANDED_EDIT_MARGIN` or a saved edit.
     */
    fun widenRecorded(stored: RecordedWindow, incoming: RecordedWindow): RecordedWindow? {
        if (!incoming.inBedKnown) return null
        var out = if (stored.inBedKnown) {
            stored.copy(inBedStart = minOf(stored.inBedStart, incoming.inBedStart), inBedEnd = maxOf(stored.inBedEnd, incoming.inBedEnd))
        } else {
            stored.copy(inBedStart = incoming.inBedStart, inBedEnd = incoming.inBedEnd)
        }
        if (incoming.sleepKnown) {
            out = if (stored.sleepKnown) {
                out.copy(sleepOnset = minOf(stored.sleepOnset, incoming.sleepOnset), sleepWake = maxOf(stored.sleepWake, incoming.sleepWake))
            } else {
                out.copy(sleepOnset = incoming.sleepOnset, sleepWake = incoming.sleepWake)
            }
        }
        return if (out == stored) null else out
    }

    /**
     * Whether two instants fall in the same picker minute, so returning a picker to the visually unchanged
     * minute cannot manufacture an edit out of hidden seconds.
     *
     * Upstream compares at its calendar's minute granularity, which cuts at whole minutes of ABSOLUTE time
     * in every zone (measured on the pinned build: one hour apart across New York's DST fall-back is not
     * the same minute, and Monrovia's 1970 offset of −0:44:30 still cuts on the UTC minute). So the
     * answer never depends on [zone]; the parameter keeps upstream's call shape and the rule that every
     * calendar question names its zone.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isSamePickerMinute(lhs: Instant, rhs: Instant, zone: ZoneId): Boolean =
        Math.floorDiv(lhs.epochSecond, 60L) == Math.floorDiv(rhs.epochSecond, 60L)

    /** A proposed edited in-bed window. */
    data class Window(val inBedStart: Instant, val inBedEnd: Instant) {
        /** `inBedEnd - inBedStart`, never negative. */
        val duration: Duration get() = maxOf(Duration.ZERO, Duration.between(inBedStart, inBedEnd))
    }

    /**
     * The three clock times the sleep editor exposes. `[inBedStart, sleepOnset]` is awake-in-bed,
     * `[sleepOnset, sleepWake]` the editable sleep window; the wake time also ends the in-bed envelope.
     */
    data class Times(val inBedStart: Instant, val sleepOnset: Instant, val sleepWake: Instant) {
        val inBedEnd: Instant get() = sleepWake
        val inBedDuration: Duration get() = maxOf(Duration.ZERO, Duration.between(inBedStart, sleepWake))
        val asleepWindowDuration: Duration get() = maxOf(Duration.ZERO, Duration.between(sleepOnset, sleepWake))
    }

    /** Why a proposed edit is rejected. A null from `validate` means the edit is allowed. */
    sealed interface Invalid {
        data object EndNotAfterStart : Invalid
        data object OnsetBeforeBedtime : Invalid
        data object WakeNotAfterOnset : Invalid

        /** Bedtime earlier than the bounds allow. */
        data object StartBeforeEarliest : Invalid

        /** Wake later than the bounds allow. */
        data object EndAfterLatest : Invalid

        /** Shorter than the caller's minimum, in whole minutes (truncated toward zero). */
        data class TooShort(val minMinutes: Long) : Invalid

        /** Longer than one plausible night ([maxWindowDuration]), in whole minutes. */
        data class TooLong(val maxMinutes: Long) : Invalid
    }

    /**
     * The longest in-bed window `validate` accepts — the "one plausible night" rule on the PROPOSED
     * window. Never below the floor span (a long recorded night plus its ±3 h margins stays selectable)
     * and never below an already-saved edit.
     */
    fun maxWindowDuration(
        recordedOnset: Instant,
        recordedWake: Instant,
        existingEdit: DateInterval? = null,
        maxNightSpan: Duration = DEFAULT_MAX_NIGHT_SPAN,
    ): Duration {
        val floorSpan = Duration.between(recordedOnset, recordedWake).plus(EDIT_MARGIN.multipliedBy(2))
        val existingSpan = existingEdit?.duration ?: Duration.ZERO
        return maxOf(maxNightSpan, maxOf(floorSpan, existingSpan))
    }

    /**
     * Validate the three independent editor anchors against the bounds the picker offered. The minimum
     * applies to the asserted sleep window, not to the longer in-bed envelope. Null when valid.
     */
    fun validate(
        times: Times,
        recordedOnset: Instant,
        recordedWake: Instant,
        minDuration: Duration = Duration.ZERO,
        dataCoverage: DateInterval? = null,
        existingEdit: DateInterval? = null,
    ): Invalid? {
        val b = bounds(recordedOnset, recordedWake, dataCoverage, existingEdit)
        if (times.sleepOnset < times.inBedStart) return Invalid.OnsetBeforeBedtime
        if (times.sleepWake <= times.sleepOnset) return Invalid.WakeNotAfterOnset
        if (times.inBedStart < b.earliest) return Invalid.StartBeforeEarliest
        if (times.sleepWake > b.latest) return Invalid.EndAfterLatest
        // "One plausible night": the bounds' edges no longer cap each other, so the window carries the rule.
        val maxDuration = maxWindowDuration(recordedOnset, recordedWake, existingEdit)
        if (times.inBedDuration > maxDuration) return Invalid.TooLong(wholeMinutes(maxDuration))
        if (times.asleepWindowDuration < minDuration) return Invalid.TooShort(wholeMinutes(minDuration))
        return null
    }

    fun isValid(
        times: Times,
        recordedOnset: Instant,
        recordedWake: Instant,
        minDuration: Duration = Duration.ZERO,
        dataCoverage: DateInterval? = null,
        existingEdit: DateInterval? = null,
    ): Boolean = validate(times, recordedOnset, recordedWake, minDuration, dataCoverage, existingEdit) == null

    /** Validate a proposed in-bed window against the recorded onset/wake bounds. Null when valid. */
    fun validate(window: Window, recordedOnset: Instant, recordedWake: Instant, minDuration: Duration = Duration.ZERO): Invalid? {
        val b = bounds(recordedOnset, recordedWake)
        if (window.inBedEnd <= window.inBedStart) return Invalid.EndNotAfterStart
        if (window.inBedStart < b.earliest) return Invalid.StartBeforeEarliest
        if (window.inBedEnd > b.latest) return Invalid.EndAfterLatest
        if (window.duration < minDuration) return Invalid.TooShort(wholeMinutes(minDuration))
        return null
    }

    fun isValid(window: Window, recordedOnset: Instant, recordedWake: Instant, minDuration: Duration = Duration.ZERO): Boolean =
        validate(window, recordedOnset, recordedWake, minDuration) == null

    /**
     * Recompute a night's segments for an edited in-bed [window], NON-DESTRUCTIVELY: base segments are
     * clipped to the window; window time BEFORE the first / AFTER the last recorded segment is credited
     * as [fillStage] (the ring stopped recording, the user was asleep); interior gaps stay as they are.
     * With an in-bed layer in the base, each extension is also in bed.
     *
     * [coverage] says which instants the ring recorded: null is the kill switch (every emitted segment is
     * measured, exactly as before provenance existed); otherwise every fill is split against it so
     * invented time is tagged asserted — after `MeasuredCoverage.trusted`, applied once for the window.
     *
     * Apply an edit to the ring's OWN segments: re-running it on its own output is not idempotent when a
     * trim cut into the recording (a trimmed edge becomes a new recording edge and is filled), exactly as
     * upstream.
     */
    fun recompute(
        baseSegments: List<SleepSegment>,
        window: Window,
        fillStage: SleepStage = SleepStage.ASLEEP_CORE,
        coverage: MeasuredCoverage? = null,
    ): List<SleepSegment> {
        val start = window.inBedStart
        val end = window.inBedEnd
        if (end <= start) return emptyList()
        // The retention guard, once for the whole night (a per-span test would answer differently per fill).
        val trusted = coverage?.trusted(DateInterval(start, end))

        val sortedBase = baseSegments.sortedBy { it.start } // stable, as Swift's sort
        val clipped = sortedBase.mapNotNull { seg ->
            val s = maxOf(seg.start, start)
            val e = minOf(seg.end, end)
            if (e > s) SleepSegment(s, e, seg.stage, seg.provenance) else null
        }

        // With no recording at all, the whole window is the user's. Otherwise extension fill is keyed to
        // the original recording envelope, never to the clipped segments (an interior gap stays empty).
        val recordedStart = sortedBase.minOfOrNull { it.start }
        val recordedEnd = sortedBase.maxOfOrNull { it.end }
        if (recordedStart == null || recordedEnd == null) return fill(start, end, fillStage, trusted)

        val hasInBedLayer = sortedBase.any { it.stage == SleepStage.IN_BED }
        val out = ArrayList<SleepSegment>()
        val leadingEnd = minOf(end, recordedStart)
        if (start < leadingEnd) {
            if (hasInBedLayer) out += fill(start, leadingEnd, SleepStage.IN_BED, trusted)
            out += fill(start, leadingEnd, fillStage, trusted)
        }
        out += clipped
        val trailingStart = maxOf(start, recordedEnd)
        if (trailingStart < end) {
            if (hasInBedLayer) out += fill(trailingStart, end, SleepStage.IN_BED, trusted)
            out += fill(trailingStart, end, fillStage, trusted)
        }
        return out
    }

    /**
     * Recompute using independent bedtime, sleep-onset and wake anchors. The result has one in-bed
     * envelope (split by coverage); bedtime-to-onset is AWAKE; the ring's stages inside the recorded
     * sleep window are kept verbatim (with their provenance); only an extension of the sleep window is
     * [fillStage]. [coverage] as in the window form, guarded once against the whole in-bed window.
     *
     * Kept from upstream on purpose: recorded stages before the asserted onset are discarded and the
     * awake paint is unconditional — the paint is TAGGED (asserted-over-measured where the ring recorded)
     * rather than vetoed, because a veto would make the onset picker a no-op against over-detected sleep.
     */
    fun recompute(
        baseSegments: List<SleepSegment>,
        times: Times,
        fillStage: SleepStage = SleepStage.ASLEEP_CORE,
        coverage: MeasuredCoverage? = null,
    ): List<SleepSegment> {
        if (!(times.sleepWake > times.sleepOnset && times.sleepOnset >= times.inBedStart)) return emptyList()
        val trusted = coverage?.trusted(DateInterval(times.inBedStart, times.inBedEnd))

        val stageBase = baseSegments.filter { it.stage != SleepStage.IN_BED }.sortedBy { it.start }
        val recordedSleep = SleepStaging.sleepWindow(stageBase)

        val stages = ArrayList<SleepSegment>()
        if (recordedSleep != null) {
            val preservedStart = maxOf(times.sleepOnset, recordedSleep.onset)
            val preservedEnd = minOf(times.sleepWake, recordedSleep.wake)
            // LEADING fill — just as capable of inventing sleep over a front-edge hole as the trailing one.
            val leadingEnd = minOf(times.sleepWake, recordedSleep.onset)
            if (times.sleepOnset < leadingEnd) stages += fill(times.sleepOnset, leadingEnd, fillStage, trusted)
            if (preservedEnd > preservedStart) {
                stages += stageBase.mapNotNull { seg ->
                    val s = maxOf(seg.start, preservedStart)
                    val e = minOf(seg.end, preservedEnd)
                    if (e > s) SleepSegment(s, e, seg.stage, seg.provenance) else null
                }
            }
            // TRAILING fill.
            val trailingStart = maxOf(times.sleepOnset, recordedSleep.wake)
            if (trailingStart < times.sleepWake) stages += fill(trailingStart, times.sleepWake, fillStage, trusted)
        } else {
            // No staged base at all: the whole sleep window is the user's assertion.
            stages += fill(times.sleepOnset, times.sleepWake, fillStage, trusted)
        }

        // The in-bed envelope is entirely a user claim; split by coverage it gives efficiency over
        // covered ground without re-deriving anything.
        val result = ArrayList(fill(times.inBedStart, times.inBedEnd, SleepStage.IN_BED, trusted))
        if (times.inBedStart < times.sleepOnset) result += fill(times.inBedStart, times.sleepOnset, SleepStage.AWAKE, trusted)
        result += stages
        return result
    }

    /** The one place ground becomes a label. UNKNOWN is coverage-unknown, NOT asserted. */
    fun provenance(ground: MeasuredCoverage.Ground): SleepProvenance = when (ground) {
        MeasuredCoverage.Ground.MEASURED -> SleepProvenance.ASSERTED_OVER_MEASURED
        MeasuredCoverage.Ground.UNMEASURED -> SleepProvenance.ASSERTED
        MeasuredCoverage.Ground.UNKNOWN -> SleepProvenance.ASSERTED_COVERAGE_UNKNOWN
    }

    /**
     * Emit a USER-ASSERTED span `[lo, hi)` as segments split at the coverage boundaries — the single
     * choke point every invented minute passes through. Null coverage gives one measured segment, bit
     * for bit what the code emitted before provenance existed. The caller has already applied
     * `MeasuredCoverage.trusted`.
     */
    private fun fill(lo: Instant, hi: Instant, stage: SleepStage, coverage: MeasuredCoverage?): List<SleepSegment> {
        if (hi <= lo) return emptyList()
        if (coverage == null) return listOf(SleepSegment(lo, hi, stage))
        return coverage.partition(DateInterval(lo, hi)).map { piece ->
            SleepSegment(piece.range.start, piece.range.end, stage, provenance(piece.ground))
        }
    }

    /** Whole minutes, truncated toward zero, as Swift's `Int(seconds / 60)`. */
    private fun wholeMinutes(d: Duration): Long = d.wholeSecondsTowardZero() / 60

    /** `this + d`, or the end of `Instant`'s range it would pass. */
    private fun Instant.plusSaturating(d: Duration): Instant = try {
        plus(d)
    } catch (e: DateTimeException) {
        if (d.isNegative) Instant.MIN else Instant.MAX
    } catch (e: ArithmeticException) {
        if (d.isNegative) Instant.MIN else Instant.MAX
    }

    /** `this - d`, or the end of `Instant`'s range it would pass. */
    private fun Instant.minusSaturating(d: Duration): Instant = try {
        minus(d)
    } catch (e: DateTimeException) {
        if (d.isNegative) Instant.MAX else Instant.MIN
    } catch (e: ArithmeticException) {
        if (d.isNegative) Instant.MAX else Instant.MIN
    }
}
