package io.github.opencircuit.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.health.ActiveCaloriesBurned
import io.github.opencircuit.health.BasalMetabolicRate
import io.github.opencircuit.health.Distance
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthRecord
import io.github.opencircuit.health.JetpackHealthConnect
import io.github.opencircuit.health.RecordMeta
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RecordingMethod
import io.github.opencircuit.health.RestingHeartRate
import io.github.opencircuit.health.RingDevice
import io.github.opencircuit.health.Steps
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Steps, distance, active calories, basal metabolic rate and resting heart rate written through the
 * real platform Health Connect on the emulator and read back with the WRITE permissions alone (own
 * records): proves the Jetpack adapter's mapping of each record (a count, metres, kilocalories,
 * kilocalories per day, whole beats per minute, the ids, the ring) on the platform, which the JVM
 * tests cannot. Only this test's records are touched, by id, and they are deleted at the end. The
 * permissions are granted from the shell (a grant does not end the app's process; a revoke would).
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectActivityReadBackTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hc = JetpackHealthConnect(context)
    private val permissions = listOf(
        HealthPermission.STEPS, HealthPermission.DISTANCE, HealthPermission.ACTIVE_CALORIES_BURNED,
        HealthPermission.BASAL_METABOLIC_RATE, HealthPermission.RESTING_HEART_RATE,
    )

    // Two hours back, on a whole hour; a day back for the daily records.
    private val t0: Instant = Instant.now().minusSeconds(2 * 3600).truncatedTo(ChronoUnit.HOURS)
    private val day: Instant = t0.minusSeconds(86_400)
    private val utc = ZoneOffset.UTC
    private val ring = RingDevice("Gen 2")
    private fun meta(id: String) = RecordMeta(id, 1, RecordingMethod.AUTOMATICALLY_RECORDED, ring)

    private val records: Map<RecordType, HealthRecord> = mapOf(
        RecordType.STEPS to Steps(t0, utc, t0.plusSeconds(120), utc, 81, meta("oc:steps:${t0.toEpochMilli()}")),
        RecordType.DISTANCE to Distance(t0, utc, t0.plusSeconds(120), utc, 20.088, meta("oc:dist:${t0.toEpochMilli()}")),
        RecordType.ACTIVE_CALORIES_BURNED to ActiveCaloriesBurned(t0, utc, t0.plusSeconds(900), utc, 12.5, meta("oc:ae:${t0.toEpochMilli()}")),
        RecordType.BASAL_METABOLIC_RATE to BasalMetabolicRate(day, utc, 1_592.5, meta("oc:bmr:test-${day.toEpochMilli()}")),
        RecordType.RESTING_HEART_RATE to RestingHeartRate(day, utc, 52, meta("oc:rhr:test-${day.toEpochMilli()}")),
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

    private suspend fun deleteOwn() = records.forEach { (type, r) -> hc.deleteByClientIds(type, listOf(r.meta.clientRecordId)) }

    @Test
    fun eachActivityTypeReadsBackAsWritten() = runBlocking {
        for ((type, record) in records) hc.insert(type, listOf(record))

        for ((type, record) in records) {
            val back = hc.readOwn(type, day.minusSeconds(60), t0.plusSeconds(3600)).single { it.meta.clientRecordId == record.meta.clientRecordId }
            when (back) {
                is Steps -> assertEquals(81L, back.count)
                is Distance -> assertEquals(20.088, back.meters, 1e-9)
                is ActiveCaloriesBurned -> assertEquals(12.5, back.kilocalories, 1e-9)
                is BasalMetabolicRate -> assertEquals(1_592.5, back.kilocaloriesPerDay, 1e-6)
                is RestingHeartRate -> assertEquals(52L, back.beatsPerMinute)
                else -> error("unexpected ${back.type}")
            }
            assertEquals(type, back.type)
            assertEquals(ring, back.meta.device)
            assertEquals(RecordingMethod.AUTOMATICALLY_RECORDED, back.meta.method)
        }
    }
}
