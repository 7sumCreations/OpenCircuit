package io.github.opencircuit.store

import io.github.opencircuit.ringkit.BulkSleep
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepEdit
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of upstream ios/OpenCircuitTests/CaptureToStoreEndToEndTests.swift (@ b1c2fdd): capture to
 * store, with the cursor advancing only past samples that were durably stored. The fixture is the
 * same real, XOR-valid `0x4c` page the upstream test decodes (header `4c 00 26`, six 23-byte
 * records, then the XOR byte), decoded through `BulkSleep` from its raw bytes.
 *
 * Upstream's ingest reads the wall clock for its "no later than now + 1 day" check; here `now` is
 * a fixed instant after every record in the page. The three night tests of the same upstream file
 * (`:96`, `:114`, `:132`) exercise the sleep-summary merge and are not part of this file.
 */
class CaptureToStoreEndToEndTest {

    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    private val now = Instant.parse("2026-10-03T00:00:00Z")
    private val zone = ZoneOffset.UTC

    /** Foundation's `Date.distantFuture` (0001-01-01 + 4000 years, 4001-01-01T00:00:00Z). */
    private val distantFuture = Instant.ofEpochSecond(64_092_211_200L)

    private fun pageSamples(): List<QuantitySample> {
        val records = BulkSleep.recordsFromPage(hex(realPage))
        assertFalse(records.isEmpty(), "fixture must decode")
        return BulkSleep.samples(records)
    }

    /** Upstream `testFirstIngestPersistsNewSamples` (`:42`). */
    @Test
    fun firstIngestPersistsNewSamples() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val samples = pageSamples()

            val ingested = store.ingest(samples, now, zone)

            assertEquals(samples.size, ingested.size)
            assertFalse(ingested.isEmpty())
        }
    }

    /** Upstream `testReSyncOfSamePageDoesNotDuplicate` (`:54`). */
    @Test
    fun reSyncOfSamePageDoesNotDuplicate() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val samples = pageSamples()

            val firstIngest = store.ingest(samples, now, zone)
            assertFalse(firstIngest.isEmpty())

            // Same page redelivered (the documented small re-sync overlap): the cursor already
            // advanced past every one of these timestamps, so nothing new lands.
            val secondIngest = store.ingest(samples, now, zone)
            assertTrue(secondIngest.isEmpty(), "re-syncing the same page must not duplicate already-ingested samples")

            // The store itself holds only the first ingest's rows, not double the count.
            val stored = store.samples(MetricKind.HEART_RATE, from = SleepEdit.DISTANT_PAST, to = distantFuture)
            val firstHRCount = firstIngest.count { it.kind == MetricKind.HEART_RATE }
            assertEquals(firstHRCount, stored.size)
        }
    }

    /** Upstream `testCursorAdvancesOnlyPastIngestedSamples` (`:75`). */
    @Test
    fun cursorAdvancesOnlyPastIngestedSamples() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            val samples = pageSamples()
            store.ingest(samples, now, zone)

            val cursor = store.loadCursor()
            val latestHR = samples.filter { it.kind == MetricKind.HEART_RATE }.maxOfOrNull { it.start }
            assertNotNull(latestHR)
            assertEquals(latestHR, cursor.last(MetricKind.HEART_RATE))
        }
    }
}

/** Hex text to bytes, two digits per byte (upstream's test-local `hex(_:)`). */
internal fun hex(s: String): ByteArray {
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
