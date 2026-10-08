package io.github.opencircuit.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `AppContainer` needs an Android `Context`, so it never runs on the JVM. A session built without
 * its history would refuse every page (nothing lost, but nothing ever synced), so the wiring is
 * checked here on the source: every session gets the history over the app's one database, opened
 * through the store's Android entry point, with the saved switch.
 */
class AppContainerHistoryWiringTest {

    private val source = File("src/main/kotlin/io/github/opencircuit/app/AppContainer.kt").readText()

    @Test
    fun everySessionIsBuiltWithTheHistoryOverTheAppsOneDatabase() {
        val sessionBuilds = Regex("""RingSessionController\(""").findAll(source).count()
        assertEquals(1, sessionBuilds, "one place builds sessions")
        assertTrue("RingSessionController(link, scope, monotonicMillis, log, history = historyFor(link))" in source)
        assertEquals(1, Regex("""StoreFactory\.open\(context\.applicationContext\)""").findAll(source).count(), "one database")
        assertTrue("database = { database.await() }" in source)
        assertTrue("disconnectAfterSync = { appPrefs.disconnectAfterSync }" in source)
    }
}
