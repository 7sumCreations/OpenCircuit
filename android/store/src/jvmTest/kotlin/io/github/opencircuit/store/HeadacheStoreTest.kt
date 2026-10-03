package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The headache log (user-entered labels) and the frozen daily risk rows. Port of upstream
 * ios/OpenCircuitTests/HeadacheStoreTests.swift (@ b1c2fdd), tests `:57`, `:78`, `:101`, `:117`,
 * `:134`, `:153`, `:166`, `:186`, in upstream order, with upstream's fixtures (reference instant
 * 1 785 000 000 s, made-up sample ids).
 *
 * Two upstream calls belong to the health-write port, not the store's: `recordHeadacheEntryHK`
 * (store the written sample ids and set the watermark) is done here by [recordHealthWrite] on the
 * table directly, as fixture set-up; `pendingHeadacheEntries` (not yet written, not imported) is
 * asserted through the two stored facts it filters on, `healthWritten` and `source`.
 */
class HeadacheStoreTest {

    private val ref = Instant.ofEpochSecond(1_785_000_000L)

    private fun at(hours: Double): Instant = ref.plusMillis((hours * 3_600_000).toLong())

    private val now = at(100.0)

    /** Upstream `testUpsertByOnset` (:57). */
    @Test
    fun upsertByOnset() = withStore { store, _ ->
        store.saveHeadacheEntry(onset = at(0.0), end = null, severityRaw = 2, symptoms = listOf("nausea"), now = now)
        store.saveHeadacheEntry(
            onset = at(0.0), end = at(2.0), severityRaw = 4, symptoms = listOf("nausea", "light sensitivity"),
            notes = "got worse after an hour", now = now,
        )

        val rows = store.allHeadacheEntries()
        assertEquals(1, rows.size, "one onset is one headache, not two")
        assertEquals(4, rows[0].severityRaw)
        assertEquals(at(2.0), rows[0].end)
        assertEquals(listOf("nausea", "light sensitivity"), rows[0].symptoms)
        assertEquals("got worse after an hour", rows[0].notes)
        assertEquals(HeadacheSource.USER, rows[0].source)
    }

    /** Upstream `testOnsetMoveRelocatesRowAndCarriesHKUUIDs` (:78). */
    @Test
    fun onsetMoveRelocatesRowAndCarriesHKUUIDs() = withStore { store, db ->
        store.saveHeadacheEntry(onset = at(0.0), end = at(1.0), severityRaw = 3, symptoms = emptyList(), now = now)
        recordHealthWrite(db, at(0.0), listOf("hk-original"), finalized = true)
        store.saveHeadacheEntry(onset = at(5.0), end = at(6.0), severityRaw = 2, symptoms = emptyList(), now = now)
        recordHealthWrite(db, at(5.0), listOf("hk-clash"), finalized = true)

        // The user moves the first entry's onset onto the second entry's slot.
        store.saveHeadacheEntry(
            onset = at(5.0), end = at(6.0), severityRaw = 3, symptoms = emptyList(), originalOnset = at(0.0), now = now,
        )

        val rows = store.allHeadacheEntries()
        assertEquals(1, rows.size, "one logical edit must not leave an orphan row behind")
        assertEquals(at(5.0), rows[0].onset)
        assertEquals(
            setOf("hk-original", "hk-clash"), rows[0].hkSampleUUIDs.toSet(),
            "both stale Health samples must stay nameable so they can be deleted",
        )
        assertFalse(rows[0].healthWritten, "the entry must be re-written at its new time")
        // Upstream: pendingHeadacheEntries() == [at(5)].
        assertEquals(listOf(at(5.0)), rows.filter { !it.healthWritten && it.source != HeadacheSource.HEALTH_IMPORT }.map { it.onset })
    }

    /** Upstream `testNotesOnlyEditDoesNotResetHealthWritten` (:101). */
    @Test
    fun notesOnlyEditDoesNotResetHealthWritten() = withStore { store, db ->
        store.saveHeadacheEntry(onset = at(0.0), end = at(2.0), severityRaw = 3, symptoms = listOf("nausea"), now = now)
        recordHealthWrite(db, at(0.0), listOf("hk-1"), finalized = true)

        store.saveHeadacheEntry(
            onset = at(0.0), end = at(2.0), severityRaw = 3, symptoms = listOf("nausea"),
            notes = "took ibuprofen at 09:20", now = now,
        )

        val row = assertNotNull(store.allHeadacheEntries().firstOrNull())
        assertEquals("took ibuprofen at 09:20", row.notes)
        assertTrue(row.healthWritten, "notes are not clinical — Health must not be re-written")
    }

    /** Upstream `testTriggerOnlyEditDoesNotResetHealthWritten` (:117). */
    @Test
    fun triggerOnlyEditDoesNotResetHealthWritten() = withStore { store, db ->
        store.saveHeadacheEntry(onset = at(0.0), end = at(2.0), severityRaw = 3, symptoms = listOf("nausea"), now = now)
        recordHealthWrite(db, at(0.0), listOf("hk-1"), finalized = true)

        store.saveHeadacheEntry(
            onset = at(0.0), end = at(2.0), severityRaw = 3, symptoms = listOf("nausea"),
            factors = listOf("poor sleep", "skipped a meal"), now = now,
        )

        val row = assertNotNull(store.allHeadacheEntries().firstOrNull())
        assertEquals(listOf("poor sleep", "skipped a meal"), row.factors)
        assertTrue(row.healthWritten, "triggers have no HealthKit field — Health is unaffected")
    }

    /** Upstream `testSeverityEditDoesResetHealthWritten` (:134). */
    @Test
    fun severityEditDoesResetHealthWritten() = withStore { store, db ->
        store.saveHeadacheEntry(onset = at(0.0), end = at(2.0), severityRaw = 2, symptoms = listOf("nausea"), now = now)
        recordHealthWrite(db, at(0.0), listOf("hk-1"), finalized = true)

        store.saveHeadacheEntry(onset = at(0.0), end = at(2.0), severityRaw = 4, symptoms = listOf("nausea"), now = now)

        val row = assertNotNull(store.allHeadacheEntries().firstOrNull())
        assertEquals(4, row.severityRaw)
        assertFalse(row.healthWritten, "a clinical change must be corrected in Apple Health")
        assertEquals(listOf("hk-1"), row.hkSampleUUIDs, "the superseded sample must still be deletable on the next flush")
        assertEquals(HeadacheSource.USER, row.source)
    }

    /** Upstream `testDeleteReturnsStaleUUIDs` (:153). */
    @Test
    fun deleteReturnsStaleUUIDs() = withStore { store, db ->
        store.saveHeadacheEntry(onset = at(0.0), end = at(1.0), severityRaw = 3, symptoms = emptyList(), now = now)
        recordHealthWrite(db, at(0.0), listOf("hk-1", "hk-2"), finalized = true)

        assertEquals(listOf("hk-1", "hk-2"), store.deleteHeadacheEntry(at(0.0)))
        assertTrue(store.allHeadacheEntries().isEmpty())
        assertEquals(emptyList(), store.deleteHeadacheEntry(at(0.0)), "deleting a row that isn't there is a no-op, not an error")
    }

    /** Upstream `testPendingExcludesHealthImportedRows` (:166). */
    @Test
    fun pendingExcludesHealthImportedRows() = withStore { store, _ ->
        store.saveHeadacheEntry(onset = at(0.0), end = at(1.0), severityRaw = 3, symptoms = emptyList(), now = now)
        store.saveHeadacheEntry(
            onset = at(4.0), end = at(5.0), severityRaw = 2, symptoms = emptyList(),
            source = HeadacheSource.HEALTH_IMPORT, importedHKUUID = "hk-imported", now = now,
        )

        // Upstream: pendingHeadacheEntries() == [at(0)] — the imported row is kept as imported.
        val rows = store.allHeadacheEntries()
        assertEquals(listOf(HeadacheSource.USER, HeadacheSource.HEALTH_IMPORT), rows.map { it.source })
        assertEquals(listOf(at(0.0)), rows.filter { !it.healthWritten && it.source != HeadacheSource.HEALTH_IMPORT }.map { it.onset })
        assertEquals(setOf("hk-imported"), store.importedHeadacheHKUUIDs(), "a repeated import must be idempotent")
    }

    /** Upstream `testInsertRiskDayIfAbsentIsIdempotent` (:186). */
    @Test
    fun insertRiskDayIfAbsentIsIdempotent() = withStore { store, _ ->
        val day = at(0.0)
        assertTrue(
            store.insertRiskDayIfAbsent(
                HeadacheRiskDay(
                    day = day, index = 42.0, bandRaw = 1, ringFeatureCount = 4, coverageFraction = 0.9,
                    contributionsJSON = "{\"hrv\":-0.3}", absentJSON = "{}", computedAt = at(1.0), updatedAt = at(1.0),
                ),
            ),
        )

        assertFalse(
            store.insertRiskDayIfAbsent(
                HeadacheRiskDay(
                    day = day, index = 99.0, bandRaw = 2, ringFeatureCount = 6, coverageFraction = 1.0,
                    contributionsJSON = "{\"hrv\":-9}", absentJSON = "{}", computedAt = at(9.0), updatedAt = at(9.0),
                ),
            ),
            "a day already scored must not be scored again",
        )

        // The re-stage / alert annotations run over the same row.
        store.markRiskRestaged(day = day, sleepUpdatedAt = at(10.0), now = at(10.0))
        store.markRiskAlerted(day = day, now = at(11.0))

        val rows = store.riskDays(from = at(-24.0), to = at(24.0))
        assertEquals(1, rows.size)
        assertEquals(42.0, rows[0].index, 0.0001, "the frozen score must be untouched")
        assertEquals(1, rows[0].bandRaw)
        assertEquals(4, rows[0].ringFeatureCount)
        assertEquals("{\"hrv\":-0.3}", rows[0].contributionsJSON)
        assertTrue(rows[0].sleepRestaged, "a re-staged night is EXCLUDED from evaluation, not rescored")
        assertEquals(at(10.0), rows[0].sleepUpdatedAt)
        assertTrue(rows[0].alerted)
    }

    private fun withStore(block: suspend (LocalStore, StoreDatabase) -> Unit) = runBlocking<Unit> {
        withInMemoryStore { db -> block(LocalStore(db), db) }
    }
}

/**
 * Fixture set-up standing in for upstream's `recordHeadacheEntryHK` (the health-write port's
 * method): the entry at [onset] now names [uuids] as its written samples, written up to date when
 * [finalized].
 */
internal suspend fun recordHealthWrite(db: StoreDatabase, onset: Instant, uuids: List<String>, finalized: Boolean) {
    val row = assertNotNull(db.userEntryDao().headacheAt(onset), "no headache at $onset")
    db.userEntryDao().updateHeadache(row.copy(hkSampleUUIDs = uuids, healthWritten = finalized))
}
