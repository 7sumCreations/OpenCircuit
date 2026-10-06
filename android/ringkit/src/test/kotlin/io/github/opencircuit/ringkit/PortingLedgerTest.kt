package io.github.opencircuit.ringkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guard for `PORTING.md`'s deviations ledger: the "Index of every row" list, the table under
 * "The rows" and the index's own "N rows." count must describe the same rows, numbered
 * D-1 to D-N with no gap, no repeat and in the same order. Every epic appends to both lists and
 * bumps the count by hand, and parallel branches merge into the same lines, so a row added to
 * one list only (it has happened once) is otherwise found by whoever next reads the file.
 *
 * [PortingLedger.check] is the rule; the file test runs it on the real ledger and the other
 * tests prove each part of the rule can fail.
 */
class PortingLedgerTest {

    @Test
    fun indexTableAndCountAgree() {
        val rootPath = assertNotNull(
            System.getProperty("opencircuit.androidRoot"),
            "system property opencircuit.androidRoot is not set — see ringkit/build.gradle.kts",
        )
        val ledger = File(rootPath, "PORTING.md")
        assertTrue(ledger.isFile, "PORTING.md not found at ${ledger.path}")
        val problems = PortingLedger.check(ledger.readText())
        assertTrue(problems.isEmpty(), "PORTING.md ledger disagrees with itself:\n" + problems.joinToString("\n"))
    }

    @Test
    fun aConsistentLedgerPasses() {
        assertEquals(emptyList(), PortingLedger.check(ledger(index = listOf(1, 2, 3), rows = listOf(1, 2, 3), count = 3)))
    }

    @Test
    fun aRowMissingFromTheIndexFails() {
        val problems = PortingLedger.check(ledger(index = listOf(1, 3), rows = listOf(1, 2, 3), count = 3))
        assertTrue(problems.any { "D-2" in it }, problems.toString())
    }

    @Test
    fun anIndexEntryWithNoRowFails() {
        val problems = PortingLedger.check(ledger(index = listOf(1, 2, 3), rows = listOf(1, 3), count = 3))
        assertTrue(problems.any { "D-2" in it }, problems.toString())
    }

    @Test
    fun aStaleCountFails() {
        val problems = PortingLedger.check(ledger(index = listOf(1, 2, 3), rows = listOf(1, 2, 3), count = 2))
        assertTrue(problems.any { "count" in it }, problems.toString())
    }

    @Test
    fun aGapOrRepeatInTheNumberingFails() {
        assertTrue(PortingLedger.check(ledger(index = listOf(1, 3), rows = listOf(1, 3), count = 2)).isNotEmpty())
        assertTrue(PortingLedger.check(ledger(index = listOf(1, 2, 2), rows = listOf(1, 2, 2), count = 3)).isNotEmpty())
    }

    @Test
    fun theSameRowsInADifferentOrderFail() {
        assertTrue(PortingLedger.check(ledger(index = listOf(2, 1), rows = listOf(1, 2), count = 2)).isNotEmpty())
    }

    @Test
    fun aMissingCountLineFails() {
        val text = ledger(index = listOf(1), rows = listOf(1), count = 1).replace("1 rows.", "")
        assertTrue(PortingLedger.check(text).any { "count" in it })
    }

    private fun ledger(index: List<Int>, rows: List<Int>, count: Int): String = buildString {
        appendLine("## Deviations & deferrals")
        appendLine()
        appendLine("### Index of every row")
        appendLine()
        appendLine("One line per row of the table below. $count rows.")
        appendLine()
        index.forEach { appendLine("- **D-$it** · DEVIATION: title $it · E1 (done)") }
        appendLine()
        appendLine("### The rows")
        appendLine()
        appendLine("| ID | What | Why | Where it goes |")
        appendLine("|---|---|---|---|")
        rows.forEach { appendLine("| D-$it | **title $it.** what | why | where |") }
    }
}

/** The consistency rule for `PORTING.md`'s deviations ledger (see [PortingLedgerTest]). */
object PortingLedger {
    private val indexEntry = Regex("""^- \*\*D-(\d+)\*\*""")
    private val tableRow = Regex("""^\| D-(\d+) \|""")
    private val countLine = Regex("""(\d+) rows\.\s*$""")

    /** Every disagreement found, one line each; empty when the ledger is consistent. */
    fun check(text: String): List<String> {
        val lines = text.lines()
        val indexStart = lines.indexOfFirst { it.trim() == "### Index of every row" }
        val rowsStart = lines.indexOfFirst { it.trim() == "### The rows" }
        if (indexStart < 0 || rowsStart < indexStart) return listOf("headings '### Index of every row' then '### The rows' not found")

        val indexLines = lines.subList(indexStart, rowsStart)
        val index = indexLines.mapNotNull { indexEntry.find(it)?.groupValues?.get(1)?.toInt() }
        val rows = lines.drop(rowsStart).mapNotNull { tableRow.find(it)?.groupValues?.get(1)?.toInt() }
        val count = indexLines.firstNotNullOfOrNull { countLine.find(it)?.groupValues?.get(1)?.toInt() }

        val problems = mutableListOf<String>()
        (rows.toSet() - index.toSet()).sorted().forEach { problems += "D-$it has a table row but no index entry" }
        (index.toSet() - rows.toSet()).sorted().forEach { problems += "D-$it has an index entry but no table row" }
        if (index.toSet() == rows.toSet() && index != rows) problems += "the index and the table list the rows in different orders"
        listOf("index" to index, "table" to rows).forEach { (name, ids) ->
            if (ids != (1..ids.size).toList()) problems += "the $name is not numbered D-1 to D-${ids.size} in order without gaps or repeats"
        }
        when {
            count == null -> problems += "the index has no 'N rows.' count line"
            count != rows.size -> problems += "the index count says $count rows, the table has ${rows.size}"
        }
        return problems
    }
}
