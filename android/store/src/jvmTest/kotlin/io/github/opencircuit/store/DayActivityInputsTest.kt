package io.github.opencircuit.store

import io.github.opencircuit.ringkit.DateInterval
import io.github.opencircuit.ringkit.HRSample
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.ringkit.StepWindow
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One local day's activity inputs, read from the real store: what the Health Connect energy writer
 * and Today's Calories card and goal rings both estimate a day from. The day's heart rate (starts
 * inside the day), its step total (null when no total is stored, never 0), the step windows that
 * start from the day before's start to the day's end (a window across midnight counts its in-day
 * share), and the in-bed window of the latest night that ENDED inside the day. Day bounds are the
 * zone's own midnights, so a daylight-saving day is 23 or 25 hours.
 */
class DayActivityInputsTest {
    private val london: ZoneId = ZoneId.of("Europe/London")
    /** After every reading below: the store refuses a reading dated after the moment it is written. */
    private val now = Instant.parse("2026-10-28T12:00:00Z")

    private val staging = SleepStaging.Summary(
        Duration.ofHours(8), Duration.ofMinutes(30), Duration.ofHours(5), Duration.ofHours(1), Duration.ofMinutes(90),
    )

    private fun at(text: String): Instant = Instant.parse(text)

    private fun hr(start: String, bpm: Double) = QuantitySample(MetricKind.HEART_RATE, at(start), value = bpm)

    private suspend fun night(db: StoreDatabase, key: String, inBedStart: String, inBedEnd: String, zone: ZoneId = london) {
        SleepStore(db).saveSleepSummary(staging, at(key), at(inBedStart), at(inBedEnd), now = now, zone = zone)
    }

    @Test
    fun aDayReadsItsHeartRateStepsWindowsAndTheNightThatEndedInIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val day = LocalDate.of(2026, 10, 20) // BST: the day is 2026-10-19T23:00Z .. 2026-10-20T23:00Z
            store.insertLiveSamples(
                listOf(
                    hr("2026-10-19T22:59:59Z", 70.0), // the day before, local 23:59:59
                    hr("2026-10-19T23:00:00Z", 61.0), // local midnight: in
                    hr("2026-10-20T09:30:00Z", 128.0),
                    hr("2026-10-20T22:59:59Z", 64.0), // local 23:59:59: in
                    hr("2026-10-20T23:00:00Z", 80.0), // the next day
                    QuantitySample(MetricKind.SPO2, at("2026-10-20T09:30:00Z"), value = 0.97), // another kind
                ),
                now,
            )
            // A window across local midnight (it starts on the day before), one inside the day, one the next day.
            store.addDailySteps(400, at("2026-10-19T23:10:00Z"), windowStart = at("2026-10-19T22:50:00Z"), now = now, zone = london)
            store.addDailySteps(2_500, at("2026-10-20T10:00:00Z"), windowStart = at("2026-10-20T09:00:00Z"), now = now, zone = london)
            store.addDailySteps(90, at("2026-10-20T23:30:00Z"), windowStart = at("2026-10-20T23:15:00Z"), now = now, zone = london)
            // Two nights ended on the day (the later wins), one ended the day before.
            night(db, "2026-10-19T23:00:00Z", "2026-10-19T21:30:00Z", "2026-10-20T05:45:00Z")
            night(db, "2026-10-18T23:00:00Z", "2026-10-18T21:00:00Z", "2026-10-19T06:00:00Z")
            night(db, "2026-10-20T23:00:00Z", "2026-10-20T13:00:00Z", "2026-10-20T14:30:00Z")

            val inputs = store.dayActivityInputs(day, london)

            assertEquals(day, inputs.day)
            assertEquals(
                listOf(
                    HRSample(61, at("2026-10-19T23:00:00Z")),
                    HRSample(128, at("2026-10-20T09:30:00Z")),
                    HRSample(64, at("2026-10-20T22:59:59Z")),
                ),
                inputs.heartRate,
            )
            assertEquals(2_900L, inputs.steps, "the day's stored total: the delta ending on it and the one inside it")
            assertEquals(
                listOf(
                    StepWindow(at("2026-10-19T22:50:00Z"), at("2026-10-19T23:10:00Z"), 400),
                    StepWindow(at("2026-10-20T09:00:00Z"), at("2026-10-20T10:00:00Z"), 2_500),
                ),
                inputs.stepWindows,
                "windows from the day before's start to the day's end",
            )
            assertEquals(DateInterval(at("2026-10-20T13:00:00Z"), at("2026-10-20T14:30:00Z")), inputs.sleepWindow, "of the two that ended on the day, the later")
        }
    }

    @Test
    fun aDayWithNothingStoredReadsEmptyAndItsStepsAsNotRecordedNotZero() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.insertLiveSamples(listOf(hr("2026-10-18T12:00:00Z", 60.0)), now)

            val inputs = store.dayActivityInputs(LocalDate.of(2026, 10, 20), london)

            assertTrue(inputs.heartRate.isEmpty())
            assertNull(inputs.steps, "no total stored: not recorded, never 0")
            assertTrue(inputs.stepWindows.isEmpty())
            assertNull(inputs.sleepWindow)
        }
    }

    /** The clocks go back on 25 October 2026 in London: that day is 25 hours, 2026-10-24T23:00Z .. 2026-10-26T00:00Z. */
    @Test
    fun aDaylightSavingDayIsBoundedByTheZonesOwnMidnights() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val day = LocalDate.of(2026, 10, 25)
            store.insertLiveSamples(listOf(hr("2026-10-24T22:59:59Z", 70.0), hr("2026-10-25T23:30:00Z", 66.0), hr("2026-10-26T00:00:00Z", 90.0)), now)
            // A night ending exactly at the day's start belongs to it; one ending at its end belongs to the next day.
            night(db, "2026-10-24T23:00:00Z", "2026-10-24T16:00:00Z", "2026-10-24T23:00:00Z")
            night(db, "2026-10-26T00:00:00Z", "2026-10-25T18:00:00Z", "2026-10-26T00:00:00Z")

            val inputs = store.dayActivityInputs(day, london)

            assertEquals(listOf(HRSample(66, at("2026-10-25T23:30:00Z"))), inputs.heartRate, "local 23:30 on the 25-hour day is in it")
            assertEquals(DateInterval(at("2026-10-24T16:00:00Z"), at("2026-10-24T23:00:00Z")), inputs.sleepWindow)
        }
    }

    /** A night whose in-bed window is empty or backwards is no window (the writer skipped it too). */
    @Test
    fun aNightWithAnEmptyWindowIsNotTheDaysSleep() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            night(db, "2026-10-19T23:00:00Z", "2026-10-20T05:00:00Z", "2026-10-20T05:00:00Z")

            assertNull(store.dayActivityInputs(LocalDate.of(2026, 10, 20), london).sleepWindow)
        }
    }
}
