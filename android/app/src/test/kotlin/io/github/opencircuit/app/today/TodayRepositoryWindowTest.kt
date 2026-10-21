package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepNightExtras
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Today's window read through the real store: the two weeks ending on the day asked for, plus the
 * evening before its first day (that night's in-bed window starts there) — nothing older, so the
 * read does not grow with the history kept. Ring-shaped synthetic data: hourly daytime heart rate,
 * overnight HRV and SpO₂ as a fraction, a daily step total, one stored night.
 */
class TodayRepositoryWindowTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.parse("2026-09-30")
    private fun at(d: LocalDate, hour: Int): Instant = d.atStartOfDay(zone).plusHours(hour.toLong()).toInstant()

    @Test
    fun theLoadReadsTheTwoWeekWindowAndNothingOlder() = runBlocking {
        val db = StoreFactory.openInMemory()
        try {
            val store = LocalStore(db)
            val now = at(today, 10)
            val samples = (0 until 30).flatMap { back ->
                val d = today.minusDays(back.toLong())
                (8..20).map { h -> QuantitySample(kind = MetricKind.HEART_RATE, start = at(d, h), value = 60.0 + back % 7) } +
                    QuantitySample(kind = MetricKind.HRV_SDNN, start = at(d, 2), value = 40.0 + back % 5) +
                    QuantitySample(kind = MetricKind.SPO2, start = at(d, 3), value = 0.97)
            }
            store.ingest(samples, now, zone)
            for (back in 0 until 30) store.addDailySteps(1_000L + back, at(today.minusDays(back.toLong()), 12), now = now, zone = zone)
            val bed = at(today.minusDays(1), 22)
            val wake = at(today, 6)
            val hypnogram = listOf(
                SleepSegment(bed, wake, SleepStage.IN_BED),
                SleepSegment(bed.plusSeconds(1_200), wake.minusSeconds(600), SleepStage.ASLEEP_CORE),
            )
            SleepStore(db).saveSleepSummary(
                SleepStaging.summary(hypnogram), wake, bed, wake, bed.plusSeconds(1_200), wake.minusSeconds(600),
                SleepNightExtras(hypnogram = hypnogram), now, zone,
            )

            val loaded = TodayRepository({ db }).load(today, zone)

            val days = loaded.trends.points.map { LocalDate.ofInstant(it.date, zone) }
            val first = today.minusDays(TodayTiles.WINDOW_DAYS.toLong() - 1)
            assertTrue(days.all { it >= first.minusDays(1) && it <= today }, "only the window (and the evening before it): $days")
            assertTrue(first in days && today in days)
            val last = loaded.trends.points.last()
            assertEquals(1_000, last.steps)
            assertEquals(40.0, last.sleepHRVAvg, "last night's HRV, averaged over its in-bed window")
            assertEquals(0.97, last.sleepSpO2Avg!!, 1e-12)
            assertEquals(60.0, last.dayHRAvg)
            assertNotNull(last.sleepMinutes)
            assertEquals(setOf(wake.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()), loaded.trends.nightKeys)
            assertEquals(at(today, 20), loaded.trends.newestSampleAt)
            // The hero's readings are read as before.
            assertEquals(at(today, 20), loaded.heartRate?.start)
        } finally {
            db.close()
        }
    }
}
