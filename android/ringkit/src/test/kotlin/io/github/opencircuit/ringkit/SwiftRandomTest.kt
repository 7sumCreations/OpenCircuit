package io.github.opencircuit.ringkit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Checks `SwiftRandom.kt` against the Swift standard library's own draws: the engine generator
 * (`tools/sleep-differential`, `regenerate.sh engine`) wrote `engine-differential/random.txt` on the
 * pinned toolchain, and every value here comes from that file, never from this code's output.
 *
 * Per seed (three of the upstream headache-evaluation tests' seeds), each line starts a fresh
 * generator and draws one call shape 24 times, then records the generator's state, so a draw that
 * consumed a different number of words shows even when its value happens to agree. The integer and
 * uniform shapes must match exactly. The `gaussian` draws go through `log` and `cos`, which are
 * `StrictMath`'s here and the platform's in Swift; they must agree within 1e-12 and every value that is
 * not bit-identical is listed. The synthetic years built from those draws must match exactly, index by
 * index. `erfc` has no JDK counterpart; the test-side one must agree with Foundation's within the
 * tolerance stated below.
 */
class SwiftRandomTest {

    private class SeedBlock(val seed: ULong, val lines: Map<String, List<String>>)

    private fun d(x: Double): String = "d" + java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(x)).padStart(16, '0')
    private fun toDouble(t: String): Double {
        require(t.length == 17 && t[0] == 'd') { "bad double token $t" }
        return java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(t.substring(1), 16))
    }
    private fun u64(x: ULong): String = x.toString(16).padStart(16, '0')

    private fun golden(): List<String> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("engine-differential/random.txt")) { "missing test resource random.txt" }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { !it.startsWith("#") }
    }

    private fun seedBlocks(): List<SeedBlock> {
        val out = mutableListOf<SeedBlock>()
        var seed: ULong? = null
        var lines = LinkedHashMap<String, List<String>>()
        for (line in golden().filter { !it.startsWith("erfc") }) {
            val t = line.split(' ')
            if (t[0] == "seed") {
                seed?.let { out += SeedBlock(it, lines) }
                seed = t[1].toULong(16)
                lines = LinkedHashMap()
            } else {
                check(seed != null && t[0] !in lines) { "unexpected line: ${line.take(60)}" }
                lines[t[0]] = t
            }
        }
        seed?.let { out += SeedBlock(it, lines) }
        return out
    }

    /** `<shape> <state after> <24 values>` from a fresh generator, rendered as the generator renders it. */
    private fun line(shape: String, seed: ULong, draw: (SplitMix64) -> String): List<String> {
        val rng = SplitMix64(seed)
        val values = List(24) { draw(rng) }
        return listOf(shape, u64(rng.state)) + values
    }

    private val exactShapes: Map<String, (SplitMix64) -> String> = mapOf(
        "next" to { r -> u64(r.next()) },
        "unit" to { r -> d(r.double(0.0, 1.0)) },
        "u1" to { r -> d(r.double(1e-12, 1.0)) },
        "wide-double" to { r -> d(r.double(-1e300, 1e300)) },
        "narrow-double" to { r -> d(r.double(1.0, Math.nextUp(1.0))) },
        "int-1-12" to { r -> r.int(1, 12).toString() },
        "int-0-4" to { r -> r.int(0, 4).toString() },
        "int-0-360" to { r -> r.intBelow(0, 360).toString() },
        "int-wide" to { r -> r.int(Long.MIN_VALUE, Long.MAX_VALUE / 2).toString() },
        "int-full" to { r -> r.int(Long.MIN_VALUE, Long.MAX_VALUE).toString() },
    )

    /** The d' each seed's year is drawn at (the upstream test that uses that seed). */
    private val yearDPrime = mapOf(0x5EED1234uL to 0.0, 0x12345678uL to 1.40, 0xFACE0001uL to 0.55)

    @Test
    fun everyIntegerAndUniformDrawMatchesSwiftExactly() {
        val blocks = seedBlocks()
        assertEquals(yearDPrime.keys, blocks.map { it.seed }.toSet(), "three seeds, the upstream tests' own")
        for (b in blocks) {
            for ((shape, draw) in exactShapes) {
                val expected = b.lines[shape] ?: error("seed ${u64(b.seed)}: no $shape line")
                assertEquals(expected, line(shape, b.seed, draw), "seed ${u64(b.seed)} $shape")
            }
            // The forward Fisher–Yates over 360 positions (the shape of a synthetic year's labels).
            val rng = SplitMix64(b.seed)
            val xs = MutableList(360) { it }
            rng.shuffle(xs)
            assertEquals(b.lines.getValue("shuffle-360"), listOf("shuffle-360", u64(rng.state)) + xs.map { it.toString() }, "seed ${u64(b.seed)} shuffle")
        }
    }

    @Test
    fun theGoldenReachesTheRejectedAndRedrawnBranches() {
        // A draw that is rejected or redrawn consumes more than one word, so the state after 24 draws is
        // not the seed advanced 24 times. Every seed reaches both in its wide-integer and one-ulp-double
        // lines, and the fresh-generator lines that draw one word each do not.
        for (b in seedBlocks()) {
            val plain = SplitMix64(b.seed).also { r -> repeat(24) { r.next() } }.state
            assertEquals(u64(plain), b.lines.getValue("int-1-12")[1], "seed ${u64(b.seed)}: a small bound never rejects")
            assertNotEquals(u64(plain), b.lines.getValue("int-wide")[1], "seed ${u64(b.seed)}: the wide bound rejects some draws")
            assertNotEquals(u64(plain), b.lines.getValue("narrow-double")[1], "seed ${u64(b.seed)}: the one-ulp range redraws some")
        }
    }

    @Test
    fun gaussianDrawsAndSyntheticYearsMatchSwift() {
        val notBitIdentical = mutableListOf<String>()
        for (b in seedBlocks()) {
            val expected = b.lines.getValue("gauss")
            val rng = SplitMix64(b.seed)
            val actual = List(24) { gaussian(rng) }
            assertEquals(expected[1], u64(rng.state), "seed ${u64(b.seed)}: gaussian draws consume the same words")
            for ((k, a) in actual.withIndex()) {
                val e = toDouble(expected[k + 2])
                if (d(a) != expected[k + 2]) {
                    assertTrue(abs(a - e) <= 1e-12, "seed ${u64(b.seed)} gaussian $k: swift $e kotlin $a")
                    notBitIdentical += "seed ${u64(b.seed)} gaussian $k: swift $e kotlin $a |d| ${abs(a - e)}"
                }
            }

            // The synthetic year built from those draws: which days are positive, and every index.
            val year = b.lines.getValue("year")
            val dPrime = yearDPrime.getValue(b.seed)
            assertEquals(d(dPrime), year[1], "seed ${u64(b.seed)}: the year's d'")
            val yearRng = SplitMix64(b.seed)
            val draws = syntheticYearDraws(count = 360, positives = 48, dPrime = dPrime, rng = yearRng)
            val positives = draws.positive.indices.filter { draws.positive[it] }.map { it.toString() }
            assertEquals(year, listOf("year", d(dPrime), u64(yearRng.state), "positive") + positives + "index" + draws.indices.map { it.toString() }, "seed ${u64(b.seed)} year")
            assertEquals(48, positives.size)
        }
        println("swift random: ${notBitIdentical.size} gaussian draws not bit-identical (platform log/cos vs StrictMath), tolerated within 1e-12")
        notBitIdentical.forEach { println("  $it") }
    }

    /**
     * Tolerance measured on the golden's 107 arguments (−30 … 27.25 and the upstream test's own): the
     * largest relative error is 2.5e-13 (at x = 2.25, where the series' 1 − erf cancels), so the bound
     * is 5e-13 relative, and 1e-300 absolute for the values that underflow to 0. The upstream test
     * needs the normal approximation only to the 1e-3 its assertion compares at.
     */
    @Test
    fun erfcAgreesWithFoundationWithinItsStatedTolerance() {
        val pairs = golden().filter { it.startsWith("erfc ") }.flatMap { it.split(' ').drop(1).chunked(2) }
            .map { (x, e) -> toDouble(x) to toDouble(e) }
        assertTrue(pairs.size >= 100, "found only ${pairs.size} erfc values")
        var worst = 0.0
        var worstAt = 0.0
        for ((x, e) in pairs) {
            val a = erfc(x)
            val rel = if (e == 0.0) abs(a) else abs(a - e) / abs(e)
            if (rel > worst) {
                worst = rel
                worstAt = x
            }
            assertTrue(if (e == 0.0) abs(a) <= 1e-300 else rel <= 5e-13, "erfc($x): foundation $e kotlin $a (relative error $rel)")
        }
        println("erfc: ${pairs.size} values, largest relative error $worst at x = $worstAt")
    }
}
