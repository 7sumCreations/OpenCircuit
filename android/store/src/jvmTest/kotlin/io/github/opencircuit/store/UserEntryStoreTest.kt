package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The period log's save rules, and the cases of the headache and risk-row rules the ported upstream
 * tests do not reach. Upstream has no period-store test; each period case below is read from
 * ios/OpenCircuit/CycleStore.swift (@ b1c2fdd), the line range in its KDoc. The risk cases are read
 * from ios/OpenCircuit/Store/HeadacheStore.swift :342-406.
 *
 * Kotlin-only cases: instants carry nanoseconds and the store keeps milliseconds, so a value that
 * differs only below the millisecond must behave as the same value; a write that fails part-way
 * must leave nothing changed (one transaction); and a move whose original row has vanished must
 * not lose the row at the destination (upstream deletes it first).
 */
class UserEntryStoreTest {

    private val ref = Instant.ofEpochSecond(1_785_000_000L)

    private fun at(hours: Long): Instant = ref.plusSeconds(hours * 3_600)

    private val now = at(100)

    // Period log

    /** Re-saving the same start replaces the entry (CycleStore.swift :114-133). */
    @Test
    fun aPeriodIsUpsertedByStart() = withStore { store, _ ->
        store.savePeriodEntry(start = at(0), end = null, flowLevelRaw = 2, symptoms = listOf("cramping"), notes = "", now = at(1))
        store.savePeriodEntry(start = at(0), end = at(96), flowLevelRaw = 3, symptoms = listOf("cramping", "bloating"), notes = "n", now = at(2))

        val rows = store.allPeriodEntries()
        assertEquals(1, rows.size)
        assertEquals(PeriodEntry(at(0), at(96), 3, listOf("cramping", "bloating"), "n", false, emptyList(), at(2)), rows[0])
    }

    /**
     * A change of flow, end or symptoms clears the health watermark and keeps the written ids; a
     * notes-only edit does not (CycleStore.swift :117-128).
     */
    @Test
    fun aClinicalPeriodChangeClearsTheWatermarkAndANotesOnlyEditDoesNot() = withStore { store, db ->
        val edits = mapOf<String, suspend () -> Unit>(
            "flow" to { store.savePeriodEntry(at(0), at(96), 3, listOf("cramping"), "", now) },
            "end" to { store.savePeriodEntry(at(0), null, 2, listOf("cramping"), "", now) },
            "symptoms" to { store.savePeriodEntry(at(0), at(96), 2, listOf("bloating"), "", now) },
            "symptom order" to { store.savePeriodEntry(at(0), at(96), 2, listOf("b", "a"), "", now) },
            "notes only" to { store.savePeriodEntry(at(0), at(96), 2, listOf("cramping"), "a note", now) },
        )
        for ((what, edit) in edits) {
            store.deletePeriodEntry(at(0))
            store.savePeriodEntry(at(0), at(96), 2, if (what == "symptom order") listOf("a", "b") else listOf("cramping"), "", now)
            recordPeriodHealthWrite(db, at(0), listOf("hk-1"))

            edit()

            val row = store.allPeriodEntries().single()
            assertEquals(what == "notes only", row.healthWritten, what)
            assertEquals(listOf("hk-1"), row.hkSampleUUIDs, what)
        }
    }

    /**
     * Moving a period's start relocates its row, appends the displaced row's ids to its own and
     * clears the watermark; with nothing at the destination it is a plain relocation
     * (CycleStore.swift :89-110).
     */
    @Test
    fun aMovedPeriodRelocatesItsRowAndCarriesTheDisplacedRowsIds() = withStore { store, db ->
        store.savePeriodEntry(at(0), at(48), 2, emptyList(), "", now)
        recordPeriodHealthWrite(db, at(0), listOf("hk-original"))
        store.savePeriodEntry(at(24), at(72), 1, emptyList(), "", now)
        recordPeriodHealthWrite(db, at(24), listOf("hk-clash"))
        store.savePeriodEntry(at(200), at(240), 2, emptyList(), "", now)
        recordPeriodHealthWrite(db, at(200), listOf("hk-lone"))

        store.savePeriodEntry(at(24), at(72), 3, listOf("cramping"), "moved", at(101), originalStart = at(0))
        store.savePeriodEntry(at(300), at(340), 2, emptyList(), "", at(102), originalStart = at(200))

        assertEquals(
            listOf(
                PeriodEntry(at(24), at(72), 3, listOf("cramping"), "moved", false, listOf("hk-original", "hk-clash"), at(101)),
                PeriodEntry(at(300), at(340), 2, emptyList(), "", false, listOf("hk-lone"), at(102)),
            ),
            store.allPeriodEntries(),
        )
    }

    /** Deleting returns the written ids; deleting nothing returns none (CycleStore.swift :141-149). */
    @Test
    fun deletingAPeriodReturnsItsWrittenIds() = withStore { store, db ->
        store.savePeriodEntry(at(0), null, 2, emptyList(), "", now)
        store.savePeriodEntry(at(-500), null, 2, emptyList(), "", now)
        recordPeriodHealthWrite(db, at(0), listOf("hk-1", "hk-2"))

        assertEquals(listOf("hk-1", "hk-2"), store.deletePeriodEntry(at(0)))
        assertEquals(emptyList(), store.deletePeriodEntry(at(0)))
        assertEquals(listOf(at(-500)), store.allPeriodEntries().map { it.start })
    }

    /** All entries, oldest start first (CycleStore.swift :152-156). */
    @Test
    fun periodsAreListedOldestFirst() = withStore { store, _ ->
        for (h in listOf(48L, -24L, 0L)) store.savePeriodEntry(at(h), null, 2, emptyList(), "", now)

        assertEquals(listOf(at(-24), at(0), at(48)), store.allPeriodEntries().map { it.start })
    }

    // Divergence: a move whose original has vanished

    /**
     * Upstream deletes the row at the destination before it looks for the original, and when the
     * original is gone (a stale edit screen) falls through to a plain save: the destination row and
     * its written ids are lost. Here the destination row is kept and edited in place by the plain
     * save rules, so its ids stay nameable.
     */
    @Test
    fun aMoveFromAVanishedOriginalKeepsTheRowAlreadyAtTheDestination() = withStore { store, db ->
        store.savePeriodEntry(at(24), at(72), 1, emptyList(), "", now)
        recordPeriodHealthWrite(db, at(24), listOf("hk-period"))
        store.saveHeadacheEntry(onset = at(5), end = at(6), severityRaw = 2, symptoms = emptyList(), now = now)
        recordHealthWrite(db, at(5), listOf("hk-headache"), finalized = true)

        store.savePeriodEntry(at(24), at(72), 3, emptyList(), "", now, originalStart = at(0))
        store.saveHeadacheEntry(onset = at(5), end = at(6), severityRaw = 2, symptoms = emptyList(), notes = "x", originalOnset = at(0), now = now)

        val period = store.allPeriodEntries().single()
        assertEquals(listOf("hk-period"), period.hkSampleUUIDs)
        assertEquals(3, period.flowLevelRaw)
        assertFalse(period.healthWritten, "flow changed: a clinical change")
        val headache = store.allHeadacheEntries().single()
        assertEquals(listOf("hk-headache"), headache.hkSampleUUIDs)
        assertEquals("x", headache.notes)
        assertTrue(headache.healthWritten, "a notes-only edit")
    }

    // Kotlin-only: milliseconds

    /** An end that differs from the stored one only below the millisecond is not a clinical change. */
    @Test
    fun aSubMillisecondDifferenceIsNotAClinicalChange() = withStore { store, db ->
        store.saveHeadacheEntry(onset = at(0), end = at(2), severityRaw = 3, symptoms = emptyList(), now = now)
        recordHealthWrite(db, at(0), listOf("hk-1"), finalized = true)

        store.saveHeadacheEntry(onset = at(0).plusNanos(400_000), end = at(2).plusNanos(999_999), severityRaw = 3, symptoms = emptyList(), now = now)

        assertTrue(store.allHeadacheEntries().single().healthWritten)
    }

    /**
     * A "move" whose original and new key are the same millisecond is an edit of that row: the row
     * is not deleted as its own clash.
     */
    @Test
    fun aMoveWithinTheSameMillisecondEditsTheRowInsteadOfDeletingIt() = withStore { store, db ->
        store.savePeriodEntry(at(0), null, 2, emptyList(), "", now)
        recordPeriodHealthWrite(db, at(0), listOf("hk-1"))

        store.savePeriodEntry(at(0).plusNanos(700_000), null, 2, emptyList(), "edited", now, originalStart = at(0).plusNanos(100_000))

        val row = store.allPeriodEntries().single()
        assertEquals(at(0), row.start)
        assertEquals("edited", row.notes)
        assertEquals(listOf("hk-1"), row.hkSampleUUIDs)
        assertTrue(row.healthWritten)
    }

    // Kotlin-only: one transaction

    /** A move that fails while writing the relocated row leaves the displaced row and the original as they were. */
    @Test
    fun aMoveThatFailsPartWayChangesNothing() = withStore { store, db ->
        store.saveHeadacheEntry(onset = at(0), end = at(1), severityRaw = 3, symptoms = emptyList(), now = now)
        recordHealthWrite(db, at(0), listOf("hk-original"), finalized = true)
        store.saveHeadacheEntry(onset = at(5), end = at(6), severityRaw = 2, symptoms = emptyList(), now = now)
        recordHealthWrite(db, at(5), listOf("hk-clash"), finalized = true)
        val before = store.allHeadacheEntries()

        assertFailsWith<IllegalArgumentException> {
            store.saveHeadacheEntry(
                onset = at(5), end = at(6), severityRaw = 3, symptoms = listOf("lone \uD83E"), originalOnset = at(0), now = now,
            )
        }

        assertEquals(before, store.allHeadacheEntries())
    }

    // Headache rules the ported tests do not reach

    /** An edit keeps the stored source and imported id; only a new entry takes them (HeadacheStore.swift :249-266). */
    @Test
    fun anEditKeepsTheEntrysSourceAndImportedId() = withStore { store, _ ->
        store.saveHeadacheEntry(
            onset = at(0), end = at(1), severityRaw = 2, symptoms = emptyList(),
            source = HeadacheSource.HEALTH_IMPORT, importedHKUUID = "hk-imported", now = at(1),
        )
        store.saveHeadacheEntry(onset = at(0), end = at(1), severityRaw = 3, symptoms = emptyList(), now = at(2))

        val row = store.allHeadacheEntries().single()
        assertEquals(HeadacheSource.HEALTH_IMPORT, row.source)
        assertEquals("hk-imported", row.importedHKUUID)
        assertEquals(at(2), row.updatedAt)
    }

    /** `headacheEntries(from, to)` is `[from, to)`, oldest first (HeadacheStore.swift :292-297). */
    @Test
    fun headacheEntriesInARangeIncludeTheStartAndExcludeTheEnd() = withStore { store, _ ->
        for (h in listOf(10L, 0L, 5L, -1L)) store.saveHeadacheEntry(onset = at(h), end = null, severityRaw = 0, symptoms = emptyList(), now = now)

        assertEquals(listOf(at(0), at(5)), store.headacheEntries(from = at(0), to = at(10)).map { it.onset })
    }

    /** An unknown stored source reads as the user's own entry (HeadacheStore.swift :99). */
    @Test
    fun anUnknownStoredSourceReadsAsUser() = withStore { store, db ->
        db.execRaw("INSERT INTO stored_headache_entry (onset, source_raw) VALUES (${at(0).toEpochMilli()}, 'fromTheFuture')")

        val row = store.allHeadacheEntries().single()
        assertEquals(HeadacheSource.USER, row.source)
        assertEquals("fromTheFuture", row.sourceRaw)
    }

    // Risk rows

    /**
     * The same night under another day key (a time-zone change) is refused; any number of days
     * scored without a night (`distantPast`) are accepted (HeadacheStore.swift :342-364).
     */
    @Test
    fun theSameNightUnderAnotherDayIsRefusedButDaysWithoutANightAreNot() = withStore { store, _ ->
        val night = at(-30)
        assertTrue(store.insertRiskDayIfAbsent(risk(day = at(0), nightKey = night, index = 10.0)))
        assertFalse(store.insertRiskDayIfAbsent(risk(day = at(6), nightKey = night, index = 90.0)))
        assertTrue(store.insertRiskDayIfAbsent(risk(day = at(24), nightKey = SleepEdit.DISTANT_PAST, index = 20.0)))
        assertTrue(store.insertRiskDayIfAbsent(risk(day = at(48), nightKey = SleepEdit.DISTANT_PAST, index = 30.0)))

        assertEquals(listOf(10.0, 20.0, 30.0), store.riskDays(at(-100), at(100)).map { it.index })
        assertEquals(10.0, assertNotNull(store.riskRow(night)).index)
        assertNull(store.riskRow(SleepEdit.DISTANT_PAST), "no night key, no lookup")
        assertNull(store.riskRow(at(-31)))
    }

    /**
     * A re-stage is recorded once: a later re-stage keeps the first time. Marks on a day with no row
     * write nothing (HeadacheStore.swift :386-406).
     */
    @Test
    fun aRestageIsRecordedOnceAndMarksOnAMissingDayWriteNothing() = withStore { store, _ ->
        store.insertRiskDayIfAbsent(risk(day = at(0), nightKey = at(-30), index = 50.0))

        store.markRiskRestaged(day = at(0), sleepUpdatedAt = at(3), now = at(4))
        store.markRiskRestaged(day = at(0), sleepUpdatedAt = at(8), now = at(9))
        store.markRiskRestaged(day = at(24), sleepUpdatedAt = at(8), now = at(9))
        store.markRiskAlerted(day = at(24), now = at(9))

        val row = store.riskDays(at(-100), at(100)).single()
        assertEquals(at(3), row.sleepUpdatedAt)
        assertEquals(at(4), row.updatedAt)
        assertTrue(row.sleepRestaged)
        assertFalse(row.alerted)
        assertEquals(50.0, row.index)
    }

    /** A risk day's range is `[from, to)` by day, oldest first (HeadacheStore.swift :377-382). */
    @Test
    fun riskDaysInARangeIncludeTheStartAndExcludeTheEnd() = withStore { store, _ ->
        for (h in listOf(48L, 0L, 24L, -24L)) store.insertRiskDayIfAbsent(risk(day = at(h), nightKey = SleepEdit.DISTANT_PAST, index = h.toDouble()))

        assertEquals(listOf(0.0, 24.0), store.riskDays(from = at(0), to = at(48)).map { it.index })
    }

    /**
     * Upstream checks no raw value and no `end >= start` at save (CycleStore.swift :81-135,
     * HeadacheStore.swift :206-268, :332-406), and its readers expect odd values: the health import
     * stores any severity another app wrote (HealthKitWriter.swift :1065), an unknown band is a
     * newer build's row after a downgrade (HeadacheEngine.swift :849-856), and the health writer
     * clamps an end before the start (:945-951). So the store keeps them exactly as given.
     */
    @Test
    fun oddRawValuesAndAnEndBeforeTheStartAreKeptExactlyAsGiven() = withStore { store, _ ->
        store.savePeriodEntry(start = at(10), end = at(5), flowLevelRaw = 0, symptoms = emptyList(), notes = "", now = now)
        store.saveHeadacheEntry(onset = at(10), end = at(5), severityRaw = 99, symptoms = emptyList(), now = now)
        store.saveHeadacheEntry(onset = at(20), end = null, severityRaw = -1, symptoms = emptyList(), now = now)
        store.insertRiskDayIfAbsent(risk(day = at(0), nightKey = SleepEdit.DISTANT_PAST, index = 250.0).copy(bandRaw = 7, ringFeatureCount = -3))

        val period = store.allPeriodEntries().single()
        assertEquals(0, period.flowLevelRaw)
        assertEquals(at(5), period.end)
        assertEquals(listOf(99 to at(5), -1 to null), store.allHeadacheEntries().map { it.severityRaw to it.end })
        val row = store.riskDays(from = at(-100), to = at(100)).single()
        assertEquals(Triple(7, -3, 250.0), Triple(row.bandRaw, row.ringFeatureCount, row.index))
    }

    /**
     * Kotlin-only: a NaN score would be bound as NULL (NOT NULL fails with an `SQLiteException`)
     * and ±∞ would be frozen as a day's score for good. Either is refused before the database with
     * an `IllegalArgumentException`, and no row is written.
     */
    @Test
    fun aRiskDayWithAScoreThatIsNotFiniteIsRefusedBeforeTheDatabase() = withStore { store, _ ->
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val day = risk(day = at(0), nightKey = SleepEdit.DISTANT_PAST, index = 10.0)
            assertFailsWith<IllegalArgumentException>("index $v") { store.insertRiskDayIfAbsent(day.copy(index = v)) }
            assertFailsWith<IllegalArgumentException>("coverage $v") { store.insertRiskDayIfAbsent(day.copy(coverageFraction = v)) }
        }
        assertEquals(emptyList(), store.riskDays(from = at(-100), to = at(100)))
    }

    /**
     * Kotlin-only (I-30 / PL-2026-10-01-o): an entry the store hands out shares no list with the
     * row it was built from, and its lists cannot be changed by casting them to `MutableList`
     * (Swift's arrays copy; a Kotlin `List` from the column reader is an `ArrayList`).
     */
    @Test
    fun anEntrysListsAreItsOwnAndCannotBeChanged() {
        val tags = mutableListOf("cramping")
        val ids = mutableListOf("uuid-1")
        val period = StoredPeriodEntryEntity(start = at(0), symptoms = tags, hkSampleUUIDs = ids).toPeriodEntry()
        val custom = mutableListOf("aura")
        val factors = mutableListOf("sleep")
        val headache = StoredHeadacheEntryEntity(
            onset = at(0), symptoms = tags, customSymptoms = custom, factors = factors, hkSampleUUIDs = ids,
        ).toHeadacheEntry()

        tags += "bloating"
        ids += "uuid-2"
        custom += "x"
        factors += "y"

        assertEquals(listOf("cramping"), period.symptoms)
        assertEquals(listOf("uuid-1"), period.hkSampleUUIDs)
        assertEquals(listOf("cramping"), headache.symptoms)
        assertEquals(listOf("aura"), headache.customSymptoms)
        assertEquals(listOf("sleep"), headache.factors)
        assertEquals(listOf("uuid-1"), headache.hkSampleUUIDs)

        val lists = listOf(period.symptoms, period.hkSampleUUIDs, headache.symptoms, headache.customSymptoms, headache.factors, headache.hkSampleUUIDs)
        for (list in lists) {
            @Suppress("UNCHECKED_CAST")
            assertFailsWith<UnsupportedOperationException> { (list as MutableList<String>).add("z") }
        }
        assertEquals(listOf("cramping"), period.symptoms)
        assertEquals(listOf("uuid-1"), headache.hkSampleUUIDs)
    }

    private fun risk(day: Instant, nightKey: Instant, index: Double) =
        HeadacheRiskDay(day = day, nightKey = nightKey, index = index, computedAt = day, updatedAt = day)

    private suspend fun recordPeriodHealthWrite(db: StoreDatabase, start: Instant, uuids: List<String>) {
        val row = assertNotNull(db.userEntryDao().periodAt(start))
        db.userEntryDao().updatePeriod(row.copy(hkSampleUUIDs = uuids, healthWritten = true))
    }

    private fun withStore(block: suspend (LocalStore, StoreDatabase) -> Unit) = runBlocking<Unit> {
        withInMemoryStore { db -> block(LocalStore(db), db) }
    }
}
