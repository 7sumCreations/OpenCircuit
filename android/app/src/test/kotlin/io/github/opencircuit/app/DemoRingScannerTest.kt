package io.github.opencircuit.app

import io.github.opencircuit.app.demo.DemoRingScanner
import io.github.opencircuit.ble.ScanUpdate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The debug build's demo scanner: the emulator has no ring to find, so a Scan & connect "finds"
 * the demo ring at once and selects it after the scanner's 2.5 s quiet window, ending the scan,
 * as `:ble`'s scanner does when exactly one ring is nearby.
 */
class DemoRingScannerTest {

    @Test
    fun theDemoRingIsFoundAtOnceAndSelectedAfterTwoAndAHalfSeconds() = runTest {
        val updates = mutableListOf<ScanUpdate>()
        val job = backgroundScope.launch { DemoRingScanner(testRing).scan().toList(updates) }
        runCurrent()
        assertEquals(listOf<ScanUpdate>(ScanUpdate.Found(listOf(testRing))), updates)

        advanceTo(2_499)
        assertEquals(1, updates.size)

        advanceTo(2_500)
        assertEquals(ScanUpdate.Selected(testRing), updates.last())
        assertEquals(2, updates.size)
        assertEquals(true, job.isCompleted, "Selected ends the scan")
    }
}
