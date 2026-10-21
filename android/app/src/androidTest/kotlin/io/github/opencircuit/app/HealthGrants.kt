package io.github.opencircuit.app

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.health.HealthConnectPort
import io.github.opencircuit.health.HealthNotGrantedException
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.RecordType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** A client record id no test writes: deleting it changes nothing, but Health Connect checks the grant first. */
private const val GRANT_PROBE_ID = "oc:grant-probe"

/**
 * Grants [permissions] to [packageName] from the shell (a grant does not end the app's process; a
 * revoke would) and returns once Health Connect ITSELF accepts a write-guarded call for each.
 *
 * The app-side grant list ([HealthConnectPort.grantedWrites]) shows a fresh grant before Health
 * Connect's own check does. Measured on the emulator straight after an install, on the first
 * grant: a delete was refused with "Caller doesn't have android.permission.health.WRITE_STEPS"
 * after the grant list already held every permission, and accepted a few tens of milliseconds
 * later. A test that waited on the grant list alone failed whenever it was the first to grant after
 * the install — in a full run, the first Health Connect read-back class. Waiting on the call Health
 * Connect enforces makes every caller independent of the order it runs in.
 */
suspend fun grantHealthWrites(
    hc: HealthConnectPort,
    packageName: String,
    permissions: Collection<HealthPermission>,
    timeoutMillis: Long = 10_000,
) {
    for (p in permissions) {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant $packageName ${p.permission}")
        // Read the command's output to its end: closing at once returns before the grant is made.
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }
    val probes = permissions.map { p -> RecordType.entries.first { it.permission == p } }
    withTimeout(timeoutMillis) {
        for (type in probes) {
            while (!accepted(hc, type)) delay(50)
        }
    }
}

/** Whether Health Connect accepts a write-guarded call for [type] now. */
private suspend fun accepted(hc: HealthConnectPort, type: RecordType): Boolean = try {
    hc.deleteByClientIds(type, listOf(GRANT_PROBE_ID))
    true
} catch (_: HealthNotGrantedException) {
    false
}
