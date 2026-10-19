package io.github.opencircuit.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card's "Open Health Connect settings" and "Change in Health Connect" open Health Connect's
 * own page for this app (`ACTION_MANAGE_HEALTH_PERMISSIONS` with `EXTRA_PACKAGE_NAME`), never the
 * permission request again: after the user refused, a second request may be dropped without
 * anything shown. The intent is built in one place (the instrumented `HealthSettingsLinkTest`
 * resolves that same intent on the platform), and a phone where nothing takes it does not crash.
 * Read from the app's sources: intents and activities never run on the JVM.
 */
class HealthSettingsLinkSurfaceTest {

    private val main = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        .associate { it.invariantSeparatorsPath to it.readText() }
    private fun source(name: String) = main.entries.single { it.key.endsWith("/$name") }.value

    @Test
    fun manageOpensHealthConnectsSettingsAndConnectStillAsks() {
        val screen = source("ring/RingScreen.kt")
        assertTrue(Regex("""HealthCardAction\.MANAGE -> onAction\(RingAction\.OpenHealthSettings\)""").containsMatchIn(screen))
        assertTrue(Regex("""HealthCardAction\.CONNECT -> onAction\(RingAction\.ConnectHealth\)""").containsMatchIn(screen))
        assertTrue("HealthCardAction.CONNECT, HealthCardAction.MANAGE" !in screen, "MANAGE no longer re-asks")

        val activity = source("MainActivity.kt")
        assertTrue(Regex("""RingAction\.OpenHealthSettings -> \{?\s*openHealthConnectSettings\(\)""").containsMatchIn(activity))
    }

    @Test
    fun theIntentIsBuiltInOnePlaceForThisAppsOwnPage() {
        val builders = main.filter { (_, text) -> "ACTION_MANAGE_HEALTH_PERMISSIONS" in text }.keys
        assertEquals(setOf("src/main/kotlin/io/github/opencircuit/app/health/HealthSettingsLink.kt"), builders)
        val link = source("health/HealthSettingsLink.kt")
        assertTrue("Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)" in link)
        assertTrue(".putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)" in link)
    }

    @Test
    fun aPhoneWhereNothingTakesTheLinkDoesNotCrash() {
        val activity = source("MainActivity.kt")
        val body = activity.substringAfter("private fun openHealthConnectSettings()").substringBefore("\n    }\n")
        assertTrue("startActivity(HealthSettingsLink.intent(packageName))" in body)
        assertTrue("catch (_: ActivityNotFoundException)" in body)
    }
}
