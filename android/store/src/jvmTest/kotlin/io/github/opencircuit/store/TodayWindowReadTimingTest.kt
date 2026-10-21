package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reads the Today screen makes for its two-week window (the app's `TodayRepository`), timed over
 * a year of ring-shaped history in a FILE-backed store on the JVM host: the nights keyed on the
 * window, its step totals, four reading kinds, daytime temperatures and step deltas — each a range
 * on its time index, so the cost follows the window, not the year. Also timed, the watch item: the
 * sleep table read whole (`SleepDao.allSummaries`, one row a night, never pruned). Written to
 * `build/reports/today-window-timing.txt`; the reads must take at most 250 ms, Today's whole
 * first-frame budget.
 */
class TodayWindowReadTimingTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-09-30")
    private val days = 365

    @Test
    fun theTodayWindowReadsOverAFileBackedYearStayInsideTheFirstFrameBudget() = runBlocking {
        val dir = Files.createTempDirectory("today-window").toFile()
        val db = StoreFactory.openFile(File(dir, "store.db").toPath())
        try {
            val now = today.atTime(10, 0).atZone(zone).toInstant()
            val store = LocalStore(db)
            val sleep = SleepStore(db)
            val random = Random(20261021)
            for (back in days - 1 downTo 0) {
                val dayStart = today.minusDays(back.toLong()).atStartOfDay(zone).toInstant()
                val bed = dayStart.minusSeconds(2 * 3_600L)
                val wake = dayStart.plusSeconds(6 * 3_600L)
                val samples = ArrayList<QuantitySample>(1_440)
                for (i in 0 until 720) {
                    val t = dayStart.plusSeconds(i * 120L)
                    if (t.isAfter(now)) break
                    samples += QuantitySample(kind = MetricKind.HEART_RATE, start = t, value = 55.0 + random.nextInt(40))
                }
                for (i in 0 until 240) {
                    val t = bed.plusSeconds(i * 120L)
                    samples += QuantitySample(kind = MetricKind.HRV_SDNN, start = t, value = 35.0 + random.nextDouble() * 30)
                    samples += QuantitySample(kind = MetricKind.SPO2, start = t, value = 0.94 + random.nextDouble() * 0.05)
                    samples += QuantitySample(kind = MetricKind.RESPIRATORY_RATE, start = t, value = 12.5 + random.nextDouble() * 4)
                }
                store.ingest(samples, now, zone)
                for (h in 7..22) {
                    val at = dayStart.plusSeconds(h * 3_600L)
                    if (at.isAfter(now)) break
                    store.addDailySteps(200L + random.nextInt(900), at, windowStart = at.minusSeconds(3_600), now = now, zone = zone)
                }
                for (k in 0 until 12) store.recordDaytimeTemperature(33.0 + random.nextDouble(), dayStart.plusSeconds(8 * 3_600L + k * 3_600L))
                val hypnogram = listOf(SleepSegment(bed, wake, SleepStage.IN_BED), SleepSegment(bed.plusSeconds(1_200), wake.minusSeconds(600), SleepStage.ASLEEP_CORE))
                sleep.saveSleepSummary(
                    SleepStaging.summary(hypnogram), wake, bed, wake, bed.plusSeconds(1_200), wake.minusSeconds(600),
                    SleepNightExtras(skinTempC = 33.5, sleepScore = 75, stressScore = 40, hypnogram = hypnogram), now, zone,
                )
            }

            val first = today.minusDays(13).atStartOfDay(zone).toInstant()
            val from = today.minusDays(14).atStartOfDay(zone).toInstant()
            val end = today.plusDays(1).atStartOfDay(zone).toInstant()
            var rowsRead = 0
            fun window(): Long {
                val t0 = System.nanoTime()
                runBlocking {
                    rowsRead = sleep.sleepSummaries(first, end).size + store.dailies(first, end).size +
                        listOf(MetricKind.HEART_RATE, MetricKind.HRV_SDNN, MetricKind.SPO2, MetricKind.RESPIRATORY_RATE).sumOf { store.samples(it, from, end).size } +
                        store.daytimeTemperatures(from, end).size + store.stepSamples(from, end).size
                }
                return (System.nanoTime() - t0) / 1_000_000
            }
            val cold = window()
            val warm = (1..5).map { window() }.sorted()
            var nights = 0
            val t0 = System.nanoTime()
            nights = db.sleepDao().allSummaries().size
            val allMs = (System.nanoTime() - t0) / 1_000_000

            val report = buildString {
                appendLine("Today window reads over $days days (file-backed store, JVM host): $rowsRead rows read")
                appendLine("cold: $cold ms; warm (5, sorted): ${warm.joinToString(", ")} ms")
                appendLine("SleepDao.allSummaries(): $nights rows in $allMs ms")
            }
            println(report)
            File("build/reports").mkdirs()
            File("build/reports/today-window-timing.txt").writeText(report)
            assertEquals(days, nights)
            assertTrue(cold <= 250, "cold window read $cold ms")
            assertTrue(warm[2] <= 250, "warm window read ${warm[2]} ms")
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }
}
