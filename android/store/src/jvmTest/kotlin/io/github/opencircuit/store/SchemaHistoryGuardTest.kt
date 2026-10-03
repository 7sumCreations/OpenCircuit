package io.github.opencircuit.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The schema history cannot drift away from what has shipped:
 * - every committed schema version (`store/schemas/<database>/N.json`) has its own test in
 *   [StoreMigrationTest], named `versionN…`, so no shipped version is ever left without a proven
 *   path to the current one;
 * - every released schema file is pinned by its SHA-256 in `store/schemas/RELEASED.sha256`. The
 *   build rewrites the current version's file on every compile, so an entity edited without a
 *   version bump shows up here as a changed pinned file;
 * - the database the code opens is at the highest committed version.
 *
 * Every version below the highest must be pinned (a newer one exists, so it has shipped or was
 * meant to); the highest may be unpinned only while it is being developed.
 */
class SchemaHistoryGuardTest {

    private val database = "io.github.opencircuit.store.StoreDatabase"

    private fun storeDir(): File =
        File(assertNotNull(System.getProperty("opencircuit.storeDir"), "opencircuit.storeDir is not set — see store/build.gradle.kts"))

    private fun migrationTestSource(): String =
        File(storeDir(), "src/jvmTest/kotlin/io/github/opencircuit/store/StoreMigrationTest.kt").readText()

    private fun liveVersion(): Int = runBlocking { withInMemoryStore { db -> db.queryRaw("PRAGMA user_version").single().toInt() } }

    private fun versionTwoOf(one: File): String = one.readText().replaceFirst(Regex("\"version\"\\s*:\\s*1\\b"), "\"version\": 2")

    @Test
    fun theCommittedSchemaHistoryIsTestedPinnedAndCurrent() {
        val schemas = File(storeDir(), "schemas")
        val history = SchemaHistory.read(schemas, database)
        // Anchors: the check reached the files it guards.
        assertTrue(1 in history.versions, "version 1 is not committed under $schemas")
        assertTrue("$database/1.json" in history.pinned, "version 1 is not pinned in RELEASED.sha256")
        assertEquals(emptyList(), SchemaHistory.problems(schemas, database, migrationTestSource(), liveVersion()))
    }

    @Test
    fun aCommittedVersionWithNoMigrationTestFails() = withSchemaCopy { schemas ->
        File(schemas, "$database/2.json").writeText(versionTwoOf(File(schemas, "$database/1.json")))
        assertEquals(
            listOf("version 2 has no test in the migration test class (a @Test fun version2…)"),
            SchemaHistory.problems(schemas, database, migrationTestSource(), liveVersion = 2),
        )
        // A test named only in a comment or a string does not count; a declared one does.
        val mentioned = migrationTestSource() +
            "\n// @Test fun version2Reopens()\n/** @Test fun version2Reopens() */\nval s = \"@Test fun version2X()\"\n"
        assertEquals(1, SchemaHistory.problems(schemas, database, mentioned, liveVersion = 2).size)
        val declared = migrationTestSource() + "\n@Test\nfun version2ReopensThroughTheFactory() = Unit\n"
        assertEquals(emptyList(), SchemaHistory.problems(schemas, database, declared, liveVersion = 2))
    }

    @Test
    fun oneChangedByteInAReleasedSchemaFails() = withSchemaCopy { schemas ->
        val one = File(schemas, "$database/1.json")
        val bytes = one.readBytes()
        val at = bytes.indexOf(','.code.toByte()).also { assertTrue(it > 0) }
        bytes[at + 1] = if (bytes[at + 1] == '\n'.code.toByte()) ' '.code.toByte() else '\n'.code.toByte()
        one.writeBytes(bytes)
        val problems = SchemaHistory.problems(schemas, database, migrationTestSource(), liveVersion())
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().startsWith("released schema $database/1.json changed"), problems.single())
    }

    @Test
    fun aLiveVersionThatIsNotTheHighestCommittedFails() = withSchemaCopy { schemas ->
        assertEquals(
            listOf("the database opens at version 2 but the highest committed schema is 1"),
            SchemaHistory.problems(schemas, database, migrationTestSource(), liveVersion = 2),
        )
    }

    @Test
    fun anUnpinnedOlderVersionAMissingPinnedFileAndABadPinLineFail() = withSchemaCopy { schemas ->
        val pins = File(schemas, "RELEASED.sha256")
        val one = File(schemas, "$database/1.json")
        File(schemas, "$database/2.json").writeText(versionTwoOf(one))
        val source = migrationTestSource() + "\n@Test\nfun version2ReopensThroughTheFactory() = Unit\n"
        // Version 2 is the newest and may stay unpinned; version 1 below it must be pinned.
        pins.writeText("# nothing pinned\n")
        assertEquals(
            listOf("version 1 is older than the newest and is not pinned in RELEASED.sha256"),
            SchemaHistory.problems(schemas, database, source, liveVersion = 2),
        )
        // A pin whose file is gone, and a line that is not a pin.
        pins.writeText("${"0".repeat(64)}  $database/3.json\nnot a pin line\n${SchemaHistory.sha256(one)}  $database/1.json\n")
        assertEquals(
            listOf("RELEASED.sha256 line 2 is not '<sha256>  <database>/<N>.json': not a pin line", "released schema $database/3.json is missing"),
            SchemaHistory.problems(schemas, database, source, liveVersion = 2).sorted(),
        )
        // No pin file at all.
        pins.delete()
        assertTrue("RELEASED.sha256 is missing" in SchemaHistory.problems(schemas, database, source, liveVersion = 2))
    }

    /** Runs [block] on a temp copy of `store/schemas`; the committed files are never touched. */
    private fun withSchemaCopy(block: (File) -> Unit) {
        val copy = createTempDirectory("schema-history").toFile()
        try {
            File(storeDir(), "schemas").copyRecursively(copy)
            block(copy)
        } finally {
            copy.deleteRecursively()
        }
    }
}

/** Reads and checks a Room schema directory: `<schemas>/<database>/N.json` plus `<schemas>/RELEASED.sha256`. */
internal object SchemaHistory {

    class History(val versions: Set<Int>, val pinned: Map<String, String>, val pinProblems: List<String>)

    private val schemaFile = Regex("""(\d+)\.json""")
    private val pinLine = Regex("""([0-9a-f]{64}) {2}(\S+/\d+\.json)""")
    private val versionTest = Regex("""@Test\s+fun\s+version(\d+)(?=[A-Z_(])""")

    fun read(schemas: File, database: String): History {
        val versions = File(schemas, database).listFiles().orEmpty()
            .mapNotNull { schemaFile.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }.toSet()
        val pinFile = File(schemas, "RELEASED.sha256")
        val pinned = linkedMapOf<String, String>()
        val pinProblems = mutableListOf<String>()
        if (!pinFile.isFile) {
            pinProblems += "RELEASED.sha256 is missing"
        } else {
            pinFile.readLines().forEachIndexed { i, line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                val m = pinLine.matchEntire(line.trimEnd())
                if (m == null) {
                    pinProblems += "RELEASED.sha256 line ${i + 1} is not '<sha256>  <database>/<N>.json': $line"
                } else {
                    pinned[m.groupValues[2]] = m.groupValues[1]
                }
            }
        }
        return History(versions, pinned, pinProblems)
    }

    fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Every way the history in [schemas] disagrees with the migration tests in [migrationTestSource] or the [liveVersion]. */
    fun problems(schemas: File, database: String, migrationTestSource: String, liveVersion: Int): List<String> {
        val history = read(schemas, database)
        if (history.versions.isEmpty()) return listOf("no schema committed under ${File(schemas, database)}")
        val newest = history.versions.max()
        val tested = versionTest.findAll(KotlinSourceText.declarationsOnly(migrationTestSource)).map { it.groupValues[1].toInt() }.toSet()
        val problems = history.pinProblems.toMutableList()
        for (version in history.versions.sorted()) {
            val file = File(schemas, "$database/$version.json")
            val declared = Json.parseToJsonElement(file.readText()).jsonObject["database"]?.jsonObject?.get("version")?.jsonPrimitive?.intOrNull
            if (declared != version) problems += "$database/$version.json declares version $declared"
            if (version !in tested) problems += "version $version has no test in the migration test class (a @Test fun version$version…)"
            if (version < newest && "$database/$version.json" !in history.pinned) {
                problems += "version $version is older than the newest and is not pinned in RELEASED.sha256"
            }
        }
        for ((name, pin) in history.pinned) {
            val file = File(schemas, name)
            when {
                !file.isFile -> problems += "released schema $name is missing"
                sha256(file) != pin -> problems += "released schema $name changed (pinned $pin, now ${sha256(file)})"
            }
        }
        for (version in tested - history.versions) problems += "the migration test class tests version $version, which has no committed schema"
        if (liveVersion != newest) problems += "the database opens at version $liveVersion but the highest committed schema is $newest"
        return problems
    }
}
