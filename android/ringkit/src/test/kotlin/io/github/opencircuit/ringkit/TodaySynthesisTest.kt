package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.BaselineTrend.Direction
import io.github.opencircuit.ringkit.TodaySynthesis.Readiness
import io.github.opencircuit.ringkit.WellnessBalance.Tier
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Synthetic-only tests for the Today synthesis line. Asserts the exact sentences, because the copy IS
 * the behaviour: each rule, the rule precedence, and the honest degradations when data is missing,
 * stale, or readiness can't be scored.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/TodaySynthesisTests.swift
 * (@ 2d07e550, the last MIT commit to touch it): all 19 tests. The `// :n` lines cite each test.
 */
class TodaySynthesisTest {

    private val now: Instant = Instant.ofEpochSecond(1_780_000_000)
    private val fresh: Instant = now.minusSeconds(2 * 3600)

    private fun ago(seconds: Double): Instant = now.minusNanos((seconds * 1e9).toLong())

    /** Upstream's `say`; [newest] null is "no data at all" (upstream's `.some(nil)`). */
    private fun say(
        readiness: Readiness = Readiness.Pending,
        hrv: Direction? = null,
        rhr: Direction? = null,
        temp: Direction? = null,
        fever: Boolean = false,
        sleep: Int? = null,
        usualSleep: Double? = null,
        newest: Instant? = fresh,
    ): String = TodaySynthesis.sentence(
        TodaySynthesis.Input(
            readiness = readiness, hrv = hrv, restingHR = rhr, skinTemp = temp, feverSuspected = fever,
            lastNightSleepMinutes = sleep, usualSleepMinutes = usualSleep, newestDataAt = newest, now = now,
        ),
    )

    // MARK: Missing / stale data

    // :27
    @Test
    fun noDataAtAll() {
        assertEquals(
            "There's no data from your device yet, so connect and sync it to see today's summary.",
            say(Readiness.Scored(score = 90, tier = Tier.EXCELLENT, factorCount = 3), newest = null),
        )
    }

    // :32
    @Test
    fun staleDataOverridesEverything() {
        assertEquals(
            "Your newest data is over a day old, so sync your device to bring today's summary up to date.",
            say(Readiness.Scored(score = 90, tier = Tier.EXCELLENT, factorCount = 3), hrv = Direction.ABOVE, newest = ago(40.0 * 3600)),
        )
        assertEquals(
            "Your newest data is 3 days old, so sync your device to bring today's summary up to date.",
            say(newest = ago(3.5 * 86_400)),
        )
    }

    // :40
    @Test
    fun beyondTheLookbackIsNotCounted() {
        assertEquals(
            "Your newest data is more than two weeks old, so sync your device to bring today's summary up to date.",
            say(newest = ago(14.0 * 86_400)),
        )
    }

    // :45
    @Test
    fun thirtyFiveHoursIsNotStale() {
        assertEquals("Working out today's readiness.", say(newest = ago(35.0 * 3600)))
    }

    // MARK: Scored readiness

    // :51
    @Test
    fun excellentWithPositive() {
        assertEquals(
            "Readiness is excellent at 88, and HRV is above your usual.",
            say(Readiness.Scored(score = 88, tier = Tier.EXCELLENT, factorCount = 3), hrv = Direction.ABOVE, rhr = Direction.WITHIN),
        )
    }

    // :56
    @Test
    fun goodWithConcernUsesThough() {
        assertEquals(
            "Readiness is good at 74, though last night's sleep was short.",
            say(Readiness.Scored(score = 74, tier = Tier.GOOD, factorCount = 3), hrv = Direction.ABOVE, sleep = 320),
        )
    }

    // :61
    @Test
    fun goodSteady() {
        assertEquals(
            "Readiness is good at 74, with HRV and resting heart rate in your usual range.",
            say(Readiness.Scored(score = 74, tier = Tier.GOOD, factorCount = 2), hrv = Direction.WITHIN, rhr = Direction.WITHIN),
        )
        assertEquals(
            "Readiness is good at 74, with resting heart rate in your usual range.",
            say(Readiness.Scored(score = 74, tier = Tier.GOOD, factorCount = 2), rhr = Direction.WITHIN),
        )
    }

    // :68
    @Test
    fun goodWithNothingElseKnown() {
        assertEquals("Readiness is good at 70.", say(Readiness.Scored(score = 70, tier = Tier.GOOD, factorCount = 3)))
    }

    // :72
    @Test
    fun sleepAloneIsDisclosed() {
        assertEquals(
            "Readiness is good at 81 based on last night's sleep alone.",
            say(Readiness.Scored(score = 81, tier = Tier.GOOD, factorCount = 1)),
        )
    }

    // :77
    @Test
    fun needsImprovement() {
        assertEquals(
            "Readiness needs improvement at 52, and HRV is below your usual, so an easier day may help.",
            say(Readiness.Scored(score = 52, tier = Tier.NEEDS_IMPROVEMENT, factorCount = 3), hrv = Direction.BELOW),
        )
        assertEquals(
            "Readiness needs improvement at 52, so an easier day may help.",
            say(Readiness.Scored(score = 52, tier = Tier.NEEDS_IMPROVEMENT, factorCount = 3), hrv = Direction.ABOVE),
        )
    }

    // :84 — 400 min is over 6 h, but 80 min under a 480-min usual → short; 440 vs 480 is within the 60-min allowance.
    @Test
    fun shortNightRelativeToUsual() {
        assertEquals(
            "Readiness is good at 75, though last night's sleep was short.",
            say(Readiness.Scored(score = 75, tier = Tier.GOOD, factorCount = 3), sleep = 400, usualSleep = 480.0),
        )
        assertEquals(
            "Readiness is good at 75.",
            say(Readiness.Scored(score = 75, tier = Tier.GOOD, factorCount = 3), sleep = 440, usualSleep = 480.0),
        )
    }

    // :93
    @Test
    fun feverPairTakesPrecedence() {
        assertEquals(
            "Readiness is good at 80, but skin temperature and resting heart rate are both above your usual, so take today gently.",
            say(
                Readiness.Scored(score = 80, tier = Tier.GOOD, factorCount = 3),
                hrv = Direction.ABOVE, rhr = Direction.ABOVE, temp = Direction.ABOVE, fever = true,
            ),
        )
        assertEquals(
            "Skin temperature and resting heart rate are both above your usual, so take today gently.",
            say(rhr = Direction.ABOVE, temp = Direction.ABOVE, fever = true),
        )
    }

    // :102 — both tiles above their usual is NOT the fever pairing: only Vitals Status's own verdict is.
    @Test
    fun bothTilesAboveWithoutVitalsStatusFeverIsOrdinaryClauses() {
        assertEquals(
            "Readiness is good at 80, though resting heart rate is above your usual.",
            say(Readiness.Scored(score = 80, tier = Tier.GOOD, factorCount = 3), rhr = Direction.ABOVE, temp = Direction.ABOVE),
        )
        assertEquals("Resting heart rate is above your usual.", say(rhr = Direction.ABOVE, temp = Direction.ABOVE))
        assertEquals(
            "Last night's sleep hasn't synced yet, so there's no readiness score, but resting heart rate is above your usual.",
            say(Readiness.NoNight, rhr = Direction.ABOVE, temp = Direction.ABOVE),
        )
    }

    // MARK: Agreement with Vitals Status

    /** A daily series ending today, oldest first (upstream `trend`, :113). */
    private fun trend(values: List<Double>, floor: Double): BaselineTrend.Result? =
        BaselineTrend.evaluate(
            values.mapIndexed { i, v -> BaselineTrend.Point(date = now.plusSeconds((i - values.size + 1) * 86_400L), value = v) },
            minAbsoluteDelta = floor,
        )

    // :123 — +0.35 °C and +3 bpm put BOTH tiles above their usual, but the fever rule doesn't fire.
    @Test
    fun theFeverPairingFollowsVitalsStatusNotTheTiles() {
        val temp = trend(listOf(33.5, 33.5, 33.5, 33.5, 33.5, 33.85), floor = 0.3)
        val rhrPrior = listOf(58.0, 58.0, 58.0, 58.0, 58.0, 58.0, 58.0)
        val rhr = trend(rhrPrior + 61.0, floor = 2.0)
        assertEquals(Direction.ABOVE, temp?.direction)
        assertEquals(Direction.ABOVE, rhr?.direction)
        val noFever = VitalsBaseline.suspectedFever(restingHRToday = 61.0, restingHRPrior = rhrPrior, skinTempOffsetC = 0.35)
        assertFalse(noFever)
        val s = say(Readiness.Scored(score = 80, tier = Tier.GOOD, factorCount = 3), rhr = rhr?.direction, temp = temp?.direction, fever = noFever)
        assertEquals("Readiness is good at 80, though resting heart rate is above your usual.", s)

        val fever = VitalsBaseline.suspectedFever(restingHRToday = 67.0, restingHRPrior = rhrPrior, skinTempOffsetC = 1.2)
        assertTrue(fever)
        assertEquals(
            "Readiness is good at 80, but skin temperature and resting heart rate are both above your usual, so take today gently.",
            say(Readiness.Scored(score = 80, tier = Tier.GOOD, factorCount = 3), rhr = Direction.ABOVE, temp = Direction.ABOVE, fever = fever),
        )
    }

    // :144 — a resting-HR tile above while Vitals Status is normal is never escalated.
    @Test
    fun aRestingHRTileAboveWhileVitalsStatusIsNormalIsNeverEscalated() {
        val prior = listOf(58.0, 58.0, 59.0, 58.0, 57.0, 58.0, 58.0)
        assertEquals(Direction.ABOVE, trend(prior + 61.0, floor = 2.0)?.direction)
        assertEquals(VitalsBaseline.Severity.NORMAL, VitalsBaseline.classify(today = 61.0, prior = prior, vital = VitalsBaseline.Vital.RESTING_HR).severity)
        val readinesses = listOf(
            Readiness.Pending, Readiness.NoNight, Readiness.NoScore,
            Readiness.Scored(score = 80, tier = Tier.GOOD, factorCount = 3),
            Readiness.Scored(score = 40, tier = Tier.NEEDS_IMPROVEMENT, factorCount = 2),
        )
        for (r in readinesses) {
            for (t in listOf(null, Direction.WITHIN, Direction.ABOVE)) {
                val s = say(r, rhr = Direction.ABOVE, temp = t)
                assertFalse(s.contains("gently"), s)
                assertFalse(s.contains("both above"), s)
            }
        }
    }

    // MARK: No readiness

    // :162
    @Test
    fun noNightSaysSyncHasntHappened() {
        assertEquals("Last night's sleep hasn't synced yet, so there's no readiness score.", say(Readiness.NoNight))
        assertEquals(
            "Last night's sleep hasn't synced yet, so there's no readiness score, but HRV is below your usual.",
            say(Readiness.NoNight, hrv = Direction.BELOW),
        )
    }

    // :168
    @Test
    fun noScoreNeverSuggestsSync() {
        val s = say(Readiness.NoScore, hrv = Direction.WITHIN)
        assertEquals("Last night has no sleep score, so there's no readiness today, but HRV is in your usual range.", s)
        assertFalse(s.contains("sync", ignoreCase = true))
    }

    // :174
    @Test
    fun pendingFallsBackToVitals() {
        assertEquals("HRV is above your usual.", say(hrv = Direction.ABOVE))
        assertEquals("HRV and resting heart rate are in your usual range.", say(hrv = Direction.WITHIN, rhr = Direction.WITHIN))
        assertEquals("Working out today's readiness.", say())
    }

    // :180
    @Test
    fun everySentenceIsOneSentence() {
        val readinesses = listOf(
            Readiness.Pending, Readiness.NoNight, Readiness.NoScore,
            Readiness.Scored(score = 90, tier = Tier.EXCELLENT, factorCount = 3),
            Readiness.Scored(score = 70, tier = Tier.GOOD, factorCount = 1),
            Readiness.Scored(score = 40, tier = Tier.NEEDS_IMPROVEMENT, factorCount = 2),
        )
        val dirs = listOf(null, Direction.ABOVE, Direction.WITHIN, Direction.BELOW)
        for (r in readinesses) for (h in dirs) for (rh in dirs) for (t in dirs) for (f in listOf(false, true)) {
            val s = say(r, hrv = h, rhr = rh, temp = t, fever = f, sleep = 300)
            assertTrue(s.endsWith("."), s)
            assertEquals(1, s.count { it == '.' }, s)
            assertEquals(s.first().uppercase(), s.first().toString(), s)
        }
    }
}
