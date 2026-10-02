package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/WorkoutHRGateTests.swift (@ b1c2fdd),
 * 7 of 7, each test named after upstream's with its line.
 *
 * Locks in the "stuck at 98 while the reading counter climbs" regression fix (#45): a workout must record
 * a live-HR sample only for a genuinely fresh, in-session, not-yet-recorded lock — never the ring's last
 * value held between polls. Upstream's fixed instants are kept; `TimeInterval` offsets are `Double`
 * seconds.
 */
class WorkoutHRGateTest {

    private val start: Instant = Instant.ofEpochSecond(1_000_000)

    private fun Instant.adding(seconds: Double): Instant = addingSeconds(this, seconds)!!

    // MARK: - Records a real fresh lock

    @Test
    fun testRecordsAFreshInSessionLock() { // :13
        val now = start.adding(10.0)
        val lockedAt = now.adding(-1.0) // 1 s old ⇒ fresh
        assertTrue(WorkoutHRGate.shouldRecord(liveHRAt = lockedAt, sessionStart = start, lastRecordedAt = null, now = now))
    }

    // MARK: - The freeze: a held latch must NOT be re-recorded

    @Test
    fun testRejectsAHeldLatchReReadEveryPoll() { // :23
        // The ring locked once at t+2 and never re-locked; the workout polls again at t+4, t+6…
        val lockedAt = start.adding(2.0)
        // First poll records it.
        assertTrue(WorkoutHRGate.shouldRecord(liveHRAt = lockedAt, sessionStart = start, lastRecordedAt = null, now = start.adding(2.0)))
        // Subsequent polls see the SAME capture time ⇒ must be rejected (no climbing counter).
        var tick = 4.0
        while (tick <= 20.0) {
            assertFalse(
                WorkoutHRGate.shouldRecord(liveHRAt = lockedAt, sessionStart = start, lastRecordedAt = lockedAt, now = start.adding(tick)),
                "a held latch re-read at t+$tick must not record",
            )
            tick += 2.0
        }
    }

    @Test
    fun testRejectsAStaleLockOlderThanMaxAge() { // :39
        val lockedAt = start.adding(2.0)
        val now = start.adding(2 + WorkoutHRGate.DEFAULT_MAX_AGE + 0.5) // just past the window
        assertFalse(WorkoutHRGate.shouldRecord(liveHRAt = lockedAt, sessionStart = start, lastRecordedAt = null, now = now))
    }

    // MARK: - The carry-in: a pre-workout resting lock must not seed the session

    @Test
    fun testRejectsAPreWorkoutLockCarriedIn() { // :49
        // A resting "98" measured 30 s BEFORE the workout, still held in the latch at start.
        val preWorkoutLock = start.adding(-30.0)
        val now = start.adding(1.0)
        // Even though lastRecordedAt is null (nothing recorded yet) and it's "recent", the in-session floor
        // rejects it — without this clause one stale sample leaks in.
        assertFalse(WorkoutHRGate.shouldRecord(liveHRAt = preWorkoutLock, sessionStart = start, lastRecordedAt = null, now = now))
    }

    // MARK: - No lock at all

    @Test
    fun testRejectsWhenNoLockEverExisted() { // :62
        assertFalse(WorkoutHRGate.shouldRecord(liveHRAt = null, sessionStart = start, lastRecordedAt = null, now = start.adding(5.0)))
    }

    // MARK: - A genuinely new lock after an earlier one IS recorded

    @Test
    fun testRecordsAnAdvancedLockAfterAPreviousOne() { // :70
        val firstLock = start.adding(2.0)
        val secondLock = start.adding(4.0) // a real new lock 2 s later
        assertTrue(WorkoutHRGate.shouldRecord(liveHRAt = secondLock, sessionStart = start, lastRecordedAt = firstLock, now = start.adding(4.0)))
    }

    // MARK: - Boundary: lock exactly at session start is in-session

    @Test
    fun testLockExactlyAtStartIsInSession() { // :80
        assertTrue(WorkoutHRGate.shouldRecord(liveHRAt = start, sessionStart = start, lastRecordedAt = null, now = start.adding(0.5)))
    }
}
