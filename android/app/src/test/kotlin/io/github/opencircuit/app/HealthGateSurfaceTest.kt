package io.github.opencircuit.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "Send to Health Connect" switch is airtight only if nothing reaches Health Connect around it.
 * The app object graph and the activity never run on the JVM, so their wiring is read from the
 * sources: the one Health Connect client is built once and handed only to the writer and to the
 * access reader; the writer is called only from inside the flush; both the flush and the access
 * reader are built asking the switch; and the activity's resume and after-screen reads go through
 * the access reader (whose every read asks the switch — `HealthSwitchGateTest`).
 */
class HealthGateSurfaceTest {

    private val main = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        .associate { it.invariantSeparatorsPath.substringAfter("io/github/opencircuit/app/") to it.readText() }

    private fun count(text: String, regex: Regex) = regex.findAll(text).count()

    @Test
    fun theOneClientIsBuiltOnceAndHeldOnlyByTheWriterAndTheAccessReader() {
        assertEquals(setOf("AppContainer.kt"), main.filterValues { "JetpackHealthConnect(" in it }.keys)
        assertEquals(setOf("AppContainer.kt"), main.filterValues { "HealthWriter(" in it }.keys)

        val container = main.getValue("AppContainer.kt")
        val uses = Regex("""\bhealthConnect\b""")
        assertEquals(setOf("AppContainer.kt"), main.filterValues { uses.containsMatchIn(it) }.keys, "no other file names the client")
        assertEquals(3, count(container, uses), "declared, handed to the writer, handed to the access reader — nothing else")
        assertTrue("port = healthConnect," in container)
        assertTrue("HealthAccess(healthConnect," in container)
    }

    @Test
    fun theWriterIsCalledOnlyFromInsideTheFlush() {
        val container = main.getValue("AppContainer.kt")
        val calls = Regex("""\bhealthWriter\.""").findAll(container).map { it.range.first }.toList()
        assertEquals(1, calls.size, "one call of the writer")
        val line = container.lineSequence().single { "healthWriter." in it }
        assertTrue(line.trimStart().startsWith("val healthFlush: HealthFlush = HealthFlush({"), "the call is the flush's own work: $line")
    }

    @Test
    fun theFlushAndTheAccessReaderAreBuiltAskingTheSwitch() {
        // Each construction, from `= HealthFlush(` / `= HealthAccess(` to the end of its statement (the blank line after it).
        val built = main.values.flatMap { text ->
            Regex("""=\s*(HealthFlush|HealthAccess)\(""").findAll(text).map { text.substring(it.range.first).substringBefore("\n\n") }
        }
        assertEquals(2, built.size, "one flush and one access reader in the app: $built")
        built.forEach { assertTrue("enabled = healthSwitch::isOn" in it, "built without the switch: $it") }
    }

    @Test
    fun theActivitysResumeAndAfterScreenReadsGoThroughTheAccessReader() {
        val activity = main.getValue("MainActivity.kt")
        val resume = activity.substringAfter("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)").substringBefore("\n                    }\n")
        assertTrue("container.healthAccess.refresh()" in resume, "the resume read is the gated one")
        // Nothing in the activity talks to Health Connect except through the flush, the access reader and the switch.
        val reached = Regex("""container\.(health\w+)""").findAll(activity).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet(), reached - setOf("healthAccess", "healthFlush", "healthSwitch", "healthNotice"))
    }
}
