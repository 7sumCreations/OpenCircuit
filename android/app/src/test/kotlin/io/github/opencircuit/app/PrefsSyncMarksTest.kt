package io.github.opencircuit.app

import io.github.opencircuit.app.data.PrefsSyncMarks
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The last complete sync, kept per ring across launches; an unreadable value reads as none. */
class PrefsSyncMarksTest {

    @Test
    fun aSavedFinishReadsBackInANewInstanceOverTheSameFile() {
        val values = InMemoryKeyValues()
        val at = Instant.parse("2026-10-08T07:12:34.567Z")
        PrefsSyncMarks(values, TEST_RING_ID).setLastCompleteSync(at)
        assertEquals(at, PrefsSyncMarks(values, TEST_RING_ID).lastCompleteSync)
        assertEquals("1791443554567", values.raw("sync.lastComplete.v1/$TEST_RING_ID"))
    }

    @Test
    fun eachRingHasItsOwnMark() {
        val values = InMemoryKeyValues()
        PrefsSyncMarks(values, TEST_RING_ID).setLastCompleteSync(Instant.parse("2026-10-08T07:00:00Z"))
        assertNull(PrefsSyncMarks(values, "AA:BB:CC:DD:EE:00").lastCompleteSync)
    }

    @Test
    fun nothingSavedReadsAsNone() {
        assertNull(PrefsSyncMarks(InMemoryKeyValues(), TEST_RING_ID).lastCompleteSync)
    }

    @Test
    fun aDamagedValueReadsAsNone() {
        val key = "sync.lastComplete.v1/$TEST_RING_ID"
        for (bad in listOf<Any>("", "-5", "12a", "١٧٩١٤٤٣٥٥٤٥٦٧", "１７９１４４３５５４５６７", "99999999999999999999", true)) {
            val values = InMemoryKeyValues()
            values.putRaw(key, bad)
            assertNull(PrefsSyncMarks(values, TEST_RING_ID).lastCompleteSync, "\"$bad\" must read as none")
        }
    }

    @Test
    fun aWriteThatDidNotReachTheFileSaysSo() {
        val values = InMemoryKeyValues().apply { failWrites = true }
        assertFalse(PrefsSyncMarks(values, TEST_RING_ID).setLastCompleteSync(Instant.parse("2026-10-08T07:00:00Z")))
    }
}
