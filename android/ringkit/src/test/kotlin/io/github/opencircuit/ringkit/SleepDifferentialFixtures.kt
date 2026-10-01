package io.github.opencircuit.ringkit

// Test-only reader for the sleep differential files in src/test/resources/sleep-differential/,
// written by tools/sleep-differential (upstream's Swift pipeline). The one place that knows their
// format, which is documented at the top of the generator's main.swift. Inputs are rebuilt on the
// RAW byte path (`BulkRecord.of` on the recorded hex), never through production builders.

import java.time.Instant

/** One night's recorded inputs: its records in archive order, its skin-temperature samples and its `0x48` frames in arrival order. */
internal class DifferentialNight(
    val id: String,
    val shape: String,
    val records: List<BulkRecord>,
    val temps: List<TemperatureSample>,
    val frames: List<ByteArray> = emptyList(),
)

internal object SleepDifferentialFixtures {

    /** A resource's lines without its `#` header comments. */
    fun lines(name: String): List<String> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("sleep-differential/$name")) { "missing test resource $name" }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { !it.startsWith("#") }
    }

    /** IEEE-754 bit pattern (16 hex digits) → double, and back. */
    fun bitsToDouble(hex: String): Double = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(hex, 16))
    fun bits(d: Double): String = java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(d)).padStart(16, '0')

    /** Every night in `inputs.txt`, in file order. */
    fun inputs(): List<DifferentialNight> {
        val lines = lines("inputs.txt")
        val out = mutableListOf<DifferentialNight>()
        var i = 0
        while (i < lines.size) {
            val head = lines[i].split(' ')
            check(head.size == 3 && head[0] == "night") { "bad night header at input line ${i + 1}: ${lines[i]}" }
            val recs = mutableListOf<BulkRecord>()
            val temps = mutableListOf<TemperatureSample>()
            val frames = mutableListOf<ByteArray>()
            i++
            while (lines[i] != "end") {
                val f = lines[i].split(' ')
                when (f[0]) {
                    "r" -> recs += checkNotNull(BulkRecord.of(hex(f[1]))) { "bad record: ${lines[i]}" }
                    "t" -> temps += TemperatureSample(Instant.ofEpochSecond(f[1].toLong()), bitsToDouble(f[2]))
                    "w" -> frames += hex(f[1]).also { check(it.size == OSAWaveform.FRAME_LENGTH) { "bad 0x48 frame: ${lines[i]}" } }
                    else -> error("bad input line ${i + 1}: ${lines[i]}")
                }
                i++
            }
            out += DifferentialNight(head[1], head[2], recs, temps, frames)
            i++
        }
        return out
    }

    /** Every night's golden lines from `goldens.txt` (without its `night` / `end` frame), in file order. */
    fun goldens(): LinkedHashMap<String, List<String>> {
        val lines = lines("goldens.txt")
        val out = LinkedHashMap<String, List<String>>()
        var i = 0
        while (i < lines.size) {
            val head = lines[i].split(' ')
            check(head.size == 2 && head[0] == "night") { "bad golden header at line ${i + 1}: ${lines[i]}" }
            val body = mutableListOf<String>()
            i++
            while (lines[i] != "end") body += lines[i++]
            out[head[1]] = body
            i++
        }
        return out
    }

    /** `coverage.txt` as branch → number of nights. */
    fun coverage(): Map<String, Int> = lines("coverage.txt").associate { line ->
        val f = line.split(' ')
        check(f.size == 3 && f[0] == "branch") { "bad coverage line: $line" }
        f[1] to f[2].toInt()
    }
}
