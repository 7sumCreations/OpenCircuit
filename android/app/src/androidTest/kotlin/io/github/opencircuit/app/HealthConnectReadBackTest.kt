package io.github.opencircuit.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthSampleStore
import io.github.opencircuit.health.HealthSdkStatus
import io.github.opencircuit.health.HealthWriter
import io.github.opencircuit.health.HeartRateSeries
import io.github.opencircuit.health.HrHours
import io.github.opencircuit.health.JetpackHealthConnect
import io.github.opencircuit.health.RecordIds
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RecordingMethod
import io.github.opencircuit.health.RingDevice
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

/**
 * One heart-rate hour written through the real platform Health Connect on the emulator and read
 * back with the app's WRITE permission alone (own records, filtered to the app's package): the
 * writer over a small in-test store, the Jetpack adapter, the platform. A second write sends
 * nothing; writing the same id again replaces rather than adds. Only the record this test wrote is
 * touched, by its id, and it is deleted at the end.
 *
 * The heart-rate WRITE permission is granted from the shell before the test (a grant does not end
 * the app's process; a revoke would).
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectReadBackTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hc = JetpackHealthConnect(context)

    // Two hours back, so the whole hour is in the past.
    private val hourStart: Instant = HrHours.hourStart(Instant.now().minusSeconds(2 * 3600))
    private val hourEnd: Instant = hourStart.plusSeconds(3600)
    private val id = RecordIds.heartRateHour(hourStart)

    @Before
    fun grant() {
        // Read the command's output to its end: closing at once returns before the grant is made.
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} ${HealthPermission.HEART_RATE.permission}")
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        runBlocking {
            withTimeout(10_000) {
                while (HealthPermission.HEART_RATE !in hc.grantedWrites()) delay(100)
            }
            hc.deleteByClientIds(RecordType.HEART_RATE, listOf(id))
        }
    }

    @After
    fun cleanUp() {
        runBlocking { hc.deleteByClientIds(RecordType.HEART_RATE, listOf(id)) }
    }

    /** The store side, as the store keeps it: pending above the mark, marks forward only. */
    private class Samples(private val stored: List<QuantitySample>, var mark: Instant? = null) : HealthSampleStore {
        override suspend fun pendingSamples(kind: MetricKind) =
            stored.filter { s -> s.kind == kind && mark.let { it == null || s.start.isAfter(it) } }

        override suspend fun samples(kind: MetricKind, from: Instant, until: Instant) =
            stored.filter { it.kind == kind && !it.start.isBefore(from) && it.start.isBefore(until) }

        override suspend fun markWritten(samples: List<QuantitySample>) {
            val newest = samples.maxOf { it.start }
            if (mark.let { it == null || it.isBefore(newest) }) mark = newest
        }
    }

    private class Generations : WriteGenerationStore {
        var value: Long? = null
        override suspend fun load() = value
        override suspend fun save(generation: Long) {
            value = generation
        }
    }

    private fun writer(store: HealthSampleStore, generations: Generations) =
        HealthWriter(hc, store, WriteGeneration(generations), Instant::now, ZoneId::systemDefault) { RingDevice("Gen 2") }

    private suspend fun ownHour(): List<HeartRateSeries> =
        hc.readOwn(RecordType.HEART_RATE, hourStart, hourEnd).map { it as HeartRateSeries }.filter { it.meta.clientRecordId == id }

    @Test
    fun aHeartRateHourIsWrittenAndReadBackWithTheWritePermissionAlone() = runBlocking {
        assertEquals(HealthSdkStatus.AVAILABLE, hc.sdkStatus())
        assertTrue(HealthPermission.HEART_RATE in hc.grantedWrites())
        val readings = listOf(62.0, 64.0, 71.0).mapIndexed { i, bpm -> QuantitySample(MetricKind.HEART_RATE, hourStart.plusSeconds(600L * (i + 1)), value = bpm) }
        val store = Samples(readings)
        val generations = Generations()

        val first = writer(store, generations).flush()

        assertEquals(mapOf(RecordType.HEART_RATE to 1), first.written)
        assertEquals(readings.last().start, store.mark)
        val back = ownHour()
        assertEquals(1, back.size)
        val record = back.single()
        assertEquals(readings.map { it.start to it.value.toLong() }, record.samples.map { it.time to it.beatsPerMinute })
        assertEquals(hourStart, record.start)
        assertEquals(hourEnd, record.end)
        assertEquals(1L, record.meta.version)
        assertEquals(RecordingMethod.AUTOMATICALLY_RECORDED, record.meta.method)
        assertEquals(RingDevice("Gen 2"), record.meta.device)

        // Nothing pending: the second write sends nothing.
        assertEquals(0, writer(store, generations).flush().recordsWritten)

        // A late reading in the same hour: the hour is rewritten under its id, replaced, not doubled.
        val later = Samples(readings + QuantitySample(MetricKind.HEART_RATE, hourStart.plusSeconds(3000), value = 80.0), mark = readings.last().start)
        assertEquals(mapOf(RecordType.HEART_RATE to 1), writer(later, generations).flush().written)
        val replaced = ownHour()
        assertEquals(1, replaced.size)
        assertEquals(listOf(62L, 64L, 71L, 80L), replaced.single().samples.map { it.beatsPerMinute })
        assertEquals(2L, replaced.single().meta.version)
    }
}
