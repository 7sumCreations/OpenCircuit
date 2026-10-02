package io.github.opencircuit.ringkit

// WorkoutSessionRecovery — what a fresh process may honestly claim about a workout that was running when the
// previous process died. Port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/WorkoutSessionRecovery.swift
// (@ b1c2fdd), without the snapshot's stored form (`encoded()` / `decoded()`), which belongs to the storage
// layer.
//
// WHY THIS EXISTS (tester report 2026-08-29, Gen 2 Air FR04.009, build 49): the running workout lived ONLY
// in the workout sheet's UI state, so any sheet teardown destroyed it, and the launch path's only reaction
// to a crash-orphaned session was to end its Live Activity — deleting the last evidence that a workout had
// ever been underway. A ~55-minute evening walk therefore vanished from the app's own UI. The app now
// persists a small snapshot while a session runs; this file decides what a later launch is ALLOWED to do
// with it.
//
// The rule the whole file exists to enforce: **the recovered workout ends at the last moment the app
// actually observed the session alive — never at "now".** A process that died at 19:20 has no evidence the
// user kept walking until they next opened the app, and stretching the span to `now` would fabricate
// duration, calories and (via the health store) an activity ring credit out of nothing. This is the same
// discipline as the sample-plausibility gate in front of the sync cursor: an implausible value is refused
// BEFORE it can reach a durable store.
//
// Deliberately NO staleness threshold. An "offer to save a workout only if it is younger than N" rule needs
// an N, and there is no measured basis for one — so the decision is handed to the user, who is the only
// party that knows whether the walk happened. What IS refused here is the pair of spans nobody can defend:
// a zero/negative one (nothing was ever observed) and one that ends in the future (a clock that moved
// backwards, or a corrupted snapshot).
//
// Shape notes. The snapshot is a stored value, so `decide` reads one holding a value no session writes (a
// non-finite or negative energy, a negative reading count or heart rate) as a snapshot this build cannot
// read: nothing to recover. Upstream's own stored form never yields a non-finite energy (it cannot encode
// one), and it offers the negative ones as they are. Spans are compared exactly between instants (the
// carried exact-elapsed-time rule). Values compare their doubles by IEEE `==`, as Swift's synthesized
// `Equatable` does. `now` is required (upstream defaulted it to the device clock).

import java.time.Instant

// MARK: - The persisted snapshot

/**
 * The minimum a running workout writes down so a later launch can prove it existed.
 *
 * Kept small and framework-free on purpose: upstream's app persists it as JSON in its preferences store,
 * which needs no database schema version (a migration is a launch-crash surface whose recovery path wipes
 * un-resyncable raw history).
 *
 * What is NOT in here: the per-reading HR series. Those live in the in-memory aggregator and are lost with
 * the process; persisting a few hundred rows on every heartbeat to save them would be a far larger change
 * than this defect warrants. [hrSampleCount] records how many real readings the dead session had captured,
 * so the recovery UI can say what was lost rather than implying the saved workout carries them.
 *
 * @property sport sport the user selected. Drives the exercise type a recovered save writes under.
 * @property startDate when the session started (the same instant the summary / Live Activity clock counts from).
 * @property lastAliveAt the last instant the app OBSERVED this session running — refreshed on the session's
 *   periodic heartbeat. This, not the clock, is the recovered workout's end. See the file header.
 * @property hrSampleCount how many genuine HR readings the session had captured by [lastAliveAt]. Not
 *   recoverable as samples; carried so the UI can be honest about what a recovered save does and does not hold.
 * @property activeKcal the live active-calorie ESTIMATE as of [lastAliveAt] (Keytel over the readings actually
 *   captured — see [WorkoutSessionAggregator.liveActiveKcal]). Null when no reading ever locked, in which case
 *   a recovered save writes no energy at all rather than inventing one.
 * @property avgHR average BPM over the readings captured up to [lastAliveAt], or null when none were.
 * @property maxHR maximum BPM over the readings captured up to [lastAliveAt], or null when none were.
 */
data class WorkoutSessionSnapshot(
    val sport: WorkoutSportType,
    val startDate: Instant,
    val lastAliveAt: Instant,
    val hrSampleCount: Int,
    val activeKcal: Double? = null,
    val avgHR: Int? = null,
    val maxHR: Int? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is WorkoutSessionSnapshot && sport == other.sport && startDate == other.startDate &&
            lastAliveAt == other.lastAliveAt && hrSampleCount == other.hrSampleCount &&
            ieeeEquals(activeKcal, other.activeKcal) && avgHR == other.avgHR && maxHR == other.maxHR

    override fun hashCode(): Int =
        listOf(sport, startDate, lastAliveAt, hrSampleCount, activeKcal?.let(::ieeeHash), avgHR, maxHR).hashCode()
}

// MARK: - The recovered workout

/** A workout an interrupted session left behind, with a span this process is willing to defend. */
data class RecoveredWorkout(
    val sport: WorkoutSportType,
    val start: Instant,
    /** Always the snapshot's `lastAliveAt`. See the file header for why it is never `now`. */
    val end: Instant,
    val hrSampleCount: Int,
    val activeKcal: Double? = null,
    val avgHR: Int? = null,
    val maxHR: Int? = null,
) {
    /** `end − start` in seconds (negative only for one built by hand with its end first). */
    val durationSeconds: Double get() = secondsBetween(start, end)

    override fun equals(other: Any?): Boolean =
        other is RecoveredWorkout && sport == other.sport && start == other.start && end == other.end &&
            hrSampleCount == other.hrSampleCount && ieeeEquals(activeKcal, other.activeKcal) &&
            avgHR == other.avgHR && maxHR == other.maxHR

    override fun hashCode(): Int =
        listOf(sport, start, end, hrSampleCount, activeKcal?.let(::ieeeHash), avgHR, maxHR).hashCode()
}

/** Why a snapshot was refused rather than offered to the user. [rawValue] is upstream's case name. */
enum class WorkoutRecoveryRefusal(val rawValue: String) {
    /**
     * `lastAliveAt <= startDate`: the session died before its first heartbeat, so there is no observed span
     * at all. Nothing to save and nothing to tell the user about.
     */
    NO_OBSERVED_SPAN("noObservedSpan"),

    /**
     * `lastAliveAt > now`: the snapshot claims the session was alive in the future. A device clock that
     * moved backwards (or a corrupt blob) — refuse it rather than write a future-dated workout into the
     * health store, where it cannot be reasoned about afterwards.
     */
    ENDS_IN_THE_FUTURE("endsInTheFuture"),
    ;

    companion object {
        /** Upstream's `init?(rawValue:)`: the refusal stored under [rawValue], or null. */
        fun fromRawValue(rawValue: String): WorkoutRecoveryRefusal? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** What a launch should do about a persisted snapshot. */
sealed interface WorkoutRecoveryDecision {
    /** No snapshot, or one this build cannot read. Say nothing. */
    data object NothingToRecover : WorkoutRecoveryDecision

    /** A snapshot exists but describes no defensible workout — drop it silently. */
    data class Discard(val refusal: WorkoutRecoveryRefusal) : WorkoutRecoveryDecision

    /** Offer the user the choice: save this span to the health store, or discard it. */
    data class Offer(val workout: RecoveredWorkout) : WorkoutRecoveryDecision
}

// MARK: - The policy

object WorkoutSessionRecovery {

    /**
     * Decide what to do with the snapshot a previous process left behind.
     *
     * [now] is injected so the future-span refusal is testable without waiting for a clock. A snapshot
     * holding a value no session writes is one this build cannot read, checked first (upstream reads its
     * stored form before it decides): see [holdsAValueNoSessionWrites].
     */
    fun decide(snapshot: WorkoutSessionSnapshot?, now: Instant): WorkoutRecoveryDecision {
        if (snapshot == null || holdsAValueNoSessionWrites(snapshot)) return WorkoutRecoveryDecision.NothingToRecover
        if (!snapshot.lastAliveAt.isAfter(snapshot.startDate)) return WorkoutRecoveryDecision.Discard(WorkoutRecoveryRefusal.NO_OBSERVED_SPAN)
        if (snapshot.lastAliveAt.isAfter(now)) return WorkoutRecoveryDecision.Discard(WorkoutRecoveryRefusal.ENDS_IN_THE_FUTURE)
        return WorkoutRecoveryDecision.Offer(
            RecoveredWorkout(
                sport = snapshot.sport,
                start = snapshot.startDate,
                // The observed end, NOT `now`. This is the whole point of the type.
                end = snapshot.lastAliveAt,
                hrSampleCount = snapshot.hrSampleCount,
                activeKcal = snapshot.activeKcal,
                avgHR = snapshot.avgHR,
                maxHR = snapshot.maxHR,
            ),
        )
    }

    /**
     * A stored snapshot no session could have written: a non-finite or negative energy (the live estimate is
     * a high-water mark from 0), or a negative reading count or heart rate. Such a snapshot is corrupt, and
     * offering it would write its values to the health store; it fails closed as unreadable instead.
     */
    private fun holdsAValueNoSessionWrites(s: WorkoutSessionSnapshot): Boolean {
        val kcal = s.activeKcal
        return s.hrSampleCount < 0 || (kcal != null && !(kcal.isFinite() && kcal >= 0)) || (s.avgHR ?: 0) < 0 || (s.maxHR ?: 0) < 0
    }
}
