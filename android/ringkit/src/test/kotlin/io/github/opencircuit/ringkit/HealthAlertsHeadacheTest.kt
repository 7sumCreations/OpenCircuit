package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.HeadacheSignals.Band
import io.github.opencircuit.ringkit.HeadacheSignals.DayInput
import io.github.opencircuit.ringkit.HeadacheSignals.Feature
import io.github.opencircuit.ringkit.HeadacheSignals.Series
import io.github.opencircuit.ringkit.HeadacheSignals.Suppression
import io.github.opencircuit.ringkit.HeadacheSignals.Tuning
import io.github.opencircuit.ringkit.HeadacheSignals.Verdict
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SYNTHETIC-ONLY tests for the morning overnight-signals notification: the per-DAY ledger, the hard
 * delivery window, quiet hours, suppression, the unlock floor, and the copy rule. No real health value
 * appears anywhere in this file.
 *
 * These prove the notification FIRES WHEN IT SHOULD AND SAYS ONLY WHAT WE MEASURED. Nothing here says
 * the index predicts anything — it cannot, and the copy test is precisely the assertion that the
 * shipped words never claim otherwise.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/HealthAlertsHeadacheTests.swift
 * (@ b1c2fdd), 22 of 22, each test named after upstream's with its line.
 *
 * ZONE. Upstream fixes a UTC calendar so "08:00" means 08:00 on any machine. Here one named zone is
 * fixed for the same reason, [zone] — Asia/Kolkata, UTC+5:30 all year — and both the instants and
 * every zone-taking call use it. It is chosen so the tests bite: a port that read UTC or the machine's
 * zone instead of the zone it is given would put 08:00 here (02:30 UTC) outside the 07:00–21:00
 * delivery window and 21:00 here (15:30 UTC) inside it.
 *
 * Swift's `Character.isNumber` is [isSwiftNumber] (a Unicode number category or a numeric ideograph —
 * not Kotlin's `isDigit`); Swift's `String.count` (characters) is read as code points, which is never
 * fewer; `lowercased()` is the locale-free `lowercase()`. Upstream's `(title, body)` tuple is
 * [HeadacheSignsNotifications.Text].
 */
class HealthAlertsHeadacheTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private val tuning = Tuning()

    /** 2026-07-[day] [hour]:[minute] in [zone]. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Instant = LocalDateTime.of(2026, 7, day, hour, minute).atZone(zone).toInstant()

    /** The shipped decision, with every gate defaulted to "would fire", so each test varies exactly one thing. */
    private fun candidates(
        enabled: Boolean = true,
        band: Band? = Band.FLAGGED,
        suppressedBy: Suppression? = null,
        frozenDayCount: Int = 30,
        retired: Boolean = false,
        now: Instant? = null,
        ledger: Map<HealthNotification, Long> = emptyMap(),
    ): List<HealthNotification> = HeadacheSignsNotifications.candidates(
        enabled = enabled, band = band, suppressedBy = suppressedBy, frozenDayCount = frozenDayCount, retired = retired,
        now = now ?: at(20, 8), lastNotifiedDay = ledger, tuning = tuning, zone = zone,
    )

    private fun dayKey(day: Int, hour: Int = 8): Long? = HeadacheSignsNotifications.dayKey(at(day, hour), zone)

    private val signs = listOf(HealthNotification.HEADACHE_SIGNS)

    // MARK: - The happy path

    @Test
    fun flaggedMorningRaisesTheCandidate() { // :49
        assertEquals(signs, candidates())
    }

    // MARK: - Per-DAY ledger

    /**
     * The load-bearing de-dupe. `evaluate` is polled several times an hour on every wake path, so the
     * verdict must survive repeated passes and fire exactly ONCE for the day it describes.
     */
    @Test
    fun firesAtMostOncePerCalendarDayAcrossRepeatedPasses() { // :57
        val ledger = mutableMapOf<HealthNotification, Long>()
        var fires = 0
        // Eleven passes across one morning-to-evening, as the wake paths would produce them.
        for (hour in listOf(7, 8, 9, 10, 11, 12, 13, 14, 16, 18, 20)) {
            val out = candidates(now = at(20, hour), ledger = ledger.toMap())
            if (HealthNotification.HEADACHE_SIGNS in out) {
                fires += 1
                ledger[HealthNotification.HEADACHE_SIGNS] = dayKey(20, hour) ?: fail("no day key") // what the app marks after a post
            }
        }
        assertEquals(1, fires, "a once-a-morning verdict must not re-fire after every sync")
        assertEquals(dayKey(20), ledger[HealthNotification.HEADACHE_SIGNS])
    }

    /** A fresh day re-arms it; a STALE ledger entry from an earlier day never blocks. */
    @Test
    fun aNewCalendarDayReArmsTheLedger() { // :73
        val ledger = mapOf(HealthNotification.HEADACHE_SIGNS to (dayKey(20) ?: fail("no day key")))
        assertEquals(emptyList(), candidates(now = at(20, 15), ledger = ledger))
        assertEquals(signs, candidates(now = at(21, 8), ledger = ledger))
    }

    /**
     * The ledger — not the shared 2 h anti-spam backoff — is what makes this once-a-morning. With only
     * the backoff, the same verdict would re-appear every couple of hours all day: exactly the bug the
     * temp flags hit and documented at `TempFeverNotifications.freshForNight`.
     */
    @Test
    fun theTwoHourBackoffAloneWouldNotDeDupeThisNotification() { // :82
        val morning = at(20, 8)
        val afternoon = at(20, 14) // 6 h later — the backoff has long expired
        val gate = NotificationGate()
        assertTrue(
            gate.shouldFire(HealthNotification.HEADACHE_SIGNS, afternoon, mapOf(HealthNotification.HEADACHE_SIGNS to morning), QuietHours(enabled = false), zone),
            "precondition: the rolling backoff would happily let it fire again",
        )
        assertEquals(
            emptyList(),
            candidates(now = afternoon, ledger = mapOf(HealthNotification.HEADACHE_SIGNS to (dayKey(20) ?: fail("no day key")))),
            "the per-day ledger is what actually stops the repeat",
        )
    }

    // MARK: - Band

    @Test
    fun typicalAndElevatedDaysNeverNotify() { // :96
        assertEquals(emptyList(), candidates(band = Band.TYPICAL))
        assertEquals(emptyList(), candidates(band = Band.ELEVATED))
        assertEquals(emptyList(), candidates(band = null), "no frozen row for today ⇒ nothing to report")
    }

    // MARK: - The unlock floor

    /**
     * The unlock is the NATURAL floor: below `minDaysForBanding` frozen days `HeadacheSignals.band`
     * cannot produce a band at all, so there is nothing to notify about.
     */
    @Test
    fun belowMinDaysForBandingNeverNotifies() { // :106
        assertEquals(emptyList(), candidates(frozenDayCount = 0))
        assertEquals(emptyList(), candidates(frozenDayCount = tuning.minDaysForBanding - 1))
        assertEquals(signs, candidates(frozenDayCount = tuning.minDaysForBanding))
    }

    /**
     * The floor and the band gate agree: at 20 prior indices the Kit's own banding refuses to return
     * `.flagged` no matter how extreme the index, so the notification gate is not inventing a second,
     * unrelated threshold.
     */
    @Test
    fun theUnlockFloorMatchesWhereBandingItselfBegins() { // :115
        val priors = List(tuning.minDaysForBanding - 1) { 0 }
        assertEquals(Band.TYPICAL, HeadacheSignals.band(index = 100, priorIndices = priors, tuning = tuning))
        assertEquals(Band.FLAGGED, HeadacheSignals.band(index = 100, priorIndices = priors + listOf(0), tuning = tuning))
    }

    // MARK: - Opt-out and auto-retire

    @Test
    fun disabledOrRetiredNeverNotifies() { // :123
        assertEquals(emptyList(), candidates(enabled = false))
        assertEquals(emptyList(), candidates(retired = true), "the quality monitor switched it off for this user")
    }

    // MARK: - Suppression

    /**
     * Suppression withholds the INTERRUPTION only. The score is still computed, still banded and still
     * shown — a suppressed day is not a missing day, and treating it as one would put a hole in the very
     * series the percentile budget is taken over.
     */
    @Test
    fun suppressionWithholdsTheAlertButNeverTheScore() { // :134
        val plain = (HeadacheSignals.assess(scoringInput()) as? Verdict.Scored)?.assessment
        val feverish = (HeadacheSignals.assess(scoringInput(fever = true)) as? Verdict.Scored)?.assessment
        val logged = (HeadacheSignals.assess(scoringInput(alreadyLogged = true)) as? Verdict.Scored)?.assessment
        if (plain == null || feverish == null || logged == null) fail("expected three scored days")

        assertTrue(plain.index > 0, "precondition: the fixture actually scores")
        assertEquals(plain.index, feverish.index, "fever must not move the number")
        assertEquals(plain.index, logged.index, "an already-logged headache must not move it either")
        assertEquals(plain.band, feverish.band)
        assertEquals(plain.band, logged.band)
        assertNull(plain.suppressedBy)
        assertEquals(Suppression.FEVER, feverish.suppressedBy)
        assertEquals(Suppression.HEADACHE_ALREADY_LOGGED, logged.suppressedBy)

        // Only the notification is withheld.
        assertEquals(emptyList(), candidates(suppressedBy = Suppression.FEVER))
        assertEquals(emptyList(), candidates(suppressedBy = Suppression.HEADACHE_ALREADY_LOGGED))
        assertEquals(signs, candidates(suppressedBy = null))
    }

    // MARK: - Delivery window + quiet hours

    /**
     * Quiet hours are a user PREFERENCE — they now ship enabled (22:00–07:00), but the user may switch
     * them off, and `QuietHours()` still defaults `enabled = false` for anyone constructing one directly.
     * This alert therefore carries its OWN hard window regardless: a summary of a night that is already
     * over has no business waking anyone at 04:00.
     */
    @Test
    fun hardDeliveryWindowKeepsItOutOfTheNight() { // :161
        assertEquals(emptyList(), candidates(now = at(20, 3)))
        assertEquals(emptyList(), candidates(now = at(20, 6, 59)))
        assertEquals(signs, candidates(now = at(20, 7)), "window opens at 07:00")
        assertEquals(signs, candidates(now = at(20, 20, 59)))
        assertEquals(emptyList(), candidates(now = at(20, 21)), "window closes at 21:00")
        assertEquals(emptyList(), candidates(now = at(20, 23, 30)))
    }

    /** A verdict held back by the window is NOT lost: the ledger is still fresh, so the next evaluate pass inside the window delivers it. */
    @Test
    fun aVerdictHeldByTheWindowStillFiresLater() { // :172
        assertEquals(emptyList(), candidates(now = at(20, 6)))
        assertEquals(signs, candidates(now = at(20, 7, 30)))
    }

    /**
     * The user's own quiet hours still apply on top, through the ONE shared gate — including a window
     * that overlaps the morning (someone who mutes 08:00–09:00).
     */
    @Test
    fun userQuietHoursSuppressTheSurvivor() { // :179
        val now = at(20, 8, 30)
        val quiet = QuietHours(enabled = true, startMinutes = 8 * 60, endMinutes = 9 * 60)
        val gate = NotificationGate()
        assertEquals(signs, candidates(now = now), "precondition: it is a candidate")
        assertEquals(emptyList(), gate.filter(candidates(now = now), now, emptyMap(), quiet, zone))
        // Outside the muted hour it survives the same gate.
        val later = at(20, 9, 30)
        assertEquals(signs, gate.filter(candidates(now = later), later, emptyMap(), quiet, zone))
    }

    // MARK: - Copy

    /**
     * THE COPY RULE. The notification reports what we MEASURED; it never forecasts. The word "headache"
     * must not appear at all — the user opted into a feature by that name, so the context is already
     * theirs, and putting it in the alert turns a true measurement into a false prediction that is wrong
     * about three times in four.
     */
    @Test
    fun copyIsAMeasurementNeverAForecast() { // :198
        val variants = listOf(
            HeadacheSignsNotifications.copy(listOf(Feature.RESTING_HR_DEVIATION, Feature.SLEEP_EFFICIENCY_DROP)),
            HeadacheSignsNotifications.copy(listOf(Feature.HRV_DEVIATION)),
            HeadacheSignsNotifications.copy(emptyList()),
        )
        val banned = listOf("headache", "risk", "predict", "likely", "warning", "probab", "chance", "score", "%", "will ", "may get", "expect", "forecast that")
        for ((title, body) in variants) {
            val text = "$title $body".lowercase()
            for (word in banned) assertFalse(word in text, "banned copy '$word' in: $title / $body")
            assertFalse(title.containsSwiftNumber(), "no number belongs in the title")
            assertFalse(body.containsSwiftNumber(), "no probability, percentage or score")
            assertTrue("estimate" in text, "every sensor-derived alert says so")
        }
    }

    @Test
    fun copyNamesTheSignalsInPlainWords() { // :217
        val (title, body) = HeadacheSignsNotifications.copy(listOf(Feature.RESTING_HR_DEVIATION, Feature.SLEEP_EFFICIENCY_DROP))
        assertEquals("Last night was unusual for you", title)
        assertTrue(body.startsWith("Resting heart rate and sleep efficiency drifted furthest"), "got: $body")
        // The analytic vocabulary never reaches the user.
        assertFalse("z-score" in body.lowercase())
        assertFalse("arousal" in body.lowercase())
        assertFalse("letdown" in body.lowercase())
    }

    /**
     * A row whose per-feature detail is unreadable still produces a TRUE sentence — just a less specific
     * one. Naming a feature we cannot evidence would be worse than being vague.
     */
    @Test
    fun copyFallsBackWhenNoSignalIsLegible() { // :231
        val (_, body) = HeadacheSignsNotifications.copy(emptyList())
        assertTrue(body.startsWith("Several of your overnight signals drifted"), "got: $body")
    }

    /**
     * THE TIMEFRAME FOLLOWS WHAT WAS NAMED. `arousalLetdown` is the one DAYTIME term — a fall in waking
     * heart rate from the day before yesterday to yesterday — so a body that names it must not file it
     * under "last night".
     */
    @Test
    fun theTimeframeFollowsTheNamedSignals() { // :240
        // The daytime term alone: no nightly claim anywhere in the body.
        val alone = HeadacheSignsNotifications.copy(listOf(Feature.AROUSAL_LETDOWN)).body
        assertFalse("last night" in alone.lowercase(), "got: $alone")
        assertTrue("over the past two days" in alone, "got: $alone")

        // A mixed pair, in EITHER ranking order: each signal carries its own period, and the nightly one
        // is still described as nightly.
        for (pair in listOf(listOf(Feature.AROUSAL_LETDOWN, Feature.SLEEP_EFFICIENCY_DROP), listOf(Feature.SLEEP_EFFICIENCY_DROP, Feature.AROUSAL_LETDOWN))) {
            val body = HeadacheSignsNotifications.copy(pair).body
            assertTrue("daytime heart rate over the past two days" in body.lowercase(), "got: $body")
            assertTrue("sleep efficiency last night" in body.lowercase(), "got: $body")
        }

        // Two nightly signals keep the compact single-suffix sentence they always had.
        val nightly = HeadacheSignsNotifications.copy(listOf(Feature.HRV_DEVIATION, Feature.RESTING_HR_DEVIATION)).body
        assertTrue(
            nightly.startsWith("Heart rate variability and resting heart rate drifted " + "furthest from your usual range last night"),
            "got: $nightly",
        )

        // Every nightly feature says so; the daytime one never does. Guards a future feature being added
        // without a decision about which period it belongs to.
        for (feature in Feature.entries) {
            val body = HeadacheSignsNotifications.copy(listOf(feature)).body
            assertEquals(feature != Feature.AROUSAL_LETDOWN, "last night" in body, "$feature is described over the wrong period: $body")
        }
    }

    /** A notification body that wraps to four lines is its own failure, so the honest per-signal timeframe must not have bought accuracy with length. */
    @Test
    fun everyBodyStaysShortEnoughToRead() { // :274
        val pairs = mutableListOf<List<Feature>>(emptyList())
        for (a in Feature.entries) {
            pairs += listOf(a)
            for (b in Feature.entries) if (b != a) pairs += listOf(a, b)
        }
        for (signals in pairs) {
            val body = HeadacheSignsNotifications.copy(signals).body
            assertTrue(body.codePointCount(0, body.length) <= 200, "too long for a notification: $body")
        }
    }

    // MARK: - Which signals get named

    @Test
    fun topSignalsRanksByWeightedShareAndExcludesTheCalendarLookup() { // :288
        val weighted = mapOf(
            Feature.RESTING_HR_DEVIATION to 0.14,
            Feature.SLEEP_EFFICIENCY_DROP to 0.18,
            Feature.SKIN_TEMP_DEVIATION to 0.08,
            Feature.PERIMENSTRUAL to 0.20, // heaviest, and deliberately never named
        )
        assertEquals(listOf(Feature.SLEEP_EFFICIENCY_DROP, Feature.RESTING_HR_DEVIATION), HeadacheSignsNotifications.topSignals(weighted))
    }

    /** A feature that contributed nothing is not "what drifted furthest" — it must not be named. */
    @Test
    fun topSignalsDropsZeroContributors() { // :300
        assertEquals(listOf(Feature.SCHEDULE_SHIFT), HeadacheSignsNotifications.topSignals(mapOf(Feature.HRV_DEVIATION to 0.0, Feature.SCHEDULE_SHIFT to 0.08)))
        assertEquals(emptyList(), HeadacheSignsNotifications.topSignals(emptyMap()))
    }

    /** Equal shares break by declaration order, so the same morning always words itself the same way rather than shuffling with the map's order. */
    @Test
    fun topSignalsTieBreakIsDeterministic() { // :309
        val weighted = mapOf(Feature.SKIN_TEMP_DEVIATION to 0.1, Feature.HRV_DEVIATION to 0.1, Feature.SCHEDULE_SHIFT to 0.1)
        repeat(25) {
            assertEquals(listOf(Feature.HRV_DEVIATION, Feature.SCHEDULE_SHIFT, Feature.SKIN_TEMP_DEVIATION), HeadacheSignsNotifications.topSignals(weighted, limit = 3))
        }
    }

    // MARK: - Membership / keys

    @Test
    fun notificationSetAndCategoryAreRingFenced() { // :321
        assertEquals(setOf(HealthNotification.HEADACHE_SIGNS), HeadacheSignsNotifications.NOTIFICATION_SET)
        assertFalse(
            HealthNotification.HEADACHE_SIGNS in TempFeverNotifications.NOTIFICATION_SET,
            "must not join the temp family's per-night ledger / disclaimer branch",
        )
        assertEquals("headache.signs", HeadacheSignsNotifications.CATEGORY_IDENTIFIER)
    }

    /** One implementation of the timezone-stable day key, shared with the temperature night ledger. */
    @Test
    fun dayKeyMatchesTheOneSharedImplementation() { // :329
        assertEquals(20_260_720L, HeadacheSignsNotifications.dayKey(at(20, 8), zone))
        assertEquals(TempFeverNotifications.dayKey(at(20, 20), zone), HeadacheSignsNotifications.dayKey(at(20, 8), zone))
    }

    // MARK: - Fixture

    /**
     * One synthetic day that scores: every feature sits exactly on a flat baseline except sleep
     * efficiency, which is 20 %-pt below it. Hand-computable — flat priors mean 1.4826·MAD == 0, so the
     * divisor is the feature's own noise floor.
     */
    private fun scoringInput(fever: Boolean = false, alreadyLogged: Boolean = false): DayInput {
        fun flat(value: Double): List<Double> = List(14) { value }
        val day = at(20, 0)
        val now = at(20, 8)
        return DayInput(
            day = day,
            now = now,
            lastRingDataAt = now.minusSeconds(3_600),
            restingHR = Series(today = 60.0, prior = flat(60.0)),
            hrvSDNN = Series(today = 50.0, prior = flat(50.0)),
            sleepEfficiencyPct = Series(today = 70.0, prior = flat(90.0)),
            sleepFragmentationMin = Series(today = 40.0, prior = flat(40.0)),
            sleepDurationMin = Series(today = 420.0, prior = flat(420.0)),
            skinTempOffsetC = 0.0,
            inBedStartMinutes = 23 * 60,
            priorInBedStartMinutes = List(14) { 23 * 60 },
            dayHRPrevious = 70.0,
            dayHRTwoDaysAgo = 70.0,
            dayHRPrior = flat(70.0),
            isPerimenstrual = false,
            sleepLikelyTruncated = false,
            feverSuspected = fever,
            headacheAlreadyLoggedToday = alreadyLogged,
            priorIndices = emptyList(),
        )
    }
}
