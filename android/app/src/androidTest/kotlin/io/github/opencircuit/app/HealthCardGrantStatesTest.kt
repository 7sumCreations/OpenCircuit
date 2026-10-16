package io.github.opencircuit.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.health.HealthAccess
import io.github.opencircuit.app.health.HealthAccessMemory
import io.github.opencircuit.app.health.HealthCardAction
import io.github.opencircuit.app.health.HealthCardInput
import io.github.opencircuit.app.health.HealthCardPresenter
import io.github.opencircuit.app.health.HealthFlushState
import io.github.opencircuit.health.HealthFailureStore
import io.github.opencircuit.health.HealthFeature
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthSampleStore
import io.github.opencircuit.health.HealthShare
import io.github.opencircuit.health.HealthWriter
import io.github.opencircuit.health.JetpackHealthConnect
import io.github.opencircuit.health.NightWindow
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RingDevice
import io.github.opencircuit.health.SampleRow
import io.github.opencircuit.health.WriteGeneration
import io.github.opencircuit.health.WriteGenerationStore
import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The Health Connect card from what the platform really grants: the app's own access read
 * (`HealthAccess` over the Jetpack adapter) must give the card the platform's own grant view
 * (`checkSelfPermission`) predicts — "Not connected" with Open Health Connect settings when
 * nothing is granted after asking, amber "Not allowed: …" naming exactly the missing types when
 * some are, "Connected" without amber when all are. A write in the same state writes heart rate
 * and SpO2 where granted and skips (never fails) the one that is not.
 *
 * Revoking a health permission ends the app's process (measured on Android 16), so the grants
 * are set from OUTSIDE this process. To check one state on purpose, set it with adb and run the
 * class alone with `-e hcCardExpect none|some|all`; the test then also asserts it ran in that
 * state. Without the argument (the full connected run) it checks whatever state the earlier
 * tests left.
 */
@RunWith(AndroidJUnit4::class)
class HealthCardGrantStatesTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hc = JetpackHealthConnect(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val written = mutableListOf<Pair<RecordType, String>>()

    // Six hours back at minute 13: a window no other test writes in.
    private val t0: Instant = Instant.now().minusSeconds(6 * 3600).truncatedTo(ChronoUnit.HOURS).plusSeconds(13 * 60)

    @After
    fun cleanUp() {
        runBlocking { written.forEach { (type, id) -> hc.deleteByClientIds(type, listOf(id)) } }
        scope.cancel()
    }

    private object AskedOnce : HealthAccessMemory {
        override suspend fun asked() = true
        override suspend fun markAsked() = Unit
        override suspend fun failures(): Map<RecordType, Instant> = emptyMap()
    }

    private class Samples(private val stored: List<QuantitySample>) : HealthSampleStore {
        override suspend fun pendingSamples(kind: MetricKind) = stored.filter { it.kind == kind }
        override suspend fun samples(kind: MetricKind, from: Instant, until: Instant) =
            stored.filter { it.kind == kind && !it.start.isBefore(from) && it.start.isBefore(until) }
        override suspend fun pendingRows(kind: MetricKind) = pendingSamples(kind).map { SampleRow(stored.indexOf(it) + 1L, it) }
        override suspend fun rows(kind: MetricKind, from: Instant, until: Instant) = samples(kind, from, until).map { SampleRow(stored.indexOf(it) + 1L, it) }
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
    fun theCardFollowsWhatThePlatformGrants() = runBlocking {
        // The platform's own view, independent of Health Connect's client.
        val platform = HealthPermission.entries.filter { context.checkSelfPermission(it.permission) == PackageManager.PERMISSION_GRANTED }.toSet()
        val requested = HealthShare.requested(hc.featureAvailable(HealthFeature.SKIN_TEMPERATURE))
        val granted = platform intersect requested
        val expect = InstrumentationRegistry.getArguments().getString("hcCardExpect")
        when (expect) {
            null -> Unit
            "none" -> assertEquals("set from adb: nothing granted", emptySet<HealthPermission>(), granted)
            "all" -> assertEquals("set from adb: everything granted", requested, granted)
            "some" -> assertTrue("set from adb: some granted, got $granted", granted.isNotEmpty() && granted != requested)
            else -> error("hcCardExpect=$expect")
        }

        val access = HealthAccess(hc, AskedOnce, scope) {}
        access.refresh().join()
        assertEquals(platform, access.state.value.granted)
        val ui = HealthCardPresenter.present(HealthCardInput(access.state.value, HealthFlushState(), Instant.now()))

        when {
            granted.isEmpty() -> {
                assertEquals("Not connected", ui.headline)
                assertEquals("Open Health Connect settings", ui.button)
                assertEquals(HealthCardAction.MANAGE, ui.action)
            }
            granted == requested -> {
                assertEquals("Connected", ui.headline)
                assertNull(ui.warning)
                assertEquals(HealthCardAction.WRITE_NOW, ui.action)
            }
            else -> {
                assertEquals("Connected", ui.headline)
                assertEquals("Not allowed: ${HealthShare.names(requested - granted).joinToString()}. The rest is being written.", ui.warning)
                assertEquals("Change in Health Connect", ui.secondButton)
                assertEquals(HealthCardAction.MANAGE, ui.secondAction)
            }
        }

        // A write in this state: heart rate and SpO2 written where granted, skipped where not.
        val readings = listOf(
            QuantitySample(MetricKind.HEART_RATE, t0, value = 63.0),
            QuantitySample(MetricKind.SPO2, t0, value = 0.97),
        )
        val writer = HealthWriter(hc, Samples(readings), WriteGeneration(Generations()), Failures(), Instant::now, ZoneId::systemDefault, device = { RingDevice("Gen 2") })
        val result = writer.flush()
        for (type in listOf(RecordType.HEART_RATE, RecordType.OXYGEN_SATURATION)) {
            if (type.permission in platform) {
                assertEquals("$type written", 1, result.written[type])
                val own = hc.readOwn(type, t0.minusSeconds(3600), t0.plusSeconds(3600))
                assertEquals("$type read back", 1, own.size)
                own.forEach { written += type to it.meta.clientRecordId }
            } else {
                assertTrue("$type skipped as not allowed", type in result.notGranted)
                assertTrue("$type never a failure", type !in result.failures)
            }
        }
    }
}
