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
        assertTrue("RingSessionController(link, scope, monotonicMillis, log, history = historyFor(link), todayVisible = todayVisible)" in source)
        assertEquals(1, Regex("""StoreFactory\.open\(context\.applicationContext\)""").findAll(source).count(), "one database")
        assertTrue("database = { database.await() }" in source)
        // The switch acts only once the app is not in front (the rule is SyncLifecycle's, tested on its own).
        assertTrue("disconnectAfterSync = { SyncLifecycle.disconnectAfterSync(appPrefs.disconnectAfterSync, appInForeground.value) }" in source)
    }

    @Test
    fun everyCommittedSyncRequestsTheHealthConnectWriteOnTheAppsOwnScope() {
        // The hook only requests; the write runs on the process scope, never the session's, so a pause or a
        // session's end cannot cut it.
        assertEquals(1, Regex("""afterCommit = \{ healthFlush\.request\(\) \}""").findAll(source).count())
        // Every write waits for the launch repairs (they move night keys the write's marks are kept under).
        assertEquals(1, Regex("""HealthFlush\(\{ launchRepairs\.join\(\); healthWriter\.flush\(\) \}, appScope, log\)""").findAll(source).count())
        assertTrue(source.indexOf("private val launchRepairs") in 0 until source.indexOf("val healthFlush"), "the repairs start before the first write can")
        // A store with no generation yet (a reinstall) starts above what an earlier install left in Health Connect.
        assertTrue("generation = WriteGeneration(StoreWriteGenerations({ database.await() }, wallNow)) { clock.nowMillis() }" in source)
        assertTrue("store = StoreHealthSamples { database.await() }" in source)
        // The writer's sleep defaults to none: the app must hand it the store's nights, edits and naps.
        assertTrue("sleep = StoreHealthSleep({ database.await() }, wallNow, ZoneId::systemDefault)" in source)
        assertTrue("generation = WriteGeneration(StoreWriteGenerations({ database.await() }, wallNow))" in source)
        assertTrue("port = healthConnect" in source)
        assertEquals(1, Regex("""JetpackHealthConnect\(context\)""").findAll(source).count(), "one Health Connect client")
    }

    @Test
    fun everySessionGetsTheAutomaticSyncsOverTheSameDatabaseZoneAndRing() {
        // Without these a session syncs only on Sync now: no link-up sync, no cadence, no catch-up.
        assertEquals(1, Regex("""triggers = SyncTriggerSources\(""").findAll(source).count())
        assertTrue("storedNights = { learningNights(database.await()) }" in source)
        assertTrue("marks = PrefsSyncMarks(keyValues, ringId)" in source)
        // The night is the phone's local one, read at each evaluation (a zone change takes effect).
        // So are the sync log's local days (the session history's own zone).
        assertEquals(3, Regex("""zone = \{ ZoneId\.systemDefault\(\) \}""").findAll(source).count(), "the store's, the triggers' and the sync log's zone")
    }
}
