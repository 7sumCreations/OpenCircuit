package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.store.codec.PriorTimesCodec
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where a Kotlin and Room port of upstream's sleep edit can go wrong where Swift, SwiftData and
 * `UserDefaults` could not (ios/OpenCircuit/Store/LocalStore.swift:2133-2257 @ b1c2fdd): a failed
 * edit half-applied, sub-millisecond times, the key text of a night in a zone east of UTC, the
 * picker minute in a zone whose offset is not whole minutes, and the two-edge overload's end.
 */
class SleepEditHazardTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val ref = Instant.ofEpochSecond(1_750_000_000)
    private val now = Instant.parse("2025-06-16T09:00:00Z")

    private fun at(hours: Double): Instant = ref.plusMillis((hours * 3_600_000).toLong())

    private val recorded = SleepStaging.Summary(
        inBed = Duration.ofHours(8), awake = Duration.ofMinutes(30), light = Duration.ofHours(5),
        deep = Duration.ofMinutes(90), rem = Duration.ofMinutes(60),
    )

    private fun asleepFor(hours: Long) = SleepStaging.Summary(
        inBed = Duration.ofHours(hours), awake = Duration.ZERO, light = Duration.ofHours(hours), deep = Duration.ZERO, rem = Duration.ZERO,
    )

    private suspend fun seed(store: SleepStore, zone: ZoneId = utc, start: Instant = at(0.0)) {
        store.saveSleepSummary(
            recorded, night = start, inBedStart = start, inBedEnd = start.plusSeconds(8 * 3_600),
            sleepOnset = start.plusSeconds(1_800), sleepWake = start.plusSeconds(27_900), now = now, zone = zone,
        )
    }

    private suspend fun StoreDatabase.kvRows(): List<String> = queryRaw("SELECT * FROM store_kv ORDER BY `key`")

    private suspend fun StoreDatabase.sleepRows(): List<String> = queryRaw("SELECT * FROM stored_sleep_summary ORDER BY id")

    /**
     * Upstream writes the undo stack and the onset to `UserDefaults` before its `save()`, which can
     * then fail and leave the changed row dirty for a later save to commit (:2159-2240). Here the row
     * and both values are one transaction: a failure injected after the row and the stack were
     * written leaves all three exactly as they were — after a later save of another night too.
     */
    @Test
    fun aFailureAfterTheRowAndStackWritesLeavesTheRowItsStackAndItsOnsetAsTheyWere() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            store.applySleepEdit(at(0.0), SleepEdit.Times(at(-1.0), at(-0.5), at(8.5)), asleepFor(9), now = now, zone = utc)
            val beforeRows = db.sleepRows()
            val beforeKv = db.kvRows()
            val before = assertNotNull(store.sleepSummary(at(0.0), utc))
            val night = before.night
            val real = db.kvDao()
            var rowWritten = false
            var stackWritten = false
            val failing = object : KvDao by real {
                override suspend fun upsert(entry: StoreKvEntity) {
                    if (!entry.key.startsWith(NightOverlays.ONSET)) return real.upsert(entry)
                    // Inside the edit's transaction: the row and the stack are already written.
                    rowWritten = db.sleepDao().summaryAt(night)?.editedInBedStart == at(-2.0)
                    stackWritten = real.get(NightOverlays.key(NightOverlays.PRIOR_TIMES, night))?.value
                        ?.let { PriorTimesCodec.decode(it).valueOrNull()?.size } == 2
                    error("injected failure")
                }
            }

            assertFails {
                SleepStore(db, db.sleepDao(), failing)
                    .applySleepEdit(at(0.0), SleepEdit.Times(at(-2.0), at(-1.0), at(9.0)), asleepFor(11), now = now.plusSeconds(60), zone = utc)
            }

            assertTrue(rowWritten && stackWritten, "the failure came after the row and the stack were written")
            assertEquals(beforeRows, db.sleepRows())
            assertEquals(beforeKv, db.kvRows())
            store.saveSleepSummary(recorded, night = at(48.0), inBedStart = at(40.0), inBedEnd = at(48.0), now = now, zone = utc)
            assertEquals(before, store.sleepSummary(at(0.0), utc))
            assertEquals(beforeKv, db.kvRows())
        }
    }

    @Test
    fun aFailedFirstEditLeavesTheNightUneditedAndNoValueBesideIt() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val beforeRows = db.sleepRows()
            val real = db.kvDao()
            val failing = object : KvDao by real {
                override suspend fun upsert(entry: StoreKvEntity) =
                    if (entry.key.startsWith(NightOverlays.ONSET)) error("injected failure") else real.upsert(entry)
            }

            assertFails {
                SleepStore(db, db.sleepDao(), failing)
                    .applySleepEdit(at(0.0), SleepEdit.Times(at(-1.0), at(-0.5), at(8.5)), asleepFor(9), now = now, zone = utc)
            }

            assertEquals(beforeRows, db.sleepRows())
            assertEquals(emptyList(), db.kvRows())
        }
    }

    /**
     * Swift's `Date` keeps sub-millisecond time; the store keeps whole milliseconds. An edit given
     * sub-millisecond times stores, shows and stacks them cut toward the past to the millisecond —
     * the same cut on the row's columns and on the onset and stack values, so they compare equal.
     */
    @Test
    fun anEditGivenSubMillisecondTimesStoresShowsAndStacksThemCutToTheMillisecond() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val first = SleepEdit.Times(at(-1.0).plusNanos(400_000), at(-0.5).plusNanos(999_999), at(8.5).plusNanos(1))

            store.applySleepEdit(at(0.0), first, asleepFor(9), now = now, zone = utc)
            val shown = assertNotNull(store.sleepSummary(at(0.0), utc))
            assertEquals(listOf(at(-1.0), at(-0.5), at(8.5)), listOf(shown.currentInBedStart, shown.currentOnset, shown.currentWake))
            assertEquals(at(-0.5).toEpochMilli().toString(), db.kvDao().get(NightOverlays.key(NightOverlays.ONSET, shown.night))?.value)

            store.applySleepEdit(at(0.0), SleepEdit.Times(at(-2.0), at(-1.0), at(9.0)), asleepFor(11), now = now, zone = utc)
            val stack = db.kvDao().get(NightOverlays.key(NightOverlays.PRIOR_TIMES, shown.night))?.value?.let { PriorTimesCodec.decode(it).valueOrNull() }
            assertEquals(
                listOf(SleepEdit.Times(at(0.0), at(0.5), at(7.75)), SleepEdit.Times(shown.currentInBedStart, shown.currentOnset, shown.currentWake)),
                stack,
            )
        }
    }

    /**
     * Upstream's key day is `startOfDay(night).timeIntervalSince1970` printed as a `Double`:
     * `sleep.edit.priorTimes.1749938400.0` for this night in Europe/Paris (measured on Swift). The
     * edit writes both keys under the Paris start of day, not UTC's (1749945600).
     */
    @Test
    fun anEditOfANightEastOfUtcKeysItsValuesByThatZonesStartOfDayInUpstreamsText() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val paris = ZoneId.of("Europe/Paris")
            val store = SleepStore(db)
            seed(store, paris)

            store.applySleepEdit(at(0.0), SleepEdit.Times(at(-1.0), at(-0.5), at(8.5)), asleepFor(9), now = now, zone = paris)

            assertEquals(listOf(Instant.ofEpochSecond(1_749_938_400)), db.sleepDao().allSummaries().map { it.night })
            assertEquals(
                listOf("sleep.edit.onset.1749938400.0", "sleep.edit.priorTimes.1749938400.0"),
                db.queryRaw("SELECT `key` FROM store_kv ORDER BY `key`"),
            )
            assertEquals(at(-0.5), assertNotNull(store.sleepSummary(at(0.0), paris)).currentOnset)
        }
    }

    /**
     * Upstream's picker minute cuts at whole minutes of absolute time in every zone (measured: Monrovia's
     * 1971 offset of −0:44:30 still cuts on the UTC minute). There, 10 s that cross a UTC minute but
     * not a local one are an edit, and 20 s that cross a local minute but not a UTC one are not.
     */
    @Test
    fun theSameMinuteCheckCutsOnTheUtcMinuteEvenWhereTheZonesOffsetIsNotWholeMinutes() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val monrovia = ZoneId.of("Africa/Monrovia")
            val crossesUtcMinute = Instant.parse("1971-06-14T22:00:55Z") // 21:16:25 local
            val crossesLocalMinute = Instant.parse("1971-06-20T22:00:20Z") // 21:15:50 local
            assertEquals(ZoneOffset.ofHoursMinutesSeconds(0, -44, -30), monrovia.rules.getOffset(crossesUtcMinute))
            val store = SleepStore(db)
            seed(store, monrovia, crossesUtcMinute)
            seed(store, monrovia, crossesLocalMinute)

            for ((start, isEdit) in listOf(crossesUtcMinute to true, crossesLocalMinute to false)) {
                val night = start // seeded under its bedtime's day
                val n =assertNotNull(store.sleepSummary(night, monrovia))
                val moved = n.currentInBedStart.plusSeconds(if (isEdit) 10 else 20)
                assertTrue(store.applySleepEdit(night, SleepEdit.Times(moved, n.currentOnset, n.currentWake), n.asSummary, now = now, zone = monrovia))
                assertEquals(isEdit, assertNotNull(store.sleepSummary(night, monrovia)).isManuallyEdited, "start $start")
            }
        }
    }

    /** The two-edge overload keeps upstream's shape: its window's end is ignored, the wake is the edited end (:2249-2257). */
    @Test
    fun theTwoEdgeOverloadTakesTheWakeAsTheEditedEndAndIgnoresTheWindowsEnd() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)

            store.applySleepEdit(
                at(0.0), editedWindow = SleepEdit.Window(at(-1.0), at(9.0)), summary = asleepFor(9),
                sleepOnset = at(-0.5), sleepWake = at(8.0), now = now, zone = utc,
            )

            val n = assertNotNull(store.sleepSummary(at(0.0), utc))
            assertEquals(at(8.0), n.editedInBedEnd)
            assertEquals(at(8.0), n.currentWake)
            assertFalse(n.currentInBedEnd == at(9.0))
        }
    }
}
