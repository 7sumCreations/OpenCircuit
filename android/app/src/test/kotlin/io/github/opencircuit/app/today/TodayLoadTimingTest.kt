package io.github.opencircuit.app.today

import io.github.opencircuit.ble.LinkState
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.ringkit.TemperatureUnit
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.SleepNightExtras
import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Today's first frame from stored data, timed on the JVM host over a year of ring-shaped history
 * (the app keeps every reading for good, so a year is a real store): the window read and the
 * presenter must take at most [BUDGET_MS]. The app's JVM tests link the store's Android variant,
 * which opens a file only through a `Context`, so this store is the same bundled SQLite in memory;
 * the same reads over a file-backed year are timed in the store's own `TodayWindowReadTimingTest`.
 * Also timed, for the record: a 30-day detail read,
 * and a read of every stored night (the sleep table is read whole by the edit write-back — a watch
 * item as the table grows a row a night). The numbers are written to
 * `app/build/reports/today-timing.txt`.
 *
 * Per day, seeded and synthetic: heart rate every 2 minutes all day (720), HRV, SpO₂ (a fraction,
 * as the ring's) and breathing rate every 2 minutes through the night (240 each), an hourly step
 * delta, 12 daytime skin temperatures and one staged night keyed on its wake day.
 */
class TodayLoadTimingTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.parse("2026-09-30")
    private val days = 365

    @Test
    fun todaysFirstFrameOverAYearOfHistoryIsWithinBudget() = runBlocking {
        val db = StoreFactory.openInMemory()
        try {
            val now = today.atTime(10, 0).atZone(zone).toInstant()
            val rows = seed(LocalStore(db), SleepStore(db), now)
            val repo = TodayRepository({ db })
            val session = TodaySession(link = LinkState.Authenticated)

            fun frame(): Pair<Long, TodayUiState> {
                val t0 = System.nanoTime()
                val stored = runBlocking { repo.load(today, zone) }
                val state = TodayPresenter.present(TodayInput(stored, session, now, zone))
                return (System.nanoTime() - t0) / 1_000_000 to state
            }
            val (coldMs, coldState) = frame()
            val warm = (1..5).map { frame().first }.sorted()
            val warmMedian = warm[2]
            assertEquals(8, coldState.tiles.size)
            assertEquals(emptyList(), coldState.tiles.filter { it.value == null }.map { it.title }, "every tile has a value over a worn year")
            assertTrue(coldState.readiness?.score != null && coldState.synthesis != null)

            val detailMs = timed {
                val from = today.minusDays(30).atStartOfDay(zone).toInstant()
                val end = today.plusDays(1).atStartOfDay(zone).toInstant()
                val store = LocalStore(db)
                val inputs = TodayWindowInputs(
                    nights = SleepStore(db).sleepSummaries(from, end).map { TodayNight(it.night, it.asleepMin, it.sleepScore, it.stressScore, it.skinTempC, it.inBedStart, it.inBedEnd) },
                    hr = store.samples(MetricKind.HEART_RATE, from, end),
                    hrv = store.samples(MetricKind.HRV_SDNN, from, end),
                    spo2 = store.samples(MetricKind.SPO2, from, end),
                    rr = store.samples(MetricKind.RESPIRATORY_RATE, from, end),
                )
                val trends = TodayTrends.of(inputs, zone)
                TodayTiles.build(trends.points, trends.restingHR, TemperatureUnit.CELSIUS, windowDays = 30, now = now, zone = zone)
            }
            var nights = 0
            val allNightsMs = timed { nights = SleepStore(db).recentSleepSummaries(limit = Int.MAX_VALUE).size; SleepStore(db).pendingSleepEditHealthWrites() }

            val report = buildString {
                appendLine("Today first frame over $days days ($rows stored rows, bundled SQLite in memory, JVM host)")
                appendLine("cold first frame: $coldMs ms")
                appendLine("warm frames (5, sorted): ${warm.joinToString(", ")} ms; median $warmMedian ms")
                appendLine("30-day detail read + rollup + tiles: $detailMs ms")
                appendLine("every stored night read ($nights nights, the edit write-back's whole-table read): $allNightsMs ms")
                appendLine("budget: $BUDGET_MS ms")
            }
            println(report)
            File("build/reports").mkdirs()
            File("build/reports/today-timing.txt").writeText(report)
            assertTrue(coldMs <= BUDGET_MS, "cold first frame $coldMs ms over the $BUDGET_MS ms budget")
            assertTrue(warmMedian <= BUDGET_MS, "warm first frame $warmMedian ms over the $BUDGET_MS ms budget")
        } finally {
            db.close()
        }
    }

    private inline fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** A year of ring-shaped history; returns how many rows went in. */
    private suspend fun seed(store: LocalStore, sleep: SleepStore, now: Instant): Int {
        val random = Random(20261021)
        var rows = 0
        for (back in days - 1 downTo 0) {
            val d = today.minusDays(back.toLong())
            val dayStart = d.atStartOfDay(zone).toInstant()
            val bed = dayStart.minusSeconds(2 * 3_600L) // 22:00 the evening before
            val wake = dayStart.plusSeconds(6 * 3_600L)
            val samples = ArrayList<QuantitySample>(1_440)
            for (i in 0 until 720) {
                val t = dayStart.plusSeconds(i * 120L)
                if (t.isAfter(now)) break
                val asleep = t.isBefore(wake)
                samples += QuantitySample(kind = MetricKind.HEART_RATE, start = t, value = (if (asleep) 52.0 else 68.0) + random.nextInt(-6, 18))
            }
            for (i in 0 until 240) {
                val t = bed.plusSeconds(i * 120L)
                samples += QuantitySample(kind = MetricKind.HRV_SDNN, start = t, value = 35.0 + random.nextDouble() * 30)
                samples += QuantitySample(kind = MetricKind.SPO2, start = t, value = 0.94 + random.nextDouble() * 0.05)
                samples += QuantitySample(kind = MetricKind.RESPIRATORY_RATE, start = t, value = 12.5 + random.nextDouble() * 4)
            }
            store.ingest(samples, now, zone)
            rows += samples.size
            for (h in 7..22) {
                val at = dayStart.plusSeconds(h * 3_600L)
                if (at.isAfter(now)) break
                store.addDailySteps(200L + random.nextInt(900), at, windowStart = at.minusSeconds(3_600), now = now, zone = zone)
                rows++
            }
            for (k in 0 until 12) {
                val at = dayStart.plusSeconds(8 * 3_600L + k * 3_600L)
                if (at.isAfter(now)) break
                store.recordDaytimeTemperature(33.0 + random.nextDouble(), at)
                rows++
            }
            val hypnogram = listOf(
                SleepSegment(bed, wake, SleepStage.IN_BED),
                SleepSegment(bed.plusSeconds(1_200), bed.plusSeconds(10_800), SleepStage.ASLEEP_CORE),
                SleepSegment(bed.plusSeconds(10_800), bed.plusSeconds(14_400), SleepStage.ASLEEP_DEEP),
                SleepSegment(bed.plusSeconds(14_400), wake.minusSeconds(600), SleepStage.ASLEEP_REM),
            )
            sleep.saveSleepSummary(
                SleepStaging.summary(hypnogram), wake, bed, wake, bed.plusSeconds(1_200), wake.minusSeconds(600),
                SleepNightExtras(
                    skinTempC = 33.2 + random.nextDouble() * 0.8, sleepScore = 60 + random.nextInt(35),
                    stressScore = 20 + random.nextInt(50), hypnogram = hypnogram,
                ),
                now, zone,
            )
            rows++
        }
        return rows
    }

    private companion object {
        /** Today's first frame from stored data on the JVM host (the plan's latency budget). */
        const val BUDGET_MS = 250L
    }
}
