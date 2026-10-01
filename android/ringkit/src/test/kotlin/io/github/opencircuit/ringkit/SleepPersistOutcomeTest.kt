package io.github.opencircuit.ringkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What actually happened when a drain tried to store a night: these assert the CLASSIFICATION —
 * which outcomes mean the wearer has a night and which mean they have nothing — because the
 * "sleep committed" evidence, the metric events and the card's warning are all defined against it.
 * An outcome added later without a decision here fails `everyOutcomeIsClassified`.
 *
 * Port of upstream ios/OpenCircuitKit/Tests/OpenCircuitKitTests/SleepPersistOutcomeTests.swift
 * (@ b1c2fdd) — all 6 tests.
 */
class SleepPersistOutcomeTest {

    @Test
    fun onlyRealWritesCount() {
        assertTrue(SleepPersistOutcome.INSERTED.wroteRow)
        assertTrue(SleepPersistOutcome.UPDATED.wroteRow)
        for (o in SleepPersistOutcome.entries) {
            if (o == SleepPersistOutcome.INSERTED || o == SleepPersistOutcome.UPDATED) continue
            assertFalse(o.wroteRow, "$o is not a write and must not report one")
        }
    }

    /**
     * The distinction a plain boolean could not make: a drain that stored nothing because the stored
     * night is BETTER is healthy; a drain that stored nothing because the write failed is the defect.
     * Both have `wroteRow == false`.
     */
    @Test
    fun deliberateKeepsAreNotLosses() {
        assertTrue(SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT.nightIsStored)
        assertTrue(SleepPersistOutcome.KEPT_MANUAL_EDIT.nightIsStored)
        assertFalse(SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT.isSilentLoss)
        assertFalse(SleepPersistOutcome.KEPT_MANUAL_EDIT.isSilentLoss)
    }

    @Test
    fun theFourWaysToEndUpWithNoStoredNight() {
        val losses = listOf(
            SleepPersistOutcome.NO_STAGED_SEGMENTS,
            SleepPersistOutcome.DEFERRED_NIGHT_KEY_MIGRATION,
            SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION,
            SleepPersistOutcome.FAILED,
        )
        for (o in losses) {
            assertTrue(o.isSilentLoss, "$o leaves the wearer with no stored night")
            assertFalse(o.nightIsStored)
        }
    }

    /**
     * A collision is permanent for this staging — every retry hits the same guard — which is why its
     * user-facing copy must not promise that syncing again will fix it.
     */
    @Test
    fun collisionIsNotRecoverableByRetry() {
        assertFalse(SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION.isRecoverableByRetry)
        assertTrue(SleepPersistOutcome.DEFERRED_NIGHT_KEY_MIGRATION.isRecoverableByRetry)
        assertTrue(SleepPersistOutcome.NO_STAGED_SEGMENTS.isRecoverableByRetry)
        assertTrue(SleepPersistOutcome.FAILED.isRecoverableByRetry)
    }

    @Test
    fun everyOutcomeIsClassified() {
        for (o in SleepPersistOutcome.entries) {
            // Exactly one of the two states, never both, never neither.
            assertNotEquals(o.nightIsStored, o.isSilentLoss, "$o is unclassified")
            // Only a loss may claim retry-recoverability — a stored night has nothing to retry.
            if (o.isRecoverableByRetry) assertTrue(o.isSilentLoss, "$o")
        }
    }

    /**
     * The raw values are persisted in the sync evidence and read back from exports taken on older
     * builds, so they are wire format and must not be renamed casually.
     */
    @Test
    fun rawValuesAreStable() {
        assertEquals("inserted", SleepPersistOutcome.INSERTED.rawValue)
        assertEquals("updated", SleepPersistOutcome.UPDATED.rawValue)
        assertEquals("keptFullerStoredNight", SleepPersistOutcome.KEPT_FULLER_STORED_NIGHT.rawValue)
        assertEquals("keptManualEdit", SleepPersistOutcome.KEPT_MANUAL_EDIT.rawValue)
        assertEquals("refusedNightKeyCollision", SleepPersistOutcome.REFUSED_NIGHT_KEY_COLLISION.rawValue)
        assertEquals("noStagedSegments", SleepPersistOutcome.NO_STAGED_SEGMENTS.rawValue)
        assertEquals("deferredNightKeyMigration", SleepPersistOutcome.DEFERRED_NIGHT_KEY_MIGRATION.rawValue)
        assertEquals("failed", SleepPersistOutcome.FAILED.rawValue)
    }
}
