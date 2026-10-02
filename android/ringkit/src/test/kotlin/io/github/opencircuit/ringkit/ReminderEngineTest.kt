package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/ReminderEngineTests.swift (@ b1c2fdd),
 * 33 of 33, each test named after upstream's with its line.
 *
 * ZONE AND CLOCK. Upstream fixes a UTC calendar for the minute-of-day maths, and it stays here ([cal]):
 * the sedentary and bedtime rules take it as their required zone. That a port reads the zone it is
 * given — not UTC, not the machine's — is shown by `ReminderEngineHazardTest` (New York across both
 * 2026 clock changes) and `ReminderGuardTest` (foreign machine zones). Upstream's wear tests read the
 * device clock (`Date()`); here each takes the fixed instant [clock] — the wear rule reads no time of
 * day, so any instant bites, and a fixed one makes every run the same. `TimeInterval` arguments are
 * `Double` seconds.
 */
class ReminderEngineTest {

    // Calendar fixed to UTC so minute-of-day maths are locale-independent in CI.
    private val cal: ZoneId = ZoneOffset.UTC

    // A "now" that maps to 10:00 UTC (600 minutes since midnight) on 2024-01-15.
    private val now1000: Instant = Instant.parse("2024-01-15T10:00:00Z")

    // 22:45 UTC — outside the default 08:00–21:00 active window.
    private val now2245: Instant = Instant.parse("2024-01-15T22:45:00Z")

    /** Upstream's `Date()` in the wear tests, fixed. */
    private val clock: Instant = Instant.parse("2026-06-17T12:00:00Z")

    private fun t(base: Instant, seconds: Long): Instant = base.plusSeconds(seconds)

    private fun sedentary50() = SedentaryReminder(interval = 50 * 60.0, activeStartMinutes = 8 * 60, activeEndMinutes = 21 * 60)

    // MARK: - SedentaryReminder

    @Test
    fun testSedentaryFiresAfterInterval() { // :31
        val r = sedentary50()
        val last = t(now1000, -(51 * 60)) // 51 min ago
        assertTrue(r.shouldFire(lastActivityAt = last, now = now1000, zone = cal))
    }

    @Test
    fun testSedentaryDoesNotFireBeforeInterval() { // :37
        val r = sedentary50()
        val last = t(now1000, -(49 * 60)) // only 49 min ago
        assertFalse(r.shouldFire(lastActivityAt = last, now = now1000, zone = cal))
    }

    @Test
    fun testSedentaryDoesNotFireOutsideActiveHours() { // :43
        val r = sedentary50()
        val last = t(now2245, -(60 * 60)) // 1 h ago, but it's 22:45 now
        assertFalse(r.shouldFire(lastActivityAt = last, now = now2245, zone = cal))
    }

    @Test
    fun testSedentaryDoesNotFireWithNilLastActivity() { // :49
        val r = SedentaryReminder()
        assertFalse(r.shouldFire(lastActivityAt = null, now = now1000, zone = cal))
    }

    @Test
    fun testSedentaryDoesNotFireWithFreshActivityWithinInterval() { // :54
        // Once a foreground sync lands a fresh step delta, the gap is < interval → no nudge. The bug was
        // evaluating this rule PRE-sync against a stale `lastActivityAt` (gap ≥ interval); the app-layer
        // fix defers the evaluation to post-sync so `lastActivityAt` is fresh like this.
        val r = sedentary50()
        val fresh = t(now1000, -(5 * 60)) // moved 5 min ago
        assertFalse(r.shouldFire(lastActivityAt = fresh, now = now1000, zone = cal))
    }

    // MARK: - SedentaryReminder: a ring on the charger is not a user sitting still

    /**
     * The reported false positive. The ring has been on the charger for the last hour, so no step delta
     * has arrived and `lastActivityAt` is an hour stale — which is exactly what genuine inactivity looks
     * like. The live charging byte says otherwise.
     */
    @Test
    fun testSedentaryDoesNotFireWhileOnTheCharger() { // :68
        val r = sedentary50()
        val last = t(now1000, -(60 * 60))
        assertFalse(r.shouldFire(lastActivityAt = last, now = now1000, isOnCharger = true, zone = cal))
    }

    /**
     * The same stretch seen after the fact — the ring is back on the finger (or merely disconnected, so no
     * live byte), but the last thing we OBSERVED was it off the finger, five minutes ago. The unmeasured
     * stretch must not be charged to the user as stillness.
     */
    @Test
    fun testSedentaryDoesNotFireWhenTheRingWasOffTheFingerInsideTheWindow() { // :78
        val r = sedentary50()
        val last = t(now1000, -(60 * 60))
        assertFalse(r.shouldFire(lastActivityAt = last, now = now1000, lastOffFingerAt = t(now1000, -(5 * 60)), zone = cal))
    }

    /**
     * …and the suppression expires: once the ring has been back on the finger for a full interval, the
     * stillness has actually been measured and the nudge is earned again. This is the test that keeps the
     * fix from silently disabling the whole reminder for anyone who ever charges.
     */
    @Test
    fun testSedentaryFiresOnceTheRingHasBeenBackOnTheFingerForAFullInterval() { // :89
        val r = sedentary50()
        val last = t(now1000, -(3 * 3600))
        assertTrue(r.shouldFire(lastActivityAt = last, now = now1000, lastOffFingerAt = t(now1000, -(51 * 60)), zone = cal))
    }

    /**
     * A future-dated off-finger stamp (ring clock drift / a timezone change between the frame and this
     * pass) is "as fresh as possible", not "infinitely old" — clamped, so it suppresses.
     */
    @Test
    fun testSedentaryClampsAFutureDatedOffFingerStamp() { // :99
        val r = sedentary50()
        val last = t(now1000, -(3 * 3600))
        assertFalse(r.shouldFire(lastActivityAt = last, now = now1000, lastOffFingerAt = t(now1000, 30 * 60), zone = cal))
    }

    /**
     * The charge that happens with the LINK DOWN: no descriptor arrives to stamp either of the signals
     * above, and the first frame after the reconnect is warm and current — but we heard nothing at all
     * across the window, so we measured no stillness to complain about.
     */
    @Test
    fun testSedentaryDoesNotFireWhenTheRingWasSilentForTheWholeWindow() { // :110
        val r = sedentary50()
        val last = t(now1000, -(2 * 3600))
        assertFalse(r.shouldFire(lastActivityAt = last, now = now1000, lastRingDataAt = t(now1000, -(2 * 3600)), zone = cal))
    }

    /**
     * A ring that IS reporting — frames landing right up to now — and still no steps is the case the
     * reminder is for. The silence suppression must not extend to it.
     */
    @Test
    fun testSedentaryFiresWhenFramesAreArrivingButNoStepsAre() { // :120
        val r = sedentary50()
        val last = t(now1000, -(51 * 60))
        assertTrue(r.shouldFire(lastActivityAt = last, now = now1000, lastRingDataAt = t(now1000, -60), zone = cal))
    }

    /**
     * The new inputs are suppressions only — omitted (an old build's persisted state, or a session that has
     * seen no descriptor yet), the rule behaves exactly as it did before. In particular a nil
     * `lastRingDataAt` is "no information", not "silent forever".
     */
    @Test
    fun testSedentaryUnchangedWhenNoWearEvidenceIsAvailable() { // :131
        val r = sedentary50()
        val last = t(now1000, -(51 * 60))
        assertTrue(r.shouldFire(lastActivityAt = last, now = now1000, isOnCharger = false, lastOffFingerAt = null, lastRingDataAt = null, zone = cal))
    }

    // MARK: - WearReminder

    @Test
    fun testWearFiresAfterInterval() { // :141
        val r = WearReminder(noDataInterval = 20 * 60.0)
        val last = t(clock, -(21 * 60))
        assertTrue(r.shouldFire(lastRingDataAt = last, now = clock, everConnected = true))
    }

    @Test
    fun testWearDoesNotFireBeforeInterval() { // :147
        val r = WearReminder(noDataInterval = 20 * 60.0)
        val last = t(clock, -(19 * 60))
        assertFalse(r.shouldFire(lastRingDataAt = last, now = clock, everConnected = true))
    }

    @Test
    fun testWearDoesNotFireIfNeverConnected() { // :153
        val r = WearReminder(noDataInterval = 20 * 60.0)
        assertFalse(r.shouldFire(lastRingDataAt = null, now = clock, everConnected = false))
    }

    @Test
    fun testWearFiresWhenNilDataButEverConnected() { // :158
        val r = WearReminder(noDataInterval = 20 * 60.0)
        // nil lastRingDataAt + everConnected = true → fire (ring disappeared)
        assertTrue(r.shouldFire(lastRingDataAt = null, now = clock, everConnected = true))
    }

    // MARK: - WearReminder: silence is not evidence of not-wearing

    /**
     * The reported false positive. A tester's link drops for an hour while she is demonstrably wearing the
     * ring; the drain that follows carries worn epochs covering that hour. The reminder must not have
     * fired, and must not fire now.
     */
    @Test
    fun testWearDoesNotFireWhenDrainedEpochsProveTheRingWasWorn() { // :169
        val r = WearReminder(noDataInterval = 60 * 60.0)
        val now = clock
        assertFalse(r.shouldFire(lastRingDataAt = t(now, -90 * 60), now = now, everConnected = true, lastWornEvidenceAt = t(now, -10 * 60)))
    }

    /** …but worn evidence that is itself older than the silence window proves nothing about now. */
    @Test
    fun testWearStillFiresWhenTheWornEvidenceIsAlsoStale() { // :178
        val r = WearReminder(noDataInterval = 60 * 60.0)
        val now = clock
        assertTrue(r.shouldFire(lastRingDataAt = t(now, -3 * 3600), now = now, everConnected = true, lastWornEvidenceAt = t(now, -3 * 3600)))
    }

    /** "Put your ring back on" is the wrong instruction while the ring is connected. */
    @Test
    fun testWearDoesNotFireWhileConnected() { // :187
        val r = WearReminder(noDataInterval = 60 * 60.0)
        val now = clock
        assertFalse(r.shouldFire(lastRingDataAt = t(now, -3 * 3600), now = now, everConnected = true, isConnected = true))
        // …and the nil-data path is gated the same way, not just the interval path.
        assertFalse(r.shouldFire(lastRingDataAt = null, now = now, everConnected = true, isConnected = true))
    }

    /** The tester also got this overnight. A wear nag inside the user's own sleep schedule is never actionable. */
    @Test
    fun testWearDoesNotFireInsideTheSleepWindow() { // :199
        val r = WearReminder(noDataInterval = 60 * 60.0)
        val now = clock
        assertFalse(r.shouldFire(lastRingDataAt = t(now, -3 * 3600), now = now, everConnected = true, inSleepWindow = true))
        assertFalse(r.shouldFire(lastRingDataAt = null, now = now, everConnected = true, inSleepWindow = true))
    }

    /**
     * A genuinely removed ring — silent, disconnected, awake hours, and the newest worn epoch is older than
     * the silence window — still fires. The suppressions must not disable the feature.
     */
    @Test
    fun testWearStillFiresForAGenuinelyRemovedRing() { // :210
        val r = WearReminder(noDataInterval = 60 * 60.0)
        val now = clock
        assertTrue(
            r.shouldFire(
                lastRingDataAt = t(now, -2 * 3600), now = now, everConnected = true,
                lastWornEvidenceAt = t(now, -2 * 3600), isConnected = false, inSleepWindow = false,
            ),
        )
    }

    @Test
    fun testWearDefaultIntervalIsAnHour() { // :219
        assertEquals(60 * 60.0, WearReminder().noDataInterval)
    }

    // MARK: - WearReminder: a docked ring was detected, not lost

    /**
     * The charger false positive: the link has been down for 90 min, but the last frame we hold said the
     * ring was on the charger. "Ring not detected · put your ring back on" is both untrue and
     * un-actionable — the user is charging on purpose.
     */
    @Test
    fun testWearDoesNotFireWhenTheLastFrameSaidOnTheCharger() { // :228
        val r = WearReminder(noDataInterval = 60 * 60.0, chargerGrace = 4 * 3600.0)
        val now = clock
        assertFalse(r.shouldFire(lastRingDataAt = t(now, -90 * 60), now = now, everConnected = true, lastKnownOnCharger = true))
    }

    /**
     * The suppression is bounded, not absolute: a ring that came off the charger straight into a drawer
     * stops being "probably still charging" once the charge could long since have finished.
     */
    @Test
    fun testWearFiresOnceTheChargerEvidenceAgesPastTheGrace() { // :237
        val r = WearReminder(noDataInterval = 60 * 60.0, chargerGrace = 4 * 3600.0)
        val now = clock
        assertTrue(r.shouldFire(lastRingDataAt = t(now, -5 * 3600), now = now, everConnected = true, lastKnownOnCharger = true))
    }

    /**
     * A ring whose last frame showed it WORN and then went silent is the genuine case the rule exists for —
     * the charger suppression must not swallow it.
     */
    @Test
    fun testWearStillFiresWhenTheLastFrameDidNotSayCharger() { // :246
        val r = WearReminder(noDataInterval = 60 * 60.0, chargerGrace = 4 * 3600.0)
        val now = clock
        assertTrue(r.shouldFire(lastRingDataAt = t(now, -90 * 60), now = now, everConnected = true, lastKnownOnCharger = false))
    }

    /**
     * With no frame ever recorded there is no timestamp to age the charger evidence against, so the flag
     * cannot suppress: "ever paired, never heard from" stays a fire.
     */
    @Test
    fun testWearFiresWithNilDataEvenIfTheChargerFlagIsSet() { // :255
        val r = WearReminder(noDataInterval = 60 * 60.0, chargerGrace = 4 * 3600.0)
        assertTrue(r.shouldFire(lastRingDataAt = null, now = clock, everConnected = true, lastKnownOnCharger = true))
    }

    @Test
    fun testWearDefaultChargerGraceIsFourHours() { // :261
        assertEquals(4 * 3600.0, WearReminder().chargerGrace)
    }

    // MARK: - BedtimeReminder (normal window, no midnight wrap)

    // Bed at 23:00 (1380 min), minutesBefore = 30 → window is [22:30, 23:00).
    @Test
    fun testBedtimeFiresInsideWindow() { // :268
        val r = BedtimeReminder(minutesBefore = 30)
        // now = 22:45 (1365 min) — inside [1350, 1380)
        val now = now2245 // 22:45 UTC
        assertTrue(r.shouldFire(now = now, bedMinutes = 1380, wakeMinutes = 7 * 60, zone = cal))
    }

    @Test
    fun testBedtimeDoesNotFireOutsideWindow() { // :275
        val r = BedtimeReminder(minutesBefore = 30)
        // now = 10:00 — far from [22:30, 23:00)
        assertFalse(r.shouldFire(now = now1000, bedMinutes = 1380, wakeMinutes = 7 * 60, zone = cal))
    }

    @Test
    fun testBedtimeDoesNotFireWhenBedEqualsWake() { // :281
        val r = BedtimeReminder(minutesBefore = 30)
        assertFalse(r.shouldFire(now = now1000, bedMinutes = 600, wakeMinutes = 600, zone = cal))
    }

    // MARK: - BedtimeReminder (window wraps midnight)

    // Bed at 01:00 (60 min), minutesBefore = 30 → window is [00:30, 01:00).
    @Test
    fun testBedtimeWrapsAroundMidnightFires() { // :289
        val r = BedtimeReminder(minutesBefore = 30)
        // now = 00:45 (45 min) — inside wrapping window [30, 60)
        val now0045 = Instant.parse("2024-01-15T00:45:00Z")
        assertTrue(r.shouldFire(now = now0045, bedMinutes = 60, wakeMinutes = 7 * 60, zone = cal))
    }

    @Test
    fun testBedtimeWrapsAroundMidnightDoesNotFireOutside() { // :299
        val r = BedtimeReminder(minutesBefore = 30)
        // now = 10:00 — not inside [30, 60)
        assertFalse(r.shouldFire(now = now1000, bedMinutes = 60, wakeMinutes = 7 * 60, zone = cal))
    }

    // Bed = 23:45 (1425 min), minutesBefore = 30 → window [1395, 1425) = [23:15, 23:45)
    // Wraps? No, both are before midnight — purely same-day window.
    @Test
    fun testBedtimeNearMidnightSameDay() { // :307
        val r = BedtimeReminder(minutesBefore = 30)
        // now = 23:20 → 1400 min — inside [1395, 1425)
        val now2320 = Instant.parse("2024-01-15T23:20:00Z")
        assertTrue(r.shouldFire(now = now2320, bedMinutes = 1425, wakeMinutes = 7 * 60, zone = cal))
    }
}
