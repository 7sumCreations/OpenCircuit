package io.github.opencircuit.app.demo

import io.github.opencircuit.ble.RememberedRing
import io.github.opencircuit.ble.RingScanner
import io.github.opencircuit.ble.ScanUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A pretend scanner for debug builds: the emulator has no ring to find, so each scan finds
 * [ring] (the demo ring) at once and selects it after 2.5 s with no other ring, then ends, as
 * `:ble`'s scanner does when exactly one ring is nearby. Only in the debug source set.
 */
class DemoRingScanner(private val ring: RememberedRing) : RingScanner {
    override fun scan(): Flow<ScanUpdate> = flow {
        emit(ScanUpdate.Found(listOf(ring)))
        delay(QUIET_WINDOW_MILLIS)
        emit(ScanUpdate.Selected(ring))
    }

    private companion object {
        /** `:ble`'s selection quiet window (upstream `selectionQuietWindow`, RingScanner.swift:293). */
        const val QUIET_WINDOW_MILLIS = 2_500L
    }
}
