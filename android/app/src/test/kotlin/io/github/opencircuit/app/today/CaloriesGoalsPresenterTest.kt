package io.github.opencircuit.app.today

import io.github.opencircuit.app.health.StoreHealthActivity
import io.github.opencircuit.app.health.StoreHealthFailures
import io.github.opencircuit.app.health.StoreHealthSamples
import io.github.opencircuit.app.health.StoreWriteGenerations
import io.github.opencircuit.health.ActiveCaloriesBurned
import io.github.opencircuit.health.EnergyProfile
import io.github.opencircuit.health.FakeHealthConnect
import io.github.opencircuit.health.HealthWriter
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RingDevice
import io.github.opencircuit.health.WriteGeneration
import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.Calories
import io.github.opencircuit.ringkit.GoalHistory
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreDatabase
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Calories card and the four goal rings for the selected day, from inputs written to the real
 * store and read back through its one day reader. The card follows upstream's Calories card
 * (`ContentView.swift` `CaloriesCardView` @ 2d07e550): resting (BMR over the part of the day gone,
 * all of it on a past day) plus the day's active estimate; the rings follow its Goals card
 * (`GoalsCardView.swift` @ 2d07e550). Synthetic, ring-shaped values.
 */
class CaloriesGoalsPresenterTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Instant = Instant.parse("2026-10-21T18:00:00Z") // a Wednesday: workday goals
    private val today: LocalDate = LocalDate.of(2026, 10, 21)
    private val yesterday: LocalDate = today.minusDays(1)
    private var db: StoreDatabase? = null

    /** Goals no default equals: the rings must follow the goals they are given, never a constant. */
    private val goals = GoalHistory.Goals(workdaySteps = 9_000, weekendSteps = 11_000, activeKcal = 250.0, activityMinutes = 20.0, workdaySleepMin = 450, weekendSleepMin = 500)

    @AfterTest
    fun close() {
        db?.close()
    }

    private fun store(): LocalStore = LocalStore(runBlocking { StoreFactory.openInMemory().also { db = it } })

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Instant = day.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant()

    /** A 40-minute brisk walk at 130 bpm (one reading per ring epoch) and resting readings around it. */
    private suspend fun LocalStore.walk(day: LocalDate) {
        val walk = (0 until 40 * 60 / BulkRecord.EPOCH_SECONDS).map { QuantitySample(MetricKind.HEART_RATE, at(day, 9).plusSeconds(it * BulkRecord.EPOCH_SECONDS.toLong()), value = 130.0) }
        val rest = (0 until 12).map { QuantitySample(MetricKind.HEART_RATE, at(day, 12 + it / 2, (it % 2) * 30), value = 62.0) }
        insertLiveSamples(walk + rest, now)
        addDailySteps(6_240, at(day, 9, 40), windowStart = at(day, 9), now = now, zone = zone)
    }

    private fun night(day: LocalDate, asleep: Int) = TodayNight(day.atStartOfDay(zone).toInstant(), asleep, 80, 30, 0.0, at(day.minusDays(1), 23), at(day, 7))

    private fun present(store: LocalStore, day: LocalDate, nights: List<TodayNight> = emptyList(), naps: List<GoalHistory.NapSleep> = emptyList()) =
        CaloriesGoals.present(runBlocking { store.dayActivityInputs(day, zone) }, nights, naps, day, now, zone, goals, EnergyProfile.DEFAULT)

    @Test
    fun aPastDaysCardIsItsWholeBasalDayPlusItsActiveEstimate() {
        val store = store()
        runBlocking { store.walk(yesterday) }

        val ui = present(store, yesterday)

        val bmr = Calories.bmrKcalPerDay(EnergyProfile.DEFAULT)
        val active = assertNotNull(ui.calories.active).replace(",", "").toInt()
        assertTrue(active > 0, "the walk is active energy")
        assertEquals(String.format(Locale.ROOT, "%,d", Math.round(bmr)), ui.calories.resting, "a past day: its whole basal day")
        // Upstream rounds the unrounded sum, so the total is the two shown parts' sum or one off it.
        val total = assertNotNull(ui.calories.total).replace(",", "").toLong()
        assertTrue(Math.abs(total - (Math.round(bmr) + active)) <= 1, "total $total vs parts ${ui.calories.resting} + $active")
        assertNull(ui.calories.qualifier)
        assertNull(ui.calories.empty)
        assertEquals("Calories, estimate, ${ui.calories.total} kilocalories: resting ${ui.calories.resting}, active ${ui.calories.active}", ui.calories.description)
    }

    @Test
    fun todaysCardCountsTheBasalPartOfTheDayGoneSoFar() {
        val store = store()
        runBlocking { store.walk(today) }

        val ui = present(store, today)

        // 18:00 of a 24-hour day: three quarters of the day's basal energy.
        val bmr = Calories.bmrKcalPerDay(EnergyProfile.DEFAULT)
        assertEquals(String.format(Locale.ROOT, "%,d", Math.round(bmr * 0.75)), ui.calories.resting)
        assertEquals("so far today", ui.calories.qualifier)
        assertTrue(ui.calories.description.startsWith("Calories so far today, estimate,"), ui.calories.description)
    }

    /** One reader feeds the card and the Health Connect writer: what the writer writes for a day is what the card calls active. */
    @Test
    fun theCardsActiveEnergyIsWhatTheHealthWriterWritesForTheDay() = runBlocking<Unit> {
        val store = store()
        store.walk(yesterday)
        val database = db!!
        val hc = FakeHealthConnect({ now }).apply { grantAll() }
        val writer = HealthWriter(
            port = hc,
            store = StoreHealthSamples { database },
            generation = WriteGeneration(StoreWriteGenerations({ database }, { now })),
            failureStore = StoreHealthFailures({ database }, { now }, log = {}),
            clock = { now },
            zone = { zone },
            device = { RingDevice("Gen 2") },
            activity = StoreHealthActivity({ database }, { now }, { zone }),
        )
        writer.flush()
        val dayStart = yesterday.atStartOfDay(zone).toInstant()
        val written = hc.records(RecordType.ACTIVE_CALORIES_BURNED).map { it as ActiveCaloriesBurned }
            .filter { !it.start.isBefore(dayStart) && it.start.isBefore(dayStart.plusSeconds(86_400)) }.sumOf { it.kilocalories }
        assertTrue(written > 0)

        assertEquals(Math.round(written).toString(), present(store, yesterday).calories.active)
    }

    @Test
    fun fourRingsFollowTheGoalsTheyAreGivenAndSayTheirValueGoalAndPercent() {
        val store = store()
        runBlocking { store.walk(yesterday) }

        val rings = present(store, yesterday, nights = listOf(night(yesterday, 405))).goals.rings

        assertEquals(listOf(GoalHistory.Ring.STEPS, GoalHistory.Ring.ACTIVE_KCAL, GoalHistory.Ring.ACTIVITY_MINUTES, GoalHistory.Ring.SLEEP_MINUTES), rings.map { it.ring })
        val steps = rings[0]
        assertEquals("Steps", steps.label)
        assertEquals("6,240 / 9,000", steps.detail)
        assertEquals("69%", steps.centre)
        assertEquals((6_240.0 / 9_000).toFloat(), steps.fraction, 1e-6f)
        assertEquals("Steps 6,240 of 9,000, 69 %", steps.description)
        val sleep = rings[3]
        assertEquals("6 h 45 m / 7 h 30 m", sleep.detail)
        assertEquals("Sleep 6 h 45 m of 7 h 30 m, 90 %", sleep.description)
        assertTrue(rings[1].detail.endsWith(" / 250"), rings[1].detail)
        assertTrue(rings[2].detail.endsWith(" / 20 min"), rings[2].detail)
    }

    /** A day with heart rate and no stored step total: steps are not recorded — never a 0 ring. */
    @Test
    fun aDayWithNoStepTotalSaysStepsNotRecordedNeverZero() {
        val store = store()
        runBlocking { store.insertLiveSamples(listOf(QuantitySample(MetricKind.HEART_RATE, at(yesterday, 12), value = 64.0)), now) }

        val ui = present(store, yesterday)

        val steps = ui.goals.rings.first { it.ring == GoalHistory.Ring.STEPS }
        assertEquals("Steps not recorded", steps.detail)
        assertEquals("—", steps.centre)
        assertEquals(0f, steps.fraction)
        assertEquals("Steps not recorded", steps.description)
        assertEquals("Steps: connected time only", ui.goals.note)
        assertNotNull(ui.calories.total, "heart rate alone still makes a calorie estimate")
    }

    @Test
    fun aDayWithNothingStoredHasEmptyStatesNotZeros() {
        val ui = present(store(), yesterday)

        assertEquals("No activity recorded for this day.", ui.calories.empty)
        assertNull(ui.calories.total)
        assertEquals("Calories. No activity recorded for this day.", ui.calories.description)
        assertTrue(ui.goals.rings.all { it.centre == "—" && it.fraction == 0f }, ui.goals.rings.toString())
        assertEquals("Sleep, no night ended on this day", ui.goals.rings.last().description)
        assertNull(ui.activity, "no activity: no Activity Score for readiness")
    }

    /** Sleep credits the night that ended on the day plus its naps outside that night (upstream's Goals card). */
    @Test
    fun sleepCreditsTheNightThatEndedOnTheDayAndItsNaps() {
        val nap = GoalHistory.NapSleep(at(yesterday, 14), at(yesterday, 14, 40), 35)

        val ui = present(store(), yesterday, nights = listOf(night(yesterday, 405)), naps = listOf(nap))

        assertEquals("7 h 20 m / 7 h 30 m", ui.goals.rings.last().detail)
    }

    /** Numbers never take the phone's locale: digits and grouping stay ASCII under any default. */
    @Test
    fun numbersStayAsciiUnderAnyDefaultLocale() {
        val store = store()
        runBlocking { store.walk(yesterday) }
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "de-DE", "hi-IN-u-nu-deva")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val ui = present(store, yesterday)
                val text = listOfNotNull(ui.calories.total, ui.calories.resting, ui.calories.active) + ui.goals.rings.flatMap { listOf(it.detail, it.centre, it.description) }
                assertTrue(text.all { s -> s.all { it.code < 128 || it == '—' } }, "$tag: $text")
                assertEquals("6,240 / 9,000", ui.goals.rings.first().detail, tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
