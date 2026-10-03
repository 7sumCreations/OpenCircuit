package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * How a list column (tags, Health sample ids) is kept, through the real database.
 *
 * Kotlin-only hazards: upstream's `[String]` is stored by SwiftData and a Swift string cannot hold
 * a lone surrogate; a Kotlin string can, and no JSON reader takes it back. A damaged list must not
 * read as empty, because an empty id list would silently forget Health samples still to delete.
 */
class ListColumnConverterTest {

    private val t = Instant.parse("2026-07-25T08:00:00Z")

    @Test
    fun tagsWithQuotesBackslashesControlCharactersAndNonAsciiTextRoundTrip() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val tags = listOf("light \"sensitivity\"", "back\\slash", "tab\tnew\nline", "Übelkeit", "頭痛", "🤕", "")
            db.userEntryDao().insertPeriod(StoredPeriodEntryEntity(start = t, symptoms = tags, hkSampleUUIDs = listOf("hk-1")))

            val row = db.userEntryDao().periodAt(t)!!
            assertEquals(tags, row.symptoms)
            assertEquals(listOf("hk-1"), row.hkSampleUUIDs)
        }
    }

    @Test
    fun aTagHoldingALoneSurrogateIsRefusedAndNothingIsStored() = runBlocking<Unit> {
        withInMemoryStore { db ->
            assertFailsWith<IllegalArgumentException> {
                db.userEntryDao().insertPeriod(StoredPeriodEntryEntity(start = t, symptoms = listOf("cramp\uD83E")))
            }
            assertEquals(emptyList(), db.queryRaw("SELECT start FROM stored_period_entry"))
        }
    }

    @Test
    fun aDamagedListFailsTheReadInsteadOfReadingAsEmpty() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.execRaw("INSERT INTO stored_headache_entry (onset, hk_sample_uuids) VALUES (1000, '[\"hk-1\",')")

            val e = assertFailsWith<IllegalStateException> { db.userEntryDao().allHeadaches() }
            assertEquals(true, e.message?.startsWith("stored list unreadable"), e.message)
        }
    }

    @Test
    fun aListOfTheWrongElementTypeFailsTheRead() = runBlocking<Unit> {
        withInMemoryStore { db ->
            db.execRaw("INSERT INTO stored_headache_entry (onset, factors) VALUES (1000, '[1, 2]')")

            assertFailsWith<IllegalStateException> { db.userEntryDao().allHeadaches() }
        }
    }

    @Test
    fun movementLevelsRoundTripAndALevelPastThirtyTwoBitsIsUnreadable() {
        val converter = ListColumnConverter()
        assertEquals(listOf(0, 3, -1, Int.MAX_VALUE), converter.textToInts(converter.intsToText(listOf(0, 3, -1, Int.MAX_VALUE))))
        assertFailsWith<IllegalStateException> { converter.textToInts("[2147483648]") }
    }
}
