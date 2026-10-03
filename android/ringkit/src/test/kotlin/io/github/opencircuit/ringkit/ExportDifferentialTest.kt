package io.github.opencircuit.ringkit

import io.github.opencircuit.ringkit.ExportEngine.SampleRow
import org.junit.jupiter.api.Timeout
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-for-byte differential of the export writers against upstream's own Swift code:
 * `tools/sleep-differential` (its `ExportDifferential` generator, `regenerate.sh export`) ran the
 * pinned `ExportEngine.samplesCSV` and `ExportEngine.toJSON` over synthetic cases — the measured
 * number texts, hostile kind strings (every CSV quoting trigger and JSON escape class), no rows,
 * millisecond ties before and after 1970, a seeded sweep of doubles across every exponent and the
 * format boundaries, and non-finite values — in four time zones, and wrote each output verbatim to
 * `src/test/resources/export-differential/`. This test rebuilds the same rows, runs the Kotlin
 * writers and compares BYTES. It also sorts the export's key vocabulary and seeded random
 * printable-ASCII keys with the port's comparator and compares the order `JSONSerialization` wrote.
 * The format is documented at the top of the generator's `main.swift`.
 *
 * Comparison rule: every output is compared whole and exactly. The goldens come only from the Swift
 * generator, never from this code's output. Where the port deliberately differs from upstream on a
 * case, the output that moves is named in [DELIBERATE_DIVERGENCES] (case + output, with the
 * `PORTING.md` D-row) and reported; no other output may differ, and a listed output that stops
 * differing fails as stale. A "csvonly" case holds a value upstream's `toJSON` cannot serialize (it
 * raises and the app dies): it has no JSON golden, and the port's `toJSON` must return null there.
 */
class ExportDifferentialTest {

    private class ECase(val id: String, val zone: ZoneId, val now: Instant, val json: Boolean, val rows: List<SampleRow>)

    private data class Divergence(val case: String, val output: String)

    private class Report {
        val mismatches = mutableListOf<String>()
        val allowed = mutableMapOf<String, MutableList<Divergence>>()
        var compared = 0
        var bytes = 0L
    }

    /**
     * Each deliberate byte difference from upstream, keyed by the improvement (with its `PORTING.md`
     * D-row). None so far: on every case the port writes upstream's bytes. A listed output that no
     * longer differs fails as stale.
     */
    private val DELIBERATE_DIVERGENCES: Map<String, Set<Divergence>> = emptyMap()

    // --- reading the generator's files ---

    private fun resource(name: String): ByteArray? = javaClass.classLoader.getResourceAsStream("export-differential/$name")?.use { it.readBytes() }

    private fun text(name: String): List<String> =
        String(checkNotNull(resource(name)) { "missing test resource $name" }, Charsets.UTF_8).lines().filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun hexString(t: String): String {
        require(t.startsWith("x") && t.length % 2 == 1) { "bad string token $t" }
        val bytes = ByteArray((t.length - 1) / 2) { k -> Integer.parseInt(t.substring(1 + 2 * k, 3 + 2 * k), 16).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    private fun bitsOf(t: String): Long {
        require(t.length == 17 && t[0] == 'd') { "bad double token $t" }
        return java.lang.Long.parseUnsignedLong(t.substring(1), 16)
    }

    private fun double(t: String): Double = java.lang.Double.longBitsToDouble(bitsOf(t))
    private fun date(t: String): Instant = FoundationDate.referenceBits(bitsOf(t))

    private fun inputs(): List<ECase> {
        val all = text("inputs.txt")
        val out = mutableListOf<ECase>()
        var i = 0
        while (i < all.size) {
            val head = all[i].split(' ')
            check(head.size == 5 && head[0] == "case" && head[4] in setOf("json", "csvonly")) { "bad case header: ${all[i]}" }
            val rows = mutableListOf<SampleRow>()
            i++
            while (all[i] != "end") {
                val f = all[i].split(' ')
                check(f.size == 5 && f[0] == "s") { "bad sample line: ${all[i]}" }
                rows += SampleRow(hexString(f[1]), date(f[2]), date(f[3]), double(f[4]))
                i++
            }
            out += ECase(head[1], ZoneId.of(head[2]), date(head[3]), head[4] == "json", rows)
            i++
        }
        return out
    }

    // --- comparing ---

    private fun describe(expected: ByteArray, actual: ByteArray?): String {
        if (actual == null) return "the port wrote nothing (null)"
        var k = 0
        while (k < expected.size && k < actual.size && expected[k] == actual[k]) k++
        fun around(b: ByteArray) = String(b.copyOfRange(maxOf(0, k - 40), minOf(b.size, k + 40)), Charsets.UTF_8)
            .replace("\n", "\\n").replace("\r", "\\r")
        return "first difference at byte $k of ${expected.size} (port: ${actual.size} bytes)\n      upstream: …${around(expected)}…\n      port:     …${around(actual)}…"
    }

    private fun compare(case: String, output: String, expected: ByteArray, actual: ByteArray?, divergences: Map<String, Set<Divergence>>, report: Report) {
        report.compared++
        report.bytes += expected.size
        if (actual != null && expected.contentEquals(actual)) return
        val improvement = divergences.entries.firstOrNull { Divergence(case, output) in it.value }?.key
        if (improvement != null) {
            report.allowed.getOrPut(improvement) { mutableListOf() } += Divergence(case, output)
        } else {
            report.mismatches += "$case / $output: ${describe(expected, actual)}"
        }
    }

    private fun staleEntries(report: Report, divergences: Map<String, Set<Divergence>>): List<String> =
        divergences.flatMap { (improvement, entries) -> entries.filter { it !in report.allowed[improvement].orEmpty() }.map { "$improvement: $it" } }

    private fun runAll(divergences: Map<String, Set<Divergence>>): Report {
        val report = Report()
        for (c in inputs()) {
            val csv = checkNotNull(resource("${c.id}.samples.csv")) { "missing golden ${c.id}.samples.csv" }
            compare(c.id, "samples.csv", csv, ExportEngine.samplesCSV(c.rows).toByteArray(Charsets.UTF_8), divergences, report)
            if (c.json) {
                val json = checkNotNull(resource("${c.id}.json")) { "missing golden ${c.id}.json" }
                compare(c.id, "json", json, ExportEngine.toJSON(samples = c.rows, sleep = emptyList(), daily = emptyList(), zone = c.zone, now = c.now)?.toByteArray(Charsets.UTF_8), divergences, report)
            }
        }
        return report
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `every writer's output equals upstream's bytes for every case`() {
        val report = runAll(DELIBERATE_DIVERGENCES)
        println("export differential: ${inputs().size} cases, ${report.compared} outputs, ${report.bytes} bytes compared, ${report.mismatches.size} mismatches")
        for ((improvement, seen) in report.allowed) println("  deliberate divergence '$improvement': ${seen.joinToString()}")
        assertTrue(report.compared >= 11, "expected at least 11 compared outputs, got ${report.compared} — FIX THE READER")
        assertTrue(report.mismatches.isEmpty(), "${report.mismatches.size} output(s) differ from upstream:\n" + report.mismatches.joinToString("\n"))
        val stale = staleEntries(report, DELIBERATE_DIVERGENCES)
        assertTrue(stale.isEmpty(), "listed deliberate divergence(s) no longer diverge — remove them:\n" + stale.joinToString("\n"))
    }

    @Test
    fun `where upstream's toJSON cannot serialize a value the port returns null and the CSV still writes`() {
        val csvOnly = inputs().filter { !it.json }
        assertTrue(csvOnly.isNotEmpty(), "the generator wrote no non-finite case")
        for (c in csvOnly) {
            assertTrue(c.rows.any { !it.value.isFinite() }, "${c.id} is marked csvonly but every value is finite")
            assertNull(ExportEngine.toJSON(samples = c.rows, sleep = emptyList(), daily = emptyList(), zone = c.zone, now = c.now), c.id)
            assertNull(resource("${c.id}.json"), "${c.id} has a JSON golden although upstream cannot write one")
        }
    }

    @Test
    fun `JSON keys sort in the order JSONSerialization wrote them`() {
        val lines = text("keyorder.txt")
        val v = lines.indexOf("vocabulary")
        val r = lines.indexOf("random")
        assertTrue(v == 0 && r > v, "keyorder.txt sections not found")
        val vocabulary = lines.subList(v + 1, r).map(::hexString)
        val random = lines.subList(r + 1, lines.size).map(::hexString)
        assertEquals(161, vocabulary.size)
        assertEquals(2_000, random.size)
        for ((name, foundation) in listOf("vocabulary" to vocabulary, "random" to random)) {
            for (seed in 1L..3L) {
                assertEquals(foundation, foundation.shuffled(java.util.Random(seed)).sortedWith(FoundationText.JSON_KEY_ORDER), "$name, shuffle $seed")
            }
        }
    }

    @Test
    fun `the golden directory holds exactly the files the cases name`() {
        val root = assertNotNull(System.getProperty("opencircuit.androidRoot"), "system property opencircuit.androidRoot is not set — see ringkit/build.gradle.kts")
        val dir = File(root, "ringkit/src/test/resources/export-differential")
        val present = assertNotNull(dir.list(), "no directory $dir").toSet()
        val expected = setOf("inputs.txt", "keyorder.txt") + inputs().flatMap { c -> listOfNotNull("${c.id}.samples.csv", if (c.json) "${c.id}.json" else null) }
        assertEquals(expected, present, "stale or missing goldens — regenerate with regenerate.sh export")
    }

    @Test
    fun `every JSON golden reads back as an export with the rows it was written from`() {
        for (c in inputs().filter { it.json }) {
            val root = ExportJsonReader.root(String(checkNotNull(resource("${c.id}.json")), Charsets.UTF_8))
            assertEquals(3L, root.long("schemaVersion"), c.id)
            val samples = assertNotNull(root.array("samples"), c.id)
            assertEquals(c.rows.map { it.kind }, samples.map { it.asObject()?.string("kind") }, c.id)
            assertEquals(c.rows.size, samples.size, c.id)
            for ((row, s) in c.rows.zip(samples)) {
                val back = assertNotNull(s.asObject()?.double("value"), "${c.id} ${row.kind}")
                assertTrue(back == row.value, "${c.id}: ${row.value} read back as $back")
            }
        }
    }

    @Test
    fun `the comparison fails on a one-byte difference, on a missing output and on a stale entry`() {
        val report = Report()
        val golden = "kind,start\nhr,1".toByteArray()
        compare("c", "samples.csv", golden, "kind,start\nhr,2".toByteArray(), emptyMap(), report)
        compare("c", "json", golden, null, emptyMap(), report)
        compare("d", "json", golden, golden.copyOf(), emptyMap(), report)
        assertEquals(2, report.mismatches.size, report.mismatches.joinToString("\n"))
        assertTrue(report.mismatches[0].contains("first difference at byte 14"), report.mismatches[0])
        val listed = mapOf("D-0 example" to setOf(Divergence("c", "samples.csv"), Divergence("d", "json")))
        val withList = Report()
        compare("c", "samples.csv", golden, "kind,start\nhr,2".toByteArray(), listed, withList)
        compare("d", "json", golden, golden.copyOf(), listed, withList)
        assertTrue(withList.mismatches.isEmpty())
        assertEquals(listOf("D-0 example: ${Divergence("d", "json")}"), staleEntries(withList, listed))
    }
}
