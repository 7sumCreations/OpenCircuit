package io.github.opencircuit.store

import io.github.opencircuit.ringkit.MetricKind
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a sample row is kept: instants as whole epoch milliseconds, and no uniqueness on
 * `(kind, start)`.
 *
 * Kotlin-only hazards. Upstream stores a Swift `Date` (a `Double` of seconds), so it never had to
 * choose a precision; a `java.time.Instant` carries nanoseconds, and the store keeps milliseconds.
 * The cut must truncate (an instant one nanosecond before a whole second must not become that
 * second), including before 1970, where truncating means moving toward the past.
 */
class StoredSampleColumnsTest {

    @Test
    fun aSubMillisecondInstantIsStoredTruncatedToTheMillisecondNeverRounded() = runBlocking {
        withInMemoryStore { db ->
            val start = Instant.ofEpochSecond(1_700_000_000L, 123_456_789)
            val end = Instant.ofEpochSecond(1_700_000_000L, 999_999_999)
            db.sampleDao().insertSamples(listOf(row(MetricKind.HEART_RATE, start, end, 61.0)))

            val stored = db.sampleDao().allSamples().single()

            assertEquals(Instant.ofEpochSecond(1_700_000_000L, 123_000_000), stored.start)
            assertEquals(Instant.ofEpochSecond(1_700_000_000L, 999_000_000), stored.end)
        }
    }

    @Test
    fun anInstantBeforeTheUnixEpochIsTruncatedTowardThePast() = runBlocking {
        withInMemoryStore { db ->
            // -0.000000001 s: whole milliseconds toward the past give -1 ms, never 0.
            val start = Instant.ofEpochSecond(-1L, 999_999_999)
            db.sampleDao().insertSamples(listOf(row(MetricKind.TEMPERATURE, start, start, 33.5)))

            assertEquals(Instant.ofEpochMilli(-1L), db.sampleDao().allSamples().single().start)
        }
    }

    @Test
    fun twoSamplesOfOneKindWithTheSameStartAreBothStored() = runBlocking {
        withInMemoryStore { db ->
            val t = Instant.ofEpochSecond(1_700_000_000L)
            db.sampleDao().insertSamples(
                listOf(
                    row(MetricKind.HEART_RATE, t, t, 61.0),
                    row(MetricKind.HEART_RATE, t, t, 62.0),
                ),
            )

            assertEquals(listOf(61.0, 62.0), db.sampleDao().allSamples().map { it.value }.sorted())
        }
    }

    private fun row(kind: MetricKind, start: Instant, end: Instant, value: Double) =
        StoredSampleEntity(kindRaw = kind.rawValue, start = start, end = end, value = value)
}
