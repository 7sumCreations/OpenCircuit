package io.github.opencircuit.health

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Jetpack adapter runs only on a phone, so the call shapes Health Connect insists on are
 * checked in its source here (the instrumented read-back proves them on the emulator):
 * - a read of the app's own records is filtered to its own package and paged — measured on
 *   Android 16, an unfiltered read with WRITE permissions only is refused, and a page holds at
 *   most 1000 records;
 * - every record carries its clientRecordId and version (without an id the platform de-duplicates
 *   by time, silently);
 * - the permission screen comes from Jetpack's request contract, which must be launched through
 *   the activity result registry.
 */
class JetpackAdapterSourceTest {

    private val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "opencircuit.androidRoot is not set"))
    private val adapter = File(root, "health/src/androidMain/kotlin/io/github/opencircuit/health/JetpackHealthConnect.kt").readText()

    @Test
    fun theAdapterHasEveryRequiredCallShape() {
        assertEquals(emptyList(), AdapterScan.missing(adapter))
    }

    @Test
    fun theScanFailsWhenAShapeIsMissing() {
        val unfiltered = adapter.replace("dataOriginFilter = setOf(DataOrigin(appContext.packageName))", "dataOriginFilter = emptySet()")
        assertEquals(listOf("own-package read filter"), AdapterScan.missing(unfiltered))
        val unpaged = adapter.replace("pageToken = token,", "")
        assertEquals(listOf("read pages followed"), AdapterScan.missing(unpaged))
        val noId = adapter.replace("meta.clientRecordId, meta.version)", ")")
        assertEquals(listOf("id and version on ring records"), AdapterScan.missing(noId))
        assertEquals(listOf("page size 1000"), AdapterScan.missing(adapter.replace("const val PAGE_SIZE = 1000", "const val PAGE_SIZE = 5000")))
        val unclassified = adapter.replace("HealthErrors.retryReason(", "listOf(")
        assertEquals(listOf("state errors classified"), AdapterScan.missing(unclassified))
        assertEquals(listOf("foreground user asked"), AdapterScan.missing(adapter.replace("isUserForeground", "isSystemUser")))
        val causeDropped = adapter.replace("throw HealthErrors.notGranted(type.permission, e)", "throw HealthNotGrantedException(type.permission, \"not granted\")")
        assertEquals(listOf("missing grant keeps its cause"), AdapterScan.missing(causeDropped))
    }

    @Test
    fun theSdksOwnErrorCodesNameTheRetryableCauses() {
        // The platform's constants, never their values copied (android-36.1: 7 and 8).
        assertEquals(listOf("rate limit code"), AdapterScan.missing(adapter.replace("HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED ->", "7 ->")))
        assertEquals(listOf("data sync code"), AdapterScan.missing(adapter.replace("HealthConnectException.ERROR_DATA_SYNC_IN_PROGRESS ->", "8 ->")))
    }
}

/** The shapes [JetpackAdapterSourceTest] requires, each named. */
object AdapterScan {
    private val required = linkedMapOf(
        "own-package read filter" to "dataOriginFilter = setOf(DataOrigin(appContext.packageName))",
        "read pages followed" to "pageToken = token,",
        "page size 1000" to "const val PAGE_SIZE = 1000",
        "id and version on ring records" to "meta.clientRecordId, meta.version)",
        "missing grant mapped" to "catch (e: SecurityException)",
        // The platform's own exception stays the cause: its message names what was refused.
        "missing grant keeps its cause" to "throw HealthErrors.notGranted(type.permission, e)",
        "request contract" to "PermissionController.createRequestPermissionResultContract()",
        "state errors classified" to "HealthErrors.retryReason(",
        "foreground user asked" to "isUserForeground",
        "rate limit code" to "HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED -> PlatformError.RATE_LIMIT_EXCEEDED",
        "data sync code" to "HealthConnectException.ERROR_DATA_SYNC_IN_PROGRESS -> PlatformError.DATA_SYNC_IN_PROGRESS",
        // A fault of ours (a record in another type's call, a ring record naming no ring) is a failed
        // call, shown on the card — never a record the platform refused, counted and dropped quietly.
        "type mismatch is a failure" to "throw HealthCallFailedException(\"a \${record.type} record in a \$type call\")",
        "ring record without ring is a failure" to "?: throw HealthCallFailedException(\"a ring record names no ring\")",
    )

    fun missing(source: String): List<String> = required.filterValues { it !in source }.keys.toList()
}
