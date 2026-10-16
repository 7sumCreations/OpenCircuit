package io.github.opencircuit.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.health.HealthFailureStore
import io.github.opencircuit.health.HealthFeature
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthSampleStore
import io.github.opencircuit.health.HealthWriter
import io.github.opencircuit.health.HeartRateVariabilityRmssd
import io.github.opencircuit.health.JetpackHealthConnect
import io.github.opencircuit.health.NightWindow
import io.github.opencircuit.health.OxygenSaturation
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RespiratoryRate
import io.github.opencircuit.health.RingDevice
import io.github.opencircuit.health.SampleRow
import io.github.opencircuit.health.SkinTemperature
import io.github.opencircuit.health.SkinTemperatureLocation
import io.github.opencircuit.health.WriteGeneration
import io.github.opencircuit.health.WriteGenerationStore
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Heart-rate variability, blood oxygen, breathing rate and one night of skin temperature written
 * through the real platform Health Connect on the emulator and read back with the WRITE
 * permissions alone (own records): proves the Jetpack adapter's mapping of each record — RMSSD in
 * ms, a percent, breaths per minute, a FINGER skin record with its baseline and differences — on
 * the platform, which the JVM tests cannot. Only this test's records are touched, by id, and they
 * are deleted at the end. The permissions are granted from the shell (a grant does not end the
 * app's process; a revoke would).
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectScalarReadBackTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hc = JetpackHealthConnect(context)
    private val permissions = listOf(
        HealthPermission.HEART_RATE_VARIABILITY, HealthPermission.OXYGEN_SATURATION, HealthPermission.RESPIRATORY_RATE, HealthPermission.SKIN_TEMPERATURE,
    )

    // Readings three hours back, on whole seconds; the night is keyed at the readings, its prior nights a day apart.
    private val t0: Instant = Instant.now().minusSeconds(3 * 3600).truncatedTo(ChronoUnit.SECONDS)
    private val tonight = NightWindow("${t0.epochSecond}.0", t0, t0.minusSeconds(1800), t0.plusSeconds(1800), 33.9)

    // Prior nights' means 33.5, 34.0, 34.5: the baseline is 34.0.
    private val prior = (1..3).map { d ->
        val key = t0.minusSeconds(d * 86_400L)
        NightWindow("${key.epochSecond}.0", key, key.minusSeconds(1800), key.plusSeconds(1800), 33.0 + d * 0.5)
    }

    private val readings = listOf(
        QuantitySample(MetricKind.HRV_SDNN, t0, value = 42.0),
        QuantitySample(MetricKind.SPO2, t0, value = 0.97),
        QuantitySample(MetricKind.RESPIRATORY_RATE, t0, value = 14.5),
        QuantitySample(MetricKind.TEMPERATURE, t0.minusSeconds(600), value = 33.2),
        QuantitySample(MetricKind.TEMPERATURE, t0.plusSeconds(600), value = 34.1),
    )

    private val ids = mapOf(
        RecordType.HEART_RATE_VARIABILITY_RMSSD to "oc:hrv:${t0.toEpochMilli()}:0",
        RecordType.OXYGEN_SATURATION to "oc:spo2:${t0.toEpochMilli()}:0",
        RecordType.RESPIRATORY_RATE to "oc:rr:${t0.toEpochMilli()}:0",
        RecordType.SKIN_TEMPERATURE to "oc:skin:${t0.epochSecond}.0",
    )

    @Before
    fun grant() {
        for (p in permissions) {
            val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} ${p.permission}")
            // Read the command's output to its end: closing at once returns before the grant is made.
            ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        }
        runBlocking {
            withTimeout(10_000) { while (!hc.grantedWrites().containsAll(permissions)) delay(100) }
            deleteOwn()
        }
    }

    @After
    fun cleanUp() = runBlocking { deleteOwn() }

    private suspend fun deleteOwn() = ids.forEach { (type, id) -> hc.deleteByClientIds(type, listOf(id)) }

    /** The store side: the readings above as rows 1…5, the four nights, marks forward only. */
    private inner class Store : HealthSampleStore {
        private val marks = mutableMapOf<MetricKind, Instant>()
        private fun rowsOf(kind: MetricKind) = readings.withIndex().filter { it.value.kind == kind }.map { SampleRow(it.index + 1L, it.value) }
        override suspend fun pendingSamples(kind: MetricKind) = pendingRows(kind).map { it.sample }
        override suspend fun samples(kind: MetricKind, from: Instant, until: Instant) = rows(kind, from, until).map { it.sample }
        override suspend fun pendingRows(kind: MetricKind) = rowsOf(kind).filter { r -> marks[kind].let { it == null || r.sample.start.isAfter(it) } }
        override suspend fun rows(kind: MetricKind, from: Instant, until: Instant) =
            rowsOf(kind).filter { !it.sample.start.isBefore(from) && it.sample.start.isBefore(until) }
        override suspend fun nights(from: Instant, until: Instant) =
            (prior + tonight).filter { !it.night.isBefore(from) && it.night.isBefore(until) }.sortedBy { it.night }
        override suspend fun markWritten(samples: List<QuantitySample>) {
            for ((kind, ofKind) in samples.groupBy { it.kind }) {
                val newest = ofKind.maxOf { it.start }
                if (marks[kind].let { it == null || it.isBefore(newest) }) marks[kind] = newest
            }
        }
    }

    private class Generations : WriteGenerationStore {
        var value: Long? = null
        override suspend fun load() = value
        override suspend fun save(generation: Long) {
            value = generation
        }
    }

    private class Failures : HealthFailureStore {
        var stored: Map<RecordType, Instant> = emptyMap()
        override suspend fun load() = stored
        override suspend fun save(failures: Map<RecordType, Instant>) {
            stored = failures
        }
    }

    @Test
    fun eachSingleReadingTypeAndASkinNightReadBackAsWritten() = runBlocking {
        assertTrue("the emulator's Health Connect offers skin temperature", hc.featureAvailable(HealthFeature.SKIN_TEMPERATURE))

        val result = HealthWriter(hc, Store(), WriteGeneration(Generations()), Failures(), Instant::now, ZoneId::systemDefault) { RingDevice("Gen 2") }.flush()

        assertEquals(emptyMap<RecordType, String>(), result.failures)
        assertEquals(ids.keys.associateWith { 1 }, result.written)
        val from = t0.minusSeconds(3600)
        val until = t0.plusSeconds(3600)
        val hrv = hc.readOwn(RecordType.HEART_RATE_VARIABILITY_RMSSD, from, until).map { it as HeartRateVariabilityRmssd }
            .single { it.meta.clientRecordId == ids[RecordType.HEART_RATE_VARIABILITY_RMSSD] }
        assertEquals(42.0, hrv.millis, 0.0)
        assertEquals(t0, hrv.time)
        val ox = hc.readOwn(RecordType.OXYGEN_SATURATION, from, until).map { it as OxygenSaturation }.single { it.meta.clientRecordId == ids[RecordType.OXYGEN_SATURATION] }
        assertEquals(97.0, ox.percent, 0.0)
        val rr = hc.readOwn(RecordType.RESPIRATORY_RATE, from, until).map { it as RespiratoryRate }.single { it.meta.clientRecordId == ids[RecordType.RESPIRATORY_RATE] }
        assertEquals(14.5, rr.breathsPerMinute, 0.0)
        val skin = hc.readOwn(RecordType.SKIN_TEMPERATURE, from, until).map { it as SkinTemperature }.single { it.meta.clientRecordId == ids[RecordType.SKIN_TEMPERATURE] }
        assertEquals(SkinTemperatureLocation.FINGER, skin.location)
        assertEquals(34.0, skin.baselineCelsius!!, 1e-9)
        assertEquals(listOf(-0.8, 0.1), skin.deltas.map { Math.round(it.deltaCelsius * 1000) / 1000.0 })
        assertEquals(listOf(t0.minusSeconds(600), t0.plusSeconds(600)), skin.deltas.map { it.time })
        assertEquals(t0.plusSeconds(601), skin.end)
        assertEquals(RingDevice("Gen 2"), skin.meta.device)
    }
}
