package io.github.opencircuit.app

import io.github.opencircuit.app.live.LiveMeasureState
import io.github.opencircuit.app.live.LiveMode
import io.github.opencircuit.app.live.MeasureFailure
import io.github.opencircuit.app.live.MeasureResult
import io.github.opencircuit.app.live.MeasureEvidence
import io.github.opencircuit.app.today.TodayInput
import io.github.opencircuit.app.today.TodayPresenter
import io.github.opencircuit.app.today.TodaySession
import io.github.opencircuit.app.today.TodayStored
import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.SyncLogEntry
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hero while the ring is live: the stored reading with "Live heart rate after sync" while a sync
 * runs; warming up once the automatic measure starts (the stored number stays until the ring locks
 * on); the live number on lock. SpO₂ is measured on a tap: Measure is off with the reason while a
 * sync runs or the ring is not connected, and becomes Stop while it measures.
 */
class TodayHeroLiveTest {

    private val now: Instant = Instant.parse("2026-10-21T16:00:00Z")
    private val stored = TodayStored(
        heartRate = QuantitySample(MetricKind.HEART_RATE, Instant.parse("2026-10-21T13:00:00Z"), value = 62.0),
        spo2 = QuantitySample(MetricKind.SPO2, Instant.parse("2026-10-21T06:00:00Z"), value = 0.97),
    )
    private val log = listOf(SyncLogEntry(startedAt = now.minusSeconds(3_660), finishedAt = now.minusSeconds(3_600), outcome = "COMPLETE"))

    private fun today(
        live: LiveMeasureState = LiveMeasureState(),
        syncing: Boolean = false,
        link: LinkState? = LinkState.Authenticated,
    ) = TodayPresenter.present(TodayInput(stored, TodaySession(link = link, log = log, syncing = syncing, live = live), now, ZoneOffset.UTC))

    @Test
    fun whileASyncRunsTheHeroShowsTheStoredReadingAndSaysLiveComesAfter() {
        val ui = today(syncing = true)
        assertEquals("62", ui.heartRate.value)
        assertEquals("as of 13:00 · 3 h ago", ui.heartRate.note)
        assertEquals("Live heart rate after sync", ui.heartRate.status)
        assertFalse(ui.heartRateLive)
    }

    @Test
    fun liveModeWarmsUpThenShowsTheNumberOnLock() {
        val warming = today(live = LiveMeasureState(mode = LiveMode.HEART_RATE, auto = true, warmingUp = true))
        assertTrue(warming.heartRateLive, "in live mode as soon as the measure starts")
        assertEquals("62", warming.heartRate.value, "the stored number stays until the ring locks on")
        assertEquals("Live · warming up", warming.heartRate.status)

        val locked = today(live = LiveMeasureState(mode = LiveMode.HEART_RATE, auto = true, newest = 71))
        assertTrue(locked.heartRateLive)
        assertEquals("71", locked.heartRate.value)
        assertEquals("Live now", locked.heartRate.note)
        assertNull(locked.heartRate.status)

        val stale = today(live = LiveMeasureState(mode = LiveMode.HEART_RATE, newest = 71, stale = true))
        assertEquals("Live · no signal from the ring", stale.heartRate.status)
    }

    @Test
    fun anSpO2MeasureShowsItsValueAndAStopButton() {
        val measuring = today(live = LiveMeasureState(mode = LiveMode.SPO2, newest = 96))
        assertEquals("96", measuring.spo2.value)
        assertEquals("Measuring · keep your hand still", measuring.spo2.status)
        assertEquals("Stop", measuring.spo2Measure.label)
        assertTrue(measuring.spo2Measure.enabled)
        assertTrue(measuring.spo2Measure.stops)
        assertFalse(measuring.heartRateLive, "one mode at a time on the ring")
        assertEquals("62", measuring.heartRate.value)
    }

    @Test
    fun measureIsOffWithItsReasonWhileSyncingOrNotConnected() {
        val ready = today()
        assertEquals("Measure", ready.spo2Measure.label)
        assertTrue(ready.spo2Measure.enabled)
        assertNull(ready.spo2Measure.reason)

        val syncing = today(syncing = true)
        assertFalse(syncing.spo2Measure.enabled)
        assertEquals("Available after sync", syncing.spo2Measure.reason)

        for (link in listOf(LinkState.Idle, LinkState.Connecting, null)) {
            val off = today(link = link)
            assertFalse(off.spo2Measure.enabled, "link $link")
            assertEquals("Connect the ring to measure", off.spo2Measure.reason)
        }
    }

    @Test
    fun aFailedSpO2MeasureSaysSoUntilTheNextOne() {
        val failed = today(live = LiveMeasureState(spo2 = MeasureResult(failure = MeasureFailure.NoReading(MeasureEvidence(LiveMode.SPO2)))))
        assertEquals("No reading — keep the ring snug and try again", failed.spo2.status)
        assertEquals("97", failed.spo2.value, "the stored reading stays")
        assertNull(today().spo2.status)
    }
}
