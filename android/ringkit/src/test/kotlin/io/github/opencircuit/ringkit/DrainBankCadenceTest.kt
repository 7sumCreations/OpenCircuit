package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A CONTINUOUS page burst re-arms the quiet debounce on every page, so without a hold bound the
 * bank is deferred until the burst ends — exactly the window that lost a whole night upstream.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/DrainBankCadenceTests.swift
 * (@ b1c2fdd): all 7 tests. Upstream's fractional seconds are exact milliseconds here.
 */
class DrainBankCadenceTest {

    private val t0 = Instant.ofEpochSecond(1_786_400_000)

    private fun seconds(s: Double): Duration = Duration.ofMillis(Math.round(s * 1000))

    // :11
    @Test
    fun nothingHeldDebounces() {
        assertEquals(DrainBankCadence.Action.DEBOUNCE, DrainBankCadence.decide(firstUnbankedAt = null, now = t0))
    }

    // :15
    @Test
    fun freshPageDebouncesRatherThanBanking() {
        assertEquals(DrainBankCadence.Action.DEBOUNCE, DrainBankCadence.decide(firstUnbankedAt = t0, now = t0))
        assertEquals(DrainBankCadence.Action.DEBOUNCE, DrainBankCadence.decide(firstUnbankedAt = t0, now = t0.plusSeconds(1)))
    }

    // :21 — at the bound exactly, and past it.
    @Test
    fun holdBoundForcesABankEvenWhilePagesKeepArriving() {
        assertEquals(
            DrainBankCadence.Action.BANK_NOW,
            DrainBankCadence.decide(firstUnbankedAt = t0, now = t0.plus(DrainBankCadence.MAX_HOLD)),
        )
        assertEquals(
            DrainBankCadence.Action.BANK_NOW,
            DrainBankCadence.decide(firstUnbankedAt = t0, now = t0.plus(DrainBankCadence.MAX_HOLD).plusSeconds(5)),
        )
    }

    // :34 — 20 pages over 43 s at the captured 2.26 s cadence never go quiet; the burst must bank
    // repeatedly on the way through.
    @Test
    fun wholeNightBurstBanksRepeatedlyInsteadOfOnceAtTheEnd() {
        var firstUnbanked: Instant? = null
        var banks = 0
        var now = t0
        for (gap in listOf(0.0) + List(19) { 2.26 }) {
            now = now.plus(seconds(gap))
            if (firstUnbanked == null) firstUnbanked = now
            if (DrainBankCadence.decide(firstUnbankedAt = firstUnbanked, now = now) == DrainBankCadence.Action.BANK_NOW) {
                banks += 1
                firstUnbanked = null
            }
        }
        assertTrue(banks >= 4, "a continuous 43 s handoff must bank repeatedly; the debounce alone never fires")
    }

    // :53 — the bound is measured from the OLDEST unbanked page, not the latest.
    @Test
    fun boundIsMeasuredFromOldestHeldPage() {
        val oldest = t0
        val latest = t0.plus(DrainBankCadence.MAX_HOLD).plusSeconds(1)
        assertEquals(DrainBankCadence.Action.BANK_NOW, DrainBankCadence.decide(firstUnbankedAt = oldest, now = latest))
        assertEquals(DrainBankCadence.Action.DEBOUNCE, DrainBankCadence.decide(firstUnbankedAt = latest, now = latest))
    }

    // :64 — `QUIET` must clear the measured 3 s inter-page ceiling, and sit below `MAX_HOLD`.
    @Test
    fun quietClearsTheMeasuredInterPageCeilingSoMaxHoldIsTheLiveMechanism() {
        val measuredMaxInterPageGap = Duration.ofSeconds(3)
        assertTrue(DrainBankCadence.QUIET > measuredMaxInterPageGap, "a quiet window inside the inter-page range makes maxHold dead code")
        assertTrue(DrainBankCadence.QUIET < DrainBankCadence.MAX_HOLD)
    }

    // :73 — replaying the measured gaps, the debounce must never be what fires mid-burst.
    @Test
    fun debounceNeverFiresMidBurstAtTheMeasuredCadence() {
        for (gap in listOf(1.0, 2.26, 3.0)) {
            assertTrue(seconds(gap) < DrainBankCadence.QUIET, "gap ${gap}s would trip the debounce and pre-empt maxHold")
        }
    }
}
