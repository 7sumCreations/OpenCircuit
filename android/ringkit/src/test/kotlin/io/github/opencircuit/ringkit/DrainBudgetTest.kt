package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether an exhausted per-channel drain budget extends rather than cuts a still-streaming ring.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/DrainBudgetTests.swift
 * (@ b1c2fdd): all 8 tests.
 */
class DrainBudgetTest {

    private val cap = 45
    private val ceiling = 180

    // :15 — THE OFF-BY-ONE: quietTicks 1 and 2 both mean "still streaming" and BOTH must extend.
    @Test
    fun extendsForEveryQuietTickValueBelowTheQuietExit() {
        for (quietTicks in 1 until 3) {
            assertTrue(
                DrainBudget.shouldExtend(tick = cap, cap = cap, ceiling = ceiling, sawPages = true, quietTicks = quietTicks),
                "quietTicks=$quietTicks is still a live stream and must extend",
            )
        }
    }

    // :26 — at or past the quiet-exit threshold the loop's own exit owns the decision.
    @Test
    fun doesNotExtendOnceTheQuietExitWouldFire() {
        for (quietTicks in 3..6) {
            assertFalse(DrainBudget.shouldExtend(tick = cap, cap = cap, ceiling = ceiling, sawPages = true, quietTicks = quietTicks))
        }
    }

    // :36
    @Test
    fun doesNotExtendBeforeTheBudgetIsSpent() {
        assertFalse(DrainBudget.shouldExtend(tick = cap - 1, cap = cap, ceiling = ceiling, sawPages = true, quietTicks = 1))
    }

    // :41
    @Test
    fun doesNotExtendAnIdleChannel() {
        assertFalse(DrainBudget.shouldExtend(tick = cap, cap = cap, ceiling = ceiling, sawPages = false, quietTicks = 1))
    }

    // :46
    @Test
    fun doesNotExtendPastTheCeiling() {
        assertFalse(DrainBudget.shouldExtend(tick = ceiling, cap = ceiling, ceiling = ceiling, sawPages = true, quietTicks = 1))
    }

    // :51
    @Test
    fun capIsClampedToTheCeiling() {
        assertEquals(180, DrainBudget.extendedCap(cap = 135, step = 45, ceiling = 180))
        assertEquals(180, DrainBudget.extendedCap(cap = 170, step = 45, ceiling = 180))
        assertEquals(90, DrainBudget.extendedCap(cap = 45, step = 45, ceiling = 180))
    }

    // :59 — walking a never-quiet ring, the cap reaches the ceiling and then stops extending.
    @Test
    fun extensionTerminatesAtTheCeiling() {
        var cap = this.cap
        var extensions = 0
        while (DrainBudget.shouldExtend(tick = cap, cap = cap, ceiling = ceiling, sawPages = true, quietTicks = 1)) {
            val next = DrainBudget.extendedCap(cap = cap, step = this.cap, ceiling = ceiling)
            assertTrue(next > cap, "cap must strictly grow or the loop cannot terminate")
            cap = next
            extensions += 1
            assertTrue(extensions < 10, "runaway extension")
        }
        assertEquals(ceiling, cap)
    }

    // :76 — the measured ~55-tick handoff and a ~88-tick ten-hour night fit only with extension.
    @Test
    fun measuredAndLongNightHandoffsFitOnlyWithExtension() {
        val measuredHandoffTicks = 55
        val tenHourNightTicks = 88
        assertTrue(tenHourNightTicks > cap, "premise: a long night outruns the nominal cap")
        for (needed in listOf(measuredHandoffTicks, tenHourNightTicks)) {
            var cap = this.cap
            while (cap < needed && DrainBudget.shouldExtend(tick = cap, cap = cap, ceiling = ceiling, sawPages = true, quietTicks = 2)) {
                cap = DrainBudget.extendedCap(cap = cap, step = this.cap, ceiling = ceiling)
            }
            assertTrue(cap >= needed, "a $needed-tick handoff must not be cut")
        }
    }
}
