package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/WorkoutSessionRecoveryTests.swift
 * (@ b1c2fdd), 7 of its 9 tests, each named after upstream's with its line. The two persistence tests
 * (`testSnapshotRoundTripsThroughItsPersistedForm` `:109`, `testUnreadableBlobIsTreatedAsAbsent` `:117`)
 * are not ported: they need the snapshot's stored form, which belongs to the storage epic
 * (`PORTING.md` names them).
 *
 * Pins the honesty invariants of crash-orphan workout recovery. SYNTHETIC by construction: these are
 * pure date / decision invariants, not decoded ring bytes. The span they defend is the one the tester's
 * 2026-08-29 report exposed — a ~55-minute evening walk that the app destroyed instead of saving.
 * Upstream's fixed instants are kept; every call names its `now`, as upstream's tests do.
 */
class WorkoutSessionRecoveryTest {

    private val t0: Instant = Instant.ofEpochSecond(1_756_400_000)

    private fun Instant.adding(seconds: Double): Instant = addingSeconds(this, seconds)!!

    private fun snapshot(start: Instant, alive: Instant, hrSampleCount: Int = 12, kcal: Double? = 210.0): WorkoutSessionSnapshot =
        WorkoutSessionSnapshot(
            sport = WorkoutSportType.WALKING_OUTDOOR, startDate = start, lastAliveAt = alive,
            hrSampleCount = hrSampleCount, activeKcal = kcal, avgHR = 104, maxHR = 131,
        )

    // MARK: - The load-bearing invariant

    /**
     * THE rule: a recovered workout ends when the app last SAW the session, never at `now`. A process that
     * died at 19:20 has no evidence the walk continued — stretching to `now` would fabricate duration and,
     * through the health store, an activity credit.
     */
    @Test
    fun testRecoveredEndIsTheLastObservedInstantNotNow() { // :27
        val start = t0
        val lastAlive = t0.adding(55.0 * 60)
        val openedTheAppMuchLater = t0.adding(9.0 * 3600)

        val recovered = assertIs<WorkoutRecoveryDecision.Offer>(
            WorkoutSessionRecovery.decide(snapshot = snapshot(start = start, alive = lastAlive), now = openedTheAppMuchLater),
            "expected an offer",
        ).workout

        assertEquals(lastAlive, recovered.end)
        assertEquals(55.0 * 60, recovered.durationSeconds, 0.001)
        assertNotEquals(openedTheAppMuchLater, recovered.end)
    }

    @Test
    fun testOfferCarriesTheSessionsOwnMeasurements() { // :41
        val recovered = assertIs<WorkoutRecoveryDecision.Offer>(
            WorkoutSessionRecovery.decide(snapshot = snapshot(start = t0, alive = t0.adding(600.0)), now = t0.adding(3600.0)),
            "expected an offer",
        ).workout

        assertEquals(WorkoutSportType.WALKING_OUTDOOR, recovered.sport)
        assertEquals(t0, recovered.start)
        assertEquals(12, recovered.hrSampleCount)
        assertEquals(210.0, recovered.activeKcal)
        assertEquals(104, recovered.avgHR)
        assertEquals(131, recovered.maxHR)
    }

    /**
     * No reading ever locked ⇒ no energy is carried. A recovered save must write nothing rather than an
     * invented number (#45 honesty, carried through the crash path).
     */
    @Test
    fun testNoCapturedHRCarriesNoEnergy() { // :57
        val recovered = assertIs<WorkoutRecoveryDecision.Offer>(
            WorkoutSessionRecovery.decide(
                snapshot = snapshot(start = t0, alive = t0.adding(600.0), hrSampleCount = 0, kcal = null),
                now = t0.adding(3600.0),
            ),
            "expected an offer",
        ).workout

        assertNull(recovered.activeKcal)
        assertEquals(0, recovered.hrSampleCount)
    }

    // MARK: - Refusals

    @Test
    fun testNoSnapshotRecoversNothing() { // :70
        assertEquals(WorkoutRecoveryDecision.NothingToRecover, WorkoutSessionRecovery.decide(snapshot = null, now = t0))
    }

    /**
     * The session died before its first heartbeat: there is no observed span, so there is nothing to save
     * and nothing worth interrupting the user about.
     */
    @Test
    fun testSpanThatWasNeverObservedIsDiscarded() { // :76
        assertEquals(
            WorkoutRecoveryDecision.Discard(WorkoutRecoveryRefusal.NO_OBSERVED_SPAN),
            WorkoutSessionRecovery.decide(snapshot = snapshot(start = t0, alive = t0), now = t0.adding(60.0)),
        )
        assertEquals(
            WorkoutRecoveryDecision.Discard(WorkoutRecoveryRefusal.NO_OBSERVED_SPAN),
            WorkoutSessionRecovery.decide(snapshot = snapshot(start = t0, alive = t0.adding(-1.0)), now = t0.adding(60.0)),
        )
    }

    /**
     * A snapshot claiming the session was alive in the future (clock moved backwards, or a corrupt blob) is
     * refused BEFORE it can reach the health store — the same plausibility discipline the sync cursor
     * applies to samples.
     */
    @Test
    fun testFutureDatedSnapshotIsRefused() { // :91
        assertEquals(
            WorkoutRecoveryDecision.Discard(WorkoutRecoveryRefusal.ENDS_IN_THE_FUTURE),
            WorkoutSessionRecovery.decide(snapshot = snapshot(start = t0, alive = t0.adding(3600.0)), now = t0.adding(600.0)),
        )
    }

    /** Boundary: a heartbeat landing exactly at `now` is fine — only strictly-future is refused. */
    @Test
    fun testLastAliveExactlyNowIsStillOffered() { // :100
        val alive = t0.adding(600.0)
        assertIs<WorkoutRecoveryDecision.Offer>(
            WorkoutSessionRecovery.decide(snapshot = snapshot(start = t0, alive = alive), now = alive),
            "a heartbeat at `now` is not in the future",
        )
    }
}
