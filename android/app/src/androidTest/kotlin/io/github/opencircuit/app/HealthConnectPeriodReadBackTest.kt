package io.github.opencircuit.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.JetpackHealthConnect
import io.github.opencircuit.health.MenstruationFlow
import io.github.opencircuit.health.MenstruationFlowLevel
import io.github.opencircuit.health.MenstruationPeriod
import io.github.opencircuit.health.PeriodRecords
import io.github.opencircuit.health.PeriodToWrite
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.RecordingMethod
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
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * A logged period's records, built by the writer's own builder, written through the real platform
 * Health Connect on the emulator and read back with the WRITE permission alone: proves the Jetpack
 * adapter's mapping of the flow level, the period's span (ending at the start of tomorrow, which the
 * platform accepts), the ids and the manual-entry metadata with no device. Only this test's records
 * are touched, by id, and they are deleted at the end.
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectPeriodReadBackTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hc = JetpackHealthConnect(context)
    private val utc = ZoneOffset.UTC
    private val now: Instant = Instant.now()
    private val today: Instant = now.truncatedTo(ChronoUnit.DAYS)

    // Logged two days ago, ending tomorrow: flows for the 3 days through today, a period over them.
    private val built = PeriodRecords.build(
        PeriodToWrite(today.minusSeconds(2 * 86_400).plusSeconds(8 * 3600), today.plusSeconds(86_400 + 8 * 3600), 3, false, emptyList()),
        now, utc, version = 1,
    )

    @Before
    fun grant() {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} ${HealthPermission.MENSTRUATION.permission}")
        // Read the command's output to its end: closing at once returns before the grant is made.
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        runBlocking {
            withTimeout(10_000) { while (HealthPermission.MENSTRUATION !in hc.grantedWrites()) delay(100) }
            deleteOwn()
        }
    }

    @After
    fun cleanUp() = runBlocking { deleteOwn() }

    private suspend fun deleteOwn() {
        hc.deleteByClientIds(RecordType.MENSTRUATION_FLOW, built.flows.map { it.meta.clientRecordId })
        hc.deleteByClientIds(RecordType.MENSTRUATION_PERIOD, built.periods.map { it.meta.clientRecordId })
    }

    @Test
    fun aLoggedPeriodsFlowDaysAndPeriodReadBackAsWritten() = runBlocking {
        assertEquals(3, built.flows.size)
        hc.insert(RecordType.MENSTRUATION_FLOW, built.flows)
        hc.insert(RecordType.MENSTRUATION_PERIOD, built.periods)

        val from = today.minusSeconds(3 * 86_400)
        val until = today.plusSeconds(2 * 86_400)
        val flows = hc.readOwn(RecordType.MENSTRUATION_FLOW, from, until).filter { it.meta.clientRecordId in built.flows.map { f -> f.meta.clientRecordId } }
            .map { it as MenstruationFlow }.sortedBy { it.time }
        assertEquals(built.flows.map { it.time }, flows.map { it.time })
        assertEquals(List(3) { MenstruationFlowLevel.HEAVY }, flows.map { it.flow })
        val period = hc.readOwn(RecordType.MENSTRUATION_PERIOD, from, until).single { it.meta.clientRecordId == built.periods.single().meta.clientRecordId } as MenstruationPeriod
        assertEquals(today.minusSeconds(2 * 86_400), period.start)
        assertEquals(today.plusSeconds(86_400), period.end)
        for (r in flows + period) {
            assertEquals(RecordingMethod.MANUAL_ENTRY, r.meta.method)
            assertNull(r.meta.device)
        }
    }
}
