package io.github.opencircuit.ringkit

// What one history sync measures about the ring, for the sync log and the Ring data card: whether a
// channel's history joins the last sync's (continuity), what a complete sync says about how much the
// ring keeps and what it does when full (the capacity verdict), when the ring is overdue a sync, and
// the small figures a log line is made of. Kotlin-only (PORTING.md D-273): upstream keeps no sync log
// and no capacity verdict; RingConn states a capacity (Gen 2 / Gen 2 Air about 7 days, Gen 3 up to 10)
// but not whether a full ring overwrites its oldest records or stops recording.
//
// Pure: every time is passed in; nothing reads the machine's clock, zone or locale.

import java.time.Duration
import java.time.Instant

/** Continuity, the capacity verdict, the overdue warning and the log's figures. */
object SyncMeasurement {

    /** The ring records one epoch every this many seconds (PROTOCOL.md §5.3). */
    const val EPOCH_SECONDS: Long = 150

    /** Epochs in a day at that rate. */
    const val RECORDS_PER_DAY: Int = 576

    /** How far a first record may sit from where it is due and still join (epoch drift measured up to 22 s over 320 epochs). */
    const val CONTINUITY_TOLERANCE_SECONDS: Long = 60

    /** A newest record at most this old means the ring is still recording. */
    val FRESH: Duration = Duration.ofMinutes(30)

    /** A ring is judged full only over a dense span at least this long. */
    val MIN_FULL_SPAN: Duration = Duration.ofDays(4)

    /** A ring that stopped recording when full shows at least this since its newest record (a charger's hour does not). */
    val STOPPED_FOR: Duration = Duration.ofDays(1)

    // ---- Continuity ----

    /** How a channel's first record of this sync sits against its last record of the syncs before. */
    enum class ContinuityKind {
        /** No earlier sync delivered a record on this channel. */
        FIRST_SYNC,

        /** This sync delivered no record on this channel. */
        NO_RECORDS,

        /** The first record is one already delivered: the ring offered it again (its pointer moved only on our acknowledgement). */
        OVERLAP,

        /** The first record is the one due next. */
        CONTIGUOUS,

        /** Records are missing between the two: data the ring no longer had, or a ring that was off. */
        GAP,
    }

    /** [kind], with [gapSeconds] = first − previous last − 150 s when both are known. */
    data class Continuity(val kind: ContinuityKind, val gapSeconds: Long?)

    /**
     * The previous syncs' last counter [previousLastCounter] (P) against this sync's first counter
     * [firstCounter] (F), both in sync-epoch seconds: gap = F − P − 150 s; within ±60 s CONTIGUOUS,
     * earlier OVERLAP, later GAP.
     */
    fun continuity(previousLastCounter: Long?, firstCounter: Long?): Continuity {
        if (firstCounter == null) return Continuity(ContinuityKind.NO_RECORDS, null)
        if (previousLastCounter == null) return Continuity(ContinuityKind.FIRST_SYNC, null)
        val gap = firstCounter - previousLastCounter - EPOCH_SECONDS
        val kind = when {
            gap < -CONTINUITY_TOLERANCE_SECONDS -> ContinuityKind.OVERLAP
            gap > CONTINUITY_TOLERANCE_SECONDS -> ContinuityKind.GAP
            else -> ContinuityKind.CONTIGUOUS
        }
        return Continuity(kind, gap)
    }

    // ---- Capacity ----

    /** What a sync says about the ring's storage. */
    enum class CapacityKind {
        /** The ring drops its oldest records when full: capacity ≈ the span. */
        OVERWRITES_OLDEST,

        /** The ring stops recording when full: capacity ≈ the span. */
        STOPS_WHEN_FULL,

        /** The ring keeps at least the span; how much more is not known yet. */
        LOWER_BOUND,

        /** This sync cannot say ([CapacityVerdict.reason]). */
        INCONCLUSIVE,
    }

    /** Why a verdict is [CapacityKind.INCONCLUSIVE]. */
    enum class InconclusiveReason {
        /** The sync delivered no record. */
        NO_RECORDS,

        /** A channel was not drained to its end. */
        NOT_COMPLETE,

        /** Fewer than half the records a worn ring makes in the span: the ring was off or not worn. */
        SPARSE,

        /** Records are missing before this sync's, but nothing says the ring ran out of room. */
        GAP_UNEXPLAINED,
    }

    /** The records one sync delivered: the [oldest], the [newest] and how many distinct [records]. */
    data class SyncSpan(val oldest: Instant, val newest: Instant, val records: Int)

    /** The verdict: its [kind], the [span] it is about (null when there were no records) and, when inconclusive, why. */
    data class CapacityVerdict(val kind: CapacityKind, val span: Duration?, val reason: InconclusiveReason?)

    /**
     * The capacity verdict of one sync finished at [now]. [span]: what it delivered (null: nothing);
     * [complete]: every channel drained to its end; [gapBefore]: a channel's first record came
     * later than due ([ContinuityKind.GAP]); [firstSync]: no earlier sync delivered a record.
     *
     * In order: no records, an incomplete sync, or fewer than half the expected records
     * (one per 150 s over the span) → INCONCLUSIVE. A gap before a fresh (≤ 30 min old) dense span of
     * at least 4 days → OVERWRITES_OLDEST; any other gap → INCONCLUSIVE. A fresh span on a first sync
     * reaching the model's capacity less a day → OVERWRITES_OLDEST (full and still recording). A span
     * of at least 4 days whose newest record is at least a day old → STOPS_WHEN_FULL. Anything else
     * → LOWER_BOUND: the ring keeps at least the span.
     */
    fun capacity(
        span: SyncSpan?,
        complete: Boolean,
        gapBefore: Boolean,
        firstSync: Boolean,
        now: Instant,
        generation: RingGeneration,
    ): CapacityVerdict {
        if (span == null || span.records <= 0) return CapacityVerdict(CapacityKind.INCONCLUSIVE, null, InconclusiveReason.NO_RECORDS)
        val length = Duration.between(span.oldest, span.newest).coerceAtLeast(Duration.ZERO)
        fun inconclusive(reason: InconclusiveReason) = CapacityVerdict(CapacityKind.INCONCLUSIVE, length, reason)
        if (!complete) return inconclusive(InconclusiveReason.NOT_COMPLETE)
        val expected = length.seconds / EPOCH_SECONDS + 1
        // Density below half of one record per epoch (576 a day): exactly half is enough.
        if (span.records.toLong() * 2 < expected) return inconclusive(InconclusiveReason.SPARSE)
        val fresh = !span.newest.isBefore(now.minus(FRESH))
        val long = length >= MIN_FULL_SPAN
        if (gapBefore) {
            return if (fresh && long) CapacityVerdict(CapacityKind.OVERWRITES_OLDEST, length, null) else inconclusive(InconclusiveReason.GAP_UNEXPLAINED)
        }
        if (fresh && firstSync && length >= Duration.ofDays(nominalCapacityDays(generation) - 1L)) {
            return CapacityVerdict(CapacityKind.OVERWRITES_OLDEST, length, null)
        }
        if (!fresh && long && !span.newest.isAfter(now.minus(STOPPED_FOR))) {
            return CapacityVerdict(CapacityKind.STOPS_WHEN_FULL, length, null)
        }
        return CapacityVerdict(CapacityKind.LOWER_BOUND, length, null)
    }

    /** The days of history RingConn states the model keeps: Gen 3 about 10, every other model (Gen 2's) about 7. */
    fun nominalCapacityDays(generation: RingGeneration): Int = if (generation == RingGeneration.GEN3) GEN3_DAYS else GEN2_DAYS

    // ---- Overdue ----

    /** How urgent a sync is. */
    enum class OverdueLevel { AMBER, RED }

    /** A warning at [level]; [keepsDays] is the model's stated capacity for its words. */
    data class Overdue(val level: OverdueLevel, val keepsDays: Int)

    /**
     * Whether the ring is overdue a sync at [now], measured from [oldestOnRing] — the oldest record
     * the ring may still hold, i.e. how far its least-drained channel is drained (null: nothing
     * known, no warning). Gen 3 amber at 7 days, red at 9; every other model (Gen 2 / Gen 2 Air, and
     * any unknown prefix) amber at 4, red at 5. Each threshold counts from its exact instant.
     */
    fun overdue(generation: RingGeneration, oldestOnRing: Instant?, now: Instant): Overdue? {
        if (oldestOnRing == null) return null
        val age = Duration.between(oldestOnRing, now)
        val gen3 = generation == RingGeneration.GEN3
        val amber = Duration.ofDays(if (gen3) GEN3_AMBER_DAYS else GEN2_AMBER_DAYS)
        val red = Duration.ofDays(if (gen3) GEN3_RED_DAYS else GEN2_RED_DAYS)
        val level = when {
            age >= red -> OverdueLevel.RED
            age >= amber -> OverdueLevel.AMBER
            else -> return null
        }
        return Overdue(level, nominalCapacityDays(generation))
    }

    // ---- Figures ----

    /** The nearest-rank [p]th percentile (1–100) of [values]; null when there are none. */
    fun percentile(values: List<Long>, p: Int): Long? {
        require(p in 1..100) { "a percentile is 1 to 100: $p" }
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        // Nearest rank: the smallest value with at least p % of the values at or below it.
        val rank = (p * sorted.size + 99) / 100
        return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
    }

    /**
     * The charge markers of a `0x50` end-of-history report: its event-log entries of type `0x15`
     * with value `0x31` (the ring went onto its charger, PROTOCOL.md §5.5.1, 🟡), as instants, in
     * time order, each once. A frame that is not a whole event log has none.
     */
    fun chargeMarkers(endOfHistory: ByteArray): List<Instant> =
        RingEventLog.decode(endOfHistory).orEmpty()
            .filter { it.type == CHARGE_TYPE && it.value == CHARGE_VALUE }
            .map { it.date }
            .distinct()
            .sorted()

    private const val GEN2_DAYS = 7
    private const val GEN3_DAYS = 10
    private const val GEN2_AMBER_DAYS = 4L
    private const val GEN2_RED_DAYS = 5L
    private const val GEN3_AMBER_DAYS = 7L
    private const val GEN3_RED_DAYS = 9L
    private const val CHARGE_TYPE = 0x15
    private const val CHARGE_VALUE = 0x31
}
