package io.github.opencircuit.app.today

import io.github.opencircuit.store.SleepStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The naps a day's Sleep ring credits are the naps whose shown (edited, or else detected) start is
 * on that day: a nap whose start the user moved across midnight belongs to the day it now starts on,
 * never to neither.
 */
class TodayNapsLoadTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val oct20 = LocalDate.of(2026, 10, 20)
    private val oct21 = LocalDate.of(2026, 10, 21)

    @Test
    fun aNapEditedAcrossMidnightIsCreditedToTheDayItNowStartsOn() = runBlocking<Unit> {
        val db = StoreFactory.openInMemory()
        try {
            val sleep = SleepStore(db)
            check(sleep.addManualNap(Instant.parse("2026-10-20T23:50:00Z"), Instant.parse("2026-10-21T00:30:00Z"), now, zone)) { "nap not added" }
            check(sleep.editNap(Instant.parse("2026-10-20T23:50:00Z"), Instant.parse("2026-10-21T00:10:00Z"), Instant.parse("2026-10-21T00:40:00Z"), now, zone)) { "nap not edited" }
            val repository = TodayRepository({ db }, io = Dispatchers.Unconfined)

            val onTheDayItNowStarts = repository.load(oct21, zone).naps
            val onTheDayItWasDetected = repository.load(oct20, zone).naps

            assertEquals(listOf(Instant.parse("2026-10-21T00:10:00Z")), onTheDayItNowStarts.map { it.start })
            assertTrue(onTheDayItWasDetected.isEmpty(), onTheDayItWasDetected.toString())
        } finally {
            db.close()
        }
    }
}
