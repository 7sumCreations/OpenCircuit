package io.github.opencircuit.app.health

import io.github.opencircuit.health.HealthConnectPort
import io.github.opencircuit.health.HealthFeature
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.HealthRecord
import io.github.opencircuit.health.HealthSdkStatus
import io.github.opencircuit.health.RecordType
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/** A Health Connect call refused because "Send to Health Connect" is OFF; a cancellation, never a failure. */
class HealthSwitchedOffException : CancellationException("Send to Health Connect is off")

/**
 * The app's one door to Health Connect: every call asks [enabled] (Settings' switch) first and,
 * while it is OFF, is refused before it reaches Health Connect. So turning the switch OFF stops a
 * write or a read that is already running at its next call — not only the ones that start later.
 *
 * The refusal is a cancellation: the writer and the access reader stop as they do for any
 * cancellation — nothing recorded as failed, the store's marks left where Health Connect last
 * accepted, so turning the switch back ON writes the rest.
 */
class SwitchedHealthConnect(private val inner: HealthConnectPort, private val enabled: () -> Boolean) : HealthConnectPort {

    private fun open() {
        if (!enabled()) throw HealthSwitchedOffException()
    }

    override suspend fun sdkStatus(): HealthSdkStatus {
        open()
        return inner.sdkStatus()
    }

    override suspend fun grantedWrites(): Set<HealthPermission> {
        open()
        return inner.grantedWrites()
    }

    override suspend fun featureAvailable(feature: HealthFeature): Boolean {
        open()
        return inner.featureAvailable(feature)
    }

    override suspend fun insert(type: RecordType, records: List<HealthRecord>): List<String> {
        open()
        return inner.insert(type, records)
    }

    override suspend fun deleteByClientIds(type: RecordType, clientRecordIds: List<String>) {
        open()
        inner.deleteByClientIds(type, clientRecordIds)
    }

    override suspend fun readOwn(type: RecordType, from: Instant, until: Instant): List<HealthRecord> {
        open()
        return inner.readOwn(type, from, until)
    }
}
