package io.github.opencircuit.health

import io.github.opencircuit.ringkit.MetricKind
import io.github.opencircuit.ringkit.QuantitySample
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The platform range table ([HealthRanges]) stands between every record the writer builds and
 * Health Connect: a record breaking a rule is dropped and counted per type, never clamped and never
 * sent (one bad record would otherwise fail its whole call). Proven by behaviour on the paths whose
 * input can break a rule from the store, and by a scan of the writer's source that every insert
 * goes through the one checked path.
 */
class RangeTableWiredTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val t0 = Instant.parse("2026-10-17T02:00:00Z")
    private val hc = FakeHealthConnect({ now }).apply { grantAll() }
    private val samples = InMemoryHealthSamples()
    private fun writer() = HealthWriter(
        hc, samples, WriteGeneration(InMemoryWriteGenerations()), InMemoryHealthFailures(), { now }, { ZoneOffset.UTC }, { RingDevice("Gen 2") },
    )

    private fun at(min: Long) = t0.plusSeconds(min * 60)

    @Test
    fun outOfRangeScalarsAreDroppedAndCountedAndTheRestIsWritten() = runBlocking<Unit> {
        samples.add(
            QuantitySample(MetricKind.HRV_SDNN, at(0), value = 250.0), // RMSSD above 200 ms
            QuantitySample(MetricKind.HRV_SDNN, at(5), value = 42.0),
            QuantitySample(MetricKind.RESPIRATORY_RATE, at(0), value = 1_001.0), // above 1000 per minute
            QuantitySample(MetricKind.RESPIRATORY_RATE, at(5), value = 15.0),
        )

        val result = writer().flush()

        assertEquals(listOf(42.0), hc.records(RecordType.HEART_RATE_VARIABILITY_RMSSD).map { (it as HeartRateVariabilityRmssd).millis })
        assertEquals(listOf(15.0), hc.records(RecordType.RESPIRATORY_RATE).map { (it as RespiratoryRate).breathsPerMinute })
        assertEquals(1, result.rejected[RecordType.HEART_RATE_VARIABILITY_RMSSD])
        assertEquals(1, result.rejected[RecordType.RESPIRATORY_RATE])
        assertTrue(hc.calls.all { it.outcome == FakeHealthConnect.Call.Outcome.OK }, "nothing the platform would refuse was sent")
    }

    @Test
    fun anHourHoldingAnImpossibleHeartRateIsNeverSent() = runBlocking<Unit> {
        samples.add(QuantitySample(MetricKind.HEART_RATE, at(0), value = 301.0), QuantitySample(MetricKind.HEART_RATE, at(70), value = 64.0))

        val result = writer().flush()

        assertEquals(1, hc.records(RecordType.HEART_RATE).size, "the next hour is written")
        assertEquals(1, result.rejected[RecordType.HEART_RATE])
        assertTrue(hc.calls.all { it.outcome == FakeHealthConnect.Call.Outcome.OK })
    }

    @Test
    fun everyInsertGoesThroughTheOneCheckedPath() {
        assertEquals(emptyList(), WriterScan.problems(writerSource()))
    }

    @Test
    fun theScanFailsWhenAnInsertBypassesTheTable() {
        val source = writerSource()
        val unchecked = source.replace("val kept = items.filter { HealthRanges.violation(it.record, now) == null }", "val kept = items")
        assertEquals(listOf("writeItems drops what breaks the table before sending"), WriterScan.problems(unchecked))
        val direct = source.replace("private suspend fun writeNaps(granted: Set<HealthPermission>) {", "private suspend fun writeNaps(granted: Set<HealthPermission>) {\n port.insert(RecordType.SLEEP_SESSION, emptyList())")
        assertEquals(listOf("one port.insert, inside send"), WriterScan.problems(direct))
        val sideDoor = source.replace("private suspend fun writeNaps(granted: Set<HealthPermission>) {", "private suspend fun writeNaps(granted: Set<HealthPermission>) {\n send(RecordType.SLEEP_SESSION, emptyList())")
        assertEquals(listOf("send is called only by writeItems, isolate and the checked edit step"), WriterScan.problems(sideDoor))
    }

    private fun writerSource(): String {
        val root = File(assertNotNull(System.getProperty("opencircuit.androidRoot"), "opencircuit.androidRoot is not set"))
        return File(root, "health/src/commonMain/kotlin/io/github/opencircuit/health/HealthWriter.kt").readText()
    }

    /** The writer's insert paths, read from its source. */
    private object WriterScan {
        private val FUN = Regex("""\bfun (\w+)\(""")

        /** Each function's name and body text up to the next function. */
        private fun functions(source: String): List<Pair<String, String>> {
            val starts = FUN.findAll(source).toList()
            return starts.mapIndexed { i, m -> m.groupValues[1] to source.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: source.length) }
        }

        fun problems(source: String): List<String> {
            val out = mutableListOf<String>()
            val fns = functions(source)
            val inserts = fns.filter { "port.insert(" in it.second }.map { it.first }
            if (inserts != listOf("send") || Regex("""port\.insert\(""").findAll(source).count() != 1) out += "one port.insert, inside send"
            val callers = fns.filter { (name, body) -> name != "send" && Regex("""\bsend\(""").containsMatchIn(body) }.map { it.first }.toSet()
            if (callers != setOf("writeItems", "isolate", "writeQueuedEdits")) out += "send is called only by writeItems, isolate and the checked edit step"
            val writeItems = fns.first { it.first == "writeItems" }.second
            if (!writeItems.contains("HealthRanges.violation(it.record, now) == null") || writeItems.indexOf("HealthRanges") > writeItems.indexOf("send(")) {
                out += "writeItems drops what breaks the table before sending"
            }
            val edits = fns.first { it.first == "writeQueuedEdits" }.second
            if (!edits.contains("HealthRanges.violation(session, now)") || edits.indexOf("HealthRanges") > edits.indexOf("send(")) {
                out += "the edit step checks the table before sending"
            }
            return out
        }
    }
}
