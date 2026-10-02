package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.FoundationDate.unix
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Kotlin-only hostile-input checks for the live-HR recording gate and the crashed-workout recovery
 * decision: what the upstream vectors never feed in. The gate's instants come from the phone's clock
 * and the ring's lock time, and its freshness window is a setting; the recovery snapshot is a STORED
 * value written by a previous process, so it can arrive holding anything. Here the instants arrive in
 * the future, at Foundation's distant dates and at `Instant`'s ends, the window arrives NaN, infinite,
 * zero and negative, and the snapshot arrives holding unreadable, negative and extreme values. Kept out
 * of the upstream-port classes so their counts stay exact.
 *
 * Every upstream outcome quoted below was measured on the pinned Swift build (Swift 6.3.2). Where the
 * port deliberately differs the test says so, and `PORTING.md` records why.
 */
class WorkoutHRGateAndRecoveryHazardTest {

    private val s: Instant = Instant.ofEpochSecond(1_000_000)
    private fun s(seconds: Double): Instant = addingSeconds(s, seconds)!!

    private fun gate(at: Instant?, start: Instant?, last: Instant?, now: Instant, maxAge: Double = WorkoutHRGate.DEFAULT_MAX_AGE) =
        WorkoutHRGate.shouldRecord(liveHRAt = at, sessionStart = start, lastRecordedAt = last, now = now, maxAge = maxAge)

    private val t0: Instant = Instant.ofEpochSecond(1_756_400_000)
    private fun t(seconds: Double): Instant = addingSeconds(t0, seconds)!!

    private fun snapshot(
        alive: Instant,
        start: Instant = t0,
        count: Int = 12,
        kcal: Double? = 210.0,
        avg: Int? = 104,
        max: Int? = 131,
    ) = WorkoutSessionSnapshot(
        sport = WorkoutSportType.WALKING_OUTDOOR, startDate = start, lastAliveAt = alive,
        hrSampleCount = count, activeKcal = kcal, avgHR = avg, maxHR = max,
    )

    // Foundation's `Date.distantPast` (0001-01-01) and `Date.distantFuture` (4001-01-01), seconds since 1970.
    private val distantPast = unix(-62_135_769_600.0)
    private val distantFuture = unix(64_092_211_200.0)

    // MARK: the live-HR gate

    @Test
    fun locksAndRecordingsFromTheFutureFollowUpstream() {
        // Measured: a lock 100 s in the future is "fresh" (its age is negative) and records; a last
        // recording in the future blocks every real lock until the clock passes it; a session that starts
        // in the future admits nothing; with no session start a pre-workout lock is admitted.
        assertTrue(gate(at = s(100.0), start = s, last = null, now = s(1.0)))
        assertFalse(gate(at = s(10.0), start = s, last = s(3_600.0), now = s(11.0)))
        assertFalse(gate(at = s, start = s(60.0), last = null, now = s))
        assertTrue(gate(at = s(-3.0), start = null, last = null, now = s))
        // With neither a start nor a last recording, only freshness decides.
        assertTrue(gate(at = s, start = null, last = null, now = s(6.0)))
        assertFalse(gate(at = s, start = null, last = null, now = s(7.0)))
    }

    @Test
    fun hostileFreshnessWindowsFollowUpstream() {
        // Measured: a negative window admits only a lock at least that far in the future; NaN admits
        // nothing; +∞ admits a lock of any age; −∞ admits nothing; 0 admits a lock taken at `now` only.
        assertFalse(gate(at = s, start = s, last = null, now = s, maxAge = -1.0))
        assertTrue(gate(at = s(1.0), start = s, last = null, now = s, maxAge = -1.0))
        assertFalse(gate(at = s(0.5), start = s, last = null, now = s, maxAge = -1.0))
        assertFalse(gate(at = s, start = s, last = null, now = s, maxAge = Double.NaN))
        assertTrue(gate(at = s, start = s, last = null, now = s(1e9), maxAge = Double.POSITIVE_INFINITY))
        assertFalse(gate(at = s, start = s, last = null, now = s, maxAge = Double.NEGATIVE_INFINITY))
        assertTrue(gate(at = s, start = s, last = null, now = s, maxAge = 0.0))
        assertFalse(gate(at = s, start = s, last = null, now = s(1e-6), maxAge = 0.0))
        // The window is closed: a lock exactly 6 s old is still fresh (measured).
        assertTrue(gate(at = s, start = s, last = null, now = s(6.0)))
        // Kept difference (the exact-elapsed-time rule): 6 s and 1 ns is past the window here; upstream's
        // `Date` doubles cannot hold the nanosecond and still record (measured: true).
        assertFalse(gate(at = s, start = s, last = null, now = s.plusSeconds(6).plusNanos(1)))
    }

    @Test
    fun instantsAtTheEndsOfTimeNeverThrow() {
        // Measured at Foundation's distant dates: true, true, false, true.
        assertTrue(gate(at = distantPast, start = distantPast, last = null, now = distantPast))
        assertTrue(gate(at = distantFuture, start = null, last = null, now = distantPast))
        assertFalse(gate(at = distantPast, start = null, last = null, now = distantFuture))
        assertTrue(gate(at = distantFuture, start = distantFuture, last = distantPast, now = distantFuture))
        // The same shapes at `Instant`'s own ends, where no Foundation date reaches: the same answers.
        assertTrue(gate(at = Instant.MIN, start = Instant.MIN, last = null, now = Instant.MIN))
        assertTrue(gate(at = Instant.MAX, start = null, last = null, now = Instant.MIN))
        assertFalse(gate(at = Instant.MIN, start = null, last = null, now = Instant.MAX))
        assertTrue(gate(at = Instant.MAX, start = Instant.MAX, last = Instant.MIN, now = Instant.MAX))
    }

    // MARK: the recovery decision (a stored snapshot)

    @Test
    fun aSnapshotHoldingAValueNoSessionWritesIsNoSnapshot() {
        val now = t(3_600.0)
        // Upstream's own stored form cannot hold a non-finite energy: measured, `encoded()` is nil for a
        // NaN, +∞ or −∞ estimate, and a stored "NaN" does not decode — so such a snapshot reaches the app
        // as no snapshot at all. Negative counts, energy and heart rates do decode upstream and are offered
        // to be saved as they are (measured: −3 readings, −5 kcal, −104 / −1 bpm). No session writes any of
        // them: the live estimate starts at 0 and never ticks down, and counts and heart rates are never
        // negative. The port reads every such snapshot as one this build cannot read.
        for (kcal in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -5.0, -Double.MIN_VALUE)) {
            assertEquals(WorkoutRecoveryDecision.NothingToRecover, WorkoutSessionRecovery.decide(snapshot(t(600.0), kcal = kcal), now = now), "kcal $kcal")
        }
        assertEquals(WorkoutRecoveryDecision.NothingToRecover, WorkoutSessionRecovery.decide(snapshot(t(600.0), count = -1, kcal = null), now = now))
        assertEquals(WorkoutRecoveryDecision.NothingToRecover, WorkoutSessionRecovery.decide(snapshot(t(600.0), count = Int.MIN_VALUE), now = now))
        assertEquals(WorkoutRecoveryDecision.NothingToRecover, WorkoutSessionRecovery.decide(snapshot(t(600.0), avg = -104), now = now))
        assertEquals(WorkoutRecoveryDecision.NothingToRecover, WorkoutSessionRecovery.decide(snapshot(t(600.0), max = -1), now = now))
        // Unreadable comes first, as upstream's decode does: a corrupt snapshot that would also be refused
        // for its span is still no snapshot.
        assertEquals(WorkoutRecoveryDecision.NothingToRecover, WorkoutSessionRecovery.decide(snapshot(t(-1.0), kcal = Double.NaN), now = now))
        assertEquals(WorkoutRecoveryDecision.NothingToRecover, WorkoutSessionRecovery.decide(snapshot(t(7_200.0), count = -1), now = now))

        // Every value a session can write is offered as upstream offers it (measured: −0.0 and 1e308 kcal
        // encode and decode; a count past 2³¹ decodes upstream and is a stored-form concern here).
        for (kcal in listOf(-0.0, 0.0, Double.MIN_VALUE, 1e308, null)) {
            val offer = assertIs<WorkoutRecoveryDecision.Offer>(WorkoutSessionRecovery.decide(snapshot(t(600.0), kcal = kcal), now = now), "kcal $kcal")
            assertEquals(kcal, offer.workout.activeKcal)
        }
        for (count in listOf(0, Int.MAX_VALUE)) {
            assertEquals(count, assertIs<WorkoutRecoveryDecision.Offer>(WorkoutSessionRecovery.decide(snapshot(t(600.0), count = count), now = now)).workout.hrSampleCount)
        }
        val zeros = assertIs<WorkoutRecoveryDecision.Offer>(WorkoutSessionRecovery.decide(snapshot(t(600.0), avg = 0, max = 0), now = now))
        assertEquals(0, zeros.workout.avgHR)
        assertEquals(0, zeros.workout.maxHR)
    }

    @Test
    fun spansAtTheEdgesFollowUpstream() {
        // Measured: a snapshot both never observed and future-dated is "no observed span" (checked first);
        // one that starts and ends in the future "ends in the future".
        assertEquals(
            WorkoutRecoveryDecision.Discard(WorkoutRecoveryRefusal.NO_OBSERVED_SPAN),
            WorkoutSessionRecovery.decide(snapshot(t(-1.0)), now = t(-100.0)),
        )
        assertEquals(
            WorkoutRecoveryDecision.Discard(WorkoutRecoveryRefusal.ENDS_IN_THE_FUTURE),
            WorkoutSessionRecovery.decide(snapshot(t(600.0), start = t(100.0)), now = t0),
        )
        // From Foundation's distant past to its distant future: offered, 126 227 980 800 s long (measured).
        val distant = assertIs<WorkoutRecoveryDecision.Offer>(WorkoutSessionRecovery.decide(snapshot(distantFuture, start = distantPast), now = distantFuture))
        assertEquals(126_227_980_800.0, distant.workout.durationSeconds)
        assertEquals(distantFuture, distant.workout.end)
        // At `Instant`'s ends nothing throws.
        val ends = assertIs<WorkoutRecoveryDecision.Offer>(WorkoutSessionRecovery.decide(snapshot(Instant.MAX, start = Instant.MIN), now = Instant.MAX))
        assertTrue(ends.workout.durationSeconds > 6e16)
        assertEquals(
            WorkoutRecoveryDecision.Discard(WorkoutRecoveryRefusal.ENDS_IN_THE_FUTURE),
            WorkoutSessionRecovery.decide(snapshot(Instant.MAX, start = Instant.MIN), now = Instant.MIN),
        )
        // Kept differences (the exact-elapsed-time rule): one nanosecond is a span here. Upstream's `Date`
        // doubles cannot hold it in 2025, so a heartbeat 1 ns after the start is "no observed span" there
        // and one 1 ns after `now` is offered (both measured).
        assertIs<WorkoutRecoveryDecision.Offer>(WorkoutSessionRecovery.decide(snapshot(t0.plusNanos(1)), now = t(60.0)))
        assertEquals(
            WorkoutRecoveryDecision.Discard(WorkoutRecoveryRefusal.ENDS_IN_THE_FUTURE),
            WorkoutSessionRecovery.decide(snapshot(t(600.0).plusNanos(1)), now = t(600.0)),
        )
        // A recovered workout built by hand with its end before its start: −60 s, as upstream (measured).
        assertEquals(-60.0, RecoveredWorkout(WorkoutSportType.YOGA, start = t0, end = t(-60.0), hrSampleCount = 0).durationSeconds)
    }
}
