package io.github.opencircuit.store

import io.github.opencircuit.ringkit.DateInterval
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The nap Health bookkeeping, read from upstream's source (ios/OpenCircuit/Store/LocalStore.swift
 * `pendingNaps` :3016-3021, `markNapWritten` :3025-3032, `staleNapHealthSpan` :3036-3044,
 * `healthWrittenNapWindows` :1344-1355 @ b1c2fdd), which no upstream test exercises.
 */
class NapHealthPendingTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-20T12:00:00Z")
    private fun at(text: String): Instant = Instant.parse(text)

    private val a = at("2025-06-15T13:00:00Z")
    private val aEnd = at("2025-06-15T14:30:00Z")
    private val b = at("2025-06-16T15:00:00Z")
    private val bEnd = at("2025-06-16T15:40:00Z")
    private val june = at("2025-06-01T00:00:00Z")
    private val july = at("2025-07-01T00:00:00Z")

    private suspend fun SleepStore.twoNaps() {
        saveNap(b, bEnd, 40, false, now = now, zone = utc)
        saveNap(a, aEnd, 90, false, now = now, zone = utc)
    }

    @Test
    fun pendingNapsAreTheUnwrittenOnesOldestFirstAndMarkingRecordsTheShownWindow() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.twoNaps()
            assertEquals(listOf(a, b), store.pendingNaps().map { it.start })

            store.markNapWritten(a)

            assertEquals(listOf(b), store.pendingNaps().map { it.start })
            val written = store.naps(a, aEnd).single()
            assertTrue(written.healthWritten)
            assertEquals(a, written.healthWrittenStart)
            assertEquals(aEnd, written.healthWrittenEnd)
        }
    }

    @Test
    fun markingANapNobodyStoredWritesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.twoNaps()
            val before = db.queryRaw("SELECT * FROM stored_nap ORDER BY id")
            store.markNapWritten(at("2025-06-17T10:00:00Z"))
            assertEquals(before, db.queryRaw("SELECT * FROM stored_nap ORDER BY id"))
        }
    }

    @Test
    fun anEditThatShrinksAWrittenNapReArmsItAndItsStaleSpanIsTheUnionOfBothWindows() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.twoNaps()
            assertNull(store.staleNapHealthSpan(a), "never written: nothing stale")
            store.markNapWritten(a)
            val shrunkStart = at("2025-06-15T13:30:00Z")
            val shrunkEnd = at("2025-06-15T14:00:00Z")
            assertTrue(store.editNap(a, shrunkStart, shrunkEnd, now, utc))

            assertEquals(listOf(a, b), store.pendingNaps().map { it.start }, "the shrink re-arms the write")
            assertEquals(DateInterval(a, aEnd), store.staleNapHealthSpan(a))

            store.markNapWritten(a)
            val rewritten = store.naps(a, aEnd).single()
            assertEquals(shrunkStart, rewritten.healthWrittenStart)
            assertEquals(shrunkEnd, rewritten.healthWrittenEnd)
            assertEquals(DateInterval(shrunkStart, shrunkEnd), store.staleNapHealthSpan(a), "written ∪ shown, the detected window no longer part of it")
        }
    }

    @Test
    fun writtenNapWindowsAreTheUnionOfDetectedAndEditedOverlappingTheSpanAndManualOnlyFilters() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.twoNaps()
            val manual = at("2025-06-17T13:00:00Z")
            val manualEnd = at("2025-06-17T13:20:00Z")
            assertTrue(store.addManualNap(manual, manualEnd, now, utc))
            assertTrue(store.healthWrittenNapWindows(june, july).isEmpty(), "nothing written yet")

            store.markNapWritten(a)
            store.markNapWritten(b)
            store.markNapWritten(manual)
            // A widening keeps the nap written: its window is detected ∪ edited.
            assertTrue(store.editNap(a, at("2025-06-15T12:30:00Z"), aEnd, now, utc))

            assertEquals(
                listOf(DateInterval(at("2025-06-15T12:30:00Z"), aEnd), DateInterval(b, bEnd), DateInterval(manual, manualEnd)),
                store.healthWrittenNapWindows(june, july),
            )
            assertEquals(
                listOf(DateInterval(at("2025-06-15T12:30:00Z"), aEnd), DateInterval(manual, manualEnd)),
                store.healthWrittenNapWindows(june, july, manualOnly = true),
                "the wearer's naps only: edited or added, never one the ring alone detected",
            )
            assertEquals(listOf(DateInterval(manual, manualEnd)), store.healthWrittenNapWindows(at("2025-06-17T00:00:00Z"), at("2025-06-18T00:00:00Z")), "only the naps overlapping the span")
            assertTrue(store.healthWrittenNapWindows(aEnd, at("2025-06-15T15:00:00Z")).isEmpty(), "touching at an end is not overlapping")
        }
    }
}
