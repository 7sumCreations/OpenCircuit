package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Silent-failure alert policy: staleness and low-battery thresholds, the Health-access-lost rule,
 * the per-condition re-notify debounce, and the bounded outcome log.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SyncObservabilityTests.swift
 * (@ b1c2fdd): all 13 tests. Upstream's seconds are `Duration`s and its dates `Instant`s.
 */
class SyncObservabilityTest {

    private val now = Instant.ofEpochSecond(1_000_000)
    private val policy = SyncAlertPolicy(
        staleSyncThreshold = Duration.ofSeconds(6 * 3600),
        lowBatteryThreshold = 15,
        renotifyInterval = Duration.ofSeconds(6 * 3600),
    )

    private fun hoursAgo(h: Long): Instant = now.minusSeconds(h * 3600)

    // MARK: activeConditions

    // :12
    @Test
    fun freshSyncHealthyHasNoConditions() {
        val active = policy.activeConditions(
            now = now,
            lastSuccessfulSync = hoursAgo(1), // 1 h ago — fresh
            batteryPercent = 80,
            healthAuthorized = true,
            healthEverAuthorized = true,
        )
        assertTrue(active.isEmpty())
    }

    // :22 — no baseline yet: a brand-new user is not nagged about staleness.
    @Test
    fun neverSyncedDoesNotFireStaleness() {
        val active = policy.activeConditions(
            now = now, lastSuccessfulSync = null, batteryPercent = 80,
            healthAuthorized = true, healthEverAuthorized = true,
        )
        assertFalse(SyncAlert.NOT_SYNCED in active)
    }

    // :30
    @Test
    fun staleSyncFires() {
        val active = policy.activeConditions(
            now = now,
            lastSuccessfulSync = hoursAgo(7), // 7 h ago > 6 h
            batteryPercent = 80,
            healthAuthorized = true, healthEverAuthorized = true,
        )
        assertTrue(SyncAlert.NOT_SYNCED in active)
    }

    // :39
    @Test
    fun lowBatteryFiresAtThresholdAndNotAbove() {
        assertTrue(
            SyncAlert.LOW_BATTERY in policy.activeConditions(
                now = now, lastSuccessfulSync = now, batteryPercent = 15,
                healthAuthorized = true, healthEverAuthorized = true,
            ),
        )
        assertFalse(
            SyncAlert.LOW_BATTERY in policy.activeConditions(
                now = now, lastSuccessfulSync = now, batteryPercent = 16,
                healthAuthorized = true, healthEverAuthorized = true,
            ),
        )
    }

    // :48
    @Test
    fun nilBatterySkipsBatteryCheck() {
        val active = policy.activeConditions(
            now = now, lastSuccessfulSync = now, batteryPercent = null,
            healthAuthorized = true, healthEverAuthorized = true,
        )
        assertFalse(SyncAlert.LOW_BATTERY in active)
    }

    // :55
    @Test
    fun healthAuthLostOnlyWhenPreviouslyAuthorized() {
        // Never authorized → not an "auth lost" condition.
        assertFalse(
            SyncAlert.HEALTH_AUTH_LOST in policy.activeConditions(
                now = now, lastSuccessfulSync = now, batteryPercent = 80,
                healthAuthorized = false, healthEverAuthorized = false,
            ),
        )
        // Was authorized, now off → fires.
        assertTrue(
            SyncAlert.HEALTH_AUTH_LOST in policy.activeConditions(
                now = now, lastSuccessfulSync = now, batteryPercent = 80,
                healthAuthorized = false, healthEverAuthorized = true,
            ),
        )
    }

    // MARK: alertsToFire (debounce)

    // :68
    @Test
    fun alertFiresWhenNeverFiredBefore() {
        val fire = policy.alertsToFire(
            now = now,
            lastSuccessfulSync = hoursAgo(7),
            batteryPercent = 80, healthAuthorized = true, healthEverAuthorized = true,
            lastFired = emptyMap(),
        )
        assertEquals(listOf(SyncAlert.NOT_SYNCED), fire)
    }

    // :77
    @Test
    fun alertSuppressedInsideRenotifyWindow() {
        val fire = policy.alertsToFire(
            now = now,
            lastSuccessfulSync = hoursAgo(7),
            batteryPercent = 80, healthAuthorized = true, healthEverAuthorized = true,
            lastFired = mapOf(SyncAlert.NOT_SYNCED to hoursAgo(1)), // fired 1 h ago < 6 h window
        )
        assertTrue(fire.isEmpty())
    }

    // :86
    @Test
    fun alertReFiresAfterRenotifyWindow() {
        val fire = policy.alertsToFire(
            now = now,
            lastSuccessfulSync = hoursAgo(7),
            batteryPercent = 80, healthAuthorized = true, healthEverAuthorized = true,
            lastFired = mapOf(SyncAlert.NOT_SYNCED to hoursAgo(7)), // fired 7 h ago > 6 h window
        )
        assertEquals(listOf(SyncAlert.NOT_SYNCED), fire)
    }

    // :95
    @Test
    fun multipleConditionsReturnedInStableOrder() {
        val fire = policy.alertsToFire(
            now = now,
            lastSuccessfulSync = hoursAgo(7),                         // notSynced
            batteryPercent = 5,                                       // lowBattery
            healthAuthorized = false, healthEverAuthorized = true,    // healthAuthLost
            lastFired = emptyMap(),
        )
        assertEquals(listOf(SyncAlert.NOT_SYNCED, SyncAlert.LOW_BATTERY, SyncAlert.HEALTH_AUTH_LOST), fire)
    }

    // MARK: BoundedLog

    // :107
    @Test
    fun boundedLogAppendsUnderLimit() {
        assertEquals(listOf(1, 2, 3), BoundedLog.appendCapped(3, to = listOf(1, 2), limit = 5))
    }

    // :112 — newest survive, oldest (1) dropped.
    @Test
    fun boundedLogTrimsOldestOverLimit() {
        assertEquals(listOf(2, 3, 4), BoundedLog.appendCapped(4, to = listOf(1, 2, 3), limit = 3))
    }

    // :117
    @Test
    fun boundedLogZeroLimitIsEmpty() {
        assertEquals(emptyList(), BoundedLog.appendCapped(1, to = listOf(1, 2), limit = 0))
    }
}
