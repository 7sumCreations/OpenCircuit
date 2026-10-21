package io.github.opencircuit.app

import io.github.opencircuit.app.today.TodayRepository
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.store.LocalStore
import io.github.opencircuit.store.StoreFactory
import io.github.opencircuit.store.openInMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Today reloads because the store changed, not on a timer: the store's change announcements become
 * revisions — the first at once (the first frame never waits), then one per burst of writes once
 * the store has been quiet for 300 ms, so a drain committing page after page reloads Today once, not
 * once per page. Through the real store, a reading another writer commits is announced and the
 * reload that follows reads it.
 */
class TodayRepositoryRevisionTest {

    @Test
    fun theFirstAnnouncementIsARevisionAtOnceAndABurstSettlesIntoOne() = runTest {
        val changes = MutableSharedFlow<Set<String>>()
        val seen = mutableListOf<Pair<Long, Long>>() // revision to virtual time
        backgroundScope.launch { TodayRepository.revisions(changes, settle = 300.milliseconds).collect { seen += it to testScheduler.currentTime } }
        runCurrent()

        changes.emit(setOf("stored_sample")) // the store's announcement when watching starts
        runCurrent()
        assertEquals(listOf(0L to 0L), seen, "the first revision does not wait for the quiet period")

        repeat(5) {
            delay(50)
            changes.emit(setOf("stored_sample"))
        }
        // The last write of the burst lands at 250 ms; one revision 300 ms after it, none before.
        delay(299)
        runCurrent()
        assertEquals(1, seen.size, "no revision while the burst is still settling")
        delay(1)
        runCurrent()
        assertEquals(listOf(0L to 0L, 1L to 550L), seen)

        delay(1_000)
        changes.emit(setOf("stored_daily"))
        delay(300)
        runCurrent()
        assertEquals(listOf(0L, 1L, 2L), seen.map { it.first }, "a second burst is a second revision, never a repeat")
    }

    @Test
    fun aReadingAnotherWriterCommitsIsAnnouncedAndTheReloadReadsIt() = runBlocking<Unit> {
        val db = StoreFactory.openInMemory()
        try {
            val repository = TodayRepository({ db }, io = Dispatchers.IO)
            val now = Instant.parse("2026-10-21T12:00:00Z")
            val revisions = Channel<Long>(Channel.UNLIMITED)
            coroutineScope {
                val watcher = launch { repository.revisions().collect { revisions.send(it) } }
                assertEquals(0L, withTimeoutOrNull(5_000) { revisions.receive() })
                assertNull(repository.load(LocalDate.of(2026, 10, 21), ZoneOffset.UTC).heartRate, "nothing stored yet")

                async(Dispatchers.IO) {
                    LocalStore(db).insertLiveSamples(listOf(QuantitySample(MetricKind.HEART_RATE, now.minusSeconds(120), value = 62.0)), now)
                }.await()

                assertEquals(1L, withTimeoutOrNull(5_000) { revisions.receive() }, "the committed reading was not announced")
                val loaded = repository.load(LocalDate.of(2026, 10, 21), ZoneOffset.UTC)
                assertEquals(62.0, assertNotNull(loaded.heartRate).value)
                watcher.cancel()
            }
        } finally {
            db.close()
        }
    }
}
