package io.github.opencircuit.app.today

import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.GoalHistory
import io.github.opencircuit.ringkit.HRSample
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.WellnessBalance
import io.github.opencircuit.store.DayActivityInputs
import io.github.opencircuit.store.SyncLogEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Today's tiles, readiness ring and synthesis line, from the stored window through the presenter:
 * what the screen is told to show. Synthetic, ring-shaped values (SpO₂ as a fraction).
 */
class TodayDashboardTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Instant = Instant.parse("2026-09-30T10:00:00Z")
    private fun day(offset: Int): Instant = LocalDate.ofInstant(now, zone).plusDays(offset.toLong()).atStartOfDay(zone).toInstant()

    private fun present(trends: TodayTrends?, log: List<SyncLogEntry> = emptyList()) = TodayPresenter.present(
        TodayInput(trends?.let { TodayStored(trends = it) }, TodaySession(link = LinkState.Authenticated, log = log), now, zone),
    )

    /**
     * Six nights ending last night, built on the raw path (stored nights and HRV readings through the
     * rollup): HRV 58 after a flat 50, and last night's score and stress as the store keeps them (0 =
     * not computed). Without [lastNight] the newest night is not stored yet.
     */
    private fun week(sleepScore: Int = 82, stress: Int = 34, lastNight: Boolean = true): TodayTrends {
        val offsets = if (lastNight) (-5..0) else (-5..-1)
        val nights = offsets.map { o ->
            val wake = day(o).plusSeconds(6 * 3_600L)
            TodayNight(day(o), 420, if (o == 0) sleepScore else 70, if (o == 0) stress else 40, 0.0, wake.minusSeconds(8 * 3_600L), wake)
        }
        val hrv = offsets.map { o -> QuantitySample(kind = MetricKind.HRV_SDNN, start = day(o).plusSeconds(2 * 3_600L), value = if (o == 0) 58.0 else 50.0) }
        val hr = listOf(QuantitySample(kind = MetricKind.HEART_RATE, start = now.minusSeconds(3_600), value = 64.0))
        return TodayTrends.of(TodayWindowInputs(nights = nights, hrv = hrv, hr = hr), zone)
    }

    @Test
    fun eightTilesInOrderEachWithItsValueVerdictBandAndSpokenLabel() {
        val state = present(week())
        assertEquals(TodayTile.Metric.entries.toList(), state.tiles.map { it.metric })
        val hrv = state.tiles.single { it.metric == TodayTile.Metric.HRV }
        assertEquals("58", hrv.value)
        assertEquals("ms", hrv.unit)
        assertEquals("last night", hrv.freshness)
        assertEquals("+8 ms vs usual", hrv.delta)
        assertEquals(TileDirection.UP, hrv.direction)
        assertEquals("Usual 47–53 ms", hrv.usual)
        assertNotNull(hrv.chart.band, "the usual band is drawn")
        assertNotNull(hrv.chart.newest, "last night is the accent dot")
        assertTrue(hrv.description.startsWith("HRV. 58 milliseconds, overnight avg."), hrv.description)
        assertTrue(hrv.description.contains("above your usual range of 47 to 53 milliseconds"), hrv.description)
        val spo2 = state.tiles.single { it.metric == TodayTile.Metric.SPO2 }
        assertNull(spo2.value, "no SpO₂ stored: no number, never 0")
        assertEquals("No data yet", spo2.usual)
    }

    @Test
    fun readinessFromLastNightsSleepAndRecovery() {
        val r = assertNotNull(present(week()).readiness)
        // Sleep 0.82 × 0.40 and recovery (90 − 34) / 75 × 0.25, renormalised over 0.65 → 79.
        assertEquals(79, r.score)
        assertEquals(WellnessBalance.Tier.GOOD, r.tier)
        assertEquals("Good", r.tierWord)
        assertEquals("Readiness", r.heading)
        assertEquals(0.79f, r.fraction, 1e-6f)
        assertEquals(listOf(FactorUi("Sleep", 82), FactorUi("Overnight recovery", 75)), r.factors)
        assertNull(r.empty)
        assertEquals("Readiness, estimate, 79 out of 100, Good. Sleep 82, overnight recovery 75", r.description)
    }

    @Test
    fun aScoreFromSleepAloneSaysSo() {
        val r = assertNotNull(present(week(stress = 0)).readiness)
        assertEquals(82, r.score)
        assertEquals("Based on last night's sleep alone", r.note)
        assertTrue(r.description.endsWith("Based on last night's sleep alone"), r.description)
    }

    @Test
    fun noNightForTodayMeansNoReadinessAndTheLineSaysWhy() {
        val state = present(week(lastNight = false), log = listOf(syncedAt(now.minusSeconds(600))))
        val r = assertNotNull(state.readiness)
        assertNull(r.score)
        assertEquals("Last night's sleep hasn't synced yet. Readiness appears once it does.", r.empty)
        assertTrue(state.synthesis!!.startsWith("Last night's sleep hasn't synced yet, so there's no readiness score"), state.synthesis)
    }

    @Test
    fun aNightWithoutAScoreIsNotANightThatHasNotSynced() {
        val r = assertNotNull(present(week(sleepScore = 0)).readiness)
        assertNull(r.score)
        assertEquals("Last night has no sleep score, so there's no readiness today.", r.empty)
    }

    @Test
    fun theSynthesisLeadsWithReadinessAndTheDateLineIsInTheZone() {
        val state = present(week(), log = listOf(syncedAt(now.minusSeconds(600))))
        assertTrue(state.synthesis!!.startsWith("Readiness is good at 79"), state.synthesis)
        assertEquals("Wednesday 30 September", state.dateLine)
    }

    @Test
    fun aFreshInstallHasDesignedEmptyStatesNotZeros() {
        val state = present(TodayTrends())
        assertEquals("There's no data from your device yet, so connect and sync it to see today's summary.", state.synthesis)
        assertEquals("Readiness needs one night of sleep with the ring on. Wear it tonight and it appears tomorrow morning.", state.readiness?.empty)
        assertTrue(state.tiles.all { it.value == null })
        assertEquals("Steps not recorded", state.tiles.single { it.metric == TodayTile.Metric.STEPS }.usual)
    }

    /**
     * Before the sync log has been read (or when reading it failed) an empty log proves nothing: an
     * empty window then claims neither "never synced" (readiness) nor "no data yet" (the sentence).
     */
    @Test
    fun anUnreadSyncLogIsNotANeverSyncedRing() {
        val state = TodayPresenter.present(
            TodayInput(TodayStored(trends = TodayTrends()), TodaySession(link = LinkState.Authenticated, logLoaded = false), now, zone),
        )
        assertEquals("Last night's sleep hasn't synced yet. Readiness appears once it does.", state.readiness?.empty)
        assertNull(state.synthesis, "no sentence until the log says whether the ring ever synced")
    }

    @Test
    fun beforeTheFirstLoadThereAreNoTilesReadinessOrSentence() {
        val state = present(null)
        assertTrue(state.loading)
        assertEquals(emptyList(), state.tiles)
        assertNull(state.readiness)
        assertNull(state.synthesis)
    }

    private fun syncedAt(t: Instant) = SyncLogEntry(startedAt = t.minusSeconds(60), finishedAt = t, outcome = "COMPLETE")

    // Readiness's activity factor, as upstream's readiness card adds it (WellnessBalanceCardView.swift
    // :194-249 @ 2d07e550): the day's Activity Score over its calorie estimate and the goals, joining
    // only when it is above 0, and never making a readiness without a scored night.

    private val today: LocalDate = LocalDate.ofInstant(now, zone)

    private fun activity(steps: Long?, hr: List<HRSample> = emptyList()) = DayActivityInputs(today, hr, steps, emptyList(), null)

    /** Calorie and minute goals off, so the Activity Score is the step attainment alone: 6,000 of 6,400 → 94. */
    private val stepsOnly = GoalHistory.Goals(workdaySteps = 6_400, activeKcal = 0.0, activityMinutes = 0.0)

    @Test
    fun theDaysActivityJoinsReadinessAsItsThirdFactor() {
        val r = assertNotNull(TodayDashboard.present(week(), today, now, zone, lastSyncAt = null, activity = activity(6_000), goals = stepsOnly).readiness)
        // Sleep 0.82 × 0.40 + recovery (90 − 34) / 75 × 0.25 + activity 0.9375 × 0.15, over 0.80 → 82.
        assertEquals(82, r.score)
        assertEquals(listOf(FactorUi("Sleep", 82), FactorUi("Overnight recovery", 75), FactorUi("Activity", 94)), r.factors)
        assertNull(r.note)
        assertEquals("Readiness, estimate, 82 out of 100, Good. Sleep 82, overnight recovery 75, activity 94", r.description)
    }

    /** A fresh morning (no steps, resting heart rate only) scores activity 0: it does not drag readiness down. */
    @Test
    fun aDayWithNoActivityYetLeavesReadinessOnTheNight() {
        val resting = (0 until 6).map { HRSample(58, now.minusSeconds(3_600L - it * 300)) }
        val r = assertNotNull(TodayDashboard.present(week(), today, now, zone, lastSyncAt = null, activity = activity(null, resting), goals = stepsOnly).readiness)
        assertEquals(79, r.score)
        assertEquals(listOf("Sleep", "Overnight recovery"), r.factors.map { it.label })
    }

    @Test
    fun activityAloneNeverMakesAReadiness() {
        val r = assertNotNull(TodayDashboard.present(week(lastNight = false), today, now, zone, lastSyncAt = now, activity = activity(10_000), goals = stepsOnly).readiness)
        assertNull(r.score)
        assertEquals("Last night's sleep hasn't synced yet. Readiness appears once it does.", r.empty)
    }
}
