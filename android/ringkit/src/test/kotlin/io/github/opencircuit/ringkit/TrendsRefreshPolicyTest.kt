package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.TrendsRefreshPolicy.Reason
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/TrendsRefreshPolicyTests.swift
 * (@ b1c2fdd), all 7 tests. Upstream already passes `now` explicitly in every call; here it is a
 * required parameter, so no test can fall back to the machine clock.
 *
 * The defect this guards: at a cold launch the trends view fires its reload from BOTH its first
 * appearance AND the app becoming active, within a frame or two of each other, for the same unchanged
 * store — and one reload reads about 25 000 rows. The asymmetry that must survive any future edit:
 * appear/foreground are navigation events that know nothing about the store and are debounced; a
 * finished sync is the one trigger that means "rows may have landed" and must NEVER be debounced.
 */
class TrendsRefreshPolicyTest {

    private val t0: Instant = Instant.ofEpochSecond(1_786_400_000)

    // MARK: First load is never suppressed

    @Test
    fun firstLoadAlwaysRuns() { // TrendsRefreshPolicyTests.swift:18
        for (reason in listOf(Reason.APPEARED, Reason.FOREGROUNDED, Reason.SYNC_FINISHED)) {
            assertTrue(
                TrendsRefreshPolicy.shouldReload(reason, lastLoadedAt = null, now = t0),
                "a snapshot that has never loaded must not be debounced ($reason)",
            )
        }
    }

    // MARK: The cold-launch double-fire — the actual bug

    @Test
    fun foregroundImmediatelyAfterAppearIsSuppressed() { // TrendsRefreshPolicyTests.swift:28
        // The first appearance loads at t0; the app becomes active a frame later.
        assertFalse(TrendsRefreshPolicy.shouldReload(Reason.FOREGROUNDED, lastLoadedAt = t0, now = t0.plusMillis(50)))
    }

    @Test
    fun navigationReloadsAreDebouncedInsideTheWindow() { // TrendsRefreshPolicyTests.swift:35
        val justInside = t0.plus(TrendsRefreshPolicy.MIN_INTERVAL).minusMillis(1)
        assertFalse(TrendsRefreshPolicy.shouldReload(Reason.APPEARED, lastLoadedAt = t0, now = justInside))
        assertFalse(TrendsRefreshPolicy.shouldReload(Reason.FOREGROUNDED, lastLoadedAt = t0, now = justInside))
    }

    @Test
    fun navigationReloadsRunOnceTheWindowElapses() { // TrendsRefreshPolicyTests.swift:43
        val atBoundary = t0.plus(TrendsRefreshPolicy.MIN_INTERVAL)
        assertTrue(TrendsRefreshPolicy.shouldReload(Reason.APPEARED, lastLoadedAt = t0, now = atBoundary))
        assertTrue(TrendsRefreshPolicy.shouldReload(Reason.FOREGROUNDED, lastLoadedAt = t0, now = t0.plusSeconds(3600)))
    }

    // MARK: A finished sync is never debounced

    @Test
    fun syncFinishedIsNeverDebounced() { // TrendsRefreshPolicyTests.swift:54
        // Same instant as the last load — a drain that commits immediately after a reload must
        // still refresh, or the rows it just wrote are invisible until the next navigation.
        assertTrue(TrendsRefreshPolicy.shouldReload(Reason.SYNC_FINISHED, lastLoadedAt = t0, now = t0))
        assertTrue(TrendsRefreshPolicy.shouldReload(Reason.SYNC_FINISHED, lastLoadedAt = t0, now = t0.plusMillis(1)))
    }

    // MARK: Clock hazards

    @Test
    fun backwardClockDoesNotLatchTheSnapshotStale() { // TrendsRefreshPolicyTests.swift:66
        // A timezone/NTP correction can move `now` behind `lastLoadedAt`. A naive `elapsed >=`
        // comparison would then suppress every navigation reload until real time caught up.
        assertTrue(TrendsRefreshPolicy.shouldReload(Reason.APPEARED, lastLoadedAt = t0, now = t0.minusSeconds(3600)))
    }

    @Test
    fun minIntervalIsBoundedByTheRingEpochCadence() { // TrendsRefreshPolicyTests.swift:74
        // The documented rationale: a suppressed reload can be behind by at most ONE 150 s epoch.
        assertTrue(TrendsRefreshPolicy.MIN_INTERVAL <= Duration.ofSeconds(150))
        assertTrue(TrendsRefreshPolicy.MIN_INTERVAL > Duration.ZERO)
    }
}
