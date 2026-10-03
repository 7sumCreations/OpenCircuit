package io.github.opencircuit.store

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencircuit.ringkit.BulkSleep
import io.github.opencircuit.ringkit.MetricKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

/**
 * The store on a real Android runtime: opened through the Android entry point with a `Context`,
 * at the app's database path, on the bundled SQLite — the same build, with the same compile
 * options, as the JVM tests run (`sqlite-compile-options.txt`, which a JVM test checks against
 * the JVM). Everything else about the store is tested on the JVM.
 *
 * Runs on an emulator with `:store:connectedAndroidDeviceTest`; never part of `./gradlew test`.
 * It touches only its own database file, named below, deleted before and after.
 */
@RunWith(AndroidJUnit4::class)
class StoreSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "store-smoke-test.db"
    private val now = Instant.parse("2026-10-03T00:00:00Z")

    // The real `0x4c` page of upstream's capture-to-store test, decoded from its raw bytes.
    private val realPage = "4c00260c22a16b55210a7d120a010101010100000402400400000c22a20155000300" +
        "120a010101010100003c00000d01200c22a297540001005f0a010101010100001101b00f" +
        "00440c22a32d6027077b120a010101010100402501c02235a00c22a3c351260577120b01" +
        "0101010108a01000000401300c22a459502d0378120a01010101010160200000040ff0cc"

    @Before
    fun removeLeftover() {
        context.deleteDatabase(name)
    }

    @After
    fun removeOwnDatabase() {
        context.deleteDatabase(name)
    }

    @Test
    fun anIngestCommitsToTheAppDatabaseFileAndSurvivesAReopen() = runBlocking {
        val samples = BulkSleep.samples(BulkSleep.recordsFromPage(hex(realPage)))
        val db = StoreFactory.open(context, name)
        val ingested = try {
            LocalStore(db).ingest(samples, now, ZoneOffset.UTC)
        } finally {
            db.close()
        }
        assertEquals(samples.size, ingested.size)
        assertTrue(context.getDatabasePath(name).exists())

        val reopened = StoreFactory.open(context, name)
        try {
            val store = LocalStore(reopened)
            val latestHR = samples.filter { it.kind == MetricKind.HEART_RATE }.maxOf { it.start }
            assertEquals(latestHR, store.loadCursor().last(MetricKind.HEART_RATE))
            assertEquals(samples.size, reopened.sampleDao().allSamples().size)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aFailureAfterTheSampleInsertsRollsBackOnTheDevice() = runBlocking {
        val samples = BulkSleep.samples(BulkSleep.recordsFromPage(hex(realPage)))
        val db = StoreFactory.open(context, name)
        try {
            val real = db.sampleDao()
            val failing = object : SampleDao by real {
                override suspend fun upsertCursors(cursors: List<StoredCursorEntity>) = error("injected failure")
            }
            val failure = runCatching { LocalStore(db, failing).ingest(samples, now, ZoneOffset.UTC) }

            assertTrue(failure.isFailure)
            assertEquals(0, real.allSamples().size)
            assertEquals(0, real.allCursors().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun theDeviceRunsTheBundledSqliteBuildWithTheJvmCompileOptions() = runBlocking {
        val stream = javaClass.classLoader?.getResourceAsStream("sqlite-compile-options.txt")
        assertNotNull("test resource sqlite-compile-options.txt is not packaged", stream)
        val expected = stream!!.bufferedReader().use { r -> r.readLines().filter { it.isNotBlank() } }
        assertEquals(52, expected.size)

        val db = StoreFactory.open(context, name)
        try {
            val (version, options) = db.useReaderConnection { connection ->
                val version = connection.usePrepared("SELECT sqlite_version()") { it.step(); it.getText(0) }
                val options = connection.usePrepared("PRAGMA compile_options") {
                    val out = mutableListOf<String>()
                    while (it.step()) out += it.getText(0)
                    out
                }
                version to options
            }
            assertEquals("3.50.1", version)
            assertEquals(expected, options)
        } finally {
            db.close()
        }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
