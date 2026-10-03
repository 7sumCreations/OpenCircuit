package io.github.opencircuit.store

import io.github.opencircuit.ringkit.Command
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ingest's plausibility check at its edges, the same for the dry run and the real write.
 *
 * Upstream (ios/OpenCircuit/Store/LocalStore.swift:1039-1043 @ b1c2fdd) keeps a sample when
 * `start >= syncEpoch` and `start <= now + 86 400 s` (both inclusive) and, for heart rate, when
 * `Int(value)` is in 30...220. `Int(Double)` truncates toward zero, measured on Swift 6.3.2:
 * 29.9 → 29 dropped, 30.9 → 30 kept, 220.9 → 220 kept, 221 dropped, −0.5 → 0 dropped. For NaN and
 * ±infinity `Int(Double)` traps ("Double value cannot be converted to Int because it is either
 * infinite or NaN"), so upstream crashes; here such a heart rate is dropped.
 */
class IngestPlausibilityHazardTest {

    private val now = Instant.parse("2026-10-03T00:00:00Z")
    private val syncEpoch = Instant.ofEpochSecond(Command.SYNC_EPOCH)
    private val ceiling = now.plusSeconds(86_400)

    // Distinct starts, so an empty cursor keeps every plausible one.
    private var nextMinute = 0L
    private fun hr(value: Double) =
        QuantitySample(MetricKind.HEART_RATE, start = Instant.parse("2026-06-01T00:00:00Z").plusSeconds(60 * nextMinute++), value = value)

    private val keptTimestamps = listOf(
        QuantitySample(MetricKind.TEMPERATURE, start = syncEpoch, value = 33.0),
        QuantitySample(MetricKind.TEMPERATURE, start = ceiling, value = 33.1),
    )
    private val droppedTimestamps = listOf(
        QuantitySample(MetricKind.TEMPERATURE, start = syncEpoch.minusMillis(1), value = 33.2),
        QuantitySample(MetricKind.TEMPERATURE, start = ceiling.plusMillis(1), value = 33.3),
    )
    // 220.6 as well as 220.9: rounding instead of truncating would then lose two kept rates while
    // gaining only one dropped one (29.9), so the counts cannot cancel out.
    private val keptHeartRates = listOf(hr(30.0), hr(30.9), hr(220.0), hr(220.6), hr(220.9))
    private val droppedHeartRates = listOf(
        hr(29.0), hr(29.9), hr(221.0), hr(-0.5), hr(0.0),
        hr(Double.NaN), hr(Double.POSITIVE_INFINITY), hr(Double.NEGATIVE_INFINITY),
    )
    private val all = keptTimestamps + droppedTimestamps + keptHeartRates + droppedHeartRates

    @Test
    fun theDryRunCountsEachEdgeAsUpstreamDoes() = runBlocking {
        withInMemoryStore { db ->
            val preview = LocalStore(db).previewIngest(all, now)

            assertEquals(
                LocalStore.IngestPreview(
                    inputCount = all.size,
                    plausibleCount = 7,
                    freshCount = 7,
                    duplicateCount = 0,
                    invalidTimestampCount = 2,
                    invalidHeartRateCount = 8,
                ),
                preview,
            )
            assertEquals(emptyList(), db.sampleDao().allSamples(), "the dry run writes nothing")
        }
    }

    @Test
    fun theIngestKeepsExactlyTheSamplesTheDryRunCallsPlausible() = runBlocking {
        withInMemoryStore { db ->
            val stored = LocalStore(db).ingest(all, now, ZoneOffset.UTC)

            assertEquals((keptTimestamps + keptHeartRates).sortedBy { it.start }, stored)
        }
    }

    @Test
    fun aRepeatedPageIsCountedAsDuplicateByTheDryRun() = runBlocking {
        withInMemoryStore { db ->
            val store = LocalStore(db)
            store.ingest(keptHeartRates, now, ZoneOffset.UTC)

            val preview = store.previewIngest(keptHeartRates, now)

            assertEquals(5, preview.plausibleCount)
            assertEquals(0, preview.freshCount)
            assertEquals(5, preview.duplicateCount)
        }
    }
}
