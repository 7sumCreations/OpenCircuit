package io.github.opencircuit.store

import io.github.opencircuit.ringkit.RingAlarm
import io.github.opencircuit.ringkit.VibrationPattern
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An unreadable stored alarm is kept, byte for byte, until a new alarm is saved.
 *
 * Kotlin-only (a deliberate difference). Upstream's getter returns `RingAlarm()` — 07:00, disabled —
 * for a stored alarm it cannot read (ios/OpenCircuit/RingAlarmController.swift:58-60 @ b1c2fdd), so
 * one damaged value silently turns the user's alarm off. Here it loads as
 * [StoredAlarm.Unreadable] carrying the stored text, and reading it never writes.
 */
class StoredAlarmKeptTest {

    private val now = Instant.parse("2026-10-03T08:00:00Z")

    // Hour past 32 bits, odd spacing and non-ASCII characters: unreadable, and easy to mangle.
    private val damaged = """{ "backupNotification":true, "burstCount":3,"burstSpacing":4,"hour":5000000000,""" +
        """"isEnabled":true,"minute":30,"pattern":1,"weekdays":[2,3],"note":"Größe ü" }"""

    private suspend fun StoreDatabase.plantAlarm(value: String) =
        execRaw("INSERT OR REPLACE INTO store_kv(`key`, value, updated_at) VALUES ('alarm.ring.config', '${value.replace("'", "''")}', 7)")

    private suspend fun StoreDatabase.alarmRow() = queryRaw("SELECT value, updated_at FROM store_kv WHERE `key` = 'alarm.ring.config'")

    @Test
    fun unreadableAlarmBytesSurviveLoadsWithoutASaveByteForByte() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.plantAlarm(damaged)
            val blobs = BlobStore(db)

            repeat(3) {
                val loaded = assertIs<StoredAlarm.Unreadable>(blobs.loadAlarm())
                assertEquals(damaged, loaded.raw)
            }
            assertEquals(listOf("$damaged|7"), db.alarmRow(), "reading never rewrites the stored alarm")
        }
    }

    @Test
    fun anAlarmThatCannotBeStoredDoesNotReplaceTheUnreadableOne() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.plantAlarm(damaged)
            val blobs = BlobStore(db)
            assertFalse(blobs.saveAlarm(RingAlarm(burstSpacing = Double.NaN), now))
            assertEquals(damaged, assertIs<StoredAlarm.Unreadable>(blobs.loadAlarm()).raw)
            assertEquals(listOf("$damaged|7"), db.alarmRow())
        }
    }

    @Test
    fun savingANewAlarmReplacesTheUnreadableOne() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val blobs = BlobStore(db)
            assertEquals(StoredAlarm.Absent, blobs.loadAlarm())

            db.plantAlarm(damaged)
            val alarm = RingAlarm(isEnabled = true, hour = 6, minute = 15, weekdays = setOf(2, 6), pattern = VibrationPattern.LONG)
            assertTrue(blobs.saveAlarm(alarm, now))
            assertEquals(StoredAlarm.Readable(alarm), blobs.loadAlarm())
        }
    }
}
