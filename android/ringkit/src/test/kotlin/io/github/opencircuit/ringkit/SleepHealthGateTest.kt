package io.github.opencircuit.ringkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate for mirroring a night's sleep to the health store: an ordinary drain waits until the
 * night's latest segment is a quiet margin in the past; a finalization signal writes at once, but
 * never without real segments.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepHealthGateTests.swift
 * (@ b1c2fdd) — all 9 tests.
 */
class SleepHealthGateTest {

    private val now: Instant = Instant.ofEpochSecond(1_000_000)

    @Test
    fun nullNeverSettled() {
        assertFalse(SleepHealthGate.isSettled(latestSegmentEnd = null, now = now))
    }

    @Test
    fun inProgressNightNotSettled() {
        // Last epoch 3 min ago — still asleep / block could grow.
        val end = now.minusSeconds(3 * 60)
        assertFalse(SleepHealthGate.isSettled(latestSegmentEnd = end, now = now))
    }

    @Test
    fun finishedNightSettled() {
        // Woke 40 min ago — block won't grow.
        val end = now.minusSeconds(40 * 60)
        assertTrue(SleepHealthGate.isSettled(latestSegmentEnd = end, now = now))
    }

    @Test
    fun exactlyAtMarginIsSettled() {
        val end = now.minus(SleepHealthGate.SETTLE_MARGIN)
        assertTrue(SleepHealthGate.isSettled(latestSegmentEnd = end, now = now))
    }

    @Test
    fun justInsideMarginNotSettled() {
        val end = now.minus(SleepHealthGate.SETTLE_MARGIN).plusSeconds(1)
        assertFalse(SleepHealthGate.isSettled(latestSegmentEnd = end, now = now))
    }

    @Test
    fun ordinaryWriteStillRequiresSettlement() {
        val end = now.minusSeconds(3 * 60)
        assertFalse(SleepHealthGate.isReadyToWrite(latestSegmentEnd = end, now = now, finalized = false))
    }

    @Test
    fun focusEndFinalizationWritesImmediately() {
        val end = now.minusSeconds(3 * 60)
        assertTrue(SleepHealthGate.isReadyToWrite(latestSegmentEnd = end, now = now, finalized = true))
    }

    @Test
    fun finalizationStillRequiresRealSegments() {
        assertFalse(SleepHealthGate.isReadyToWrite(latestSegmentEnd = null, now = now, finalized = true))
    }

    /**
     * The case the edit paths pass `finalized = true` for (a Gen 2 Air tester, Europe/Paris): she woke,
     * saw the app had ended her night at a 02:45 bathroom trip, corrected her wake to 06:44 and saved
     * at 06:50 — SIX minutes later, inside the 20-minute margin. Gating on `isSettled` wrote NOTHING at
     * Save time. Editing your wake right after waking is the normal case, and an edited night's edges
     * are typed, not growing — so the Save is the finalization signal. The `false` arm is the same
     * instant WITHOUT that signal, so this pins the difference rather than just the new behaviour.
     */
    @Test
    fun aWearersSaveMinutesAfterHerAssertedWakeWritesImmediately() {
        val assertedWake = Instant.ofEpochSecond(1_787_546_640) // 06:44:00 +02:00
        val saveTime = assertedWake.plusSeconds(6 * 60) // 06:50:00
        assertTrue(SleepHealthGate.isReadyToWrite(latestSegmentEnd = assertedWake, now = saveTime, finalized = true))
        assertFalse(
            SleepHealthGate.isReadyToWrite(latestSegmentEnd = assertedWake, now = saveTime, finalized = false),
            "without the signal her edit is deferred — the behaviour she reported",
        )
    }
}
