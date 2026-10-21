package io.github.opencircuit.app.today

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which dates get the "new data" mark, from two loads of the real repository around a real store
 * write: the days whose stored readings changed, days a drain added before the first stored day,
 * and the day of a newer newest reading. The day on screen is never marked (it folds in place),
 * and two loads of unchanged data mark nothing, whichever days they were for.
 */
class DayMarksTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.of(2026, 10, 21)
    private val now: Instant = today.atStartOfDay(zone).plusHours(18).toInstant()

    private fun at(day: LocalDate, hour: Int): Instant = day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant()
    private fun hr(day: LocalDate, hour: Int, bpm: Double) = QuantitySample(kind = MetricKind.HEART_RATE, start = at(day, hour), value = bpm)

    private fun withStore(block: suspend (LocalStore, TodayRepository) -> Unit) = runBlocking {
        val db = StoreFactory.openInMemory()
        try {
            block(LocalStore(db), TodayRepository({ db }))
        } finally {
            db.close()
        }
    }

    @Test
    fun aWriteOnAnotherDayInTheWindowMarksThatDayAndNotTheDayOnScreen() = withStore { store, repo ->
        val shown = today.minusDays(2)
        store.ingest((1..5).map { hr(today.minusDays(it.toLong()), 9, 60.0) }, now, zone)
        val before = repo.load(shown, zone)

        // Behind the ingest cursor, so through the cursor-free write path a live reading takes.
        store.insertLiveSamples(listOf(hr(today.minusDays(4), 14, 90.0), hr(shown, 14, 88.0)), now)
        val after = repo.load(shown, zone)

        assertEquals(setOf(today.minusDays(4)), DayMarks.landed(before, after, zone))
    }

    @Test
    fun theSameDataLoadedForAnotherDayOrWindowMarksNothing() = withStore { store, repo ->
        store.ingest((0..20).map { hr(today.minusDays(it.toLong()), 9, 60.0 + it % 3) }, now, zone)
        val a = repo.load(today.minusDays(2), zone)
        val b = repo.load(today.minusDays(5), zone)
        val c = repo.load(today.minusDays(5), zone, windowDays = 30)
        assertEquals(emptySet(), DayMarks.landed(a, b, zone))
        assertEquals(emptySet(), DayMarks.landed(b, c, zone))
        assertEquals(emptySet(), DayMarks.landed(null, a, zone), "the first load has nothing to compare")
    }

    @Test
    fun daysADrainAddsBeforeTheFirstStoredDayAreMarked() = withStore { store, repo ->
        store.ingest(listOf(hr(today.minusDays(1), 9, 60.0)), now, zone)
        val before = repo.load(today, zone)
        // Older history arrives: three days before the first stored one, far outside today's window too.
        store.insertLiveSamples((20..22).map { hr(today.minusDays(it.toLong()), 9, 61.0) }, now)
        val after = repo.load(today, zone)

        assertEquals(today.minusDays(22), LocalDate.ofInstant(after.earliest, zone))
        assertEquals((2..22).map { today.minusDays(it.toLong()) }.toSet(), DayMarks.landed(before, after, zone))
    }

    @Test
    fun aNewerNewestReadingMarksItsDayEvenOutsideTheWindowOnScreen() = withStore { store, repo ->
        store.ingest(listOf(hr(today.minusDays(40), 9, 60.0), hr(today.minusDays(1), 9, 60.0)), now, zone)
        val shown = today.minusDays(30)
        val before = repo.load(shown, zone)
        store.ingest(listOf(hr(today, 9, 72.0)), now, zone)
        val after = repo.load(shown, zone)

        assertEquals(setOf(today), DayMarks.landed(before, after, zone))
    }

    @Test
    fun theFirstHistoryEverStoredMarksItsDays() = withStore { store, repo ->
        val before = repo.load(today, zone)
        store.ingest((1..3).map { hr(today.minusDays(it.toLong()), 9, 60.0) }, now, zone)
        val after = repo.load(today, zone)

        assertEquals((1..3).map { today.minusDays(it.toLong()) }.toSet(), DayMarks.landed(before, after, zone))
    }
}
