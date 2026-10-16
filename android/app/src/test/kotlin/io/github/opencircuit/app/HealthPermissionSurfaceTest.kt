package io.github.opencircuit.app

import io.github.opencircuit.health.HealthPermission
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one place the app asks for Health Connect permissions (adapted from upstream
 * OpenCircuitKit `HealthKitAuthorizationSurfaceTests.swift` :92, :135, which pin HealthKit's one
 * authorization surface and its one type set):
 * - exactly one request surface in the app: Jetpack's request contract, registered through the
 *   activity result registry (on Android 14 and later its intent is handled only there; a plain
 *   startActivityForResult finds no activity — measured on Android 16), launched only from the
 *   card's button, never at launch;
 * - one permission set for the request and for the status read: the twelve WRITE permissions of
 *   `HealthPermission`, which the manifest declares (`HealthManifestTest` checks the manifest);
 * - nothing in the app builds the contract or the request intent itself.
 * Read from the app's sources (host tests run with the module directory as working directory).
 */
class HealthPermissionSurfaceTest {

    private val main = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        .associate { it.invariantSeparatorsPath to it.readText() }
    private val activity = main.entries.single { it.key.endsWith("/MainActivity.kt") }.value
    private val adapter = File("../health/src/androidMain/kotlin/io/github/opencircuit/health/JetpackHealthConnect.kt").readText()

    @Test
    fun theAppHasOneHealthPermissionRequestRegisteredThroughTheActivityResultRegistry() {
        assertTrue(main.size >= 50, "only ${main.size} files scanned")
        val registrations = main.flatMap { (path, text) -> Regex("""registerForActivityResult\(JetpackHealthConnect\.permissionRequestContract\(\)\)""").findAll(text).map { path } }
        assertEquals(listOf("src/main/kotlin/io/github/opencircuit/app/MainActivity.kt"), registrations.toList())
        assertEquals(1, Regex("""healthPermissionRequest\.launch\(""").findAll(activity).count(), "launched from one place")
        assertTrue("healthPermissionRequest.launch(HealthPermission.ALL_STRINGS.toSet())" in activity, "the one permission set")
        // Only behind the card's button.
        assertTrue(Regex("""RingAction\.ConnectHealth -> \{?\s*askForHealthConnect\(\)""").containsMatchIn(activity))
    }

    @Test
    fun nothingElseBuildsTheRequestOrStartsItOutsideTheRegistry() {
        val offenders = main.filter { (_, text) ->
            "createRequestPermissionResultContract" in text || "startActivityForResult" in text ||
                "androidx.health.ACTION_REQUEST_PERMISSIONS" in text || "REQUEST_PERMISSIONS" in text
        }.keys
        assertEquals(emptySet(), offenders)
    }

    @Test
    fun theStatusReadAndTheRequestUseTheSameTwelvePermissions() {
        assertEquals(12, HealthPermission.ALL_STRINGS.size)
        assertEquals(HealthPermission.ALL_STRINGS.toSet().size, HealthPermission.ALL_STRINGS.size)
        assertTrue(HealthPermission.ALL_STRINGS.all { it.startsWith("android.permission.health.WRITE_") })
        // The status read keeps only the app's own set out of whatever Health Connect reports.
        assertTrue("HealthPermission.entries.filter { it.permission in granted }" in adapter)
    }
}
