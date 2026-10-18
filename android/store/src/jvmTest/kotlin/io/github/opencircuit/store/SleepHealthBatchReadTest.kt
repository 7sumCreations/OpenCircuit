package io.github.opencircuit.store

import io.github.opencircuit.ringkit.SleepEdit
import io.github.opencircuit.ringkit.SleepStaging
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The batched read of the nights' Health marks, which is what the settled sleep write needs for
 * every stored night on every flush. One call must answer exactly what [SleepStore.mirroredNight]
 * and [SleepStore.sleepHealthRecordIds] answer for a single night: the mirror, the ids, and the
 * difference between a night with nothing kept and one whose kept value this build cannot read.
 * Nights outside the key range are not read.
 */
class SleepHealthBatchReadTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2026-06-29T09:00:00Z")

    /** Five stored nights, each keyed by its wake day (UTC); [outside] sits before any range read here. */
    private val outside = Instant.parse("2026-06-25T00:00:00Z")
    private val first = Instant.parse("2026-06-26T00:00:00Z")
    private val second = Instant.parse("2026-06-27T00:00:00Z")
    private val third = Instant.parse("2026-06-28T00:00:00Z")
    private val fourth = Instant.parse("2026-06-29T00:00:00Z")

    private val staging = SleepStaging.Summary(
        Duration.ofHours(8), Duration.ofMinutes(30), Duration.ofHours(5), Duration.ofHours(1), Duration.ofMinutes(90),
    )

    /** Stores one night keyed [key]: in bed from 22:00 the day before to 06:00 on [key]'s day. */
    private suspend fun save(db: StoreDatabase, key: Instant) {
        SleepStore(db).saveSleepSummary(staging, key, key.minusSeconds(7_200), key.plusSeconds(21_600), now = now, zone = zone)
    }

    /** A stored value the codecs refuse, written the way an older build or a damaged row leaves it. */
    private suspend fun corrupt(db: StoreDatabase, key: String, value: String) =
        db.execRaw("INSERT INTO store_kv (key, value, updated_at) VALUES ('$key', '$value', 0)")

    @Test
    fun theBatchAnswersEveryNightOfTheRangeExactlyAsTheSingleNightReadsDo() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            for (key in listOf(outside, first, second, third, fourth)) save(db, key)
            // first: both marks kept.
            store.setMirroredNight(first, MirroredNight("cbf29ce484222325", first.minusSeconds(7_200), first.plusSeconds(21_600)), now)
            store.setSleepHealthRecordIds(first, listOf("oc:sleep:${first.epochSecond}.0"), now)
            // second: ids kept, mirror stored in a form this build cannot read.
            store.setSleepHealthRecordIds(second, listOf("oc:sleep:${second.epochSecond}.0", "oc:sleep:${second.minusSeconds(86_400).epochSecond}.0"), now)
            corrupt(db, "sleep.mirror.night.${second.epochSecond}.0", "{\"signature\":1}")
            // third: ids kept in a form this build cannot read.
            corrupt(db, "sleep.edit.hkuuids.${third.epochSecond}.0", "[1,2]")
            // fourth: nothing kept at all.
            // outside: both marks kept, but its night is outside the range read below.
            store.setMirroredNight(outside, MirroredNight("outside", outside, outside.plusSeconds(3_600)), now)
            store.setSleepHealthRecordIds(outside, listOf("oc:sleep:${outside.epochSecond}.0"), now)

            val marks = store.sleepHealthMarks(first, fourth.plusMillis(1))

            assertEquals(setOf(first, second, third, fourth), marks.keys, "every stored night of the range, and no night outside it")
            for (night in listOf(first, second, third, fourth)) {
                assertEquals(
                    NightHealthMarks(store.mirroredNight(night), store.sleepHealthRecordIds(night)),
                    marks[night],
                    "the batch answers $night exactly as the single-night reads do",
                )
            }
            // The four answers spelled out: kept, unreadable, absent.
            assertEquals("cbf29ce484222325", assertNotNull(marks.getValue(first).mirror).signature)
            assertEquals(listOf("oc:sleep:${first.epochSecond}.0"), marks.getValue(first).recordIds)
            assertNull(marks.getValue(second).mirror, "a mirror this build cannot read is null, never a guess")
            assertEquals(2, marks.getValue(second).recordIds?.size, "the ids kept beside it are still read")
            assertNull(marks.getValue(third).recordIds, "an id list this build cannot read is null, never empty")
            assertEquals(NightHealthMarks(null, emptyList()), marks.getValue(fourth), "a night with nothing kept answers nothing, not a missing entry")
            assertTrue(outside !in marks, "a night outside the range is not read")
        }
    }

    @Test
    fun aRangeHoldingNoStoredNightReadsNothing() = runBlocking<Unit> {
        withInMemoryStore { db ->
            val store = SleepStore(db)
            save(db, first)
            store.setSleepHealthRecordIds(first, listOf("oc:sleep:${first.epochSecond}.0"), now)

            assertEquals(emptyMap(), store.sleepHealthMarks(first.plusMillis(1), first.plusSeconds(86_400)), "the night ends before the range starts")
            assertEquals(emptyMap(), store.sleepHealthMarks(SleepEdit.DISTANT_PAST, SleepEdit.DISTANT_PAST.plusMillis(1)), "an empty range reads nothing")
            assertEquals(
                mapOf(first to NightHealthMarks(null, listOf("oc:sleep:${first.epochSecond}.0"))),
                store.sleepHealthMarks(SleepEdit.DISTANT_PAST, fourth.plusSeconds(86_400)),
                "a range wide enough to hold the store reads the one night it holds",
            )
        }
    }

    /**
     * The shape the batching is made of, checked in the source because a transaction count is not
     * observable from the values: the batch reads each overlay prefix with one range scan and opens
     * exactly one read transaction, so a flush costs two queries for the whole store, not two
     * transactions per night.
     */
    @Test
    fun theBatchReadsEachOverlayPrefixOnceInsideOneReadTransaction() {
        val storeDir = File(assertNotNull(System.getProperty("opencircuit.storeDir"), "opencircuit.storeDir is not set — see store/build.gradle.kts"))
        val bookkeeping = KotlinSourceText.withoutComments(File(storeDir, "src/commonMain/kotlin/io/github/opencircuit/store/SleepHealthBookkeeping.kt").readText())
        val sleepStore = KotlinSourceText.withoutComments(File(storeDir, "src/commonMain/kotlin/io/github/opencircuit/store/SleepStore.kt").readText())

        val reads = declaration(bookkeeping, "suspend fun marks(from: Instant, until: Instant)")
        assertEquals(2, count(reads, "kv.range("), "one range scan per overlay prefix, never one read per night")
        assertEquals(0, count(reads, "kv.get("), "no night is read on its own")
        assertEquals(0, count(reads, "withReadTransaction"), "the reads join the caller's transaction, as every other read here does")

        val call = declaration(sleepStore, "suspend fun sleepHealthMarks(from: Instant, until: Instant)")
        assertEquals(1, count(call, "withReadTransaction"), "one read transaction for the whole batch")
        assertEquals(0, count(call, "withWriteTransaction"), "a read opens no write transaction")
        assertEquals(1, count(call, "health.marks("), "it reads through the bookkeeping, as its siblings do")
    }

    /** The text of the declaration starting at [signature], up to the next declaration at the same indent. */
    private fun declaration(source: String, signature: String): String {
        val at = source.indexOf(signature)
        assertTrue(at >= 0, "no declaration starting \"$signature\" — this audit is not looking at the batched read")
        val rest = source.substring(at)
        val next = Regex("\n    (private |internal )?(suspend )?fun ").find(rest, 1)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    private fun count(text: String, token: String): Int {
        var found = 0
        var at = text.indexOf(token)
        while (at >= 0) {
            found++
            at = text.indexOf(token, at + token.length)
        }
        return found
    }
}
