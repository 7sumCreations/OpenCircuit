package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import io.github.opencircuit.ringkit.SleepStaging
import io.github.opencircuit.ringkit.healthPublication
import io.github.opencircuit.ringkit.healthPublishable
import io.github.opencircuit.ringkit.healthUserEntered
import io.github.opencircuit.ringkit.withheldSpans
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitTests/SleepEditStoreTests.swift (@ b1c2fdd), all ten: the five that
 * call only the store's save, edit and read (`:38`, `:65`, `:96`, `:105`, `:124`) and the five that
 * call the Health write bookkeeping (`:141`, `:200`, `:235`, `:283`, `:332`), through
 * [SleepStore.pendingSleepEditHealthWrites], [SleepStore.markSleepEditHealthWritten],
 * [SleepStore.markSleepEditHealthCovered] and the sleep mark on [LocalStore].
 *
 * Upstream ran in the simulator's zone; here the zone is UTC. Each call names a fixed `now`. Where
 * upstream asserts on the model object it fetched, the night is read again.
 */
class SleepEditStoreTest {

    private val ref = Instant.ofEpochSecond(1_750_000_000) // 40 s into its minute
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2025-06-16T09:00:00Z")

    private fun at(hours: Double): Instant = ref.plusMillis((hours * 3_600_000).toLong())

    private fun summary(inBed: Duration, awake: Duration, light: Duration, deep: Duration, rem: Duration) =
        SleepStaging.Summary(inBed = inBed, awake = awake, light = light, deep = deep, rem = rem)

    private suspend fun seed(store: SleepStore) {
        store.saveSleepSummary(
            summary(Duration.ofHours(8), Duration.ofMinutes(30), Duration.ofHours(5), Duration.ofMinutes(90), Duration.ofMinutes(60)),
            night = at(0.0), inBedStart = at(0.0), inBedEnd = at(8.0), sleepOnset = at(0.5), sleepWake = at(7.75), now = now, zone = zone,
        )
    }

    /** Upstream `testFirstEditPersistsFlagOverlayAndImmutableRecordedAnchors` (`:38`). */
    @Test
    fun firstEditPersistsFlagOverlayAndImmutableRecordedAnchors() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val edited = SleepEdit.Window(inBedStart = at(-1.0), inBedEnd = at(9.0))
            val segments = SleepEdit.recompute(
                baseSegments = listOf(
                    SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED),
                    SleepSegment(at(0.0), at(8.0), SleepStage.ASLEEP_CORE),
                ),
                window = edited,
            )

            assertTrue(
                store.applySleepEdit(
                    at(0.0), editedWindow = edited, summary = SleepStaging.summary(segments),
                    sleepOnset = at(-1.0), sleepWake = at(9.0), now = now, zone = zone,
                ),
            )
            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertTrue(row.isManuallyEdited)
            assertEquals(at(-1.0), row.editedInBedStart)
            assertEquals(at(9.0), row.editedInBedEnd)
            assertEquals(at(0.0), row.inBedStart, "recorded anchors remain immutable")
            assertEquals(at(8.0), row.inBedEnd)
            assertEquals(at(0.5), row.sleepOnset)
            assertEquals(at(7.75), row.sleepWake)
            assertEquals(10 * 60, row.asleepMin)
            assertEquals(1.0, row.efficiency, 0.0001)
            assertTrue(row.sleepScore > 0)
        }
    }

    /** Upstream `testResyncCannotOverwriteManualEditAndReeditKeepsOriginalBounds` (`:65`). */
    @Test
    fun resyncCannotOverwriteManualEditAndReeditKeepsOriginalBounds() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val first = SleepEdit.Window(inBedStart = at(-1.0), inBedEnd = at(9.0))
            val firstSummary = summary(Duration.ofHours(10), Duration.ZERO, Duration.ofHours(10), Duration.ZERO, Duration.ZERO)
            assertTrue(
                store.applySleepEdit(
                    at(0.0), editedWindow = first, summary = firstSummary, sleepOnset = at(-1.0), sleepWake = at(9.0), now = now, zone = zone,
                ),
            )

            // A later sync is ignored once the explicit persisted flag is set.
            val replacement = summary(Duration.ofHours(2), Duration.ZERO, Duration.ofHours(2), Duration.ZERO, Duration.ZERO)
            store.saveSleepSummary(replacement, night = at(0.0), inBedStart = at(3.0), inBedEnd = at(5.0), now = now, zone = zone)
            assertEquals(at(-1.0), store.sleepSummary(at(0.0), zone)?.editedInBedStart)

            val second = SleepEdit.Window(inBedStart = at(-2.0), inBedEnd = at(10.0))
            val secondSummary = summary(Duration.ofHours(12), Duration.ZERO, Duration.ofHours(12), Duration.ZERO, Duration.ZERO)
            assertTrue(
                store.applySleepEdit(
                    at(0.0), editedWindow = second, summary = secondSummary, sleepOnset = at(-2.0), sleepWake = at(10.0), now = now, zone = zone,
                ),
            )
            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertEquals(at(0.5), row.sleepOnset)
            assertEquals(at(7.75), row.sleepWake)
            assertEquals(at(0.0), row.inBedStart)
            assertEquals(at(8.0), row.inBedEnd)
            assertEquals(at(-2.0), row.editedInBedStart)
            assertEquals(at(10.0), row.editedInBedEnd)
        }
    }

    /**
     * Upstream `testManualFlagIsNotInferredFromUncommittedOverlayDates` (`:96`). Upstream sets the two
     * edited dates on its fetched model object without saving; a stored night here is a value, so the
     * dates are written to the row directly, without the flag, and the night read back.
     */
    @Test
    fun manualFlagIsNotInferredFromUncommittedOverlayDates() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            db.execRaw(
                "UPDATE stored_sleep_summary SET edited_in_bed_start = ${at(-1.0).toEpochMilli()}, " +
                    "edited_in_bed_end = ${at(9.0).toEpochMilli()}",
            )

            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertFalse(row.isManuallyEdited)
            assertEquals(at(0.0), row.currentInBedStart, "dates without the flag are not an edit")
            assertEquals(at(8.0), row.currentInBedEnd)
        }
    }

    /** Upstream `testThreeTimeEditPersistsBedtimeSeparatelyFromSleepWindow` (`:105`). */
    @Test
    fun threeTimeEditPersistsBedtimeSeparatelyFromSleepWindow() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val times = SleepEdit.Times(inBedStart = at(-0.5), sleepOnset = at(0.5), sleepWake = at(8.0))
            val segments = SleepEdit.recompute(
                baseSegments = listOf(
                    SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED),
                    SleepSegment(at(0.5), at(8.0), SleepStage.ASLEEP_CORE),
                ),
                times = times,
            )

            assertTrue(store.applySleepEdit(at(0.0), times, SleepStaging.summary(segments), now = now, zone = zone))
            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertEquals(at(-0.5), row.currentInBedStart)
            assertEquals(at(0.5), row.currentOnset)
            assertEquals(at(8.0), row.currentWake)
            assertEquals(60, row.awakeMin)
            assertEquals(450, row.asleepMin)
        }
    }

    /**
     * Upstream `testVisuallyUnchangedRecordedWindowDoesNotBecomeManualEdit` (`:124`). The picker shows
     * minutes: `ref` is 40 s into its minute, so 10 s later is still the same displayed minute.
     */
    @Test
    fun visuallyUnchangedRecordedWindowDoesNotBecomeManualEdit() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val row = assertNotNull(store.sleepSummary(at(0.0), zone))
            val unchanged = SleepEdit.Window(inBedStart = row.inBedStart.plusSeconds(10), inBedEnd = row.inBedEnd.plusSeconds(10))

            assertTrue(
                store.applySleepEdit(
                    at(0.0), editedWindow = unchanged, summary = row.asSummary,
                    sleepOnset = row.sleepOnset, sleepWake = row.sleepWake, now = now, zone = zone,
                ),
            )
            val after = assertNotNull(store.sleepSummary(at(0.0), zone))
            assertFalse(after.isManuallyEdited)
            assertEquals(SleepEdit.DISTANT_PAST, after.editedInBedStart)
            assertEquals(SleepEdit.DISTANT_PAST, after.editedInBedEnd)
        }
    }

    // The Health half: the edit-extension bookkeeping, asserted through the store's own pending /
    // mark calls. The night these take is the stored row's key, as the writer passes it.

    private fun tenHours() = summary(Duration.ofHours(10), Duration.ZERO, Duration.ofHours(10), Duration.ZERO, Duration.ZERO)

    private suspend fun key(store: SleepStore): Instant = assertNotNull(store.sleepSummary(at(0.0), zone)).night

    /** Upstream `testLeadingHealthExtensionIsPendingRetryableAndIncrementalAcrossReedit` (`:141`). */
    @Test
    fun leadingHealthExtensionIsPendingRetryableAndIncrementalAcrossReedit() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            val local = LocalStore(db)
            seed(store)
            local.markSleepWritten(listOf(SleepSegment(at(0.0), at(8.0), SleepStage.ASLEEP_CORE)))

            val first = SleepEdit.Window(inBedStart = at(-1.0), inBedEnd = at(9.0))
            assertTrue(store.applySleepEdit(at(0.0), editedWindow = first, summary = tenHours(), sleepOnset = at(-1.0), sleepWake = at(9.0), now = now, zone = zone))

            val pending = store.pendingSleepEditHealthWrites()
            assertEquals(1, pending.size)
            assertEquals(listOf(SleepStage.IN_BED, SleepStage.ASLEEP_CORE, SleepStage.IN_BED, SleepStage.ASLEEP_CORE), pending[0].segments.map { it.stage })
            assertEquals(at(-1.0), pending[0].segments.first().start)
            assertEquals(at(0.0), pending[0].segments.first().end)
            assertEquals(at(8.0), pending[0].segments[2].start)
            assertEquals(at(9.0), pending[0].segments[2].end)
            // Merely reading pending work does not mark it; a failed or refused write retries identically.
            assertEquals(pending[0].segments, store.pendingSleepEditHealthWrites().first().segments)

            store.markSleepEditHealthWritten(pending[0].night, pending[0].segments)
            assertTrue(store.pendingSleepEditHealthWrites().isEmpty())
            assertTrue(
                local.pendingHealthSleep(listOf(SleepSegment(at(8.0), at(9.0), SleepStage.ASLEEP_CORE))).isEmpty(),
                "successful manual-tail retry must advance the shared sleep cursor",
            )

            val second = SleepEdit.Window(inBedStart = at(-2.0), inBedEnd = at(10.0))
            val secondSummary = summary(Duration.ofHours(12), Duration.ZERO, Duration.ofHours(12), Duration.ZERO, Duration.ZERO)
            assertTrue(store.applySleepEdit(at(0.0), editedWindow = second, summary = secondSummary, sleepOnset = at(-2.0), sleepWake = at(10.0), now = now, zone = zone))
            val incremental = store.pendingSleepEditHealthWrites().first()
            assertEquals(at(-2.0), incremental.segments.first().start)
            assertEquals(at(-1.0), incremental.segments.first().end, "re-edit must append only the newly exposed bedtime slice")
        }
    }

    /** Upstream `testTheAssertedLeadingExtensionIsWrittenWatermarkedAndNotOfferedTwice` (`:200`). */
    @Test
    fun theAssertedLeadingExtensionIsWrittenWatermarkedAndNotOfferedTwice() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            LocalStore(db).markSleepWritten(listOf(SleepSegment(at(0.0), at(8.0), SleepStage.ASLEEP_CORE)))

            val times = SleepEdit.Times(inBedStart = at(-1.0), sleepOnset = at(-1.0), sleepWake = at(9.0))
            assertTrue(store.applySleepEdit(at(0.0), times = times, summary = tenHours(), now = now, zone = zone))

            val proposed = store.pendingSleepEditHealthWrites().first().segments
            assertTrue(proposed.any { it.stage == SleepStage.ASLEEP_CORE && it.start == at(-1.0) })

            // The leading hour holds no records, so that core block is the wearer's own account. It is
            // PUBLISHED — and tagged as hers at the write site.
            val assertedLeading = proposed.map { seg ->
                if (seg.stage == SleepStage.ASLEEP_CORE && seg.start == at(-1.0)) seg.withProvenance(SleepProvenance.ASSERTED) else seg
            }
            val published = assertedLeading.healthPublishable
            assertTrue(published.any { it.stage == SleepStage.ASLEEP_CORE && it.start == at(-1.0) }, "precondition: the leading asleep block reaches Health")
            assertTrue(assertedLeading.healthUserEntered.any { it.stage == SleepStage.ASLEEP_CORE && it.start == at(-1.0) }, "…as the wearer's entry, not as a measurement")
            assertTrue(assertedLeading.withheldSpans.isEmpty(), "nothing withheld ⇒ nothing may be spared from the cleanup delete")

            store.markSleepEditHealthWritten(key(store), published)

            assertTrue(
                store.pendingSleepEditHealthWrites().isEmpty(),
                "every offered span reached Health, so nothing may be offered again — re-offering it is how a night gets written to Health twice",
            )
        }
    }

    /** Upstream `testTheWatermarkStillComesFromTheWRITTENSetNotTheProposedOne` (`:235`). */
    @Test
    fun theWatermarkStillComesFromTheWrittenSetNotTheProposedOne() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            LocalStore(db).markSleepWritten(listOf(SleepSegment(at(0.0), at(8.0), SleepStage.ASLEEP_CORE)))
            val times = SleepEdit.Times(inBedStart = at(-1.0), sleepOnset = at(-1.0), sleepWake = at(9.0))
            assertTrue(store.applySleepEdit(at(0.0), times = times, summary = tenHours(), now = now, zone = zone))
            val proposed = store.pendingSleepEditHealthWrites().first().segments

            val asIfWithheld = proposed.filterNot { it.stage == SleepStage.ASLEEP_CORE && it.start == at(-1.0) }
            store.markSleepEditHealthWritten(key(store), asIfWithheld)

            val after = store.pendingSleepEditHealthWrites().firstOrNull()
            assertNotNull(after, "a span Health never received must still be offered")
            assertTrue(after.segments.any { it.stage == SleepStage.ASLEEP_CORE && it.start == at(-1.0) }, "watermarking from the proposal would retire it permanently")
            assertFalse(after.segments.any { it.stage == SleepStage.IN_BED && it.start == at(-1.0) }, "the in-bed hour WAS written, so it must not be offered again")
        }
    }

    /** Upstream `testALeadingPROVENHoleIsOfferedToAppleHealthOnlyAsTheWearersOwnEntry` (`:283`). */
    @Test
    fun aLeadingProvenHoleIsOfferedToHealthOnlyAsTheWearersOwnEntry() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            LocalStore(db).markSleepWritten(listOf(SleepSegment(at(0.0), at(8.0), SleepStage.ASLEEP_CORE)))

            // The hypnogram the primary path stored for this edit: bedtime dragged back one hour into
            // ground the records PROVE holds nothing, over a night the ring did record.
            val hypnogram = listOf(
                SleepSegment(at(-1.0), at(0.0), SleepStage.IN_BED, SleepProvenance.ASSERTED),
                SleepSegment(at(-1.0), at(0.0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
                SleepSegment(at(0.0), at(8.0), SleepStage.IN_BED),
                SleepSegment(at(0.0), at(8.0), SleepStage.ASLEEP_CORE),
            )
            val times = SleepEdit.Times(inBedStart = at(-1.0), sleepOnset = at(-1.0), sleepWake = at(8.0))
            assertTrue(
                store.applySleepEdit(
                    at(0.0), times = times,
                    summary = summary(Duration.ofHours(9), Duration.ZERO, Duration.ofHours(9), Duration.ZERO, Duration.ZERO),
                    hypnogram = hypnogram, now = now, zone = zone,
                ),
            )

            val offered = store.pendingSleepEditHealthWrites().first().segments
            val holeStart = at(-1.0)
            val holeEnd = at(0.0)
            val asleep = setOf(SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM)
            fun overHole(s: SleepSegment) = s.start < holeEnd && s.end > holeStart

            assertTrue(offered.any { it.stage in asleep && overHole(it) }, "precondition: the second path proposes asleep time across the leading hour")
            for (seg in offered.healthPublication.measured.filter { it.stage in asleep }) {
                assertFalse(overHole(seg), "an UNTAGGED asleep sample over the PROVEN hole reaches Health: $seg")
            }
            assertTrue(offered.healthUserEntered.any { it.stage in asleep && overHole(it) }, "the hour she asserted must reach Health, marked as entered by her")
            assertTrue(offered.healthPublishable.any { it.stage == SleepStage.IN_BED && it.start == at(-1.0) }, "the wearer's in-bed claim must survive")
            assertTrue(offered.healthUserEntered.any { it.stage == SleepStage.IN_BED && it.start == at(-1.0) }, "…tagged, because over a proven hole the only source for it is her")
        }
    }

    /** Upstream `testNormalFullNightWriteCoversLeadingEditWithoutSecondAppend` (`:332`). */
    @Test
    fun normalFullNightWriteCoversLeadingEditWithoutSecondAppend() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            seed(store)
            val edited = SleepEdit.Window(inBedStart = at(-1.0), inBedEnd = at(9.0))
            assertTrue(store.applySleepEdit(at(0.0), editedWindow = edited, summary = tenHours(), sleepOnset = at(-1.0), sleepWake = at(9.0), now = now, zone = zone))

            val full = listOf(SleepSegment(at(-1.0), at(9.0), SleepStage.ASLEEP_CORE))
            LocalStore(db).markSleepWritten(full)
            store.markSleepEditHealthCovered(full)
            assertTrue(store.pendingSleepEditHealthWrites().isEmpty())
            assertTrue(store.pendingSleepEditHealthWrites().isEmpty())
        }
    }
}
