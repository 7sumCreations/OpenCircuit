package io.github.opencircuit.ringkit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-end tracer for the night bookkeeping: a wearer's edit to a night's bedtime, sleep onset and
 * wake, and a nap she adds, survive re-syncing the same records through the real pipeline and the
 * merge — each field checked on what the store would show, not just that a merge happened.
 *
 * The stores themselves are the store epic's; here a minimal store stands in, built only from the
 * ported decisions and kept to the upstream flow: a night is filed under its night key; the RING's
 * own staged night is kept apart from any edit and merged by completeness; an edited night is kept
 * (the edit is authoritative) and what it shows is re-derived by applying the edit to the ring's OWN
 * segments — never to a previous edit's output, which upstream's recompute does not reproduce on a
 * gapped night — and persisted through the stored-hypnogram codec.
 *
 * The night is a recorded `0x4c` night from the differential set (rebuilt on the raw byte path),
 * selected and staged by the real pipeline in UTC: in bed 2026-06-07 23:00:41 → 06-08 07:26:11.
 */
class NightBookkeepingTracerTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val night = SleepDifferentialFixtures.inputs().single { it.id == "synthetic-000" }
    private val asleepStages = setOf(SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM)

    /** One drain: select the latest night from the records and stage it, exactly as a sync would. */
    private fun stage(records: List<BulkRecord>): List<SleepSegment> =
        BulkSleep.stagedSegments(BulkSleep.latestNightRecords(records, zone = zone, temperatures = night.temps))

    private class Row(val key: Instant, val ring: List<SleepSegment>, val edit: SleepEdit.Times?, val hypnogram: ByteArray)

    /** The minimal stand-in store: night rows by key, plus the wearer's naps. */
    private inner class Store {
        val rows = LinkedHashMap<Instant, Row>()
        val naps = ArrayList<NapEdit.Window>()

        fun shown(key: Instant): List<SleepSegment> = SleepHypnogramCodec.decode(rows.getValue(key).hypnogram)

        private fun inBed(segs: List<SleepSegment>): DateInterval {
            val layer = segs.filter { it.stage == SleepStage.IN_BED }
            return DateInterval(layer.minOf { it.start }, layer.maxOf { it.end })
        }

        /** The store's same-coverage rule, as upstream's store computes it: both in-bed edges within one epoch. */
        private fun sameCoverage(a: List<SleepSegment>, b: List<SleepSegment>): Boolean {
            val x = inBed(a)
            val y = inBed(b)
            val tolerance = Duration.ofSeconds(BulkRecord.EPOCH_SECONDS.toLong())
            return Duration.between(x.start, y.start).abs() <= tolerance && Duration.between(x.end, y.end).abs() <= tolerance
        }

        fun sync(records: List<BulkRecord>): SleepPersistOutcome {
            val ring = stage(records)
            if (ring.isEmpty()) return SleepPersistOutcome.NO_STAGED_SEGMENTS
            val key = SleepNightKey.night(ring, zone) ?: return SleepPersistOutcome.FAILED
            val existing = rows[key]
            if (existing == null) {
                rows[key] = Row(key, ring, null, SleepHypnogramCodec.encode(ring))
                return SleepPersistOutcome.INSERTED
            }
            // The ring layer is merged by completeness whatever the edit state ...
            val stored = SleepStaging.summary(existing.ring)
            val fresh = SleepStaging.summary(ring)
            val replaceRing = SleepSummaryMerge.shouldReplace(
                storedInBed = stored.inBed,
                newInBed = fresh.inBed,
                storedAsleep = stored.totalAsleep,
                newAsleep = fresh.totalAsleep,
                sameCoverage = sameCoverage(existing.ring, ring),
            )
            val mergedRing = if (replaceRing) ring else existing.ring
            val edit = existing.edit
            if (edit != null) {
                // ... and the wearer's edit wins: re-applied to the ring's OWN segments.
                rows[key] = Row(key, mergedRing, edit, SleepHypnogramCodec.encode(SleepEdit.recompute(mergedRing, edit)))
                return SleepPersistOutcome.KEPT_MANUAL_EDIT
            }
            if (!replaceRing) return SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT
            rows[key] = Row(key, ring, null, SleepHypnogramCodec.encode(ring))
            return SleepPersistOutcome.UPDATED
        }

        fun edit(key: Instant, times: SleepEdit.Times): SleepEdit.Invalid? {
            val row = rows.getValue(key)
            val recorded = assertNotNull(SleepStaging.sleepWindow(row.ring))
            SleepEdit.validate(times, recordedOnset = recorded.onset, recordedWake = recorded.wake)?.let { return it }
            rows[key] = Row(key, row.ring, times, SleepHypnogramCodec.encode(SleepEdit.recompute(row.ring, times)))
            return null
        }

        fun addNap(w: NapEdit.Window, now: Instant): NapEdit.Invalid? {
            val nightWindow = rows.values.lastOrNull()?.let { inBed(shown(it.key)) }
            NapEdit.validate(w, zone, night = nightWindow, otherNaps = naps.map { DateInterval(it.start, it.end) }, now = now)?.let { return it }
            naps += w
            return null
        }

        /** The one-shot re-key migration: plan from the stored rows, apply the moves in order. */
        fun migrate(): SleepNightRekeyPlan.Plan {
            val plan = SleepNightRekeyPlan.plan(
                rows.values.map { r -> inBed(shown(r.key)).let { SleepNightRekeyPlan.Row(night = r.key, inBedStart = it.start, inBedEnd = it.end) } },
                zone,
            )
            for (m in plan.moves) {
                val r = rows.remove(m.from)!!
                rows[m.to] = Row(m.to, r.ring, r.edit, r.hypnogram)
            }
            return plan
        }
    }

    private val wakeDay = Instant.parse("2026-06-08T00:00:00Z")
    private val edit = SleepEdit.Times(
        inBedStart = Instant.parse("2026-06-07T22:30:00Z"), // earlier than the ring's 23:00:41
        sleepOnset = Instant.parse("2026-06-07T23:15:00Z"), // later than the ring's onset
        sleepWake = Instant.parse("2026-06-08T07:45:00Z"), // later than the ring's 07:26:11
    )
    private val nap = NapEdit.Window(Instant.parse("2026-06-08T14:00:00Z"), Instant.parse("2026-06-08T14:45:00Z"))
    private val afternoon = Instant.parse("2026-06-08T18:00:00Z")

    /** Every edited field, read back from what the store shows. */
    private fun assertTheEditIsShown(store: Store, key: Instant, why: String) {
        val shown = store.shown(key)
        val inBed = shown.filter { it.stage == SleepStage.IN_BED }
        val asleep = shown.filter { it.stage in asleepStages }
        assertEquals(edit.inBedStart, inBed.minOf { it.start }, "bedtime — $why")
        assertEquals(edit.sleepOnset, asleep.minOf { it.start }, "sleep onset — $why")
        assertEquals(edit.sleepWake, inBed.maxOf { it.end }, "wake (in-bed end) — $why")
        assertEquals(edit.sleepWake, asleep.maxOf { it.end }, "wake (sleep end) — $why")
        assertTrue(
            shown.any { it.stage == SleepStage.AWAKE && it.start == edit.inBedStart && it.end == edit.sleepOnset },
            "bedtime to onset is awake in bed — $why",
        )
        assertEquals(key, SleepNightKey.night(shown, zone), "the edited night still files under its key — $why")
        assertEquals(listOf(nap), store.naps, "the nap is still there — $why")
        assertNull(
            NapEdit.validate(nap, zone, night = DateInterval(inBed.minOf { it.start }, inBed.maxOf { it.end }), now = afternoon),
            "the nap still sits clear of the re-synced night — $why",
        )
    }

    @Test
    fun anEditedBedtimeOnsetWakeAndNapSurviveReSyncAndMerge() {
        val store = Store()

        // First drain: the whole night lands under the WAKE day.
        assertEquals(SleepPersistOutcome.INSERTED, store.sync(night.records))
        assertEquals(listOf(wakeDay), store.rows.keys.toList())
        val ring = store.rows.getValue(wakeDay).ring
        val ringInBed = ring.filter { it.stage == SleepStage.IN_BED }
        assertEquals(Instant.parse("2026-06-07T23:00:41Z"), ringInBed.minOf { it.start }, "the pipeline staged the expected night")
        assertEquals(Instant.parse("2026-06-08T07:26:11Z"), ringInBed.maxOf { it.end })

        // A morning drain of only the last ~4.75 h cannot shrink the fuller stored night.
        val wake = ringInBed.maxOf { it.end }
        val morning = night.records.filter { !it.date().isBefore(wake.minus(SleepCaptureCoverage.RING_BUFFER)) }
        assertTrue(morning.size < night.records.size && stage(morning).isNotEmpty(), "the morning slice is a real, shorter night")
        val kept = store.sync(morning)
        assertEquals(SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT, kept)
        assertTrue(kept.nightIsStored && !kept.wroteRow)
        assertEquals(ring, store.rows.getValue(wakeDay).ring)

        // The wearer corrects bedtime, onset and wake, and adds an afternoon nap.
        assertNull(store.edit(wakeDay, edit))
        assertNull(store.addNap(nap, now = afternoon))
        assertTheEditIsShown(store, wakeDay, "right after the edit")
        val shownAfterEdit = store.rows.getValue(wakeDay).hypnogram

        // Re-sync the SAME records, three times: the edit is kept and re-derived from the ring's own night.
        repeat(3) { round ->
            val outcome = store.sync(night.records)
            assertEquals(SleepPersistOutcome.KEPT_MANUAL_EDIT, outcome, "re-sync $round")
            assertTrue(outcome.nightIsStored && !outcome.isSilentLoss)
            assertEquals(listOf(wakeDay), store.rows.keys.toList(), "no second night was filed")
            assertTheEditIsShown(store, wakeDay, "after re-sync $round")
            assertContentEquals(shownAfterEdit, store.rows.getValue(wakeDay).hypnogram, "the stored night is byte-identical after re-sync $round")
            assertEquals(ring, store.rows.getValue(wakeDay).ring, "the ring's own night is unchanged")
        }

        // A thinner morning drain after the edit: the ring layer keeps the fuller night, the edit still wins.
        assertEquals(SleepPersistOutcome.KEPT_MANUAL_EDIT, store.sync(morning))
        assertEquals(ring, store.rows.getValue(wakeDay).ring)
        assertTheEditIsShown(store, wakeDay, "after a thinner morning drain")

        // A drain that stages nothing touches nothing.
        val nothing = store.sync(night.records.take(1))
        assertEquals(SleepPersistOutcome.NO_STAGED_SEGMENTS, nothing)
        assertTrue(nothing.isSilentLoss && nothing.isRecoverableByRetry)
        assertTheEditIsShown(store, wakeDay, "after an empty drain")
    }

    /**
     * A night filed under the OLD key (the bedtime's day) before the re-key migration: unmigrated, a
     * re-sync files a SECOND night under the wake day — the duplicate the migration exists to stop.
     * Migrated, the edited row moves to the wake day and the re-sync lands on it, edit intact.
     */
    @Test
    fun theReKeyMigrationCarriesTheEditSoAReSyncFindsIt() {
        fun legacyStore(): Store {
            val s = Store()
            assertEquals(SleepPersistOutcome.INSERTED, s.sync(night.records))
            assertNull(s.edit(wakeDay, edit))
            assertNull(s.addNap(nap, now = afternoon))
            val row = s.rows.remove(wakeDay)!!
            val bedtimeDay = Instant.parse("2026-06-07T00:00:00Z") // start of day of the edited bedtime
            s.rows[bedtimeDay] = Row(bedtimeDay, row.ring, row.edit, row.hypnogram)
            return s
        }

        val unmigrated = legacyStore()
        assertEquals(SleepPersistOutcome.INSERTED, unmigrated.sync(night.records), "without the migration a duplicate night is filed")
        assertEquals(2, unmigrated.rows.size)

        val store = legacyStore()
        val plan = store.migrate()
        assertEquals(listOf(SleepNightRekeyPlan.Move(from = Instant.parse("2026-06-07T00:00:00Z"), to = wakeDay)), plan.moves)
        assertTrue(plan.refused.isEmpty())
        assertEquals(SleepNightRekeyPlan.Plan(emptyList(), emptyList()), store.migrate(), "a second pass moves nothing")
        assertEquals(SleepPersistOutcome.KEPT_MANUAL_EDIT, store.sync(night.records))
        assertEquals(listOf(wakeDay), store.rows.keys.toList())
        assertTheEditIsShown(store, wakeDay, "after the migration and a re-sync")
        assertFalse(store.rows.getValue(wakeDay).edit == null)
    }
}
