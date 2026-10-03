package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepPersistOutcome
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The night save's branches that keep the stored night, read from upstream's source
 * (ios/OpenCircuit/Store/LocalStore.swift:1639-1753 @ b1c2fdd), which has no store test of them:
 * the refusal of an unfinished evening bout colliding with the night that ended this morning, the
 * kept wearer's edit, and the clamp window both kept branches widen with a fuller staging.
 */
class SleepSaveKeepTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val now1 = Instant.parse("2025-06-15T08:00:00Z")
    private val now2 = Instant.parse("2025-06-15T23:00:00Z")

    private fun local(text: String, zone: ZoneId = utc): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun summary(inBedMin: Long, asleepMin: Long) = SleepStaging.Summary(
        inBed = Duration.ofMinutes(inBedMin), awake = Duration.ofMinutes(maxOf(inBedMin - asleepMin, 0)),
        light = Duration.ofMinutes(asleepMin), deep = Duration.ZERO, rem = Duration.ZERO,
    )

    private suspend fun SleepStore.save(
        start: Instant,
        end: Instant,
        asleepMin: Long,
        onset: Instant = SleepEdit.DISTANT_PAST,
        wake: Instant = SleepEdit.DISTANT_PAST,
        night: Instant = end,
        now: Instant = now1,
        zone: ZoneId = utc,
    ): SleepPersistOutcome = saveSleepSummary(
        summary(Duration.between(start, end).toMinutes(), asleepMin), night = night, inBedStart = start, inBedEnd = end,
        sleepOnset = onset, sleepWake = wake, now = now, zone = zone,
    )

    private suspend fun StoreDatabase.snapshot(): List<String> =
        queryRaw("SELECT * FROM stored_sleep_summary ORDER BY id") + queryRaw("SELECT * FROM store_kv ORDER BY `key`")

    /**
     * The first save that finds a stored night also latches the one-time move of stored nights onto
     * their wake day (NightKeyMigrationTest). Latched before a "nothing written" snapshot, so the
     * snapshot still covers every row of the table.
     */
    private suspend fun SleepStore.latched(): SleepStore = also { assertTrue(it.ensureNightKeyMigrated(utc, now1)) }

    // The night that ended this morning, and the evening that follows it.
    private val bed = local("2025-06-14T23:00")
    private val wake = local("2025-06-15T07:00")

    /**
     * An evening drain stages a block that ends before midnight; it keys to the same day as the
     * night that ended this morning (:1631-1651). Not overlapping it, not ending in the wake window
     * while the stored one does: refused, and nothing written.
     */
    @Test
    fun anUnfinishedEveningBoutCollidingWithTheNightThatEndedThisMorningIsRefused() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(bed, wake, 420)
            store.latched()
            val before = db.snapshot()

            assertEquals(
                SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION,
                store.save(local("2025-06-15T20:00"), local("2025-06-15T22:30"), 150, night = wake, now = now2),
            )
            assertEquals(before, db.snapshot())
        }
    }

    /** Overlap counts with one ring epoch of slack (150 s): a gap of exactly 150 s is the same night, 151 s is not. */
    @Test
    fun aBlockStartingWithinOneRingEpochOfTheStoredEndIsNotACollision() = runBlocking<Unit> {
        for ((gapSeconds, refused) in listOf(150L to false, 151L to true)) {
            withInMemoryStore { db ->
                val store = SleepStore(db)
                store.save(bed, wake, 420)
                val start = wake.plusSeconds(gapSeconds)

                val outcome = store.save(start, local("2025-06-15T13:00"), 60, night = wake, now = now2)

                assertEquals(refused, outcome == SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION, "gap $gapSeconds s: $outcome")
            }
        }
    }

    /**
     * Only a block that does NOT end in the wake window against a stored one that DOES is refused;
     * every other non-overlapping pair falls through to the merge, as upstream: both ending in it,
     * neither ending in it, or a stored window that is not known.
     */
    @Test
    fun everyOtherNonOverlappingPairFallsThroughToTheMerge() = runBlocking<Unit> {
        val cases = listOf(
            "both end in the wake window" to Triple(local("2025-06-15T01:00") to local("2025-06-15T03:00"), local("2025-06-15T05:00"), local("2025-06-15T09:00")),
            "neither ends in it" to Triple(local("2025-06-15T13:00") to local("2025-06-15T15:00"), local("2025-06-15T20:00"), local("2025-06-15T22:30")),
            "the stored window is unknown" to Triple(SleepEdit.DISTANT_PAST to SleepEdit.DISTANT_PAST, local("2025-06-15T20:00"), local("2025-06-15T22:30")),
        )
        for ((name, case) in cases) {
            withInMemoryStore { db ->
                val (stored, start, end) = case
                val store = SleepStore(db)
                store.save(stored.first, stored.second, 100, night = wake)

                val outcome = store.save(start, end, 100, night = wake, now = now2)

                assertNotEquals(SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION, outcome, name)
            }
        }
    }

    /**
     * The wake window is read in the zone given: in Asia/Tokyo the same local hours are refused,
     * though the stored night's end (07:00 local) is 22:00 in UTC, outside UTC's wake window.
     */
    @Test
    fun theWakeWindowIsReadInTheZoneGiven() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val tokyo = ZoneId.of("Asia/Tokyo")
            val store = SleepStore(db)
            val storedEnd = local("2025-06-15T07:00", tokyo)
            store.save(local("2025-06-14T23:00", tokyo), storedEnd, 420, zone = tokyo)

            assertEquals(
                SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION,
                store.save(local("2025-06-15T20:00", tokyo), local("2025-06-15T22:30", tokyo), 150, night = storedEnd, now = now2, zone = tokyo),
            )
        }
    }

    /** The guard runs before the edit check (:1639-1656): an evening bout colliding with an edited night is refused too. */
    @Test
    fun anEveningBoutCollidingWithAnEditedNightIsRefusedNotKept() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(bed, wake, 420)
            store.applySleepEdit(wake, SleepEdit.Times(bed.minusSeconds(1_800), bed, wake), summary(510, 480), now = now1, zone = utc)
            store.latched()
            val before = db.snapshot()

            assertEquals(
                SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION,
                store.save(local("2025-06-15T20:00"), local("2025-06-15T22:30"), 150, night = wake, now = now2),
            )
            assertEquals(before, db.snapshot())
        }
    }

    /**
     * An edited night is the wearer's (:1656-1684): a later staging — even a fuller one — never
     * replaces its minutes, timeline, edges or values beside it. It only widens the clamp window
     * outward, in the widened-recorded columns; the recorded window itself stays as first saved.
     */
    @Test
    fun aFullerStagingOfAnEditedNightIsKeptOutAndOnlyWidensItsClamp() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(bed, wake, 420, onset = bed.plusSeconds(1_800), wake = wake.minusSeconds(900))
            store.applySleepEdit(wake, SleepEdit.Times(bed.minusSeconds(3_600), bed.plusSeconds(600), wake), summary(540, 500), now = now1, zone = utc)
            store.latched()
            val before = assertNotNull(store.sleepSummary(wake, utc))
            val kvBefore = db.queryRaw("SELECT * FROM store_kv ORDER BY `key`")

            val outcome = store.save(
                bed.minusSeconds(1_200), wake.plusSeconds(2_400), 520, onset = bed.plusSeconds(600), wake = wake.plusSeconds(1_800), now = now2,
            )

            assertEquals(SleepPersistOutcome.KEPT_MANUAL_EDIT, outcome)
            val after = assertNotNull(store.sleepSummary(wake, utc))
            assertEquals(
                before.copy(
                    widenedRecordedInBedStart = bed.minusSeconds(1_200), widenedRecordedInBedEnd = wake.plusSeconds(2_400),
                    widenedRecordedOnset = bed.plusSeconds(600), widenedRecordedWake = wake.plusSeconds(1_800), updatedAt = now2,
                ),
                after,
            )
            assertEquals(SleepEdit.RecordedWindow(bed.minusSeconds(1_200), wake.plusSeconds(2_400), bed.plusSeconds(600), wake.plusSeconds(1_800)), after.clampWindow)
            assertEquals(kvBefore, db.queryRaw("SELECT * FROM store_kv ORDER BY `key`"))
        }
    }

    /** The widening is outward only and cumulative: each staging widens the clamp already widened. */
    @Test
    fun theClampWidensOutwardOnlyAndAccumulatesAcrossStagings() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(bed, wake, 420, onset = bed.plusSeconds(1_800), wake = wake.minusSeconds(900))
            store.applySleepEdit(wake, SleepEdit.Times(bed.minusSeconds(3_600), bed.plusSeconds(600), wake), summary(540, 500), now = now1, zone = utc)

            store.save(bed.minusSeconds(600), wake, 420, onset = bed.plusSeconds(1_800), wake = wake.minusSeconds(900), now = now1)
            store.save(bed, wake.plusSeconds(1_200), 430, onset = bed.plusSeconds(1_800), wake = wake.plusSeconds(600), now = now2)

            val clamp = assertNotNull(store.sleepSummary(wake, utc)).clampWindow
            assertEquals(SleepEdit.RecordedWindow(bed.minusSeconds(600), wake.plusSeconds(1_200), bed.plusSeconds(1_800), wake.plusSeconds(600)), clamp)
        }
    }

    /** A staging inside the clamp widens nothing, and the kept edited night is not written at all. */
    @Test
    fun aStagingInsideTheClampOfAnEditedNightWritesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(bed, wake, 420, onset = bed.plusSeconds(1_800), wake = wake.minusSeconds(900))
            store.applySleepEdit(wake, SleepEdit.Times(bed.minusSeconds(3_600), bed.plusSeconds(600), wake), summary(540, 500), now = now1, zone = utc)
            store.latched()
            val before = db.snapshot()

            assertEquals(
                SleepPersistOutcome.KEPT_MANUAL_EDIT,
                store.save(bed.plusSeconds(3_600), wake.minusSeconds(3_600), 300, onset = bed.plusSeconds(3_700), wake = wake.minusSeconds(3_700), now = now2),
            )
            assertEquals(before, db.snapshot())
        }
    }

    /**
     * Upstream's sibling branch (:1712-1753, measured on a tester's night): a staging that refuses to
     * replace the stored night — its tail reads awake, so it has less sleep — may still reach further.
     * The minutes stay; the clamp window widens to the later end.
     */
    @Test
    fun aKeptFullerNightStillWidensItsClampToALaterStagingsReach() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(bed, wake, 420, onset = bed.plusSeconds(1_800), wake = wake.minusSeconds(900))
            val before = assertNotNull(store.sleepSummary(wake, utc))

            val outcome = store.save(bed, wake.plusSeconds(600), 410, onset = bed.plusSeconds(1_800), wake = wake.minusSeconds(900), now = now2)

            assertEquals(SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT, outcome)
            assertEquals(
                before.copy(
                    widenedRecordedInBedStart = bed, widenedRecordedInBedEnd = wake.plusSeconds(600),
                    widenedRecordedOnset = bed.plusSeconds(1_800), widenedRecordedWake = wake.minusSeconds(900), updatedAt = now2,
                ),
                store.sleepSummary(wake, utc),
            )
        }
    }

    @Test
    fun aKeptFullerNightThatNothingWidensIsNotWritten() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(bed, wake, 420)
            store.latched()
            val before = db.snapshot()

            assertEquals(SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT, store.save(bed.plusSeconds(3_600), wake, 300, now = now2))
            assertEquals(before, db.snapshot())
        }
    }

    /** A failed widen fails the save and changes nothing (one transaction, as every sleep write). */
    @Test
    fun aFailedWidenFailsTheSaveAndChangesNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            store.save(bed, wake, 420)
            store.applySleepEdit(wake, SleepEdit.Times(bed.minusSeconds(3_600), bed, wake), summary(540, 500), now = now1, zone = utc)
            store.latched()
            val before = db.snapshot()
            val real = db.sleepDao()
            val failing = object : SleepDao by real {
                override suspend fun updateSummary(row: StoredSleepSummaryEntity) = error("injected failure")
            }

            assertFails { SleepStore(db, failing).save(bed.minusSeconds(1_200), wake.plusSeconds(2_400), 520, now = now2) }

            assertEquals(before, db.snapshot())
        }
    }
}
