package io.github.opencircuit.app

import io.github.opencircuit.health.HealthPermission
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * What the app declares for Health Connect, read from the source manifest (host tests run with the
 * module directory as working directory).
 *
 * - Exactly twelve WRITE permissions (typed out here, not read from the code) and no READ one: an
 *   app reads back what it wrote with its WRITE permission alone.
 * - The `VIEW_PERMISSION_USAGE` alias in the `HEALTH_PERMISSIONS` category, guarded by
 *   `START_VIEW_PERMISSION_USAGE`: on Android 14 and later the platform refuses every health
 *   permission to an app without it, and removing it in an update revokes them all.
 * - The Jetpack library's exported service removed: it only serves the Health Connect app of
 *   Android 13 and earlier, which this app (minimum Android 14) never talks to.
 * - No `<queries>` and no INTERNET permission.
 */
class HealthManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val toolsNs = "http://schemas.android.com/tools"

    private val manifest: Element = run {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }

    private fun elements(parent: Element, tag: String): List<Element> =
        parent.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private val requested: List<String> = elements(manifest, "uses-permission").map { it.getAttributeNS(androidNs, "name") }

    private val expectedHealthWrites = listOf(
        "android.permission.health.WRITE_HEART_RATE",
        "android.permission.health.WRITE_RESTING_HEART_RATE",
        "android.permission.health.WRITE_HEART_RATE_VARIABILITY",
        "android.permission.health.WRITE_OXYGEN_SATURATION",
        "android.permission.health.WRITE_RESPIRATORY_RATE",
        "android.permission.health.WRITE_SKIN_TEMPERATURE",
        "android.permission.health.WRITE_STEPS",
        "android.permission.health.WRITE_DISTANCE",
        "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
        "android.permission.health.WRITE_BASAL_METABOLIC_RATE",
        "android.permission.health.WRITE_SLEEP",
        "android.permission.health.WRITE_MENSTRUATION",
    )

    @Test
    fun exactlyTheTwelveHealthWritePermissionsAreDeclared() {
        val health = requested.filter { it.startsWith("android.permission.health.") }
        assertEquals(expectedHealthWrites.sorted(), health.sorted())
        assertEquals(health.size, health.toSet().size, "a health permission is declared twice")
    }

    @Test
    fun theManifestAndTheWritersPermissionSetAgree() {
        assertEquals(expectedHealthWrites.sorted(), HealthPermission.ALL_STRINGS.sorted())
    }

    @Test
    fun noReadOrBackgroundHealthPermissionAndNoNetwork() {
        val forbidden = requested.filter {
            it.startsWith("android.permission.health.READ_") || it == "android.permission.INTERNET"
        }
        assertEquals(emptyList(), forbidden)
        assertEquals(0, elements(manifest, "queries").size, "no <queries>")
    }

    @Test
    fun thePermissionUsageAliasPointsAtTheRationaleActivity() {
        val application = elements(manifest, "application").single()
        val aliases = elements(application, "activity-alias").filter { alias ->
            elements(alias, "action").any { it.getAttributeNS(androidNs, "name") == "android.intent.action.VIEW_PERMISSION_USAGE" }
        }
        assertEquals(1, aliases.size, "one VIEW_PERMISSION_USAGE alias")
        val alias = aliases.single()
        assertEquals("android.permission.START_VIEW_PERMISSION_USAGE", alias.getAttributeNS(androidNs, "permission"))
        assertEquals("true", alias.getAttributeNS(androidNs, "exported"))
        assertEquals(
            listOf("android.intent.category.HEALTH_PERMISSIONS"),
            elements(alias, "category").map { it.getAttributeNS(androidNs, "name") },
        )
        val target = alias.getAttributeNS(androidNs, "targetActivity")
        assertEquals(".health.HealthPermissionsRationaleActivity", target)
        val activity = elements(application, "activity").single { it.getAttributeNS(androidNs, "name") == target }
        assertEquals("false", activity.getAttributeNS(androidNs, "exported"), "only the guarded alias is exported")
        assertTrue(
            File("src/main/kotlin/io/github/opencircuit/app/health/HealthPermissionsRationaleActivity.kt").isFile,
            "the alias targets a class that exists",
        )
    }

    @Test
    fun theLibrarysExportedServiceIsRemoved() {
        val application = elements(manifest, "application").single()
        val removed = elements(application, "service").filter {
            it.getAttributeNS(androidNs, "name") == "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService"
        }
        assertEquals(1, removed.size)
        assertEquals("remove", removed.single().getAttributeNS(toolsNs, "node"))
    }
}
