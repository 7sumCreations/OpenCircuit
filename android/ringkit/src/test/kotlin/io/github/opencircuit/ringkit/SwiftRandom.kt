package io.github.opencircuit.ringkit

// The Swift standard library's seeded random draws, reproduced bit for bit, for the ported headache
// evaluation tests. Upstream's HeadacheEvaluationTests build their synthetic years with a test-local
// SplitMix64 driving `Double.random(in:using:)`, `Int.random(in:using:)` and `Array.shuffle(using:)`,
// and some of its assertions are tied to exact seeds; a different draw sequence would break them, or
// make them pass for the wrong reason.
//
// The algorithms are the standard library's (Swift 6.3.2, the toolchain the goldens come from):
//  • `next(upperBound:)` — Lemire's nearly divisionless method: the high word of `random × bound`,
//    drawing again while the low word is below `2^64 mod bound`;
//  • `Int.random(in: a...b)` — `a + next(upperBound: b − a + 1)` in wrapping arithmetic, or one raw
//    word when the range is the whole of `Int`; `Int.random(in: a..<b)` the same without the + 1;
//  • `Double.random(in: a..<b)` — the low 53 bits of one word times 2^-53, scaled by `b − a` and
//    shifted by `a` (no fused multiply-add), drawn again when that rounds up to `b`;
//  • `shuffle(using:)` — the forward Fisher–Yates: for each position from the first, swap it with
//    a uniformly drawn one at or after it.
// `SwiftRandomTest` checks every one of them, and the test's own `gaussian` and synthetic-year draws,
// against `engine-differential/random.txt`, written by the Swift generator on that toolchain. A new
// toolchain means regenerating that file and re-running the test.

/** The upstream test's `SplitMix64` (`HeadacheEvaluationTests.swift:23`): a 64-bit state, wrapping arithmetic. */
internal class SplitMix64(seed: ULong) {
    var state: ULong = seed
        private set

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /** Swift's `next(upperBound:)` (Lemire): uniform in `0 until upperBound`, [upperBound] > 0. */
    fun next(upperBound: ULong): ULong {
        require(upperBound != 0uL) { "upperBound cannot be zero" }
        var random = next()
        var low = random * upperBound
        if (low < upperBound) {
            val threshold = (0uL - upperBound) % upperBound
            while (low < threshold) {
                random = next()
                low = random * upperBound
            }
        }
        return unsignedMultiplyHigh(random, upperBound)
    }

    /** Swift's `Int.random(in: lo...hi, using:)` (Swift `Int` is 64-bit, hence `Long`). */
    fun int(lo: Long, hi: Long): Long {
        require(lo <= hi) { "Range requires lowerBound <= upperBound" }
        val delta = (hi - lo).toULong() // wrapping, as Swift's Magnitude(truncatingIfNeeded:)
        if (delta == ULong.MAX_VALUE) return next().toLong()
        return (lo.toULong() + next(delta + 1uL)).toLong()
    }

    /** Swift's `Int.random(in: lo..<hi, using:)`. */
    fun intBelow(lo: Long, hi: Long): Long {
        require(lo < hi) { "Can't get random value with an empty range" }
        return (lo.toULong() + next((hi - lo).toULong())).toLong()
    }

    /** Swift's `Double.random(in: lo..<hi, using:)`. */
    fun double(lo: Double, hi: Double): Double {
        require(lo < hi) { "Can't get random value with an empty range" }
        val delta = hi - lo
        require(delta.isFinite()) { "There is no uniform distribution on an infinite range" }
        while (true) {
            val rand = next() and ((1uL shl 53) - 1uL)
            val unit = rand.toDouble() * (Math.ulp(1.0) / 2)
            val r = delta * unit + lo
            if (r != hi) return r
        }
    }

    /** Swift's `MutableCollection.shuffle(using:)`, the forward Fisher–Yates, in place. */
    fun <T> shuffle(xs: MutableList<T>) {
        if (xs.size <= 1) return
        var amount = xs.size
        var current = 0
        while (amount > 1) {
            val random = intBelow(0, amount.toLong()).toInt()
            amount -= 1
            val other = current + random
            val t = xs[current]
            xs[current] = xs[other]
            xs[other] = t
            current += 1
        }
    }

    private companion object {
        /** The high 64 bits of the 128-bit product of two unsigned words (JDK 17 has only the signed form). */
        fun unsignedMultiplyHigh(a: ULong, b: ULong): ULong {
            val x = a.toLong()
            val y = b.toLong()
            return (Math.multiplyHigh(x, y) + ((x shr 63) and y) + ((y shr 63) and x)).toULong()
        }
    }
}

/**
 * The upstream test's `gaussian` (`HeadacheEvaluationTests.swift:48-51`), Box–Muller over Foundation's
 * `log` and `cos`; here `StrictMath`'s, which can differ from the platform's in the last bit
 * (`SwiftRandomTest` lists every such draw).
 */
internal fun gaussian(rng: SplitMix64): Double {
    val u1 = rng.double(1e-12, 1.0)
    val u2 = rng.double(0.0, 1.0)
    return kotlin.math.sqrt(-2 * StrictMath.log(u1)) * StrictMath.cos(2 * Math.PI * u2)
}

/** Which days of a synthetic year are positive, and each day's index. */
internal class SyntheticYearDraws(val positive: List<Boolean>, val indices: List<Int>)

/**
 * The random half of the upstream test's `makeYear` (`HeadacheEvaluationTests.swift:73-88`): which of
 * [count] days are positive ([positives] of them, shuffled), then each day's index — a latent N(0, 1),
 * shifted by [dPrime] on positive days, mapped to `max(0, round(20·latent + 5))`.
 */
internal fun syntheticYearDraws(count: Int, positives: Int, dPrime: Double, rng: SplitMix64): SyntheticYearDraws {
    val isPositive = MutableList(count) { it < minOf(positives, count) }
    rng.shuffle(isPositive)
    val indices = List(count) { i ->
        val latent = gaussian(rng) + (if (isPositive[i]) dPrime else 0.0)
        maxOf(0, roundHalfAwayFromZero(20 * latent + 5).toInt())
    }
    return SyntheticYearDraws(isPositive.toList(), indices)
}

/**
 * The complementary error function, for the upstream p-value test's normal approximation
 * (`HeadacheEvaluationTests.swift:228`, Foundation's `erfc`). Not in the JDK: for |x| < 2.5 from the
 * all-positive series `erf(x) = 2/√π · e^(−x²) · Σ (2x²)^n · x / (1·3·…·(2n+1))`, beyond it from the
 * continued fraction for erfc (evaluated by the modified Lentz method); erfc(−x) = 2 − erfc(x).
 * `SwiftRandomTest` measures it against Foundation's values and states the tolerance it holds to.
 */
internal fun erfc(x: Double): Double {
    if (x.isNaN()) return x
    if (x < 0) return 2 - erfc(-x)
    if (x < 2.5) {
        var term = x
        var sum = x
        var n = 0
        while (true) {
            n++
            term *= 2 * x * x / (2 * n + 1)
            val next = sum + term
            if (next == sum) break
            sum = next
        }
        return 1 - 2 / kotlin.math.sqrt(Math.PI) * StrictMath.exp(-x * x) * sum
    }
    if (x > 27.3) return 0.0 // e^(−x²) underflows below the least subnormal
    // erfc(x) = e^(−x²)/√π · 1/(x + (1/2)/(x + 1/(x + (3/2)/(x + 2/(x + …)))))
    val tiny = 1e-300
    var f = x
    var c = x
    var d = 0.0
    var k = 1
    while (k < 500) {
        val a = k / 2.0
        d = x + a * d
        if (d == 0.0) d = tiny
        c = x + a / c
        if (c == 0.0) c = tiny
        d = 1 / d
        val delta = c * d
        f *= delta
        if (kotlin.math.abs(delta - 1) < 1e-16) break
        k++
    }
    return StrictMath.exp(-x * x) / kotlin.math.sqrt(Math.PI) / f
}
