package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.store.codec.PriorTimesCodec
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The per-night values a wearer's edit keeps beside its row: the undo stack of replaced times and
 * the edited onset. Upstream keeps both in `UserDefaults` (ios/OpenCircuit/Store/LocalStore.swift
 * `SleepEditPriorTimesOverlay` :400-452, `SleepEditOnsetOverlay` :457-480 @ b1c2fdd); here they are
 * `store_kv` rows, read and written on the real table.
 */
class NightOverlaysTest {

    private val night = Instant.ofEpochSecond(1_749_945_600) // 2025-06-15T00:00:00Z
    private val now = Instant.parse("2025-06-16T09:00:00Z")

    private fun t(seconds: Long) = Instant.ofEpochSecond(seconds)

    private fun times(i: Long) = SleepEdit.Times(t(1_750_000_000 + i * 60), t(1_750_001_800 + i * 60), t(1_750_028_800 + i * 60))

    private suspend fun KvDao.stack(night: Instant): List<SleepEdit.Times>? =
        get(NightOverlays.key(NightOverlays.PRIOR_TIMES, night))?.value?.let { PriorTimesCodec.decode(it).valueOrNull() }

    /**
     * Upstream builds the key from a `Double` (`"\(day)"` prints `1749938400.0`, measured); Kotlin's
     * `Double.toString` would print `1.7499384E9`, so the day is written out as whole seconds + `.0`.
     */
    @Test
    fun theKeyIsUpstreamsTextWithTheDayInWholeSecondsAndATrailingPointZero() {
        assertEquals("sleep.edit.priorTimes.1749938400.0", NightOverlays.key(NightOverlays.PRIOR_TIMES, t(1_749_938_400)))
        assertEquals("sleep.edit.onset.1749945600.0", NightOverlays.key(NightOverlays.ONSET, night))
        assertEquals("sleep.edit.onset.-86400.0", NightOverlays.key(NightOverlays.ONSET, t(-86_400)))
    }

    @Test
    fun aPushStoresTheReplacedTimesAndAnUnchangedPushDoesNotGrowTheStack() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val overlays = NightOverlays(kv)

            overlays.pushPriorTimes(night, times(0), now)
            overlays.pushPriorTimes(night, times(0), now)
            overlays.pushPriorTimes(night, times(1), now)

            assertEquals(listOf(times(0), times(1)), kv.stack(night))
            assertNull(kv.stack(night.plusSeconds(86_400)), "another night's stack is its own key")
        }
    }

    /** Upstream's guard (:424-425): nothing to restore to when the window is unknown or not a window. */
    @Test
    fun timesWithNoUsableWindowAreNotPushed() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val overlays = NightOverlays(db.kvDao())
            val start = t(1_750_000_000)

            overlays.pushPriorTimes(night, SleepEdit.Times(SleepEdit.DISTANT_PAST, start, start.plusSeconds(3_600)), now)
            overlays.pushPriorTimes(night, SleepEdit.Times(start, start, start), now)
            overlays.pushPriorTimes(night, SleepEdit.Times(start, start, start.minusSeconds(1)), now)

            assertNull(db.kvDao().get(NightOverlays.key(NightOverlays.PRIOR_TIMES, night)))
        }
    }

    /** Depth 20: the oldest entry after the first is dropped, so the ring's own window always survives (:429-430). */
    @Test
    fun theStackKeepsItsFirstEntryAndTheNewestNineteen() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val overlays = NightOverlays(db.kvDao())

            for (i in 0L..20L) overlays.pushPriorTimes(night, times(i), now)

            assertEquals(listOf(times(0)) + (2L..20L).map(::times), db.kvDao().stack(night))
        }
    }

    @Test
    fun aStoredStackLongerThanTheCapIsCutBackToTheFirstEntryAndTheNewestNineteenOnThePush() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val key = NightOverlays.key(NightOverlays.PRIOR_TIMES, night)
            kv.upsert(StoreKvEntity(key, PriorTimesCodec.encode((0L..24L).map(::times)), now))

            NightOverlays(kv).pushPriorTimes(night, times(25), now)

            assertEquals(listOf(times(0)) + (7L..25L).map(::times), kv.stack(night))
        }
    }

    /**
     * Upstream reads an unreadable stack as empty and the next push overwrites it. Here the stored
     * text is kept as it is and the push is skipped: stored text this build cannot read is never
     * written over.
     */
    @Test
    fun anUnreadableStackIsKeptAsStoredAndThePushSkipped() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val key = NightOverlays.key(NightOverlays.PRIOR_TIMES, night)
            val raw = """[{"inBedStart":1750000000000,"sleepOnset":"later"}]"""
            kv.upsert(StoreKvEntity(key, raw, now))

            NightOverlays(kv).pushPriorTimes(night, times(1), now.plusSeconds(60))

            assertEquals(StoreKvEntity(key, raw, now), kv.get(key))
        }
    }

    /**
     * The store keeps whole milliseconds, where Swift's `Date` equality sees sub-millisecond
     * differences. A push is cut to the stored millisecond before it is compared with the top, so
     * times that differ only below it are the same entry.
     */
    @Test
    fun timesDifferingOnlyBelowAMillisecondFromTheTopAreTheSameEntry() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val overlays = NightOverlays(kv)
            val subMs = SleepEdit.Times(
                times(0).inBedStart.plusNanos(400_000), times(0).sleepOnset.plusNanos(999_999), times(0).sleepWake.plusNanos(1),
            )

            overlays.pushPriorTimes(night, times(0), now)
            overlays.pushPriorTimes(night, subMs, now)

            assertEquals(listOf(times(0)), kv.stack(night))
        }
    }

    @Test
    fun theOnsetIsStoredAsWholeEpochMillisecondsAndReadBack() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val overlays = NightOverlays(kv)
            val onset = Instant.ofEpochMilli(1_750_001_800_123).plusNanos(456_789)

            assertNull(overlays.onset(night))
            overlays.saveOnset(night, onset, now)

            assertEquals("1750001800123", kv.get(NightOverlays.key(NightOverlays.ONSET, night))?.value)
            assertEquals(Instant.ofEpochMilli(1_750_001_800_123), overlays.onset(night))
        }
    }

    /**
     * Upstream reads the onset with `object(forKey:) as? Date`, so a value of another type reads as
     * absent (measured with a `Double`). Here any text that is not whole epoch milliseconds reads as
     * absent, and reading never changes it.
     */
    @Test
    fun anOnsetThatIsNotWholeEpochMillisecondsReadsAsAbsentAndIsLeftAsStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val kv = db.kvDao()
            val key = NightOverlays.key(NightOverlays.ONSET, night)
            for (raw in listOf("abc", "1.5", "\"1750001800000\"", "true", "", "１７５０")) {
                kv.upsert(StoreKvEntity(key, raw, now))
                assertNull(NightOverlays(kv).onset(night), raw)
                assertEquals(raw, kv.get(key)?.value, raw)
            }
        }
    }
}
