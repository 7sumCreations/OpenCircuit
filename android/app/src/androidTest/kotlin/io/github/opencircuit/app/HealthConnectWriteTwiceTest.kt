package io.github.opencircuit.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.health.ActiveCaloriesBurned
import io.github.opencircuit.health.BasalMetabolicRate
import io.github.opencircuit.health.Distance
import io.github.opencircuit.health.HealthFeature
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthRecord
import io.github.opencircuit.health.HeartRateSample
import io.github.opencircuit.health.HeartRateSeries
import io.github.opencircuit.health.HeartRateVariabilityRmssd
import io.github.opencircuit.health.JetpackHealthConnect
import io.github.opencircuit.health.MenstruationFlow
import io.github.opencircuit.health.MenstruationFlowLevel
import io.github.opencircuit.health.MenstruationPeriod
import io.github.opencircuit.health.OxygenSaturation
import io.github.opencircuit.health.RecordMeta
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RecordingMethod
import io.github.opencircuit.health.RespiratoryRate
import io.github.opencircuit.health.RestingHeartRate
import io.github.opencircuit.health.RingDevice
import io.github.opencircuit.health.SkinTemperature
import io.github.opencircuit.health.SkinTemperatureDelta
import io.github.opencircuit.health.SkinTemperatureLocation
import io.github.opencircuit.health.SleepSession
import io.github.opencircuit.health.SleepStage
import io.github.opencircuit.health.SleepStageType
import io.github.opencircuit.health.Steps
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Every record type the writer emits, written twice under the same ids through the real platform
 * Health Connect: after the second write each type still holds exactly one own record per id (an
 * upsert, never a copy), read back from the platform. The type list is checked against
 * `RecordType` so a new type cannot be left out. Only this test's records are touched, by id, and
 * they are deleted at the end. Permissions are granted from the shell (a grant does not end the
 * app's process; a revoke would).
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectWriteTwiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hc = JetpackHealthConnect(context)

    // Five hours back on whole seconds: a window no other test writes in.
    private val t0: Instant = Instant.now().minusSeconds(5 * 3600).truncatedTo(ChronoUnit.HOURS).plusSeconds(7 * 60)
    private val utc = ZoneOffset.UTC
    private val ring = RingDevice("Gen 2")
    private fun meta(id: String, method: RecordingMethod = RecordingMethod.AUTOMATICALLY_RECORDED) = RecordMeta(id, 1, method, ring)
    private val from = t0.minusSeconds(3 * 86_400)
    private val until = t0.plusSeconds(3600)

    private val records: Map<RecordType, HealthRecord> = mapOf(
        RecordType.HEART_RATE to HeartRateSeries(t0, utc, t0.plusSeconds(600), utc, listOf(HeartRateSample(t0.plusSeconds(60), 61)), meta("oc:twice:hr")),
        RecordType.RESTING_HEART_RATE to RestingHeartRate(t0, utc, 52, meta("oc:twice:rhr")),
        RecordType.HEART_RATE_VARIABILITY_RMSSD to HeartRateVariabilityRmssd(t0, utc, 41.0, meta("oc:twice:hrv")),
        RecordType.OXYGEN_SATURATION to OxygenSaturation(t0, utc, 97.0, meta("oc:twice:spo2")),
        RecordType.RESPIRATORY_RATE to RespiratoryRate(t0, utc, 14.5, meta("oc:twice:rr")),
        RecordType.SKIN_TEMPERATURE to SkinTemperature(
            t0, utc, t0.plusSeconds(601), utc, 33.4, listOf(SkinTemperatureDelta(t0.plusSeconds(600), 0.3)), SkinTemperatureLocation.FINGER, meta("oc:twice:skin"),
        ),
        RecordType.STEPS to Steps(t0, utc, t0.plusSeconds(120), utc, 81, meta("oc:twice:steps")),
        RecordType.DISTANCE to Distance(t0, utc, t0.plusSeconds(120), utc, 20.1, meta("oc:twice:dist")),
        RecordType.ACTIVE_CALORIES_BURNED to ActiveCaloriesBurned(t0, utc, t0.plusSeconds(900), utc, 12.5, meta("oc:twice:ae")),
        RecordType.BASAL_METABOLIC_RATE to BasalMetabolicRate(t0, utc, 1_592.5, meta("oc:twice:bmr")),
        RecordType.SLEEP_SESSION to SleepSession(
            t0.minusSeconds(4 * 3600), utc, t0.minusSeconds(3600), utc, null, null,
            listOf(SleepStage(t0.minusSeconds(4 * 3600), t0.minusSeconds(3600), SleepStageType.LIGHT)), meta("oc:twice:sleep"),
        ),
        RecordType.MENSTRUATION_FLOW to MenstruationFlow(t0, utc, MenstruationFlowLevel.MEDIUM, meta("oc:twice:flow", RecordingMethod.MANUAL_ENTRY)),
        RecordType.MENSTRUATION_PERIOD to MenstruationPeriod(
            t0.minusSeconds(2 * 86_400), utc, t0.minusSeconds(86_400), utc, meta("oc:twice:period", RecordingMethod.MANUAL_ENTRY),
        ),
    )

    @Before
    fun grant() = runBlocking {
        grantHealthWrites(hc, context.packageName, HealthPermission.entries)
        deleteOwn()
    }

    @After
    fun cleanUp() = runBlocking { deleteOwn() }

    private suspend fun deleteOwn() = records.forEach { (type, r) -> hc.deleteByClientIds(type, listOf(r.meta.clientRecordId)) }

    private suspend fun ownCount(type: RecordType, id: String) = hc.readOwn(type, from, until).count { it.meta.clientRecordId == id }

    @Test
    fun everyTypeWrittenTwiceHoldsOneRecordPerId() = runBlocking {
        assertEquals("every record type is covered", RecordType.entries.toSet(), records.keys)
        assertTrue("skin temperature on this AVD", hc.featureAvailable(HealthFeature.SKIN_TEMPERATURE))

        for ((type, record) in records) hc.insert(type, listOf(record))
        val first = records.mapValues { (type, r) -> ownCount(type, r.meta.clientRecordId) }
        for ((type, record) in records) hc.insert(type, listOf(record))
        val second = records.mapValues { (type, r) -> ownCount(type, r.meta.clientRecordId) }

        assertEquals(records.mapValues { 1 }, first)
        assertEquals(first, second)
    }
}
