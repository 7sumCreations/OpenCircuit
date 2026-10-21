package io.github.opencircuit.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.app.demo.DemoRingLink
import io.github.opencircuit.health.Distance
import io.github.opencircuit.health.HealthPermission
import io.github.opencircuit.health.RecordType
import io.github.opencircuit.health.Steps
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Live steps on the emulator, end to end through the real app: the debug build's demo ring sends
 * its descriptor with the current quarter hour's step bucket at the link-up and every 30 s; the
 * session folds it into the on-device store; the Health Connect write (after the launch's sync, and
 * on request) sends each stored step row as a steps record and then its distance, to the platform
 * Health Connect, where they are read back as the app's own records. The ring stays connected (the
 * "Disconnect after syncing" switch is off) so its descriptors keep coming. Only the records this
 * test finds are deleted at the end, by their ids.
 */
@RunWith(AndroidJUnit4::class)
class LiveStepsOnDemoRingTest {

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenCircuitApp
    private val written = mutableListOf<Pair<RecordType, String>>()

    @get:Rule(order = 0)
    val connectedDemoRing = object : ExternalResource() {
        override fun before() {
            runBlocking { grantHealthWrites(app.container.healthConnect, app.packageName, listOf(HealthPermission.STEPS, HealthPermission.DISTANCE)) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
            check(app.container.appPrefs.setOnboardingCompleted()) { "could not record that onboarding was done" }
            check(app.container.rememberedRings.save(DemoRingLink.RING)) { "could not remember the demo ring" }
            check(app.container.appPrefs.setDisconnectAfterSync(false)) { "could not turn the switch off" }
            // Writing to Health Connect is OFF until the user turns it on (Settings); this test is about the writing.
            check(app.container.healthSwitch.set(true)) { "could not turn Send to Health Connect on" }
        }

        override fun after() {
            // Back to the default for the tests that follow in this process.
            app.container.healthSwitch.set(false)
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun cleanUp() = runBlocking {
        written.groupBy({ it.first }, { it.second }).forEach { (type, ids) -> app.container.healthConnect.deleteByClientIds(type, ids) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.container.ringSessions.stopReconnecting() }
    }

    private suspend fun ownSteps(since: Instant): List<Steps> =
        app.container.healthConnect.readOwn(RecordType.STEPS, since, Instant.now().plusSeconds(60)).map { it as Steps }
            .filter { it.meta.clientRecordId.startsWith("oc:steps:") }

    @Test
    fun theDemoRingsStepsAreStoredAndWrittenToHealthConnectThenTheirDistance() = runBlocking {
        val since = Instant.now().minusSeconds(60)
        val flush = app.container.healthFlush

        withTimeout(150_000) {
            while (ownSteps(since).isEmpty()) {
                flush.request()
                delay(5_000)
            }
            // The write that stored them may still be on its distance: wait for it to end.
            while (flush.state.value.running) delay(200)
        }
        val steps = ownSteps(since)
        steps.forEach { written += RecordType.STEPS to it.meta.clientRecordId }
        assertTrue("the demo walks: steps were counted", steps.all { it.count > 0 })
        assertTrue("each record over its own window", steps.all { it.start.isBefore(it.end) })

        val distances = app.container.healthConnect.readOwn(RecordType.DISTANCE, since, Instant.now().plusSeconds(60)).map { it as Distance }
            .filter { it.meta.clientRecordId.startsWith("oc:dist:") }
        distances.forEach { written += RecordType.DISTANCE to it.meta.clientRecordId }
        assertEquals("a distance for each steps record, over its window", steps.map { it.start }.toSet(), distances.map { it.start }.toSet())
    }
}
