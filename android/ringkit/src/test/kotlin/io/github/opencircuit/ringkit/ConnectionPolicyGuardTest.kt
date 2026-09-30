package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.LiveMeasureOwnership.Action
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Kotlin-only guarantees for the connection-policy files: what Swift's value types gave upstream for
 * free, the decisions driven across repeated calls on one link state, the exact edges upstream's
 * tests leave unpinned, and every tuned constant pinned to the literal typed from upstream's source
 * (never read back from the Kotlin constant).
 */
class ConnectionPolicyGuardTest {

    private fun s(seconds: Long): Duration = Duration.ofSeconds(seconds)

    // MARK: ReconnectBackoff

    @Test
    fun theBackoffScheduleCannotBeChangedThroughACast() {
        @Suppress("UNCHECKED_CAST")
        val asMutable = ReconnectBackoff.DELAYS as MutableList<Duration>
        assertFailsWith<UnsupportedOperationException> { asMutable[2] = Duration.ZERO }
        assertEquals(listOf(s(1), s(5), s(30)), ReconnectBackoff.DELAYS)
    }

    @Test
    fun backgroundDelaysAreCappedAndForegroundDelaysAreNot() {
        val background = (0..5).map { ReconnectBackoff.delay(attempt = it, inBackground = true) }
        val foreground = (0..5).map { ReconnectBackoff.delay(attempt = it, inBackground = false) }
        assertEquals(listOf(s(0), s(1), s(5), s(8), s(8), s(8)), background)
        assertEquals(listOf(s(0), s(1), s(5), s(30), s(30), s(30)), foreground)
    }

    // MARK: RecorderStall

    @Test
    fun stallVerdictsCompareByValueAndFreshnessOutranksCharging() {
        val t = Instant.parse("2026-08-08T05:52:00Z")
        assertEquals(RecorderStall.Verdict.Stalled(t), RecorderStall.Verdict.Stalled(Instant.parse("2026-08-08T05:52:00Z")))
        assertNotEquals<RecorderStall.Verdict>(RecorderStall.Verdict.Stalled(t), RecorderStall.Verdict.Stalled(t.plusSeconds(1)))
        // A fresh head is Recording even on the charger; a nonsense negative drain count proves nothing.
        assertEquals(
            RecorderStall.Verdict.Recording,
            RecorderStall.verdict(newestEpochAt = t, completedDrainsSinceHeadMoved = 5, isCharging = true, now = t.plusSeconds(60)),
        )
        assertEquals(
            RecorderStall.Verdict.UnknownNotDrained,
            RecorderStall.verdict(newestEpochAt = t, completedDrainsSinceHeadMoved = -1, isCharging = false, now = t.plusSeconds(3 * 3600)),
        )
    }

    // MARK: LiveMeasureOwnership — one link driven through a sequence of requests

    /** The link state a live-measure session holds between requests, updated from each [Action]. */
    private class Link {
        var monitoring = false
        var userMeasuring = false
        var autoMeasuring = false
        var mode: String? = null

        fun request(mode: String, userInitiated: Boolean, workoutHolding: Boolean = false): Action {
            val action = LiveMeasureOwnership.decide(
                monitoring = monitoring,
                userInitiated = userInitiated,
                userMeasuring = userMeasuring,
                workoutHolding = workoutHolding,
                sameMode = this.mode == mode,
            )
            when (action) {
                is Action.Start -> { monitoring = true; userMeasuring = userInitiated; autoMeasuring = !userInitiated; this.mode = mode }
                Action.Takeover -> { userMeasuring = true; autoMeasuring = false; this.mode = mode }
                Action.Rearm, Action.Ignore -> Unit
                is Action.SwitchMode -> this.mode = mode
            }
            return action
        }

        fun autoStandsDownWhileWaitingFor(mode: String) =
            LiveMeasureOwnership.autoShouldStandDown(autoMeasuring, monitoring, this.mode == mode, calibrationCapturing = false)
    }

    @Test
    fun ownershipHoldsAcrossRepeatedRequestsOnOneLink() {
        val link = Link()
        assertEquals(Action.Start(clearStale = false), link.request("hr", userInitiated = false))
        assertFalse(link.autoStandsDownWhileWaitingFor("hr"))
        repeat(3) { assertEquals(Action.Ignore, link.request("hr", userInitiated = false), "auto refresh #$it") }
        assertFalse(link.autoStandsDownWhileWaitingFor("hr"), "repeated auto refreshes must not unseat the auto read")

        assertEquals(Action.Takeover, link.request("hr", userInitiated = true))
        assertTrue(link.autoStandsDownWhileWaitingFor("hr"), "the in-flight auto wait stands down after a takeover")
        repeat(3) { assertEquals(Action.Rearm, link.request("hr", userInitiated = true), "re-tap #$it never re-takes over") }
        assertEquals(Action.Ignore, link.request("hr", userInitiated = false))

        assertEquals(Action.SwitchMode(armDeadline = true), link.request("spo2", userInitiated = true))
        assertTrue(link.autoStandsDownWhileWaitingFor("hr"))

        link.monitoring = false
        assertEquals(Action.Start(clearStale = true), link.request("spo2", userInitiated = true))
    }

    @Test
    fun aWorkoutKeepsTheLinkThroughEveryRepeatedTap() {
        val link = Link()
        assertEquals(Action.Start(clearStale = false), link.request("hr", userInitiated = false, workoutHolding = true))
        repeat(5) {
            assertEquals(Action.Rearm, link.request("hr", userInitiated = true, workoutHolding = true), "tap #$it")
        }
        assertEquals(Action.SwitchMode(armDeadline = true), link.request("spo2", userInitiated = true, workoutHolding = true))
    }

    // MARK: AutoMeasureGate — a run of probe cycles on one counter

    @Test
    fun theProbeBacksOffAcrossAMissStreakAndRecoversOnALockOrAWarmReading() {
        val base = s(600)
        val cap = s(7200)
        var misses = 0
        fun cycle(locked: Boolean, tempC: Double? = null): Duration {
            misses = if (locked) 0 else misses + 1
            return AutoMeasureGate.interval(base = base, cap = cap, consecutiveNoLock = misses, rawSkinTempC = tempC)
        }
        val streak = (1..6).map { cycle(locked = false) }
        assertEquals(listOf(s(600), s(1200), s(2400), s(4800), s(7200), s(7200)), streak)
        assertEquals(s(600), cycle(locked = true), "a lock drops straight back to base")
        assertEquals(s(600), cycle(locked = false), "one miss after a lock is not yet not-worn")
        repeat(3) { cycle(locked = false) }
        assertEquals(s(600), cycle(locked = false, tempC = 31.5), "a warm reading mid-streak restores base")
        assertEquals(s(7200), cycle(locked = false, tempC = 21.0), "a cold reading keeps the streak's back-off")
    }

    // MARK: SyncObservability

    @Test
    fun alertRawValuesAndOrderAreUpstreams() {
        assertEquals(listOf("notSynced", "lowBattery", "healthAuthLost", "notRecording"), SyncAlert.entries.map { it.rawValue })
    }

    @Test
    fun aChangedPolicyIsACopyAndPoliciesCompareByValue() {
        val p = SyncAlertPolicy()
        val q = p.copy(lowBatteryThreshold = 20)
        assertEquals(15, p.lowBatteryThreshold)
        assertEquals(20, q.lowBatteryThreshold)
        assertEquals(SyncAlertPolicy(s(6 * 3600), 15, s(6 * 3600)), p)
        assertNotEquals(p, q)
    }

    @Test
    fun notRecordingFiresOnlyForAStalledRecorderAndComesLast() {
        val now = Instant.ofEpochSecond(1_000_000)
        val p = SyncAlertPolicy()
        fun fire(recording: EpochRecordingHealth.Status) = p.alertsToFire(
            now = now, lastSuccessfulSync = now.minusSeconds(7 * 3600), batteryPercent = 5,
            healthAuthorized = false, healthEverAuthorized = true, lastFired = emptyMap(), recording = recording,
        )
        val threeKnown = listOf(SyncAlert.NOT_SYNCED, SyncAlert.LOW_BATTERY, SyncAlert.HEALTH_AUTH_LOST)
        assertEquals(threeKnown, p.alertsToFire(now, now.minusSeconds(7 * 3600), 5, false, true, emptyMap()))
        assertEquals(threeKnown, fire(EpochRecordingHealth.Status.Recording))
        assertEquals(threeKnown, fire(EpochRecordingHealth.Status.Unknown))
        assertEquals(threeKnown + SyncAlert.NOT_RECORDING, fire(EpochRecordingHealth.Status.Stalled(now.minusSeconds(4 * 3600))))
    }

    @Test
    fun staleIsStrictlyPastTheThresholdAndReNotifyIsDueExactlyAtTheInterval() {
        val now = Instant.ofEpochSecond(1_000_000)
        val p = SyncAlertPolicy()
        fun active(lastSync: Instant) = p.activeConditions(now, lastSync, 80, true, true)
        assertFalse(SyncAlert.NOT_SYNCED in active(now.minusSeconds(6 * 3600)), "exactly 6 h is not yet stale")
        assertTrue(SyncAlert.NOT_SYNCED in active(now.minusSeconds(6 * 3600 + 1)))

        fun fired(lastFiredAgo: Long) =
            p.alertsToFire(now, now.minusSeconds(7 * 3600), 80, true, true, mapOf(SyncAlert.NOT_SYNCED to now.minusSeconds(lastFiredAgo)))
        assertEquals(listOf(SyncAlert.NOT_SYNCED), fired(6 * 3600), "exactly one interval later it re-fires")
        assertEquals(emptyList(), fired(6 * 3600 - 1))
    }

    @Test
    fun boundedLogNeverTouchesOrSharesItsInput() {
        val input = mutableListOf(1, 2, 3)
        val out = BoundedLog.appendCapped(4, to = input, limit = 10)
        assertEquals(listOf(1, 2, 3), input, "the input list changed")
        assertNotSame<List<Int>>(input, out)
        input += 99
        assertEquals(listOf(1, 2, 3, 4), out, "the output moved with the input")
        assertEquals(emptyList(), BoundedLog.appendCapped(1, to = listOf(1), limit = -1))
    }

    // MARK: StepAccumulator — the edges the upstream vectors leave open

    private val newYork = ZoneId.of("America/New_York")

    private fun ny(hhmmss: String): Instant = LocalDateTime.parse("2026-08-08T$hhmmss").atZone(newYork).toInstant()

    @Test
    fun theClearLagReachesBackExactlyOneHundredTwentySeconds() {
        fun window(sample: String) = StepAccumulator.windowStart(ny(sample), null, ny("00:00:00"), newYork)
        assertEquals(ny("17:00:00"), window("17:16:59"), "119 s after the boundary may still be the previous bucket")
        assertEquals(ny("17:15:00"), window("17:17:00"), "at exactly 120 s it no longer reaches back")
    }

    @Test
    fun reDeliveryResetAndDayChangeFoldAsUpstream() {
        // The same bucket reading delivered again and again adds nothing and never flags a roll.
        repeat(4) {
            assertEquals(StepUpdate(deltaToAdd = 0, isReset = false), StepAccumulator.update(previousRaw = 37, newRaw = 37, dayChanged = false))
        }
        // A counter reset credits the new bucket whole, and the climb after it only the increment.
        assertEquals(StepUpdate(12, true), StepAccumulator.update(previousRaw = 98, newRaw = 12, dayChanged = false))
        assertEquals(StepUpdate(8, false), StepAccumulator.update(previousRaw = 12, newRaw = 20, dayChanged = false))
        // Across a day change an EQUAL reading is still a new bucket: credited whole, not a roll.
        assertEquals(StepUpdate(50, false), StepAccumulator.update(previousRaw = 50, newRaw = 50, dayChanged = true))
    }

    // MARK: every tuned constant, pinned to the literal typed from upstream's source

    @Test
    fun everyConnectionPolicyConstantMatchesUpstreamsLiteral() {
        assertEquals(listOf(s(1), s(5), s(30)), ReconnectBackoff.DELAYS)                  // S/ReconnectBackoff.swift:18
        assertEquals(s(8), ReconnectBackoff.BACKGROUND_DELAY_CAP)                          // :33
        assertEquals(3, ReconnectBackoff.CALM_STATE_ATTEMPT_THRESHOLD)                     // :45
        assertEquals(s(30), KeepaliveCadence.interval(isNight = true, activeMeasurement = true, batterySaver = true))    // S/KeepaliveCadence.swift:18
        assertEquals(s(60), KeepaliveCadence.interval(isNight = true, activeMeasurement = false, batterySaver = false))  // :19
        assertEquals(s(90), KeepaliveCadence.interval(isNight = true, activeMeasurement = false, batterySaver = true))   // :19
        assertEquals(s(180), KeepaliveCadence.interval(isNight = false, activeMeasurement = false, batterySaver = false)) // :20
        assertEquals(s(300), KeepaliveCadence.interval(isNight = false, activeMeasurement = false, batterySaver = true))  // :20
        assertEquals(2, RecorderStall.MINIMUM_UNMOVED_DRAINS)                              // S/RecorderStall.swift:34
        assertEquals(s(2 * 3600), RecorderStall.STALE_AFTER)                               // :42
        assertEquals(2, AutoMeasureGate.NOT_WORN_AFTER_FAILURES)                           // S/AutoMeasureGate.swift:24
        assertEquals(4, AutoMeasureGate.MAX_BACKOFF_DOUBLINGS)                             // :27
        assertEquals(s(6 * 3600), SyncAlertPolicy().staleSyncThreshold)                    // S/SyncObservability.swift:35
        assertEquals(15, SyncAlertPolicy().lowBatteryThreshold)                            // :36
        assertEquals(s(6 * 3600), SyncAlertPolicy().renotifyInterval)                      // :37
        assertEquals(s(15 * 60), StepAccumulator.BUCKET)                                   // S/StepAccumulator.swift:82
        assertEquals(s(120), StepAccumulator.CLEAR_LAG_ALLOWANCE)                          // :88
    }
}
