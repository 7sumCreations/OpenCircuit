package io.github.opencircuit.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Measurements the event loop relies on: the one-at-a-time dispatcher it runs on keeps the test
 * scheduler's virtual time (so timeouts are testable without real sleeps), and on the real
 * thread pool it never runs two events at once.
 */
class LoopDispatcherHazardTest {

    @Test
    fun aDelayOnTheLoopDispatcherAdvancesOnlyWithVirtualTime() = runTest {
        var fired = false
        launch(loopDispatcherFor(this)) {
            delay(5_000)
            fired = true
        }

        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(fired, "fired before its 5 s")

        advanceTimeBy(1)
        runCurrent()
        assertTrue(fired, "not fired at 5 s")
        assertEquals(5_000, testScheduler.currentTime)
    }

    @Test
    fun onTheRealThreadPoolTheLoopDispatcherRunsOneTaskAtATime() = runBlocking<Unit> {
        // The control shows the probe can see overlap: the same work on the unrestricted pool overlaps.
        assertTrue(maxConcurrent(CoroutineScope(Dispatchers.Default), restrict = false) > 1, "the probe cannot see overlap")
        assertEquals(1, maxConcurrent(CoroutineScope(Dispatchers.Default), restrict = true))
    }

    private suspend fun maxConcurrent(scope: CoroutineScope, restrict: Boolean): Int {
        val dispatcher = if (restrict) loopDispatcherFor(scope) else Dispatchers.Default
        val active = AtomicInteger()
        val peak = AtomicInteger()
        (1..64).map {
            scope.launch(dispatcher) {
                val now = active.incrementAndGet()
                peak.accumulateAndGet(now, ::maxOf)
                Thread.sleep(2)
                active.decrementAndGet()
            }
        }.joinAll()
        return peak.get()
    }
}
