package io.github.opencircuit.app

import io.github.opencircuit.app.health.HealthSwitchedOffException
import io.github.opencircuit.app.health.SwitchedHealthConnect
import io.github.opencircuit.health.FakeHealthConnect
import io.github.opencircuit.health.HealthFeature
import io.github.opencircuit.health.HealthSdkStatus
import io.github.opencircuit.health.RecordType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The one door to Health Connect: while the switch is OFF every one of the port's six calls is
 * refused before it reaches Health Connect, as a cancellation (so the writer and the access reader
 * stop the way they stop for any cancellation: nothing recorded as failed, the marks left where
 * Health Connect last accepted). While ON each call passes through unchanged.
 */
class SwitchedHealthConnectTest {

    private val now = Instant.parse("2026-10-21T10:00:00Z")

    @Test
    fun offRefusesAllSixCallsBeforeTheyReachHealthConnectAndOnPassesThem() = runTest {
        val recorded = RecordingPort(FakeHealthConnect({ now }).apply { grantAll() })
        var on = false
        val port = SwitchedHealthConnect(recorded, enabled = { on })
        val calls: List<suspend () -> Unit> = listOf(
            { port.sdkStatus() },
            { port.grantedWrites() },
            { port.featureAvailable(HealthFeature.SKIN_TEMPERATURE) },
            { port.insert(RecordType.HEART_RATE, emptyList()) },
            { port.deleteByClientIds(RecordType.HEART_RATE, listOf("x")) },
            { port.readOwn(RecordType.HEART_RATE, now.minusSeconds(60), now) },
        )

        for (call in calls) {
            val refused = assertFailsWith<HealthSwitchedOffException> { call() }
            assertIs<CancellationException>(refused, "refused as a cancellation, never as a failure")
        }
        assertEquals(emptyList(), recorded.calls, "nothing reached Health Connect while OFF")

        on = true
        assertEquals(HealthSdkStatus.AVAILABLE, port.sdkStatus())
        assertTrue(port.grantedWrites().isNotEmpty())
        for (call in calls) call()
        assertEquals(
            listOf("sdkStatus", "grantedWrites", "sdkStatus", "grantedWrites", "featureAvailable", "insert", "deleteByClientIds", "readOwn"),
            recorded.calls,
        )
    }
}
