package io.github.opencircuit.app.today

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of upstream's `DashboardSectionOrderTests` (ios/OpenCircuitTests/TodayMeasureCardTests.swift
 * :16-79 @ 2d07e550), the five cases of that class; the file's other class (`VitalMeasureStateTests`,
 * the Measure control) is not ported — the Android measure stack has its own tests. Upstream's
 * Today has eight sections; this one has four of them (readiness, metrics, calories, goals), so
 * every other upstream id decodes as an id this build does not have — dropped in place, exactly as
 * the retired `vitals` is. The cases keep upstream's saved strings; where an expectation named a
 * section this build lacks, it names the section in the same place here (noted per case).
 */
class DashboardSectionOrderTest {

    /** Upstream :18-27. A layout saved while the Vitals card still existed keeps the user's order; `vitals` (and every id this build lacks) is gone. */
    @Test
    fun aSavedOrderNamingVitalsLoadsTheRestInItsSavedOrder() {
        val saved = "sync,vitals,goals,readiness,metrics,vitalsStatus,calories,cycle,headache"
        val order = DashboardSectionOrder.decode(saved)

        assertFalse(order.any { it.rawValue == "vitals" })
        // Upstream: [.sync, .goals, .readiness, .metrics, .vitalsStatus, .calories, .cycle, .headache] — the four this build has, in that order.
        assertEquals(listOf(DashboardSection.GOALS, DashboardSection.READINESS, DashboardSection.METRICS, DashboardSection.CALORIES), order)
        assertEquals(DashboardSection.entries.toSet(), order.toSet(), "every live section still loads")
    }

    /** Upstream :29-35. Retired ids (and a duplicate) are dropped without disturbing the rest; metrics, missing, goes under readiness. */
    @Test
    fun unknownAndDuplicateIdsAreDroppedInPlace() {
        val order = DashboardSectionOrder.decode("sleep,readiness,vitals,workout,goals,readiness,trends")
        assertEquals(listOf(DashboardSection.READINESS, DashboardSection.METRICS, DashboardSection.GOALS), order.take(3), "metrics is inserted under readiness; the retired ids leave no gap")
    }

    /** Upstream :37-42. No saved layout: the canonical order, with no Vitals card in it. */
    @Test
    fun anEmptySavedOrderGivesTheDefaultWithoutVitals() {
        assertEquals(DashboardSection.entries.toList(), DashboardSectionOrder.decode(""))
        assertFalse(DashboardSection.entries.any { it.rawValue == "vitals" })
    }

    /** Upstream :44-59. A reorder writes live ids only, so a retired id never comes back. */
    @Test
    fun vitalsNeverComesBackAfterAReorder() {
        val saved = "readiness,vitals,metrics,vitalsStatus,calories,goals,cycle,headache,sync"
        val full = DashboardSectionOrder.decode(saved)
        // Move the last section to the top, as a long-press drag does.
        val moved = DashboardSectionOrder.reordered(full, full, from = full.size - 1, to = 0)
        val written = DashboardSectionOrder.encode(moved)

        assertFalse(written.split(",").contains("vitals"), "a retired id must never be written back")
        assertEquals(moved, DashboardSectionOrder.decode(written))
        // Upstream: .sync (its last section); the last section here is goals.
        assertEquals(DashboardSection.GOALS, moved.first())
        assertEquals(DashboardSection.entries.toSet(), moved.toSet())
    }

    /** Upstream :61-71. A hidden section comes back at its prior absolute position, never dropped. */
    @Test
    fun aHiddenSectionIsMergedBackAtItsPriorPosition() {
        val full = DashboardSectionOrder.decode("")
        // Upstream hides its two opt-in cards (cycle, headache); here the last two sections.
        val visible = full.filter { it != DashboardSection.CALORIES && it != DashboardSection.GOALS }
        val moved = DashboardSectionOrder.reordered(full, visible, from = 0, to = visible.size)
        assertEquals(full.toSet(), moved.toSet())
        assertTrue(DashboardSection.CALORIES in moved)
        assertTrue(DashboardSection.GOALS in moved)
    }

    /**
     * The ported cases are upstream's `DashboardSectionOrderTests`, all of them and nothing else:
     * the `func test…` lines of that class in ios/OpenCircuitTests/TodayMeasureCardTests.swift
     * @ 2d07e550, in file order; the Kotlin side is read off the compiled test class.
     */
    @Test
    fun thePortHoldsEveryUpstreamSectionOrderTestOnce() {
        val upstream = listOf(
            "testASavedOrderNamingVitalsLoadsTheRestInItsSavedOrder", "testUnknownAndDuplicateIdsAreDroppedInPlace",
            "testAnEmptySavedOrderGivesTheDefaultWithoutVitals", "testVitalsNeverComesBackAfterAReorder",
            "testAHiddenSectionIsMergedBackAtItsPriorPosition",
        )
        assertEquals(5, upstream.size)
        val expected = upstream.map { it.removePrefix("test").replaceFirstChar(Char::lowercaseChar) }.sorted()
        val ported = DashboardSectionOrderTest::class.java.declaredMethods
            .filter { it.isAnnotationPresent(Test::class.java) && it.name != "thePortHoldsEveryUpstreamSectionOrderTestOnce" }.map { it.name }.sorted()
        assertEquals(expected, ported)
    }
}
