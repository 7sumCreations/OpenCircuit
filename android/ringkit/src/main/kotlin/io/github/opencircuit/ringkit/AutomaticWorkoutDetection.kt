package io.github.opencircuit.ringkit

// Reconstruct potential workouts from the ring's store-and-forward sport stream (#179). Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/Analytics/AutomaticWorkoutDetection.swift (@ b1c2fdd), whole.
//
// RingConn's automatic recognition runs on the ring. Captures prove that `05 23 01/00 00` toggles it and
// channel 0x02 drains `0x4d` pages containing 10-second HR/step records. The official 4.2.1 UI states
// that a continuous workout must last at least 10 minutes. No stable activity-type field exists in the
// captured records; walk/run can only be suggested from cadence, so the UI requires explicit type
// confirmation and leaves stationary activities unlabeled.
//
// Shape notes. Ring cursors (Swift `UInt32`) are `Long` in 0..0xFFFFFFFF, required at construction; where
// upstream wraps (`&+`) the port masks to 32 bits. A retention or age is `Double` seconds where upstream
// takes a `TimeInterval`, and NaN keeps upstream's meaning except where upstream traps or would erase the
// saved span ledger (see [CursorSpan.migratedFromLegacyCursor] and [prunedToRelevant]). Elapsed times are
// measured exactly between instants (the carried exact-elapsed-time rule). Step sums are `Long` (Swift's
// 64-bit `Int`). `rebuild` takes a required `now` (upstream defaulted to the device clock). Where upstream
// de-duplicates through a Dictionary, the port uses an insertion-ordered map and sorts its values on their
// unique keys, so no iteration order reaches a result.

import java.time.Instant

/** Decoder for a historical sport page (`0x4d`). */
object HistoricalSportFrame {
    const val OPCODE: Int = 0x4d
    const val RECORD_LENGTH: Int = 11
    const val INTERVAL_SECONDS: Double = 10.0

    /** [INTERVAL_SECONDS] as whole seconds, for instant and cursor arithmetic. */
    internal const val INTERVAL_WHOLE_SECONDS: Long = 10L

    /**
     * One 10-second record. Compares by content (its auxiliary bytes included); the auxiliary bytes are
     * copied in and read out as a copy, as Swift's `[UInt8]` value is.
     *
     * @param cursor end of the 10-second interval, in the ring's cursor space (seconds since
     *   `Command.SYNC_EPOCH`); an unsigned 32-bit value, required in 0..0xFFFFFFFF.
     */
    class Sample(val cursor: Long, val heartRate: Int?, val steps: Int, auxiliary: ByteArray = ByteArray(0)) {
        init {
            require(cursor in 0L..MAX_CURSOR) { "cursor must fit an unsigned 32-bit value: $cursor" }
        }

        private val aux: ByteArray = auxiliary.copyOf()

        /** Five still-undecoded quality / perfusion bytes retained for future classifier RE (a copy). */
        val auxiliary: ByteArray get() = aux.copyOf()

        val endDate: Instant get() = Instant.ofEpochSecond(Command.SYNC_EPOCH + cursor)

        override fun equals(other: Any?): Boolean =
            other is Sample && cursor == other.cursor && heartRate == other.heartRate && steps == other.steps && aux.contentEquals(other.aux)

        override fun hashCode(): Int = ((cursor.hashCode() * 31 + (heartRate ?: -1)) * 31 + steps) * 31 + aux.contentHashCode()

        override fun toString(): String =
            "Sample(cursor=$cursor, heartRate=$heartRate, steps=$steps, auxiliary=${aux.map { it.toInt() and 0xff }})"
    }

    /**
     * Decode all complete 11-byte records from one XOR-valid `0x4d` page; null for a page that is too short,
     * of another opcode, fails its XOR trailer or does not hold whole records. A valid page with no records
     * decodes to an empty list.
     *
     * Byte 2 is a remaining-record/page countdown, not the number of records in this frame. The payload
     * therefore runs from byte 3 through the byte before the XOR trailer.
     */
    fun decode(frame: ByteArray): List<Sample>? {
        if (frame.size < 4 || frame.u8(0) != OPCODE || !Frame.isValid(frame)) return null
        val payloadStart = 3
        val payloadLength = frame.size - 1 - payloadStart
        if (payloadLength % RECORD_LENGTH != 0) return null
        return (0 until payloadLength / RECORD_LENGTH).map { k ->
            val o = payloadStart + k * RECORD_LENGTH
            val cursor = (frame.u8(o).toLong() shl 24) or (frame.u8(o + 1).toLong() shl 16) or
                (frame.u8(o + 2).toLong() shl 8) or frame.u8(o + 3).toLong()
            val bpm = frame.u8(o + 4)
            Sample(
                cursor = cursor,
                heartRate = if (bpm in LiveHR.VALID_BPM) bpm else null,
                steps = frame.u8(o + 5),
                auxiliary = frame.copyOfRange(o + 6, o + RECORD_LENGTH),
            )
        }
    }
}

/** The largest ring cursor: Swift's `UInt32.max`. */
private const val MAX_CURSOR: Long = 0xFFFF_FFFFL

/**
 * Inclusive range of ring cursors covered by a bout. This — not the first-sample cursor — is the durable
 * identity of a detected workout: the first cursor MOVES whenever the two-day retention prune eats the
 * bout's head or pages arrive out of order across drains, and keying notified/dismissed state to it let one
 * stuck 19.7 h bout re-notify 10× and survive 4 dismissals (device-proven 2026-08-17). Overlap, not
 * equality, is the "same bout" test. Both ends are unsigned 32-bit values, required in 0..0xFFFFFFFF; as
 * upstream, [end] may precede [start].
 */
data class CursorSpan(val start: Long, val end: Long) {
    init {
        require(start in 0L..MAX_CURSOR && end in 0L..MAX_CURSOR) { "cursor span ends must fit an unsigned 32-bit value: $start .. $end" }
    }

    /** Degenerate single-cursor span. */
    constructor(point: Long) : this(point, point)

    /**
     * The ring-cursor span of a wall-clock window — e.g. a MANUAL workout, so the ring's buffered sport
     * records for it are never offered back as an automatically detected workout (tester report
     * 2026-09-27). Each end is the whole seconds since the sync epoch, clamped at the epoch and at
     * 0xFFFFFFFF (upstream's `UInt32(clamping:)`; no `Instant` reaches the `Int64` conversion upstream
     * traps on). An end before the start collapses to a point.
     */
    constructor(window: DateInterval) : this(cursorOf(window.start), maxOf(cursorOf(window.start), cursorOf(window.end)))

    fun overlaps(other: CursorSpan): Boolean = start <= other.end && other.start <= end

    companion object {
        /**
         * Migrate a v1 head-cursor bookkeeping entry. A v1 cursor is the bout head AS OF the moment it was
         * recorded, and the retention prune only ever moves a head FORWARD — so the bout the cursor belonged
         * to lies within [retention] AFTER it, never before. Widening forward by the retention window
         * restores the overlap; the over-suppression risk is bounded to bouts starting within 48 h after an
         * already-reviewed head, and stale spans are pruned away once they can no longer overlap anything
         * ([prunedToRelevant]).
         *
         * The end wraps past 0xFFFFFFFF, masked to 32 bits as upstream's `&+` wraps. Upstream converts the
         * retention with `UInt32(_:)`, which traps on NaN, an infinity or a value of 2³² or more; here it
         * converts as `UInt32(clamping:)` after truncation — NaN widens nothing, anything past the top
         * widens by 0xFFFFFFFF — and every other value gives upstream's answer.
         */
        fun migratedFromLegacyCursor(cursor: Long, retention: Double = AutomaticWorkoutInbox.RETENTION): CursorSpan {
            require(cursor in 0L..MAX_CURSOR) { "cursor must fit an unsigned 32-bit value: $cursor" }
            val widen = clampedUInt32(swiftMax(retention, 0.0))
            return CursorSpan(cursor, (cursor + widen) and MAX_CURSOR)
        }

        /** A non-negative or NaN `Double` truncated into 0..0xFFFFFFFF: NaN → 0, past the top → 0xFFFFFFFF. */
        private fun clampedUInt32(x: Double): Long = when {
            x.isNaN() -> 0L
            x >= MAX_CURSOR.toDouble() -> MAX_CURSOR
            x <= 0.0 -> 0L
            else -> x.toLong()
        }

        /** Whole seconds since the sync epoch, floored, clamped to 0..0xFFFFFFFF. */
        private fun cursorOf(t: Instant): Long = (t.epochSecond - Command.SYNC_EPOCH).coerceIn(0L, MAX_CURSOR)
    }
}

/**
 * Drop spans that can no longer matter: [AutomaticWorkoutInbox.rebuild] only retains samples whose end lies
 * within [retention] of [now], so a span ending before that horizon can never overlap a future candidate.
 * Bounds the otherwise append-only notified/resolved bookkeeping and caps how long a widened legacy span can
 * over-suppress. A span ending exactly at the horizon is kept. A NaN retention keeps every span: upstream
 * would compare against a NaN horizon and drop them all, erasing the saved ledger; the port fails closed,
 * as upstream's own inbox keeps every sample on a NaN retention.
 */
fun List<CursorSpan>.prunedToRelevant(now: Instant, retention: Double = AutomaticWorkoutInbox.RETENTION): List<CursorSpan> {
    val back = swiftMax(retention, 0.0)
    val horizon = addingSeconds(now, -back) ?: return toList()
    return filter { !Instant.ofEpochSecond(Command.SYNC_EPOCH + it.end).isBefore(horizon) }
}

/** Reconstructs retroactive potential-workout periods from historical sport samples. */
object AutomaticWorkoutDetector {

    /** A cadence-based suggestion only; [rawValue] is upstream's case name. */
    enum class SuggestedKind(val rawValue: String) {
        WALKING("walking"),
        RUNNING("running"),
        ;

        companion object {
            /** Upstream's `init?(rawValue:)`: the kind stored under [rawValue], or null. */
            fun fromRawValue(rawValue: String): SuggestedKind? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /** One detected bout. Its sample list is copied in and read-only out. */
    class Candidate(val start: Instant, val end: Instant, samples: List<HistoricalSportFrame.Sample>, val suggestedKind: SuggestedKind?) {
        val samples: List<HistoricalSportFrame.Sample> = samples.toList()

        /**
         * Stable across retransmitted and later pages because it is the first ring cursor in the bout, not an
         * app-generated id or the still-growing end cursor. 0 for a candidate with no samples.
         */
        val id: Long get() = samples.firstOrNull()?.cursor ?: 0L

        /**
         * The bout's durable identity for notified/dismissed bookkeeping. [id] (the first cursor) is only
         * stable for list diffing within one rebuild; across rebuilds the prune and page arrival move it.
         */
        val cursorSpan: CursorSpan get() = CursorSpan(samples.firstOrNull()?.cursor ?: 0L, samples.lastOrNull()?.cursor ?: 0L)

        val duration: Double get() = secondsBetween(start, end)

        /** Steps summed over the bout, as a `Long` (Swift's 64-bit `Int`). */
        val steps: Long get() = samples.sumOf { it.steps.toLong() }

        val averageHeartRate: Double?
            get() {
                val values = samples.mapNotNull { it.heartRate }
                if (values.isEmpty()) return null
                return values.sumOf { it.toLong() }.toDouble() / values.size.toDouble()
            }

        val maximumHeartRate: Int? get() = samples.mapNotNull { it.heartRate }.maxOrNull()

        override fun equals(other: Any?): Boolean =
            other is Candidate && start == other.start && end == other.end && samples == other.samples && suggestedKind == other.suggestedKind

        override fun hashCode(): Int = listOf(start, end, samples, suggestedKind).hashCode()

        override fun toString(): String = "Candidate(start=$start, end=$end, samples=${samples.size}, suggestedKind=$suggestedKind)"
    }

    /** RingConn 4.2.1's documented minimum continuous recognition period (seconds). */
    const val MINIMUM_DURATION: Double = 10.0 * 60.0

    /**
     * Group ordered 10-second samples into workout candidates. The period begins one sample interval before
     * the first record's end timestamp, preserving the automatic-detection lead-in instead of reporting the
     * later sync/confirmation time. A retransmitted page can repeat cursors: a duplicate with a valid HR
     * reading is preferred, otherwise the later copy wins. The coverage floor is Swift's
     * `min(max(minimumCoverage, 0), 1)`, which lets a NaN through (then nothing qualifies).
     */
    fun detect(
        samples: List<HistoricalSportFrame.Sample>,
        minimumDuration: Double = MINIMUM_DURATION,
        maximumGap: Double = 30.0,
        minimumCoverage: Double = 0.7,
    ): List<Candidate> {
        if (samples.isEmpty() || !(minimumDuration > 0)) return emptyList()
        val ordered = preferValidHeartRate(samples).sortedBy { it.cursor }
        val first = ordered.firstOrNull() ?: return emptyList()

        val groups = mutableListOf(mutableListOf(first))
        for (sample in ordered.drop(1)) {
            val previous = groups.last().last()
            val gap = secondsBetween(previous.endDate, sample.endDate)
            if (gap > 0 && gap <= maximumGap) groups.last().add(sample) else groups.add(mutableListOf(sample))
        }

        val coverageFloor = swiftMin(swiftMax(minimumCoverage, 0.0), 1.0)
        return groups.mapNotNull { group ->
            val start = group.first().endDate.minusSeconds(HistoricalSportFrame.INTERVAL_WHOLE_SECONDS)
            val end = group.last().endDate
            val duration = secondsBetween(start, end)
            val observed = group.size.toDouble() * HistoricalSportFrame.INTERVAL_SECONDS
            if (!(duration >= minimumDuration && observed / duration >= coverageFloor)) return@mapNotNull null

            val stepRate = group.sumOf { it.steps.toLong() }.toDouble() / duration * 60.0
            // Type is only a suggestion: 0x4d exposes cadence but not the ring classifier's label.
            // Stationary/cycling/rowing/yoga/basketball candidates intentionally remain unlabeled.
            val kind = if (stepRate >= 130) SuggestedKind.RUNNING else if (stepRate >= 45) SuggestedKind.WALKING else null
            Candidate(start = start, end = end, samples = group, suggestedKind = kind)
        }
    }

    /**
     * One sample per cursor: a later duplicate replaces the kept one unless the kept one has a valid HR and
     * the later one has none. Insertion-ordered; callers sort on the unique cursors.
     */
    internal fun preferValidHeartRate(samples: Iterable<HistoricalSportFrame.Sample>): Collection<HistoricalSportFrame.Sample> {
        val unique = LinkedHashMap<Long, HistoricalSportFrame.Sample>()
        for (sample in samples) {
            if (unique[sample.cursor]?.heartRate == null || sample.heartRate != null) unique[sample.cursor] = sample
        }
        return unique.values
    }
}

/**
 * Pure state transition for the app's two-day detected-workout inbox. Keeping this out of the ring session
 * makes retransmission, retention and reviewed-item behaviour replay-testable without Bluetooth or a
 * preferences store; the app layer only persists the returned normalized samples.
 */
object AutomaticWorkoutInbox {

    /** The inbox after a rebuild. Its lists are copied in and read-only out. */
    class State(samples: List<HistoricalSportFrame.Sample>, candidates: List<AutomaticWorkoutDetector.Candidate>) {
        val samples: List<HistoricalSportFrame.Sample> = samples.toList()
        val candidates: List<AutomaticWorkoutDetector.Candidate> = candidates.toList()

        override fun equals(other: Any?): Boolean = other is State && samples == other.samples && candidates == other.candidates

        override fun hashCode(): Int = samples.hashCode() * 31 + candidates.hashCode()

        override fun toString(): String = "State(samples=${samples.size}, candidates=$candidates)"
    }

    /** Two days, in seconds. */
    const val RETENTION: Double = 2.0 * 24 * 60 * 60

    /**
     * Merge page retransmissions, prune expired records, reconstruct bouts, and suppress any bout that
     * overlaps a span the user already saved/dismissed. Prefer a duplicate with valid HR.
     *
     * Suppression is span-OVERLAP, not first-cursor equality: the retention prune below runs with [now] on
     * every rebuild, so a long bout's first cursor advances as the cutoff crosses it, and an equality key
     * resurrects the dismissed bout with a fresh head on every merge. A sample ending exactly at the cutoff
     * is kept; a NaN retention keeps every sample (Foundation's `>=` against a NaN date is true).
     */
    fun rebuild(
        existing: List<HistoricalSportFrame.Sample>,
        incoming: List<HistoricalSportFrame.Sample>,
        resolvedSpans: List<CursorSpan>,
        now: Instant,
        retention: Double = RETENTION,
    ): State {
        val unique = AutomaticWorkoutDetector.preferValidHeartRate(existing + incoming)
        val cutoff = addingSeconds(now, -swiftMax(retention, 0.0))
        val retained = unique
            .filter { cutoff == null || !it.endDate.isBefore(cutoff) }
            .sortedBy { it.cursor }
        val candidates = AutomaticWorkoutDetector.detect(samples = retained)
            .filter { candidate ->
                val span = candidate.cursorSpan
                resolvedSpans.none { it.overlaps(span) }
            }
        return State(samples = retained, candidates = candidates)
    }
}

/**
 * Pure gate for the one-time "Possible workout detected" push. Kept out of the ring session so the two
 * field failure modes — a bout re-announced because its head cursor moved, and a days-old bout announced
 * as if it just happened — stay replay-testable without the notification framework.
 */
object AutomaticWorkoutAnnouncement {
    /**
     * A push is only useful in the day after the bout ENDS; past this age (seconds) the two-day review inbox
     * is the surface, not a notification. 24 h (not 12) so a once-a-morning syncer still gets the push for
     * yesterday morning's workout — the span-overlap dedup, not this age cut, is what prevents repeats.
     */
    const val MAX_AGE: Double = 24.0 * 3600

    /** True for a bout that ended no more than [maxAge] seconds before [now] and overlaps no announced span. */
    fun shouldAnnounce(
        candidate: AutomaticWorkoutDetector.Candidate,
        announcedSpans: List<CursorSpan>,
        now: Instant,
        maxAge: Double = MAX_AGE,
    ): Boolean {
        if (!(secondsBetween(candidate.end, now) <= maxAge)) return false
        val span = candidate.cursorSpan
        return announcedSpans.none { it.overlaps(span) }
    }
}

/**
 * Converts a user-confirmed candidate into the same analytics payload used by a live workout. Writing to
 * the health store remains an app-layer concern; this preparation is pure and preserves only real ring HR.
 */
object AutomaticWorkoutConfirmation {

    /** The summary and the HR samples behind it (read-only, in the candidate's record order). */
    class Prepared(val summary: WorkoutSummary, heartRateSamples: List<HRSample>) {
        val heartRateSamples: List<HRSample> = heartRateSamples.toList()

        override fun equals(other: Any?): Boolean = other is Prepared && summary == other.summary && heartRateSamples == other.heartRateSamples

        override fun hashCode(): Int = summary.hashCode() * 31 + heartRateSamples.hashCode()

        override fun toString(): String = "Prepared(summary=$summary, heartRateSamples=$heartRateSamples)"
    }

    fun prepare(candidate: AutomaticWorkoutDetector.Candidate, sport: WorkoutSportType, profile: UserProfile): Prepared {
        val aggregator = WorkoutSessionAggregator(startDate = candidate.start, userAge = profile.age)
        for (sample in candidate.samples) {
            val bpm = sample.heartRate ?: continue
            val end = sample.endDate
            aggregator.add(HRSample(bpm = bpm, start = end.minusSeconds(HistoricalSportFrame.INTERVAL_WHOLE_SECONDS), end = end))
        }
        val steps = candidate.steps
        val summary = aggregator.finalize(
            sport = sport,
            endDate = candidate.end,
            distanceMeters = null,
            hasRoute = false,
            profile = profile,
            steps = if (steps > 0) steps else null,
        )
        return Prepared(summary = summary, heartRateSamples = aggregator.collectedSamples)
    }
}
