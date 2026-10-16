package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skin temperature: one record per stored night (`oc:skin:<night key text>`), measured at the
 * FINGER, spanning the night's first to last reading (+1 s, so the last difference lies inside
 * the record), each reading written as its difference from the rolling baseline of the PRIOR
 * nights' means (`SkinTempBaseline.baseline`: at least 3 prior nights). No baseline, no record.
 * On a phone without Health Connect's skin-temperature feature nothing is written, nothing is
 * marked and nothing fails. Nights are shaped as the store keeps them (night key = start of day,
 * the in-bed span, the night's published mean in °C, 0 when none).
 */
class SkinTempNightTest {

    private val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val hc = FakeHealthConnect(clock::now).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private val zone = ZoneOffset.ofHours(2)

    private val generations = MemoryGenerations()

    private fun writer() = HealthWriter(hc, samples, WriteGeneration(generations), InMemoryHealthFailures(), clock::now, { zone }, { RingDevice("Gen 2") })

    private class MemoryGenerations(var value: Long? = null) : WriteGenerationStore {
        override suspend fun load(): Long? = value
        override suspend fun save(generation: Long) { value = generation }
    }

    /** The night keyed on [day] (start of day UTC), in bed 23:00 the evening before to 07:00, with a published [mean]. */
    private fun night(day: String, mean: Double): NightWindow {
        val key = Instant.parse("${day}T00:00:00Z")
        return NightWindow(key = "${key.epochSecond}.0", night = key, inBedStart = key.minusSeconds(3600), inBedEnd = key.plusSeconds(7 * 3600), nightlyCelsius = mean)
    }

    private fun temp(at: Instant, c: Double) = QuantitySample(MetricKind.TEMPERATURE, at, value = c)

    // ---- the record ----

    @Test
    fun aNightWithThreePriorNightsIsOneFingerRecordOfDifferencesFromTheirMean() {
        val prior = listOf(night("2026-10-12", 33.0), night("2026-10-13", 33.5), night("2026-10-14", 34.0))
        val tonight = night("2026-10-16", 33.9)
        val readings = listOf(temp(tonight.inBedStart.plusSeconds(600), 33.2), temp(tonight.inBedStart.plusSeconds(1800), 34.1))

        val item = SkinTempNights.item(tonight, readings, prior + tonight, clock.now(), zone, RingDevice("Gen 2"), version = 7)!!
        val record = item.record as SkinTemperature

        assertEquals("oc:skin:${tonight.night.epochSecond}.0", record.meta.clientRecordId)
        assertEquals(33.5, record.baselineCelsius)
        assertEquals(listOf(-0.3, 0.6), record.deltas.map { Math.round(it.deltaCelsius * 1000) / 1000.0 })
        assertEquals(readings.map { it.start }, record.deltas.map { it.time })
        assertEquals(readings.first().start, record.start)
        assertEquals(readings.last().start.plusSeconds(1), record.end)
        assertEquals(SkinTemperatureLocation.FINGER, record.location)
        assertEquals(RecordMeta(record.meta.clientRecordId, 7, RecordingMethod.AUTOMATICALLY_RECORDED, RingDevice("Gen 2")), record.meta)
        assertEquals(readings, item.samples)
        assertNull(HealthRanges.violation(record, clock.now()))
    }

    @Test
    fun twoPriorNightsAreNoBaselineSoNoRecord() {
        val prior = listOf(night("2026-10-13", 33.5), night("2026-10-14", 34.0))
        val tonight = night("2026-10-16", 33.9)

        assertNull(SkinTempNights.item(tonight, listOf(temp(tonight.inBedStart.plusSeconds(600), 33.2)), prior + tonight, clock.now(), zone, RingDevice("Gen 2"), 1))
    }

    @Test
    fun theBaselineReadsOnlyPriorNightsWithAPublishedMean() {
        // Tonight's own mean, a later night and nights with no mean (0) or an unreadable one are not prior means.
        val all = listOf(
            night("2026-10-10", 0.0), night("2026-10-11", Double.NaN), night("2026-10-12", 33.0), night("2026-10-13", 33.5),
            night("2026-10-16", 40.0), night("2026-10-17", 40.0),
        )
        val tonight = all[4]

        assertNull(SkinTempNights.item(tonight, listOf(temp(tonight.inBedStart.plusSeconds(600), 33.2)), all, clock.now(), zone, RingDevice("Gen 2"), 1))
    }

    @Test
    fun readingsOutsideTheNightAfterNowZeroOrUnreadableAreLeftOut() {
        val prior = listOf(night("2026-10-12", 33.0), night("2026-10-13", 33.0), night("2026-10-14", 33.0))
        val tonight = night("2026-10-16", 33.0)
        val inside = temp(tonight.inBedStart.plusSeconds(600), 33.2)
        val stored = listOf(
            temp(tonight.inBedStart.minusMillis(1), 33.0), inside, temp(tonight.inBedStart.plusSeconds(700), 0.0),
            temp(tonight.inBedStart.plusSeconds(800), Double.NaN), temp(tonight.inBedEnd.plusMillis(1), 33.0),
        )
        clock.set(tonight.inBedStart.plusSeconds(650))

        val record = SkinTempNights.item(tonight, stored + temp(tonight.inBedStart.plusSeconds(660), 33.3), prior + tonight, clock.now(), zone, RingDevice("Gen 2"), 1)!!.record as SkinTemperature

        assertEquals(listOf(inside.start), record.deltas.map { it.time })
    }

    @Test
    fun aNightWithNoReadingIsNoRecord() {
        val prior = listOf(night("2026-10-12", 33.0), night("2026-10-13", 33.0), night("2026-10-14", 33.0))
        val tonight = night("2026-10-16", 33.0)

        assertNull(SkinTempNights.item(tonight, emptyList(), prior + tonight, clock.now(), zone, RingDevice("Gen 2"), 1))
    }

    // ---- through the writer ----

    private fun seedThreePriorNightsAndTonight(): NightWindow {
        samples.addNights(night("2026-10-12", 33.0), night("2026-10-13", 33.5), night("2026-10-14", 34.0))
        val tonight = night("2026-10-16", 33.9)
        samples.addNights(tonight)
        samples.add(temp(tonight.inBedStart.plusSeconds(600), 33.2), temp(tonight.inBedStart.plusSeconds(1800), 34.1))
        return tonight
    }

    @Test
    fun aPendingNightIsWrittenAndItsReadingsMarked() = runTest {
        val tonight = seedThreePriorNightsAndTonight()

        val result = writer().flush()

        val written = hc.records(RecordType.SKIN_TEMPERATURE).map { it as SkinTemperature }
        assertEquals(listOf("oc:skin:${tonight.night.epochSecond}.0"), written.map { it.meta.clientRecordId })
        assertEquals(mapOf(RecordType.SKIN_TEMPERATURE to 1), result.written)
        assertEquals(tonight.inBedStart.plusSeconds(1800), samples.mark(MetricKind.TEMPERATURE))
    }

    @Test
    fun withoutTheFeatureNothingIsWrittenNothingMarkedAndNothingFailed() = runTest {
        seedThreePriorNightsAndTonight()
        hc.features = emptySet()

        val result = writer().flush()

        assertEquals(setOf(RecordType.SKIN_TEMPERATURE), result.unsupported)
        assertEquals(emptyMap(), result.failures)
        assertTrue(RecordType.SKIN_TEMPERATURE !in result.notGranted)
        assertEquals(emptyList(), hc.calls)
        assertNull(samples.mark(MetricKind.TEMPERATURE))
    }

    @Test
    fun withoutTheGrantItIsSkippedNotFailed() = runTest {
        seedThreePriorNightsAndTonight()
        hc.revoke(HealthPermission.SKIN_TEMPERATURE)

        val result = writer().flush()

        assertEquals(setOf(RecordType.SKIN_TEMPERATURE), result.notGranted)
        assertEquals(emptyMap(), result.failures)
        assertNull(samples.mark(MetricKind.TEMPERATURE))
    }

    @Test
    fun aLateReadingRewritesItsNightUnderTheSameIdWithAHigherVersion() = runTest {
        val tonight = seedThreePriorNightsAndTonight()
        writer().flush()

        samples.add(temp(tonight.inBedStart.plusSeconds(3600), 33.7))
        writer().flush()

        val written = hc.records(RecordType.SKIN_TEMPERATURE).map { it as SkinTemperature }.single()
        assertEquals(3, written.deltas.size)
        assertEquals(2L, written.meta.version)
    }
}
