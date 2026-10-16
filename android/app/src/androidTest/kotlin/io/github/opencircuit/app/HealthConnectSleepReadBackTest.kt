package io.github.opencircuit.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.health.HealthFailureStore
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthSampleStore
import io.github.opencircuit.health.HealthSleepStore
import io.github.opencircuit.health.HealthWriter
import io.github.opencircuit.health.JetpackHealthConnect
import io.github.opencircuit.health.NightMirror
import io.github.opencircuit.health.NightWindow
import io.github.opencircuit.health.QueuedNightEdit
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RecordingMethod
import io.github.opencircuit.health.RingDevice
import io.github.opencircuit.health.SampleRow
import io.github.opencircuit.health.SleepSession
import io.github.opencircuit.health.SleepStageType
import io.github.opencircuit.health.StoredNapToWrite
import io.github.opencircuit.health.StoredSleepNight
import io.github.opencircuit.health.WriteGeneration
import io.github.opencircuit.health.WriteGenerationStore
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import io.github.opencircuit.ringkit.SleepProvenance
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.SleepStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A staged night and a nap the wearer added, written through the real platform Health Connect on
 * the emulator and read back with WRITE_SLEEP alone (own records): proves the Jetpack adapter's
 * sleep mapping — stage kinds by the platform's numbers, the notes, the nap's title and manual
 * method — and that a night moved to another key is written under its new id and its old id
 * deleted by clientRecordId on the platform. Only this test's records are touched, by id, and they
 * are deleted at the end. The permission is granted from the shell.
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectSleepReadBackTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hc = JetpackHealthConnect(context)

    // A night 10 h to 2 h ago, on whole seconds, keyed by two made-up keys (old and moved).
    private val bed: Instant = Instant.now().minusSeconds(10 * 3600).truncatedTo(ChronoUnit.SECONDS)
    private fun at(min: Long): Instant = bed.plusSeconds(min * 60)
    private val oldKey: Instant = bed.minusSeconds(86_400)
    private val newKey: Instant = bed
    private val napStart: Instant = at(500)
    private val ids = listOf("oc:sleep:${oldKey.epochSecond}.0", "oc:sleep:${newKey.epochSecond}.0", "oc:nap:${napStart.toEpochMilli()}")

    private val timeline = listOf(
        SleepSegment(at(0), at(480), SleepStage.IN_BED),
        SleepSegment(at(10), at(200), SleepStage.ASLEEP_CORE),
        SleepSegment(at(200), at(260), SleepStage.ASLEEP_DEEP),
        SleepSegment(at(260), at(380), SleepStage.ASLEEP_REM),
        SleepSegment(at(380), at(400), SleepStage.AWAKE),
        SleepSegment(at(400), at(470), SleepStage.ASLEEP_CORE),
    )

    @Before
    fun grant() {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} ${HealthPermission.SLEEP.permission}")
        // Read the command's output to its end: closing at once returns before the grant is made.
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        runBlocking {
            withTimeout(10_000) { while (HealthPermission.SLEEP !in hc.grantedWrites()) delay(100) }
            hc.deleteByClientIds(RecordType.SLEEP_SESSION, ids)
        }
    }

    @After
    fun cleanUp() = runBlocking { hc.deleteByClientIds(RecordType.SLEEP_SESSION, ids) }

    /** One stored night under [key] and one nap the wearer added; ids, mirror and the edit queue kept in memory. */
    private class Sleep(var key: Instant, private val timeline: List<SleepSegment>, private val nap: StoredNapToWrite) : HealthSleepStore {
        private val mirrors = mutableMapOf<Instant, NightMirror>()
        private val recordIds = mutableMapOf<Instant, List<String>>()
        private var napWritten = false
        private var edited = false
        val queue = mutableListOf<QueuedNightEdit>()
        fun move(to: Instant) {
            mirrors.remove(key)?.let { mirrors[to] = it }
            recordIds.remove(key)?.let { recordIds[to] = it }
            key = to
        }
        fun edit(edit: QueuedNightEdit) {
            edited = true
            queue += edit
        }
        override suspend fun nights() = listOf(StoredSleepNight("${key.epochSecond}.0", key, edited, timeline, timeline.maxOf { it.end }))
        override suspend fun mirror(night: Instant) = mirrors[night]
        override suspend fun setMirror(night: Instant, mirror: NightMirror) { mirrors[night] = mirror }
        override suspend fun recordIds(night: Instant) = recordIds[night].orEmpty()
        override suspend fun setRecordIds(night: Instant, ids: List<String>) { recordIds[night] = ids }
        override suspend fun markNightWritten(segments: List<SleepSegment>) = Unit
        override suspend fun markEditWritten(night: Instant, segments: List<SleepSegment>, recordedEnd: Instant) = Unit
        override suspend fun queuedEdits() = queue.toList()
        override suspend fun clearQueuedEdit(edit: QueuedNightEdit) = queue.remove(edit)
        override suspend fun pendingNaps() = if (napWritten) emptyList() else listOf(nap)
        override suspend fun markNapWritten(nap: StoredNapToWrite) { napWritten = true }
        override suspend fun autoNaps(from: Instant, to: Instant) = emptyList<StoredNapToWrite>()
        override suspend fun deleteNaps(starts: List<Instant>) = Unit
    }

    private object NoSamples : HealthSampleStore {
        override suspend fun pendingSamples(kind: MetricKind) = emptyList<QuantitySample>()
        override suspend fun samples(kind: MetricKind, from: Instant, until: Instant) = emptyList<QuantitySample>()
        override suspend fun pendingRows(kind: MetricKind) = emptyList<SampleRow>()
        override suspend fun rows(kind: MetricKind, from: Instant, until: Instant) = emptyList<SampleRow>()
        override suspend fun nights(from: Instant, until: Instant) = emptyList<NightWindow>()
        override suspend fun markWritten(samples: List<QuantitySample>) = Unit
    }

    private class Generations : WriteGenerationStore {
        var value: Long? = null
        override suspend fun load() = value
        override suspend fun save(generation: Long) { value = generation }
    }

    private class Failures : HealthFailureStore {
        var stored: Map<RecordType, Instant> = emptyMap()
        override suspend fun load() = stored
        override suspend fun save(failures: Map<RecordType, Instant>) { stored = failures }
    }

    @Test
    fun aStagedNightAndAManualNapReadBackAsWrittenAndAMovedNightKeepsOneRecord() = runBlocking {
        val nap = StoredNapToWrite(
            napStart, napStart, napStart.plusSeconds(1800),
            listOf(SleepSegment(napStart, napStart.plusSeconds(1800), SleepStage.IN_BED), SleepSegment(napStart, napStart.plusSeconds(1800), SleepStage.ASLEEP_CORE)),
            manuallyAdded = true, healthWritten = false,
        )
        val sleep = Sleep(oldKey, timeline, nap)
        val generations = Generations()
        fun writer() = HealthWriter(hc, NoSamples, WriteGeneration(generations), Failures(), Instant::now, ZoneId::systemDefault, device = { RingDevice("Gen 2") }, sleep = sleep)

        val first = writer().flush()
        assertEquals(first.toString(), emptyMap<RecordType, String>(), first.failures)
        assertEquals(first.toString(), mapOf(RecordType.SLEEP_SESSION to 2), first.written)

        val own = hc.readOwn(RecordType.SLEEP_SESSION, at(-60), at(600)).map { it as SleepSession }.filter { it.meta.clientRecordId in ids }
        val night = own.single { it.meta.clientRecordId == ids[0] }
        assertEquals(at(0), night.start)
        assertEquals(at(480), night.end)
        assertEquals(
            listOf(
                SleepStageType.AWAKE_IN_BED, SleepStageType.LIGHT, SleepStageType.DEEP, SleepStageType.REM,
                SleepStageType.AWAKE, SleepStageType.LIGHT, SleepStageType.AWAKE_IN_BED,
            ),
            night.stages.map { it.stage },
        )
        assertEquals(RecordingMethod.AUTOMATICALLY_RECORDED, night.meta.method)
        assertEquals(RingDevice("Gen 2"), night.meta.device)
        assertNull(night.title)
        assertNull(night.notes)
        val napRecord = own.single { it.meta.clientRecordId == ids[2] }
        assertEquals("Nap", napRecord.title)
        assertEquals(RecordingMethod.MANUAL_ENTRY, napRecord.meta.method)
        assertEquals(listOf(SleepStageType.SLEEPING), napRecord.stages.map { it.stage })

        // The night moves to its wake-day key: written under the new id, the old id deleted.
        sleep.move(newKey)
        writer().flush()

        val after = hc.readOwn(RecordType.SLEEP_SESSION, at(-60), at(600)).filter { it.meta.clientRecordId in ids }.map { it.meta.clientRecordId }.sorted()
        assertEquals(listOf(ids[1], ids[2]).sorted(), after)

        // The wearer moves her bedtime 20 min earlier, over ground the ring did not record: the
        // edited night replaces its record under the same id, her part named in its notes.
        val lead = listOf(
            SleepSegment(at(-20), at(0), SleepStage.IN_BED, SleepProvenance.ASSERTED),
            SleepSegment(at(-20), at(0), SleepStage.ASLEEP_CORE, SleepProvenance.ASSERTED),
        )
        sleep.edit(QueuedNightEdit(newKey, at(-20), at(-20), at(480), lead + timeline))
        val third = writer().flush()
        assertEquals(third.toString(), mapOf(RecordType.SLEEP_SESSION to 1), third.written)

        val edited = hc.readOwn(RecordType.SLEEP_SESSION, at(-60), at(600)).map { it as SleepSession }.filter { it.meta.clientRecordId in ids }
        assertEquals(listOf(ids[1], ids[2]).sorted(), edited.map { it.meta.clientRecordId }.sorted())
        val replaced = edited.single { it.meta.clientRecordId == ids[1] }
        assertEquals(at(-20), replaced.start)
        assertEquals(SleepStageType.LIGHT, replaced.stages.first().stage)
        assertEquals(true, replaced.notes?.startsWith("Times you entered: "))
        assertEquals(true, sleep.queue.isEmpty())
    }
}
